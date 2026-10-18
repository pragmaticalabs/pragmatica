/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/// A-03 (cluster T assessment, rc4 `7c786db0d3`): a follower's link to its leader flaps. The transport emits
/// `PeerDisconnected` (view without the leader) and, on re-attach, `PeerReconnected` (view with it, see
/// `QuicReconnectObservationControlTest` for the producer side). The leader FSM stores its topology by replacement
/// from the last event, so `LeaderManager.peerReconnected` must dispatch `NodeAdded` like `peerJoined`; when it did
/// nothing the follower kept a live, reconnected leader out of its view and kept "suspecting" it until some unrelated
/// topology event replaced the view (second review: deposing needs a majority of the electorate to doubt; the effect
/// was a stale follower plus a standing pre-vote retry loop, not a lone deposition).
///
/// Level: the real FSM, context and pre-vote of one follower (the construction of `LeaderPreVoteTest`), fed the two
/// transport events. Nothing is mocked; the consumer is the unit under test. The real transport producing the
/// events is in `LeaderReconnectTransportTest` (NT-A10).
@Timeout(30)
class LeaderReconnectTripwireTest {
    private static final NodeId N1 = new NodeId("node-1");
    private static final NodeId N2 = new NodeId("node-2");
    private static final NodeId N3 = new NodeId("node-3");
    private static final List<NodeId> ALL = List.of(N1, N2, N3);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private Follower follower;

    @AfterEach
    void tearDown() {
        if (follower != null) {
            follower.manager.stop();
        }
    }

    /// Node 2 following node 1, pre-vote on (as in production), nobody to answer its questions.
    private static final class Follower {
        final FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
        final LeaderElectionContext ctx;
        final FsmBackedLeaderManager manager;
        final AtomicInteger sends = new AtomicInteger();

        Follower() {
            var router = MessageRouter.mutable();
            var holder = new java.util.concurrent.atomic.AtomicReference<LeaderElectionContext>();

            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> Promise.success(Unit.unit());
            harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("a03-follower", fsm -> {
                var context = new LeaderElectionContext(fsm,
                                                        N2,
                                                        Option.some(handler),
                                                        ALL,
                                                        router,
                                                        LONG,
                                                        LONG,
                                                        LONG,
                                                        LONG,
                                                        LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                        LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                        LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                        () -> true,
                                                        Option::none,
                                                        LONG,
                                                        LONG,
                                                        LONG,
                                                        _ -> true,
                                                        LONG);

                holder.set(context);
                return context.dormant();
            });
            ctx = holder.get();
            ctx.enablePreVote(LONG);
            router.addRoute(Send.class, (Consumer<Send>) _ -> sends.incrementAndGet());
            manager = new FsmBackedLeaderManager(harness.fsm(), ctx, false);
            harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
            harness.dispatch(new ClusterFsmEvent.NodeAdded(N2, ALL));
            harness.dispatch(new ConsensusReady());
            harness.dispatch(new LeaderCommitted(N1, 1L));
        }

        boolean followsTheLeader() {
            return harness.state() instanceof LeaderElectionState.Led led && led.leader().equals(N1);
        }

        boolean suspectsTheLeader() {
            return harness.state() instanceof LeaderElectionState.Led led && led.isLeaderSuspected();
        }
    }

    /// The follower after its leader link flapped: disconnected (view without the leader), then reconnected.
    private Follower afterTheLeaderLinkFlapped() {
        follower = new Follower();
        follower.manager.peerDisconnected(new PeerDisconnected(N1, List.of(N2, N3), ObservationSource.QUIC));
        follower.manager.peerReconnected(new PeerReconnected(N1, ALL, ObservationSource.QUIC));

        return follower;
    }

    @Test
    void leaderManager_asksNoPreVoteQuestion_whenTheLeaderReconnects() {
        follower = new Follower();
        follower.manager.peerDisconnected(new PeerDisconnected(N1, List.of(N2, N3), ObservationSource.QUIC));
        var sendsAfterTheDisconnect = follower.sends.get();

        follower.manager.peerReconnected(new PeerReconnected(N1, ALL, ObservationSource.QUIC));

        assertThat(sendsAfterTheDisconnect).as("control: losing the leader starts a pre-vote round").isPositive();
        assertThat(follower.sends.get()).as("a reconnect is a join, not another loss of the leader").isEqualTo(sendsAfterTheDisconnect);
    }

    @Test
    void leaderManager_keepsOnlyEligibleNodesInTheView_whenAPeerReconnects() {
        follower = new Follower();
        follower.ctx.installVoters(List.of(N1, N2));
        follower.manager.peerReconnected(new PeerReconnected(N1, ALL, ObservationSource.QUIC));

        assertThat(follower.ctx.currentTopology()).as("view after the reconnect, node-3 is not a voter").containsExactlyInAnyOrder(N1, N2);
    }

    @Test
    void leaderManager_restoresTheLeaderToTheView_afterItReconnects() {
        var flapped = afterTheLeaderLinkFlapped();

        assertThat(flapped.followsTheLeader()).as("control: the follower still follows node 1 (pre-vote holds it in Led)").isTrue();
        assertThat(flapped.ctx.currentTopology()).as("view after the leader reconnected").contains(N1);
        assertThat(flapped.suspectsTheLeader()).as("follower suspects a connected leader").isFalse();
    }
}
