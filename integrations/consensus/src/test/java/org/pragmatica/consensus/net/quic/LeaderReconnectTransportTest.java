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
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// NT-A10 (cluster T, PR-A2b): a follower's link to its leader is evicted and re-attached with no other topology
/// event. The follower's real [LeaderManager] is fed by a real transport's `TransportObservation` stream (the
/// routes `RabiaNode` registers), so the producer and the consumer are both real. The eviction's `PeerDisconnected`
/// removes the leader from the follower's stored view; the re-attach's `PeerReconnected` must put it back, or the
/// follower keeps suspecting a leader it is connected to (and answers pre-votes "doubt"). Mutation MT-A8: restore the
/// empty `peerReconnected` body. The unit-level twin is `LeaderReconnectTripwireTest`.
@Timeout(60)
class LeaderReconnectTransportTest {
    private static final NodeId FOLLOWER = new NodeId("aaa-follower");
    private static final NodeId LEADER = new NodeId("zzz-leader");
    private static final List<NodeId> ALL = List.of(FOLLOWER, LEADER);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private FsmBackedLeaderManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    private FsmBackedLeaderManager followerRoutedFrom(MessageRouter.MutableRouter router) {
        LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> Promise.success(Unit.unit());
        var built = (FsmBackedLeaderManager) LeaderManager.leaderManager(FOLLOWER, router, handler, ALL);

        router.addRoute(PeerJoined.class, built::peerJoined);
        router.addRoute(PeerDisconnected.class, built::peerDisconnected);
        router.addRoute(PeerReconnected.class, built::peerReconnected);
        router.addRoute(Send.class, (Consumer<Send>) _ -> {});
        built.context().enablePreVote(LONG);
        built.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());
        built.fsm().dispatch(new ClusterFsmEvent.NodeAdded(FOLLOWER, ALL));
        built.fsm().dispatch(new ConsensusReady());
        built.fsm().dispatch(new LeaderCommitted(LEADER, 1L));

        return built;
    }

    private boolean followsTheLeader() {
        return manager.fsm().current() instanceof LeaderElectionState.Led led && led.leader().equals(LEADER);
    }

    /// What a timed-out wait needs to be read: the follower's FSM state, its view of the electorate, and both ends of the link.
    private String linkState(QuicClusterNetwork follower, QuicClusterNetwork leader) {
        return "follower FSM " + manager.fsm().current()
               + ", follower topology " + manager.context().currentTopology()
               + ", follower sees leader connected=" + connected(follower, LEADER)
               + ", leader sees follower connected=" + connected(leader, FOLLOWER);
    }

    private boolean suspectsTheLeader() {
        return manager.fsm().current() instanceof LeaderElectionState.Led led && led.isLeaderSuspected();
    }

    @Test
    void followerLeaderLink_evictedAndReattached_leavesTheFollowerNotSuspectingTheLeader() {
        var followerRouter = MessageRouter.mutable();

        manager = followerRoutedFrom(followerRouter);
        var follower = startedNetwork(new TripwireTopology(FOLLOWER, false, TimeSpan.timeSpan(30).seconds()), followerRouter, networks);
        var leader = startedNetwork(new TripwireTopology(LEADER, false, TimeSpan.timeSpan(30).seconds()), MessageRouter.mutable(), networks);
        var leaderInfo = infoOf(LEADER, leader);

        follower.dialForTests(leaderInfo, false);
        awaitTrue(() -> connected(follower, LEADER) && connected(leader, FOLLOWER), AWAIT, "both ends CONNECTED");
        awaitTrue(() -> manager.context().currentTopology().contains(LEADER), AWAIT, "the join put the leader in the follower's view");
        assertThat(followsTheLeader()).as("control: the follower follows the leader").isTrue();

        follower.peerStateForTests(LEADER).evict(System.nanoTime()).onPresent(connection -> connection.close().await(AWAIT));
        awaitTrue(this::suspectsTheLeader, AWAIT, () -> "the eviction removed the leader from the follower's view; " + linkState(follower, leader));
        awaitTrue(() -> !connected(leader, FOLLOWER), AWAIT, () -> "the leader noticed the closed link; " + linkState(follower, leader));
        assertThat(followsTheLeader()).as("control: pre-vote holds the follower in Led while it asks").isTrue();

        follower.dialForTests(leaderInfo, false);
        awaitTrue(() -> connected(follower, LEADER), AWAIT, () -> "the link is back; " + linkState(follower, leader));
        awaitTrue(() -> manager.context().currentTopology().contains(LEADER),
                  AWAIT,
                  () -> "the reconnect restored the leader to the follower's view; " + linkState(follower, leader));

        assertThat(suspectsTheLeader()).as("a follower connected to its leader again does not suspect it").isFalse();
        assertThat(followsTheLeader()).as("the follower still follows the leader").isTrue();
    }
}
