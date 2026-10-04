// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import static org.assertj.core.api.Assertions.assertThat;

class StreamPartitionOwnershipValueTest {
    @Test
    void streamPartitionOwnershipValue_withAllFields_populatesRecord() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var epoch = Epoch.epoch(0L, 5L, 12L);
        var hlc = new HlcTimestamp(200L, new NodeId("core-1"));

        var v = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, epoch, 7L, hlc);

        assertThat(v.owner()).isEqualTo(owner);
        assertThat(v.ownerEpoch()).isEqualTo(epoch);
        assertThat(v.ownershipTerm()).isEqualTo(7L);
        assertThat(v.transferredAt()).isEqualTo(hlc);
    }

    @Test
    void fenceEpoch_returnsOwnerEpoch() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var epoch = Epoch.epoch(0L, 9L, 3L);

        var v = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, epoch, 1L, HlcTimestamp.ZERO);

        assertThat(v.fenceEpoch())
            .as("the fence token is the owner epoch — the applier guard reads this")
            .isEqualTo(epoch);
    }

    @Test
    void construct_nullEpoch_normalizesToZero() {
        var owner = NodeId.nodeId("core-1").unwrap();

        var v = new StreamPartitionOwnershipValue(owner, null, 0L, HlcTimestamp.ZERO, null, 0L, false, null, null);

        assertThat(v.ownerEpoch()).isEqualTo(Epoch.ZERO);
    }

    @Test
    void construct_nullTransferredAt_normalizesToZero() {
        var owner = NodeId.nodeId("core-1").unwrap();

        var v = new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, null);

        assertThat(v.transferredAt()).isEqualTo(HlcTimestamp.ZERO);
    }

    /// #1730: a record without an ISR (null or empty) carries the owner alone, never an empty set an ack could be
    /// judged against.
    @Test
    void construct_missingIsr_normalizesToOwnerAlone() {
        var owner = NodeId.nodeId("core-1").unwrap();

        assertThat(new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, null).isr()).containsExactly(owner);
        assertThat(new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, java.util.List.of(), 0L, false, null, null).isr()).containsExactly(owner);
    }

    /// #1730: an ISR change keeps the ownership and advances only the ISR version.
    @Test
    void withIsr_keepsOwnership_andAdvancesTheVersion() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var peer = NodeId.nodeId("core-2").unwrap();
        var v = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.ZERO, 3L, HlcTimestamp.ZERO, java.util.List.of(owner), 4L);
        var next = v.withIsr(java.util.List.of(owner, peer));

        assertThat(next.isr()).containsExactly(owner, peer);
        assertThat(next.isrVersion()).isEqualTo(5L);
        assertThat(next.owner()).isEqualTo(owner);
        assertThat(next.ownershipTerm()).isEqualTo(3L);
        assertThat(next.ownerEpoch()).isEqualTo(v.ownerEpoch());
    }

    /// #1883: a record carries no fenced member unless the leader recorded one.
    @Test
    void construct_missingFenced_normalizesToEmpty() {
        var owner = NodeId.nodeId("core-1").unwrap();

        assertThat(new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, null).fenced()).isEmpty();
        assertThat(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.ZERO, 1L, HlcTimestamp.ZERO).fenced()).isEmpty();
    }

    /// #1883: the fenced set changes with the ISR in ONE versioned step, and the ownership is untouched.
    @Test
    void withIsrAndFenced_isOneVersionedStep_keepingOwnership() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var gone = NodeId.nodeId("core-2").unwrap();
        var v = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.ZERO, 3L, HlcTimestamp.ZERO, java.util.List.of(owner, gone), 4L);
        var next = v.withIsrAndFenced(java.util.List.of(owner), java.util.List.of(gone));

        assertThat(next.isr()).containsExactly(owner);
        assertThat(next.fenced()).containsExactly(gone);
        assertThat(next.isrVersion()).isEqualTo(5L);
        assertThat(next.owner()).isEqualTo(owner);
        assertThat(next.ownershipTerm()).isEqualTo(3L);
        assertThat(v.withIsr(java.util.List.of(owner)).fenced()).as("a plain ISR change keeps the fenced set").isEmpty();
        assertThat(next.withIsr(java.util.List.of(owner)).fenced()).containsExactly(gone);
    }

    /// #1883: the list is bounded and forgets the OLDEST member first, so a stale entry cannot grow a record forever.
    @Test
    void withIsrAndFenced_isBounded_keepingTheNewest() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var many = java.util.stream.IntStream.range(0, StreamPartitionOwnershipValue.FENCED_MAX + 3)
                                              .mapToObj(i -> NodeId.nodeId("gone-" + i).unwrap())
                                              .toList();
        var v = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.ZERO, 1L, HlcTimestamp.ZERO).withIsrAndFenced(java.util.List.of(owner), many);

        assertThat(v.fenced()).hasSize(StreamPartitionOwnershipValue.FENCED_MAX).isEqualTo(many.subList(3, many.size()));
    }

    /// #1730 phase 2: a record names no epoch start unless its owner recorded one.
    @Test
    void construct_missingEpochStarts_normalizesToEmpty() {
        var owner = NodeId.nodeId("core-1").unwrap();

        assertThat(new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, null).epochStarts()).isEmpty();
        assertThat(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.ZERO, 1L, HlcTimestamp.ZERO).epochStarts()).isEmpty();
    }

    @Test
    void withEpochStart_recordsTheCurrentEpochAtTheOffset_andIsIdempotent() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var epoch = Epoch.epoch(1L, 2L, 3L);
        var v = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, epoch, 3L, HlcTimestamp.ZERO, java.util.List.of(owner), 4L);
        var started = v.withEpochStart(10L);

        assertThat(started.epochStarts()).containsExactly(new AetherValue.EpochStart(epoch, 10L));
        assertThat(started.lastEpochStart().unwrap()).isEqualTo(new AetherValue.EpochStart(epoch, 10L));
        assertThat(started.isrVersion()).as("a recorded start is a change of the record").isEqualTo(5L);
        assertThat(started.withEpochStart(10L)).as("the same start again changes nothing").isSameAs(started);
    }

    /// The owner that restarted its ring takes the next ownership term and the epoch it implies, and begins it at the
    /// offset it resumed from; the ISR and the fence are untouched.
    @Test
    void restarted_takesTheNextTerm_andBeginsTheNewEpochAtTheResumeOffset() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var peer = NodeId.nodeId("core-2").unwrap();
        var epoch = Epoch.epoch(1L, 2L, 3L);
        var v = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, epoch, 3L, HlcTimestamp.ZERO, java.util.List.of(owner, peer), 4L).withEpochStart(7L);
        var next = v.restarted(3L, HlcTimestamp.ZERO);

        assertThat(next.owner()).isEqualTo(owner);
        assertThat(next.ownershipTerm()).isEqualTo(4L);
        assertThat(next.ownerEpoch()).isEqualTo(epoch.withCounter(4L)).isNotEqualTo(epoch);
        assertThat(next.epochStarts()).as("the new epoch re-assigns offsets from 3, so the start at 7 is superseded")
                                      .containsExactly(new AetherValue.EpochStart(epoch.withCounter(4L), 3L));
        assertThat(next.isr()).containsExactly(owner, peer);
    }

    /// #1873, re-create: a rebuilt ring that begins at or below an earlier start re-assigns those offsets, so the starts at or
    /// above it are superseded and dropped; the record's starts stay increasing in offset.
    @Test
    void restarted_atOrBelowEarlierStarts_dropsTheSupersededOnes() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var e1 = Epoch.epoch(1L, 2L, 1L);
        var v = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, e1, 1L, HlcTimestamp.ZERO, java.util.List.of(owner), 1L).withEpochStart(0L);
        var second = v.restarted(3L, HlcTimestamp.ZERO);
        var third = second.restarted(0L, HlcTimestamp.ZERO);

        assertThat(second.epochStarts()).containsExactly(new AetherValue.EpochStart(e1, 0L), new AetherValue.EpochStart(e1.withCounter(2L), 3L));
        assertThat(third.epochStarts()).as("a new life begins at offset 0: nothing before it survives")
                                       .containsExactly(new AetherValue.EpochStart(e1.withCounter(3L), 0L));
    }

    /// Bounded, newest kept, and the dropped starts are FOLDED (C2, v1873 round 2): the oldest kept start takes the lowest
    /// dropped offset, so a consumer older than the history is checked against a bound at or below every offset a dropped epoch
    /// may have re-assigned. Keeping the oldest kept start's own offset (30 here) would let such a consumer be admitted at a
    /// cursor of 25 and skip records re-assigned from offset 0.
    @Test
    void epochStarts_areBounded_keepingTheNewest_andFoldingTheLowestDroppedOffsetIntoTheOldestKept() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var starts = java.util.stream.IntStream.range(0, StreamPartitionOwnershipValue.EPOCH_STARTS_MAX + 3)
                                               .mapToObj(i -> new AetherValue.EpochStart(Epoch.epoch(1L, 2L, i), i * 10L))
                                               .toList();
        var v = new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, starts);

        assertThat(v.epochStarts()).hasSize(StreamPartitionOwnershipValue.EPOCH_STARTS_MAX);
        assertThat(v.epochStarts().getFirst()).as("the oldest kept epoch, at the lowest dropped offset")
                                              .isEqualTo(new AetherValue.EpochStart(starts.get(3).epoch(), 0L, starts.get(0).epoch()));
        assertThat(v.epochStarts().subList(1, v.epochStarts().size())).as("the rest is untouched")
                                                                      .isEqualTo(starts.subList(4, starts.size()));
    }

    /// A re-fold keeps the OLDEST dropped epoch: the folded entry that is itself dropped by a later cap hands its `coversFrom` on,
    /// so what the entry asserts exactly (that epoch began at that offset) never moves to a newer epoch.
    @Test
    void refold_keepsTheOldestDroppedEpoch() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var starts = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, StreamPartitionOwnershipValue.EPOCH_STARTS_MAX + 2)
                                                                           .mapToObj(i -> new AetherValue.EpochStart(Epoch.epoch(1L, 2L, i), i * 10L))
                                                                           .toList());
        var once = new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, starts);

        starts = new java.util.ArrayList<>(once.epochStarts());
        starts.add(new AetherValue.EpochStart(Epoch.epoch(1L, 2L, 99L), 999L));
        starts.add(new AetherValue.EpochStart(Epoch.epoch(1L, 2L, 100L), 1_000L));

        var twice = new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, starts);

        assertThat(twice.epochStarts()).hasSize(StreamPartitionOwnershipValue.EPOCH_STARTS_MAX);
        assertThat(twice.epochStarts().getFirst().coversFrom()).as("still the oldest dropped epoch of the FIRST fold").isEqualTo(Epoch.epoch(1L, 2L, 0L));
        assertThat(twice.epochStarts().getFirst().startOffset()).isZero();
        assertThat(twice.epochStarts().stream().skip(1)).as("only the oldest entry is ever folded").allMatch(start -> start.coversFrom().equals(start.epoch()));
    }

    /// Under the cap nothing is folded.
    @Test
    void epochStarts_underTheBound_areKeptAsGiven() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var starts = java.util.stream.IntStream.range(0, StreamPartitionOwnershipValue.EPOCH_STARTS_MAX)
                                               .mapToObj(i -> new AetherValue.EpochStart(Epoch.epoch(1L, 2L, i), 100L + i * 10L))
                                               .toList();

        assertThat(new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, starts).epochStarts())
            .isEqualTo(starts);
    }
}
