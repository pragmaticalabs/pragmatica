/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net.quic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.leader.fsm.LeaderElectionState;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// #2971: the transport routes each view-carrying observation synchronously on the thread that made the peer
/// transition — `PeerJoined` on the dial thread, `PeerDisconnected` on the evicting one — so a join whose handler is
/// slow can reach the follower's [LeaderManager] AFTER a newer eviction of the same link (the race #2969 saw in CI,
/// SLOW-HANDLER 5 ms). The leader FSM stores its view by replacement, so the stale join's view put the evicted leader
/// back and cleared the suspicion the eviction raised.
///
/// The race is made deterministic: a route registered ahead of the manager's holds the transport's `PeerJoined(leader)`
/// on the dial thread until the eviction's `PeerDisconnected` has been fully handled, then lets the join through.
/// Producer (real transport) and consumer (real manager and FSM) are both real; only the interleaving is forced.
/// RED before the per-transport observation sequence: the late join restores the leader.
@Timeout(60)
class PeerObservationOrderTransportTest {
    private static final NodeId FOLLOWER = new NodeId("aaa-follower");
    private static final NodeId LEADER = new NodeId("zzz-leader");
    private static final List<NodeId> ALL = List.of(FOLLOWER, LEADER);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private final CountDownLatch joinHeld = new CountDownLatch(1);
    private final CountDownLatch evictionHandled = new CountDownLatch(1);
    private final CountDownLatch releaseJoin = new CountDownLatch(1);
    private final AtomicBoolean joinHandled = new AtomicBoolean();
    private final AtomicBoolean joinOutlivedTheGate = new AtomicBoolean();
    private FsmBackedLeaderManager manager;

