package org.pragmatica.consensus.leader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.leader.LeaderNotification.leaderChange;
import static org.pragmatica.consensus.topology.TransportObservation.ObservationSource.QUIC;
import static org.pragmatica.consensus.topology.TransportObservation.ObservationSource.SWIM;
import static org.pragmatica.consensus.topology.TransportObservation.peerDisconnected;
import static org.pragmatica.consensus.topology.TransportObservation.peerJoined;
import static org.pragmatica.consensus.topology.TransportObservation.peerObservedFaulty;
import static org.pragmatica.consensus.topology.TransportObservation.peerReconnected;

/// #3044 P2: in local mode a departure is applied to the stored view first, the leader is adopted from the STORED view
/// next, and the event is dispatched last. A stale eviction therefore moves nothing, and a fresh one moves the leader
/// without an intermediate "no leader". Local-mode notifications are synchronous, so every assertion reads them
/// directly after routing (no timing).
class LeaderManagerLocalModeOrderTest {
    record Watcher(List<LeaderNotification> collected) {
        @MessageReceiver
        public void watch(LeaderChange notification) {
            collected.add(notification);
        }
    }

    private final NodeId nodeA = NodeId.nodeId("node-a").unwrap();
    private final NodeId self = NodeId.nodeId("node-b").unwrap();
    private final NodeId nodeC = NodeId.nodeId("node-c").unwrap();
    private final List<NodeId> all = List.of(nodeA, self, nodeC);
    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final Watcher watcher = new Watcher(new ArrayList<>());

    @BeforeEach
    void setUp() {
        router.addRoute(LeaderChange.class, watcher::watch);

        var leaderManager = LeaderManager.leaderManager(self, router);
        router.addRoute(PeerJoined.class, leaderManager::peerJoined);
        router.addRoute(PeerDisconnected.class, leaderManager::peerDisconnected);
        router.addRoute(PeerReconnected.class, leaderManager::peerReconnected);
        router.addRoute(PeerObservedFaulty.class, leaderManager::peerObservedFaulty);
        router.addRoute(ClusterStateNotification.class, leaderManager::watchClusterState);

        startClusterLedBy(nodeA);
    }

    /// 5b: the eviction at sequence 4 is routed after the reconnect at sequence 5, so the newer view already holds
    /// the leader. Adopting from the stale event's view (before the fence) would move the leader to node-b.
    @Test
    void localMode_staleEvictionAfterNewerReconnect_keepsTheLeader() {
        router.route(peerReconnected(nodeA, all, QUIC, 5));
        router.route(peerDisconnected(nodeA, List.of(self, nodeC), QUIC, 4));

        assertThat(watcher.collected()).containsExactly(leaderChange(Option.some(nodeA), false));
    }

    /// A fresh eviction of the leader moves it straight to the next node: adoption precedes the dispatch, so `Led`
    /// never loses the leader on the way (which would notify "no leader" first).
    @Test
    void localMode_freshEviction_noIntermediateNoLeader() {
        router.route(peerDisconnected(nodeA, List.of(self, nodeC), QUIC, 4));

        assertThat(watcher.collected()).containsExactly(leaderChange(Option.some(nodeA), false),
                                                        leaderChange(Option.some(self), true));
    }

    /// SWIM-FAULTY of the leader: same order, the removal and the adoption precede the dispatch.
    @Test
    void localMode_faultyLeader_noIntermediateNoLeader() {
        router.route(peerObservedFaulty(nodeA, List.of(self, nodeC), SWIM));

        assertThat(watcher.collected()).containsExactly(leaderChange(Option.some(nodeA), false),
                                                        leaderChange(Option.some(self), true));
    }

    /// SWIM-FAULTY of a follower: the adoption reads the STORED view (node-c removed, node-a kept), never SWIM's
    /// snapshot, which here lacks the healthy leader.
    @Test
    void localMode_faultyFollower_adoptsFromTheStoredView() {
        router.route(peerObservedFaulty(nodeC, List.of(self), SWIM));

        assertThat(watcher.collected()).containsExactly(leaderChange(Option.some(nodeA), false));
    }

