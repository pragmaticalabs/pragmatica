// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.stream.StreamReadRouter.ReplicaSetView;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
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

/// Shared 5-node in-JVM Forge harness for the two multi-partition stream fixtures (#429 / #430),
/// both deploying the `test-stream-multipart` blueprint (`streams.multipart-events`, partitions=4,
/// replicas=2, min-sync-replicas=2). Mirrors [AbstractStreamOwnerFailover] structure but for a
/// FOUR-partition stream: keyless app publishes round-robin across the four partitions
/// (`DefaultStreamPublisher#resolvePartition`), so driving every publish through ONE stable app port
/// spreads events deterministically over all partitions and HRW places their owners across the
/// cluster. Each publish AWAITs a replica ack (min-sync-2), so a successful publish is proof the
/// write is durable on >=2 nodes.
///
/// Concrete variants ([MultiPartitionStreamTest] #429, [StreamPublishReshuffleTest] #430) carry the
/// `@Tag("Heavy")` / `@Execution(SAME_THREAD)` / `@TestInstance(PER_CLASS)` annotations and their own
/// disjoint base-port ranges so the suite can run both in one JVM without TCP contention; the
/// `@BeforeAll`/`@AfterAll` cluster lifecycle lives here.
///
/// The replica-set view is read IN-JVM off the owner node's `StreamReadRouter.replicaSnapshot` (the
/// same sensor the `/api/v1/streams/replicas` management route serves), scanning every live node and
/// returning the owner-authoritative view (`servedByOwner()`), because the HTTP sensor is
/// `taskGroup(STREAMING)`-routed to a single delegate and cannot yield a per-node owner view (#490).
abstract class AbstractMultiPartitionStream {
    static final int NODES = 5;
    static final int INSTANCES = 5;
    static final int PARTITIONS = 4;

    static final String STREAM_SLICE = TestArtifacts.STREAM_MULTIPART_SLICE;
    static final String STREAM_NAME = "multipart-events";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    static final Duration PLACEMENT_TIMEOUT = Duration.ofSeconds(120);
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(90);
    static final Duration FAILOVER_TIMEOUT = Duration.ofSeconds(180);
    static final long POLL_GAP_NANOS = Duration.ofMillis(20).toNanos();

    private static final Pattern EVENT_OBJECT = Pattern.compile("\\{[^{}]*\"offset\"[^{}]*}");
    private static final Pattern OFFSET_FIELD = Pattern.compile("\"offset\"\\s*:\\s*(\\d+)");
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("\"payload\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");

    EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    /// One stream event as observed by a consumer over HTTP: physical `offset` within its partition
    /// plus the monotonic `seq` the test embedded in the payload at publish time.
    record Event(long offset, long seq) {}

    // --- variant hooks ------------------------------------------------------

    abstract int basePort();

    abstract int baseMgmtPort();

    abstract int baseAppHttpPort();

    abstract String nodePrefix();

    abstract String blueprintId();

    // --- lifecycle ----------------------------------------------------------

