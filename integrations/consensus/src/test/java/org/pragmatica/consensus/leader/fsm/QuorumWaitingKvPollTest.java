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
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// R6 verification: `QuorumWaiting` is part of the always-listening KV poll path. On entry it
/// pulls the cluster KV-Store via `currentLeaderFromKvSupplier`; the periodic re-check ALSO
/// pulls KV in addition to consensus-readiness. A re-joining node whose snapshot included
/// `LeaderKey` short-circuits straight to `Led` without paying the consensus-readiness wait
/// or the AwaitingKvSync grace window.
class QuorumWaitingKvPollTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    private static final TimeSpan PROPOSAL_RETRY_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan BASE_ELECTION_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PER_RANK_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PROPOSAL_TIMEOUT = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan KV_SYNC_GRACE_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan LONG_OBSERVATION_INTERVAL = TimeSpan.timeSpan(60).seconds();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness(
            AtomicBoolean consensusReady,
            AtomicReference<Option<NodeId>> kvLeader) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "quorum-waiting-kv-poll-test",
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
                                                        consensusReady::get,
                                                        kvLeader::get,
                                                        KV_SYNC_GRACE_DELAY,
                                                        LONG_OBSERVATION_INTERVAL);
                    return ctx.dormant();
                });
    }

    /// Re-joining node: snapshot replay populated `LeaderKey` locally before the FSM saw
    /// `QuorumEstablished`. On entry to `QuorumWaiting` the synchronous KV pull synthesizes a
    /// `LeaderCommitted` and the FSM transitions straight to `Led(thatLeader)` — no wait for
    /// consensus-readiness, no AwaitingKvSync grace window paid.
    @Test
    void quorumWaiting_kvHasLeaderOnEntry_synchronousTransitionToLed() {
        var consensusReady = new AtomicBoolean(false);
        var kvLeader = new AtomicReference<>(Option.some(PEER_A));
        var h = buildHarness(consensusReady, kvLeader);

        // Seed topology so adoptLeaderIfInTopology accepts PEER_A.
        h.dispatch(new ClusterFsmEvent.NodeAdded(PEER_A, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());

        // Synchronous: Dormant → QuorumWaiting.onEntry → adoptLeaderFromKvIfPresent →
        // LeaderCommitted dispatched → handler arm transitions to Led(PEER_A).
        assertThat(h.state())
                .as("QuorumWaiting.onEntry pulled KV synchronously, advanced straight to Led")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Periodic re-check ALSO pulls KV. consensus stays not-ready, but a peer commits a leader
    /// while the FSM is sitting in QuorumWaiting. The 1Hz poll observes the new KV value via
    /// `adoptLeaderFromKvIfPresent` and the FSM transitions to `Led` — without waiting for
    /// consensus-readiness to flip.
    @Test
    void quorumWaiting_kvLeaderAppearsDuringPoll_transitionsToLed() throws InterruptedException {
        var consensusReady = new AtomicBoolean(false);
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = buildHarness(consensusReady, kvLeader);

        h.dispatch(new ClusterFsmEvent.NodeAdded(PEER_A, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        assertThat(h.state())
                .as("FSM in QuorumWaiting because consensus is not ready and KV is empty")
                .isInstanceOf(LeaderElectionState.QuorumWaiting.class);

        // Mid-window, a peer commits a leader. The 1Hz poll picks it up.
        kvLeader.set(Option.some(PEER_A));

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
               && !(h.state() instanceof LeaderElectionState.Led)) {
            Thread.sleep(50);
        }

        assertThat(h.state())
                .as("QuorumWaiting periodic poll pulled KV and advanced to Led without "
                    + "waiting for consensus-readiness flip")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// FIX 1 (B5-facet-2): a committed KV leader NOT yet in the transport topology is adopted
    /// UNCONDITIONALLY — the consensus decision overrides observed transport state (WARN logged).
    /// This OVERTURNS the prior contract (which kept the FSM in QuorumWaiting until topology
    /// caught up); that gate was the second root of the 600s never-READY wedge.
    @Test
    void quorumWaiting_kvLeaderNotInTopology_adoptsUnconditionally() {
        var consensusReady = new AtomicBoolean(false);
        // KV reports PEER_A but topology is still empty (haven't received NodeAdded yet).
        var kvLeader = new AtomicReference<>(Option.some(PEER_A));
        var h = buildHarness(consensusReady, kvLeader);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());

        assertThat(h.state())
                .as("committed KV leader adopted despite empty topology — consensus overrides transport (FIX 1)")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }
}
