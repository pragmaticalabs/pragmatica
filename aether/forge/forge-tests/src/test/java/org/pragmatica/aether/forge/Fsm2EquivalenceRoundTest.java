// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.forge.EquivalenceComparison.Arm;
import org.pragmatica.aether.forge.EquivalenceComparison.Divergence;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.repository.maven.MavenLocalRepoLocator;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// One F4 equivalence ROUND (FSM replacement plan 3.3, F4; phase-3 design 9.5 criterion 4, 9.6; #2763): the scenario `-Df4.scenario` runs
/// under LEGACY and under FSM2 in one JVM, the arm order alternating with `-Df4.round` (even: LEGACY first), and the FSM2 arm's record is passed
/// through the divergent control. One invocation = one round; the evidence is the first N rounds at a SHA, all reported.
///
/// Per arm: a 1 s committed-KV sampler (the invariant monitor's input), the O4 batch counter over every node's commit ring (distinct slots
/// whose effects touch the union key families), the O5 placement time, the O6 hygiene counters, and a wait for quiescence (design 9.6 (c):
/// no family mutation for 2 x the reconcile interval, no open apply, no outcome IN_PROGRESS). Then, per round:
/// - **O4 control**: one scripted batch (a put of a control outcome key) must move the counter by exactly 1 slot; removed afterwards;
/// - **oracle** (9.6) over the LEGACY arm's quiescent point, both D-8 (e) controls required;
/// - **divergent control**: every family in `EquivalenceDivergentControl.FAMILIES` must report a divergence FSM2 does not show.
///
/// Verdict, written to `-Df4.out` (default `target/f4`): VOID (a control did not fire: the round says nothing), DIVERGE (LEGACY and FSM2 differ,
/// or the oracle disagrees), PASS. The test passes only on PASS. Per arm: UTC start/end and the host's load average (the queue state is
/// recorded by the runner script around the invocation, since bb-queue has no exclusive mode).
///
/// Restart scenarios: #2625 (a same-id restart re-vote) is a known confounder; a divergence there is re-run after #2625 merges before it is
/// attributed to the FSM.
@Tag("Heavy")
@EnabledIfSystemProperty(named = "f4.scenario", matches = ".+")
class Fsm2EquivalenceRoundTest {
    private static final Duration WAIT = Duration.ofSeconds(240);
    private static final long TICK_MS = Long.getLong("f4.tick.ms", 30_000L);
    private static final long QUIET_MS = Long.getLong("f4.quiet.ms", 2 * TICK_MS);
    private static final long QUIESCENCE_BOUND_MS = Long.getLong("f4.quiescence.bound.ms", 600_000L);
    private static final EquivalenceMonitor MONITOR = new EquivalenceMonitor(TICK_MS, Long.getLong("f4.scale.window.ms", 2 * TICK_MS));
    private static final BlueprintId O4_CONTROL = BlueprintId.blueprintId("org.f4.control:o4-scripted-batch:1.0.0").unwrap();
    private static final long PARTITION_MS = Long.getLong("f4.partition.ms", 20_000L);
    private static final String BLUEPRINT_ID = "forge.test:f4-equivalence:1.0.0";

    private final HttpOperations http = jdkHttpOperations();
    private final List<String> report = new ArrayList<>();
    private final Map<DeploymentEngine, Boolean> monitorLive = new HashMap<>();

    /// The scenarios of design 9.3 that this harness drives. Node counts leave room to re-place after a loss (one instance per node).
    enum Scenario {
        E01(3), E02(5), E03(3), E04(4), E05(4), E06(5), E07(4), E09(3), E10(4), E11(4);

        final int nodes;

        Scenario(int nodes) {
            this.nodes = nodes;
        }
    }

