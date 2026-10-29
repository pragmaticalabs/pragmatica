// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BACK_IN_RING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BEHIND_RING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_CURSOR_EXPIRED;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_SEALED_RANGE_MISSING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_SEALED_RANGE_RESTORED;

/// #2201 C-16 in one JVM, through the real read path: a consumer runtime reading through `StreamReadRouter.readValidated`
/// (the node's validated reader), the partition manager's `readServingTiered`, the sealed-tier fallback and a real tiered
/// reader. The ring holds offsets 20..29 (tail 20); the tier holds what each scenario writes.
///
/// - NT-05: the range retention reclaimed (0..9): the group resets forward once, to 10, with one CRITICAL warning carrying
///   the exact count, a checkpoint at 10, and everything from 10 on delivered.
/// - NT-104: a hole (5..9 never sealed): the group waits at 5 with one WARNING naming the range and resumes, with the
///   recovery, once the hole fills.
/// - NT-02: the owner-served forwarded consumer read below the ring is served from the owner's tier, under the owner epoch.
/// - NT-03: the local validated read keeps epoch admission ahead of the tier, and the operator's WARN line names the group.
class ConsumerBehindTheRingComponentTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final String GROUP = "group-c16";
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);

    private final SealedTierFixture tier = new SealedTierFixture();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final List<Long> delivered = new CopyOnWriteArrayList<>();
    private final List<Long> commits = new CopyOnWriteArrayList<>();
    private final AtomicInteger readsAtHole = new AtomicInteger();
    private StreamPartitionManager manager;
    private Option<StreamConsumerRuntime> runtime = Option.none();

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10, 1_048_576L, 600_000L), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        publish(30);
        manager.tieredReader(tier.reader());
        assertThat(manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::tailOffset).or(-1L)).as("control: ring tail")
                                                                                                         .isEqualTo(20L);
    }

    @AfterEach
    void tearDown() {
        runtime.onPresent(StreamConsumerRuntime::close);
        manager.close();
        tier.shutdown();
    }

    /// NT-05.
    @Test
    void reclaimedRange_resetsForwardOnce_withTheExactSkippedCount_andDeliversEverythingAfterIt() {
        tier.seal(STREAM, PARTITION, 0, 9);
        tier.seal(STREAM, PARTITION, 10, 19);
        tier.reclaim(STREAM, PARTITION, 0);

        consume();
        awaitUntil(() -> delivered.size() >= 20 && !warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED).isEmpty(), "delivered 10..29");
        publish(1);
        awaitUntil(() -> delivered.contains(30L) && !warningsOf(STREAM_CONSUMER_BACK_IN_RING).isEmpty(), "delivered 30 from the ring");

        assertThat(delivered).as("nothing below the earliest retained offset, everything above it, in order")
                             .containsExactlyElementsOf(range(10, 30));
        assertThat(commits.getFirst()).as("the reset is checkpointed at once").isEqualTo(10L);
        assertThat(warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED)).singleElement()
                                                              .satisfies(warning -> assertThat(warning.message()).contains(GROUP)
                                                                                                                 .contains("skipped 10 records at offsets [0, 10)"));
        assertThat(codes()).as("the lag episode opens on the tier read from 10 and closes on the ring read from 30")
                           .containsSubsequence(STREAM_CONSUMER_CURSOR_EXPIRED, STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
        assertThat(warningsOf(STREAM_CONSUMER_BEHIND_RING)).hasSize(1);
        assertThat(codes()).doesNotContain(STREAM_CONSUMER_SEALED_RANGE_MISSING);
    }

    /// NT-104.
    @Test
    void holeInTheSealedRange_waitsAtItWithOneWarning_andResumesWhenItFills() {
        tier.seal(STREAM, PARTITION, 0, 4);
        tier.seal(STREAM, PARTITION, 10, 19);

        consume();
        awaitUntil(() -> !warningsOf(STREAM_CONSUMER_SEALED_RANGE_MISSING).isEmpty() && readsAtHole.get() >= 3,
                   "the hole is warned and re-read");

        assertThat(delivered).as("delivered up to the hole, nothing past it").containsExactlyElementsOf(range(0, 4));
        assertThat(runtime.flatMap(r -> r.cursorPosition(STREAM, PARTITION, GROUP)).or(-1L)).as("the cursor waits at the hole")
                                                                                            .isEqualTo(5L);
        assertThat(warningsOf(STREAM_CONSUMER_SEALED_RANGE_MISSING)).singleElement().satisfies(warning -> {
            assertThat(warning.subject()).contains(GROUP).endsWith("@5");
            assertThat(warning.message()).contains(GROUP).contains("[5, 9]").contains("offset 10 is sealed");
        });

        tier.seal(STREAM, PARTITION, 5, 9);
        awaitUntil(() -> delivered.size() >= 30 && !warningsOf(STREAM_CONSUMER_SEALED_RANGE_RESTORED).isEmpty(), "the hole filled");

        assertThat(delivered).containsExactlyElementsOf(range(0, 29));
        assertThat(warningsOf(STREAM_CONSUMER_SEALED_RANGE_MISSING)).as("once per (group, partition, offset), not per re-read")
                                                                    .hasSize(1);
        assertThat(warningsOf(STREAM_CONSUMER_SEALED_RANGE_RESTORED)).singleElement()
                                                                     .satisfies(warning -> assertThat(warning.subject()).endsWith("@5"));
        assertThat(codes()).doesNotContain(STREAM_CONSUMER_CURSOR_EXPIRED);
    }

    /// NT-02: a consumer on a node that holds no ring reads through the owner; the owner's handler serves the read below
    /// its ring from its tier, under its epoch, and the consumer learns it was behind the ring.
    @Test
    void forwardedConsumerRead_belowTheOwnersRing_isServedFromTheTier_underTheOwnerEpoch() {
        tier.seal(STREAM, PARTITION, 0, 19);
        manager.ownershipRecords((_, _) -> Option.some(record(List.of(new EpochStart(E1, 0L), new EpochStart(E2, 25L)))));
        var assignee = assigneeOf(manager);

        try {
            var belowRing = assignee.readValidated(STREAM, PARTITION, 3L, 5, E1).await().unwrap();
            var inRing = assignee.readValidated(STREAM, PARTITION, 22L, 2, E2).await().unwrap();

            assertThat(belowRing.events()).extracting(OffHeapRingBuffer.RawEvent::offset).containsExactly(3L, 4L, 5L, 6L, 7L);
            assertThat(new String(belowRing.events().getFirst().data(), UTF_8)).isEqualTo("evt-3");
            assertThat(belowRing.ownerEpoch()).isEqualTo(E2);
            assertThat(belowRing.fromTier()).isTrue();
            assertThat(inRing.events()).extracting(OffHeapRingBuffer.RawEvent::offset).containsExactly(22L, 23L);
            assertThat(inRing.fromTier()).isFalse();
        } finally {
            assigneeManager.get().close();
        }
    }

    /// NT-03: below the ring, epoch admission still comes first: a cursor of a replaced lineage gets `EpochDiverged`, not
    /// the tier's events. A cursor of the live lineage is served from the tier with the epoch, and the operator's WARN line
    /// names the group (the cluster real also matches a line containing `CursorExpired`, so it cannot tell).
    @Test
    void localValidatedRead_belowTheRing_keepsEpochAdmissionFirst_andTheWarnLineNamesTheGroup() {
        tier.seal(STREAM, PARTITION, 0, 19);
        manager.ownershipRecords((_, _) -> Option.some(record(List.of(new EpochStart(E1, 0L), new EpochStart(E2, 5L)))));
        var router = StreamReadRouter.localOnly(manager);

        var diverged = router.readValidated(STREAM, PARTITION, 7L, 5, E1).await();
        var served = router.readValidated(STREAM, PARTITION, 2L, 3, E1).await().unwrap();

        assertThat(diverged.isFailure()).isTrue();
        diverged.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                             divergence -> assertThat(divergence.resumeAt()).isEqualTo(5L)));
        assertThat(served.events()).extracting(OffHeapRingBuffer.RawEvent::offset).containsExactly(2L, 3L, 4L);
        assertThat(served.ownerEpoch()).isEqualTo(E2);
        assertThat(served.fromTier()).isTrue();

        manager.ownershipRecords((_, _) -> Option.none());
        var lines = new CopyOnWriteArrayList<String>();
        var detach = LogCapture.warningsOf(ConsumerRuntimeState.class, lines);

        try {
            consume();
            awaitUntil(() -> lines.stream().anyMatch(line -> line.contains("stream-consumer-behind-ring")), "the WARN line");

            assertThat(lines.stream().filter(line -> line.contains("stream-consumer-behind-ring")).toList())
                .singleElement()
                .satisfies(line -> assertThat(line).contains(GROUP));
        } finally {
            detach.run();
        }
    }

    /// B5 on the tier path: a copy that is not the owner and has not been compared with its owner holds back what it has at
    /// and above the committed epoch's start; that holds for the events its tier serves below the ring as for the ring's.
    @Test
    void unverifiedReplica_holdsBackTierEventsFromTheEpochStart_asItDoesRingEvents() {
        tier.seal(STREAM, PARTITION, 0, 19);
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        manager.ownerEpochSource((_, _) -> E2);
        manager.epochStarts((_, _, _) -> Option.some(5L));

        var read = manager.readServingTiered(STREAM, PARTITION, 2L, 10, Epoch.ZERO).await().unwrap();

        assertThat(read.fromTier()).isTrue();
        assertThat(read.events()).extracting(OffHeapRingBuffer.RawEvent::offset).as("nothing at or above the epoch start 5")
                                 .containsExactly(2L, 3L, 4L);
    }

    /// I-09 has one decider: the runtime's DEFAULT reader (the local one, used where no routed reader is wired) reads below
    /// the ring through the same validated read, so it is served from the tier like the node's reader, never reset to the
    /// ring tail past records the tier still holds.
    @Test
    void defaultLocalReader_belowTheRing_isServedFromTheTier() {
        tier.seal(STREAM, PARTITION, 0, 19);
        var local = StreamConsumerRuntime.streamConsumerRuntime(manager);

        local.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        runtime = Option.some(local);
        local.subscribe(STREAM, PARTITION, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> deliver(offset));
        awaitUntil(() -> delivered.size() >= 30, "delivered 0..29");

        assertThat(delivered).containsExactlyElementsOf(range(0, 29));
        assertThat(codes()).doesNotContain(STREAM_CONSUMER_CURSOR_EXPIRED);
    }

    private final AtomicReference<StreamPartitionManager> assigneeManager = new AtomicReference<>();

    private StreamReadRouter assigneeOf(StreamPartitionManager owner) {
        var assignee = streamPartitionManager();
        var ownerId = NodeId.nodeId("owner").unwrap();
        var assigneeId = NodeId.nodeId("assignee").unwrap();

        assigneeManager.set(assignee);
        assignee.placementRoleSupplier((_, _) -> ReplicaSetController.Role.NONE);
        assignee.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10, 1_048_576L, 600_000L), "earliest"))
                .onFailure(cause -> fail(cause.message()));
        var clientRef = new StreamForwardClient[1];
        StreamForwardTransport ownerToAssignee = (_, message) -> clientRef[0].onReadForwardResponse((ReadForwardResponse) message);
        var ownerHandler = StreamForwardHandler.streamForwardHandler(ownerId, owner, ownerToAssignee);
        StreamForwardTransport assigneeToOwner = (_, message) -> ownerHandler.onReadForward((ReadForward) message);

        clientRef[0] = StreamForwardClient.streamForwardClient(assigneeId, assigneeToOwner, TimeSpan.timeSpan(5).seconds());

        return StreamReadRouter.streamReadRouter(assignee,
                                                 Option.none(),
                                                 Option.some(clientRef[0]),
                                                 assigneeId,
                                                 (_, _) -> Option.some(ownerId),
                                                 StreamReadForwardMetrics.NOOP);
    }

    private void consume() {
        var router = StreamReadRouter.localOnly(manager);
        var created = StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                                  DeadLetterHandler.deadLetterHandler(),
                                                                  recordingStore(),
                                                                  StreamConsumerRuntime.validatingReader((_, _, _, _) -> Promise.success(List.of()),
                                                                                                         (stream, partition, from, max, epoch) -> countingHole(router.readValidated(stream,
                                                                                                                                                                                    partition,
                                                                                                                                                                                    from,
                                                                                                                                                                                    max,
                                                                                                                                                                                    epoch),
                                                                                                                                                               from)));

        created.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        runtime = Option.some(created);
        created.subscribe(STREAM, PARTITION, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> deliver(offset));
    }

    private Promise<StreamPartitionManager.EpochRead> countingHole(Promise<StreamPartitionManager.EpochRead> read, long from) {
        if (from == 5L) {
            readsAtHole.incrementAndGet();
        }

        return read;
    }

    private Promise<Unit> deliver(long offset) {
        delivered.add(offset);

        return Promise.unitPromise();
    }

    private ConsumerCursorStore recordingStore() {
        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String group, String stream, int partition, long offset) {
                commits.add(offset);

                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String group, String stream, int partition) {
                return Promise.success(Option.none());
            }
        };
    }

    private void publish(int count) {
        var head = manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::headOffset).or(-1L);

        for (var offset = head + 1; offset <= head + count; offset++) {
            manager.publishLocal(STREAM, PARTITION, SealedTierFixture.payload(offset), 1_000L + offset)
                   .onFailure(cause -> fail("publish: " + cause.message()));
        }
    }

    private List<OperatorWarningCode> codes() {
        return warnings.stream().map(OperatorWarning::code).toList();
    }

    private List<OperatorWarning> warningsOf(OperatorWarningCode code) {
        return warnings.stream().filter(warning -> warning.code() == code).toList();
    }

    private void awaitUntil(BooleanSupplier done, String what) {
        var deadline = System.currentTimeMillis() + 10_000L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(done.getAsBoolean()).as("%s within 10 s (delivered %s, warnings %s)", what, delivered, codes()).isTrue();
    }

    private static List<Long> range(long from, long to) {
        return java.util.stream.LongStream.rangeClosed(from, to).boxed().toList();
    }

    private static StreamPartitionOwnershipValue record(List<EpochStart> starts) {
        var owner = NodeId.nodeId("owner").unwrap();

        return new StreamPartitionOwnershipValue(owner, E2, 2L, HlcTimestamp.ZERO, List.of(owner), 3L, false, List.of(), starts);
    }
}