    /// Refused removal, self = node-a leads and SWIM reports SELF faulty: the removal is refused (the last node
    /// stays), so nothing is adopted and nothing is dispatched; a refused removal must not cost the leader.
    @Test
    void localMode_swimFaultyOfSelf_whileSelfLeads_keepsTheLeader() {
        var run = freshRun(nodeA);

        run.router().route(peerJoined(nodeA, List.of(nodeA), QUIC, 1));
        run.router().route(peerJoined(self, List.of(nodeA, self), QUIC, 2));
        run.router().route(ClusterStateNotification.active());
        run.router().route(peerJoined(nodeC, all, QUIC, 3));
        run.router().route(peerObservedFaulty(nodeA, List.of(self, nodeC), SWIM));

        assertThat(run.watcher().collected()).containsExactly(leaderChange(Option.some(nodeA), true));
    }

    /// Refused removal, self = node-b; the stored view is [node-a] (the last node) and node-a leads. SWIM reports
    /// node-a faulty: the removal is refused, so nothing is adopted and nothing is dispatched.
    @Test
    void localMode_swimFaultyOfLeader_lastInStoredView_keepsTheLeader() {
        var run = freshRun(self);

        run.router().route(peerJoined(nodeA, List.of(nodeA), QUIC, 1));
        run.router().route(ClusterStateNotification.active());
        run.router().route(peerObservedFaulty(nodeA, List.of(self), SWIM));

        assertThat(run.watcher().collected()).containsExactly(leaderChange(Option.some(nodeA), false));
    }

    /// A dropped `applyTopology` gate: a lower node reconnects after the leader moved on (a reconnect never adopts), so
    /// the stored view's first node differs from the leader; an older eviction routed late must then change nothing.
    /// Without the gate it would adopt the stored first node and swap the leader.
    @Test
    void localMode_staleEvictionAfterLowerNodeReconnect_keepsTheAdoptedLeader() {
        router.route(peerDisconnected(nodeA, List.of(self, nodeC), QUIC, 4));
        router.route(peerReconnected(nodeA, all, QUIC, 5));
        router.route(peerDisconnected(nodeC, List.of(self, nodeA), QUIC, 3));

        assertThat(watcher.collected()).containsExactly(leaderChange(Option.some(nodeA), false),
                                                        leaderChange(Option.some(self), true));
    }

    /// A dropped `removeFromView` gate: a lower node has reconnected without being adopted (leader is node-b, stored
    /// first is node-a); a SWIM-FAULTY report of a peer the view never held removes nothing, so it must not adopt
    /// (adopting would swap the leader to node-a).
    @Test
    void localMode_faultyOfAnAbsentPeer_adoptsNothing() {
        var absent = NodeId.nodeId("node-z").unwrap();

        router.route(peerDisconnected(nodeA, List.of(self, nodeC), QUIC, 4));
        router.route(peerReconnected(nodeA, all, QUIC, 5));
        router.route(peerObservedFaulty(absent, all, SWIM));

        assertThat(watcher.collected()).containsExactly(leaderChange(Option.some(nodeA), false),
                                                        leaderChange(Option.some(self), true));
    }

    private record Run(MessageRouter.MutableRouter router, Watcher watcher) {}

    /// A second, independent wiring of a local-mode manager for `node`, for tests that need another self than the
    /// one `setUp` started.
    private Run freshRun(NodeId node) {
        var freshRouter = MessageRouter.mutable();
        var freshWatcher = new Watcher(new ArrayList<>());

        freshRouter.addRoute(LeaderChange.class, freshWatcher::watch);

        var leaderManager = LeaderManager.leaderManager(node, freshRouter);
        freshRouter.addRoute(PeerJoined.class, leaderManager::peerJoined);
        freshRouter.addRoute(PeerObservedFaulty.class, leaderManager::peerObservedFaulty);
        freshRouter.addRoute(ClusterStateNotification.class, leaderManager::watchClusterState);

        return new Run(freshRouter, freshWatcher);
    }

    /// Joins at sequences 1..3; quorum arrives before the last join, which elects the lowest node.
    private void startClusterLedBy(NodeId expectedLeader) {
        router.route(peerJoined(nodeA, List.of(nodeA), QUIC, 1));
        router.route(peerJoined(self, List.of(nodeA, self), QUIC, 2));
        router.route(ClusterStateNotification.active());
        router.route(peerJoined(nodeC, all, QUIC, 3));

        assertThat(watcher.collected()).containsExactly(leaderChange(Option.some(expectedLeader), false));
    }
}
