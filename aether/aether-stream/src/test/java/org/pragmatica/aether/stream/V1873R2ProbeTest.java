// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// v1873 round 2 (421a4d271): `boundaryKnown=false` (INFO, no `stream-consumer-rewound`) is given to EVERY consumer whose epoch
/// precedes the oldest kept start, not only to one older than a FOLDED (capped) history. When the oldest start superseded the
/// consumer's epoch (a no-WAL restart with nothing sealed begins at 0, a re-created stream begins at 0), that start is a real
/// start of an epoch AFTER the consumer's, so a loss of [start, cursor) is proven, and the loss witness must be raised.
class V1873R2ProbeTest {
    private static final NodeId SELF = new NodeId("self");
    private static final Epoch E1 = Epoch.epoch(1L, 2L, 1L);

    /// No-WAL owner, nothing sealed yet (floor -1, the common dev stream): the consumer read 0..4 under E1, the owner restarted and
    /// began E2 at 0 (superseding (E1,0)). Records 0..4 the group processed are gone: the boundary is exact.
    @Test
    void nothingSealedRestart_lossIsProven_soTheBoundaryIsKnown() {
        var record = recordAt(E1).withEpochStart(0L).restarted(0L, HlcTimestamp.ZERO);

        assertThat(record.epochStarts()).as("premise: the restart at 0 superseded E1's start").hasSize(1);
        var diverged = divergence(EpochValidation.admit("s", 0, record, E1, 5L));

        assertThat(diverged.resumeAt()).isZero();
        assertThat(diverged.lossProven()).as("E2 began at 0 after E1: records 0..4 of E1 are proven gone, so WARN, not INFO").isTrue();
        assertThat(diverged.provenLossFrom()).isZero();
    }

    /// A failover began E1 at 10; the consumer read 10..14; the owner restarted without a WAL at floor 4 (start 5), superseding (E1,10).
    @Test
    void restartBelowTheConsumersOwnEpochStart_lossIsProven() {
        var record = recordAt(E1).withEpochStart(10L).restarted(5L, HlcTimestamp.ZERO);
        var diverged = divergence(EpochValidation.admit("s", 0, record, E1, 15L));

        assertThat(diverged.resumeAt()).isEqualTo(5L);
        assertThat(diverged.lossProven()).isTrue();
    }

    /// Control: the same loss with E1's start still kept (the restart began above it) is exact today.
    @Test
    void control_restartAboveTheConsumersEpochStart_isExact() {
        var record = recordAt(E1).withEpochStart(0L).restarted(3L, HlcTimestamp.ZERO);
        var diverged = divergence(EpochValidation.admit("s", 0, record, E1, 5L));

        assertThat(diverged.resumeAt()).isEqualTo(3L);
        assertThat(diverged.lossProven()).isTrue();
    }

    /// Control: a FOLDED history (cap reached) for a consumer between the dropped and the kept epochs is honestly not exact.
    @Test
    void control_foldedHistory_isInexactBelowTheNextExactStart_andProvenAbove() {
        var starts = java.util.stream.IntStream.range(0, 17).mapToObj(i -> new EpochStart(Epoch.epoch(1L, 2L, 10L + i), 100L + i * 10L)).toList();
        var record = new StreamPartitionOwnershipValue(SELF, starts.getLast().epoch(), 26L, HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(), starts);
        var diverged = divergence(EpochValidation.admit("s", 0, record, Epoch.epoch(1L, 2L, 10L), 115L));

        assertThat(diverged.resumeAt()).isEqualTo(100L);
        assertThat(diverged.lossProven()).as("only the folded entry lies below 115").isFalse();

        var above = divergence(EpochValidation.admit("s", 0, record, Epoch.epoch(1L, 2L, 10L), 250L));

        assertThat(above.provenLossFrom()).as("(e12 at 120) is an exact start after e10 and below 250: proven, resume stays 100").isEqualTo(120L);
    }

    private static StreamError.EpochDiverged divergence(Result<Epoch> result) {
        var holder = new StreamError.EpochDiverged[1];

        result.onFailure(cause -> holder[0] = (StreamError.EpochDiverged) cause);
        assertThat(holder[0]).as("expected a divergence, got %s", result).isNotNull();

        return holder[0];
    }

    private static StreamPartitionOwnershipValue recordAt(Epoch epoch) {
        return new StreamPartitionOwnershipValue(SELF, epoch, epoch.localCounter(), HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(), List.of());
    }
}
