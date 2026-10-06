// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CoreVoterReconcilerTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");
    private static final NodeId E = new NodeId("e");
    private static final VoterConfiguration CURRENT = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C)));

    @Test
    void replacesMissingVoterWithReadyCandidateButDoesNotInventCapacity() {
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, D), 3))
            .containsExactly(A, B, D);
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, D), 5)).isEmpty();
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, C, D), 3))
            .containsExactly(A, B, C);
    }

    @Test
    void settledRosterRequestsTheChangeOnceWhileItIsInFlight() {
        var calls = new AtomicInteger();
        var pending = Promise.<Unit>promise();
        var reconciler = CoreVoterReconciler.coreVoterReconciler(A, () -> true,
            () -> Option.some(CURRENT), () -> Option.some(CURRENT), () -> 3, () -> Set.of(A, B, D), Map::of,
            roster -> { calls.incrementAndGet(); return pending; });
        reconciler.reconcile();
        reconciler.reconcile();
        assertThat(calls.get()).isEqualTo(1);
        pending.succeed(Unit.unit());
    }

    @Test
    void unsettledRosterWaitsInsteadOfRequestingAnotherChange() {
        var calls = new AtomicInteger();
        var reconciler = CoreVoterReconciler.coreVoterReconciler(A, () -> true,
            () -> Option.some(CURRENT), Option::none, () -> 3, () -> Set.of(A, B, D), Map::of,
            roster -> { calls.incrementAndGet(); return Promise.unitPromise(); });
        reconciler.reconcile().await();
        assertThat(calls.get()).as("a pending change or an uncaught-up added member blocks the next one").isZero();
    }

    @Test
    void unchangedRosterRequestsNothing() {
        var calls = new AtomicInteger();
        var reconciler = CoreVoterReconciler.coreVoterReconciler(A, () -> true,
            () -> Option.some(CURRENT), () -> Option.some(CURRENT), () -> 3, () -> Set.of(A, B, C), Map::of,
            roster -> { calls.incrementAndGet(); return Promise.unitPromise(); });
        reconciler.reconcile().await();
        assertThat(calls.get()).isZero();
    }
    /// #1543 rule 2: a ready replacement paired with a voter displaces it in ONE change at 3 cores. Under the
    /// unpaired ranking a newcomer never displaces a healthy voter, so this is red without the swap.
    @Test
    void selectVoters_pairedReadyReplacement_swapsOriginalOutKeepingVoterCount() {
        var target = CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, C, D), 3, Map.of(C, D));

        assertThat(target).containsExactly(A, B, D);
        assertThat(target).hasSize(CURRENT.members().size());
    }

    @Test
    void reconcile_pairedReadyReplacement_requestsTheSwapAsOneReconfiguration() {
        var requested = new java.util.ArrayList<List<NodeId>>();
        var reconciler = CoreVoterReconciler.coreVoterReconciler(A, () -> true,
            () -> Option.some(CURRENT), () -> Option.some(CURRENT), () -> 3, () -> Set.of(A, B, C, D), () -> Map.of(C, D),
            roster -> { requested.add(roster.members()); return Promise.unitPromise(); });

        reconciler.reconcile().await();

        assertThat(requested).containsExactly(List.of(A, B, D));
    }

    /// Control: with no pairing the selection is the unpaired one for every scenario pinned above.
    @Test
    void selectVoters_withoutPairing_isTheUnpairedSelection() {
        for (var ready : List.of(Set.of(A, B, D), Set.of(A, B, C, D), Set.of(A, B, C), Set.of(A, C, D, E))) {
            assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, ready, 3, Map.of()))
                .isEqualTo(CoreVoterReconciler.selectVoters(A, CURRENT, ready, 3));
        }
    }

    @Test
    void selectVoters_pairedReplacementNotReady_keepsTheOriginal() {
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, C), 3, Map.of(C, D))).containsExactly(A, B, C);
    }

    /// A swap never rides along with a heal: with B unready the unpaired heal is the only change requested.
    @Test
    void selectVoters_swapNeverRidesAlongWithAHeal() {
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, C, D, E), 3, Map.of(C, E)))
            .isEqualTo(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, C, D, E), 3));
    }

    /// The leader's own seat is never swapped; the leader is replaced last, by drain (owner ruling Q3).
    @Test
    void selectVoters_pairingOnTheLeader_doesNotSwapItsSeat() {
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, C, D), 3, Map.of(A, D))).containsExactly(A, B, C);
    }

    @Test
    void selectVoters_twoPairings_swapOnlyOneSeatPerReconfiguration() {
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, C, D, E), 3, Map.of(B, D, C, E)))
            .containsExactly(A, D, C);
    }

    /// Five voters: the swap is still one seat and the count still five.
    @Test
    void selectVoters_fiveVoters_pairedSwapKeepsFive() {
        var five = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C, D, E)));
        var f = new NodeId("f");

        assertThat(CoreVoterReconciler.selectVoters(A, five, Set.of(A, B, C, D, E, f), 5, Map.of(D, f)))
            .containsExactly(A, B, C, f, E);
    }
}
