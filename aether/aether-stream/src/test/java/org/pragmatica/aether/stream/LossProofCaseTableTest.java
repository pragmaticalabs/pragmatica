// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1873 R2-1 (s31/r2-1-joint.md): `stream-consumer-rewound` (WARN, `provenLossFrom`) is raised exactly when the record PROVES a loss:
/// some entry began at `S < cursor` and every epoch it stands for followed the consumer's. One test per row of the case table; the
/// records are built through the record's own operations wherever a real sequence can produce them.
class LossProofCaseTableTest {
    private static final NodeId SELF = new NodeId("self");

    private static Epoch e(long counter) {
        return Epoch.epoch(1L, 1L, counter);
    }

    /// C1: the consumer's epoch is kept; the epoch after it began at 3 < 5.
    @Test
    void c1_withinTheHistory_exactBoundary_warns() {
        var record = owned(e(1)).withEpochStart(0L).restarted(3L, HlcTimestamp.ZERO);

        assertDiverged(record, e(1), 5L, 3L, true);
    }

    /// C2: no WAL, nothing sealed: the restart begins at 0 and supersedes E1's start. Records 0..4 are gone: MUST warn.
    @Test
    void c2_nothingSealedRestart_supersedesEverything_warns() {
        var record = owned(e(1)).withEpochStart(0L).restarted(0L, HlcTimestamp.ZERO);

        assertThat(record.epochStarts()).hasSize(1);
        assertDiverged(record, e(1), 5L, 0L, true);
    }

    /// C3: a failover began E1 at 10; the consumer read 10..14; a restart began at 5 and superseded (E1, 10).
    @Test
    void c3_restartBelowTheConsumersOwnEpochStart_warns() {
        var record = owned(e(1)).withEpochStart(10L).restarted(5L, HlcTimestamp.ZERO);

        assertDiverged(record, e(1), 15L, 5L, true);
    }

    /// C4: a re-created stream: the new life begins at 0, the earlier life's consumer read to 60. Its records are gone.
    @Test
    void c4_recreatedStream_warns() {
        var record = owned(e(1)).withEpochStart(0L).restarted(40L, HlcTimestamp.ZERO).restarted(0L, HlcTimestamp.ZERO);

        assertDiverged(record, e(2), 60L, 0L, true);
    }

    /// C5 (f-p2c's counterexample): 21 lineages fold to 16 (folded first entry: epoch 6, start 0, standing for 1..6), then a restart
    /// at 60 leaves 2 entries. A consumer of epoch 3 at cursor 25 lost nothing (epochs 4..6 began at 30, 40, 50; epoch 22 at 60).
    @Test
    void c5_foldedEntry_consumerBetweenTheFoldedEpochs_noLoss_info() {
        var record = foldedThenRestartedAt(60L);

        assertThat(record.epochStarts()).as("premise: two entries, the oldest folded").hasSize(2);
        assertThat(record.epochStarts().getFirst()).isEqualTo(new EpochStart(e(6), 0L, e(1)));
        assertDiverged(record, e(3), 25L, 0L, false);
    }

    /// C6: the same fold, but the restart began at 5 < 25: epoch 22 followed epoch 3 and began below the cursor. Proven.
    @Test
    void c6_foldedEntry_plusAnExactLaterStartBelowTheCursor_warns() {
        var record = foldedThenRestartedAt(5L);

        assertDiverged(record, e(3), 25L, 0L, true);
    }

    /// C7: 16 exact entries (epochs 10..25 at 100..250), consumer of epoch 1 at 250: epoch 11 began at 110 < 250. Proven.
    @Test
    void c7_fullUnfoldedHistory_consumerOlderThanAll_warns() {
        var record = withStarts(IntStream.range(0, 16).mapToObj(i -> new EpochStart(e(10 + i), 100L + i * 10L)).toList());

        assertDiverged(record, e(1), 250L, 100L, true);
    }

    /// C8: 17 starts fold (entry: epoch 11, start 100, standing for 10..11); a consumer older than every folded epoch at 105: proven.
    @Test
    void c8_foldedEntry_consumerOlderThanEveryFoldedEpoch_warns() {
        var record = withStarts(IntStream.range(0, 17).mapToObj(i -> new EpochStart(e(10 + i), 100L + i * 10L)).toList());

        assertThat(record.epochStarts().getFirst()).isEqualTo(new EpochStart(e(11), 100L, e(10)));
        assertDiverged(record, e(1), 105L, 100L, true);
    }

    /// C9: a consumer at or below the oldest start holds nothing any later epoch re-assigned: admitted, no rewind.
    @Test
    void c9_consumerBelowEveryStart_isAdmitted() {
        var record = withStarts(IntStream.range(0, 17).mapToObj(i -> new EpochStart(e(10 + i), 100L + i * 10L)).toList());

        assertThat(EpochValidation.admit("s", 0, record, e(1), 90L).isSuccess()).isTrue();
    }

    /// C10: an ordinary CF>=2 failover begins at or above the consumer's cursor: admitted, never a warning.
    @Test
    void c10_ordinaryFailover_atOrAboveTheCursor_isAdmitted() {
        var record = owned(e(1)).withEpochStart(0L).restarted(10L, HlcTimestamp.ZERO);

        assertThat(EpochValidation.admit("s", 0, record, e(1), 10L).isSuccess()).isTrue();
    }

    private static StreamPartitionOwnershipValue foldedThenRestartedAt(long restartAt) {
        var record = owned(e(1)).withEpochStart(0L);

        for (var i = 1; i <= 20; i++) {
            record = record.restarted(i * 10L, HlcTimestamp.ZERO);
        }

        return record.restarted(restartAt, HlcTimestamp.ZERO);
    }

    private static void assertDiverged(StreamPartitionOwnershipValue record, Epoch consumer, long cursor, long resumeAt, boolean warn) {
        Result<Epoch> result = EpochValidation.admit("s", 0, record, consumer, cursor);
        var holder = new ArrayList<StreamError.EpochDiverged>();

        result.onFailure(cause -> holder.add((StreamError.EpochDiverged) cause));
        assertThat(holder).as("expected a divergence, got %s", result).hasSize(1);
        assertThat(holder.getFirst().resumeAt()).isEqualTo(resumeAt);
        assertThat(holder.getFirst().lossProven()).as("WARN (a proven loss) for %s", record.epochStarts()).isEqualTo(warn);
    }

    private static StreamPartitionOwnershipValue owned(Epoch epoch) {
        return new StreamPartitionOwnershipValue(SELF, epoch, epoch.localCounter(), HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(), List.of());
    }

    private static StreamPartitionOwnershipValue withStarts(List<EpochStart> starts) {
        var last = starts.getLast().epoch();

        return new StreamPartitionOwnershipValue(SELF, last, last.localCounter(), HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(), starts);
    }
}
