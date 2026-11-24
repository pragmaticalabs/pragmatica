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
import org.pragmatica.consensus.fsm.ClusterFsmRouter;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PeerRemovedFromView;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.consensus.topology.TransportView;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.pragmatica.consensus.leader.fsm.LeaderCommits.commitLeaderOnDrainer;
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
    private static final NodeId N4 = new NodeId("node-4");
    private static final List<NodeId> ALL = List.of(N1, N2, N3);
    private static final List<NodeId> WITHOUT_N1 = List.of(N2, N3);
    private static final List<NodeId> WITHOUT_N3 = List.of(N1, N2);
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

            OnDrainer.dispatch(node.harness, new ClusterFsmEvent.QuorumEstablished());
            OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeAdded(N2, ALL, 1L));
            OnDrainer.dispatch(node.harness, new ConsensusReady());
            commitLeaderOnDrainer(node.harness, N1, 1L);

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

        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 2L));
        node.manager.peerReconnected(new PeerReconnected(N1, ALL, ObservationSource.QUIC, 4L));
        assertThat(node.ctx.currentTopology()).as("control: the reconnect restored the leader").contains(N1);
        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 3L));

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

    /// #3044 §2.1: only sequenced transport observations replace the view. After the view at sequence 5, an
    /// unsequenced eviction, `NodeGone` and join reach neither the view nor the leader's suspicion.
    @Test
    void unsequencedWholesaleWrite_isDropped() {
        node = Node.following();
        node.manager.peerDisconnected(new PeerDisconnected(N3, List.of(N1, N2), ObservationSource.QUIC, 5L));

        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC));
        OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeGone(N1, List.of(N2)));
        node.manager.peerJoined(new PeerJoined(N3, ALL, ObservationSource.QUIC));

        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N3, ALL))).as("an unsequenced write is dropped").isFalse();
        assertThat(node.ctx.currentTopology()).as("the view at sequence 5 is unchanged").containsExactly(N1, N2);
        assertThat(node.followsTheLeader()).isTrue();
        assertThat(node.suspectsTheLeader()).as("a dropped eviction does not suspect the leader").isFalse();
    }

    @Test
    void localMode_evictionOfTheLeaderWithAnEmptyEligibleView_leavesNoLeader() {
        node = Node.following(false);
        var local = new FsmBackedLeaderManager(node.harness.fsm(), node.ctx, true);

        node.ctx.installVoters(List.of(N1));
        local.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 2L));

        assertThat(node.followsTheLeader()).as("the evicted leader is absent from the eligible view and is not re-adopted").isFalse();
        assertThat(node.harness.state()).as("an empty eligible view yields re-election, not the evicted leader").isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    @Test
    void sequencedEmptyView_isStoredAndAdvancesTheFence() {
        node = Node.following();

        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(), 3L))).as("a newer empty view is not stale").isTrue();
        assertThat(node.ctx.currentTopology()).as("#3092 I1: an applied empty view is the view").isEmpty();
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 2L))).as("older than the empty view").isFalse();
        assertThat(node.ctx.currentTopology()).isEmpty();
    }

    @Test
    void unsequencedWholesaleWrite_changesNothing() {
        node = Node.following();

        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 5L))).isTrue();
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1, N2)))).as("an unsequenced write is dropped").isFalse();
        assertThat(node.ctx.currentTopology()).as("an unsequenced write changes no content").containsExactlyElementsOf(ALL);
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 5L))).as("an unsequenced write moved no fence: 5 is still stale").isFalse();
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1, N2), 6L))).as("an unsequenced write does not raise the fence").isTrue();
        assertThat(node.ctx.currentTopology()).containsExactly(N1, N2);
    }

    /// #3044 3a: after the transport evicted the leader, a SWIM Faulty for ANOTHER peer used to write SWIM's snapshot,
    /// which still carried the leader, so the follower stopped suspecting a leader whose link is down. SWIM now
    /// removes exactly the faulty peer.
    @Test
    void swimFaultyForAnotherPeer_afterTransportEvictionOfTheLeader_keepsTheLeaderSuspected() {
        node = Node.following();
        node.manager.peerDisconnected(new PeerDisconnected(N1, WITHOUT_N1, ObservationSource.QUIC, 2L));
        assertThat(node.suspectsTheLeader()).as("control: the eviction removed the leader from the view").isTrue();

        node.manager.peerObservedFaulty(new PeerObservedFaulty(N3, List.of(N1, N2), ObservationSource.SWIM));

        assertThat(node.ctx.currentTopology()).as("SWIM's snapshot does not restore the evicted leader").containsExactly(N2);
        assertThat(node.suspectsTheLeader()).as("the leader stays suspected").isTrue();
    }

    /// #3044 3b: a leader that loses eligibility is removed view-free. The harness records no event for a transition
    /// (`Fsm` is final, `Transition` carries none), so the dispatched event is read from what it does to the STORED
    /// view: a view-carrying change would rewrite the view wholesale and delete node 4 (a voter-to-be the narrower voter
    /// set filters out), the view-free removal keeps it. The follower leaves `Led` exactly once and names NO suspect.
    /// No pre-vote: the leader is ineligible.
    @Test
    void installVoterConfiguration_inLed_leaderDropped_dispatchesOneViewFreeRemoval_andLeavesLed() {
        node = Node.following(false);
        node.ctx.installVoters(List.of(N1, N2, N3, N4));
        node.manager.peerJoined(new PeerJoined(N4, List.of(N1, N2, N3, N4), ObservationSource.QUIC, 2L));
        var transitionsBefore = node.harness.transitions().size();

        node.manager.installVoterConfiguration(VoterConfiguration.voterConfiguration(2L, List.of(N2, N3)).unwrap());
        node.ctx.installVoters(List.of(N1, N2, N3, N4));

        assertThat(node.harness.transitions().stream().skip(transitionsBefore).filter(transition -> transition.from() instanceof LeaderElectionState.Led))
            .as("the follower leaves Led exactly once").hasSize(1);
        assertThat(node.harness.state()).as("a view-free removal loses the leader naming no suspect")
                                        .isInstanceOfSatisfying(LeaderElectionState.ReElecting.class,
                                                                reElecting -> assertThat(reElecting.suspect()).isEqualTo(Option.none()));
        assertThat(node.ctx.currentTopology()).as("no view-carrying event: the stored view lost the leader only")
                                              .containsExactly(N2, N3, N4);
    }

    /// #3044 3b discriminator: the removal writes exactly the leader out of the STORED view. `currentTopology()` reads
    /// the stored view through the installed voters, so the stored view is read back by widening the voters again.
    /// The old wholesale rewrite stored the eligibility-filtered view and so also deleted node 4 (a voter-to-be that
    /// the narrower voter set had filtered out); the removal deletes node 1 only.
    @Test
    void installVoterConfiguration_inLed_leaderDropped_removesExactlyTheLeaderFromTheStoredView() {
        node = Node.following(false);
        node.ctx.installVoters(List.of(N1, N2, N3, N4));
        node.manager.peerJoined(new PeerJoined(N4, List.of(N1, N2, N3, N4), ObservationSource.QUIC, 2L));
        assertThat(node.ctx.currentTopology()).as("control: the four-node view is stored").containsExactly(N1, N2, N3, N4);

        node.manager.installVoterConfiguration(VoterConfiguration.voterConfiguration(2L, List.of(N2, N3)).unwrap());
        node.ctx.installVoters(List.of(N1, N2, N3, N4));

        assertThat(node.ctx.currentTopology()).as("only the leader left the stored view").containsExactly(N2, N3, N4);
        assertThat(node.harness.state()).as("the leader is lost").isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    /// #3044 S2: whatever SWIM's snapshots carry, interleaved with sequenced transport views, the stored view after
    /// eligibility filtering stays inside the newest applied transport view: a removal never adds.
    @Test
    void removalDelta_neverAdds() {
        node = new Node(LeaderElectionContext::dormant);
        var random = new Random(3044);
        var newest = ALL;
        var snapshotsAddingANode = 0;
        node.manager.peerJoined(new PeerJoined(N1, ALL, ObservationSource.QUIC, 1L));

        for (long sequence = 2; sequence < 400; sequence++) {
            if (random.nextInt(3) == 0) {
                newest = randomView(random);
                node.manager.peerDisconnected(new PeerDisconnected(N1, newest, ObservationSource.QUIC, sequence));
            } else {
                var snapshot = randomView(random);
                snapshotsAddingANode += addsANode(snapshot, node.ctx.currentTopology()) ? 1 : 0;
                node.manager.peerObservedFaulty(new PeerObservedFaulty(ALL.get(random.nextInt(ALL.size())), snapshot, ObservationSource.SWIM));
            }
            assertThat(newest).as("stored view inside the newest transport view at sequence %d", sequence).containsAll(node.ctx.currentTopology());
        }
        assertThat(snapshotsAddingANode).as("control: some snapshots carried a node outside the stored view").isPositive();
    }

    private static List<NodeId> randomView(Random random) {
        var view = ALL.stream().filter(_ -> random.nextBoolean()).toList();

        return view.isEmpty()
               ? List.of(ALL.get(random.nextInt(ALL.size())))
               : view;
    }

    private static boolean addsANode(List<NodeId> snapshot, List<NodeId> stored) {
        return !stored.containsAll(snapshot);
    }

    /// #3044 §3.4: `ClusterFsmRouter` still maps a SWIM Faulty to an unsequenced `NodeGone`; wired into leader
    /// election, the fence drops it, so the router cannot reopen 3a. View and sequence stay as they were.
    @Test
    void clusterFsmRouter_faulty_isDroppedByTheLeaderElectionFence() {
        node = Node.following();
        var router = MessageRouter.mutable();
        ClusterFsmRouter.wire(router, node.harness.fsm(), new AtomicLong());
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 5L))).isTrue();

        router.route(new PeerObservedFaulty(N1, WITHOUT_N1, ObservationSource.SWIM));

        assertThat(node.ctx.currentTopology()).as("the router's Faulty leaves the view").containsExactlyElementsOf(ALL);
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1, N2), 5L))).as("the sequence is still 5").isFalse();
        assertThat(node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1, N2), 6L))).as("the sequence did not move past 5").isTrue();
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

        OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeAdded(N1, ALL, 5L));
        var electing = (LeaderElectionState.Electing) node.harness.state();
        var afterFresh = electing.tickFuture().get();
        OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 4L));

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

        OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 2L));
        OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeAdded(N1, ALL, 1L));

        assertThat(node.ctx.currentTopology()).as("in %s", state).containsExactlyElementsOf(WITHOUT_N1);
    }

    @ParameterizedTest
    @EnumSource(StoringState.class)
    void olderNodeGone_afterANewerNodeAdded_doesNotRemoveThePeer(StoringState state) {
        node = new Node(state.initial);

        OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeAdded(N1, ALL, 2L));
        OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 1L));

        assertThat(node.ctx.currentTopology()).as("in %s", state).containsExactlyElementsOf(ALL);
    }

    // ---- #3044 P1a: the view-free removal (`PeerRemovedFromView`, `LeaderElectionContext.removeFromView`) ----

    @Test
    void removal_lastNode_keepsView() {
        node = new Node(LeaderElectionContext::dormant);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, List.of(N1), 1L));

        assertThat(node.ctx.removeFromView(N1)).as("removing the last node is not applied").isFalse();
        assertThat(node.ctx.currentTopology()).as("a view-free removal never empties the view (only a sequenced view may, I1)").containsExactly(N1);
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

    /// #3047: the transport still lists the removed node, so the re-assertion a removal arms re-reads the transport view
    /// under a fresh sequence and the node returns within the bound, with no other transport observation.
    @Test
    void removalAfterNewerRestore_nodeReturnsWithinBound() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var sequence = new AtomicLong(6L);
        node.ctx.installTransportView(() -> Option.some(TransportView.transportView(ALL, sequence.incrementAndGet())),
                                      TimeSpan.timeSpan(200).millis());
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1, N2), 5L));
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N3, ALL, 6L));

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N3));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (! node.ctx.currentTopology().contains(N3) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertThat(node.ctx.currentTopology()).as("re-asserted within the period, no other transport observation").contains(N3);
    }

    /// #3047, the binding ruling on #3092: the re-assertion adds only what the transport lists. A node the transport
    /// dropped as well stays out, so an evicted node is never restored by it.
    @Test
    void reassertion_transportAlsoDroppedNode_staysOut() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var reads = new AtomicInteger();
        var sequence = new AtomicLong(6L);
        node.ctx.installTransportView(() -> countedView(reads, WITHOUT_N3, sequence), TimeSpan.timeSpan(100).millis());
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 6L));

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N3));
        awaitReads(reads, 1);

        assertThat(node.ctx.currentTopology()).as("the transport does not list node-3").containsExactly(N1, N2);
    }

    /// #3047: removals inside one period share one re-assertion, so a burst costs one transport read, and a removal
    /// after it arms the next.
    @Test
    void reassertion_removalsInOnePeriod_shareOneRead() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var reads = new AtomicInteger();
        var sequence = new AtomicLong(6L);
        node.ctx.installTransportView(() -> countedView(reads, ALL, sequence), TimeSpan.timeSpan(300).millis());
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 6L));

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N3));
        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N1));
        awaitReads(reads, 1);
        Thread.sleep(600);

        assertThat(reads.get()).as("one read for the burst").isEqualTo(1);
        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N3));
        awaitReads(reads, 2);
        assertThat(node.ctx.currentTopology()).as("restored again").containsExactlyElementsOf(ALL);
    }

    /// #3047: the manager's `installTransportView` is the production seam (the node wires the transport through it), and
    /// it re-asserts within the default bound.
    @Test
    void installedThroughTheManager_nodeReturnsWithinTheDefaultBound() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var sequence = new AtomicLong(6L);
        node.manager.installTransportView(() -> Option.some(TransportView.transportView(ALL, sequence.incrementAndGet())));
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 6L));

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N3));
        var deadline = System.nanoTime() + LeaderElectionContext.DEFAULT_REASSERT_PERIOD.nanos() * 3;
        while (!node.ctx.currentTopology().contains(N3) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertThat(node.ctx.currentTopology()).as("re-asserted within the default period").containsExactlyElementsOf(ALL);
    }

    /// #3047: a host with no transport view (local election, stubs) never re-asserts.
    @Test
    void reassertion_withoutTransportView_neverRestores() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 6L));

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N3));
        Thread.sleep(400);

        assertThat(node.ctx.currentTopology()).containsExactly(N1, N2);
    }

    /// #3047: the re-assertion disarms BEFORE it reads the transport, so a removal that lands during the read arms the
    /// next re-assertion: the read hook removes node-1, and a second read follows.
    @Test
    void reassertion_removalDuringTheRead_armsTheNextReassertion() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var reads = new AtomicInteger();
        var sequence = new AtomicLong(6L);
        var midReadRemovalApplied = new AtomicBoolean();
        node.ctx.installTransportView(() -> removingOnFirstRead(reads, sequence, midReadRemovalApplied),
                                      TimeSpan.timeSpan(150).millis());
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 6L));

        node.ctx.removeFromView(N3);
        awaitReads(reads, 2);
        Thread.sleep(400);

        assertThat(midReadRemovalApplied.get()).as("the removal inside the read was applied").isTrue();
        assertThat(reads.get()).as("the removal during the read armed exactly one more read").isEqualTo(2);
        assertThat(node.ctx.currentTopology()).containsExactlyElementsOf(ALL);
    }

    /// #3047: a removal that does not apply arms nothing. Self is never removed, so no transport read follows.
    @Test
    void reassertion_removalOfSelf_armsNothing() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var reads = new AtomicInteger();
        var sequence = new AtomicLong(6L);
        node.ctx.installTransportView(() -> countedView(reads, ALL, sequence), TimeSpan.timeSpan(100).millis());
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 6L));

        assertThat(node.ctx.removeFromView(N2)).as("node-2 is self").isFalse();
        Thread.sleep(400);

        assertThat(reads.get()).as("no read after a removal that did not apply").isZero();
    }

    /// #3047: a removal that does not apply arms nothing. The last node is never removed, so no transport read follows.
    @Test
    void reassertion_removalOfTheLastNode_armsNothing() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var reads = new AtomicInteger();
        var sequence = new AtomicLong(6L);
        node.ctx.installTransportView(() -> countedView(reads, ALL, sequence), TimeSpan.timeSpan(100).millis());
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, List.of(N1), 6L));

        assertThat(node.ctx.removeFromView(N1)).as("removing the last node is not applied").isFalse();
        Thread.sleep(400);

        assertThat(reads.get()).as("no read after a removal that did not apply").isZero();
    }

    /// #3047: each re-assertion disarms, so a removal after it arms the next one: three sequential removals are each
    /// restored. Asserted on the view alone, independent of the read count the burst tests use.
    @Test
    void reassertion_sequentialRemovals_eachIsRestored() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var sequence = new AtomicLong(6L);
        node.ctx.installTransportView(() -> Option.some(TransportView.transportView(ALL, sequence.incrementAndGet())),
                                      TimeSpan.timeSpan(150).millis());
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 6L));

        for (var round = 0; round < 3; round++) {
            assertThat(node.ctx.removeFromView(N3)).as("round " + round + " removes node-3").isTrue();
            awaitTopologyContains(N3);
            assertThat(node.ctx.currentTopology()).as("round " + round + " restored").containsExactlyElementsOf(ALL);
        }
    }

    /// #3047: removals inside one period share one re-assertion, through the context directly (no FSM dispatch): five
    /// applied removals cost one transport read.
    @Test
    void reassertion_directRemovalsInOnePeriod_shareOneRead() throws InterruptedException {
        node = new Node(LeaderElectionContext::dormant);
        var reads = new AtomicInteger();
        var sequence = new AtomicLong(6L);
        node.ctx.installTransportView(() -> countedView(reads, ALL, sequence), TimeSpan.timeSpan(400).millis());
        node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 6L));

        for (var i = 0; i < 5; i++) {
            node.ctx.applyTopology(new ClusterFsmEvent.NodeAdded(N1, ALL, 7L + i));
            assertThat(node.ctx.removeFromView(N3)).as("removal " + i + " applies").isTrue();
        }
        Thread.sleep(1500);

        assertThat(reads.get()).as("one read for the five removals").isEqualTo(1);
    }

    private Option<TransportView> removingOnFirstRead(AtomicInteger reads, AtomicLong sequence, AtomicBoolean applied) {
        if (reads.incrementAndGet() == 1) {
            applied.set(node.ctx.removeFromView(N1));
        }

        return Option.some(TransportView.transportView(ALL, sequence.incrementAndGet()));
    }

    private void awaitTopologyContains(NodeId expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (!node.ctx.currentTopology().contains(expected) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static Option<TransportView> countedView(AtomicInteger reads, List<NodeId> view, AtomicLong sequence) {
        reads.incrementAndGet();
        return Option.some(TransportView.transportView(view, sequence.incrementAndGet()));
    }

    private static void awaitReads(AtomicInteger reads, int expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (reads.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
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
        OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeAdded(N1, ALL, 5L));

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N3));

        assertThat(node.ctx.currentTopology()).as("in %s", state).containsExactly(N1, N2);
    }

    @Test
    void removalOfLeader_inLed_losesLeader() {
        node = Node.following(false);

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N1));

        assertThat(node.ctx.currentTopology()).containsExactlyElementsOf(WITHOUT_N1);
        assertThat(node.harness.state()).as("the leader is lost").isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    @Test
    void removalOfAnotherPeer_inLed_keepsTheLeader() {
        node = Node.following(false);

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N3));

        assertThat(node.ctx.currentTopology()).containsExactly(N1, N2);
        assertThat(node.followsTheLeader()).isTrue();
    }

    /// D-S1: the storage keeps the view, yet the leader is lost. Arm 1: the leader is the last node of the stored view.
    @Test
    void removal_lastNode_ofLeader_inLed_losesLeader_keepsView() {
        node = Node.following(false);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1), 5L));
        assertThat(node.ctx.removeFromView(N1)).as("control: the storage keeps the last node").isFalse();

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N1));

        assertThat(node.ctx.currentTopology()).as("the view is kept").containsExactly(N1);
        assertThat(node.harness.state()).as("the leader is lost all the same").isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    /// D-S1, arm 2: the leader is already absent from the stored view (written without dispatching).
    @Test
    void removal_absentLeader_inLed_losesLeader_keepsView() {
        node = Node.following(false);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N1, WITHOUT_N1, 5L));
        assertThat(node.followsTheLeader()).as("control: the direct write left Led alone").isTrue();

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N1));

        assertThat(node.ctx.currentTopology()).as("the view is kept").containsExactlyElementsOf(WITHOUT_N1);
        assertThat(node.harness.state()).as("the leader is lost all the same").isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    /// n3: with the pre-vote enabled a removal of the leader goes through `loseLeader`'s pre-vote path. The leader is the
    /// last node of the stored view (kept, so still eligible), so the pre-vote asks the electorate and the node stays in
    /// `Led`; a bypass straight to `ReElecting` sends no question.
    @Test
    void removalOfLeader_inLed_withPreVote_asksTheElectorate_andStaysInLed() {
        node = Node.following(true);
        node.ctx.applyTopology(new ClusterFsmEvent.NodeGone(N3, List.of(N1), 5L));
        var before = node.sends.get();

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N1));

        assertThat(node.sends.get()).as("the pre-vote question went out").isGreaterThan(before);
        assertThat(node.followsTheLeader()).as("the pre-vote keeps Led until the verdict").isTrue();
    }

    /// n6 (design 3.1): a removal-driven loss of the leader names no suspect.
    @Test
    void removalOfLeader_inLed_leavesNoSuspect() {
        node = Node.following(false);

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N1));

        assertThat(node.harness.state()).isInstanceOfSatisfying(LeaderElectionState.ReElecting.class,
                                                                reElecting -> assertThat(reElecting.suspect()).isEqualTo(Option.none()));
    }

    /// n1: a leader removed as SELF still steps down, the same end state as the base `NodeGone(self)`; the pre-vote
    /// filter excludes self, so even with the pre-vote enabled the node goes to `ReElecting`.
    @Test
    void removalOfSelf_asLeader_inLed_stepsDown() {
        node = Node.following(true);
        commitLeaderOnDrainer(node.harness, N2, 2L);
        assertThat(node.harness.state()).as("control: self leads")
                  .isInstanceOfSatisfying(LeaderElectionState.Led.class, led -> assertThat(led.leader()).isEqualTo(N2));

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N2));

        assertThat(node.harness.state()).isInstanceOf(LeaderElectionState.ReElecting.class);
    }

    @ParameterizedTest
    @EnumSource(value = RemovingState.class, names = {"ELECTING", "RE_ELECTING"})
    void removalInAnElectingState_reschedulesOnlyWhenApplied(RemovingState state) {
        node = state.node.get();
        OnDrainer.dispatch(node.harness, new ClusterFsmEvent.NodeAdded(N1, List.of(N1, N2), 5L));
        var tick = electionTick(node.harness.state());
        var afterAdded = tick.get();

        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N3));
        assertThat(tick.get() == afterAdded).as("in %s: removing an absent node leaves the tick alone", state).isTrue();
        OnDrainer.dispatch(node.harness, new PeerRemovedFromView(N1));

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
