// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// MANUAL MEASUREMENT, excluded from CI by the `Benchmark` tag (root pom `surefire.excludedGroups`; `tools/ember-shard.py` skips top-level `@Tag("Benchmark")` classes). See README-m1-measurement.md in this package.
///
/// M-1 counting arm (#2512): committed KV mutations per key family, leader transactions and slots per node event, at worker count N.
///
/// One JVM run is one N. System properties: `m1.n` (workers), `m1.classes` (comma list), `m1.reps`, `m1.out` (JSON-lines file),
/// `m1.base` (first port), `m1.communitySize`, `m1.label`. Counts only: the windows have a length because an event has a tail, and the
/// idle background around it is measured on both sides and reported next to the raw count; no figure here is a latency.
@Tag("Benchmark")
class M1MeasureTest {
    private static final int N = Integer.getInteger("m1.n", 10);
    private static final int REPS = Integer.getInteger("m1.reps", 3);
    private static final int CORES = 5;
    private static final long IDLE_WINDOW_MS = Long.getLong("m1.idleMs", 45_000L);
    private static final long MIN_EVENT_MS = Long.getLong("m1.minEventMs", 30_000L);
    private static final long QUIET_MS = Long.getLong("m1.quietMs", 20_000L);
    private static final long MAX_EVENT_MS = Long.getLong("m1.maxEventMs", 240_000L);
    private static final String LABEL = System.getProperty("m1.label", "run");
    private static final String SOURCE = System.getProperty("m1.source", "default");
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(300).seconds();
    private static final TimeSpan APPLY_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(180).seconds();

