// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

import org.pragmatica.aether.ember.EmberCluster;

/// Streaming-persistence A6 — full-cluster restart crash-durability proof for the per-partition WAL.
///
/// This is the end-to-end gate that the Phase A-WAL write-ahead log actually survives a restart with
/// ZERO loss of acked events. It complements `StreamFanoutConsumerTest` (which locks the in-memory
/// fan-out semantics with the WAL OFF) by turning the WAL ON: the cluster is built with a writable,
/// restart-stable per-node data dir (`EmberCluster.withDataBaseDir(@TempDir)`), so each node's disk
/// tier and per-partition WAL (`<baseDir>/<nodeId>/stream-segments/<nodeId>/wal/test-events/0.wal`)
/// are live.
///
/// The proof hinges on a deliberately small batch: N=50 events with the `test-events` resource's
/// count retention (100000) keeps every event in the volatile hot ring — nothing is ever sealed or
/// evicted to a segment. So after a full-cluster `stop()`→`start()` the in-memory ring is gone and
/// the ONLY place those 50 events can come back from is the WAL. A plain graceful `stop()`/`start()`
/// already exercises "restart without losing acked events" because publish fsyncs each event into
/// the WAL BEFORE its ack resolves (durability contract); recovering them post-restart therefore
/// proves WAL append + replay, not segment sealing.
///
/// Forge's Rabia persistence is in-memory, so a full-cluster restart wipes the KV (blueprint +
/// stream config). The test re-deploys the same `test-stream` blueprint after restart: that re-puts
/// the deterministic `test-events` stream config, the owner re-materializes partition 0, and
/// partition build opens-or-recovers its WAL and replays the un-sealed tail into a fresh ring before
/// any read is served. Re-deploy carries NO events — it only restores the stream config; the events
/// themselves are recovered from the WAL.
///
/// `test-stream` keeps NO server-side consumer cursor (the slice exposes only `publish`/`read`; every
/// consumer tracks its own offset client-side), so cursor durability is not exercised here — the
/// events-survive assertion is the required A6 proof.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamCrashDurabilityTest {
    private static final int BASE_PORT = 13500;
    private static final int BASE_MGMT_PORT = 13600;
    private static final int BASE_APP_HTTP_PORT = 13700;
    private static final int NODES = 5;
    private static final int INSTANCES = 5;
    private static final int EVENT_COUNT = 50;

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(240);
    private static final long POLL_GAP_NANOS = Duration.ofMillis(20).toNanos();

    private static final String STREAM_SLICE = TestArtifacts.STREAM_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:stream-crash-durability:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Pattern EVENT_OBJECT = Pattern.compile("\\{[^{}]*\"offset\"[^{}]*}");
    private static final Pattern OFFSET_FIELD = Pattern.compile("\"offset\"\\s*:\\s*(\\d+)");
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("\"payload\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");

    Path baseDir;

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    /// One stream event as observed by a consumer over HTTP.
    private record Event(long offset, String payload) {}

    @BeforeAll
    void setUp(@TempDir Path tempDir) {
        this.baseDir = tempDir;

        // A ConfigurationProvider must be present for the node to enable resource provisioning
        // (StreamPublisher / StreamAccess); without it AetherNode installs a no-op facade that fails
        // every resource-backed slice (mirrors StreamFanoutConsumerTest / ForgeServer).
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "scd", Option.some(configProvider));
        // Opt in to a writable, restart-stable per-node data dir -> disk tier + per-partition WAL ON.
        cluster.withDataBaseDir(baseDir);

        startAndAwaitReady();
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

    /// THE A6 gate: publish N acked events that live only in the hot ring + WAL, fully restart the
    /// cluster preserving the per-node data dirs, then prove all N are recovered via WAL replay.
    @Test
    void fullClusterRestart_recoversAllAckedEvents_viaWalReplay() throws IOException {
        var port = appPort();
        var base = head(port);

        assertThat(base)
            .describedAs("fresh dedicated cluster: 'test-events' starts empty (offset 0)")
            .isZero();

        publishBatch(port, "crash", EVENT_COUNT);

        var published = drain(port, base, EVENT_COUNT, deadlineNanos());
        assertContiguousBatch(published, base, "crash");

        // Guard against a false-green where the dir was writable but the WAL silently stayed OFF (or
        // events were never appended): a NON-EMPTY 'test-events' WAL must exist on disk, proving the
        // owner appended + fsync'd the acked events before ack. WAL OFF would leave none.
        assertWalActiveForTestEvents();

        // Full-cluster graceful restart preserving per-node data dirs (same stable node ids -> same
        // dirs). The in-memory ring is lost; only the WAL can bring the 50 events back. Recovery is
        // reconcile-driven: after restart the deterministic HRW owner re-materializes its partition,
        // replays its WAL tail into a fresh ring and — finding no live peer source on a cold start —
        // self-promotes to CAUGHT_UP after a bounded wait, then serves owner-routed reads. So the read
        // is drained with a generous deadline until all N reappear (returns as soon as they do).
        restartCluster();

        var recoveredPort = appPort();
        var recovered = drain(recoveredPort, 0L, EVENT_COUNT, recoveryDeadlineNanos());

        assertThat(recovered)
            .describedAs("WAL replay must recover ALL %d acked events after full-cluster restart "
                         + "(ring was in-memory and lost; nothing was sealed)", EVENT_COUNT)
            .hasSize(EVENT_COUNT);
        assertContiguousBatch(recovered, 0L, "crash");
    }

    // --- restart ------------------------------------------------------------

    private void restartCluster() {
        cluster.stop()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster stop failed: " + cause.message());
               });

        startAndAwaitReady();
    }

    private void startAndAwaitReady() {
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

        // A6: gate on FULL membership. allNodesHealthy() only checks each node's LOCAL quorum flag, which
        // turns true at quorum (3/5). After a full-cluster restart the cold-boot convergence window must
        // let ALL NODES nodes re-form before we publish (pre-restart) or read (post-restart): with RF=1
        // the deterministic HRW owner of test-events[0] must be a rejoined node holding the WAL, not an
        // empty-WAL survivor picked because a straggler was prematurely evicted.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> allNodesAreMembers(NODES));

        // Re-deploy: Forge KV is in-memory, so a full-cluster restart wiped the stream config. This
        // re-puts the deterministic `test-events` config -> owner re-materializes partition 0 ->
        // partition build opens-or-recovers the WAL and replays the un-sealed tail. Idempotent if KV
        // survived. Carries NO events (only the stream config); events come from the WAL.
        deployStreamSlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::appHttpReady);
    }

    // --- WAL on-disk assertion ---------------------------------------------

    private void assertWalActiveForTestEvents() throws IOException {
        var testEventsWals = walFiles(baseDir).stream()
                                              .filter(StreamCrashDurabilityTest::isTestEventsWal)
                                              .toList();

        assertThat(testEventsWals)
            .describedAs("stream WAL must be ACTIVE: a 'test-events' WAL file must exist under %s "
                         + "(WAL OFF -> none; acked events would not survive restart)", baseDir)
            .isNotEmpty();

        // Stronger than mere existence (every node opens an empty WAL on materialization): the owner
        // must have actually APPENDED + fsync'd the acked events, so the total on-disk WAL bytes for
        // 'test-events' must be positive.
        long totalBytes = 0;

        for (var wal : testEventsWals) {
            totalBytes += Files.size(wal);
        }

        assertThat(totalBytes)
            .describedAs("the owner's 'test-events' WAL must hold the acked events (non-empty) — proof they "
                         + "were appended + fsync'd before ack, not just that the WAL dir was writable")
            .isPositive();
    }

    private static boolean isTestEventsWal(Path walFile) {
        var parent = walFile.getParent();

        return parent != null && parent.getFileName().toString().equals("test-events");
    }

    private static List<Path> walFiles(Path base) throws IOException {
        try (var paths = Files.walk(base)) {
            return paths.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".wal"))
                        .toList();
        }
    }

    // --- assertions ---------------------------------------------------------

    private void assertContiguousBatch(List<Event> events, long base, String tag) {
        assertThat(events)
            .describedAs("consumer must receive exactly %d events (no dups, no gaps)", EVENT_COUNT)
            .hasSize(EVENT_COUNT);

        for (int i = 0; i < EVENT_COUNT; i++) {
            assertThat(events.get(i).offset())
                .describedAs("event %d offset (contiguous from base %d)", i, base)
                .isEqualTo(base + i);
            assertThat(events.get(i).payload())
                .describedAs("event %d payload (in publish order)", i)
                .isEqualTo(tag + "-" + i);
        }
    }

    // --- consumers ----------------------------------------------------------

    private List<Event> drain(int port, long base, int count, long deadlineNanos) {
        var collected = new ArrayList<Event>();
        var offset = base;

        while (collected.size() < count && System.nanoTime() < deadlineNanos) {
            var events = readEvents(port, offset, count);

            if (events.isEmpty()) {
                LockSupport.parkNanos(POLL_GAP_NANOS);
                continue;
            }

            collected.addAll(events);
            offset = events.getLast().offset() + 1;
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

    private void publish(int port, String payload) {
        var body = "{\"payload\":\"" + payload + "\"}";
        var response = httpPost(port, "/api/stream/publish", body);

        assertThat(response)
            .describedAs("publish '%s' must succeed (and thus be WAL-fsync'd before ack)", payload)
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

    private long recoveryDeadlineNanos() {
        return System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
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

    /// A6 full-membership gate: poll the LEADER's `/api/health` and require quorum AND a member count
    /// that has reached the full expected set. `nodeCount` is the cluster-wide membership count the
    /// bootstrap formation phase also gates on (see `BootstrapPhaseFormation.healthMeetsFloor`).
    private boolean allNodesAreMembers(int expected) {
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderPort + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && healthHasFullMembership(r.body(), expected))
                   .or(false);
    }

    private static boolean healthHasFullMembership(String body, int expected) {
        if (!body.contains("\"quorum\":true")) {
            return false;
        }

        var matcher = NODE_COUNT_FIELD.matcher(body);

        return matcher.find() && Integer.parseInt(matcher.group(1)) >= expected;
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
