// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1873 (owner rule): a ring rebuilt under an UNCHANGED owner is announced once, on the commit that advances the epoch and
/// records its start; a failover (the owner changes), an ISR or fence change, the owner committing the start of an epoch it
/// already holds, and the first record all announce nothing, so the event cannot be raised by ordinary traffic.
class StreamLineageAnnouncerTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final StreamPartitionOwnershipKey KEY = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 3);
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);

    @Test
    void ownerRestartsItsRing_isAnnouncedOnce_withTheStartOfTheNewEpoch() {
        var before = record(A, List.of(A, B)).withEpochStart(5L);
        var after = before.restarted(3L, HlcTimestamp.ZERO);

        var event = StreamLineageAnnouncer.transition(KEY, Option.some(before), after).unwrap();

        assertThat(event).isInstanceOfSatisfying(OperationalEvent.StreamLineageRestarted.class, restarted -> {
            assertThat(restarted.stream()).isEqualTo("orders");
            assertThat(restarted.partition()).isEqualTo(3);
            assertThat(restarted.owner()).isEqualTo("node-a");
            assertThat(restarted.oldEpoch()).isEqualTo(E1.toString());
            assertThat(restarted.newEpoch()).isEqualTo(after.ownerEpoch().toString());
            assertThat(restarted.startOffset()).isEqualTo(3L);
        });
        assertThat(StreamLineageAnnouncer.transition(KEY, Option.some(after), after.withIsr(List.of(A))).isEmpty())
            .as("a later commit that keeps the epoch announces nothing")
            .isTrue();
    }

    @Test
    void failoverToAnotherOwner_isNotALineageRestart() {
        var before = record(A, List.of(A, B)).withEpochStart(5L);
        var failedOver = new StreamPartitionOwnershipValue(B, E1.withCounter(2L), 2L, HlcTimestamp.ZERO, List.of(B), before.isrVersion() + 1, false, List.of(), before.epochStarts());

        assertThat(StreamLineageAnnouncer.transition(KEY, Option.some(before), failedOver).isEmpty()).isTrue();
        assertThat(StreamLineageAnnouncer.transition(KEY, Option.some(before), failedOver.withEpochStart(2L)).isEmpty())
            .as("even when the new owner's start is recorded with the move, an owner change is the failover event's business")
            .isTrue();
    }

    @Test
    void ownerCommittingTheStartOfItsOwnEpoch_orTheFirstRecord_announceNothing() {
        var minted = record(A, List.of(A, B));

        assertThat(StreamLineageAnnouncer.transition(KEY, Option.none(), minted.withEpochStart(0L)).isEmpty()).as("first record").isTrue();
        assertThat(StreamLineageAnnouncer.transition(KEY, Option.some(minted), minted.withEpochStart(0L)).isEmpty()).as("same epoch, start committed").isTrue();
    }

    @Test
    void anEpochAdvanceWithoutAStartForTheNewEpoch_isNotAnnouncedHere() {
        var before = record(A, List.of(A, B)).withEpochStart(5L);
        var bumpedByTheLeader = new StreamPartitionOwnershipValue(A, E1.withCounter(2L), 2L, HlcTimestamp.ZERO, List.of(A, B), before.isrVersion() + 1, false, List.of(), before.epochStarts());

        assertThat(StreamLineageAnnouncer.transition(KEY, Option.some(before), bumpedByTheLeader).isEmpty()).isTrue();
    }

    private static StreamPartitionOwnershipValue record(NodeId owner, List<NodeId> isr) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, E1, 1L, HlcTimestamp.ZERO, isr, 1L);
    }
}
