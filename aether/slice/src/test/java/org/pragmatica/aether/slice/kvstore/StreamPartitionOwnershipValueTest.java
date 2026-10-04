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

        var v = new StreamPartitionOwnershipValue(owner, null, 0L, HlcTimestamp.ZERO, null, 0L, false, null, 0L);

        assertThat(v.ownerEpoch()).isEqualTo(Epoch.ZERO);
    }

    @Test
    void construct_nullTransferredAt_normalizesToZero() {
        var owner = NodeId.nodeId("core-1").unwrap();

        var v = new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, 0L);

        assertThat(v.transferredAt()).isEqualTo(HlcTimestamp.ZERO);
    }

    /// #1730: a record without an ISR (null or empty) carries the owner alone, never an empty set an ack could be
    /// judged against.
    @Test
    void construct_missingIsr_normalizesToOwnerAlone() {
        var owner = NodeId.nodeId("core-1").unwrap();

        assertThat(new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, 0L).isr()).containsExactly(owner);
        assertThat(new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, java.util.List.of(), 0L, false, null, 0L).isr()).containsExactly(owner);
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

        assertThat(new StreamPartitionOwnershipValue(owner, Epoch.ZERO, 0L, null, null, 0L, false, null, 0L).fenced()).isEmpty();
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

    /// The refusal count grows on each transition INTO refused, only; it is what makes a recurring refusal a new event.
    @Test
    void failoverRefusalSeq_countsTransitionsIntoRefused_only() {
        var owner = new NodeId("owner");
        var v = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.ZERO, 1L, HlcTimestamp.ZERO);
        var refused = v.withFailoverRefused(true);

        assertThat(v.failoverRefusalSeq()).isZero();
        assertThat(refused.failoverRefusalSeq()).isEqualTo(1L);
        assertThat(refused.withFailoverRefused(true).failoverRefusalSeq()).as("already refused: not a new transition").isEqualTo(1L);
        assertThat(refused.withFailoverRefused(false).failoverRefusalSeq()).as("resolving keeps the count").isEqualTo(1L);
        assertThat(refused.withFailoverRefused(false).withFailoverRefused(true).failoverRefusalSeq()).isEqualTo(2L);
        assertThat(refused.withIsr(java.util.List.of(owner)).failoverRefusalSeq()).as("an ISR change carries it").isEqualTo(1L);
        assertThat(refused.withIsrAndFenced(java.util.List.of(owner), java.util.List.of()).failoverRefusalSeq()).isEqualTo(1L);
    }
}
