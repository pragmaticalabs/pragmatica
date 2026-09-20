// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
/// each id is counted once per node. Asserting exactly one delivery PER ID is simultaneously a
/// delivery proof and a duplication proof. The subscriber methods are deliberately ABSENT from the
/// fixture's `routes.toml`, so nothing but the runtime's dispatch path can invoke them.
///
/// **Non-vacuity of the group-attribution arm.** Two subscriber methods bind to the SAME
/// `poison-events` topic, making them two consumer groups over one event sequence. One can never
/// ack. If attribution is real the failing group dead-letters while the healthy group processes the
/// identical events untouched.
///
/// **No arm reads another arm's events.** Every arm publishes under ids of its own and counts only
/// those, per id, from the fixture's per-group records. The first runs of this suite compared
/// cluster-wide counters against baselines and failed three arms on events still in flight from the
/// readiness gates or an earlier arm; a baseline cannot tell whose event moved a counter, an id can.
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
///   - **Publish outcomes (#1236) and pre-durability visibility (#1235) have no arm.** Driving a
///     `NOT_ENOUGH_REPLICAS` result needs fewer live replica targets than `min_sync_replicas = 2`,
///     which a five-node cluster only reaches by losing quorum; #1235's loss needs an owner failover,
///     the missing #739 arm. Every publish here resolves normally, so neither defect can show.
@Tag("Heavy")
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
    private static final int SLOW_ORDER_COUNT = 10;
    private static final int POISON_COUNT = 2;

    /// durable-pubsub-spec §7: bounded retries before the dead-letter hop. The fixture's failing
    /// handler records every invocation, so this is observed rather than assumed.
    private static final int EXPECTED_ATTEMPTS_PER_EVENT = 5;

    /// Declared alongside every other fixture coordinate in [TestArtifacts], where its rationale lives.
    private static final String DURABLE_TOPIC_SLICE = TestArtifacts.DURABLE_TOPIC_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:durable-topic:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    /// The id both readiness gates publish under. No arm counts it: every arm counts only the ids it
    /// published itself, which is what removes baseline carryover rather than draining around it.
    ///
    /// The number of warm-up events is NOT fixed. A gate publish can time out after its event landed
    /// (#1236), and the gate then publishes again under a new message id (#1237); one run in eight left
    /// two warm-up events. No verdict depends on it: [PreAttachBacklog] asserts at least one
    /// delivery, never an exact count.
    private static final String WARMUP_ID = "__warmup__";

    /// The fixture acks orders carrying this prefix late (`DurableTopicSlice.durableTopicSlice.SLOW_ACK_PREFIX`).
    private static final String SLOW_ACK_PREFIX = "slow-";

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /// How long a count must stay put before it is read as final. Longer than the whole retry budget
    /// (100+200+400+800ms of backoff), so a late duplicate or a resumed retry lands inside it.
    private static final Duration SETTLE = Duration.ofSeconds(10);

    private static final Pattern ORDER_ENTRY = Pattern.compile("\"orderId\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"sequence\"\\s*:\\s*(-?\\d+)");
    private static final Pattern FAILING_PAYLOADS = Pattern.compile("\"failingPayloads\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern HEALTHY_PAYLOADS = Pattern.compile("\"healthyPayloads\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern INSTANCE_ID = Pattern.compile("\"instanceId\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

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
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());

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
        // real publish resolving before any assertion runs. Both gates publish under WARMUP_ID, which no
        // arm counts.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::publishReady);

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::poisonPublishReady);

        // Arms count only their own ids, so a warm-up can no longer be MISCOUNTED. It can still
        // INTERFERE: a poison warm-up mid-retry when an arm publishes shares that group's partition, and
        // #1238(a) re-delivers a retrying event on every append. So the poison topic must be quiescent
        // before the first arm runs — either the warm-up exhausted its budget, or it is stranded
        // pre-attach (#1238(c)) and the first arm's append releases it into a serial loop.
        //
        // The order-events warm-up is deliberately NOT drained here. The previous version waited for it
        // and died in setUp on every run, taking all five arms with it: an event appended before its
        // group's push listener registers is never read until the next append (#1238(c)). That is now
        // an arm of its own, [PreAttachBacklog], instead of a precondition of every arm.
        var lastPoisonSample = new AtomicInteger(-1);

        await().atMost(WAIT_TIMEOUT)
               .pollDelay(SETTLE)
               .pollInterval(SETTLE)
               .failFast(this::failIfSliceFailed)
               .until(() -> unchangedSinceLastSample(lastPoisonSample, failingAttemptsFor(WARMUP_ID)));
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());

            httpDelete(leaderPort, "/api/v1/blueprints/" + BLUEPRINT_ID);
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    /// #1238(c): subscribing to a LOCAL partition only installs an append listener, so an event
    /// already in the ring waits for the next append. The order-events warm-up is published by the
    /// readiness gate, before the group attaches, and nothing else appends to order-events until the
    /// [Delivery] arms run — so this class must run FIRST, and nothing in it may publish an order.
    ///
    /// Before PR #1285 (#1238(c): subscribe installed a listener and never read the backlog) the
    /// warm-up was never delivered — undelivered after 20 s in 6/6 rc4 runs, and delivered in 0.6 s
    /// once #1285 merged. The stranding is also the mechanism #751 left unexplained: the suite's old
    /// setUp drain gate waited on exactly this event and timed out on every run.
    @Nested
    @Order(1)
    class PreAttachBacklog {
        /// Meaningful only when the warm-up was appended before the group attached. The pre-#1285
        /// tripwire that stood here found it undelivered on every rc4 run, which is that precondition
        /// observed: an event published after attach would have been delivered by the listener.
        @Test
        void eventPublishedBeforeTheGroupAttached_isDeliveredWithoutAFollowUpAppend() {
            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(DurableTopicDeliveryForgeTest.this::failIfSliceFailed)
                   .untilAsserted(() -> assertThat(deliveriesOf(WARMUP_ID))
                           .describedAs("the readiness gate's order was appended before the group"
                                        + " attached; a subscribe that reads the backlog delivers it"
                                        + " without any further publish")
                           .isGreaterThanOrEqualTo(1));
        }
    }

    @Nested
    @Order(2)
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
            publishPoison("tier-probe");

            awaitSettled("the durable tier retries a failing handler %d times; ephemeral delivery invokes"
                           .formatted(EXPECTED_ATTEMPTS_PER_EVENT)
                           + " it ONCE and never retries, so anything above 1 proves the durable tier is"
                           + " dispatching",
                           () -> failingAttemptsFor("tier-probe"),
                           EXPECTED_ATTEMPTS_PER_EVENT);
        }
    }

    @Nested
    @Order(3)
    class Delivery {
        /// Delivery AND duplication in one assertion — see the class doc: one partition, slice on every
        /// node, so an ungated consumer would deliver each id once per node. Counted per id, so a
        /// duplicate of one event cannot be masked by the loss of another.
        @Test
        void everyPublishedEvent_isDeliveredExactlyOnceClusterWide() {
            var ids = publishOrders("dlv-", ORDER_COUNT);

            awaitSettled("each event delivered exactly once cluster-wide — a count of %d per id would mean"
                         .formatted(NODES)
                         + " ungated per-node delivery",
                         () -> deliveryCounts(ids),
                         onceEach(ids));
        }
    }

    /// The only ordering guarantee §5 makes: serial per (group x partition). The topic has ONE
    /// partition, so dispatch order is offset order and the ascending sequences an arm published must
    /// come back ascending, each exactly once.
    ///
    /// The events are acked late ([#SLOW_ACK_PREFIX]) and published back to back, so each append
    /// arrives while an earlier delivery is still unacked. With an instant ack every delivery completes
    /// before the next append and serial dispatch is indistinguishable from overlapping dispatch — the
    /// previous form of this arm could not fail, and when it ran before the delivery arm it asserted
    /// that a one-element list was sorted.
    ///
    /// Before PR #1285 (#1238(a)/(b)) every append started its own delivery pass from a cursor the
    /// unacked delivery had not yet advanced: ten late-acked events came back 10, 9, 8, … 1 times.
    /// With #1285 each comes back once, in order. Runs after [Delivery] so a redelivery storm, should
    /// one recur, cannot reach that arm's window.
    @Nested
    @Order(4)
    class SerialDispatch {
        @Test
        void eventsArriveInPublishedOrder_evenWhilePreviousDeliveriesAreUnacked() {
            var prefix = SLOW_ACK_PREFIX + "ord-";
            var ids = publishOrders(prefix, SLOW_ORDER_COUNT);

            awaitSettled("each late-acked event delivered exactly once — a repeat means a second delivery"
                         + " of an offset overlapped the first", () -> deliveryCounts(ids), onceEach(ids));

            assertThat(sequencesPerNode(prefix)).describedAs("serial per-(group x partition) dispatch over"
                                                              + " one partition means arrival order IS"
                                                              + " offset order on every node that"
                                                              + " delivered")
                                                 .allSatisfy(sequences -> assertThat(sequences).isSorted());
        }
    }

    @Nested
    @Order(5)
    class DeadLetterPath {
        /// The DLQ arm. A handler that can never ack must be retried a BOUNDED number of times and then
        /// stop — and stopping is the dead-letter boundary observed from outside: the runtime gave up on
        /// the event and moved it aside rather than retrying it forever or silently dropping it on the
        /// first failure. The second event proves the failing group's cursor moved PAST the first.
        ///
        /// The events are published one at a time, each after the previous exhausted its budget. Two
        /// back to back put the second append inside the first's retry backoff, where #1238(a)
        /// re-delivers the retrying event alongside its own scheduled retry; that is a separate
        /// defect, and this arm keeps it out of the budget it measures.
        ///
        /// The `.dlq` stream itself is deliberately NOT asserted here. It is a runtime-created stream
        /// named `topic:<address>.dlq`, which the management stream listing cannot show (see
        /// [DurableTier#failingHandlerIsRETRIED_whichEphemeralDispatchNeverDoes] for why), so asserting
        /// on it from this suite would mean asserting on something unobservable. Its contents are
        /// unit-covered by `DlqStreamSinkTest.append_reEnvelopesWithGroupAttribution_preservingMessageId`;
        /// what this suite adds is that the boundary is reached on a real cluster and the partition
        /// survives it — the second half being
        /// [#healthyGroup_processesTheSameEvent_onceAndUnaffectedByTheFailingGroup].
        @Test
        void poisonEvent_isRetriedABoundedNumberOfTimes_thenStops() {
            for (var i = 0; i < POISON_COUNT; i++) {
                var payload = "poison-" + i;

                publishPoison(payload);
                awaitSettled("the never-acking handler must be retried a BOUNDED number of times (%d for %s),"
                               .formatted(EXPECTED_ATTEMPTS_PER_EVENT, payload)
                               + " not forever and not once — a count still climbing after the budget means"
                               + " the event was never moved aside",
                               () -> failingAttemptsFor(payload),
                               EXPECTED_ATTEMPTS_PER_EVENT);
            }
        }

        /// Group attribution: the failing group's exhaustion must not touch the healthy group's
        /// handling of the SAME event. This is what "no cross-group duplication by construction, not by
        /// dedup" (§9) means operationally, and it is also the partition-unblock proof — a DLQ that
        /// stalled the shared partition would never let the healthy group see the event.
        ///
        /// **Why this arm can now fail.** The earlier form asserted that a cluster-wide healthy COUNT
        /// grew by at least one after a publish. Any poison event still in flight from another arm or a
        /// warm-up satisfied that, so it could pass with the probe never reaching the healthy group.
        /// Every assertion here names the probe's own payload, and each half is checked from its own
        /// group's record: the failing group must have been invoked for the probe (the two groups are
        /// separate consumers at all), the healthy group must have handled it EXACTLY once (a failing
        /// group's retries re-dispatching to every group would show as a repeat), and the failing
        /// group's budget must still come out at exactly 5 (a healthy ack leaking into the failing
        /// group's cursor would cut it short).
        @Test
        void healthyGroup_processesTheSameEvent_onceAndUnaffectedByTheFailingGroup() {
            var probe = "isolation-probe";

            publishPoison(probe);

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(DurableTopicDeliveryForgeTest.this::failIfSliceFailed)
                   .untilAsserted(() -> assertThat(failingAttemptsFor(probe))
                           .describedAs("the failing group must be invoked for %s — if only one group ever"
                                        + " sees it, the two subscriber methods are not two groups", probe)
                           .isGreaterThan(0));

            awaitSettled("the healthy group must handle %s exactly once, while the group sharing the topic".formatted(probe)
                           + " never acks it",
                           () -> healthyDeliveriesOf(probe),
                           1);

            awaitSettled("the failing group's budget for %s is its own".formatted(probe),
                           () -> failingAttemptsFor(probe),
                           EXPECTED_ATTEMPTS_PER_EVENT);
        }
    }

    // --- fixture driving -----------------------------------------------------

    /// Publishes `count` orders with ids `prefix + i` and ascending sequences, each publish resolving
    /// before the next is sent, so publication order is offset order.
    private List<String> publishOrders(String prefix, int count) {
        return IntStream.range(0, count)
                        .mapToObj(i -> publishOrder(prefix + i, i))
                        .toList();
    }

    private String publishOrder(String orderId, int sequence) {
        var body = "{\"orderId\":\"%s\",\"sequence\":%d}".formatted(orderId, sequence);
        var response = httpPost(appPort(), "/api/durable-topic/publish-order", body);

        assertThat(response).describedAs("durable publish must resolve at the min-sync floor")
                            .doesNotContain("\"error\"");

        return orderId;
    }

    private void publishPoison(String payload) {
        var response = httpPost(appPort(), "/api/durable-topic/publish-poison", "{\"payload\":\"" + payload + "\"}");

        assertThat(response).doesNotContain("\"error\"");
    }

    /// Waits until `probe` reaches `expected`, then holds for [#SETTLE] and requires it to still read
    /// `expected`. Reaching a count proves delivery; holding it is what proves no duplicate or resumed
    /// retry arrived afterwards, which a bare "until equal" can never see.
    private <T> void awaitSettled(String description, Supplier<T> probe, T expected) {
        await().atMost(DELIVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .untilAsserted(() -> assertThat(probe.get()).describedAs(description)
                                                           .isEqualTo(expected));

        sleep(SETTLE);

        assertThat(probe.get()).describedAs("%s — and it must STAY there for %s", description, SETTLE)
                               .isEqualTo(expected);
    }

    private static Map<String, Long> onceEach(List<String> ids) {
        return ids.stream()
                  .collect(Collectors.toMap(Function.identity(), _ -> 1L));
    }

    /// Per id, how many times it was delivered, summed across every instance — which is what makes the
    /// count a cluster-wide claim rather than a per-node one. Ids never delivered are reported as 0.
    private Map<String, Long> deliveryCounts(List<String> ids) {
        var delivered = allDeliveries().stream()
                                       .collect(Collectors.groupingBy(Delivered::orderId, Collectors.counting()));

        return ids.stream()
                  .collect(Collectors.toMap(Function.identity(), id -> delivered.getOrDefault(id, 0L)));
    }

    private long deliveriesOf(String orderId) {
        return allDeliveries().stream()
                              .filter(delivered -> delivered.orderId().equals(orderId))
                              .count();
    }

    private List<Delivered> allDeliveries() {
        return deliveriesPerNode().stream()
                                  .flatMap(List::stream)
                                  .toList();
    }

    /// The sequences of the `prefix` orders as each node delivered them, in arrival order; nodes that
    /// delivered none are omitted.
    ///
    /// It scans every node rather than the first available one. With `partitions = 1` exactly ONE node
    /// owns the partition at a time and records deliveries, and that node is not necessarily the one
    /// [#appPort] happens to return — so reading a single node passes only when the owner is the one
    /// polled.
    private List<List<Integer>> sequencesPerNode(String prefix) {
        return deliveriesPerNode().stream()
                                  .map(deliveries -> deliveries.stream()
                                                               .filter(delivered -> delivered.orderId().startsWith(prefix))
                                                               .map(Delivered::sequence)
                                                               .toList())
                                  .filter(sequences -> !sequences.isEmpty())
                                  .toList();
    }

    private List<List<Delivered>> deliveriesPerNode() {
        return statusPerInstance("/api/durable-topic/order-status").stream()
                                                                   .map(DurableTopicDeliveryForgeTest::parseDeliveries)
                                                                   .toList();
    }

    /// One status body per slice INSTANCE, however many ports answered from it.
    ///
    /// App HTTP is local-first but forwards when the receiving node has no active local instance, so
    /// two ports can answer from the same instance. Summing per PORT then counts that instance's
    /// deliveries twice — a run reported 10 attempts for an event whose trace log shows exactly 5 —
    /// so bodies are keyed by the `instanceId` the fixture reports and each instance is counted once.
    private List<String> statusPerInstance(String path) {
        return List.copyOf(cluster.getAvailableAppHttpPorts()
                                  .stream()
                                  .map(port -> httpPost(port, path, "{}"))
                                  .collect(Collectors.toMap(DurableTopicDeliveryForgeTest::instanceId,
                                                            Function.identity(),
                                                            (first, _) -> first))
                                  .values());
    }

    /// A body carrying no `instanceId` is an error response; each gets a key of its own, so it
    /// contributes nothing to a count and never displaces a real instance's body.
    private static String instanceId(String body) {
        var matcher = INSTANCE_ID.matcher(body);

        return matcher.find()
               ? matcher.group(1)
               : "no-instance:" + UUID.randomUUID();
    }

    private static List<Delivered> parseDeliveries(String body) {
        return ORDER_ENTRY.matcher(body)
                          .results()
                          .map(result -> new Delivered(result.group(1), Integer.parseInt(result.group(2))))
                          .toList();
    }

    private int failingAttemptsFor(String payload) {
        return poisonRecordsOf(FAILING_PAYLOADS, payload);
    }

    private int healthyDeliveriesOf(String payload) {
        return poisonRecordsOf(HEALTHY_PAYLOADS, payload);
    }

    /// Occurrences of `payload` in one of the fixture's per-group records, summed across every instance.
    private int poisonRecordsOf(Pattern field, String payload) {
        return statusPerInstance("/api/durable-topic/poison-status").stream()
                                                                    .mapToInt(body -> occurrences(field, body, payload))
                                                                    .sum();
    }

    private static int occurrences(Pattern field, String body, String payload) {
        var list = field.matcher(body);

        return list.find()
               ? (int) QUOTED.matcher(list.group(1))
                             .results()
                             .filter(result -> result.group(1).equals(payload))
                             .count()
               : 0;
    }

    private static boolean unchangedSinceLastSample(AtomicInteger last, int now) {
        return last.getAndSet(now) == now;
    }

    record Delivered(String orderId, int sequence) {}

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
                                "{\"orderId\":\"" + WARMUP_ID + "\",\"sequence\":0}");

        return !response.contains("\"error\"") && response.contains("published");
    }

    /// The `poison-events` half of the readiness gate. Its warm-up event WILL be dead-lettered by the
    /// failing group and handled by the healthy one — harmless to the counts, because no arm counts
    /// [#WARMUP_ID], and kept from interfering by the quiescence gate at the end of [#setUp].
    private boolean poisonPublishReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var response = httpPost(ports.getFirst(), "/api/durable-topic/publish-poison", "{\"payload\":\"" + WARMUP_ID + "\"}");

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