    private EmberCluster cluster;
    private final List<M1Instrument> instruments = new ArrayList<>();
    private final List<String> coreIds = new ArrayList<>();
    private final Deque<String> workers = new ConcurrentLinkedDeque<>();
    private final Set<String> idleOps = ConcurrentHashMap.newKeySet();
    private PrintWriter out;
    private int controlSerial;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
        if (out != null) {
            out.close();
        }
    }

    @Test
    @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.HOURS)
    void measure() throws Exception {
        var path = Path.of(System.getProperty("m1.out", "/tmp/m1-" + LABEL + "-n" + N + ".jsonl"));

        Files.createDirectories(path.toAbsolutePath().getParent());
        out = new PrintWriter(Files.newBufferedWriter(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND), true);
        if (Arrays.stream(classList()).anyMatch(c -> c.strip().startsWith("s6"))) {
            preflightEcho();
        }
        setUp();
        buildUp();
        emit("{\"kind\":\"census\"," + header() + ",\"keys\":" + js(census()) + "}");
        for (var cls : classList()) {
            log("class " + cls.strip() + " begins");
            switch (cls.strip()) {
                case "control" -> control();
                case "idle" -> idleClass();
                case "join" -> joinClass();
                case "leave" -> leaveClass();
                case "crash" -> crashClass();
                case "kill-k" -> killKClass();
                case "kill-community" -> killCommunityClass();
                case "kill-governor" -> killGovernorClass();
                case "kill-leader" -> killLeaderClass();
                case "control-refused" -> controlRefused();
                case "core-join-leave" -> coreJoinLeaveClass();
                case "replace-worker" -> replaceWorkerClass();
                case "scale" -> scaleClass();
                case "policy-change" -> policyChangeClass();
                case "crash-replaced" -> crashReplacedClass();
                case "s6a", "s6b", "s6c", "s6d" -> s6Class(cls.strip());
                case "preflight-config" -> preflightConfig();
                default -> throw new IllegalArgumentException("unknown class " + cls);
            }
        }
        emit("{\"kind\":\"census-end\"," + header() + ",\"keys\":" + js(census()) + "}");
    }

    private static String[] classList() {
        return System.getProperty("m1.classes", "control,idle,join,leave,crash,kill-k,kill-community,kill-governor,kill-leader").split(",");
    }

    // ---- setup -------------------------------------------------------------------------------------------------------------------

    private void setUp() throws Exception {
        int base = Integer.getInteger("m1.base", 36000);

        cluster = emberCluster(CORES, base, base + 1000, base + 2000, "m1");
        check(cluster.withAdditionalNodeSlots(Math.min(256, N + 60)).isSuccess(), "slots");
        check(cluster.withPortStride(3).isSuccess(), "stride");
        if (Boolean.getBoolean("m1.raisedSwim")) {
            cluster.withRaisedSwimTimeouts();
        }
        var started = cluster.start().await(START_BOUND);

        check(started.isSuccess(), "cluster start " + started);
        await("leader", () -> cluster.currentLeader().isPresent(), 120_000);
        for (var node : cluster.allNodes()) {
            coreIds.add(node.self().id());
            instruments.add(M1Instrument.install(node));
        }
        await("cluster config committed", () -> committedConfig().isPresent(), 120_000);
        commitPolicy();
        log("set up: cores=" + coreIds + " leader=" + cluster.currentLeader());
    }

    private void buildUp() {
        if (!"default".equals(SOURCE)) {
            // CTM-managed mode: the leader provisions the workers itself, so they carry the tags it counts.
            setDesired(N);
            await("provisioned to desired", () -> cluster.nodeCount() == CORES + N, 1_800_000);
            syncWorkers();
            await("every worker holds a directive", () -> directives() >= N, 600_000);
            settleLoose("build-up", 40_000, 400_000);
            return;
        }

        var pool = Executors.newFixedThreadPool(8);

        try {
            while (workers.size() < N) {
                var batch = Math.min(8, N - workers.size());
                var futures = new ArrayList<Future<String>>();

                for (int i = 0; i < batch; i++) {
                    futures.add(pool.submit(this::addWorkerNoWait));
                }
                for (var future : futures) {
                    workers.add(get(future));
                }
                log("workers " + workers.size() + "/" + N);
            }
        } finally {
            pool.shutdown();
        }
        await("every worker holds a directive", () -> directives() >= N, 600_000);
        settleLoose("build-up", 40_000, 400_000);
    }

    // ---- event classes -----------------------------------------------------------------------------------------------------------

    /// Instrument controls: one explicit put counts exactly one mutation of its family on every core, and a remove too.
    private void control() {
        for (int rep = 1; rep <= REPS; rep++) {
            final String name = "m1-control-" + (++controlSerial);

            measure("control-put", rep, Set.of(), () -> apply(new KVCommand.Put<AetherKey, AetherValue>(new AlertThresholdKey(name),
                                                                                                              AlertThresholdValue.alertThresholdValue(name, 1.0, 2.0))), 10_000, 5_000);
            measure("control-remove", rep, Set.of(), () -> apply(new KVCommand.Remove<AetherKey>(new AlertThresholdKey(name))), 10_000, 5_000);
        }
    }

    /// A leader transaction whose expectation is false is refused: +1 refused transaction, no mutation.
    private void controlRefused() {
        for (int rep = 1; rep <= REPS; rep++) {
            final String name = "m1-refused-" + (++controlSerial);

            measure("control-refused-tx", rep, Set.of(), () -> {
                var node = leader();
                var key = new AlertThresholdKey(name);
                var wrong = Option.some((AetherValue) AlertThresholdValue.alertThresholdValue(name, 9.0, 9.0));
                var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
                var tx = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, UUID.randomUUID().toString(), authority, List.of(),
                                                                                 List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key, wrong, Option.none())));
                var result = node.<Object> apply(List.of(tx)).await(APPLY_BOUND).unwrap();

                check(result.stream().anyMatch(o -> o instanceof KVCommand.TransactionResult r && !r.accepted()), "the planted transaction is refused");
            }, 10_000, 5_000);
        }
    }

    private void coreJoinLeaveClass() {
        for (int rep = 1; rep <= Math.min(REPS, 2); rep++) {
            final var core = new String[1];

            measure("core-join", rep, Set.of(), () -> {
                var added = cluster.addNode().await(START_BOUND);

                check(added.isSuccess(), "add core " + added);
                core[0] = added.unwrap().id();
                coreIds.add(core[0]);
                try {
                    instruments.add(M1Instrument.install(cluster.getNode(core[0]).unwrap()));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }, MIN_EVENT_MS, QUIET_MS);
            measure("core-leave-graceful", rep, Set.of(core[0]), () -> {
                check(cluster.killNode(core[0], true).await(STOP_BOUND).isSuccess(), "core leave " + core[0]);
                coreIds.remove(core[0]);
            }, MIN_EVENT_MS, QUIET_MS);
        }
    }

    /// The operator-driven worker replacement (#1543): the leader provisions a replacement, canaries it, retires the old one.
    private void replaceWorkerClass() {
        syncWorkers();
        for (int rep = 1; rep <= REPS; rep++) {
            measure("replace-worker", rep, Set.of(), () -> {
                var victim = workers.peekLast();

                leader().nodeReplacementService().begin(new NodeId(victim), "").await(START_BOUND).onFailure(cause -> check(false, "replacement begin: " + cause.message()));
                await("old worker gone", () -> cluster.getNode(victim).isEmpty(), 600_000);
                await("node count restored", () -> cluster.nodeCount() == coreIds.size() + N, 300_000);
            }, MIN_EVENT_MS, QUIET_MS);
            syncWorkers();
            settleLoose("after replace", 20_000, 200_000);
        }
    }

    /// CTM-managed scale: the desired worker count is the input; the leader provisions or retires through the compute provider.
    private void scaleClass() {
        int k = Math.max(1, N / 10);

        syncWorkers();
        for (int rep = 1; rep <= REPS; rep++) {
            measure("scale-up(+" + k + ")", rep, Set.of(), () -> {
                setDesired(N + k);
                await("scaled up", () -> cluster.nodeCount() == coreIds.size() + N + k, 900_000);
            }, MIN_EVENT_MS, QUIET_MS);
            syncWorkers();
            measure("scale-down(-" + k + ")", rep, Set.of(), () -> {
                setDesired(N);
                await("scaled down", () -> cluster.nodeCount() == coreIds.size() + N, 900_000);
            }, MIN_EVENT_MS, QUIET_MS);
            syncWorkers();
        }
    }

    /// With the desired count set, a crashed worker is replaced by the leader (auto-heal).
    private void crashReplacedClass() {
        syncWorkers();
        for (int rep = 1; rep <= REPS; rep++) {
            measure("crash+auto-replace", rep, Set.of(), () -> {
                var victim = workers.peekLast();

                check(cluster.blackhole(victim).await(STOP_BOUND).isSuccess(), "blackhole " + victim);
                await("victim retired and replaced", () -> cluster.getNode(victim).isEmpty() && cluster.nodeCount() == coreIds.size() + N, 900_000);
            }, MIN_EVENT_MS, QUIET_MS);
            syncWorkers();
        }
    }

    private void setDesired(int count) {
        var before = committedConfig().unwrap();

        commit(ClusterConfigKey.CURRENT, Option.some(before), Option.some(before.withDesiredCount(SOURCE, "worker", count)));
        log("desired " + SOURCE + " workers = " + count);
    }

    private void syncWorkers() {
        workers.clear();
        cluster.allNodes().stream().map(n -> n.self().id()).filter(id -> !coreIds.contains(id)).sorted().forEach(workers::add);
    }

    private void idleClass() {
        for (int rep = 1; rep <= REPS; rep++) {
            measure("idle", rep, Set.of(), () -> {}, 120_000, 120_000);
        }
    }

    private void joinClass() {
        for (int rep = 1; rep <= REPS; rep++) {
            measure("join", rep, Set.of(), () -> workers.add(addWorkerNoWait()), MIN_EVENT_MS, QUIET_MS);
        }
    }

    private void leaveClass() {
        for (int rep = 1; rep <= REPS; rep++) {
            measure("leave-graceful", rep, Set.of(), () -> {
                var victim = workers.pollLast();

                check(cluster.killNode(victim, true).await(STOP_BOUND).isSuccess(), "leave " + victim);
            }, MIN_EVENT_MS, QUIET_MS);
        }
    }

    private void crashClass() {
        for (int rep = 1; rep <= REPS; rep++) {
            final var victim = new String[1];

            measure("crash-worker", rep, Set.of(), () -> {
                victim[0] = workers.pollLast();
                check(cluster.blackhole(victim[0]).await(STOP_BOUND).isSuccess(), "blackhole " + victim[0]);
                awaitDeparture(List.of(victim[0]));
            }, MIN_EVENT_MS, QUIET_MS);
            cleanupAndRestore(List.of(victim[0]), 0, 1);
        }
    }

    /// A crash is only an event once the leader has committed the departure; with long failure-detection timeouts that is later than the
    /// minimum window, so the window is held open until the victims' directives are gone.
    private void awaitDeparture(List<String> victims) {
        await("departure committed for " + victims, () -> victims.stream().allMatch(v -> leader().kvStore().get(new ActivationDirectiveKey(new NodeId(v))).isEmpty()), 900_000);
    }

    private void killKClass() {
        int k = Math.max(2, N / 10);

        for (int rep = 1; rep <= REPS; rep++) {
            final var victims = new ArrayList<String>();

            measure("crash-k-workers(k=" + k + ")", rep, Set.of(), () -> {
                for (int i = 0; i < k; i++) {
                    victims.add(workers.pollLast());
                }
                victims.forEach(v -> check(cluster.blackhole(v).await(STOP_BOUND).isSuccess(), "blackhole " + v));
                awaitDeparture(victims);
            }, MIN_EVENT_MS, QUIET_MS);
            cleanupAndRestore(victims, 0, k);
        }
    }

    private void killCommunityClass() {
        for (int rep = 1; rep <= REPS; rep++) {
            final var victims = new ArrayList<String>();

            measure("crash-community", rep, Set.of(), () -> {
                victims.addAll(wholeCommunity());
                log("killing community members " + victims);
                victims.forEach(workers::remove);
                victims.forEach(v -> check(cluster.blackhole(v).await(STOP_BOUND).isSuccess(), "blackhole " + v));
                awaitDeparture(victims);
            }, MIN_EVENT_MS, QUIET_MS);
            cleanupAndRestore(victims, 0, victims.size());
        }
    }

    private void killGovernorClass() {
        for (int rep = 1; rep <= REPS; rep++) {
            final var victim = new String[1];

            measure("crash-governor", rep, Set.of(), () -> {
                victim[0] = someGovernorWorker();
                workers.remove(victim[0]);
                check(cluster.blackhole(victim[0]).await(STOP_BOUND).isSuccess(), "blackhole " + victim[0]);
                awaitDeparture(List.of(victim[0]));
            }, MIN_EVENT_MS, QUIET_MS);
            cleanupAndRestore(List.of(victim[0]), 0, 1);
        }
    }

    private void killLeaderClass() {
        for (int rep = 1; rep <= REPS; rep++) {
            final var victim = new String[1];

            victim[0] = cluster.currentLeader().unwrap();
            final var gone = victim[0];

            measure("crash-core-leader", rep, Set.of(gone), () -> {
                check(cluster.blackhole(gone).await(STOP_BOUND).isSuccess(), "blackhole " + gone);
                await("a successor leader", () -> cluster.currentLeader().filter(id -> !id.equals(gone)).isPresent(), 240_000);
            }, MIN_EVENT_MS, QUIET_MS);
            cleanupAndRestore(List.of(gone), 1, 0);
        }
    }

    /// After a measured crash: stop the zombies, restore the node count, settle. Not part of any measured window.
    private void cleanupAndRestore(List<String> victims, int coresToAdd, int workersToAdd) {
        for (var victim : victims) {
            cluster.killNode(victim, false).await(STOP_BOUND);
            coreIds.remove(victim);
        }
        for (int i = 0; i < coresToAdd; i++) {
            var added = cluster.addNode().await(START_BOUND);

            check(added.isSuccess(), "restore core " + added);
            var id = added.unwrap().id();

            coreIds.add(id);
            try {
                instruments.add(M1Instrument.install(cluster.getNode(id).unwrap()));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        var pool = Executors.newFixedThreadPool(8);

        try {
            var futures = new ArrayList<Future<String>>();

            for (int i = 0; i < workersToAdd; i++) {
                futures.add(pool.submit(this::addWorkerNoWait));
            }
            for (var future : futures) {
                workers.add(get(future));
            }
        } finally {
            pool.shutdown();
        }
        try {
            await("directives restored", () -> directives() >= workers.size(), 240_000);
        } catch (AssertionError e) {
            log("restore shortfall: directives=" + directives() + " workers=" + workers.size() + " (collateral evictions; the next sample records its own workersStart)");
        }
        workers.removeIf(w -> leader().kvStore().get(new ActivationDirectiveKey(new NodeId(w))).isEmpty());
        settleLoose("restore", 40_000, 300_000);
    }

    // ---- S6: one slice deployed (#2512, q8 P4.1) -----------------------------------------------------------------------------------

    private static final String ECHO_VERSION = System.getProperty("project.version", "UNKNOWN");
    private static final String ECHO_SLICE = "org.pragmatica-lite.aether.test:echo-slice-echo-service:" + ECHO_VERSION;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /// The blueprint parser refuses a slice with fewer instances (HTTP 400); with fewer than 3 communities S6b therefore deploys 3 and says so.
    private static final int MIN_BLUEPRINT_INSTANCES = 3;
    private static final String WORKERS_ONLY = "WORKERS_ONLY";
    private static final String CORE_ONLY = "CORE_ONLY";
    /// Commit families through which a membership change (a SWIM verdict the leader acted on) surfaces; a window with any is flagged.
    private static final List<String> MEMBERSHIP_FAMILIES = List.of("put:NodeDepartureKey", "remove:ActivationDirectiveKey", "put:UnconfirmedTerminationKey");

    private record Http(int status, String body) {}

    /// One S6 deployment: the arm's target instance count and placement, and what the window observed about it.
    private static final class S6Run {
        final String cls;
        final int rep;
        final String blueprintId;
        final Artifact artifact;
        final int instances;
        final String placement;
        Set<String> membersAtStart = Set.of();
        String leaderAtStart = "none";
        boolean converged;
        boolean routePublished;

        S6Run(String cls, int rep, Artifact artifact, int instances, String placement) {
            this.cls = cls;
            this.rep = rep;
            this.blueprintId = "m1.s6:" + cls + "-r" + rep + ":1.0.0";
            this.artifact = artifact;
            this.instances = instances;
            this.placement = placement;
        }
    }

    /// Fails before any window opens when the echo slice the nodes will load is not the one built from this tree. The negative control must
    /// detect a planted one-byte difference, otherwise a green comparison proves nothing.
    private void preflightEcho() throws IOException {
        check(!"UNKNOWN".equals(ECHO_VERSION), "system property project.version is not set (ember's surefire config provides it)");
        var outcome = M1EchoPreflight.check(ECHO_VERSION);

        emit("{\"kind\":\"preflight-echo\"," + header() + ",\"ok\":" + outcome.ok() + ",\"compared\":" + outcome.compared() + ",\"sha256\":\"" + outcome.digest()
             + "\",\"jar\":\"" + M1EchoPreflight.installedJar(ECHO_VERSION) + "\",\"detail\":\"" + outcome.detail().replace("\"", "'") + "\"}");
        check(outcome.ok(), "echo slice pre-flight: " + outcome.detail());
        var mutated = Files.createTempDirectory("m1-echo-control");

        try (var walk = Files.walk(M1EchoPreflight.headClasses())) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                var target = mutated.resolve(M1EchoPreflight.headClasses().relativize(file).toString());

                Files.createDirectories(target.getParent());
                Files.write(target, Files.readAllBytes(file));
            }
        }
        Path victim;

        try (var walk = Files.walk(mutated)) {
            victim = walk.filter(f -> f.toString().endsWith(".class")).sorted().findFirst().orElseThrow();
        }
        var bytes = Files.readAllBytes(victim);

        bytes[bytes.length - 1] ^= 1;
        Files.write(victim, bytes);
        check(!M1EchoPreflight.compare(M1EchoPreflight.installedJar(ECHO_VERSION), mutated).ok(), "pre-flight control: a one-byte difference was not detected");
        log("echo pre-flight ok: " + outcome.compared() + " entries, sha256 " + outcome.digest());
    }

    private void s6Class(String cls) {
        var artifact = Artifact.artifact(ECHO_SLICE).unwrap();

        for (int rep = 1; rep <= REPS; rep++) {
            var run = new S6Run(cls, rep, artifact, s6Instances(cls), "s6c".equals(cls) ? CORE_ONLY : WORKERS_ONLY);

            if ("s6d".equals(cls)) {
                deploy(run);
                settleLoose("s6d deployed", 40_000, 400_000);
                emitS6(run, measure("s6d-undeploy", rep, Set.of(), () -> undeploy(run), MIN_EVENT_MS, QUIET_MS), false);
            } else {
                emitS6(run, measure(cls + "-deploy(" + run.placement + ",instances=" + run.instances + ")", rep, Set.of(), () -> deploy(run), MIN_EVENT_MS, QUIET_MS), true);
                undeploy(run);
                settleLoose("s6 undeployed", 40_000, 400_000);
            }
        }
    }

    private int s6Instances(String cls) {
        return switch (cls) {
            case "s6b" -> Math.max(MIN_BLUEPRINT_INSTANCES, new java.util.HashSet<>(communities().values()).size());
            case "s6c" -> CORES;
            default -> workers.size();
        };
    }

    /// The whole deploy event: blueprint POST, then the placement pass (the blueprint TOML has no placement key, so WORKERS_ONLY is a second
    /// leader transaction, counted inside the window), then until every target instance is ACTIVE and its route is published.
    private void deploy(S6Run run) {
        run.membersAtStart = memberIds();
        run.leaderAtStart = cluster.currentLeader().or("none");
        var blueprint = """
            id = "%s"
            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(run.blueprintId, ECHO_SLICE, run.instances);
        var applied = send("POST", "/api/v1/blueprints", "application/toml", blueprint);

        check(applied.status() == 200 && applied.body().contains("\"status\":\"applied\""), "blueprint applied: " + applied);
        var key = SliceTargetKey.sliceTargetKey(run.artifact.base());

        await("slice target", () -> leader().kvStore().getTyped(key, SliceTargetValue.class).isPresent(), 60_000);
        if (WORKERS_ONLY.equals(run.placement)) {
            placeWorkersOnly(key);
        }
        run.converged = awaitQuietly(() -> artifactStates(run.artifact).getOrDefault("ACTIVE", 0) >= run.instances, 600_000);
        run.routePublished = awaitQuietly(() -> routePublished(run.artifact), 60_000);
    }

    private void placeWorkersOnly(SliceTargetKey key) {
        for (int attempt = 1; ; attempt++) {
            var before = leader().kvStore().getTyped(key, SliceTargetValue.class).unwrap();

            try {
                commit(key, Option.some(before), Option.some(before.withPlacement(WORKERS_ONLY)));
                return;
            } catch (AssertionError e) {
                // the blueprint apply rewrote the target between the read and the transaction; read again
                if (attempt >= 5) {
                    throw e;
                }
            }
        }
    }

    private void undeploy(S6Run run) {
        run.membersAtStart = memberIds();
        run.leaderAtStart = cluster.currentLeader().or("none");
        var deleted = send("DELETE", "/api/v1/blueprints/" + run.blueprintId, "application/json", "");

        check(deleted.status() == 200, "blueprint deleted: " + deleted);
        run.converged = awaitQuietly(() -> artifactStates(run.artifact).isEmpty(), 600_000);
    }

    private boolean routePublished(Artifact artifact) {
        return snap().keySet().stream().anyMatch(key -> key instanceof NodeRoutesKey routes && routes.artifact().equals(artifact));
    }

    private Map<String, Integer> artifactStates(Artifact artifact) {
        var states = new TreeMap<String, Integer>();

        snap().forEach((key, value) -> {
            if (key instanceof NodeArtifactKey node && node.artifact().equals(artifact) && value instanceof NodeArtifactValue state) {
                states.merge(state.state().name(), 1, Integer::sum);
            }
        });
        return states;
    }

    /// Worker community of every worker with a directive; the coverage of S6b is read from this, never assumed.
    private Map<String, String> communities() {
        var byNode = new TreeMap<String, String>();

        snap().forEach((key, value) -> {
            if (key instanceof ActivationDirectiveKey directive && value instanceof ActivationDirectiveValue v && "WORKER".equals(v.role())) {
                byNode.put(directive.nodeId().id(), v.communityId());
            }
        });
        return byNode;
    }

    private Set<String> memberIds() {
        return cluster.allNodes().stream().map(n -> n.self().id()).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private void emitS6(S6Run run, Map<String, Long> raw, boolean deploy) {
        var states = artifactStates(run.artifact);
        var communityOf = communities();
        var hosts = new java.util.TreeSet<String>();

        snap().keySet().forEach(key -> {
            if (key instanceof NodeArtifactKey node && node.artifact().equals(run.artifact)) {
                hosts.add(node.nodeId().id());
            }
        });
        var covered = hosts.stream().map(communityOf::get).filter(c -> c != null).collect(Collectors.toCollection(java.util.TreeSet::new));
        var allCommunities = new java.util.TreeSet<>(communityOf.values());
        long edges = MEMBERSHIP_FAMILIES.stream().mapToLong(f -> raw.getOrDefault(f, 0L)).sum();
        boolean membersChanged = !memberIds().equals(run.membersAtStart);
        boolean leaderChanged = !cluster.currentLeader().or("none").equals(run.leaderAtStart);
        long failedPuts = raw.getOrDefault("nodeArtifact.state:FAILED", 0L);
        int failedNow = states.getOrDefault("FAILED", 0);
        long artifactPuts = raw.getOrDefault("put:NodeArtifactKey", 0L);
        long engineMutations = raw.getOrDefault("tx.accepted.mutations", 0L) + raw.getOrDefault("cmd.Put", 0L) + raw.getOrDefault("cmd.Remove", 0L);
        long notified = raw.getOrDefault("put.total", 0L) + raw.getOrDefault("remove.total", 0L);
        var transitions = raw.entrySet().stream().filter(e -> e.getKey().startsWith("nodeArtifact.state:")).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
        var excluded = failedPuts > 0 || failedNow > 0 || !run.converged;

        emit("{\"kind\":\"s6\"," + header() + ",\"class\":\"" + run.cls + "\",\"rep\":" + run.rep + ",\"placement\":\"" + run.placement + "\",\"instances\":" + run.instances
             + ",\"converged\":" + run.converged + ",\"routePublished\":" + run.routePublished + ",\"statesAtEnd\":" + js(states.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> (long) e.getValue(), (a, b) -> a, TreeMap::new)))
             + ",\"hosts\":" + hosts.size() + ",\"communitiesTotal\":" + allCommunities.size() + ",\"communitiesCovered\":" + covered.size()
             + ",\"nodeArtifactPuts\":" + artifactPuts + ",\"nodeArtifactPutsPerInstance\":" + (run.instances == 0 ? 0 : (double) artifactPuts / run.instances)
             + ",\"transitions\":" + js(transitions) + ",\"failedPuts\":" + failedPuts + ",\"failedAtEnd\":" + failedNow
             + ",\"txAccepted\":" + raw.getOrDefault("tx.accepted", 0L) + ",\"slots\":" + raw.getOrDefault("engine.slots", 0L)
             + ",\"mutationsNotified\":" + notified + ",\"mutationsEngine\":" + engineMutations
             + ",\"familyGaps\":" + js(familyGaps(raw))
             + ",\"membershipEdges\":" + edges + ",\"membersChanged\":" + membersChanged + ",\"leaderChanged\":" + leaderChanged
             + ",\"flaggedSwim\":" + (edges > 0 || membersChanged || leaderChanged) + ",\"excludedFromFit\":" + excluded
             + ",\"families\":" + js(raw.entrySet().stream().filter(e -> e.getKey().startsWith("put:") || e.getKey().startsWith("remove:")).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new))) + "}");
        if (deploy && !excluded) {
            // Instrument self-checks against two independent readings: the engine's committed commands (per family), and the leader's own KV state.
            // A Put notifies exactly once, so the engine's puts of a family equal the notified puts of it; a Remove of an absent key notifies nothing,
            // so the engine's removes may exceed the notified ones, never fall short.
            for (var family : List.of("NodeArtifactKey", "NodeRoutesKey")) {
                long enginePuts = raw.getOrDefault("cmd.Put:" + family, 0L) + raw.getOrDefault("tx.mut:" + family, 0L);

                check(enginePuts == raw.getOrDefault("put:" + family, 0L), run.cls + ": " + family + " puts: engine " + enginePuts + " != notified " + raw.getOrDefault("put:" + family, 0L));
                check(raw.getOrDefault("cmd.Remove:" + family, 0L) >= raw.getOrDefault("remove:" + family, 0L), run.cls + ": " + family + " removes notified exceed engine removes");
            }
            check(notified <= engineMutations, run.cls + ": notification reading " + notified + " exceeds engine reading " + engineMutations);
            check(artifactPuts >= states.getOrDefault("ACTIVE", 0), run.cls + ": " + states.getOrDefault("ACTIVE", 0) + " ACTIVE instances but only " + artifactPuts + " NodeArtifactKey puts counted");
        }
    }

    /// Per family, engine-committed puts minus notified puts (and removes), non-zero entries only: where the two readings disagree, by family.
    private static Map<String, Long> familyGaps(Map<String, Long> raw) {
        var gaps = new TreeMap<String, Long>();
        var families = new java.util.TreeSet<String>();

        raw.keySet().forEach(name -> {
            if (name.startsWith("put:") || name.startsWith("remove:") || name.startsWith("cmd.Put:") || name.startsWith("cmd.Remove:") || name.startsWith("tx.mut:")) {
                families.add(name.substring(name.indexOf(':') + 1));
            }
        });
        for (var family : families) {
            long putGap = raw.getOrDefault("cmd.Put:" + family, 0L) + raw.getOrDefault("tx.mut:" + family, 0L) - raw.getOrDefault("put:" + family, 0L);
            long removeGap = raw.getOrDefault("cmd.Remove:" + family, 0L) - raw.getOrDefault("remove:" + family, 0L);

            if (putGap != 0) {
                gaps.put("put:" + family, putGap);
            }
            if (removeGap != 0) {
                gaps.put("remove:" + family, removeGap);
            }
        }
        return gaps;
    }

    // ---- P6 pre-flight: what one CONFIG_SET commits (#2512, q8 P6.1 step 1) ------------------------------------------------------------

    /// Arm K's instrument control, counts only: drives `POST /api/v1/config` over a rotating key set of 16 and records, per op, what the leader
    /// core committed. A single op's mutations are attributed by the snapshot delta around it, so background commits can add to an op;
    /// the family histogram says which.
    private void preflightConfig() {
        int ops = Integer.getInteger("m1.configOps", 48);
        int keys = 16;
        long keysBefore = census().getOrDefault("ConfigKey", 0L);
        var perOp = new ArrayList<Map<String, Long>>();

        measure("config-set", 1, Set.of(), () -> {
            var leaderId = cluster.currentLeader().unwrap();
            var instrument = instruments.stream().filter(i -> i.nodeId().equals(leaderId)).findFirst().orElseThrow();

            for (int i = 0; i < ops; i++) {
                var before = instrument.snapshot();
                var answer = send("POST", "/api/v1/config", "application/json", "{\"key\":\"m1.arm-k." + (i % keys) + "\",\"value\":\"v-" + String.format("%06d", i) + "\"}");

                check(answer.status() == 200 && answer.body().contains("config_set"), "config set: " + answer);
                var landed = awaitQuietly(() -> M1Instrument.delta(before, instrument.snapshot()).getOrDefault("put:ConfigKey", 0L) >= 1, 5_000);

                check(landed, "config commit not observed on the leader core");
                sleep(300);
                perOp.add(M1Instrument.delta(before, instrument.snapshot()));
            }
        }, 10_000, 5_000);
        var perOpKey = histogram(perOp, d -> d.getOrDefault("put:ConfigKey", 0L));
        var perOpTotal = histogram(perOp, d -> d.getOrDefault("put.total", 0L) + d.getOrDefault("remove.total", 0L));
        var extraFamilies = new TreeMap<String, Long>();

        perOp.forEach(d -> d.forEach((name, value) -> {
            if ((name.startsWith("put:") || name.startsWith("remove:")) && !name.equals("put:ConfigKey")) {
                extraFamilies.merge(name, value, Long::sum);
            }
        }));
        long keysAfter = census().getOrDefault("ConfigKey", 0L);

        emit("{\"kind\":\"preflight-config\"," + header() + ",\"ops\":" + ops + ",\"keySet\":" + keys + ",\"configKeysBefore\":" + keysBefore + ",\"configKeysAfter\":" + keysAfter
             + ",\"putConfigKeyPerOp\":" + js(perOpKey) + ",\"mutationsPerOp\":" + js(perOpTotal) + ",\"otherFamiliesAcrossOps\":" + js(extraFamilies)
             + ",\"txPerOp\":" + js(histogram(perOp, d -> d.getOrDefault("tx.accepted", 0L)))
             + ",\"putBytesPerOp\":" + js(histogram(perOp, d -> d.getOrDefault("put.bytes", 0L))) + "}");
        check(perOpKey.size() == 1 && perOpKey.containsKey("1"), "every CONFIG_SET must commit exactly one ConfigKey put: " + perOpKey);
        check(keysAfter - keysBefore == keys, "the rotating driver must create exactly " + keys + " ConfigKey entries, created " + (keysAfter - keysBefore));
    }

    // ---- S6 / P6 plumbing --------------------------------------------------------------------------------------------------------

    /// HTTP to the leader's management port, as an operator or the arm K driver would.
    private Http send(String method, String path, String contentType, String body) {
        var leaderId = cluster.currentLeader().unwrap();
        var port = cluster.status().nodes().stream().filter(n -> n.id().equals(leaderId)).findFirst().orElseThrow().mgmtPort();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", contentType)
                                 .timeout(java.time.Duration.ofSeconds(30))
                                 .method(method, HttpRequest.BodyPublishers.ofString(body))
                                 .build();

        try {
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            return new Http(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /// value -> number of ops that showed it, keyed by the value's text so it renders through [#js].
    private static Map<String, Long> histogram(List<Map<String, Long>> perOp, java.util.function.ToLongFunction<Map<String, Long>> value) {
        return perOp.stream().collect(Collectors.groupingBy(d -> Long.toString(value.applyAsLong(d)), TreeMap::new, Collectors.counting()));
    }

    private static boolean awaitQuietly(BooleanSupplier condition, long millis) {
        try {
            await("condition", condition, millis);
            return true;
        } catch (AssertionError e) {
            log("not reached within " + millis + " ms: " + e.getMessage());
            return false;
        }
    }

    // ---- measurement -------------------------------------------------------------------------------------------------------------

    /// Returns the first reference core's delta over the window (the `raw` of the emitted sample).
    private Map<String, Long> measure(String cls, int rep, Set<String> excluded, Runnable action, long minMs, long quietMs) {
        var refs = refInstruments(excluded);
        var idleBefore = idleWindow(refs, IDLE_WINDOW_MS);
        var before = refs.stream().map(M1Instrument::snapshot).toList();
        var t0 = System.nanoTime();
        var workersStart = workers.size();
        var loadStart = loadavg();
        var directivesStart = directives();
        var leaderStart = cluster.currentLeader().or("none");
        var startUtc = Instant.now();

        action.run();
        var settled = settle(refs, t0, minMs, quietMs, MAX_EVENT_MS);
        var t1 = System.nanoTime();
        var after = refs.stream().map(M1Instrument::snapshot).toList();
        var idleAfter = idleWindow(refs, IDLE_WINDOW_MS);
        var lastOffsetMs = lastNonIdleOffsetMs(refs.getFirst(), t0);
        var loadEnd = loadavg();
        var directivesEnd = directives();
        var leaderEnd = cluster.currentLeader().or("none");
        var deltas = new ArrayList<Map<String, Long>>();

        for (int i = 0; i < refs.size(); i++) {
            deltas.add(M1Instrument.delta(before.get(i), after.get(i)));
        }
        var spread = new TreeMap<String, long[]>();

        for (var key : List.of("put.total", "remove.total", "engine.batches", "engine.slots", "tx.accepted", "tx.refused")) {
            var values = deltas.stream().mapToLong(d -> d.getOrDefault(key, 0L)).toArray();

            spread.put(key, new long[]{Arrays.stream(values).min().orElse(0), Arrays.stream(values).max().orElse(0)});
        }
        emit("{\"kind\":\"sample\"," + header() + ",\"class\":\"" + cls + "\",\"rep\":" + rep + ",\"startUtc\":\"" + startUtc + "\",\"workersStart\":"
             + workersStart + ",\"workersEnd\":" + workers.size() + ",\"cores\":" + coreIds.size() + ",\"refCores\":" + refs.stream().map(M1Instrument::nodeId).toList().stream().map(s -> "\"" + s + "\"").toList()
             + ",\"loadStart\":\"" + loadStart + "\",\"loadEnd\":\"" + loadEnd + "\",\"directivesStart\":" + directivesStart + ",\"directivesEnd\":" + directivesEnd + ",\"leaderStart\":\"" + leaderStart + "\",\"leaderEnd\":\"" + leaderEnd + "\",\"windowMs\":" + (t1 - t0) / 1_000_000 + ",\"settled\":" + settled + ",\"lastNonIdleOffsetMs\":" + lastOffsetMs
             + ",\"raw\":" + js(deltas.getFirst()) + ",\"spread\":" + jsSpread(spread) + ",\"idleBefore\":" + idleBefore + ",\"idleAfter\":" + idleAfter + "}");
        return deltas.getFirst();
    }

    private String idleWindow(List<M1Instrument> refs, long millis) {
        var before = refs.getFirst().snapshot();
        var t0 = System.nanoTime();

        sleep(millis);
        var after = refs.getFirst().snapshot();

        refs.getFirst().logSince(t0).forEach(commit -> idleOps.add(commit.op()));
        return "{\"ms\":" + (System.nanoTime() - t0) / 1_000_000 + ",\"counts\":" + js(M1Instrument.delta(before, after)) + "}";
    }

    /// True when no non-idle commit reached the first reference core for `quietMs` after `t0` (and at least `minMs` passed); false at the cap.
    private boolean settle(List<M1Instrument> refs, long t0, long minMs, long quietMs, long maxMs) {
        while (true) {
            sleep(1_000);
            var elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            var last = refs.stream().mapToLong(ref -> lastNonIdleNanos(ref, t0)).max().orElse(t0);
            var quietFor = (System.nanoTime() - last) / 1_000_000;

            if (elapsedMs >= minMs && quietFor >= quietMs) {
                return true;
            }
            if (elapsedMs >= maxMs) {
                return false;
            }
        }
    }

    private long lastNonIdleNanos(M1Instrument ref, long t0) {
        return ref.logSince(t0).stream().filter(c -> !idleOps.contains(c.op())).mapToLong(M1Instrument.Commit::nanos).max().orElse(t0);
    }

    private long lastNonIdleOffsetMs(M1Instrument ref, long t0) {
        return (lastNonIdleNanos(ref, t0) - t0) / 1_000_000;
    }

    /// Settling that is not a measurement: same quiet rule on the first live core.
    private void settleLoose(String what, long minMs, long maxMs) {
        var refs = refInstruments(Set.of());
        var t0 = System.nanoTime();
        var settled = settle(refs, t0, minMs, QUIET_MS, maxMs);

        log("settle " + what + " settled=" + settled + " after " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }

    private List<M1Instrument> refInstruments(Set<String> excluded) {
        var refs = instruments.stream()
                              .filter(i -> coreIds.contains(i.nodeId()) && !excluded.contains(i.nodeId()) && cluster.getNode(i.nodeId()).isPresent())
                              .toList();

        check(!refs.isEmpty(), "no live reference core");
        return refs;
    }

    // ---- cluster helpers ---------------------------------------------------------------------------------------------------------

    private String addWorkerNoWait() {
        var labels = "default".equals(SOURCE)
                     ? Map.of(NodeInfo.LABEL_ROLE, "worker")
                     : Map.of(NodeInfo.LABEL_ROLE, "worker", NodeInfo.LABEL_SOURCE, SOURCE);
        var added = cluster.addNode(labels).await(START_BOUND);

        check(added.isSuccess(), "add worker " + added);
        return added.unwrap().id();
    }

    private int directives() {
        return (int) snap().entrySet().stream()
                             .filter(e -> e.getKey() instanceof ActivationDirectiveKey && e.getValue() instanceof ActivationDirectiveValue v && "WORKER".equals(v.role()))
                             .count();
    }

    private List<String> wholeCommunity() {
        var byCommunity = new TreeMap<String, List<String>>();

        snap().forEach((key, value) -> {
            if (key instanceof ActivationDirectiveKey directive && value instanceof ActivationDirectiveValue v && "WORKER".equals(v.role())
                && workers.contains(directive.nodeId().id())) {
                byCommunity.computeIfAbsent(v.communityId(), _ -> new ArrayList<>()).add(directive.nodeId().id());
            }
        });
        return byCommunity.values().stream().max(java.util.Comparator.comparingInt(List::size)).orElseThrow();
    }

    private String someGovernorWorker() {
        var governors = new LinkedHashSet<String>();

        snap().forEach((key, value) -> {
            if (key instanceof GovernorAnnouncementKey && value instanceof GovernorAnnouncementValue v && workers.contains(v.governorId().id())) {
                governors.add(v.governorId().id());
            }
        });
        check(!governors.isEmpty(), "no worker governor announced");
        return governors.iterator().next();
    }

    private Map<String, Long> census() {
        var out = new TreeMap<String, Long>();

        snap().keySet().forEach(key -> out.merge(key.getClass().getSimpleName(), 1L, Long::sum));
        return out;
    }

    /// The snapshot also holds the leader record, which is not an AetherKey, so the key type is not trusted.
    private Map<?, ?> snap() {
        return leader().kvStore().snapshot();
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }

    private Option<ClusterConfigValue> committedConfig() {
        return cluster.currentLeader().flatMap(cluster::getNode).flatMap(node -> node.kvStore().getTyped(ClusterConfigKey.CURRENT, ClusterConfigValue.class));
    }

    private int communitySize() {
        return Integer.getInteger("m1.communitySize", N >= 200 ? 50 : N >= 50 ? 25 : 5);
    }

    /// Placement change: the community policy's max_size is lowered, then restored; the leader's placement pass decides what to move.
    private void policyChangeClass() {
        int size = communitySize();
        int smaller = Math.max(2, size * 4 / 5);

        for (int rep = 1; rep <= REPS; rep++) {
            measure("policy-change(max_size " + size + "->" + smaller + ")", rep, Set.of(), () -> commitPolicy(smaller), MIN_EVENT_MS, QUIET_MS);
            commitPolicy(size);
            settleLoose("policy restored", 40_000, 300_000);
        }
    }

    private void commitPolicy() {
        commitPolicy(communitySize());
    }

    private void commitPolicy(int size) {
        var policy = """
            config_version = "1.0.0"
            [cluster]
            name = "m1"
            version = "1.0.0"
            %s
            [community_policy]
            max_size = %d
            viability_floor = 1
            allow_below_rf = true
            max_communities = 40
            dissolve_grace_seconds = 3
            growth = true
            """.formatted("default".equals(SOURCE) ? "" : "[source." + SOURCE + "]\ntype = \"forge\"\n[source." + SOURCE + ".worker]\ncount = " + N, size);
        var before = committedConfig().unwrap();
        var value = new ClusterConfigValue(Option.some(policy), before.clusterName(), before.version(), before.desiredTopology(), before.coreMin(),
                                           before.coreMax(), before.deploymentType(), before.configVersion() + 1, System.currentTimeMillis());

        commit(ClusterConfigKey.CURRENT, Option.some(before), Option.some(value));
        log("community policy committed, max_size=" + size + "; desiredTopology before=" + before.desiredTopology() + " toml before=" + before.tomlContent());
    }

    private void commit(AetherKey key, Option<? extends AetherValue> expected, Option<? extends AetherValue> replacement) {
        var node = leader();
        var id = UUID.randomUUID().toString();
        var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, id, authority, List.of(),
                                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key, expected.map(v -> (AetherValue) v),
                                                                                                                                         replacement.map(v -> (AetherValue) v))));
        var results = node.<Object> apply(List.of(transaction)).await(APPLY_BOUND).unwrap();

        check(results.stream().anyMatch(o -> o instanceof KVCommand.TransactionResult r && r.transactionId().equals(id) && r.accepted()), "leader tx accepted for " + key.asString());
    }

    private void apply(KVCommand<AetherKey> command) {
        check(leader().<Object> apply(List.of(command)).await(APPLY_BOUND).isSuccess(), "apply " + command);
    }

    // ---- plumbing ----------------------------------------------------------------------------------------------------------------

    /// The host's 1/5/15 minute load average; counts are not timing, but a loaded host changes SWIM's verdicts, so it is recorded per sample.
    private static String loadavg() {
        try {
            return Files.readString(Path.of("/proc/loadavg")).split(" ")[0] + "/" + Files.readString(Path.of("/proc/loadavg")).split(" ")[1];
        } catch (IOException | RuntimeException e) {
            return "n/a";
        }
    }

    private String header() {
        return "\"label\":\"" + LABEL + "\",\"n\":" + N + ",\"utc\":\"" + Instant.now() + "\"";
    }

    private void emit(String line) {
        out.println(line);
    }

    private static String js(Map<String, Long> map) {
        return map.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue()).collect(Collectors.joining(",", "{", "}"));
    }

    private static String jsSpread(Map<String, long[]> map) {
        return map.entrySet().stream().map(e -> "\"" + e.getKey() + "\":[" + e.getValue()[0] + "," + e.getValue()[1] + "]").collect(Collectors.joining(",", "{", "}"));
    }

    private static void log(String message) {
        System.out.println("M1 " + Instant.now() + " " + message);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("m1: " + what);
        }
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(String what, BooleanSupplier condition, long millis) {
        var start = System.currentTimeMillis();
        var deadline = start + millis;
        var nextLog = start + 60_000;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("m1: timed out waiting for " + what);
            }
            if (System.currentTimeMillis() > nextLog) {
                log("still waiting for " + what);
                nextLog += 60_000;
            }
            sleep(500);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
