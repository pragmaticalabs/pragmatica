package org.pragmatica.consensus.leader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.consensus.leader.LeaderNotification.leaderChange;
import static org.pragmatica.consensus.topology.TopologyChangeNotification.nodeAdded;
import static org.pragmatica.consensus.topology.TopologyChangeNotification.nodeRemoved;

class LeaderManagerTest {
    record Watcher<T>(List<T> collected) {
        @MessageReceiver
        public void watch(T notification) {
            collected.add(notification);
        }
    }

    // Use deterministic IDs to ensure predictable ordering (a < b < c)
    private final NodeId self = NodeId.nodeId("node-b").unwrap();
    private final List<NodeId> nodes = List.of(NodeId.nodeId("node-a").unwrap(), self, NodeId.nodeId("node-c").unwrap());

    @Nested
    class LocalElectionMode {
        private final MessageRouter.MutableRouter router = MessageRouter.mutable();
        private final Watcher<LeaderNotification> watcher = new Watcher<>(new ArrayList<>());

        @BeforeEach
        void setUp() {
            watcher.collected().clear();
            router.addRoute(LeaderChange.class, watcher::watch);

            // LeaderManager uses @MessageReceiver annotations, routes added manually
            var leaderManager = LeaderManager.leaderManager(self, router);
            router.addRoute(NodeAdded.class, leaderManager::nodeAdded);
            router.addRoute(NodeRemoved.class, leaderManager::nodeRemoved);
            router.addRoute(NodeDown.class, leaderManager::nodeDown);
            router.addRoute(QuorumStateNotification.class, leaderManager::watchQuorumState);
        }

        @Test
        void onTopologyChange_nodesAddedThenQuorum_electsLeader() {
            var expected = simulateClusterStart(router, watcher);

            // When quorum disappears, we should see disappearance of the leader
            expected.add(leaderChange(Option.none(), false));
            router.route(QuorumStateNotification.DISAPPEARED);

            assertThat(watcher.collected()).isEqualTo(expected);
        }

        @Test
        void onTopologyChange_nodesAddedAndRemoved_leaderStaysStable() {
            var expected = simulateClusterStart(router, watcher);

            // Use deterministic ID that sorts after existing nodes (a < b < c < d)
            sendNodeAdded(router, nodeId("node-d").unwrap());
            sendNodeRemoved(router, nodes.getLast());

            // When quorum disappears, we should see disappearance of the leader
            expected.add(leaderChange(Option.none(), false));
            router.route(QuorumStateNotification.DISAPPEARED);

            assertThat(watcher.collected()).isEqualTo(expected);
        }

        @Test
        void onTopologyChange_leaderRemoved_electsNewLeader() {
            var expected = simulateClusterStart(router, watcher);

            // Remove the leader (node-a, which is first in sorted order)
            sendNodeRemoved(router, nodes.getFirst());
            // New leader should be the next in sorted order (self = node-b)
            expected.add(leaderChange(Option.option(self), true));

            // When quorum disappears, we should see disappearance of the leader
            expected.add(leaderChange(Option.none(), false));
            router.route(QuorumStateNotification.DISAPPEARED);

            assertThat(watcher.collected()).isEqualTo(expected);
        }
    }

    @Nested
    class ConsensusElectionMode {
        private final MessageRouter.MutableRouter router = MessageRouter.mutable();
        private final Watcher<LeaderNotification> watcher = new Watcher<>(new CopyOnWriteArrayList<>());
        private final List<LeaderProposal> proposals = new CopyOnWriteArrayList<>();
        private LeaderManager leaderManager;

        record LeaderProposal(NodeId candidate, long viewSequence) {}

        @BeforeEach
        void setUp() {
            watcher.collected().clear();
            proposals.clear();
            router.addRoute(LeaderChange.class, watcher::watch);

            // Consensus-based leader election with proposal handler that records proposals
            LeaderManager.LeaderProposalHandler proposalHandler = (candidate, viewSequence) -> {
                proposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };

            leaderManager = LeaderManager.leaderManager(self, router, proposalHandler);
            router.addRoute(NodeAdded.class, leaderManager::nodeAdded);
            router.addRoute(NodeRemoved.class, leaderManager::nodeRemoved);
            router.addRoute(NodeDown.class, leaderManager::nodeDown);
            router.addRoute(QuorumStateNotification.class, leaderManager::watchQuorumState);
        }

        @Test
        void onTopologyChange_nodesAddedThenQuorum_submitsProposal() {
            // Add nodes
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                var topology = list.stream().sorted().toList();
                router.route(nodeAdded(nodeId, topology));
            }

