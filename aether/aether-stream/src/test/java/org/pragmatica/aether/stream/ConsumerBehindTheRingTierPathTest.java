// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_CURSOR_EXPIRED;

/// #2201 C-16 on the real read path (validated read, `readServingTiered`, `SealedTierFallback`, a real tiered reader): an
/// unreadable tier never resets, a hole is never turned into an expiry, the forwarded `fromTier` boundary, and the reset
/// target when this node's tier holds nothing.
/// The ring holds 20..29 (tail 20), as in `ConsumerBehindTheRingComponentTest`.
class ConsumerBehindTheRingTierPathTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final String GROUP = "group-tier-path";

    private final SealedTierFixture tier = new SealedTierFixture();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final List<Long> delivered = new CopyOnWriteArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();
    private StreamPartitionManager manager;
    private Option<StreamConsumerRuntime> runtime = Option.none();

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10, 1_048_576L, 600_000L), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        for (var offset = 0L; offset < 30; offset++) {
            manager.publishLocal(STREAM, PARTITION, SealedTierFixture.payload(offset), 1_000L + offset)
                   .onFailure(cause -> fail("publish: " + cause.message()));
        }
        manager.tieredReader(tier.reader());
        assertThat(manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::tailOffset).or(-1L)).isEqualTo(20L);
    }

    @AfterEach
    void tearDown() {
        runtime.onPresent(StreamConsumerRuntime::close);
        manager.close();
    }

    /// A tier whose storage cannot serve a segment the index names (the ref is indexed, its block was never stored): the consumer's read
    /// fails with a storage cause, the cursor stays at 0, nothing is delivered and nothing is reset. The control, inside the
    /// same run, shows the read below the ring really reaches the broken storage and fails with a cause that is not an
    /// expiry. RED under MT-02a (any failure resets).
    @Test
    void unreadableTierStorage_isRetried_neverAReset() {
        tier.index().addSegment(STREAM, PARTITION, 0, 19);

        var control = manager.readServingTiered(STREAM, PARTITION, 0L, 10, Epoch.ZERO).await();
        assertThat(control.isFailure()).as("control: the tier read fails").isTrue();
        control.onFailure(cause -> assertThat(cause).isNotInstanceOf(StreamError.CursorExpired.class));

        consume();
        awaitUntil(() -> reads.get() >= 10, "the consumer re-reads");

        assertThat(delivered).isEmpty();
        assertThat(runtime.flatMap(r -> r.cursorPosition(STREAM, PARTITION, GROUP)).or(-1L)).isEqualTo(0L);
        assertThat(codes()).doesNotContain(STREAM_CONSUMER_CURSOR_EXPIRED);
    }

    /// Retention's bound is capped at the contiguous sealed watermark (`RetentionEnforcer.findExpiredSegments`, pinned by
    /// `ReclaimedThroughFloorTest#reclaim_neverPassesTheContiguousSealedWatermark`), which never passes a hole, and even a
    /// recorded reclaimed-through floor above the hole leaves the watermark below it: the tier keeps answering
    /// `SealedRangeMissing`, so an unfilled hole holds the group (the runbook says so).
    @Test
    void aHole_isNeverTurnedIntoAnExpiry_byAReclaimedFloorAboveIt() {
        tier.seal(STREAM, PARTITION, 0, 4);
        tier.seal(STREAM, PARTITION, 10, 19);
        tier.index().recordReclaimed(STREAM, PARTITION, 12L);

        assertThat(tier.index().lastSealedOffset(STREAM, PARTITION)).as("the watermark stops at the hole").isEqualTo(4L);
        var read = tier.reader().read(STREAM, PARTITION, 5L, 10).await();
        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isInstanceOf(SegmentError.SealedRangeMissing.class));
    }

    /// The reset target when the tier holds NOTHING at or above the cursor (never sealed on this node): the ring's own
    /// `CursorExpired` at the RING TAIL reaches the consumer, which skips to 20. The warning says the records are no longer
    /// held by the partition's owner, and does not assert they were reclaimed by retention as its proven cause (CTO ruling on
    /// v-2211 N-1: the reset stays; the text must not assert what the trigger cannot prove).
    @Test
    void emptyTier_belowTheRing_resetsToTheRingTail_withTheOwnerHeldText() {
        consume();
        awaitUntil(() -> delivered.size() >= 10 && !warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED).isEmpty(), "reset and delivered 20..29");

        assertThat(delivered.getFirst()).isEqualTo(20L);
        assertThat(warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED)).singleElement()
                                                              .satisfies(warning -> assertThat(warning.message()).contains("skipped 20 records")
                                                                                                                 .contains("no longer held by this partition's owner")
                                                                                                                 .doesNotContain("retention reclaimed them"));
    }

    /// The honest limit says the forwarded `fromTier` never misses a read the owner served from its tier. The tightest case:
    /// a forwarded read from 19, one below the owner's ring tail 20, served by the tier (19) and continued from the ring.
    @Test
    void forwardedRead_oneBelowTheOwnersRingTail_isNamedATierRead() {
        tier.seal(STREAM, PARTITION, 0, 19);
        var assignee = assigneeOf(manager);

        try {
            var read = assignee.readValidated(STREAM, PARTITION, 19L, 3, Epoch.ZERO).await().unwrap();

            assertThat(read.events()).extracting(OffHeapRingBuffer.RawEvent::offset).containsExactly(19L, 20L, 21L);
            assertThat(read.fromTier()).isTrue();
            var atTail = assignee.readValidated(STREAM, PARTITION, 20L, 2, Epoch.ZERO).await().unwrap();
            assertThat(atTail.fromTier()).as("control: a read at the tail is a ring read").isFalse();
        } finally {
            assigneeManager.get().close();
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<StreamPartitionManager> assigneeManager = new java.util.concurrent.atomic.AtomicReference<>();

    private StreamReadRouter assigneeOf(StreamPartitionManager owner) {
        var assignee = streamPartitionManager();
        var ownerId = org.pragmatica.consensus.NodeId.nodeId("owner").unwrap();
        var assigneeId = org.pragmatica.consensus.NodeId.nodeId("assignee").unwrap();

        assigneeManager.set(assignee);
        assignee.placementRoleSupplier((_, _) -> org.pragmatica.aether.stream.replication.ReplicaSetController.Role.NONE);
        assignee.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10, 1_048_576L, 600_000L), "earliest"))
                .onFailure(cause -> fail(cause.message()));
        var clientRef = new org.pragmatica.aether.stream.forward.StreamForwardClient[1];
        org.pragmatica.aether.stream.forward.StreamForwardTransport ownerToAssignee =
            (_, message) -> clientRef[0].onReadForwardResponse((org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse) message);
        var ownerHandler = org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler(ownerId, owner, ownerToAssignee);
        org.pragmatica.aether.stream.forward.StreamForwardTransport assigneeToOwner =
            (_, message) -> ownerHandler.onReadForward((org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward) message);

        clientRef[0] = org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient(assigneeId,
                                                                                                    assigneeToOwner,
                                                                                                    org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds());

        return StreamReadRouter.streamReadRouter(assignee,
                                                 Option.none(),
                                                 Option.some(clientRef[0]),
                                                 assigneeId,
                                                 (_, _) -> Option.some(ownerId),
                                                 org.pragmatica.aether.stream.forward.StreamReadForwardMetrics.NOOP);
    }

    private void consume() {
        var router = StreamReadRouter.localOnly(manager);
        var created = StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                                  DeadLetterHandler.deadLetterHandler(),
                                                                  nullStore(),
                                                                  StreamConsumerRuntime.validatingReader((_, _, _, _) -> Promise.success(List.of()),
                                                                                                         (stream, partition, from, max, epoch) -> counted(router.readValidated(stream,
                                                                                                                                                                               partition,
                                                                                                                                                                               from,
                                                                                                                                                                               max,
                                                                                                                                                                               epoch))));

        created.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        runtime = Option.some(created);
        created.subscribe(STREAM, PARTITION, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> deliver(offset));
    }

    private Promise<StreamPartitionManager.EpochRead> counted(Promise<StreamPartitionManager.EpochRead> read) {
        reads.incrementAndGet();

        return read;
    }

    private Promise<Unit> deliver(long offset) {
        delivered.add(offset);

        return Promise.unitPromise();
    }

    private static ConsumerCursorStore nullStore() {
        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String group, String stream, int partition, long offset) {
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String group, String stream, int partition) {
                return Promise.success(Option.none());
            }
        };
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
}
