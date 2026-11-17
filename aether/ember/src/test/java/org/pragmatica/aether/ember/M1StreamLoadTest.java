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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// MANUAL MEASUREMENT, excluded from CI by the `Benchmark` tag. S7 (#2512, #2590): the consensus load of stream cursor checkpoints.
///
/// Per arm (G consumer groups x P partitions, consumers on cores or on workers, a producer at a recorded rate per partition) a fresh Ember
/// cluster deploys the generated slice `test-stream-ckpt-g<G>-p<P>` (see `aether/tests/blueprints/test-stream-checkpoint-load`), waits until
/// G x P consumer subscriptions are attached, runs the producer, and counts on the first core, per window: committed checkpoint puts,
/// barrier Noops that carry a checkpoint key, slots, batches, commands and leader transactions. Then the producer stops and the same
/// counters are taken again as the IDLE control. Counts only; no figure here is a latency.
///
/// Expected (design): one checkpoint per active (group, partition) per T, so `checkpoints/s = G x P / T` while events flow (T = `m1.s7.t`,
/// default 1 s), one consensus command per checkpoint on a core and two on a worker (the forwarding Noop barrier), and ~0 when idle.
///
/// System properties: `m1.s7.arms` = comma list of `G:P:placement:rate` (placement `core`|`worker`; rate = events per second PER PARTITION),
/// `m1.s7.n` workers (default 6), `m1.s7.warmupS` (30), `m1.s7.windowS` (30), `m1.s7.windows` (3), `m1.s7.idleS` (20), `m1.s7.idleWindows` (2),
/// `m1.s7.t` (1.0), `m1.base`, `m1.out`, `m1.label`, `m1.sha`, `m1.host`.
@Tag("Benchmark")
class M1StreamLoadTest {
    private static final int CORES = 5;
    private static final int WORKERS = Integer.getInteger("m1.s7.n", 6);
    private static final long WARMUP_S = Long.getLong("m1.s7.warmupS", 30L);
    private static final long WINDOW_S = Long.getLong("m1.s7.windowS", 30L);
    private static final int WINDOWS = Integer.getInteger("m1.s7.windows", 3);
    private static final long IDLE_S = Long.getLong("m1.s7.idleS", 20L);
    private static final int IDLE_WINDOWS = Integer.getInteger("m1.s7.idleWindows", 2);
    private static final double T_SECONDS = Double.parseDouble(System.getProperty("m1.s7.t", "1.0"));
    private static final String LABEL = System.getProperty("m1.label", "s7");
    private static final String SHA = System.getProperty("m1.sha", "unknown");
    private static final String HOST = System.getProperty("m1.host", "unknown");
    private static final String VERSION = "1.0.0";
    private static final String CKPT = "StreamCursorCheckpointKey";
    private static final Pattern ATTACHED = Pattern.compile("\"attachedSubscriptions\"\\s*:\\s*(\\d+)");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(300).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(180).seconds();

    record Arm(int g, int p, String placement, double rate) {
        String artifactBase() {
            return "test-stream-ckpt-g" + g + "-p" + p;
        }

        String label() {
            return "g" + g + "-p" + p + "-" + placement + "-r" + rate;
        }
    }

    private PrintWriter out;
    private EmberCluster cluster;
    private final List<M1Instrument> instruments = new ArrayList<>();

