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
        void onTopologyChange_nodesAddedThenQuorum_submitsProposal() throws InterruptedException {
            // Create LeaderManager with node-a as self (min node submits proposals)
            var minNode = nodes.getFirst();
            var localRouter = MessageRouter.mutable();
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            var localLeaderManager = LeaderManager.leaderManager(minNode, localRouter, handler);
            localRouter.addRoute(NodeAdded.class, localLeaderManager::nodeAdded);
            localRouter.addRoute(QuorumStateNotification.class, localLeaderManager::watchQuorumState);

            // Add nodes
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                var topology = list.stream().sorted().toList();
                localRouter.route(nodeAdded(nodeId, topology));
            }

            // Establish quorum - first election uses INITIAL_ELECTION_DELAY (10s) for Fury warmup
            localRouter.route(QuorumStateNotification.ESTABLISHED);

            // Wait for scheduled proposal (10s initial delay + margin)
            Thread.sleep(10_500);

            // Proposal should have been submitted by min node
            assertThat(localProposals).hasSize(1);
            assertThat(localProposals.getFirst().candidate()).isEqualTo(minNode);
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
            leaderManager.onLeaderCommitted(expectedLeader);

            // Wait for async notification (routeAsync uses Promise.async)
            Thread.sleep(100);

            // Now notification should be present
            assertThat(watcher.collected()).hasSize(1);
            var notification = (LeaderChange) watcher.collected().getFirst();
            assertThat(notification.leaderId()).isEqualTo(Option.some(expectedLeader));
            assertThat(notification.localNodeIsLeader()).isFalse(); // self is node-b, leader is node-a
        }

        @Test
        void onLeaderCommitted_skipsNotificationWhenLeaderUnchanged() throws InterruptedException {
            // Setup cluster
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                var topology = list.stream().sorted().toList();
                router.route(nodeAdded(nodeId, topology));
            }
            router.route(QuorumStateNotification.ESTABLISHED);

            // First commit
            leaderManager.onLeaderCommitted(nodes.getFirst());
            Thread.sleep(50);

            // Second commit with same leader (should skip notification)
            leaderManager.onLeaderCommitted(nodes.getFirst());
            Thread.sleep(50);

            // Only one notification (duplicate skipped)
            assertThat(watcher.collected()).hasSize(1);
            var notification = (LeaderChange) watcher.collected().getFirst();
            assertThat(notification.leaderId()).isEqualTo(Option.some(nodes.getFirst()));
        }

        @Test
        void triggerElection_eventuallyElectsLeader_whenMinNodeInactive() {
            // Create manager for node-b (NOT min node) with expectedCluster
            var localRouter = MessageRouter.mutable();
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            // self=node-b, expectedCluster=[node-a, node-b, node-c]
            // node-a is min but never proposes (simulating it being inactive)
            var localManager = LeaderManager.leaderManager(self, localRouter, handler, nodes);
            localRouter.addRoute(NodeAdded.class, localManager::nodeAdded);
            localRouter.addRoute(QuorumStateNotification.class, localManager::watchQuorumState);

            // Add nodes to topology
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                localRouter.route(nodeAdded(nodeId, list.stream().sorted().toList()));
            }

            // Establish quorum (sets active=true)
            localRouter.route(QuorumStateNotification.ESTABLISHED);

            // Directly call triggerElection enough times to exceed fallback threshold.
            // Each call increments electionRetryCount in scheduleElectionRetryIfNeeded().
            // After ELECTION_FALLBACK_RETRIES (6) increments, the next call triggers fallback.
            for (var i = 0; i <= LeaderManager.ELECTION_FALLBACK_RETRIES; i++) {
                localManager.triggerElection();
            }

            // Non-min node should have taken over submission for the designated min candidate
            assertThat(localProposals).isNotEmpty();
            assertThat(localProposals.getLast().candidate()).isEqualTo(nodes.getFirst());
        }

        @Test
        void triggerElection_recovers_afterQuorumFlapping() throws InterruptedException {
            // Create manager for min node (node-a)
            var minNode = nodes.getFirst();
            var localRouter = MessageRouter.mutable();
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            var localManager = LeaderManager.leaderManager(minNode, localRouter, handler, nodes);
            localRouter.addRoute(NodeAdded.class, localManager::nodeAdded);
            localRouter.addRoute(QuorumStateNotification.class, localManager::watchQuorumState);

            // Build topology
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                localRouter.route(nodeAdded(nodeId, list.stream().sorted().toList()));
            }

            // Simulate rapid quorum flapping: ESTABLISHED -> DISAPPEARED -> ESTABLISHED -> DISAPPEARED -> ESTABLISHED
            localRouter.route(QuorumStateNotification.ESTABLISHED);
            Thread.sleep(100);
            localRouter.route(QuorumStateNotification.DISAPPEARED);
            Thread.sleep(100);
            localRouter.route(QuorumStateNotification.ESTABLISHED);
            Thread.sleep(100);
            localRouter.route(QuorumStateNotification.DISAPPEARED);
            Thread.sleep(100);
            localRouter.route(QuorumStateNotification.ESTABLISHED);

            // Wait for retry mechanism to kick in and eventually propose
            Thread.sleep(5_000);

            // Despite flapping, proposals should eventually be submitted
            assertThat(localProposals).isNotEmpty();
            assertThat(localProposals.getLast().candidate()).isEqualTo(minNode);
        }

        @Test
        void onLeaderCommitted_resetsRetryCount_afterElection() throws InterruptedException {
            // Use min node (node-a) so proposals are actually submitted
            var minNode = nodes.getFirst();
            var localRouter = MessageRouter.mutable();
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            var localManager = LeaderManager.leaderManager(minNode, localRouter, handler, nodes);
            localRouter.addRoute(NodeAdded.class, localManager::nodeAdded);
            localRouter.addRoute(QuorumStateNotification.class, localManager::watchQuorumState);

            // Build topology
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                localRouter.route(nodeAdded(nodeId, list.stream().sorted().toList()));
            }

            // Establish quorum and wait for initial election proposal
            localRouter.route(QuorumStateNotification.ESTABLISHED);
            Thread.sleep(4_000);

            // Commit leader — resets retry count and sets hasEverHadLeader=true
            localManager.onLeaderCommitted(minNode);

            // Record proposal count after initial election
            var proposalsAfterInitial = localProposals.size();

            // Simulate leader loss and re-election
            localRouter.route(QuorumStateNotification.DISAPPEARED);
            Thread.sleep(50);
            localRouter.route(QuorumStateNotification.ESTABLISHED);

            // Wait for re-election proposal (uses PROPOSAL_RETRY_DELAY since hasEverHadLeader=true)
            Thread.sleep(1_000);

            // Min node should submit a new proposal for re-election
            assertThat(localProposals.size()).isGreaterThan(proposalsAfterInitial);
            assertThat(localProposals.getLast().candidate()).isEqualTo(minNode);
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
            leaderManager.onLeaderCommitted(nodes.getFirst());
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
