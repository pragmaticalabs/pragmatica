// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

import org.pragmatica.aether.ember.EmberCluster;

/// #265 STEP 0 — streaming end-to-end BASELINE (regression net for log/Kafka fan-out semantics).
///
/// Locks the CURRENT in-memory streaming behaviour BEFORE the #265 placement-aware hydration
/// refactor (and the streaming-persistence work) touch the path. Runs a 5-node in-JVM Ember
/// cluster and deploys the minimal stream-only `test-stream` blueprint (no Postgres) — a single
/// partition (clean global order) with count-based retention far larger than any test batch (a
/// slow consumer is never evicted → no spurious `CursorExpired`).
///
/// Confirmed semantics under test:
///
///   - The server keeps NO per-consumer state; every consumer tracks its OWN offset and reads
///     ALL events from where it asks — fan-out is purely client-side.
///   - Replay-from-earliest works: re-reading from a consumer's origin returns the full batch.
///   - A late-joining consumer reads full history from offset 0.
///   - A slow consumer loses nothing while publishing continues (count retention >> batch).
///   - With partitions=1, offsets are strictly monotonic and payloads are in publish order.
///
/// The `test-stream` stream is a single shared log; each scenario captures `base` (the head
/// offset before it publishes) and treats it as that scenario's logical "offset 0", so the tests
/// are robust to the shared, accumulating log and to `@Nested` execution order.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamFanoutConsumerTest {
    private static final int BASE_PORT = 13000;
    private static final int BASE_MGMT_PORT = 13100;
    private static final int BASE_APP_HTTP_PORT = 13200;
    private static final int NODES = 5;
    private static final int INSTANCES = 5;
    private static final int EVENT_COUNT = 50;

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(90);
    private static final long POLL_GAP_NANOS = Duration.ofMillis(20).toNanos();

    private static final String STREAM_SLICE = TestArtifacts.STREAM_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:stream-fanout:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Pattern EVENT_OBJECT = Pattern.compile("\\{[^{}]*\"offset\"[^{}]*}");
    private static final Pattern OFFSET_FIELD = Pattern.compile("\"offset\"\\s*:\\s*(\\d+)");
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("\"payload\"\\s*:\\s*\"([^\"]*)\"");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    /// One stream event as observed by a consumer over HTTP.
    private record Event(long offset, String payload) {}

    /// A consumer's pace profile: read batch size + nanos to pause between polls.
    private record Pace(int batch, long paceNanos) {}

    @BeforeAll
    void setUp() {
        // A ConfigurationProvider must be present for the node to enable resource provisioning
        // (StreamPublisher / StreamAccess) — without it AetherNode installs a no-op facade that
        // fails every resource-backed slice with "Resource provisioning not configured". The
        // concrete stream config (partitions=1, count retention) is resolved from the slice's
        // packaged resources.toml via the KV-overlay composite; this node-level provider only
        // flips the gate (mirrors ForgeServer.buildConfigurationProvider).
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "sf", Option.some(configProvider));
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        deployStreamSlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::appHttpReady);

        // appHttpReady only proves the READ path serves. Immediately after deployment a publish can still
        // land before the partition OWNER has materialized its buffer, so the forwarded append briefly
        // fails; gate on a warm-up publish actually succeeding so tests start write-ready.
        //
        // NOTE: the 5s forward timeouts this gate originally chased were NOT "governor write-readiness
        // lag" — they were the #467 defect where the app publish path forwarded to the STREAMING leader
        // instead of the partition owner and could send-to-self (which QUIC silently drops). That is now
        // fixed at the source (owner-routed publish + self-guard); this gate only smooths genuine
        // owner-materialization lag.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::publishReady);
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
            httpDelete(leaderPort, "/api/blueprints/" + BLUEPRINT_ID);
            cluster.stop()
                   .await();
        }
    }

    @Nested
    class FanOutCompleteness {
        /// Publish N, then run M parallel consumers at DIFFERENT paces, each reading independently
        /// from the scenario origin. Every consumer must receive ALL N in order — no dups, no gaps.
        @Test
        void parallelConsumers_eachReceiveAllEvents_atDifferentPaces() {
            var port = appPort();
            var base = head(port);
            var tag = "fanout";
            publishBatch(port, tag, EVENT_COUNT);

            var paces = List.of(new Pace(1, Duration.ofMillis(3).toNanos()),
                                 new Pace(5, Duration.ofMillis(1).toNanos()),
                                 new Pace(13, 0L),
                                 new Pace(EVENT_COUNT, Duration.ofMillis(7).toNanos()));

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var deadline = deadlineNanos();
                var futures = paces.stream()
                                   .map(pace -> CompletableFuture.supplyAsync(
                                       () -> drain(port, base, EVENT_COUNT, pace, deadline), executor))
                                   .toList();
                var results = futures.stream().map(CompletableFuture::join).toList();

                results.forEach(events -> assertContiguousBatch(events, base, EVENT_COUNT, tag));
            }
        }
    }

    @Nested
    class ReReadFromEarliest {
        /// A consumer that reached the tail resets its offset back to the origin and re-reads the
        /// whole batch (full replay) — proving the server retains the log, not consumer position.
        @Test
        void consumerAtTail_resetsToOrigin_replaysFullBatch() {
            var port = appPort();
            var base = head(port);
            var tag = "replay";
            publishBatch(port, tag, EVENT_COUNT);

            var firstPass = drain(port, base, EVENT_COUNT, new Pace(7, 0L), deadlineNanos());
            assertContiguousBatch(firstPass, base, EVENT_COUNT, tag);

            var replay = drain(port, base, EVENT_COUNT, new Pace(EVENT_COUNT, 0L), deadlineNanos());
            assertContiguousBatch(replay, base, EVENT_COUNT, tag);
        }
    }

    @Nested
    class LateJoiningConsumer {
        /// A consumer started AFTER all N are published reads the full history from absolute
        /// offset 0; the batch it just missed appears as the contiguous tail of that history.
        @Test
        void consumerStartedAfterPublish_readsFullHistoryFromZero() {
            var port = appPort();
            var base = head(port);
            var tag = "latejoin";
            publishBatch(port, tag, EVENT_COUNT);

            var fromOrigin = drain(port, base, EVENT_COUNT, new Pace(11, 0L), deadlineNanos());
            assertContiguousBatch(fromOrigin, base, EVENT_COUNT, tag);

            var fullHistory = drainAll(port);
            assertThat(fullHistory).hasSizeGreaterThanOrEqualTo((int) base + EVENT_COUNT);
            var tailBlock = fullHistory.subList((int) base, (int) base + EVENT_COUNT);
            assertContiguousBatch(tailBlock, base, EVENT_COUNT, tag);
        }
    }

    @Nested
    class SlowConsumerNoLoss {
        /// One slow consumer drains while publishing continues concurrently. With count-based
        /// retention far above the batch size, it eventually receives every event — no gaps.
        @Test
        void slowConsumer_whilePublishing_losesNothing() {
            var port = appPort();
            var base = head(port);
            var tag = "slow";

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var publisher = CompletableFuture.runAsync(
                    () -> publishPaced(port, tag, EVENT_COUNT, Duration.ofMillis(5).toNanos()), executor);
                var slow = CompletableFuture.supplyAsync(
                    () -> drain(port, base, EVENT_COUNT, new Pace(1, Duration.ofMillis(15).toNanos()), deadlineNanos()),
                    executor);

                var collected = slow.join();
                publisher.join();

                assertContiguousBatch(collected, base, EVENT_COUNT, tag);
            }
        }
    }

    @Nested
    class OrderingMonotonicOffsets {
        /// partitions=1: offsets are strictly monotonic (increase by exactly 1) and payloads come
        /// back in publish order.
        @Test
        void singlePartition_offsetsStrictlyMonotonic_payloadsInPublishOrder() {
            var port = appPort();
            var base = head(port);
            var tag = "order";
            publishBatch(port, tag, EVENT_COUNT);

            var events = drain(port, base, EVENT_COUNT, new Pace(EVENT_COUNT, 0L), deadlineNanos());

            assertThat(events).hasSize(EVENT_COUNT);
            for (int i = 0; i < EVENT_COUNT; i++) {
                assertThat(events.get(i).offset())
                    .describedAs("offset at index %d strictly monotonic from base %d", i, base)
                    .isEqualTo(base + i);
                assertThat(events.get(i).payload())
                    .describedAs("payload at index %d in publish order", i)
                    .isEqualTo(tag + "-" + i);
            }
        }
    }

    // --- assertions ---------------------------------------------------------

    private void assertContiguousBatch(List<Event> events, long base, int count, String tag) {
        assertThat(events)
            .describedAs("consumer must receive exactly %d events (no dups, no gaps)", count)
            .hasSize(count);

        for (int i = 0; i < count; i++) {
            assertThat(events.get(i).offset())
                .describedAs("event %d offset (contiguous from base %d)", i, base)
                .isEqualTo(base + i);
            assertThat(events.get(i).payload())
                .describedAs("event %d payload (in publish order)", i)
                .isEqualTo(tag + "-" + i);
        }
    }

    // --- consumers ----------------------------------------------------------

    private List<Event> drain(int port, long base, int count, Pace pace, long deadlineNanos) {
        var collected = new ArrayList<Event>();
        var offset = base;

        while (collected.size() < count && System.nanoTime() < deadlineNanos) {
            var events = readEvents(port, offset, pace.batch());

            if (events.isEmpty()) {
                LockSupport.parkNanos(POLL_GAP_NANOS);
                continue;
            }

            collected.addAll(events);
            offset = events.getLast().offset() + 1;

            if (pace.paceNanos() > 0) {
                LockSupport.parkNanos(pace.paceNanos());
            }
        }

        return List.copyOf(collected);
    }

    private List<Event> drainAll(int port) {
        var all = new ArrayList<Event>();
        var offset = 0L;

        while (true) {
            var events = readEvents(port, offset, 200);

            if (events.isEmpty()) {
                return List.copyOf(all);
            }

            all.addAll(events);
            offset = events.getLast().offset() + 1;
        }
    }

    private long head(int port) {
        return drainAll(port).size();
    }

    // --- publishing ---------------------------------------------------------

    private void publishBatch(int port, String tag, int count) {
        for (int i = 0; i < count; i++) {
            publish(port, tag + "-" + i);
        }
    }

    private void publishPaced(int port, String tag, int count, long gapNanos) {
        for (int i = 0; i < count; i++) {
            publish(port, tag + "-" + i);
            LockSupport.parkNanos(gapNanos);
        }
    }

    private void publish(int port, String payload) {
        var body = "{\"payload\":\"" + payload + "\"}";
        var response = httpPost(port, "/api/stream/publish", body);

        assertThat(response)
            .describedAs("publish '%s' must succeed", payload)
            .doesNotContain("\"error\"")
            .contains("published");
    }

    private List<Event> readEvents(int port, long fromOffset, int maxEvents) {
        var body = "{\"fromOffset\":" + fromOffset + ",\"maxEvents\":" + maxEvents + "}";

        return parseEvents(httpPost(port, "/api/stream/read", body));
    }

    private static List<Event> parseEvents(String body) {
        var events = new ArrayList<Event>();
        Matcher objects = EVENT_OBJECT.matcher(body);

        while (objects.find()) {
            var object = objects.group();
            Matcher offset = OFFSET_FIELD.matcher(object);
            Matcher payload = PAYLOAD_FIELD.matcher(object);

            if (offset.find() && payload.find()) {
                events.add(new Event(Long.parseLong(offset.group(1)), payload.group(1)));
            }
        }

        return List.copyOf(events);
    }

    // --- deployment + readiness --------------------------------------------

    private void deployStreamSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, STREAM_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = postBlueprintWithRetry(leaderPort, blueprint);

        assertThat(response)
            .describedAs("stream-slice deployment")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var body = httpPost(ports.getFirst(), "/api/stream/read", "{\"fromOffset\":0,\"maxEvents\":1}");

        return !body.contains("\"error\"") && body.contains("events");
    }

    /// Warm-up publish readiness: immediately after deployment a publish can land before the partition
    /// OWNER has materialized its buffer (placement / stream-config lag), so the forwarded append briefly
    /// fails even though the READ path already serves. Returns true once a publish actually succeeds. (The
    /// prior 5s-timeout symptom was the #467 leader-misroute defect, now fixed — not this materialization lag.)
    private boolean publishReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var response = httpPost(ports.getFirst(), "/api/stream/publish", "{\"payload\":\"__warmup__\"}");

        return !response.contains("\"error\"") && response.contains("published");
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(s -> s.artifact().equals(STREAM_SLICE) && s.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("Stream slice deployment FAILED: " + STREAM_SLICE);
        }
    }

    private int appPort() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private long deadlineNanos() {
        return System.nanoTime() + DRAIN_TIMEOUT.toNanos();
    }

    private boolean allNodesHealthy() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }

    // --- HTTP ---------------------------------------------------------------

    private String postBlueprintWithRetry(int port, String body) {
        var lastResponse = ERROR_FALLBACK;

        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = httpPostToml(port, "/api/blueprints", body);

            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }

            if (attempt < 3) {
                LockSupport.parkNanos(Duration.ofSeconds(2).toNanos());
            }
        }

        return lastResponse;
    }

    private String httpPostToml(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpDelete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
