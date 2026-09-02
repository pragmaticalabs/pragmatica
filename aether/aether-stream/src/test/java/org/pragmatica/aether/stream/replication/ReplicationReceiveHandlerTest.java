// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

class ReplicationReceiveHandlerTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final int PARTITION = 0;

    private enum TestError implements Cause {
        APPEND_FAILED;

        @Override public String message() {
            return "append failed (test)";
        }
    }

    @Test
    void fullBatch_apply_acksHighestOffset_noGapRepair() {
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var gapFires = new AtomicInteger(0);
        // Appender that always succeeds.
        ReplicationReceiveHandler.RecoveredAppender appender = (_, _, _, _, _) -> Result.success(0L);
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 10L, payloads(3), timestamps(3), Epoch.ZERO));

        // All 3 applied: ack highest = 10 + 3 - 1 = 12, no repair.
        assertThat(acks).hasSize(1);
        assertThat(acks.getFirst().confirmedOffset()).isEqualTo(12L);
        assertThat(gapFires.get()).isZero();
    }

    @Test
    void midBatchApplyFailure_acksOnlyContiguousPrefix_andTriggersBackfillRepair() {
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var gapStream = new ArrayList<String>();
        var gapPartition = new AtomicLong(-1);
        // Appender fails on the 3rd append (index 2): only [10, 11] land contiguously, [12] is the gap.
        var calls = new AtomicInteger(0);
        ReplicationReceiveHandler.RecoveredAppender appender =
                (_, _, _, _, _) -> calls.getAndIncrement() < 2 ? Result.success(0L) : TestError.APPEND_FAILED.result();
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (stream, partition) -> {
                                                    gapStream.add(stream);
                                                    gapPartition.set(partition);
                                                });

        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 10L, payloads(4), timestamps(4), Epoch.ZERO));

        // Ack only the contiguous prefix end (11), NEVER the batch nominal end (13) — no over-stated
        // watermark, so the replica is not falsely treated as CAUGHT_UP at the batch end.
        assertThat(acks).hasSize(1);
        assertThat(acks.getFirst().confirmedOffset()).isEqualTo(11L);
        // The gap is surfaced for repair: the replica re-enters SYNCING/backfill for (stream, partition).
        assertThat(gapStream).containsExactly(STREAM);
        assertThat(gapPartition.get()).isEqualTo((long) PARTITION);
    }

    @Test
    void zeroApplied_firstAppendFails_noAck_butTriggersBackfillRepair() {
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var gapFires = new AtomicInteger(0);
        // Appender fails immediately: nothing lands.
        ReplicationReceiveHandler.RecoveredAppender appender = (_, _, _, _, _) -> TestError.APPEND_FAILED.result();
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 10L, payloads(2), timestamps(2), Epoch.ZERO));

        // Nothing applied => no ack (would otherwise ack 10 + 0 - 1 = 9, below fromOffset).
        assertThat(acks).isEmpty();
        // Gap still surfaced so the replica repairs.
        assertThat(gapFires.get()).isEqualTo(1);
    }

    // S1 / #260: offset verification against the local head — the verifying factory rejects gaps,
    // skips duplicates, and never silently shifts local offsets when a batch is dropped/reordered.

    /// A successful append that tracks a local ring so the local-head seam advances as events land.
    /// #634 item 1: the ack means "fsynced here". It must not leave before the durability barrier
    /// resolves — an ack from RAM lets the owner's min-sync barrier count a copy that correlated power
    /// loss can erase.
    @Test
    void ack_waitsForTheDurabilityBarrier_thenCarriesTheHighestAppliedOffset() {
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var barrier = Promise.<Unit> promise();
        ReplicationReceiveHandler.RecoveredAppender appender = (_, _, _, _, _) -> Result.success(0L);
        var handler = ReplicationReceiveHandler.replicationReceiveHandler(SELF,
                                                                          appender,
                                                                          (_, _) -> 10L,
                                                                          (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                                          (_, _) -> {},
                                                                          (_, _) -> barrier);

        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 10L, payloads(3), timestamps(3), Epoch.ZERO));

        assertThat(acks).as("no ack may leave before the batch is durable").isEmpty();

        barrier.resolve(Result.success(Unit.unit()));

        awaitAck(acks);

        assertThat(acks).hasSize(1);
        assertThat(acks.getFirst().confirmedOffset()).isEqualTo(12L);
    }

    /// A FAILED sync withholds the ack: the record is applied and serveable, but this replica must not
    /// count toward the owner's durability barrier. Over-counting is the exact defect being closed.
    @Test
    void ack_isWithheld_whenTheDurabilityBarrierFails() {
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        ReplicationReceiveHandler.RecoveredAppender appender = (_, _, _, _, _) -> Result.success(0L);
        var handler = ReplicationReceiveHandler.replicationReceiveHandler(SELF,
                                                                          appender,
                                                                          (_, _) -> 10L,
                                                                          (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                                          (_, _) -> {},
                                                                          (_, _) -> Causes.cause("fsync failed").promise());

        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 10L, payloads(3), timestamps(3), Epoch.ZERO));

        assertThat(acks).as("a replica whose fsync failed must not be counted as a durable copy").isEmpty();
    }

    /// The ack rides the barrier promise's resolution, which may land on another thread — bounded wait,
    /// never a bare sleep.
    private static void awaitAck(java.util.List<ReplicationMessage.ReplicateAck> acks) {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);

        while (acks.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static final class TrackingAppender implements ReplicationReceiveHandler.RecoveredAppender {
        private long head = -1L; // empty ring: next-expected offset is 0

        @Override
        public Result<Long> appendRecovered(String streamName, int partition, byte[] payload, long timestamp, Epoch ownerEpoch) {
            head++;

            return Result.success(head);
        }

        long nextExpected(String streamName, int partition) {
            return head + 1;
        }
    }

    @Test
    void contiguousBatch_appliesAndAcks_whenFromOffsetMatchesLocalHead() {
        var appender = new TrackingAppender();
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var gapFires = new AtomicInteger(0);
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                appender::nextExpected,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        // Local head empty (next-expected 0); batch starts at 0 — contiguous.
        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 0L, payloads(3), timestamps(3), Epoch.ZERO));

        assertThat(acks).hasSize(1);
        assertThat(acks.getFirst().confirmedOffset()).isEqualTo(2L);
        assertThat(gapFires.get()).isZero();
        assertThat(appender.nextExpected(STREAM, PARTITION)).isEqualTo(3L);
    }

    @Test
    void gapAhead_droppedEarlierBatch_isRejected_triggersBackfill_noAppendNoAck() {
        var appender = new TrackingAppender();
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var gapFires = new AtomicInteger(0);
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                appender::nextExpected,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        // Local head empty (next-expected 0) but the batch starts at 5: offsets [0,4] were dropped.
        // Applying now would land owner-offset-5 at local offset 0 — divergence. Reject + repair.
        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 5L, payloads(3), timestamps(3), Epoch.ZERO));

        assertThat(acks).isEmpty();
        assertThat(gapFires.get()).isEqualTo(1);
        // Nothing applied — local head unchanged, no silent corruption.
        assertThat(appender.nextExpected(STREAM, PARTITION)).isEqualTo(0L);
    }

    @Test
    void reorderedThenInOrder_gapRejected_thenContiguousApplies() {
        var appender = new TrackingAppender();
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var gapFires = new AtomicInteger(0);
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                appender::nextExpected,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        // Reordered: the [3,5] batch arrives first while local head is empty (next-expected 0) — gap.
        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 3L, payloads(3), timestamps(3), Epoch.ZERO));
        assertThat(gapFires.get()).isEqualTo(1);
        assertThat(acks).isEmpty();

        // The missing prefix [0,2] arrives — contiguous, applies, acks 2.
        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 0L, payloads(3), timestamps(3), Epoch.ZERO));
        assertThat(acks).hasSize(1);
        assertThat(acks.getLast().confirmedOffset()).isEqualTo(2L);
        assertThat(appender.nextExpected(STREAM, PARTITION)).isEqualTo(3L);
    }

    @Test
    void duplicateBatch_fullyBelowLocalHead_reAcksWithoutAppendingOrRepairing() {
        var appender = new TrackingAppender();
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var gapFires = new AtomicInteger(0);
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                appender::nextExpected,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        // Apply [0,2] so local next-expected becomes 3.
        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 0L, payloads(3), timestamps(3), Epoch.ZERO));
        assertThat(appender.nextExpected(STREAM, PARTITION)).isEqualTo(3L);

        // Re-delivery of [0,2]: entirely below local head — nothing to append, no gap, re-ack the end.
        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 0L, payloads(3), timestamps(3), Epoch.ZERO));

        assertThat(gapFires.get()).isZero();
        assertThat(appender.nextExpected(STREAM, PARTITION)).isEqualTo(3L); // no extra appends
        assertThat(acks).hasSize(2);
        assertThat(acks.getLast().confirmedOffset()).isEqualTo(2L);
    }

    @Test
    void overlappingBatch_skipsAppliedPrefix_appliesTailOnly() {
        var appender = new TrackingAppender();
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var gapFires = new AtomicInteger(0);
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                appender::nextExpected,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        // Apply [0,2] → local next-expected 3.
        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 0L, payloads(3), timestamps(3), Epoch.ZERO));

        // Overlapping re-delivery [1,4]: prefix [1,2] already present (skip 2), tail [3,4] applies.
        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 1L, payloads(4), timestamps(4), Epoch.ZERO));

        assertThat(gapFires.get()).isZero();
        assertThat(appender.nextExpected(STREAM, PARTITION)).isEqualTo(5L); // applied 3 then 2 more
        assertThat(acks).hasSize(2);
        assertThat(acks.getLast().confirmedOffset()).isEqualTo(4L);
    }

    @Test
    void verifyingFactory_midBatchApplyFailure_acksContiguousPrefix_andTriggersBackfill() {
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var gapFires = new AtomicInteger(0);
        // Local next-expected fixed at 10; appender fails on the 3rd new append.
        var calls = new AtomicInteger(0);
        ReplicationReceiveHandler.RecoveredAppender appender =
                (_, _, _, _, _) -> calls.getAndIncrement() < 2 ? Result.success(0L) : TestError.APPEND_FAILED.result();
        ReplicationReceiveHandler.LocalHead localHead = (_, _) -> 10L;
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                localHead,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        // Contiguous from 10; only [10,11] land before the failure at index 2.
        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 10L, payloads(4), timestamps(4), Epoch.ZERO));

        assertThat(acks).hasSize(1);
        assertThat(acks.getFirst().confirmedOffset()).isEqualTo(11L);
        assertThat(gapFires.get()).isEqualTo(1);
    }

    private static List<byte[]> payloads(int count) {
        var list = new ArrayList<byte[]>(count);
        for (var i = 0; i < count; i++) {
            list.add(("p-" + i).getBytes());
        }
        return list;
    }

    private static List<Long> timestamps(int count) {
        var list = new ArrayList<Long>(count);
        for (var i = 0; i < count; i++) {
            list.add(1000L + i);
        }
        return list;
    }
}