    @Test
    void round() {
        var scenario = Scenario.valueOf(System.getProperty("f4.scenario").replace("-", ""));
        var round = Integer.getInteger("f4.round", 0);
        var engines = round % 2 == 0 ? List.of(DeploymentEngine.LEGACY, DeploymentEngine.FSM2) : List.of(DeploymentEngine.FSM2, DeploymentEngine.LEGACY);
        var arms = new HashMap<DeploymentEngine, Arm>();
        var oracle = new ArrayList<EquivalenceOracle.Verdict>();
        var o4Controls = new HashMap<DeploymentEngine, Boolean>();

        log("F4 round scenario=%s round=%d order=%s tick=%d quiet=%d".formatted(scenario, round, engines, TICK_MS, QUIET_MS));
        for (var index = 0; index < engines.size(); index++) {
            var engine = engines.get(index);

            try {
                arms.put(engine, runArm(scenario, engine, 33000 + 400 * index, oracle, o4Controls));
            } catch (RuntimeException | AssertionError e) {
                log("arm %s ERROR %s".formatted(engine, e));
                log("VERDICT ERROR");
                write(scenario, round);
                throw e;
            }
        }
        var legacy = arms.get(DeploymentEngine.LEGACY);
        var fsm2 = arms.get(DeploymentEngine.FSM2);
        var control = EquivalenceDivergentControl.of(fsm2, legacy, MONITOR);
        var fsm2Divergences = EquivalenceComparison.compare(legacy, fsm2, MONITOR);
        var controlDivergences = EquivalenceComparison.compare(legacy, control, MONITOR);
        var silent = new ArrayList<>(EquivalenceDivergentControl.silentFamilies(fsm2Divergences, controlDivergences));

        o4Controls.forEach((engine, fired) -> {
            if (!fired) {
                silent.add("O4-scripted-batch(" + engine + ")");
            }
        });
        monitorLive.forEach((engine, fired) -> {
            if (!fired) {
                silent.add("MONITOR-live-plant(" + engine + ")");
            }
        });
        oracle.forEach(verdict -> {
            if (!verdict.controlsFired()) {
                silent.add("ORACLE-controls");
            }
        });
        var divergences = new ArrayList<>(fsm2Divergences.stream().map(Divergence::describe).toList());

        oracle.forEach(verdict -> verdict.disagreements().forEach(submit -> divergences.add("ORACLE: " + submit.describe())));
        var verdict = !silent.isEmpty() ? "VOID" : divergences.isEmpty() ? "PASS" : "DIVERGE";

        log("arm legacy: o4=%d o5=%d ms o6=%s samples=%d".formatted(legacy.o4Batches(), legacy.o5PlacementMs(), legacy.o6(), legacy.samples().size()));
        log("arm fsm2:   o4=%d o5=%d ms o6=%s samples=%d".formatted(fsm2.o4Batches(), fsm2.o5PlacementMs(), fsm2.o6(), fsm2.samples().size()));
        oracle.forEach(result -> log(result.describe()));
        log("control divergences: " + controlDivergences.stream().map(Divergence::describe).toList());
        log("silent controls: " + silent);
        divergences.forEach(divergence -> log("DIVERGENCE " + divergence));
        log("VERDICT " + verdict);
        write(scenario, round);
        assertThat(verdict).as(String.join("\n", report)).isEqualTo("PASS");
    }

    private Arm runArm(Scenario scenario,
                       DeploymentEngine engine,
                       int basePort,
                       List<EquivalenceOracle.Verdict> oracle,
                       Map<DeploymentEngine, Boolean> o4Controls) {
        var cluster = emberCluster(scenario.nodes, basePort, basePort + 100, basePort + 200, "f4" + engine.name().toLowerCase().charAt(0));

        cluster.withDeploymentEngine(engine);
        log("arm %s start %s load=%s".formatted(engine, Instant.now(), loadAverage()));
        LifecycleAwait.settled("F4 cluster start (" + engine + ")", cluster, cluster.start());
        try {
            awaitTrue("a leader", WAIT, () -> cluster.currentLeader().isPresent());
            awaitTrue("quorum on every node", WAIT, () -> healthy(cluster));
            var recorder = new Recorder(cluster);

            recorder.start();
            var placementMs = scenario(scenario, cluster);
            var quiescent = awaitQuiescence(cluster);

            recorder.stop();
            var o4 = recorder.batches();
            var kv = new EquivalenceKv(snapshot(cluster));
            var live = cluster.allNodes().stream().map(AetherNode::self).collect(Collectors.toUnmodifiableSet());

            if (!quiescent) {
                log("arm %s NOT QUIESCENT within %d ms".formatted(engine, QUIESCENCE_BOUND_MS));
            }
            o4Controls.put(engine, o4ScriptedBatchCounted(cluster));
            if (engine == DeploymentEngine.LEGACY) {
                oracle.add(EquivalenceOracle.check(kv, leaderValue(cluster), live));
            }
            var arm = new Arm(engine == DeploymentEngine.LEGACY ? "legacy" : "fsm2",
                              kv,
                              live,
                              recorder.samples(),
                              quiescent ? o4 : -1,
                              placementMs,
                              o6(cluster));

            monitorLiveControl(cluster, engine);
            log("arm %s end %s load=%s".formatted(engine, Instant.now(), loadAverage()));

            return arm;
        } finally {
            LifecycleAwait.bestEffort("F4 cluster stop (" + engine + ")", cluster, cluster.stop());
        }
    }

