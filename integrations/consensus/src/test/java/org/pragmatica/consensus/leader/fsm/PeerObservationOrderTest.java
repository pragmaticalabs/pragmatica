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
import org.junit.jupiter.api.Disabled;
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
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PeerRemovedFromView;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
            this(initial, true);
        }

        /// `preVote = false`: a lost leader goes straight to `ReElecting`, so leaving `Led` is observable.
        Node(Function<LeaderElectionContext, LeaderElectionState> initial, boolean preVote) {
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
            if (preVote) {
                ctx.enablePreVote(LONG);
            }
            router.addRoute(Send.class, (Consumer<Send>) _ -> sends.incrementAndGet());
            manager = new FsmBackedLeaderManager(harness.fsm(), ctx, false);
        }

        /// Node 2 following node 1 (the route `LeaderReconnectTripwireTest` takes into `Led`).
        static Node following() {
            return following(true);
        }

        static Node following(boolean preVote) {
            var node = new Node(LeaderElectionContext::dormant, preVote);

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
    void lateEviction_afterANewerReconnectThatRestoredThePeer_keepsThePeer() {
        node = Node.following();

        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 1L));
        node.manager.peerReconnected(new PeerReconnected(N1, ALL, ObservationSource.QUIC, 3L));
        assertThat(node.ctx.currentTopology()).as("control: the reconnect restored the leader").contains(N1);
        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 2L));

        assertThat(node.ctx.currentTopology()).as("the older eviction's view does not overwrite the reconnect's").contains(N1);
        assertThat(node.suspectsTheLeader()).isFalse();
    }

    @Test
    void applyTopology_storesTheViewSorted() {
        node = Node.following();

        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, List.of(N3, N1, N2), 7L));

        assertThat(node.ctx.currentTopology()).containsExactly(N1, N2, N3);
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

        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, Long.MAX_VALUE));
        node.manager.peerJoined(new PeerJoined(N1, ALL, ObservationSource.QUIC));

        assertThat(node.ctx.currentTopology()).as("an unordered join is applied unconditionally").contains(N1);
        assertThat(node.suspectsTheLeader()).isFalse();
        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC));
        assertThat(node.ctx.currentTopology()).as("an unordered eviction is applied unconditionally").doesNotContain(N1);
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
        node.ctx.setCurrentTopology(List.of(N1, N3));
        assertThat(node.ctx.currentTopology()).as("setCurrentTopology is an unordered write").containsExactly(N1, N3);
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N2, ALL, 6L))).as("an unordered write does not raise the fence").isTrue();
        assertThat(node.ctx.currentTopology()).containsExactly(N1, N2, N3);
    }

    @Test
    void firstSequencedObservation_appliesOnAFreshContext() {
        node = new Node(LeaderElectionContext::dormant);

        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 1L))).as("sequence 1 is the first observation, not stale").isTrue();
        assertThat(node.ctx.currentTopology()).containsExactlyElementsOf(WITHOUT_N1);
    }

    @Test
    void staleTopologyEventInElecting_doesNotRescheduleTheTick() {
        node = new Node(LeaderElectionContext::electing);

        node.harness.dispatch(new ClusterFsmEvent.NodeAdded(N1, ALL, 5L));
        var electing = (LeaderElectionState.Electing) node.harness.state();
        var afterFresh = electing.tickFuture().get();
        node.harness.dispatch(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 4L));

        assertThat(node.ctx.currentTopology()).as("control: the stale event was dropped").contains(N1);
        assertThat(electing.tickFuture().get() == afterFresh).as("a dropped event leaves the pending tick alone").isTrue();
    }

    @Test
    void viewRestoringReconnect_dispatchesNodeAdded_andANoOpReconnectDoesNot() {
        node = new Node(LeaderElectionContext::electing);

        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 1L));
        var electing = (LeaderElectionState.Electing) node.harness.state();
        var afterEviction = electing.tickFuture().get();
        node.manager.peerReconnected(new PeerReconnected(N1, ALL, ObservationSource.QUIC, 2L));

        assertThat(node.ctx.currentTopology()).as("the reconnect restored the view").contains(N1);
        var afterRestore = electing.tickFuture().get();
        assertThat(afterRestore != afterEviction).as("a restoring reconnect reaches the FSM as NodeAdded (reschedules the tick)").isTrue();

        node.manager.peerReconnected(new PeerReconnected(N1, ALL, ObservationSource.QUIC, 4L));
        assertThat(electing.tickFuture().get() == afterRestore).as("a no-op reconnect dispatches nothing").isTrue();
        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 3L));
        assertThat(node.ctx.currentTopology()).as("the no-op reconnect still advanced the fence").contains(N1);
    }

    /// Two threads released together by a spin barrier apply a sequenced pair in opposite order of sequence: whatever
    /// the interleaving, the higher sequence's view must win. Fence update done as get-then-set loses it.
    @Test
    void concurrentApplyTopology_higherSequenceAlwaysWins() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var rounds = 20_000;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var go = new AtomicInteger(-1);
        var finished = new AtomicInteger();
        var abort = new AtomicBoolean();
        var older = new Thread(() -> applyEachRound(go, finished, abort, 0L, ALL), "o2971-older");
        var newer = new Thread(() -> applyEachRound(go, finished, abort, 1L, WITHOUT_N1), "o2971-newer");
        var lost = 0;
        var completed = 0;

        older.setDaemon(true);
        newer.setDaemon(true);
        older.start();
        newer.start();
        while (completed < rounds && System.nanoTime() < deadline) {
            finished.set(0);
            go.set(completed);
            while (finished.get() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            if (finished.get() < 2) {
                break;
            }
            if (node.ctx.currentTopology().contains(N1)) {
                lost++;
            }
            completed++;
        }
        abort.set(true);
        older.join(5_000);
        newer.join(5_000);

        assertThat(completed).as("rounds completed before the deadline").isGreaterThanOrEqualTo(1_000);
        assertThat(lost).as("rounds where the older sequence's view overwrote the newer one").isZero();
    }

    private void applyEachRound(AtomicInteger go, AtomicInteger finished, AtomicBoolean abort, long offset, List<NodeId> view) {
        for (var round = 0; ! abort.get(); round++) {
            while (go.get() < round) {
                if (abort.get()) {
                    return;
                }
                Thread.onSpinWait();
            }
            node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, view, 10L * round + 1 + offset));
            finished.incrementAndGet();
        }
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

    // ---- #3044 P1a: the view-free removal (`PeerRemovedFromView`, `LeaderElectionContext.removeFromView`) ----

    @Test
    void removal_lastNode_keepsView() {
        node = new Node(LeaderElectionContext::dormant);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, List.of(N1), 1L));

        assertThat(node.ctx.removeFromView(N1)).as("removing the last node is not applied").isFalse();
        assertThat(node.ctx.currentTopology()).as("an empty view is never stored").containsExactly(N1);
    }

    @Test
    void removal_ofSelf_ignored() {
        node = new Node(LeaderElectionContext::dormant);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 1L));

        assertThat(node.ctx.removeFromView(N2)).as("node-2 is self").isFalse();
        assertThat(node.ctx.currentTopology()).containsExactlyElementsOf(ALL);
    }

    /// The removal keeps the sequence: a transport view at the stored sequence stays stale (the removal did not lower
    /// the fence), and the next sequence applies (the removal did not raise it).
    @Test
    void removal_thenStaleTransport_staysDropped() {
        node = new Node(LeaderElectionContext::dormant);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 5L));

        assertThat(node.ctx.removeFromView(N3)).isTrue();
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N3, ALL, 5L))).as("sequence 5 is still stale").isFalse();
        assertThat(node.ctx.currentTopology()).as("the stale view does not restore the removed node").containsExactly(N1, N2);
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N3, WITHOUT_N1, 6L))).as("sequence 6 is newer").isTrue();
        assertThat(node.ctx.currentTopology()).containsExactlyElementsOf(WITHOUT_N1);
    }

    @Test
    void removalDelta_thenNewerTransportView_restoresByTransportOnly() {
        node = new Node(LeaderElectionContext::dormant);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 5L));

        assertThat(node.ctx.removeFromView(N3)).isTrue();
        assertThat(node.ctx.removeFromView(N3)).as("an absent node is a no-op").isFalse();
        assertThat(node.ctx.removeFromView(N2)).as("a removal of self restores nothing").isFalse();
        assertThat(node.ctx.currentTopology()).as("no removal adds a node").containsExactly(N1, N2);
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N3, ALL, 6L))).isTrue();
        assertThat(node.ctx.currentTopology()).as("a newer transport view restores the node").containsExactlyElementsOf(ALL);
    }

    /// Named gap S1 (#3044 design §2): a removal is not ordered against a NEWER transport restore of the same node that
    /// arrived first on another route. Asserts today's behaviour: the node stays out until the next transport view.
    @Test
    void removalAfterNewerRestore_staysRemovedUntilNextTransportObservation_tripwire() {
        node = new Node(LeaderElectionContext::dormant);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1, N2), 5L));
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N3, ALL, 6L));

        node.harness.dispatch(new PeerRemovedFromView(N3));

        assertThat(node.ctx.currentTopology())
            .as("periodic sequenced re-assertion landed: enable removalAfterNewerRestore_nodeReturnsWithinBound and delete this tripwire")
            .containsExactly(N1, N2);
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 7L))).isTrue();
        assertThat(node.ctx.currentTopology()).as("the next transport view restores the node").containsExactlyElementsOf(ALL);
    }

    @Disabled("needs periodic sequenced re-assertion")
    @Test
    void removalAfterNewerRestore_nodeReturnsWithinBound() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1, N2), 5L));
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N3, ALL, 6L));

        node.harness.dispatch(new PeerRemovedFromView(N3));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (! node.ctx.currentTopology().contains(N3) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertThat(node.ctx.currentTopology()).as("re-asserted within the period, no other transport observation").contains(N3);
    }

    /// The seven states that handle `NodeGone` handle the removal too (#3044 design §3.1).
    enum RemovingState {
        DORMANT(() -> new Node(LeaderElectionContext::dormant)),
        QUORUM_WAITING(() -> new Node(LeaderElectionContext::quorumWaiting)),
        AWAITING_KV_SYNC(() -> new Node(LeaderElectionContext::awaitingKvSync)),
        ELECTING(() -> new Node(LeaderElectionContext::electing)),
        LED(Node::following),
        RE_ELECTING(() -> new Node(LeaderElectionContext::reElecting)),
        QUORUM_LOST(() -> new Node(LeaderElectionContext::quorumLost));

        final Supplier<Node> node;

        RemovingState(Supplier<Node> node) {
            this.node = node;
        }
    }

    @ParameterizedTest
    @EnumSource(RemovingState.class)
    void removedFromView_inEveryState(RemovingState state) {
        node = state.node.get();
        node.harness.dispatch(new ClusterFsmEvent.NodeAdded(N1, ALL, 5L));

        node.harness.dispatch(new PeerRemovedFromView(N3));

        assertThat(node.ctx.currentTopology()).as("in %s", state).containsExactly(N1, N2);
    }

    @Test
    void removalOfLeader_inLed_losesLeader() {
        node = Node.following(false);

        node.harness.dispatch(new PeerRemovedFromView(N1));

        assertThat(node.ctx.currentTopology()).containsExactlyElementsOf(WITHOUT_N1);
        assertThat(node.harness.state()).as("the leader is lost").isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    @Test
    void removalOfAnotherPeer_inLed_keepsTheLeader() {
        node = Node.following(false);

        node.harness.dispatch(new PeerRemovedFromView(N3));

        assertThat(node.ctx.currentTopology()).containsExactly(N1, N2);
        assertThat(node.followsTheLeader()).isTrue();
    }

    /// D-S1: the storage keeps the view, yet the leader is lost. Arm 1: the leader is the last node of the stored view.
    @Test
    void removal_lastNode_ofLeader_inLed_losesLeader_keepsView() {
        node = Node.following(false);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1), 5L));
        assertThat(node.ctx.removeFromView(N1)).as("control: the storage keeps the last node").isFalse();

        node.harness.dispatch(new PeerRemovedFromView(N1));

        assertThat(node.ctx.currentTopology()).as("the view is kept").containsExactly(N1);
        assertThat(node.harness.state()).as("the leader is lost all the same").isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    /// D-S1, arm 2: the leader is already absent from the stored view (written without dispatching).
    @Test
    void removal_absentLeader_inLed_losesLeader_keepsView() {
        node = Node.following(false);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 5L));
        assertThat(node.followsTheLeader()).as("control: the direct write left Led alone").isTrue();

        node.harness.dispatch(new PeerRemovedFromView(N1));

        assertThat(node.ctx.currentTopology()).as("the view is kept").containsExactlyElementsOf(WITHOUT_N1);
        assertThat(node.harness.state()).as("the leader is lost all the same").isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    @ParameterizedTest
    @EnumSource(value = RemovingState.class, names = {"ELECTING", "RE_ELECTING"})
    void removalInAnElectingState_reschedulesOnlyWhenApplied(RemovingState state) {
        node = state.node.get();
        node.harness.dispatch(new ClusterFsmEvent.NodeAdded(N1, List.of(N1, N2), 5L));
        var tick = electionTick(node.harness.state());
        var afterAdded = tick.get();

        node.harness.dispatch(new PeerRemovedFromView(N3));
        assertThat(tick.get() == afterAdded).as("in %s: removing an absent node leaves the tick alone", state).isTrue();
        node.harness.dispatch(new PeerRemovedFromView(N1));

        assertThat(node.ctx.currentTopology()).containsExactly(N2);
        assertThat(tick.get() != afterAdded).as("in %s: an applied removal reschedules the tick", state).isTrue();
    }

    private static AtomicReference<ScheduledFuture<?>> electionTick(LeaderElectionState state) {
        return switch (state) {
            case LeaderElectionState.Electing electing -> electing.tickFuture();
            case LeaderElectionState.ReElecting reElecting -> reElecting.tickFuture();
            default -> throw new IllegalStateException("not an electing state: " + state);
        };
    }

    /// Per round the main thread stores `ALL` at sequence s; two threads released together by a spin barrier replace it
    /// with `[N1, N2]` at s + 1 and remove N1. Either order leaves `[N1, N2]` or `[N2]`; a removal computed from the
    /// pre-replace view (get-then-set) writes back `[N2, N3]`, a node no applied transport view still holds.
    @Test
    void advance_concurrentReplaceAndRemove_isAtomic() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var rounds = 20_000;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var go = new AtomicInteger(-1);
        var finished = new AtomicInteger();
        var abort = new AtomicBoolean();
        var replacer = new Thread(() -> replaceEachRound(go, finished, abort), "o3044-replace");
        var remover = new Thread(() -> removeEachRound(go, finished, abort), "o3044-remove");
        var lost = 0;
        var completed = 0;

        replacer.setDaemon(true);
        remover.setDaemon(true);
        replacer.start();
        remover.start();
        while (completed < rounds && System.nanoTime() < deadline) {
            node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 10L * completed + 1));
            finished.set(0);
            go.set(completed);
            while (finished.get() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            if (finished.get() < 2) {
                break;
            }
            if (node.ctx.currentTopology().contains(N3)) {
                lost++;
            }
            completed++;
        }
        abort.set(true);
        replacer.join(5_000);
        remover.join(5_000);

        assertThat(completed).as("rounds completed before the deadline").isGreaterThanOrEqualTo(1_000);
        assertThat(lost).as("rounds where the removal wrote back a view older than the replace").isZero();
    }

    private void replaceEachRound(AtomicInteger go, AtomicInteger finished, AtomicBoolean abort) {
        for (var round = 0; awaitRound(go, abort, round); round++) {
            node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1, N2), 10L * round + 2));
            finished.incrementAndGet();
        }
    }

    private void removeEachRound(AtomicInteger go, AtomicInteger finished, AtomicBoolean abort) {
        for (var round = 0; awaitRound(go, abort, round); round++) {
            node.ctx.removeFromView(N1);
            finished.incrementAndGet();
        }
    }

    private static boolean awaitRound(AtomicInteger go, AtomicBoolean abort, int round) {
        while (go.get() < round) {
            if (abort.get()) {
                return false;
            }
            Thread.onSpinWait();
        }
        return ! abort.get();
    }
}
