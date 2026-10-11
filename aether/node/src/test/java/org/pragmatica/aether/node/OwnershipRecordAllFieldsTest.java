// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1883 + #1873 merged: the ownership record carries PR-A's `fenced` and `failoverRefusalSeq` beside PR-C's `epochStarts` (with a
/// FOLDED `coversFrom`). Every field must survive the node codec, and every transformation must keep the fields it does not own,
/// so no path silently unfences a member, rewinds a refusal count, or forgets an epoch boundary.
class OwnershipRecordAllFieldsTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final NodeId OWNER = new NodeId("node-a");
    private static final NodeId GONE = new NodeId("node-x");
    private static final Epoch EPOCH = Epoch.epoch(1L, 2L, 9L);
    private static final EpochStart FOLDED = new EpochStart(Epoch.epoch(1L, 2L, 7L), 100L, Epoch.epoch(1L, 2L, 3L));
    private static final EpochStart EXACT = new EpochStart(EPOCH, 150L);

    @Test
    void everyFieldNonDefault_survivesTheNodeCodec() {
        var record = full();

        assertThat(record.fenced()).as("premise").containsExactly(GONE);
        assertThat(record.failoverRefusalSeq()).as("premise").isEqualTo(2L);
        assertThat(record.epochStarts()).as("premise").containsExactly(FOLDED, EXACT);

        var decoded = (StreamPartitionOwnershipValue) CODEC.decode(CODEC.encode(record));

        assertThat(decoded.fenced()).containsExactly(GONE);
        assertThat(decoded.failoverRefusalSeq()).isEqualTo(2L);
        assertThat(decoded.failoverRefused()).isTrue();
        assertThat(decoded.epochStarts()).containsExactly(FOLDED, EXACT);
        assertThat(decoded.epochStarts().getFirst().coversFrom()).isEqualTo(Epoch.epoch(1L, 2L, 3L));
        assertThat(decoded).isEqualTo(record);
    }

    @Test
    void withIsr_keepsFencedSeqAndStarts() {
        assertKept(full().withIsr(List.of(OWNER)), true);
    }

    @Test
    void withIsrAndFenced_keepsSeqAndStarts() {
        var next = full().withIsrAndFenced(List.of(OWNER), List.of(GONE));

        assertKept(next, true);
    }

    @Test
    void withFailoverRefusedFalse_keepsFencedSeqAndStarts() {
        assertKept(full().withFailoverRefused(false), false);
    }

    @Test
    void withEpochStart_keepsFencedAndSeq_andAppendsTheStart() {
        var next = full().withEpochStart(200L);

        assertThat(next.fenced()).containsExactly(GONE);
        assertThat(next.failoverRefusalSeq()).isEqualTo(2L);
        assertThat(next.failoverRefused()).isTrue();
        assertThat(next.epochStarts()).startsWith(FOLDED, EXACT);
    }

    @Test
    void restarted_keepsFenced_andTheFoldedStart_andRaisesTheTerm() {
        var before = full();
        var next = before.restarted(200L, HlcTimestamp.ZERO);

        assertThat(next.fenced()).containsExactly(GONE);
        assertThat(next.ownershipTerm()).as("a restart is a new term").isGreaterThan(before.ownershipTerm());
        assertThat(next.ownerEpoch()).isNotEqualTo(before.ownerEpoch());
        assertThat(next.epochStarts()).startsWith(FOLDED, EXACT);
    }

    /// PR-A's invariant: a record that is refused has counted at least one refusal. `restarted()` raises the term and the epoch
    /// (the event id includes both), so it must carry the count and the flag, not reset the count under a still-set flag.
    @Test
    void restarted_onARefusedRecord_keepsTheFlagAndTheRefusalCount() {
        var before = full();
        var next = before.restarted(200L, HlcTimestamp.ZERO);

        assertThat(next.failoverRefused()).as("the flag is unchanged").isTrue();
        assertThat(next.failoverRefusalSeq()).as("a refused record has counted a refusal").isEqualTo(2L).isGreaterThanOrEqualTo(1L);
        assertThat(next.ownershipTerm()).isGreaterThan(before.ownershipTerm());
        assertThat(next.ownerEpoch()).isNotEqualTo(before.ownerEpoch());
    }

    private static void assertKept(StreamPartitionOwnershipValue next, boolean refused) {
        assertThat(next.fenced()).as("fenced").containsExactly(GONE);
        assertThat(next.failoverRefusalSeq()).as("refusal count").isEqualTo(2L);
        assertThat(next.failoverRefused()).isEqualTo(refused);
        assertThat(next.epochStarts()).as("epoch starts").containsExactly(FOLDED, EXACT);
    }

    /// fenced {x}, refused twice (seq 2, refused now), epoch starts [folded (7, 100, from 3), exact (9, 150)].
    private static StreamPartitionOwnershipValue full() {
        return new StreamPartitionOwnershipValue(OWNER,
                                                 EPOCH,
                                                 9L,
                                                 HlcTimestamp.ZERO,
                                                 List.of(OWNER, new NodeId("node-b")),
                                                 7L,
                                                 false,
                                                 List.of(GONE),
                                                 0L,
                                                 List.of(FOLDED, EXACT))
               .withFailoverRefused(true)
               .withFailoverRefused(false)
               .withFailoverRefused(true);
    }
}