    // -- scenarios (design 9.3) ------------------------------------------------------------------------------------

    /// Runs the scenario; returns O5, the time from the publish to the first instant (polled every 250 ms, the same for both arms) at which the
    /// published artifact's ACTIVE count reached its target, or -1 when the scenario places nothing (E-09).
    private long scenario(Scenario scenario, EmberCluster cluster) {
        var echo = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();

        return switch (scenario) {
            case E01 -> publishAndPlace(cluster, echo, 3);
            case E02 -> {
                var placement = publishAndPlace(cluster, echo, 3);

                // Design 9.3 says 3 -> 5 -> 2; the blueprint floor (#1495) refuses 2, so the step down goes to the floor, 3.
                expectAccepted(scale(cluster, 5));
                awaitTrue("5 ACTIVE", WAIT, () -> active(cluster, echo) == 5);
                expectAccepted(scale(cluster, 3));
                awaitTrue("3 ACTIVE", WAIT, () -> active(cluster, echo) == 3);
                yield placement;
            }
            case E03 -> {
                var placement = publishAndPlace(cluster, echo, 3);

                send(HttpRequest.newBuilder().uri(mgmt(cluster, "/api/v1/blueprints/" + BLUEPRINT_ID)).DELETE().timeout(Duration.ofSeconds(10)).build());
                awaitTrue("the target is removed", WAIT, () -> new EquivalenceKv(snapshot(cluster)).targets().isEmpty());
                yield placement;
            }
            case E04 -> {
                var leader = cluster.currentLeader().unwrap();
                var at = publish(cluster, blueprint(echo, 3));

                cluster.killNode(leader, false).await(TimeSpan.timeSpan(60).seconds());
                awaitTrue("a new leader", WAIT, () -> cluster.currentLeader().filter(id -> !id.equals(leader)).isPresent());
                awaitTrue("3 ACTIVE after the leader kill", WAIT, () -> active(cluster, echo) == 3);
                yield System.currentTimeMillis() - at;
            }
            case E05, E07 -> {
                var placement = publishAndPlace(cluster, echo, 3);
                var victim = hostOfAnInstance(cluster, echo);

                cluster.killNode(victim, scenario == Scenario.E07).await(TimeSpan.timeSpan(60).seconds());
                awaitTrue("3 ACTIVE after losing " + victim, WAIT, () -> active(cluster, echo) == 3);
                yield placement;
            }
            case E06 -> {
                var placement = publishAndPlace(cluster, echo, 3);
                // Quorum loss (3 of 5 silenced) and heal. Comparison scope: both engines must converge to the same committed state; the
                // FSM2-only asserts of design 9.3 (no extra suspend on a resync, no re-incarnation) are NOT checked here.
                var silenced = cluster.allNodes().stream().map(node -> node.self().id()).sorted().limit(3).toList();

                silenced.forEach(id -> cluster.getNode(id).onPresent(node -> node.blackhole(true)));
                sleep(PARTITION_MS);
                silenced.forEach(id -> cluster.getNode(id).onPresent(node -> node.blackhole(false)));
                awaitTrue("quorum after the heal", WAIT, () -> cluster.currentLeader().isPresent() && healthy(cluster));
                awaitTrue("3 ACTIVE after the heal", WAIT, () -> active(cluster, echo) == 3);
                yield placement;
            }
            case E10 -> {
                var placement = publishAndPlace(cluster, echo, 3);
                // One core silenced then healed in the same process. NOT the arm-C harness (the victim's self-fence is not swallowed), and the
                // FSM2-only asserts (J3 ADMITTED(e_d + 1), kept rows adopted) are not checked: this is the engine-comparison part only.
                var victim = hostOfAnInstance(cluster, echo);

                cluster.getNode(victim).onPresent(node -> node.blackhole(true));
                sleep(PARTITION_MS);
                cluster.getNode(victim).onPresent(node -> node.blackhole(false));
                awaitTrue("quorum after the heal", WAIT, () -> cluster.currentLeader().isPresent() && healthy(cluster));
                awaitTrue("3 ACTIVE after the heal", WAIT, () -> active(cluster, echo) == 3);
                yield placement;
            }
            case E11 -> {
                // The leader is silenced while a goal is in flight, then heals (a same-node or a changed election, whichever the cluster picks).
                var leader = cluster.currentLeader().unwrap();
                var at = publish(cluster, blueprint(echo, 3));

                cluster.getNode(leader).onPresent(node -> node.blackhole(true));
                sleep(PARTITION_MS);
                cluster.getNode(leader).onPresent(node -> node.blackhole(false));
                awaitTrue("quorum after the heal", WAIT, () -> cluster.currentLeader().isPresent() && healthy(cluster));
                awaitTrue("3 ACTIVE after the leader heal", WAIT, () -> active(cluster, echo) == 3);
                yield System.currentTimeMillis() - at;
            }
            case E09 -> {
                // A fatal failure under ALL_OR_NOTHING: a second slice whose jar is present but carries no slice manifest (unloadable, so a fatal
                // load failure, not a publish-time rejection); the verdict is whatever the durable record says.
                installUnloadableJar();
                var at = publish(cluster, blueprint(echo, 3) + """

                    [[slices]]
                    artifact = "org.f4.broken:unloadable:1.0.0"
                    instances = 3
                    """);

                awaitTrue("a terminal outcome", WAIT, () -> new EquivalenceKv(snapshot(cluster)).outcomes()
                                                                                              .values()
                                                                                              .stream()
                                                                                              .anyMatch(outcome -> outcome.status() != AetherValue.DeploymentOutcomeStatus.IN_PROGRESS));
                yield -1L;
            }
        };
    }

