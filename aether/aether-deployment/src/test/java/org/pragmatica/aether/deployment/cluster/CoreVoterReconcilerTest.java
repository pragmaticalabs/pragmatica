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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class CoreVoterReconcilerTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");
    private static final NodeId E = new NodeId("e");
    private static final VoterConfiguration CURRENT = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C)));

    /// A clock that has moved a whole observation grace between the leader's first tick and the selection, so the
    /// selection sees the leader as long-observed (the pre-#2098 behaviour these tests pin).
    private static LongSupplier pastGraceClock() {
        var now = new AtomicLong(-CoreVoterReconciler.OBSERVATION_GRACE_NANOS);

        return () -> now.addAndGet(CoreVoterReconciler.OBSERVATION_GRACE_NANOS);
    }

    private static final VoterConfiguration FIVE = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C, D, E)));
    private static final NodeId F = new NodeId("f");

    private static CoreVoterReconciler freshLeader(Set<NodeId> ready, Map<NodeId, NodeId> swaps, AtomicLong clock, List<List<NodeId>> requested) {
        return CoreVoterReconciler.coreVoterReconciler(A, () -> true, () -> Option.some(FIVE), () -> Option.some(FIVE), () -> 5,
            () -> ready, () -> swaps, roster -> { requested.add(roster.members()); return Promise.unitPromise(); }, clock::get);
    }

    /// #2098: a leader that has just taken over has observed readiness for itself and the new replacement only. An
    /// unobserved healthy voter must not be dropped for the replacement: unknown is not unhealthy. Red before the grace:
    /// the unpaired heal drops the unobserved voter E for F, and the swap never gets to choose its leaver.
    @Test
    void freshLeader_unobservedHealthyVoter_isNotDropped_andTheSwapLeaverIsTheOriginal() {
        var requested = new java.util.ArrayList<List<NodeId>>();
        var clock = new AtomicLong();
        var reconciler = freshLeader(Set.of(A, B, C, F), Map.of(D, F), clock, requested);

        reconciler.reconcile().await();

        assertThat(requested).containsExactly(List.of(A, B, C, F, E));
    }

    @Test
    void freshLeader_unobservedHealthyVoter_noPairing_requestsNothing() {
        var requested = new java.util.ArrayList<List<NodeId>>();
        var reconciler = freshLeader(Set.of(A, B, C, F), Map.of(), new AtomicLong(), requested);

        reconciler.reconcile().await();

        assertThat(requested).isEmpty();
    }

    /// Bounded: a voter still unobserved after the grace is a dead voter and is healed as before.
    @Test
    void unobservedVoter_afterTheGrace_isHealed() {
        var requested = new java.util.ArrayList<List<NodeId>>();
        var clock = new AtomicLong();
        var reconciler = freshLeader(Set.of(A, B, C, F), Map.of(), clock, requested);

        reconciler.reconcile().await();
        clock.set(CoreVoterReconciler.OBSERVATION_GRACE_NANOS);
        reconciler.reconcile().await();

        assertThat(requested).hasSize(1);
        assertThat(requested.getFirst()).containsExactlyInAnyOrder(A, B, C, D, F).hasSize(5).doesNotContain(E);
    }

    /// Losing leadership restarts the observation: a leader that comes back is fresh again.
    @Test
    void regainedLeadership_startsANewGrace() {
        var requested = new java.util.ArrayList<List<NodeId>>();
        var leader = new java.util.concurrent.atomic.AtomicBoolean(true);
        var clock = new AtomicLong();
        var reconciler = CoreVoterReconciler.coreVoterReconciler(A, leader::get, () -> Option.some(FIVE), () -> Option.some(FIVE), () -> 5,
            () -> Set.of(A, B, C, F), Map::of, roster -> { requested.add(roster.members()); return Promise.unitPromise(); }, clock::get);

        reconciler.reconcile().await();
        leader.set(false);
        reconciler.reconcile().await();
        clock.set(CoreVoterReconciler.OBSERVATION_GRACE_NANOS * 10);
        leader.set(true);
        reconciler.reconcile().await();

        assertThat(requested).as("the first tick after regaining leadership is inside a new grace").isEmpty();
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
            () -> Option.some(CURRENT), () -> Option.some(CURRENT), () -> 3, () -> Set.of(A, B, D), Map::of,
            roster -> { calls.incrementAndGet(); return pending; }, pastGraceClock());
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
            roster -> { calls.incrementAndGet(); return Promise.unitPromise(); }, pastGraceClock());
        reconciler.reconcile().await();
        assertThat(calls.get()).as("a pending change or an uncaught-up added member blocks the next one").isZero();
    }

    @Test
    void unchangedRosterRequestsNothing() {
        var calls = new AtomicInteger();
        var reconciler = CoreVoterReconciler.coreVoterReconciler(A, () -> true,
            () -> Option.some(CURRENT), () -> Option.some(CURRENT), () -> 3, () -> Set.of(A, B, C), Map::of,
            roster -> { calls.incrementAndGet(); return Promise.unitPromise(); }, pastGraceClock());
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
            roster -> { requested.add(roster.members()); return Promise.unitPromise(); }, pastGraceClock());

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
}
