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

/// #1883 (owner rule): the in-sync set falling below the confirmation factor, and its return, are announced on the
/// TRANSITION and only then. A commit that leaves the condition unchanged announces nothing, so ISR churn can never
/// become an event storm, and the ordinary paths (a CF-1 stream, an ISR that stays at the factor, an owner move) raise
/// no alert.
class StreamIsrAnnouncerTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final StreamPartitionOwnershipKey KEY = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 3);

    @Test
    void isrFallsBelowTheFactor_isAnnouncedOnce_withTheFencedMembers() {
        var before = record(List.of(A, B), 4L);
        var after = before.withIsrAndFenced(List.of(A), List.of(B));

        var event = StreamIsrAnnouncer.transition(KEY, Option.some(before), after, 2).unwrap();

        assertThat(event).isInstanceOfSatisfying(OperationalEvent.StreamIsrBelowMinimum.class, below -> {
            assertThat(below.stream()).isEqualTo("orders");
            assertThat(below.partition()).isEqualTo(3);
            assertThat(below.isr()).containsExactly("node-a");
            assertThat(below.fenced()).containsExactly("node-b");
            assertThat(below.confirmationFactor()).isEqualTo(2);
        });
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(after), after.withFailoverRefused(true), 2).isEmpty())
            .as("a further commit that stays below the factor announces nothing")
            .isTrue();
    }

    @Test
    void isrReachesTheFactorAgain_isAnnouncedRestored_once() {
        var below = record(List.of(A), 5L);
        var restored = below.withIsr(List.of(A, B));

        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(below), restored, 2).unwrap())
            .isInstanceOf(OperationalEvent.StreamIsrRestored.class);
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(restored), restored.withIsr(List.of(A, B, C)), 2).isEmpty())
            .as("growing past the factor announces nothing")
            .isTrue();
    }

    /// The false-alert controls: none of the ordinary commits may raise anything.
    @Test
    void ordinaryCommits_announceNothing() {
        var healthy = record(List.of(A, B, C), 4L);

        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), healthy.withIsr(List.of(A, B)), 2).isEmpty())
            .as("shrink that stays at the factor").isTrue();
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), healthy.withIsr(List.of(A)), 1).isEmpty())
            .as("CF 1 requires no confirmation: no minimum to fall below").isTrue();
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), healthy.withIsr(List.of(A)), 0).isEmpty())
            .as("factor not known on this node").isTrue();
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), healthy.withIsrAndFenced(List.of(A, B, C), List.of()), 2).isEmpty())
            .as("a fence change that keeps the ISR").isTrue();
    }

    /// A record minted before #1730 carries no committed ISR (`isrVersion` 0): it is not "below" anything.
    @Test
    void recordWithoutACommittedIsr_isNeverBelow() {
        var legacy = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(A, Epoch.ZERO, 1L, HlcTimestamp.ZERO);

        assertThat(StreamIsrAnnouncer.transition(KEY, Option.none(), legacy, 2).isEmpty()).isTrue();
    }

    /// The first committed record of a partition starting below the factor is itself the transition into the condition.
    @Test
    void firstRecordBelowTheFactor_isAnnounced() {
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.none(), record(List.of(A), 1L), 2).unwrap())
            .isInstanceOf(OperationalEvent.StreamIsrBelowMinimum.class);
    }

    private static StreamPartitionOwnershipValue record(List<NodeId> isr, long isrVersion) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(A,
                                                                           Epoch.epoch(1L, 2L, 0L).withCounter(3L),
                                                                           3L,
                                                                           HlcTimestamp.ZERO,
                                                                           isr,
                                                                           isrVersion);
    }
}