            // Establish quorum - this should trigger a proposal (not immediate notification)
            router.route(QuorumStateNotification.ESTABLISHED);

            // No immediate notification in consensus mode (waits for commit)
            assertThat(watcher.collected()).isEmpty();

            // But proposal should have been submitted
            assertThat(proposals).hasSize(1);
            assertThat(proposals.getFirst().candidate()).isEqualTo(nodes.getFirst()); // node-a
        }

        @Test
        void onLeaderCommitted_sendsAsyncNotification() throws InterruptedException {
            // Setup: simulate cluster formation
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                var topology = list.stream().sorted().toList();
                router.route(nodeAdded(nodeId, topology));
            }
            router.route(QuorumStateNotification.ESTABLISHED);

            // No notification yet
            assertThat(watcher.collected()).isEmpty();

            // Simulate consensus commit
            var expectedLeader = nodes.getFirst(); // node-a
            leaderManager.onLeaderCommitted(expectedLeader, 1);

            // Wait for async notification (routeAsync uses Promise.async)
            Thread.sleep(100);

            // Now notification should be present
            assertThat(watcher.collected()).hasSize(1);
            var notification = (LeaderChange) watcher.collected().getFirst();
            assertThat(notification.leaderId()).isEqualTo(Option.some(expectedLeader));
            assertThat(notification.localNodeIsLeader()).isFalse(); // self is node-b, leader is node-a
        }

        @Test
        void onLeaderCommitted_rejectsStaleViewSequence() throws InterruptedException {
            // Setup cluster
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                var topology = list.stream().sorted().toList();
                router.route(nodeAdded(nodeId, topology));
            }
            router.route(QuorumStateNotification.ESTABLISHED);

            // Commit with viewSequence 5
            leaderManager.onLeaderCommitted(nodes.getFirst(), 5);
            Thread.sleep(50);

            // Try to commit with older viewSequence (should be rejected)
            leaderManager.onLeaderCommitted(self, 3);
            Thread.sleep(50);

            // Only one notification (the first commit)
            assertThat(watcher.collected()).hasSize(1);
            var notification = (LeaderChange) watcher.collected().getFirst();
            assertThat(notification.leaderId()).isEqualTo(Option.some(nodes.getFirst()));
        }

        @Test
        void onQuorumDisappeared_sendsImmediateNotification() throws InterruptedException {
            // Setup cluster
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                var topology = list.stream().sorted().toList();
                router.route(nodeAdded(nodeId, topology));
            }
            router.route(QuorumStateNotification.ESTABLISHED);

            // Commit leader and wait for async notification to complete
            leaderManager.onLeaderCommitted(nodes.getFirst(), 1);
            Thread.sleep(50);

            // Clear watcher to check quorum disappearance
            watcher.collected().clear();

            // Quorum disappears - should send immediate sync notification
            router.route(QuorumStateNotification.DISAPPEARED);

            // Immediate notification (not async)
            assertThat(watcher.collected()).hasSize(1);
            var notification = (LeaderChange) watcher.collected().getFirst();
            assertThat(notification.leaderId()).isEqualTo(Option.none());
            assertThat(notification.localNodeIsLeader()).isFalse();
        }
    }

    // Helper methods shared between test classes

    private void sendNodeAdded(MessageRouter router, NodeId nodeId) {
        var topology = Stream.concat(nodes.stream(), Stream.of(nodeId))
                             .sorted()
                             .toList();

        router.route(nodeAdded(nodeId, topology));
    }

    private void sendNodeRemoved(MessageRouter router, NodeId nodeId) {
        var topology = nodes.stream()
                            .filter(id -> !id.equals(nodeId))
                            .sorted()
                            .toList();

        router.route(nodeRemoved(nodeId, topology));
    }

    private List<LeaderNotification> simulateClusterStart(MessageRouter router,
                                                          Watcher<LeaderNotification> watcher) {
        var expected = new ArrayList<LeaderNotification>();
        var list = new ArrayList<NodeId>();
        for (var nodeId : nodes) {
            list.add(nodeId);

            var topology = list.stream().sorted().toList();

            if (nodeId.equals(nodes.getLast())) {
                // When quorum will be reached, we should see the current state of
                // the leader selection
                expected.add(leaderChange(Option.option(topology.getFirst()),
                                          self.equals(topology.getFirst())));

                router.route(QuorumStateNotification.ESTABLISHED);
            }

            router.route(nodeAdded(nodeId, topology));
        }

        return expected;
    }
}
