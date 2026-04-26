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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies Fix A: `QuorumWaiting` is now a data-carrying record that periodically re-queries
/// [`LeaderElectionContext#consensusReadySupplier`] via a `ScheduledFuture` cancelled on
/// `onExit` / `onCasLost`. Removes the dead-time where the FSM sits leaderless until an
/// incidental topology event nudges it.
class QuorumWaitingPeriodicRecheckTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    /// Long delays so the election tick never fires during the test — we only assert behaviour
    /// of the QuorumWaiting periodic re-check.
    private static final TimeSpan PROPOSAL_RETRY_DELAY = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan BASE_ELECTION_DELAY = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan PER_RANK_DELAY = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan PROPOSAL_TIMEOUT = TimeSpan.timeSpan(30).seconds();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness(AtomicBoolean consensusReady) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "quorum-waiting-periodic-recheck-test",
                fsm -> {
                    var ctx = new LeaderElectionContext(fsm,
                                                        SELF,
                                                        Option.<LeaderProposalHandler>none(),
                                                        List.of(SELF, PEER_A, PEER_B),
                                                        MessageRouter.mutable(),
                                                        PROPOSAL_RETRY_DELAY,
                                                        BASE_ELECTION_DELAY,
                                                        PER_RANK_DELAY,
                                                        PROPOSAL_TIMEOUT,
                                                        LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                        LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                        LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                        consensusReady::get);
                    return ctx.dormant();
                });
    }

    @Test
    void quorumWaiting_supplierFalseThenTrue_dispatchesConsensusReadyOnSecondTick() throws InterruptedException {
        var ready = new AtomicBoolean(false);
        var h = buildHarness(ready);

        // Dormant → QuorumWaiting (supplier reports false on entry, so the FSM stays put and
        // schedules a 1Hz periodic re-check).
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        assertThat(h.state())
                .as("FSM remains in QuorumWaiting because the supplier returned false on entry")
                .isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        var qw = (LeaderElectionState.QuorumWaiting) h.state();
        assertThat((Object) qw.pollFuture().get())
                .as("periodic poll future is scheduled when supplier is false on entry")
                .isNotNull();

        // Flip the supplier — the next periodic tick should observe true and dispatch
        // ConsensusReady, advancing the FSM out of QuorumWaiting.
        ready.set(true);

        // Poll up to 5s — the periodic re-check runs at 1Hz, so we expect a transition within
        // ~1 tick (give it 5s of slack to absorb scheduler jitter on busy CI runners).
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
               && !(h.state() instanceof LeaderElectionState.Electing)) {
            Thread.sleep(50);
        }
        assertThat(h.state())
                .as("periodic re-check observes the false→true edge and advances")
                .isInstanceOf(LeaderElectionState.Electing.class);

        // The poll future on the (now-departed) QuorumWaiting record must have been cancelled
        // by `onExit`. The holder is also drained.
        assertThat((Object) qw.pollFuture().get())
                .as("QuorumWaiting.onExit drains the poll-future holder")
                .isNull();

        // Cleanup — cancel the eagerly-scheduled Electing tick so the test scheduler doesn't
        // hold a long-lived reference.
        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void quorumWaiting_supplierTrueAtEntry_dispatchesImmediatelyNoTimer() {
        var ready = new AtomicBoolean(true);
        var h = buildHarness(ready);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());

        // Synchronous dispatch path — we should already be in Electing, having passed through
        // QuorumWaiting in a single dispatch tick.
        assertThat(h.state())
                .as("supplier true at entry: synchronous ConsensusReady dispatch advances the FSM")
                .isInstanceOf(LeaderElectionState.Electing.class);

        // Cleanup.
        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void quorumWaiting_onExit_cancelsTimer() {
        var ready = new AtomicBoolean(false);
        var h = buildHarness(ready);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        var qw = (LeaderElectionState.QuorumWaiting) h.state();
        var pollFuture = qw.pollFuture().get();
        assertThat((Object) pollFuture).as("poll future scheduled on entry").isNotNull();
        assertThat(pollFuture.isCancelled()).isFalse();

        // Transition out via QuorumDisappeared → Dormant.
        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Dormant.class);

        assertThat(pollFuture.isCancelled())
                .as("QuorumWaiting.onExit must cancel the periodic poll future")
                .isTrue();
        assertThat((Object) qw.pollFuture().get())
                .as("QuorumWaiting.onExit drains the poll-future holder")
                .isNull();
    }

    @Test
    void quorumWaiting_onCasLost_cancelsTimer() {
        var ready = new AtomicBoolean(false);
        var h = buildHarness(ready);

        // Drive FSM into Dormant (initial state) — we'll construct a fresh QuorumWaiting record
        // and exercise its `onCasLost` directly, the same pattern used by
        // LeaderElectionStaleTimerTest for Electing/ReElecting.
        var ctx = ((LeaderElectionState.Dormant) h.state()).ctx();
        var freshQuorumWaiting = LeaderElectionState.QuorumWaiting.fresh(ctx);
        // Fresh record — invoke onEntry to schedule the poll.
        freshQuorumWaiting.onEntry();
        var pollFuture = freshQuorumWaiting.pollFuture().get();
        assertThat((Object) pollFuture).as("poll future scheduled on entry").isNotNull();
        assertThat(pollFuture.isCancelled()).isFalse();

        freshQuorumWaiting.onCasLost();

        assertThat(pollFuture.isCancelled())
                .as("QuorumWaiting.onCasLost must cancel the periodic poll future")
                .isTrue();
        assertThat((Object) freshQuorumWaiting.pollFuture().get())
                .as("QuorumWaiting.onCasLost drains the poll-future holder")
                .isNull();
    }
}
