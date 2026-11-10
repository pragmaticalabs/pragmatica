// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// MANUAL MEASUREMENT, excluded from CI by the `Benchmark` tag: the publish acknowledgement latency of a
/// `confirmation_factor` 2 stream at a fixed offered rate, for comparing two builds (the cost gate of #2981 direction B,
/// the ack-time ownership check). Three Ember nodes; one partition, RF 3 and CF 2 (the cluster defaults); the owner's own
/// local publish path (owner-admitted append at the replica floor, then [StreamPartitionManager#awaitReplication], what
/// `StreamWriteRouter` runs for a self-owned partition), driven open-loop: each publish starts at its scheduled slot and
/// its latency runs from that slot to its acknowledgement, so a slow acknowledgement cannot lower the offered rate.
///
/// Appends one line per run to `ackcost.out` (default `target/ackcost.txt`): label, sha, rate, counts, p50/p90/p99/max in
/// microseconds, and the host's 1-minute load average at the start and end of the measured window.
///
/// System properties: `ackcost.rate` (publishes per second, default 200), `ackcost.warmupS` (5), `ackcost.seconds` (30),
/// `ackcost.label`, `ackcost.sha`, `ackcost.out`.
@Tag("Benchmark")
@PortBudget
class EmberAckLatencyBenchmarkTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long ISR_BUDGET_MS = 60_000L;
    private static final int RATE = Integer.getInteger("ackcost.rate", 200);
    private static final long WARMUP_S = Long.getLong("ackcost.warmupS", 5L);
    private static final long MEASURE_S = Long.getLong("ackcost.seconds", 30L);
    private static final String LABEL = System.getProperty("ackcost.label", "unlabelled");
    private static final String SHA = System.getProperty("ackcost.sha", "unknown");
    private static final Path OUT = Path.of(System.getProperty("ackcost.out", "target/ackcost.txt"));
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";
    private static final String STREAM = "ackcost";
    private static final String ENGINE_KEY = NAMESPACE + ":" + STREAM + ":" + VERSION;

    @TempDir
    Path dataDir;

    private EmberCluster cluster;

    private record Response(int status, String body) {
        boolean acked() {
            return status >= 200 && status < 300;
        }
    }

    /// One window's samples: latencies of acknowledged publishes, and the count of failed ones.
    private record Window(ConcurrentLinkedQueue<Long> latenciesNanos, AtomicLong failures, AtomicLong outstanding) {
        static Window window() {
            return new Window(new ConcurrentLinkedQueue<>(), new AtomicLong(), new AtomicLong());
        }
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(900)
    void cf2Publish_atFixedRate_reportsAckLatency() throws Exception {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();

        var created = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + STREAM + "/" + VERSION, "{\"partitions\":1}");

        assertThat(created.acked()).as("create: %s", created.body()).isTrue();

        var record = awaitFullIsr();
        var owner = cluster.getNode(record.owner().id()).unwrap();
        var manager = owner.streamPartitionManager();

        assertThat(manager.confirmationFactorFor(ENGINE_KEY)).as("the measured stream is CF 2").isEqualTo(2);
        assertThat(acknowledgedWithin(owner, "control").acked()).as("control publish acknowledged").isTrue();

        drive(manager, WARMUP_S, Window.window());
        var load1Start = loadAverage();
        var measured = Window.window();
        drive(manager, MEASURE_S, measured);
        var load1End = loadAverage();

        report(measured, load1Start, load1End);
        assertThat(measured.failures().get()).as("no failed publish in the measured window").isZero();
    }

    /// Offers `RATE` publishes per second for `seconds`, open-loop, then waits (bounded) for the outstanding ones.
    private void drive(StreamPartitionManager manager, long seconds, Window window) throws InterruptedException {
        var periodNanos = 1_000_000_000L / RATE;
        var total = seconds * RATE;
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var issued = new AtomicLong();

        scheduler.scheduleAtFixedRate(() -> issue(manager, window, issued, total), 0L, periodNanos, TimeUnit.NANOSECONDS);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds + 30);

        while ((issued.get() < total || window.outstanding().get() > 0) && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        scheduler.shutdownNow();
    }

    private static void issue(StreamPartitionManager manager, Window window, AtomicLong issued, long total) {
        if (issued.getAndIncrement() >= total) {
            return;
        }
        var started = System.nanoTime();
        var payload = ("p-" + started).getBytes(StandardCharsets.UTF_8);

        window.outstanding().incrementAndGet();
        manager.publishLocalAtFloor(ENGINE_KEY, 0, payload, System.currentTimeMillis(), 1)
               .async()
               .flatMap(offset -> manager.awaitReplication(ENGINE_KEY, 0, offset, 1))
               .onResult(result -> record(window, started, result));
    }

    private static void record(Window window, long started, Result<?> result) {
        var elapsed = System.nanoTime() - started;

        result.onSuccess(_ -> window.latenciesNanos().add(elapsed))
              .onFailure(_ -> window.failures().incrementAndGet());
        window.outstanding().decrementAndGet();
    }

    private void report(Window window, double load1Start, double load1End) throws IOException {
        var sorted = new ArrayList<>(window.latenciesNanos());

        sorted.sort(Long::compare);
        var line = "ACKCOST label=%s sha=%s utc=%s rate=%d seconds=%d acked=%d failed=%d p50us=%d p90us=%d p99us=%d maxus=%d load1Start=%.2f load1End=%.2f%n".formatted(LABEL,
                                                                                                                                                                         SHA,
                                                                                                                                                                         Instant.now(),
                                                                                                                                                                         RATE,
                                                                                                                                                                         MEASURE_S,
                                                                                                                                                                         sorted.size(),
                                                                                                                                                                         window.failures()
                                                                                                                                                                               .get(),
                                                                                                                                                                         percentileMicros(sorted,
                                                                                                                                                                                          0.50),
                                                                                                                                                                         percentileMicros(sorted,
                                                                                                                                                                                          0.90),
                                                                                                                                                                         percentileMicros(sorted,
                                                                                                                                                                                          0.99),
                                                                                                                                                                         percentileMicros(sorted,
                                                                                                                                                                                          1.0),
                                                                                                                                                                         load1Start,
                                                                                                                                                                         load1End);

        System.out.print(line);
        Files.createDirectories(OUT.toAbsolutePath()
                                   .getParent());
        Files.writeString(OUT, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static long percentileMicros(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return -1L;
        }
        var index = (int) Math.min(sorted.size() - 1, Math.ceil(quantile * sorted.size()) - 1);

        return sorted.get(Math.max(0, index)) / 1_000L;
    }

    private static double loadAverage() {
        try {
            return Double.parseDouble(Files.readString(Path.of("/proc/loadavg"))
                                           .split(" ")[0]);
        } catch (IOException | RuntimeException e) {
            return -1.0;
        }
    }

    /// The control publish, retried while the owner is still activating (a transient refusal), until acknowledged.
    private Response acknowledgedWithin(AetherNode owner, String payload) {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;
        var last = publish(owner, payload);

        while (!last.acked() && System.currentTimeMillis() < deadline) {
            sleepQuietly(500L);
            last = publish(owner, payload);
        }

        return last;
    }

    private Response publish(AetherNode node, String payload) {
        return post(mgmtPort(node), "/api/v1/streams/" + NAMESPACE + "/" + STREAM + "/" + VERSION + "/publish", "{\"data\":\"" + payload + "\"}");
    }

    private int mgmtPort(AetherNode node) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(status -> status.id().equals(node.self().id()))
                      .findFirst()
                      .orElseThrow()
                      .mgmtPort();
    }

    /// The committed record once its ISR holds three members (owner + both replicas).
    private StreamPartitionOwnershipValue awaitFullIsr() {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord().filter(value -> value.isrVersion() > 0 && value.isr().size() == 3);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(250L);
        }

        throw new AssertionError("no committed ISR of three for " + ENGINE_KEY + " within " + ISR_BUDGET_MS + "ms; last: " + committedRecord());
    }

    private Option<StreamPartitionOwnershipValue> committedRecord() {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(ENGINE_KEY, 0),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "ackcost");

        built.withDataBaseDir(dataDir);

        return built;
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort().or(-1);
    }

    private void awaitLeader() {
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(500L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Response post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            return new Response(-1, e.toString());
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