    @BeforeAll
    void setUp() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, basePort(), baseMgmtPort(), baseAppHttpPort(), nodePrefix(), Option.some(configProvider));
        startAndAwaitReady();
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
            httpDelete(leaderPort, "/api/v1/blueprints/" + blueprintId());
            cluster.stop().await();
        }
    }

    private void startAndAwaitReady() {
        cluster.start().await().onFailure(AbstractMultiPartitionStream::failStart);

        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allNodesHealthy);
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> allNodesAreMembers(NODES));

        deployStreamSlice();

        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).failFast(this::failIfSliceFailed).until(this::appHttpReady);
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).failFast(this::failIfSliceFailed).until(this::publishReady);
    }

    private void deployStreamSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(blueprintId(), STREAM_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = postBlueprintWithRetry(leaderPort, blueprint);

        assertThat(response)
            .describedAs("multi-partition (partitions=4, RF=2) stream-slice deployment")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var body = httpPost(ports.getFirst(), "/api/stream-mp/read", "{\"partition\":0,\"fromOffset\":0,\"maxEvents\":1}");

        return !body.contains("\"error\"") && body.contains("events");
    }

    private boolean publishReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var response = httpPost(ports.getFirst(), "/api/stream-mp/publish", "{\"payload\":\"__warmup__\"}");

        return !response.contains("\"error\"") && response.contains("published");
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(s -> s.artifact().equals(STREAM_SLICE) && s.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("multi-partition stream slice deployment FAILED: " + STREAM_SLICE);
        }
    }

    // --- replica-set view (in-JVM, owner-authoritative) ---------------------

    /// The owner-authoritative replica-set view for `(STREAM_NAME, partition)`: the registry is
    /// authoritative only on the partition's HRW owner (`servedByOwner()` true), so scan every live
    /// node's in-JVM `replicaSnapshot` and return the owner's.
    Option<ReplicaSetView> ownerView(int partition) {
        for (var node : cluster.allNodes()) {
            var view = node.streamReadRouter().replicaSnapshot(STREAM_NAME, partition);

            if (view.servedByOwner()) {
                return Option.some(view);
            }
        }

        return Option.none();
    }

    String ownerId(int partition) {
        return ownerView(partition).flatMap(ReplicaSetView::ownerNodeId).or("");
    }

    private boolean partitionPlaced(int partition) {
        return ownerView(partition).map(view -> view.replicas().size() >= 2
                                                 && view.replicas().stream().anyMatch(r -> !r.hrwOwner()))
                                   .or(false);
    }

    boolean allPartitionsPlaced() {
        for (int partition = 0; partition < PARTITIONS; partition++) {
            if (!partitionPlaced(partition)) {
                return false;
            }
        }

        return true;
    }

    // --- publishing + consuming --------------------------------------------

    /// Publish `seq` (payload = its decimal string) through `port` and return whether it was ACKED
    /// (min-sync-2 replica ack, HTTP success). An unacked publish returns false — the caller decides
    /// whether that is a failure (#429 static publish) or a tolerated in-flight loss (#430 under kill).
    boolean publish(int port, long seq) {
        return publish(port, seq, Duration.ofSeconds(15));
    }

    /// Publish variant with an explicit client timeout. #430 uses a SHORT one: after the kill, a
    /// min-sync-2 publish to a partition that momentarily lost a replica-set member cannot be acked
    /// until RF is restored, so the owner-side await never resolves; a short client timeout turns that
    /// into a fast unacked failure instead of stalling the serial publisher for the full default budget.
    boolean publish(int port, long seq, Duration timeout) {
        var body = "{\"payload\":\"" + seq + "\"}";
        var response = httpPost(port, "/api/stream-mp/publish", body, timeout);

        return !response.contains("\"error\"") && response.contains("published");
    }

    List<Event> readPartition(int port, int partition, long fromOffset, int maxEvents) {
        var body = "{\"partition\":" + partition + ",\"fromOffset\":" + fromOffset + ",\"maxEvents\":" + maxEvents + "}";

        return parseEvents(httpPost(port, "/api/stream-mp/read", body));
    }

    /// Read a partition fully from offset 0 to its tail (bounded loop; stops on the first empty page).
    List<Event> drainPartition(int port, int partition) {
        var all = new ArrayList<Event>();
        var offset = 0L;

        while (true) {
            var events = readPartition(port, partition, offset, 200);

            if (events.isEmpty()) {
                return List.copyOf(all);
            }

            all.addAll(events);
            offset = events.getLast().offset() + 1;
        }
    }

    /// Drain every partition (0..PARTITIONS-1) fully, index i = partition i's events in offset order.
    List<List<Event>> drainAllPartitions(int port) {
        var byPartition = new ArrayList<List<Event>>();

        for (int partition = 0; partition < PARTITIONS; partition++) {
            byPartition.add(drainPartition(port, partition));
        }

        return byPartition;
    }

    /// Per partition, the test's events read back with contiguous offsets (each exactly one past the
    /// previous — no duplicate and no skipped offset within the run) and STRICTLY INCREASING embedded
    /// sequence numbers (the publish order is preserved for that partition). Offsets are validated as a
    /// contiguous run rather than from zero because a setup warm-up publish may occupy the partition's
    /// offset 0.
    void assertPerPartitionOrdered(List<Event> events, int partition) {
        assertThat(events)
            .describedAs("partition %d must be populated", partition)
            .isNotEmpty();

        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).offset())
                .describedAs("partition %d offset at index %d is contiguous (prev + 1) — no dup/gap", partition, i)
                .isEqualTo(events.get(i - 1).offset() + 1);
            assertThat(events.get(i).seq())
                .describedAs("partition %d seq strictly increases with offset (per-partition publish order)", partition)
                .isGreaterThan(events.get(i - 1).seq());
        }
    }

    private static List<Event> parseEvents(String body) {
        var events = new ArrayList<Event>();
        Matcher objects = EVENT_OBJECT.matcher(body);

        while (objects.find()) {
            var object = objects.group();
            Matcher offset = OFFSET_FIELD.matcher(object);
            Matcher payload = PAYLOAD_FIELD.matcher(object);

            if (offset.find() && payload.find()) {
                addEvent(events, offset.group(1), payload.group(1));
            }
        }

        return List.copyOf(events);
    }

    /// Keep only payloads that are a decimal `seq` (skips the `__warmup__` marker and any non-numeric
    /// payload) so a warm-up publish never pollutes the seq accounting.
    private static void addEvent(List<Event> events, String offset, String payload) {
        if (payload.chars().allMatch(Character::isDigit) && !payload.isEmpty()) {
            events.add(new Event(Long.parseLong(offset), Long.parseLong(payload)));
        }
    }

    // --- port / node helpers ------------------------------------------------

    int appPort() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    /// The app-http port of a specific node: `baseAppHttpPort + slot`, where the slot is recovered from
    /// the node's cluster port (`clusterPort - basePort`). Lets #430 publish through a node that is NOT
    /// the kill target.
    int appPortFor(String nodeId) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> node.id().equals(nodeId))
                      .map(node -> baseAppHttpPort() + (node.port() - basePort()))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No app-http port for node " + nodeId));
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    long deadline(Duration budget) {
        return System.nanoTime() + budget.toNanos();
    }

    // --- health / membership ------------------------------------------------

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private boolean allNodesAreMembers(int expected) {
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderPort + "/api/v1/health"))
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
            lastResponse = httpPostToml(port, "/api/v1/blueprints", body);

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

    String httpPost(int port, String path, String body) {
        return httpPost(port, path, body, Duration.ofSeconds(15));
    }

    String httpPost(int port, String path, String body, Duration timeout) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(timeout)
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

    // --- failure sinks ------------------------------------------------------

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }
}
