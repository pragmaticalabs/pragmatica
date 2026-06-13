// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
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
        ReplicationReceiveHandler.RecoveredAppender appender = (_, _, _, _) -> Result.success(0L);
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 10L, payloads(3), timestamps(3)));

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
                (_, _, _, _) -> calls.getAndIncrement() < 2 ? Result.success(0L) : TestError.APPEND_FAILED.result();
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (stream, partition) -> {
                                                    gapStream.add(stream);
                                                    gapPartition.set(partition);
                                                });

        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 10L, payloads(4), timestamps(4)));

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
        ReplicationReceiveHandler.RecoveredAppender appender = (_, _, _, _) -> TestError.APPEND_FAILED.result();
        var handler = replicationReceiveHandler(SELF,
                                                appender,
                                                (target, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                (_, _) -> gapFires.incrementAndGet());

        handler.onReplicateEvents(replicateEvents(GOVERNOR, STREAM, PARTITION, 10L, payloads(2), timestamps(2)));

        // Nothing applied => no ack (would otherwise ack 10 + 0 - 1 = 9, below fromOffset).
        assertThat(acks).isEmpty();
        // Gap still surfaced so the replica repairs.
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
