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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/// #2971: the transport's view-carrying observations reach the leader FSM on different threads, and the FSM stores
/// its topology by replacing it with the last event's view. Each observation carries the transport's sequence; the
/// context drops a view older than the last one applied, atomically with the write. Here the observations are fed
/// out of order by hand (the order a delayed handler produces); `PeerObservationOrderTransportTest` drives the same
/// race through a real transport.
@Timeout(30)
class PeerObservationOrderTest {
    private static final NodeId N1 = new NodeId("node-1");
    private static final NodeId N2 = new NodeId("node-2");
    private static final NodeId N3 = new NodeId("node-3");
    private static final List<NodeId> ALL = List.of(N1, N2, N3);
    private static final List<NodeId> WITHOUT_N1 = List.of(N2, N3);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private Node node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.manager.stop();
        }
    }

    /// Node 2 with the real FSM, context and pre-vote, started in the state `initial` picks (no entry action runs).
    private static final class Node {
        final FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
        final LeaderElectionContext ctx;
        final FsmBackedLeaderManager manager;
        final AtomicInteger sends = new AtomicInteger();

        Node(Function<LeaderElectionContext, LeaderElectionState> initial) {
            var router = MessageRouter.mutable();
            var holder = new AtomicReference<LeaderElectionContext>();

            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> Promise.success(Unit.unit());
            harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("o2971-node", fsm -> {
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
                return initial.apply(context);
            });
            ctx = holder.get();
            ctx.enablePreVote(LONG);
            router.addRoute(Send.class, (Consumer<Send>) _ -> sends.incrementAndGet());
            manager = new FsmBackedLeaderManager(harness.fsm(), ctx, false);
        }

        /// Node 2 following node 1 (the route `LeaderReconnectTripwireTest` takes into `Led`).
        static Node following() {
            var node = new Node(LeaderElectionContext::dormant);

            node.harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
            node.harness.dispatch(new ClusterFsmEvent.NodeAdded(N2, ALL));
            node.harness.dispatch(new ConsensusReady());
            node.harness.dispatch(new LeaderCommitted(N1, 1L));

            return node;
        }

        boolean followsTheLeader() {
            return harness.state() instanceof LeaderElectionState.Led led && led.leader().equals(N1);
        }

        boolean suspectsTheLeader() {
            return harness.state() instanceof LeaderElectionState.Led led && led.isLeaderSuspected();
        }
    }

    @Test
    void lateJoin_afterANewerEviction_leavesTheLeaderSuspected() {
        node = Node.following();

        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 2L));
        assertThat(node.suspectsTheLeader()).as("control: the eviction removed the leader from the view").isTrue();
        node.manager.peerJoined(new PeerJoined(N1, ALL, ObservationSource.QUIC, 1L));

        assertThat(node.ctx.currentTopology()).as("the older join's view does not overwrite the eviction's").doesNotContain(N1);
        assertThat(node.suspectsTheLeader()).as("the eviction wins: the leader stays suspected").isTrue();
    }

    @Test
    void lateEviction_afterANewerReconnect_neitherDropsNorSuspectsTheLeader() {
        node = Node.following();

        node.manager.peerReconnected(new PeerReconnected(N1, ALL, ObservationSource.QUIC, 2L));
        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 1L));

        assertThat(node.ctx.currentTopology()).as("the older eviction's view does not overwrite the reconnect's").contains(N1);
        assertThat(node.sends.get()).as("a stale eviction asks no pre-vote question about the leader").isZero();
        assertThat(node.followsTheLeader()).isTrue();
        assertThat(node.suspectsTheLeader()).isFalse();
    }

    @Test
    void equalSequence_isStale() {
        node = Node.following();

        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 2L));
        node.manager.peerJoined(new PeerJoined(N1, ALL, ObservationSource.QUIC, 2L));

        assertThat(node.ctx.currentTopology()).as("a sequence already applied is not applied again").doesNotContain(N1);
    }

    @Test
    void unsequencedObservations_applyInArrivalOrder() {
        node = Node.following();

        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 2L));
        node.manager.peerJoined(new PeerJoined(N1, ALL, ObservationSource.QUIC));

        assertThat(node.ctx.currentTopology()).as("an unordered observation is applied unconditionally").contains(N1);
        assertThat(node.suspectsTheLeader()).isFalse();
    }

    @Test
    void sequencedEmptyView_keepsTheViewButAdvancesTheFence() {
        node = Node.following();

        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(), 3L))).as("a newer empty view is not stale").isTrue();
        assertThat(node.ctx.currentTopology()).as("an empty view is never stored").containsExactlyElementsOf(ALL);
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 2L))).as("older than the empty view").isFalse();
        assertThat(node.ctx.currentTopology()).containsExactlyElementsOf(ALL);
    }

    @Test
    void unsequencedWrite_doesNotLowerTheFence() {
        node = Node.following();

        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 5L))).isTrue();
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1, N2)))).isTrue();
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 4L))).as("still older than 5").isFalse();
        assertThat(node.ctx.currentTopology()).containsExactly(N1, N2);
    }

    /// Every state that stores the view: started there directly, fed a newer event and then an older one in both
    /// directions (join-then-gone, gone-then-join). `Led` is covered by the manager-level tests above.
    enum StoringState {
        DORMANT(LeaderElectionContext::dormant),
        QUORUM_WAITING(LeaderElectionContext::quorumWaiting),
        AWAITING_KV_SYNC(LeaderElectionContext::awaitingKvSync),
        ELECTING(LeaderElectionContext::electing),
        RE_ELECTING(LeaderElectionContext::reElecting),
        QUORUM_LOST(LeaderElectionContext::quorumLost);

        final Function<LeaderElectionContext, LeaderElectionState> initial;

        StoringState(Function<LeaderElectionContext, LeaderElectionState> initial) {
            this.initial = initial;
        }
    }

    @ParameterizedTest
    @EnumSource(StoringState.class)
    void olderNodeAdded_afterANewerNodeGone_doesNotRestoreThePeer(StoringState state) {
        node = new Node(state.initial);

        node.harness.dispatch(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 2L));
        node.harness.dispatch(new ClusterFsmEvent.NodeAdded(N1, ALL, 1L));

        assertThat(node.ctx.currentTopology()).as("in %s", state).containsExactlyElementsOf(WITHOUT_N1);
    }

    @ParameterizedTest
    @EnumSource(StoringState.class)
    void olderNodeGone_afterANewerNodeAdded_doesNotRemoveThePeer(StoringState state) {
        node = new Node(state.initial);

        node.harness.dispatch(new ClusterFsmEvent.NodeAdded(N1, ALL, 2L));
        node.harness.dispatch(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 1L));

        assertThat(node.ctx.currentTopology()).as("in %s", state).containsExactlyElementsOf(ALL);
    }
}
