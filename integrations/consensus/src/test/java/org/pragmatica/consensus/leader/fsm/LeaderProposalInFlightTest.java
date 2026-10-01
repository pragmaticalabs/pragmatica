/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ElectionTick;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// #1797: the in-flight proposal guard is held from submit until the proposal COMMITS (the FSM leaves
/// `Electing` / `ReElecting` on the committed `LeaderKey`) or the proposal timeout releases it — never
/// cleared by the "submitted" settle. Before the fix a tick (or a topology reschedule) between the submit
/// settle and the commit re-proposed, advancing the committed `viewSequence` twice for one election
/// round and leaving `LeaderTerm` one below it.
class LeaderProposalInFlightTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    /// Long delays so no tick fires on its own: the tests dispatch `ElectionTick` themselves, which is
    /// exactly what a fired tick or a topology reschedule delivers.
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan SHORT_RETRY = TimeSpan.timeSpan(50).millis();
    private static final TimeSpan SHORT_PROPOSAL_TIMEOUT = TimeSpan.timeSpan(300).millis();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness(LeaderProposalHandler handler,
                                                                              TimeSpan retryDelay,
                                                                              TimeSpan proposalTimeout) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "leader-proposal-in-flight-test",
                fsm -> new LeaderElectionContext(fsm,
                                                 SELF,
                                                 Option.some(handler),
                                                 List.of(SELF, PEER_A, PEER_B),
                                                 MessageRouter.mutable(),
                                                 retryDelay,
                                                 LONG,
                                                 LONG,
                                                 proposalTimeout,
                                                 LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                 LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                 LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                 () -> true,
                                                 Option::none,
                                                 LONG,
                                                 LONG).dormant());
    }

    private static LeaderProposalHandler submittingHandler(AtomicInteger proposals) {
        return (candidate, viewSeq) -> {
            proposals.incrementAndGet();
            return Promise.success(Unit.unit());
        };
    }

    private static void enterElecting(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h) {
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Electing.class);
    }

    private static void enterReElecting(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h) {
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ConsensusReady());
        h.dispatch(new LeaderCommitted(SELF));
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);
        h.dispatch(new ClusterFsmEvent.NodeGone(SELF, List.of(PEER_A, PEER_B)));
        assertThat(h.state()).isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    private static LeaderElectionContext ctxOf(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h) {
        return switch (h.state()) {
            case LeaderElectionState.Electing e -> e.ctx();
            case LeaderElectionState.ReElecting r -> r.ctx();
            default -> throw new AssertionError("not electing: " + h.state());
        };
    }

    @Test
    void reElecting_secondTickAfterSubmitSettle_proposesNothingMore() {
        var proposals = new AtomicInteger();
        var h = buildHarness(submittingHandler(proposals), LONG, LONG);

        enterReElecting(h);
        h.dispatch(new ElectionTick());
        assertThat(proposals.get()).as("first tick proposes").isEqualTo(1);
        assertThat(ctxOf(h).proposalInFlight()).as("the submit settle leaves the guard held until commit").isTrue();

        h.dispatch(new ElectionTick());

        assertThat(proposals.get()).as("a second schedule between submit-settle and commit must not re-propose")
                                   .isEqualTo(1);
        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void electing_secondTickAfterSubmitSettle_proposesNothingMore() {
        var proposals = new AtomicInteger();
        var h = buildHarness(submittingHandler(proposals), LONG, LONG);

        enterElecting(h);
        h.dispatch(new ElectionTick());
        assertThat(proposals.get()).as("first tick proposes").isEqualTo(1);

        h.dispatch(new ElectionTick());

        assertThat(proposals.get()).as("a second schedule between submit-settle and commit must not re-propose")
                                   .isEqualTo(1);
        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void reElecting_topologyChangeAfterSubmitSettle_proposesNothingMore() throws InterruptedException {
        var proposals = new AtomicInteger();
        var h = buildHarness(submittingHandler(proposals), SHORT_RETRY, LONG);

        enterReElecting(h);
        h.dispatch(new ElectionTick());
        assertThat(proposals.get()).as("first tick proposes").isEqualTo(1);

        h.dispatch(new ClusterFsmEvent.NodeGone(PEER_B, List.of(SELF, PEER_A)));
        Thread.sleep(400);

        assertThat(proposals.get()).as("the tick a topology change reschedules must not re-propose either")
                                   .isEqualTo(1);
        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// The guard is held for the commit, so the commit must release it: the FSM leaves `ReElecting` on the
    /// committed `LeaderKey` and `onExit` clears the guard. Without this the next election round could
    /// never propose.
    @Test
    void reElecting_commit_releasesTheGuard() {
        var proposals = new AtomicInteger();
        var h = buildHarness(submittingHandler(proposals), LONG, LONG);

        enterReElecting(h);
        var ctx = ctxOf(h);
        h.dispatch(new ElectionTick());
        assertThat(ctx.proposalInFlight()).isTrue();

        ctx.observeViewSequence(1L);
        h.dispatch(new LeaderCommitted(SELF, 1L));

        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);
        assertThat(ctx.proposalInFlight()).as("leaving ReElecting on the commit releases the guard").isFalse();
    }

    /// A proposal that never commits is not held forever: the proposal timeout releases the guard and
    /// the rescheduled tick re-proposes.
    @Test
    void reElecting_proposalThatNeverCommits_isReProposedAfterTheTimeout() throws InterruptedException {
        var proposals = new AtomicInteger();
        var h = buildHarness(submittingHandler(proposals), SHORT_RETRY, SHORT_PROPOSAL_TIMEOUT);

        enterReElecting(h);
        h.dispatch(new ElectionTick());
        h.dispatch(new ElectionTick());
        assertThat(proposals.get()).as("held before the timeout").isEqualTo(1);

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && proposals.get() < 2) {
            Thread.sleep(20);
        }

        assertThat(proposals.get()).as("the timeout released the guard and the retry re-proposed").isEqualTo(2);
        h.dispatch(new ClusterFsmEvent.Shutdown());
    }
}
