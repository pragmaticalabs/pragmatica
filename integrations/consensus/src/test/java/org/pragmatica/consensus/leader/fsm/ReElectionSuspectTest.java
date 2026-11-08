/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderDoubtConfirmed;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// #2869: after the leader lease trips (directly, or through a majority-confirmed pre-vote doubt), the
/// re-election must not propose the silent ex-leader again merely because it is the lowest NodeId still in the
/// transport topology. A blackholed leader keeps its QUIC channels open, so `NodeGone` never removes it from the
/// topology; before the fix every re-election proposed it again and Rabia committed it.
///
/// Fixture: expected cluster `[A, B, SELF]` with the suspect `A` the lowest NodeId, the topology holding all
/// three (no `NodeGone`), and a capturing [`LeaderProposalHandler`]. Only the lease, the staircase tick and the
/// proposal drive behaviour; everything else uses 60 s delays.
class ReElectionSuspectTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId SELF = new NodeId("node-3");

    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan BASE_ELECTION_DELAY = TimeSpan.timeSpan(50).millis();
    private static final TimeSpan PER_RANK_DELAY = TimeSpan.timeSpan(50).millis();
    private static final TimeSpan LEASE_INTERVAL = TimeSpan.timeSpan(30).millis();
    private static final TimeSpan LEASE_WARMUP = TimeSpan.timeSpan(30).millis();
    private static final long AWAIT_MS = 5_000L;

    private record Proposal(NodeId candidate, long viewSequence) {}

    private final List<Proposal> proposals = new CopyOnWriteArrayList<>();
    private final List<Send> sent = new CopyOnWriteArrayList<>();
    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
    private LeaderElectionContext context;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.dispatch(new ClusterFsmEvent.Shutdown());
        }
    }

    private Promise<Unit> capture(NodeId candidate, long viewSequence) {
        proposals.add(new Proposal(candidate, viewSequence));
        return Promise.success(Unit.unit());
    }

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness(List<NodeId> expectedCluster,
                                                                              Predicate<NodeId> leaderPingFreshFn,
                                                                              boolean preVote) {
        var router = MessageRouter.mutable();
        router.addRoute(Send.class, (Consumer<Send>) sent::add);
        LeaderProposalHandler handler = this::capture;
        var holder = new AtomicReference<LeaderElectionContext>();
        var h = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("re-election-suspect", fsm -> {
            var ctx = new LeaderElectionContext(fsm,
                                                SELF,
                                                Option.some(handler),
                                                expectedCluster,
                                                router,
                                                LONG,
                                                BASE_ELECTION_DELAY,
                                                PER_RANK_DELAY,
                                                LONG,
                                                LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                () -> true,
                                                Option::none,
                                                LONG,
                                                LONG,
                                                LEASE_INTERVAL,
                                                leaderPingFreshFn,
                                                LEASE_WARMUP);
            holder.set(ctx);
            return ctx.dormant();
        });

        if (preVote) {
            holder.get().enablePreVote(LONG);
        }

        harness = h;
        context = holder.get();
        return h;
    }

    /// Dormant → QuorumWaiting → AwaitingKvSync → `Led(leader)`, with every node in the transport topology.
    private static void driveToLed(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h,
                                   List<NodeId> topology,
                                   NodeId leader) {
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, topology));
        h.dispatch(new ConsensusReady());
        h.dispatch(new LeaderCommitted(leader, 1L));
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);
    }

    private Proposal awaitProposal(int index) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS);
        while (proposals.size() <= index && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(proposals).as("proposal #%d submitted within %d ms", index, AWAIT_MS)
                             .hasSizeGreaterThan(index);
        return proposals.get(index);
    }

    private static boolean awaitCondition(Predicate<LeaderElectionState> condition,
                                          FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h)
            throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS);
        while (!condition.test(h.state()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return condition.test(h.state());
    }

    /// T1 — lease-trip path without the pre-vote (`loseLeader`). The silent leader A stays in the topology and
    /// is the lowest NodeId; the re-election proposes B.
    @Test
    void reElecting_afterLeaseTrip_neverProposesTheSilentLeader() throws InterruptedException {
        var h = buildHarness(List.of(A, B, SELF), node -> !node.equals(A), false);

        driveToLed(h, List.of(A, B, SELF), A);

        assertThat(awaitCondition(LeaderElectionState.ReElecting.class::isInstance, h))
            .as("lease on silent A trips into ReElecting")
            .isTrue();
        assertThat(awaitProposal(0).candidate())
            .as("the silent ex-leader A is still in the topology but must not be proposed again")
            .isEqualTo(B);
    }

    /// T2 — pre-vote path (`handleLeaderDoubtConfirmedInLed`), the path the #2869 logs took: the lease trip
    /// asks the electorate, and the majority-confirmed doubt moves the follower to ReElecting.
    @Test
    void reElecting_afterConfirmedLeaderDoubt_neverProposesTheDoubtedLeader() throws InterruptedException {
        var h = buildHarness(List.of(A, B, SELF), node -> !node.equals(A), true);

        driveToLed(h, List.of(A, B, SELF), A);

        assertThat(awaitCondition(state -> state instanceof LeaderElectionState.Led led && led.isLeaderSuspected(),
                                  h))
            .as("lease on silent A trips and the follower asks the electorate instead of re-electing")
            .isTrue();
        assertThat(proposals).as("no proposal while the pre-vote round runs").isEmpty();

        h.dispatch(new LeaderDoubtConfirmed(A));

        assertThat(h.state()).as("majority-confirmed doubt moves to ReElecting")
                             .isInstanceOf(LeaderElectionState.ReElecting.class);
        assertThat(awaitProposal(0).candidate())
            .as("the doubted leader A is still in the topology but must not be proposed again")
            .isEqualTo(B);
    }

    /// T3 — the exclusion falls back to the unfiltered pool when the suspect is the only candidate, so the FSM
    /// still proposes instead of swallowing the tick on the empty-pool guard. The electorate is reduced to A.
    @Test
    void exclusionFallsBackWhenSuspectIsTheOnlyCandidate() throws InterruptedException {
        var h = buildHarness(List.of(A), node -> !node.equals(A), false);

        driveToLed(h, List.of(A, SELF), A);

        assertThat(awaitCondition(LeaderElectionState.ReElecting.class::isInstance, h))
            .as("lease on silent A trips into ReElecting")
            .isTrue();
        assertThat(awaitProposal(0).candidate())
            .as("A is the only candidate: proposing it beats proposing nothing")
            .isEqualTo(A);
    }

    /// The suspect holds no staircase slot: with A excluded SELF ranks 1 (proposes at base + 1 step) instead of 2.
    @Test
    void rankOfSelf_withSuspectExcluded_movesNextLowestNodeUpOneStep() {
        buildHarness(List.of(A, B, SELF), _ -> true, false);

        assertThat(context.rankOfSelf(Option.none())).as("no suspect: SELF is third of [A, B, SELF]").isEqualTo(2);
        assertThat(context.rankOfSelf(Option.some(A))).as("suspect A left out of the ranking").isEqualTo(1);
        assertThat(context.rankOfSelf(Option.some(SELF))).as("a suspected SELF ranks after everyone").isEqualTo(2);
    }

    /// T4 — the suspect belongs to one ReElecting instance. After B is committed and then goes silent too, the
    /// next re-election excludes B — and A, the earlier suspect, is a candidate again.
    @Test
    void suspectIsClearedOnExit_nextReElectionExcludesOnlyTheNewSuspect() throws InterruptedException {
        var h = buildHarness(List.of(A, B, SELF), _ -> false, false);

        driveToLed(h, List.of(A, B, SELF), A);

        var first = awaitProposal(0);
        assertThat(first.candidate()).as("first re-election excludes A").isEqualTo(B);

        // Production order: the LeaderKey ValuePut advances the committed viewSequence before the FSM sees
        // LeaderCommitted, so the commit postdates the ReElecting entry baseline and is adopted.
        context.observeViewSequence(first.viewSequence());
        // The ReElecting instance retries only after 60 s, so a second proposal within the await window can only
        // come from a fresh ReElecting entered after Led(B) tripped its own lease.
        h.dispatch(new LeaderCommitted(B, first.viewSequence()));

        var second = awaitProposal(1);
        assertThat(second.candidate())
            .as("second re-election excludes the new suspect B, not the earlier suspect A")
            .isEqualTo(A);
        assertThat(second.viewSequence()).isGreaterThan(first.viewSequence());
    }


    /// T5 — the suspect must not survive an exit that is NOT a commit. ReElecting(suspect A) loses quorum,
    /// regains it, and the AwaitingKvSync grace fall-through enters a fresh ReElecting with no suspect: A (lowest,
    /// still in topology) is a candidate again.
    @Test
    void suspectIsClearedOnNonCommitExit_freshReElectionProposesTheLowestNodeAgain() throws InterruptedException {
        var h = buildHarness(List.of(A, B, SELF), node -> !node.equals(A), false);

        driveToLed(h, List.of(A, B, SELF), A);
        assertThat(awaitProposal(0).candidate()).as("first re-election excludes A").isEqualTo(B);

        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.QuorumLost.class);
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
        h.dispatch(new LeaderElectionEvents.KvSyncGraceTimeout());
        assertThat(h.state()).as("grace fall-through re-elects").isInstanceOf(LeaderElectionState.ReElecting.class);

        assertThat(awaitProposal(1).candidate())
            .as("a ReElecting entered without a suspect proposes the lowest node A again")
            .isEqualTo(A);
    }
}
