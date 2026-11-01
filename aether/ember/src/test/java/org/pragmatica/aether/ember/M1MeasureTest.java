// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.io.PrintWriter;
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
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
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
        setUp();
        buildUp();
        emit("{\"kind\":\"census\"," + header() + ",\"keys\":" + js(census()) + "}");
        for (var cls : System.getProperty("m1.classes", "control,idle,join,leave,crash,kill-k,kill-community,kill-governor,kill-leader").split(",")) {
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
                default -> throw new IllegalArgumentException("unknown class " + cls);
            }
        }
        emit("{\"kind\":\"census-end\"," + header() + ",\"keys\":" + js(census()) + "}");
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

    // ---- measurement -------------------------------------------------------------------------------------------------------------

    private void measure(String cls, int rep, Set<String> excluded, Runnable action, long minMs, long quietMs) {
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
