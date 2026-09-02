// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
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
/// **The durability guard comes first, because everything else depends on it.** The fixture's topic
/// sections use UNDERSCORE keys (`topic_name`, `min_sync_replicas`) while `[streams.X]` sections use
/// DASHES, so a single mistyped key parses as absent, silently defaults `durability` to "ephemeral",
/// and makes every assertion below vacuously green against a tier that was never engaged (#738).
/// [DurableTier#failingHandlerIsRETRIED_whichEphemeralDispatchNeverDoes] proves the tier from
/// BEHAVIOUR the two tiers cannot share: ephemeral delivery invokes a failing handler exactly once and
/// never retries, so an observed retry cannot be ephemeral dispatch.
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
/// **Everything here is observed through the FIXTURE's own HTTP surface, never the management API.**
/// That is not a stylistic choice. The first run of this suite died in `@BeforeAll` against a guard
/// that read `GET /api/v1/streams` looking for the topic's `topic:<address>` backing stream — which
/// that endpoint can never show, because it lists blueprint-declared `[streams.X]` resources keyed by
/// `ResourceAddress`, and `ResourceAddress` parses exactly three colon-separated parts while a topic
/// stream name has four. The guard was unsatisfiable by construction; every arm below it never ran.
///
/// **HONEST SCOPE — what a green run here does NOT prove.** Stated up front so the tick is not
/// over-read:
///
///   - **Not exactly-once.** This asserts at-least-once delivery plus the dead-letter boundary.
///     Duplicate exposure on redelivery (spec §7) stands until the D4 idempotency guard is wired;
///     the delivery-count arm runs without induced failures, where at-least-once and exactly-once
///     are indistinguishable.
///   - **The `.dlq` stream's CONTENTS are not asserted** — only that the retry budget is bounded and
///     then stops, which is the dead-letter boundary as seen from outside. The envelope's shape
///     (messageId, failing group, attempt count) is unit-covered by `DlqStreamSinkTest`.
///   - **Redrive is not exercised.** Spec §9's management triad (DLQ list/inspect/redrive) does not
///     exist yet, so there is nothing to drive.
///   - **Zombie / concurrent cross-instance attempts (§6) are not reproduced.** A timed-out attempt
///     still executing while its retry runs elsewhere is not constructible in this harness.
///   - **No owner-loss arm** — the SIGKILL failover case is tracked as #739; without it this suite
///     does not prove survival of a partition owner's death.
@Tag("Heavy")
@Disabled("never observed fully green; enable on first green run — run 2 executed all five arms and"
          + " proved the durable tier is dispatching (20 retries where ephemeral gives 1), but three"
          + " arms failed on test-side baseline carryover and the group-isolation arm was vacuous."
          + " Enabling it IS the acceptance criterion.")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
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

    /// Declared alongside every other fixture coordinate in [TestArtifacts], where its rationale lives.
    private static final String DURABLE_TOPIC_SLICE = TestArtifacts.DURABLE_TOPIC_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:durable-topic:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final Pattern COUNT_FIELD = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");
    private static final Pattern FAILING_ATTEMPTS = Pattern.compile("\"failingAttempts\"\\s*:\\s*(\\d+)");
    private static final Pattern HEALTHY_COUNT = Pattern.compile("\"healthyCount\"\\s*:\\s*(\\d+)");
    private static final Pattern SEQUENCE_FIELD = Pattern.compile("\"sequence\"\\s*:\\s*(\\d+)");

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
        // readiness. A never-committed consumer group starts at offset 0 — EARLIEST, permanently, per
        // the #478 ruling (StreamResourceValidator rejects any other auto-offset-reset as inert) — so
        // an event published before its group attached is QUEUED AND LATER DELIVERED, not skipped.
        //
        // That is the opposite of what this comment claimed until 2026-08-29, and the correction
        // STRENGTHENS the drain gate below rather than weakening it: if pre-attach events were
        // skipped, the warm-ups would evaporate harmlessly; because they are queued, they are
        // guaranteed to arrive later and land inside some arm's measurement window unless drained
        // first. Refuted empirically by e2e-runner (four events published across the attach boundary,
        // all four delivered including the three published before attach) and confirmed here against
        // the #478 ruling in the validator.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::poisonPublishReady);

        // DRAIN BOTH WARM-UPS BEFORE ANY ARM RUNS. The two gates above publish REAL events, and the
        // first run of this suite failed three arms because those events were still in flight when the
        // arms captured their baselines — the warm-up order landed inside arm 2's window (21 delivered
        // against an expected 20) and the warm-up poison's five retries were counted by a later arm.
        // Nothing was wrong with the runtime; the test was measuring its own setup.
        //
        // Draining rather than removing the gates: the gates exist because a publish can land before
        // the backing stream's owner has materialized its ring, and dropping them would trade a
        // measurable pollution for a flaky first publish. Waiting for the warm-ups to be fully
        // PROCESSED makes the system quiescent instead, so every baseline below starts from a settled
        // state.
        //
        // This also localises the poison-dispatch stall observed on run 2 (240s+ with zero poison
        // invocations while order-events flowed): if it recurs, THIS gate fails naming it directly,
        // instead of scattering the symptom across three arms that each blame something else.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .untilAsserted(() -> assertThat(totalOrdersDelivered())
                       .describedAs("the order-events warm-up must be delivered before any arm measures"
                                    + " delivery")
                       .isGreaterThanOrEqualTo(1));

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .untilAsserted(() -> assertThat(failingAttempts())
                       .describedAs("the poison-events warm-up must exhaust its %d-attempt budget before"
                                    + " any arm measures retries — if this times out, poison dispatch"
                                    + " stalled, which is a RUNTIME signal and not a baseline problem",
                                    EXPECTED_ATTEMPTS_PER_EVENT)
                       .isGreaterThanOrEqualTo(EXPECTED_ATTEMPTS_PER_EVENT));
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
    @Order(1)
    class DurableTier {
        /// The guard every other assertion rests on: proof that the DURABLE tier is the one dispatching,
        /// not the ephemeral default.
        ///
        /// The discriminator is the retry budget, because it is the one behaviour the two tiers cannot
        /// share. Ephemeral delivery is a *single* `invoke` that is "never retried, never persisted"
        /// (guarantees.md §5): a handler that fails is logged and the event is gone. Durable delivery
        /// retries a failing handler a bounded 5 times before dead-lettering. So a failing handler
        /// invoked MORE THAN ONCE cannot be ephemeral dispatch, and exactly 5 invocations is the durable
        /// budget observed directly.
        ///
        /// This replaced an earlier guard that looked for the topic's `topic:<address>` backing stream in
        /// `GET /api/v1/streams`. That endpoint cannot ever show it: the listing is a registry of
        /// blueprint-declared `[streams.X]` resources keyed by `ResourceAddress`, and `ResourceAddress`
        /// parses exactly three colon-separated parts (`ResourceAddress.java:73-77`) while a topic stream
        /// name has four (`topic:` + `namespace:name:version`). The guard was unsatisfiable by
        /// construction, not merely mis-parsed — and being unsatisfiable in `@BeforeAll`, it burned the
        /// full timeout and prevented every arm below from running at all.
        @Test
        void failingHandlerIsRETRIED_whichEphemeralDispatchNeverDoes() {
            var baseline = failingAttempts();

            publishPoison("tier-probe");

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(DurableTopicDeliveryForgeTest.this::failIfSliceFailed)
                   .untilAsserted(() -> assertThat(failingAttempts() - baseline)
                           .describedAs("the durable tier retries a failing handler %d times; ephemeral"
                                        + " delivery invokes it ONCE and never retries, so anything above"
                                        + " 1 proves the durable tier is dispatching",
                                        EXPECTED_ATTEMPTS_PER_EVENT)
                           .isEqualTo(EXPECTED_ATTEMPTS_PER_EVENT));
        }
    }

    @Nested
    @Order(2)
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
    @Order(3)
    class DeadLetterPath {
        /// The DLQ arm. A handler that can never ack must be retried a BOUNDED number of times and then
        /// stop — and stopping is the dead-letter boundary observed from outside: the runtime gave up on
        /// the event and moved it aside rather than retrying it forever or silently dropping it on the
        /// first failure.
        ///
        /// The `.dlq` stream itself is deliberately NOT asserted here. It is a runtime-created stream
        /// named `topic:<address>.dlq`, which the management stream listing cannot show (see
        /// [DurableTier#failingHandlerIsRETRIED_whichEphemeralDispatchNeverDoes] for why), so asserting
        /// on it from this suite would mean asserting on something unobservable. Its contents are
        /// unit-covered by `DlqStreamSinkTest.append_reEnvelopesWithGroupAttribution_preservingMessageId`;
        /// what this suite adds is that the boundary is reached on a real cluster and the partition
        /// survives it — the second half being
        /// [#healthyGroup_processesTheSameEvents_unaffectedByTheFailingGroup].
        @Test
        void poisonEvent_isRetriedABoundedNumberOfTimes_thenStops() {
            var baseline = failingAttempts();

            for (var i = 0; i < POISON_COUNT; i++) {
                publishPoison("poison-" + i);
            }

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(DurableTopicDeliveryForgeTest.this::failIfSliceFailed)
                   .untilAsserted(() -> assertThat(failingAttempts() - baseline)
                           .describedAs("the never-acking handler must be retried a BOUNDED number of"
                                        + " times (%d per event), not forever and not once",
                                        EXPECTED_ATTEMPTS_PER_EVENT)
                           .isEqualTo(POISON_COUNT * EXPECTED_ATTEMPTS_PER_EVENT));

            // The boundary HOLDS: having given up, the runtime must not resume retrying. A count that
            // keeps climbing here would mean the event was never dead-lettered, only endlessly retried.
            var settled = failingAttempts();

            sleep(Duration.ofSeconds(10));

            assertThat(failingAttempts())
                    .describedAs("retries must STOP once the budget is exhausted — a climbing count means"
                                 + " the event was never moved aside")
                    .isEqualTo(settled);
        }

        /// Group attribution: the failing group's exhaustion must not touch the healthy group's
        /// progress over the SAME events. This is what "no cross-group duplication by construction,
        /// not by dedup" (§9) means operationally, and it is also the partition-unblock proof — a DLQ
        /// that stalled the shared partition would freeze this count too.
        ///
        /// **The dispatch-started gate is load-bearing and was missing on run 2.** That run sampled
        /// `healthyCount` before poison dispatch had begun at all, so it observed 0 and could not
        /// distinguish a BROKEN healthy group from a LATE one — the arm was vacuous in exactly the way
        /// this suite exists to prevent, in its own assertion. Waiting for the failing group to be
        /// invoked first establishes that the topic is being dispatched AT ALL; only then does the
        /// healthy group's count mean anything, because only then is its absence attributable.
        @Test
        void healthyGroup_processesTheSameEvents_unaffectedByTheFailingGroup() {
            var failingBaseline = failingAttempts();
            var healthyBaseline = healthyCount();

            publishPoison("isolation-probe");

            // Dispatch is happening: the failing group has been invoked for this arm's event. Until
            // this holds, a zero healthyCount says nothing about the healthy group.
            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(DurableTopicDeliveryForgeTest.this::failIfSliceFailed)
                   .untilAsserted(() -> assertThat(failingAttempts() - failingBaseline)
                           .describedAs("poison-events dispatch must be observed BEFORE the healthy"
                                        + " group's progress can be judged — otherwise a zero below is"
                                        + " indistinguishable from 'not started yet'")
                           .isGreaterThan(0));

            // Now it is attributable: the same event reached the failing group, so the healthy group
            // must see it too. A stall here is a real isolation failure, not a timing artefact.
            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(DurableTopicDeliveryForgeTest.this::failIfSliceFailed)
                   .untilAsserted(() -> assertThat(healthyCount() - healthyBaseline)
                           .describedAs("the healthy group shares the topic with a group that can never"
                                        + " ack; separate cursors and retry budgets mean it must still"
                                        + " process the event the failing group is choking on")
                           .isGreaterThanOrEqualTo(1));
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

    /// Sequences as delivered, read from whichever node actually dispatched them.
    ///
    /// It scans every node rather than the first available one. With `partitions = 1` exactly ONE node
    /// owns the partition and records deliveries, and that node is not necessarily the one
    /// [#appPort] happens to return — so reading a single node passes only when the owner is the one
    /// polled. Run 2's ordering arm passed that way, which is luck rather than evidence.
    private List<Integer> deliveredSequences() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .map(port -> httpPost(port, "/api/durable-topic/order-status", "{}"))
                      .map(body -> SEQUENCE_FIELD.matcher(body)
                                                 .results()
                                                 .map(result -> Integer.parseInt(result.group(1)))
                                                 .toList())
                      .filter(sequences -> !sequences.isEmpty())
                      .findFirst()
                      .orElseGet(List::of);
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

    /// The `poison-events` half of the readiness gate. Its warm-up event WILL be dead-lettered by the
    /// failing group and counted by the healthy one — harmless, because every arm takes a baseline
    /// before publishing rather than assuming a zero start.
    private boolean poisonPublishReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var response = httpPost(ports.getFirst(), "/api/durable-topic/publish-poison", "{\"payload\":\"__warmup__\"}");

        return !response.contains("\"error\"") && response.contains("published");
    }

    /// Deliberately a park rather than an awaitility gate: the assertion it serves is that a count does
    /// NOT move, and there is no condition to poll for that — only elapsed time in which movement would
    /// have shown up.
    private static void sleep(Duration duration) {
        java.util.concurrent.locks.LockSupport.parkNanos(duration.toNanos());
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
