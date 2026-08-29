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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #386 — the COMPOSED durable pub/sub path, end to end on a real multi-node cluster.
///
/// Every component of this path is unit-tested in isolation (`DurableTopicPublisherTest`,
/// `DlqStreamSinkTest`, `StreamConsumerManagerTest$TopicGroupDispatch`,
/// `StreamConsumerRuntimeTest$DeadLetterAppendContract`) and NOTHING exercised them joined up until
/// this test: `feature-catalog.md` row 24 grades the durable tier "single-node verified" and names
/// this exact gap under **Pending**. The path is: publish appends a KSUID-stamped envelope to the
/// replicated `topic:<address>` stream and resolves at the min-sync floor, dispatch rides
/// StreamConsumerManager placement serially per (group x partition), the handler's promise IS the
/// ack, and a handler that keeps failing exhausts the bounded retries into a group-attributed
/// dead-letter stream.
///
/// **The durability guard comes first, because everything else depends on it.** A durable topic is
/// backed by a real `topic:<address>` stream; an EPHEMERAL topic has no backing stream at all — it
/// fans out through SliceInvoker and persists nothing. The fixture's topic sections use UNDERSCORE
/// keys (`topic_name`, `min_sync_replicas`) while `[streams.X]` sections use DASHES, so a single
/// mistyped key would parse as absent, silently default `durability` to "ephemeral", and make every
/// assertion below vacuously green against a tier that was never engaged.
/// [DurableTier#durableTopic_isBackedByARealStream] therefore proves the tier from the RUNTIME's
/// stream listing rather than trusting the config file.
///
/// **Non-vacuity of the delivery count.** `order-events` declares `partitions = 1` and the blueprint
/// deploys the slice to EVERY node. Exactly one node owns that partition, so a correctly gated
/// consumer records each event once CLUSTER-WIDE, while an ungated one records it once per node and
/// the total is a multiple of the published count. Asserting the exact total is simultaneously a
/// delivery proof and a duplication proof. The subscriber methods are deliberately ABSENT from the
/// fixture's `routes.toml`, so nothing but the runtime's dispatch path can invoke them.
///
/// **Non-vacuity of the group-attribution arm.** Two subscriber methods bind to the SAME
/// `poison-events` topic, making them two consumer groups over one event sequence. One can never
/// ack. If attribution is real the failing group dead-letters while the healthy group processes the
/// identical events untouched.
///
/// **HONEST SCOPE — what a green run here does NOT prove.** Stated up front so the tick is not
/// over-read:
///
///   - **Not exactly-once.** This asserts at-least-once delivery plus the dead-letter boundary.
///     Duplicate exposure on redelivery (spec §7) stands until the D4 idempotency guard is wired;
///     the delivery-count arm runs without induced failures, where at-least-once and exactly-once
///     are indistinguishable.
///   - **Redrive is not exercised.** Spec §9's management triad (DLQ list/inspect/redrive) does not
///     exist yet, so there is nothing to drive. This test asserts events ARRIVE in the DLQ, never
///     that they can be replayed out of it.
///   - **Zombie / concurrent cross-instance attempts (§6) are not reproduced.** A timed-out attempt
///     still executing while its retry runs elsewhere is not constructible in this harness.
///   - **No idempotency-key assertion.** `MessageContext.messageId` survival across the dead-letter
///     hop is unit-tested; this suite does not observe message ids.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DurableTopicDeliveryForgeTest {
    private static final int BASE_PORT = 19000;
    private static final int BASE_MGMT_PORT = 19100;
    private static final int BASE_APP_HTTP_PORT = 19200;
    private static final int NODES = 5;
    private static final int INSTANCES = 5;

    private static final int ORDER_COUNT = 20;
    private static final int POISON_COUNT = 2;

    /// durable-pubsub-spec §7: bounded retries before the dead-letter hop. The fixture's failing
    /// handler records every invocation, so this is observed rather than assumed.
    private static final int EXPECTED_ATTEMPTS_PER_EVENT = 5;

    private static final String ORDER_TOPIC = "order-events";
    private static final String POISON_TOPIC = "poison-events";

    /// The fixture's coordinate is defined here rather than in `TestArtifacts` deliberately: stream B's
    /// claim for this work covers NEW files only, and `TestArtifacts` is an existing forge file. This
    /// test is the sole consumer of the coordinate, so the locality costs nothing.
    private static final String DURABLE_TOPIC_SLICE =
            "org.pragmatica.aether.test:test-durable-topic-durable-topic-slice:1.0.0";
    private static final String BLUEPRINT_ID = "forge.test:durable-topic:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final Pattern COUNT_FIELD = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");
    private static final Pattern FAILING_ATTEMPTS = Pattern.compile("\"failingAttempts\"\\s*:\\s*(\\d+)");
    private static final Pattern HEALTHY_COUNT = Pattern.compile("\"healthyCount\"\\s*:\\s*(\\d+)");
    private static final Pattern SEQUENCE_FIELD = Pattern.compile("\"sequence\"\\s*:\\s*(\\d+)");
    private static final Pattern STREAM_NAME = Pattern.compile("\"name\"\\s*:\\s*\"(topic:[^\"]+)\"");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp(@TempDir Path baseDir) {
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();

        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "dtd", Option.some(configProvider));
        // The durable tier writes envelopes through per-partition WALs. Without an on-disk data dir the
        // backing streams are memory-only and "durable" would be measuring nothing.
        cluster.withDataBaseDir(baseDir);
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

        deployDurableTopicSlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::appHttpReady);

        // A publish can land before the backing stream's owner has materialized its ring, so gate on a
        // real publish resolving before any assertion runs.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::publishReady);

        // Durable subscriptions attach on the manager's ownership tick, independently of publish
        // readiness. Gate on BOTH topics' backing streams existing, so a delivery assertion measures
        // delivery rather than attach timing.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(() -> topicStreamFor(ORDER_TOPIC).isPresent() && topicStreamFor(POISON_TOPIC).isPresent());
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

    @Nested
    class DurableTier {
        /// The guard every other assertion rests on. A durable topic is backed by a real
        /// `topic:<address>` stream; an ephemeral one has none, because it fans out through
        /// SliceInvoker and persists nothing. Reading the backing stream out of the runtime's own
        /// listing proves the durable tier engaged — a mistyped key in the fixture's topic section
        /// would silently downgrade it to ephemeral and this assertion is what catches that.
        @Test
        void durableTopic_isBackedByARealStream() {
            assertThat(topicStreamFor(ORDER_TOPIC).isPresent())
                    .describedAs("a durable topic must have a topic:<address> backing stream;"
                                 + " an ephemeral one has none, so its absence means the tier never engaged")
                    .isTrue();
            assertThat(topicStreamFor(POISON_TOPIC).isPresent()).isTrue();
        }
    }

    @Nested
    class Delivery {
        /// Delivery AND duplication in one assertion — see the class doc: one partition, slice on every
        /// node, so an ungated consumer would return a multiple of ORDER_COUNT.
        @Test
        void everyPublishedEvent_isDeliveredExactlyOnceClusterWide() {
            var baseline = totalOrdersDelivered();

            for (var i = 0; i < ORDER_COUNT; i++) {
                publishOrder("order-" + i, i);
            }

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(DurableTopicDeliveryForgeTest.this::failIfSliceFailed)
                   .untilAsserted(() -> assertThat(totalOrdersDelivered() - baseline)
                           .describedAs("each event delivered exactly once cluster-wide — a multiple of"
                                        + " %d would mean ungated per-node delivery", ORDER_COUNT)
                           .isEqualTo(ORDER_COUNT));
        }

        /// The only ordering guarantee §5 makes: serial per (group x partition). The topic has ONE
        /// partition, so dispatch order is offset order and the ascending sequences the fixture
        /// published must come back ascending. Asserting more than this would assert something the
        /// design does not promise.
        @Test
        void eventsArriveInPublishedOrder_withinTheSinglePartition() {
            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> !deliveredSequences().isEmpty());

            var sequences = deliveredSequences();

            assertThat(sequences).describedAs("serial per-(group x partition) dispatch over one partition"
                                              + " means arrival order IS offset order")
                                 .isSorted();
        }
    }

    @Nested
    class DeadLetterPath {
        /// The DLQ arm, with the assertion that matters most LAST. A handler that can never ack must be
        /// retried a bounded number of times and the event must land in the topic's `.dlq` stream — but
        /// a dead-letter implementation that swallows the message and WEDGES the partition also
        /// satisfies "the entry landed". The partition must unblock, which is what the final assertion
        /// checks: the healthy group keeps making progress over the same topic afterwards.
        @Test
        void poisonEvent_isRetriedABoundedNumberOfTimes_thenDeadLettered() {
            for (var i = 0; i < POISON_COUNT; i++) {
                publishPoison("poison-" + i);
            }

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(DurableTopicDeliveryForgeTest.this::failIfSliceFailed)
                   .untilAsserted(() -> assertThat(failingAttempts())
                           .describedAs("the never-acking handler must be retried a BOUNDED number of"
                                        + " times (%d per event), not forever and not once",
                                        EXPECTED_ATTEMPTS_PER_EVENT)
                           .isEqualTo(POISON_COUNT * EXPECTED_ATTEMPTS_PER_EVENT));

            assertThat(dlqStreamFor(POISON_TOPIC).isPresent())
                    .describedAs("exhausted retries must land in the topic's group-attributed .dlq stream")
                    .isTrue();
        }

        /// Group attribution: the failing group's exhaustion must not touch the healthy group's
        /// progress over the SAME events. This is what "no cross-group duplication by construction,
        /// not by dedup" (§9) means operationally, and it is also the partition-unblock proof — a DLQ
        /// that stalled the shared partition would freeze this count too.
        @Test
        void healthyGroup_processesTheSameEvents_unaffectedByTheFailingGroup() {
            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(DurableTopicDeliveryForgeTest.this::failIfSliceFailed)
                   .untilAsserted(() -> assertThat(healthyCount())
                           .describedAs("the healthy group shares the topic with a group that can never"
                                        + " ack; separate cursors and retry budgets mean it must still"
                                        + " process every event")
                           .isGreaterThanOrEqualTo(POISON_COUNT));
        }
    }

    // --- fixture driving -----------------------------------------------------

    private void publishOrder(String orderId, int sequence) {
        var body = "{\"orderId\":\"%s\",\"sequence\":%d}".formatted(orderId, sequence);
        var response = httpPost(appPort(), "/api/durable-topic/publish-order", body);

        assertThat(response).describedAs("durable publish must resolve at the min-sync floor")
                            .doesNotContain("\"error\"");
    }

    private void publishPoison(String payload) {
        var response = httpPost(appPort(), "/api/durable-topic/publish-poison", "{\"payload\":\"" + payload + "\"}");

        assertThat(response).doesNotContain("\"error\"");
    }

    /// Summed across every node, which is what makes the count a cluster-wide claim rather than a
    /// per-node one — the ungated-delivery failure mode only shows up in the total.
    private int totalOrdersDelivered() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .mapToInt(port -> firstInt(COUNT_FIELD, httpPost(port, "/api/durable-topic/order-status", "{}")))
                      .sum();
    }

    private List<Integer> deliveredSequences() {
        var body = httpPost(appPort(), "/api/durable-topic/order-status", "{}");
        var matcher = SEQUENCE_FIELD.matcher(body);

        return matcher.results()
                      .map(result -> Integer.parseInt(result.group(1)))
                      .toList();
    }

    private int failingAttempts() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .mapToInt(port -> firstInt(FAILING_ATTEMPTS,
                                                 httpPost(port, "/api/durable-topic/poison-status", "{}")))
                      .sum();
    }

    private int healthyCount() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .mapToInt(port -> firstInt(HEALTHY_COUNT,
                                                 httpPost(port, "/api/durable-topic/poison-status", "{}")))
                      .sum();
    }

    // --- stream discovery ----------------------------------------------------

    /// The topic's backing stream, discovered from the runtime rather than reconstructed. The address
    /// is `topic:<namespace>:<name>:<version>` with the namespace derived from the blueprint's Maven
    /// coordinates (`DurableTopicNames`), so matching on the bare topic name inside a `topic:`-prefixed
    /// stream avoids hard-coding a derivation this test does not own.
    private Option<String> topicStreamFor(String topicName) {
        return listedTopicStreams().stream()
                                   .filter(name -> name.contains(topicName) && !name.endsWith(".dlq"))
                                   .findFirst()
                                   .map(Option::some)
                                   .orElseGet(Option::none);
    }

    private Option<String> dlqStreamFor(String topicName) {
        return listedTopicStreams().stream()
                                   .filter(name -> name.contains(topicName) && name.endsWith(".dlq"))
                                   .findFirst()
                                   .map(Option::some)
                                   .orElseGet(Option::none);
    }

    private List<String> listedTopicStreams() {
        var body = httpGet(cluster.getLeaderManagementPort().or(anyMgmtPort()), "/api/v1/streams");

        return STREAM_NAME.matcher(body)
                          .results()
                          .map(result -> result.group(1))
                          .toList();
    }

    // --- cluster plumbing ----------------------------------------------------

    private void deployDurableTopicSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, DURABLE_TOPIC_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = httpPostToml(leaderPort, "/api/v1/blueprints", blueprint);

        assertThat(response).describedAs("durable-topic slice deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var body = httpPost(ports.getFirst(), "/api/durable-topic/order-status", "{}");

        return !body.contains("\"error\"") && body.contains("count");
    }

    private boolean publishReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var response = httpPost(ports.getFirst(),
                                "/api/durable-topic/publish-order",
                                "{\"orderId\":\"__warmup__\",\"sequence\":0}");

        return !response.contains("\"error\"") && response.contains("published");
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(status -> status.artifact().equals(DURABLE_TOPIC_SLICE)
                                                && status.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("Durable-topic slice deployment FAILED: " + DURABLE_TOPIC_SLICE);
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

    private static int firstInt(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);

        return matcher.find()
               ? Integer.parseInt(matcher.group(1))
               : 0;
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