    @AfterEach
    void tearDown() {
        releaseJoin.countDown();
        if (manager != null) {
            manager.stop();
        }
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    /// Holds the leader's join on the dial thread until the test releases it, after the eviction has been handled and
    /// checked (or until the gate times out, which the test reports rather than hangs on).
    private void holdTheLeaderJoin(PeerJoined joined) {
        if (!joined.nodeId().equals(LEADER)) {
            return;
        }

        joinHeld.countDown();
        joinOutlivedTheGate.set(!awaitQuietly(releaseJoin));
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    private FsmBackedLeaderManager followerRoutedFrom(MessageRouter.MutableRouter router) {
        LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> Promise.success(Unit.unit());
        var built = (FsmBackedLeaderManager) LeaderManager.leaderManager(FOLLOWER, router, handler, ALL);

        // Registered BEFORE the manager's route, so it runs first on the routing thread (CopyOnWriteArrayList order).
        router.addRoute(PeerJoined.class, (Consumer<PeerJoined>) this::holdTheLeaderJoin);
        router.addRoute(PeerJoined.class, built::peerJoined);
        router.addRoute(PeerDisconnected.class, built::peerDisconnected);
        router.addRoute(PeerReconnected.class, built::peerReconnected);
        // Registered AFTER the manager's routes, so each fires once that handler has returned.
        router.addRoute(PeerJoined.class, (Consumer<PeerJoined>) joined -> joinHandled.compareAndSet(false, joined.nodeId().equals(LEADER)));
        router.addRoute(PeerDisconnected.class, (Consumer<PeerDisconnected>) _ -> evictionHandled.countDown());
        router.addRoute(Send.class, (Consumer<Send>) _ -> {});
        built.context().enablePreVote(LONG);
        built.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());
        built.fsm().dispatch(new ClusterFsmEvent.NodeAdded(FOLLOWER, ALL));
        built.fsm().dispatch(new ConsensusReady());
        built.fsm().dispatch(new LeaderCommitted(LEADER, 1L));

        return built;
    }

    private boolean suspectsTheLeader() {
        return manager.fsm().current() instanceof LeaderElectionState.Led led && led.isLeaderSuspected();
    }

    private boolean followsTheLeader() {
        return manager.fsm().current() instanceof LeaderElectionState.Led led && led.leader().equals(LEADER);
    }

    private String followerState() {
        return "follower FSM " + manager.fsm().current() + ", follower topology " + manager.context().currentTopology();
    }

    @Test
    void lateJoin_afterANewerEvictionOfTheSameLink_leavesTheLeaderSuspected() {
        var followerRouter = MessageRouter.mutable();

        manager = followerRoutedFrom(followerRouter);
        var follower = startedNetwork(new TripwireTopology(FOLLOWER, false, TimeSpan.timeSpan(30).seconds()), followerRouter, networks);
        var leader = startedNetwork(new TripwireTopology(LEADER, false, TimeSpan.timeSpan(30).seconds()), MessageRouter.mutable(), networks);

        follower.dialForTests(infoOf(LEADER, leader), false);
        assertThat(awaitQuietly(joinHeld)).as("the transport routed PeerJoined(leader) and the gate holds it").isTrue();
        assertThat(connected(follower, LEADER)).as("control: the join's transition is done, its observation is in flight").isTrue();

        follower.peerStateForTests(LEADER).evict(System.nanoTime()).onPresent(connection -> connection.close().await(AWAIT));
        assertThat(awaitQuietly(evictionHandled)).as("the eviction's PeerDisconnected was handled while the join was held").isTrue();
        assertThat(suspectsTheLeader()).as("control: the eviction removed the leader from the view; " + followerState()).isTrue();
        assertThat(joinHandled.get()).as("control: the join is still held").isFalse();

        releaseJoin.countDown();

        awaitTrue(joinHandled::get, AWAIT, () -> "the held join reached the manager after the eviction; " + followerState());

        assertThat(joinOutlivedTheGate.get()).as("the join was released by the test, not by the gate's timeout").isFalse();
        assertThat(manager.context().currentTopology()).as("the late join's older view does not restore the evicted leader").doesNotContain(LEADER);
        assertThat(suspectsTheLeader()).as("the eviction wins: the follower still suspects the leader; " + followerState()).isTrue();
        assertThat(followsTheLeader()).as("control: pre-vote holds the follower in Led while it asks").isTrue();
    }

    /// The producer side of the fence: join, eviction and re-attach of one link each carry a transport sequence, above
    /// [TransportObservation#NO_SEQUENCE] and increasing in transport order.
    @Test
    void processViewChange_stampsJoinEvictionAndReconnect_withIncreasingSequences() {
        var followerRouter = MessageRouter.mutable();
        var sequences = new CopyOnWriteArrayList<String>();

        followerRouter.addRoute(PeerJoined.class, (Consumer<PeerJoined>) joined -> sequences.add("joined:" + joined.sequence()));
        followerRouter.addRoute(PeerDisconnected.class, (Consumer<PeerDisconnected>) gone -> sequences.add("disconnected:" + gone.sequence()));
        followerRouter.addRoute(PeerReconnected.class, (Consumer<PeerReconnected>) back -> sequences.add("reconnected:" + back.sequence()));
        followerRouter.addRoute(Send.class, (Consumer<Send>) _ -> {});
        var follower = startedNetwork(new TripwireTopology(FOLLOWER, false, TimeSpan.timeSpan(30).seconds()), followerRouter, networks);
        var leader = startedNetwork(new TripwireTopology(LEADER, false, TimeSpan.timeSpan(30).seconds()), MessageRouter.mutable(), networks);
        var leaderInfo = infoOf(LEADER, leader);

        follower.dialForTests(leaderInfo, false);
        awaitTrue(() -> sequences.size() == 1, AWAIT, () -> "the join was observed; " + sequences);
        follower.peerStateForTests(LEADER).evict(System.nanoTime()).onPresent(connection -> connection.close().await(AWAIT));
        awaitTrue(() -> sequences.size() == 2, AWAIT, () -> "the eviction was observed; " + sequences);
        awaitTrue(() -> !connected(leader, FOLLOWER), AWAIT, () -> "the leader noticed the closed link; " + sequences);
        follower.dialForTests(leaderInfo, false);
        awaitTrue(() -> sequences.size() == 3, AWAIT, () -> "the re-attach was observed; " + sequences);

        assertThat(sequences).as("one link's transport-ordered observations").containsExactly("joined:1", "disconnected:2", "reconnected:3");
    }
}