    @Test
    @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.HOURS)
    void measure() throws Exception {
        var path = Path.of(System.getProperty("m1.out", "/tmp/s7-" + LABEL + ".jsonl"));

        Files.createDirectories(path.toAbsolutePath().getParent());
        out = new PrintWriter(Files.newBufferedWriter(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND), true);
        try {
            for (var arm : arms()) {
                runArm(arm);
            }
        } finally {
            out.close();
        }
    }

    private static List<Arm> arms() {
        return java.util.Arrays.stream(System.getProperty("m1.s7.arms", "1:4:core:2").split(","))
                               .map(spec -> spec.strip().split(":"))
                               .map(f -> new Arm(Integer.parseInt(f[0]), Integer.parseInt(f[1]), f[2], Double.parseDouble(f[3])))
                               .toList();
    }

    // ---- one arm -------------------------------------------------------------------------------------------------------------------

    private void runArm(Arm arm) throws Exception {
        var jarSha = installedJarSha(arm);
        var started = System.nanoTime();

        instruments.clear();
        M1Instrument.ALL.clear();
        emit("{\"kind\":\"s7-arm\"," + header(arm) + ",\"jarSha256\":\"" + jarSha + "\",\"cores\":" + CORES + ",\"workers\":" + WORKERS + ",\"t\":" + T_SECONDS + "}");
        startCluster();
        try {
            deploy(arm);
            long placedS = "worker".equals(arm.placement()) ? awaitPlacedOnWorkers(arm) : 0;
            long attachS = awaitAttached(arm);

            emit("{\"kind\":\"s7-attached\"," + header(arm) + ",\"placedSeconds\":" + placedS + ",\"attachSeconds\":" + attachS + ",\"subscriptionsByNode\":" + attachedByNode() + ",\"sliceHosts\":\"" + sliceHosts(arm) + "\"}");
            var producer = new Producer(arm);

            producer.start();
            log(arm, "attached after " + attachS + " s; warming up " + WARMUP_S + " s");
            sleep(WARMUP_S * 1_000);
            var load = new ArrayList<Map<String, Object>>();

            for (int w = 1; w <= WINDOWS; w++) {
                load.add(window(arm, "load", w, WINDOW_S, producer));
            }
            producer.finish();
            long quiesceMs = awaitQuiet();
            var idle = new ArrayList<Map<String, Object>>();

            for (int w = 1; w <= IDLE_WINDOWS; w++) {
                idle.add(window(arm, "idle", w, IDLE_S, producer));
            }
            summary(arm, load, idle, attachS, quiesceMs, producer, (System.nanoTime() - started) / 1_000_000_000L);
        } finally {
            cluster.stop().await(STOP_BOUND);
        }
    }

    private void startCluster() throws Exception {
        int base = Integer.getInteger("m1.base", 44000);
        var configProvider = ConfigurationProvider.builder().withSystemProperties("aether.").withEnvironment("AETHER_").build();

        cluster = emberCluster(CORES, base, base + 1000, base + 2000, "s7", Option.some(configProvider));
        check(cluster.withAdditionalNodeSlots(WORKERS + 10).isSuccess(), "slots");
        check(cluster.withPortStride(3).isSuccess(), "stride");
        check(cluster.start().await(START_BOUND).isSuccess(), "cluster start");
        await("leader", () -> cluster.currentLeader().isPresent(), 120_000);
        for (var node : cluster.allNodes()) {
            instruments.add(M1Instrument.install(node));
        }
        for (int i = 0; i < WORKERS; i++) {
            check(cluster.addWorkerNode().await(START_BOUND).isSuccess(), "add worker");
        }
        await("workers ready", () -> cluster.allNodes().size() == CORES + WORKERS && cluster.allNodes().stream().allMatch(AetherNode::isReady), 300_000);
        sleep(15_000);
    }

    private void deploy(Arm arm) {
        var id = "m1.s7:" + arm.artifactBase() + ":1.0.0";
        var artifact = "org.pragmatica.aether.test:" + arm.artifactBase() + "-load-slice:" + VERSION;
        var blueprint = """
            id = "%s"
            [[slices]]
            artifact = "%s"
            instances = 3
            """.formatted(id, artifact);
        var applied = send("POST", mgmtPort(), "/api/v1/blueprints", "application/toml", blueprint);

        check(applied.contains("\"status\":\"applied\""), "blueprint applied: " + applied);
        if ("worker".equals(arm.placement())) {
            var key = SliceTargetKey.sliceTargetKey(Artifact.artifact(artifact).unwrap().base());

            await("slice target", () -> leader().kvStore().getTyped(key, SliceTargetValue.class).isPresent(), 60_000);
            placeWorkersOnly(key);
        }
    }

    private void placeWorkersOnly(SliceTargetKey key) {
        for (int attempt = 1; ; attempt++) {
            var before = leader().kvStore().getTyped(key, SliceTargetValue.class).unwrap();

            try {
                commit(key, before, before.withPlacement("WORKERS_ONLY"));
                return;
            } catch (AssertionError e) {
                if (attempt >= 5) {
                    throw e;
                }
            }
        }
    }

    /// WORKERS_ONLY is a second placement pass after the blueprint apply: the slice first loads on the cores and is then moved. Counting from the first load would
    /// measure the move, so a worker arm waits until every ACTIVE host of the slice is a worker (and at least one is).
    private long awaitPlacedOnWorkers(Arm arm) {
        long start = System.nanoTime();
        var cores = new java.util.HashSet<String>();

        instruments.forEach(i -> cores.add(i.nodeId()));
        await("slice ACTIVE on workers only", () -> {
            var hosts = activeHosts(arm);

            return !hosts.isEmpty() && hosts.stream().noneMatch(cores::contains);
        }, 600_000);
        return (System.nanoTime() - start) / 1_000_000_000L;
    }

    private java.util.Set<String> activeHosts(Arm arm) {
        var hosts = new java.util.TreeSet<String>();

        ((Map<?, ?>) leader().kvStore().snapshot()).forEach((k, v) -> {
            if (k instanceof AetherKey.NodeArtifactKey nk && nk.artifact().asString().contains(arm.artifactBase())
                && v instanceof AetherValue.NodeArtifactValue state && "ACTIVE".equals(state.state().name())) {
                hosts.add(nk.nodeId().id());
            }
        });
        return hosts;
    }

    private long awaitAttached(Arm arm) {
        long start = System.nanoTime();
        int expected = arm.g() * arm.p();

        try {
            await("G x P = " + expected + " consumer subscriptions attached", () -> attached() == expected, Long.getLong("m1.s7.attachTimeoutS", 600L) * 1_000);
        } catch (AssertionError e) {
            var bodies = cluster.status().nodes().stream()
                                .map(n -> n.id() + "=" + send("GET", n.mgmtPort(), "/api/v1/streams/declarative-consumers", "application/json", "").replace("\"", "'"))
                                .map(b -> b.length() > 700 ? b.substring(0, 700) : b)
                                .collect(Collectors.joining(" | "));

            emit("{\"kind\":\"s7-attach-failed\"," + header(arm) + ",\"subscriptionsByNode\":" + attachedByNode() + ",\"activeHosts\":\"" + activeHosts(arm) + "\",\"declarativeConsumersByNode\":\"" + bodies + "\"}");
            throw e;
        }
        return (System.nanoTime() - start) / 1_000_000_000L;
    }

    /// Events the runtime has delivered to the slice's subscribers, summed over every node that serves the route (each node counts its own).
    private long delivered() {
        long total = 0;

        for (var port : cluster.getAvailableAppHttpPorts()) {
            var matcher = Pattern.compile("\"delivered\"\\s*:\\s*(\\d+)").matcher(send("POST", port, "/api/ckpt-load/received", "application/json", "{}"));

            total += matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
        }
        return total;
    }

    /// Which node holds how many attached subscriptions, with its role (core or worker): where the consumers (and so the checkpoint writers) actually run.
    private String attachedByNode() {
        var cores = new java.util.HashSet<String>();

        instruments.forEach(i -> cores.add(i.nodeId()));
        return cluster.status().nodes().stream().map(n -> {
            var matcher = ATTACHED.matcher(send("GET", n.mgmtPort(), "/api/v1/streams/declarative-consumers", "application/json", ""));

            return "\"" + n.id() + (cores.contains(n.id()) ? "(core)" : "(worker)") + "\":" + (matcher.find() ? matcher.group(1) : "0");
        }).collect(Collectors.joining(",", "{", "}"));
    }

    /// The nodes whose NodeArtifactKey for this arm's slice exists in the leader's store.
    private String sliceHosts(Arm arm) {
        var cores = new java.util.HashSet<String>();

        instruments.forEach(i -> cores.add(i.nodeId()));
        // the snapshot also holds the leader record, which is not an AetherKey, so the key type is not trusted
        return ((Map<?, ?>) leader().kvStore().snapshot()).keySet().stream()
                       .filter(k -> k instanceof AetherKey.NodeArtifactKey nk && nk.artifact().asString().contains(arm.artifactBase()))
                       .map(k -> ((AetherKey.NodeArtifactKey) k).nodeId().id())
                       .map(id -> id + (cores.contains(id) ? "(core)" : "(worker)"))
                       .sorted().collect(Collectors.joining(" "));
    }

    private int attached() {
        return cluster.status().nodes().stream().mapToInt(n -> {
            var matcher = ATTACHED.matcher(send("GET", n.mgmtPort(), "/api/v1/streams/declarative-consumers", "application/json", ""));

            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        }).sum();
    }

    // ---- measuring -----------------------------------------------------------------------------------------------------------------

    private Map<String, Object> window(Arm arm, String phase, int number, long seconds, Producer producer) {
        var ref = instruments.getFirst();
        var before = ref.snapshot();
        long eventsBefore = producer.published.get();
        long t0 = System.nanoTime();
        var latencies = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var sampling = new boolean[]{true};
        var sampler = new Thread(() -> sampleApplyLatency(latencies, sampling), "s7-latency-sampler");

        sampler.setDaemon(true);
        sampler.start();
        sleep(seconds * 1_000);
        sampling[0] = false;
        var delta = M1Instrument.delta(before, ref.snapshot());
        double s = (System.nanoTime() - t0) / 1e9;
        long events = producer.published.get() - eventsBefore;
        var row = new TreeMap<String, Object>();

        row.put("phase", phase);
        row.put("window", number);
        row.put("seconds", s);
        row.put("eventsPublished", events);
        row.put("eventsPerSecPerPartition", events / s / arm.p());
        var sorted = latencies.stream().sorted().toList();

        row.put("applyLatencySamples", sorted.size());
        row.put("applyLatencyMedianMs", sorted.isEmpty() ? 0.0 : sorted.get(sorted.size() / 2) / 1e6);
        row.put("applyLatencyP95Ms", sorted.isEmpty() ? 0.0 : sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95))) / 1e6);
        row.put("applyLatencyMaxMs", sorted.isEmpty() ? 0.0 : sorted.getLast() / 1e6);
        row.put("producerCalls", producer.calls.get());
        row.put("producerMaxCallMs", producer.maxCallMs.get());
        row.put("checkpointPuts", delta.getOrDefault("put:" + CKPT, 0L));
        row.put("enginePutsCkpt", delta.getOrDefault("cmd.Put:" + CKPT, 0L));
        row.put("noopCkpt", delta.getOrDefault("cmd.Noop:" + CKPT, 0L));
        row.put("noopAll", delta.getOrDefault("cmd.Noop", 0L));
        row.put("slots", delta.getOrDefault("engine.slots", 0L));
        // the L sampler submits one Noop(AlertThresholdKey) per sample, each its own slot: it is the harness own load, not checkpoint or background load
        long samplerNoops = delta.getOrDefault("cmd.Noop:AlertThresholdKey", 0L);

        row.put("samplerNoops", samplerNoops);
        row.put("slotsExSampler", Math.max(0L, delta.getOrDefault("engine.slots", 0L) - samplerNoops));
        row.put("batches", delta.getOrDefault("engine.batches", 0L));
        row.put("commands", delta.getOrDefault("engine.commands", 0L));
        row.put("tx", delta.getOrDefault("tx.accepted", 0L));
        row.put("putTotal", delta.getOrDefault("put.total", 0L));
        row.put("expectedCheckpointsPerSec", arm.g() * arm.p() / T_SECONDS);
        var engines = instruments.stream().map(M1Instrument::engineProbe).collect(Collectors.joining(",", "[", "]"));
        var where = attachedByNode();
        long delivered = delivered();

        if (((Number) row.get("slots")).longValue() > Long.getLong("m1.s7.floodSlots", 3_000L) || Boolean.getBoolean("m1.s7.dumpAlways")) {
            instruments.forEach(i -> emit("{\"kind\":\"s7-pending-dump\"," + header(arm) + ",\"phase\":\"" + phase + "\",\"window\":" + number + ",\"dump\":" + i.pendingDump() + "}"));
        }
        emit("{\"kind\":\"s7-window\"," + header(arm) + "," + json(row) + ",\"families\":" + familiesJson(delta) + ",\"subscriptionsByNode\":" + where + ",\"deliveredTotal\":" + delivered + ",\"engines\":" + engines + "}");
        return row;
    }

    /// Every 500 ms a Noop with a private key goes through the leader's apply and its round trip (submit to applied) is recorded: this is L, the commit latency a
    /// checkpoint pays, taken on the same engine while the load runs. Two submissions per second: a small, stated perturbation.
    @SuppressWarnings("unchecked")
    private void sampleApplyLatency(List<Long> latencies, boolean[] sampling) {
        while (sampling[0]) {
            try {
                long t0 = System.nanoTime();
                var noop = new KVCommand.Noop<AetherKey>(new AetherKey.AlertThresholdKey("s7-latency"));
                var done = leader().<Object> apply(List.of((KVCommand<AetherKey>) noop)).await(TimeSpan.timeSpan(10).seconds());

                if (done.isSuccess()) {
                    latencies.add(System.nanoTime() - t0);
                }
            } catch (RuntimeException e) {
                // no leader for a moment: skip this sample
            }
            sleep(500);
        }
    }

    /// Waits for the trailing commit: no checkpoint put for 3 s (bounded at 60 s); returns how long that took.
    private long awaitQuiet() {
        var ref = instruments.getFirst();
        long start = System.nanoTime();
        long last = ref.snapshot().getOrDefault("put:" + CKPT, 0L);
        long lastChange = System.nanoTime();

        while ((System.nanoTime() - start) / 1_000_000 < 60_000) {
            sleep(500);
            long now = ref.snapshot().getOrDefault("put:" + CKPT, 0L);

            if (now != last) {
                last = now;
                lastChange = System.nanoTime();
            } else if ((System.nanoTime() - lastChange) / 1_000_000 >= 3_000) {
                break;
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void summary(Arm arm, List<Map<String, Object>> load, List<Map<String, Object>> idle, long attachS, long quiesceMs, Producer producer, long armSeconds) {
        double ckpt = mean(load, "checkpointPuts");
        double expected = arm.g() * arm.p() / T_SECONDS;
        double perSecond = ckpt / WINDOW_S;
        double commits = (mean(load, "enginePutsCkpt") + mean(load, "noopCkpt")) / WINDOW_S;
        double ratio = perSecond / expected;
        var s = new TreeMap<String, Object>();

        s.put("attachSeconds", attachS);
        s.put("quiesceMs", quiesceMs);
        s.put("loadWindows", load.size());
        s.put("windowSeconds", WINDOW_S);
        s.put("checkpointsPerSec", perSecond);
        s.put("expectedCheckpointsPerSec", expected);
        s.put("ratioToFormula", ratio);
        s.put("within10pct", Math.abs(ratio - 1.0) <= 0.10);
        s.put("commitsPerCheckpoint", ckpt == 0 ? 0 : commits * WINDOW_S / ckpt);
        s.put("slotsPerCheckpoint", ckpt == 0 ? 0 : mean(load, "slotsExSampler") / ckpt);
        s.put("slotsPerCheckpointInclSampler", ckpt == 0 ? 0 : mean(load, "slots") / ckpt);
        s.put("txPerCheckpoint", ckpt == 0 ? 0 : mean(load, "tx") / ckpt);
        double lSeconds = mean(load, "applyLatencyMedianMs") / 1_000.0;
        double expectedWithL = arm.g() * arm.p() / (T_SECONDS + lSeconds);

        s.put("applyLatencyMedianMs", lSeconds * 1_000.0);
        s.put("applyLatencyP95MsMean", mean(load, "applyLatencyP95Ms"));
        s.put("expectedCheckpointsPerSecWithL", expectedWithL);
        s.put("ratioToFormulaWithL", perSecond / expectedWithL);
        s.put("within10pctWithL", Math.abs(perSecond / expectedWithL - 1.0) <= 0.10);
        s.put("kPerGPPerT", commits / expected);
        s.put("idleCheckpointsPerSec", idle.stream().mapToDouble(r -> ((Number) r.get("checkpointPuts")).doubleValue() / ((Number) r.get("seconds")).doubleValue()).average().orElse(0));
        s.put("idleSlotsPerSec", idle.stream().mapToDouble(r -> ((Number) r.get("slotsExSampler")).doubleValue() / ((Number) r.get("seconds")).doubleValue()).average().orElse(0));
        s.put("rateHeld", load.stream().allMatch(r -> Math.abs(((Number) r.get("eventsPerSecPerPartition")).doubleValue() / arm.rate() - 1.0) <= 0.10));
        s.put("producerCalls", producer.calls.get());
        s.put("producerMaxCallMs", producer.maxCallMs.get());
        s.put("producerSent", producer.sent.get());
        s.put("producerPublished", producer.published.get());
        s.put("producerFailedCalls", producer.failed.get());
        s.put("armSeconds", armSeconds);
        emit("{\"kind\":\"s7-summary\"," + header(arm) + "," + json(s) + "}");
    }

    private static double mean(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(r -> ((Number) r.get(key)).doubleValue()).average().orElse(0);
    }

    // ---- producer ------------------------------------------------------------------------------------------------------------------

    /// `m1.s7.producers` (default 16) threads, each posting a `produce` batch every 100 ms to a node's app port, together `rate x P` events per second (a
    /// fractional carry keeps each thread's average exact). A produce call can take seconds when the stream's partition owners are busy, so one thread would
    /// silently fall below the target: the threads make the target reachable, and `published` counts what the slice reported published, `sent` what was
    /// requested, `calls`/`maxCallMs` how slow the publish path was. A window whose `eventsPerSecPerPartition` is off target is a window the formula does
    /// not apply to; the summary says whether every load window held the rate within 10%.
    private final class Producer {
        private static final int THREADS = Integer.getInteger("m1.s7.producers", 16);
        private final Arm arm;
        private final List<Thread> threads = new ArrayList<>();
        volatile boolean stop;
        final AtomicLong sent = new AtomicLong();
        final AtomicLong published = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong calls = new AtomicLong();
        final AtomicLong maxCallMs = new AtomicLong();

        Producer(Arm arm) {
            this.arm = arm;
        }

        void start() {
            for (int i = 0; i < THREADS; i++) {
                var thread = new Thread(this::run, "s7-producer-" + i);

                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
        }

        void finish() throws InterruptedException {
            stop = true;
            for (var thread : threads) {
                thread.join(60_000);
            }
        }

        private void run() {
            long next = System.nanoTime();
            double carry = 0;

            while (!stop) {
                next += 100_000_000L;
                double want = arm.rate() * arm.p() * 0.1 / THREADS + carry;
                int count = (int) want;

                carry = want - count;
                if (count > 0) {
                    post(count);
                }
                long wait = (next - System.nanoTime()) / 1_000_000;

                if (wait > 0) {
                    sleep(wait);
                } else if (wait < -2_000) {
                    next = System.nanoTime();
                }
            }
        }

        private void post(int count) {
            var ports = cluster.getAvailableAppHttpPorts();

            if (ports.isEmpty()) {
                failed.incrementAndGet();
                return;
            }
            long t0 = System.nanoTime();
            var answer = send("POST", ports.getFirst(), "/api/ckpt-load/produce", "application/json", "{\"count\":" + count + "}");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            var matcher = Pattern.compile("\"published\"\\s*:\\s*(\\d+)").matcher(answer);

            calls.incrementAndGet();
            maxCallMs.accumulateAndGet(ms, Math::max);
            sent.addAndGet(count);
            if (matcher.find()) {
                published.addAndGet(Long.parseLong(matcher.group(1)));
            } else {
                failed.incrementAndGet();
            }
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------------------------------------------

    private static String installedJarSha(Arm arm) throws Exception {
        var artifact = arm.artifactBase() + "-load-slice";
        var jar = Path.of(org.pragmatica.aether.slice.repository.maven.MavenLocalRepoLocator.findLocalRepository(), "org/pragmatica/aether/test", artifact, VERSION, artifact + "-" + VERSION + ".jar");

        check(Files.isRegularFile(jar), "slice jar not installed: " + jar + " (python3 gen.py all <dir>; mvn -f <dir>/pom.xml install)");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }

    private int mgmtPort() {
        var leaderId = cluster.currentLeader().unwrap();

        return cluster.status().nodes().stream().filter(n -> n.id().equals(leaderId)).findFirst().orElseThrow().mgmtPort();
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }

    private void commit(AetherKey key, AetherValue expected, AetherValue replacement) {
        var node = leader();
        var id = UUID.randomUUID().toString();
        var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, id, authority, List.of(),
                                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.some(expected), Option.some(replacement))));
        var results = node.<Object> apply(List.of(transaction)).await(TimeSpan.timeSpan(30).seconds()).unwrap();

        check(results.stream().anyMatch(o -> o instanceof KVCommand.TransactionResult r && r.transactionId().equals(id) && r.accepted()), "leader tx accepted for " + key.asString());
    }

    private static String send(String method, int port, String path, String contentType, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", contentType)
                                 .timeout(Duration.ofSeconds(30))
                                 .method(method, HttpRequest.BodyPublishers.ofString(body))
                                 .build();

        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            return "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\":\"interrupted\"}";
        }
    }

    private String header(Arm arm) {
        return "\"label\":\"" + LABEL + "\",\"sha\":\"" + SHA + "\",\"host\":\"" + HOST + "\",\"arm\":\"" + arm.label() + "\",\"g\":" + arm.g() + ",\"p\":" + arm.p()
               + ",\"placement\":\"" + arm.placement() + "\",\"ratePerPartition\":" + arm.rate() + ",\"utc\":\"" + Instant.now() + "\"";
    }

    private static String json(Map<String, Object> row) {
        return row.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + (e.getValue() instanceof String ? "\"" + e.getValue() + "\"" : e.getValue())).collect(Collectors.joining(","));
    }

    private static String familiesJson(Map<String, Long> map) {
        return map.entrySet().stream().filter(e -> e.getKey().startsWith("put:") || e.getKey().startsWith("remove:") || e.getKey().startsWith("cmd.")).map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                  .collect(Collectors.joining(",", "{", "}"));
    }

    private void emit(String line) {
        out.println(line);
    }

    private static void log(Arm arm, String message) {
        System.out.println("S7 " + Instant.now() + " " + arm.label() + " " + message);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("s7: " + what);
        }
    }

    private static void await(String what, BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("s7: timed out waiting for " + what);
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