    /// Writes a jar that is a valid archive without any slice manifest entries into the local Maven repository the nodes resolve from (the cluster's
    /// own artifact store is not consulted by a blueprint publish: a PUT /repository upload answered "Artifact not found" for 30 s). E-09's fatal trigger.
    private void installUnloadableJar() {
        var jar = new ByteArrayOutputStream();

        try (var out = new JarOutputStream(jar)) {
            out.putNextEntry(new ZipEntry("org/f4/broken/Nothing.txt"));
            out.write("not a slice".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            var target = Path.of(MavenLocalRepoLocator.findLocalRepository(), "org/f4/broken/unloadable/1.0.0/unloadable-1.0.0.jar");

            Files.createDirectories(target.getParent());
            Files.write(target, jar.toByteArray());
            log("installed unloadable jar at " + target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Publishes and waits for placement; returns O5 in ms.
    private long publishAndPlace(EmberCluster cluster, Artifact artifact, int instances) {
        var at = publish(cluster, blueprint(artifact, instances));

        awaitTrue(instances + " ACTIVE", WAIT, () -> active(cluster, artifact) == instances);

        return System.currentTimeMillis() - at;
    }

    private static String blueprint(Artifact artifact, int instances) {
        return """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, artifact.asString(), instances);
    }

    private long publish(EmberCluster cluster, String blueprint) {
        var at = System.currentTimeMillis();
        var response = "";

        // A just-uploaded artifact becomes resolvable asynchronously: retry only the "not found" answer, for at most 30 s.
        for (var attempt = 0; attempt < 15; attempt++) {
            response = send(HttpRequest.newBuilder()
                                       .uri(mgmt(cluster, "/api/v1/blueprints"))
                                       .header("Content-Type", "application/toml")
                                       .POST(HttpRequest.BodyPublishers.ofString(blueprint))
                                       .timeout(Duration.ofSeconds(10))
                                       .build());
            if (!response.contains("Artifact not found")) {
                break;
            }
            sleep(2_000L);
        }
        log("publish -> " + response);

        return at;
    }

    private String scale(EmberCluster cluster, int instances) {
        var body = "{\"artifact\": \"%s\", \"instances\": %d}".formatted(TestArtifacts.ECHO_SLICE, instances);

        return send(HttpRequest.newBuilder()
                               .uri(mgmt(cluster, "/api/v1/scale"))
                               .header("Content-Type", "application/json")
                               .POST(HttpRequest.BodyPublishers.ofString(body))
                               .timeout(Duration.ofSeconds(10))
                               .build());
    }

    private void expectAccepted(String response) {
        log("scale -> " + response);
        assertThat(response).as("SETUP: scale accepted").doesNotContain("\"error\"");
    }

    private static String hostOfAnInstance(EmberCluster cluster, Artifact artifact) {
        var leader = cluster.currentLeader().or("");

        return new EquivalenceKv(snapshot(cluster)).instances()
                                                   .stream()
                                                   .filter(instance -> instance.base().equals(artifact.base()) && instance.state().equals("ACTIVE"))
                                                   .map(instance -> instance.node().id())
                                                   .filter(id -> !id.equals(leader))
                                                   .sorted()
                                                   .findFirst()
                                                   .orElseThrow();
    }

    // -- instruments -------------------------------------------------------------------------------------------------

    /// The per-arm recorder: the 1 s KV sampler and the O4 ring reader (every 200 ms, over every live node's commit ring, union of slots).
    private static final class Recorder {
        private final EmberCluster cluster;
        private final long startMs = System.currentTimeMillis();
        private final List<EquivalenceMonitor.Sample> samples = new CopyOnWriteArrayList<>();
        private final Set<Long> familySlots = new ConcurrentSkipListSet<>();
        private final Map<String, Long> nextSlot = Collections.synchronizedMap(new HashMap<>());
        private final Set<String> gaps = new ConcurrentSkipListSet<>();
        private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        Recorder(EmberCluster cluster) {
            this.cluster = cluster;
        }

        void start() {
            executor.scheduleAtFixedRate(this::sample, 0, 1, TimeUnit.SECONDS);
            executor.scheduleAtFixedRate(this::readRings, 0, 200, TimeUnit.MILLISECONDS);
        }

        void stop() {
            executor.shutdownNow();
            readRings();
        }

        private void sample() {
            try {
                samples.add(new EquivalenceMonitor.Sample(System.currentTimeMillis() - startMs, new EquivalenceKv(snapshot(cluster))));
            } catch (RuntimeException e) {
                // A node stopping mid-read: the next second samples again.
            }
        }

        private synchronized void readRings() {
            for (var node : cluster.allNodes()) {
                try {
                    var ring = node.kvStore().replicaRing();
                    var key = node.self().id();
                    var expected = nextSlot.get(key);

                    if (expected != null && ring.floor() > expected) {
                        gaps.add(key + ":[" + expected + "," + ring.floor() + ")");
                    }
                    var last = expected == null ? -1L : expected - 1;

                    for (var entry : ring.entries()) {
                        if (entry.slot() > last) {
                            if (touchesFamily(entry)) {
                                familySlots.add(entry.slot());
                            }
                            last = entry.slot();
                        }
                    }
                    nextSlot.put(key, last + 1);
                } catch (RuntimeException e) {
                    // A node stopping mid-read.
                }
            }
        }

        List<EquivalenceMonitor.Sample> samples() {
            return List.copyOf(samples);
        }

        /// A gap on one node is covered when another node read the same window; the count is void only when every node had one.
        long batches() {
            var nodes = nextSlot.size();
            var gappedNodes = gaps.stream().map(gap -> gap.substring(0, gap.indexOf(':'))).distinct().count();

            return nodes > 0 && gappedNodes >= nodes ? -1 : familySlots.size();
        }
    }

    static boolean touchesFamily(ReplicaEntry entry) {
        return entry.effects().stream().anyMatch(effect -> switch (effect) {
            case KVCommand.Put<?, ?> put -> EquivalenceKv.inO4Family(put.key());
            case KVCommand.Remove<?> remove -> EquivalenceKv.inO4Family(remove.key());
            case KVCommand.LeaderTransaction<?, ?> transaction -> transaction.mutations().stream().anyMatch(mutation -> EquivalenceKv.inO4Family(mutation.key()));
            default -> false;
        });
    }

    /// The scripted extra batch (design 9.5 O4 positive control): one leader transaction putting a control outcome key. Counted = exactly one
    /// slot of the ring carries it. Removed afterwards (outside the arm's count, which was taken before).
    private boolean o4ScriptedBatchCounted(EmberCluster cluster) {
        var node = leader(cluster);
        var key = AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(O4_CONTROL);
        var value = new AetherValue.DeploymentOutcomeValue(AetherValue.DeploymentOutcomeStatus.SUCCEEDED, List.of(), "", 0L, AetherValue.DeploymentOutcomeValue.FIRST_VERSION, "f4");
        var before = node.kvStore().replicaRing().nextSlot();

        commit(node, key, Option.none(), Option.some(value));
        awaitTrue("the scripted batch commits", Duration.ofSeconds(30), () -> node.kvStore().get(key).isPresent());
        var slots = node.kvStore()
                        .replicaRing()
                        .entries()
                        .stream()
                        .filter(entry -> entry.slot() >= before)
                        .filter(entry -> entry.effects().stream().anyMatch(effect -> effect instanceof KVCommand.Put<?, ?> put && put.key().equals(key)))
                        .map(ReplicaEntry::slot)
                        .collect(Collectors.toCollection(TreeSet::new));

        commit(node, key, Option.some(value), Option.none());
        log("O4 scripted batch: slots carrying it=" + slots);

        return slots.size() == 1;
    }

    /// The monitor hook's control (CTO ruling on plan 3.3): each invariant's violation is committed into the LIVE store, read back through the
    /// same snapshot path the recorder uses, and sampled every 250 ms and checked by a monitor with 250 ms bounds (an engine may clean a planted row within a tick); all four must trip. Runs after the arm's data is taken, so
    /// it contaminates no comparison; the rows are removed again. A miss is a silent control.
    private void monitorLiveControl(EmberCluster cluster, DeploymentEngine engine) {
        var node = leader(cluster);
        var rows = EquivalenceDivergentControl.livePlant();
        var monitor = new EquivalenceMonitor(250L, 250L);
        var samples = new ArrayList<EquivalenceMonitor.Sample>();
        var start = System.currentTimeMillis();

        rows.forEach((key, value) -> commit(node, key, Option.none(), Option.some(value)));
        for (var index = 0; index < 32; index++) {
            samples.add(new EquivalenceMonitor.Sample(System.currentTimeMillis() - start, new EquivalenceKv(snapshot(cluster))));
            sleep(250L);
        }
        rows.forEach((key, value) -> commit(node, key, Option.some(value), Option.none()));
        var tripped = monitor.check(samples).stream().map(EquivalenceMonitor.Violation::invariant).collect(Collectors.toCollection(TreeSet::new));

        log("monitor live plant (%s): tripped=%s; plant rows seen in %d of %d samples (iii: %d)".formatted(engine,
                                                                                                          tripped,
                                                                                                          samples.stream().filter(sample -> sample.kv().entries().keySet().containsAll(rows.keySet())).count(),
                                                                                                          samples.size(),
                                                                                                          samples.stream().filter(sample -> !sample.kv().departed().isEmpty()).count()));
        monitorLive.put(engine, tripped.containsAll(Set.of("i", "ii", "iii", "iv")));
    }

    private static void commit(AetherNode node, AetherKey key, Option<AetherValue> expected, Option<AetherValue> replacement) {
        var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  UUID.randomUUID().toString(),
                                                                                  authority,
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<>(key, expected, replacement)));

        node.<Object> apply(List.of(transaction)).await(TimeSpan.timeSpan(30).seconds());
    }

    /// Design 9.6 (c): no family mutation for QUIET_MS (read through the family digest every second), no open apply, no outcome IN_PROGRESS,
    /// every instance in a steady state.
    private boolean awaitQuiescence(EmberCluster cluster) {
        var deadline = System.currentTimeMillis() + QUIESCENCE_BOUND_MS;
        var lastDigest = 0;
        var quietSince = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            var kv = new EquivalenceKv(snapshot(cluster));
            var digest = familyDigest(kv);
            var steady = kv.applies().isEmpty()
                         && kv.outcomes().values().stream().noneMatch(outcome -> outcome.status() == AetherValue.DeploymentOutcomeStatus.IN_PROGRESS)
                         && kv.instances().stream().allMatch(instance -> Set.of("ACTIVE", "LOADED", "FAILED").contains(instance.state()));

            if (digest != lastDigest || !steady) {
                lastDigest = digest;
                quietSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - quietSince >= QUIET_MS) {
                return true;
            }
            sleep(1_000L);
        }

        return false;
    }

    private static int familyDigest(EquivalenceKv kv) {
        return kv.entries().entrySet().stream().filter(entry -> EquivalenceKv.inO4Family(entry.getKey())).collect(Collectors.toSet()).hashCode();
    }

    private static Map<String, Double> o6(EmberCluster cluster) {
        var o6 = new HashMap<String, Double>();

        o6.put("lost", cluster.allNodes().stream().mapToDouble(node -> Fsm2Sensors.armCount(node, "lost")).sum());
        o6.put("settled_unknown", cluster.allNodes().stream().mapToDouble(node -> Fsm2Sensors.armCount(node, "settled_unknown")).sum());
        o6.put("closed",
               cluster.allNodes()
                      .stream()
                      .mapToDouble(node -> node.deploymentWiring()
                                               .meters()
                                               .find("aether_deployment_fsm_inputs_total")
                                               .tags("result", "closed")
                                               .counters()
                                               .stream()
                                               .mapToDouble(counter -> counter.count())
                                               .sum())
                      .sum());

        return o6;
    }

    // -- cluster reads --------------------------------------------------------------------------------------------

    /// The committed deployment state: the store also holds non-Aether keys (the `LeaderKey`), which no instrument reads.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<AetherKey, AetherValue> snapshot(EmberCluster cluster) {
        var aether = new HashMap<AetherKey, AetherValue>();

        ((Map<Object, Object>) (Map) leader(cluster).kvStore().snapshot()).forEach((key, value) -> {
            if (key instanceof AetherKey aetherKey && value instanceof AetherValue aetherValue) {
                aether.put(aetherKey, aetherValue);
            }
        });

        return aether;
    }

    private static AetherNode leader(EmberCluster cluster) {
        return cluster.currentLeader().flatMap(cluster::getNode).or(() -> cluster.allNodes().getFirst());
    }

    private static LeaderValue leaderValue(EmberCluster cluster) {
        return leader(cluster).kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
    }

    private static long active(EmberCluster cluster, Artifact artifact) {
        return new EquivalenceKv(snapshot(cluster)).active().getOrDefault(artifact.base(), 0L);
    }

    private boolean healthy(EmberCluster cluster) {
        return cluster.status().nodes().stream().allMatch(node -> get(node.mgmtPort(), "/api/v1/health").contains("\"quorum\":true"));
    }

    private static URI mgmt(EmberCluster cluster, String path) {
        var port = cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());

        return URI.create("http://localhost:" + port + path);
    }

    private String get(int port, String path) {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().timeout(Duration.ofSeconds(10)).build());
    }

    private String send(HttpRequest request) {
        return http.sendString(request).await().map(HttpResult::body).or("{\"error\":\"request failed\"}");
    }

    // -- plumbing ----------------------------------------------------------------------------------------------------

    private static void awaitTrue(String what, Duration bound, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + bound.toMillis();

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("F4 timed out waiting: " + what);
            }
            sleep(250L);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String loadAverage() {
        try {
            return Files.readString(Path.of("/proc/loadavg")).strip();
        } catch (IOException | RuntimeException e) {
            return "n/a";
        }
    }

    private void log(String line) {
        var stamped = Instant.now() + " " + line;

        report.add(stamped);
        System.out.println("F4 " + stamped);
    }

    private void write(Scenario scenario, int round) {
        var dir = Path.of(System.getProperty("f4.out", "target/f4"));

        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("%s-round%02d.txt".formatted(scenario, round)), report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
