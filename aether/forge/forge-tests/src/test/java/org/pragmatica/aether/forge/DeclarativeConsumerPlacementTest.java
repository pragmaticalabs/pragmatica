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
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #535 — a declarative consumer delivers under a DEFAULT placement, where the partition owner does
/// not host the slice.
///
/// [DeclarativeStreamConsumerTest] proved the #488 mechanism with the slice deployed to every node,
/// which makes `partition owner INTERSECT slice-bearing` non-empty by construction. That is precisely
/// the case the defect could not reach: on a real 5-node cluster at default replication the slice ran
/// on 3 of 5 nodes, the partition owner was not one of them, and three successfully-published events
/// were delivered to NOBODY while every node truthfully reported `attachedSubscriptions: 0`.
///
/// **Deterministic by counting, not by luck.** `streams.spread-events` declares 5 partitions and this
/// blueprint deploys `instances = 1`, so the single slice-bearing node can own AT MOST one of them.
/// At least four partitions are therefore guaranteed to have an owner that cannot run the consumer —
/// no placement control, no owner pinning, and no arrangement in which the test silently degenerates
/// into the already-covered co-located case. Compare a 1-partition stream at `instances = 1`, which
/// would exercise the interesting case only 4 times in 5.
///
/// **Non-vacuity.** Two independent arms. Structurally, `onSpreadEvent` is absent from the fixture's
/// `routes.toml`, so nothing but the framework's delivery path can invoke it. Behaviourally,
/// [PlacementShape#placement_leavesMostPartitionOwnersWithoutTheSlice] asserts the configuration under
/// test really is the uncovered one — the endpoint's own diagnostic must say reads are being forwarded
/// — so a run that accidentally co-located everything fails loudly instead of passing for free. The
/// matching unit-level arm lives in `StreamConsumerRuntimeTest.RoutedReads`, where the same
/// subscription against the LOCAL reader is shown to deliver nothing.
///
/// **Honest scope.** Forge is a single-JVM Ember cluster: this proves the assignment rule, the
/// forwarded-read path, and the observability surface. It does NOT prove real-network read forwarding
/// or ownership failover across machines — that needs the live cluster harness.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeclarativeConsumerPlacementTest {
    private static final int BASE_PORT = 18500;
    private static final int BASE_MGMT_PORT = 18600;
    private static final int BASE_APP_HTTP_PORT = 18700;
    private static final int NODES = 5;

    /// The whole point: ONE instance against a FIVE-partition stream. The single host can own at most
    /// one partition, so at least four must be consumed by reading through their owners.
    private static final int INSTANCES = 1;
    private static final int SPREAD_PARTITIONS = 5;

    /// Attachments expected cluster-wide once settled. The fixture slice declares THREE consumers —
    /// `consumer-events` (1 partition), `order-events` (1) and `spread-events` (5) — and with a single
    /// instance the sole candidate is assigned EVERY partition of all three, so the total is 7, not 5.
    /// Gating on 5 was the first run's mistake: it never converged, because the true value settles at 7
    /// and the assignment was in fact correct the whole time.
    private static final int EXPECTED_ATTACHMENTS = 7;
    private static final int EVENT_COUNT = 25;

    private static final String SPREAD_EVENTS_STREAM = "spread-events";

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final String CONSUMER_SLICE = TestArtifacts.STREAM_CONSUMER_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:declarative-consumer-placement:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Pattern COUNT_FIELD = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");
    private static final Pattern ATTACHED_FIELD = Pattern.compile("\"attachedSubscriptions\"\\s*:\\s*(\\d+)");
    private static final Pattern OWNER_NODE_FIELD = Pattern.compile("\"ownerNode\"");

    /// One `partitionAssignments` row. Field order follows the record's component order, so consumer
    /// and owner can be compared per partition without a JSON parser.
    private static final Pattern ASSIGNMENT_ROW =
        Pattern.compile("\\{\"partition\":\\d+,\"consumerNode\":\"([^\"]+)\",\"ownerNode\":\"([^\"]+)\"\\}");

    /// Matches `unassignedPartitions` carrying at least one partition. The serializer omits empty
    /// collections, so a healthy response has no such field at all and this must never match.
    private static final Pattern UNASSIGNED_NONEMPTY = Pattern.compile("\"unassignedPartitions\":\\[\\d");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();

        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "dcp", Option.some(configProvider));
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

        deployConsumerSlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::appHttpReady);

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::publishReady);

        // Gate on the consumer holding every partition of all three declared streams. With one instance
        // the sole candidate is assigned all of them, so anything less means the assignment has not
        // settled and a delivery assertion would be measuring attach timing.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(() -> totalAttachedSubscriptions() == EXPECTED_ATTACHMENTS);
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());

            httpDelete(leaderPort, "/api/v1/blueprints/" + BLUEPRINT_ID);
            cluster.stop()
                   .await();
        }
    }

    /// The configuration under test really is the one that used to deliver nothing. These assertions
    /// are what stop a co-located run from passing for free.
    @Nested
    class PlacementShape {

        @Test
        void placement_leavesMostPartitionOwnersWithoutTheSlice() {
            var hosts = cluster.slicesStatus()
                               .stream()
                               .filter(status -> status.artifact().equals(CONSUMER_SLICE))
                               .flatMap(status -> status.instances().stream())
                               .toList();

            assertThat(hosts).describedAs("the pigeonhole depends on exactly one host against five partitions")
                             .hasSize(INSTANCES);
            assertThat(forwardedPartitionCount())
                    .describedAs("one host cannot own five partitions, so at least four MUST be read through their owners — "
                                 + "this is the case #488 could not express and the live cluster failed")
                    .isGreaterThanOrEqualTo(SPREAD_PARTITIONS - INSTANCES);
        }

        @Test
        void declarativeConsumersEndpoint_namesConsumerAndOwnerForEveryPartition() {
            var fragment = spreadFragment();

            assertThat(OWNER_NODE_FIELD.matcher(fragment).results().count())
                    .describedAs("an operator must be able to answer 'who consumes partition 3' from any node")
                    .isEqualTo(SPREAD_PARTITIONS);
        }

        /// The endpoint must not claim a gap that does not exist, and must not use its FAULT channel
        /// for routine forwarding — a non-empty `diagnostic` has to keep meaning "act on this".
        /// Asserted semantically rather than on a literal `"unassignedPartitions":[]`, because the
        /// serializer OMITS empty collections: the healthy shape is the field being absent.
        @Test
        void declarativeConsumersEndpoint_reportsNoUnassignedPartitions() {
            var fragment = spreadFragment();

            assertThat(UNASSIGNED_NONEMPTY.matcher(fragment).find())
                    .describedAs("the slice IS active somewhere, so no partition may be reported as consumed by nobody")
                    .isFalse();
            assertThat(fragment).doesNotContain("NOT being consumed by anyone")
                                .doesNotContain("not being consumed YET")
                                .describedAs("forwarding is normal operation and must not occupy the fault channel")
                                .doesNotContain("forwarded to the owner");
        }
    }

    @Nested
    class Delivery {

        /// The headline #535 assertion: a default deployment delivers. Against the pre-fix runtime this
        /// stays at 0 forever for every partition whose owner lacks the slice — four of five here.
        @Test
        void declaredConsumer_receivesEveryEvent_whenOwnersDoNotHostTheSlice() {
            var baseline = settledSpreadReceived();

            publishSpreadBatch(EVENT_COUNT);

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .untilAsserted(() -> assertThat(spreadReceived() - baseline)
                           .describedAs("every published event must arrive even though four of five owners cannot run the consumer")
                           .isEqualTo(EVENT_COUNT));
        }

        /// Reading through an owner must not turn one event into several. The hold past several
        /// reconcile ticks is what catches a late second assignee, which a single sample would miss.
        @Test
        void declaredConsumer_deliversEachEventExactlyOnceClusterWide() {
            var baseline = settledSpreadReceived();

            publishSpreadBatch(EVENT_COUNT);

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .untilAsserted(() -> assertThat(spreadReceived() - baseline).isEqualTo(EVENT_COUNT));

            sleep(Duration.ofSeconds(12));

            assertThat(spreadReceived() - baseline)
                    .describedAs("exactly one node is assigned per partition, and it stays the only one")
                    .isEqualTo(EVENT_COUNT);
        }
    }

    // --- publish / receive ---------------------------------------------------

    private void publishSpreadBatch(int count) {
        for (var i = 0; i < count; i++) {
            var response = httpPost(appPort(), "/api/stream-consumer/publish-spread", "{\"payload\":\"spread-" + i + "\"}");

            assertThat(response).describedAs("publish must succeed — it write-forwards to each partition owner")
                                .contains("published");
        }
    }

    /// Deliveries recorded by the slice. With a SINGLE instance every node's app-HTTP route proxies to
    /// the same slice object, so every port reports the same queue — summing would multiply the true
    /// count by the number of routable ports (the first run reported 125 for 25 published). The max IS
    /// the count. The #488 suite sums instead, correctly, because there every node has its own instance.
    private int spreadReceived() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .map(port -> httpPost(port, "/api/stream-consumer/received-spread", "{}"))
                      .mapToInt(body -> firstInt(COUNT_FIELD, body))
                      .max()
                      .orElse(0);
    }

    /// The delivered count once it has stopped moving — two consecutive samples equal. A baseline
    /// captured while a previous test's delivery is still in flight makes the next exact-count
    /// assertion off by one, which reads exactly like a duplicate-delivery bug.
    private int settledSpreadReceived() {
        var lastSample = new AtomicInteger(-1);

        await().atMost(DELIVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> isRepeatSample(lastSample, spreadReceived()));

        return lastSample.get();
    }

    private static boolean isRepeatSample(AtomicInteger lastSample, int current) {
        return lastSample.getAndSet(current) == current;
    }

    private int totalAttachedSubscriptions() {
        return mgmtPorts().stream()
                          .map(port -> httpGet(port, "/api/v1/streams/declarative-consumers"))
                          .mapToInt(body -> firstInt(ATTACHED_FIELD, body))
                          .sum();
    }

    /// Partitions of spread-events whose assigned consumer is NOT the owner — i.e. whose reads are
    /// forwarded. Counted off the endpoint's own assignment map rather than a log line, so the
    /// pigeonhole is asserted structurally.
    private long forwardedPartitionCount() {
        return ASSIGNMENT_ROW.matcher(spreadFragment())
                             .results()
                             .filter(match -> !match.group(1).equals(match.group(2)))
                             .count();
    }

    /// The spread-events consumer as the node HOSTING the slice sees it. Every node computes the same
    /// assignment, but only the host can report `sliceDeployedLocally` and the forwarding diagnostic.
    private String spreadFragment() {
        return mgmtPorts().stream()
                          .map(port -> consumerFragment(httpGet(port, "/api/v1/streams/declarative-consumers"),
                                                        SPREAD_EVENTS_STREAM))
                          .filter(fragment -> fragment.contains("\"sliceDeployedLocally\":true"))
                          .findFirst()
                          .orElseThrow(() -> new AssertionError("No node reports hosting the declarative consumer slice"));
    }

    /// The slice of the declarative-consumers JSON describing one stream. Substring-scoped rather than
    /// parsed: the response carries one object per declared consumer and a whole-body `contains` would
    /// happily match a field belonging to another stream.
    private static String consumerFragment(String body, String stream) {
        return Arrays.stream(body.split("\\{\"stream\":\""))
                     .filter(fragment -> fragment.startsWith(stream + "\""))
                     .findFirst()
                     .orElse("");
    }

    private static int firstInt(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);

        return matcher.find()
               ? Integer.parseInt(matcher.group(1))
               : 0;
    }

    // --- deployment + readiness ---------------------------------------------

    private void deployConsumerSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, CONSUMER_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = httpPostToml(leaderPort, "/api/v1/blueprints", blueprint);

        assertThat(response).describedAs("placement-restricted consumer slice deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var body = httpPost(ports.getFirst(), "/api/stream-consumer/received-spread", "{}");

        return !body.contains("\"error\"") && body.contains("count");
    }

    private boolean publishReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var response = httpPost(ports.getFirst(), "/api/stream-consumer/publish-spread", "{\"payload\":\"__warmup__\"}");

        return !response.contains("\"error\"") && response.contains("published");
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(status -> status.artifact().equals(CONSUMER_SLICE) && status.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("Declarative-consumer slice deployment FAILED: " + CONSUMER_SLICE);
        }
    }

    private int appPort() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    private List<Integer> mgmtPorts() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .map(node -> node.mgmtPort())
                      .toList();
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private boolean allNodesHealthy() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(response -> response.statusCode() == 200 && response.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private static void sleep(Duration duration) {
        java.util.concurrent.locks.LockSupport.parkNanos(duration.toNanos());
    }

    // --- HTTP ----------------------------------------------------------------

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

    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
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
