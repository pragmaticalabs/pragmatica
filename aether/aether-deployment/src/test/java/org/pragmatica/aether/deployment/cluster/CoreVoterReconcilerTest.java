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

    private static final VoterConfiguration FIVE = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C, D, E)));
    private static final NodeId F = new NodeId("f");

    private static CoreVoterReconciler fiveVoterLeader(Set<NodeId> ready, Set<NodeId> failed, Map<NodeId, NodeId> swaps, List<List<NodeId>> requested) {
        return CoreVoterReconciler.coreVoterReconciler(A, () -> true, () -> Option.some(FIVE), () -> Option.some(FIVE), () -> 5,
            () -> ready, () -> swaps, () -> failed, roster -> { requested.add(roster.members()); return Promise.unitPromise(); });
    }

    /// #2098: a leader that has just taken over has observed readiness for itself and the new replacement only. An
    /// unobserved healthy voter must not be dropped for the replacement: unknown is not unhealthy. Red at rc4: the
    /// unpaired heal drops the unobserved voter E for F, and the swap never gets to choose its leaver.
    @Test
    void freshLeader_unobservedHealthyVoter_isNotDropped_andTheSwapLeaverIsTheOriginal() {
        var requested = new java.util.ArrayList<List<NodeId>>();

        fiveVoterLeader(Set.of(A, B, C, F), Set.of(), Map.of(D, F), requested).reconcile().await();

        assertThat(requested).containsExactly(List.of(A, B, C, F, E));
    }

    /// Time does not turn unobserved into removable: however many ticks pass, a voter with no evidence of failure is kept.
    @Test
    void unobservedVoter_withoutEvidence_survivesEveryTick() {
        var requested = new java.util.ArrayList<List<NodeId>>();
        var reconciler = fiveVoterLeader(Set.of(A, B, C, F), Set.of(), Map.of(), requested);

        for (var tick = 0; tick < 50; tick++) {
            reconciler.reconcile().await();
        }

        assertThat(requested).isEmpty();
    }

    /// Positive evidence (observed non-READY or a Dead/Departing verdict) makes the voter healable: D is replaced, E is kept.
    @Test
    void voterWithEvidenceOfFailure_isHealed_whileTheUnobservedOneIsKept() {
        var requested = new java.util.ArrayList<List<NodeId>>();

        fiveVoterLeader(Set.of(A, B, C, F), Set.of(D), Map.of(), requested).reconcile().await();

        assertThat(requested).hasSize(1);
        assertThat(requested.getFirst()).containsExactlyInAnyOrder(A, B, C, E, F);
    }

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
            () -> Option.some(CURRENT), () -> Option.some(CURRENT), () -> 3, () -> Set.of(A, B, D), Map::of, () -> Set.of(C),
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
            () -> Option.some(CURRENT), Option::none, () -> 3, () -> Set.of(A, B, D), Map::of, Set::of,
            roster -> { calls.incrementAndGet(); return Promise.unitPromise(); });
        reconciler.reconcile().await();
        assertThat(calls.get()).as("a pending change or an uncaught-up added member blocks the next one").isZero();
    }

    @Test
    void unchangedRosterRequestsNothing() {
        var calls = new AtomicInteger();
        var reconciler = CoreVoterReconciler.coreVoterReconciler(A, () -> true,
            () -> Option.some(CURRENT), () -> Option.some(CURRENT), () -> 3, () -> Set.of(A, B, C), Map::of, Set::of,
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
            () -> Option.some(CURRENT), () -> Option.some(CURRENT), () -> 3, () -> Set.of(A, B, C, D), () -> Map.of(C, D), Set::of,
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

    /// #1543 E: the leader's own seat IS swapped when its pairing authorizes it, one out and one in, so the electorate
    /// stays three. (At minimum topology the budget refuses draining a voter, so this is how the old leader leaves.)
    @Test
    void selectVoters_pairingOnTheLeader_swapsItsSeat_electorateSizeUnchanged() {
        var target = CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, C, D), 3, Map.of(A, D));

        assertThat(target).containsExactly(D, B, C);
        assertThat(target).hasSize(CURRENT.members().size());
    }

    /// Control: WITHOUT a pairing the leader is never displaced, so a heal can never remove the leader's seat.
    @Test
    void selectVoters_withoutAPairing_theLeaderKeepsItsSeat() {
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, C, D), 3, Map.of())).containsExactly(A, B, C);
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

    private static List<List<NodeId>> requestsOf(VoterConfiguration roster, int desired, Set<NodeId> ready, Set<NodeId> failed, Map<NodeId, NodeId> swaps) {
        var requested = new java.util.ArrayList<List<NodeId>>();

        CoreVoterReconciler.coreVoterReconciler(A, () -> true, () -> Option.some(roster), () -> Option.some(roster), () -> desired,
            () -> ready, () -> swaps, () -> failed, config -> { requested.add(config.members()); return Promise.unitPromise(); }).reconcile().await();

        return requested;
    }

    private static final NodeId G = new NodeId("g");
    private static final VoterConfiguration SEVEN = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C, D, E, F, G)));

    /// F2: a Dead or Departing voter that still has a READY entry (stale) is healed: `ready - failed`.
    @Test
    void deadVoterWithAStaleReadyEntry_isHealed() {
        var requested = requestsOf(FIVE, 5, Set.of(A, B, C, D, F), Set.of(D), Map.of());

        assertThat(requested).hasSize(1);
        assertThat(requested.getFirst()).containsExactlyInAnyOrder(A, B, C, E, F);
    }

    /// F3: a surplus trim (7 voters, desired 5) is a size reduction. Failed voters go first.
    @Test
    void surplusTrim_removesTheFailedVoterFirst() {
        var requested = requestsOf(SEVEN, 5, Set.of(A, B, C, D), Set.of(G), Map.of());

        assertThat(requested).hasSize(1);
        assertThat(requested.getFirst()).containsExactlyInAnyOrder(A, B, C, D, E);
    }

    /// F3: then never-observed voters, before any observed-healthy one, whatever the ids: the healthy F and G stay, the unobserved D and E go.
    @Test
    void surplusTrim_removesNeverObservedVotersBeforeObservedHealthyOnes() {
        var requested = requestsOf(SEVEN, 5, Set.of(A, F, G), Set.of(), Map.of());

        assertThat(requested).hasSize(1);
        assertThat(requested.getFirst()).containsExactlyInAnyOrder(A, B, C, F, G);
    }

    /// F3: id order is only the final tie-break among equals.
    @Test
    void surplusTrim_amongEquals_breaksTiesById() {
        var requested = requestsOf(SEVEN, 5, Set.of(A, B, C, D, E, F, G), Set.of(), Map.of());

        assertThat(requested.getFirst()).containsExactlyInAnyOrder(A, B, C, D, E);
    }

    /// F3: never while a swap is in progress (Mode 1's trigger); control: the same trim without a swap goes through.
    @Test
    void surplusTrim_isRefusedWhileASwapIsInProgress() {
        assertThat(requestsOf(SEVEN, 5, Set.of(A, B, C, D, E, F), Set.of(G), Map.of(F, new NodeId("h")))).isEmpty();
        assertThat(requestsOf(SEVEN, 5, Set.of(A, B, C, D, E, F), Set.of(G), Map.of())).hasSize(1);
    }

    /// The guard is on SHRINKING only: replacing a failed voter keeps the size, so it proceeds even while a swap is in progress.
    @Test
    void healOfAFailedVoter_isNotATrim_soItProceedsDuringASwap() {
        var requested = requestsOf(FIVE, 5, Set.of(A, B, C, E, F), Set.of(D), Map.of(B, new NodeId("h")));

        assertThat(requested).hasSize(1);
        assertThat(requested.getFirst()).containsExactlyInAnyOrder(A, B, C, E, F);
    }
}
