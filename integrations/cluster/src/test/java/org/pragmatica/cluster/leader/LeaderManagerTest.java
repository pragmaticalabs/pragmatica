package org.pragmatica.consensus.leader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.SelfShutdown;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.consensus.leader.LeaderNotification.leaderChange;
import static org.pragmatica.consensus.topology.TransportObservation.ObservationSource.QUIC;
import static org.pragmatica.consensus.topology.TransportObservation.peerDisconnected;
import static org.pragmatica.consensus.topology.TransportObservation.peerJoined;

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
            router.addRoute(PeerJoined.class, leaderManager::peerJoined);
            router.addRoute(PeerDisconnected.class, leaderManager::peerDisconnected);
            router.addRoute(SelfShutdown.class, leaderManager::selfShutdown);
            router.addRoute(ClusterStateNotification.class, leaderManager::watchClusterState);
        }

        @Test
        void onTopologyChange_nodesAddedThenQuorum_electsLeader() {
            var expected = simulateClusterStart(router, watcher);

            // When quorum disappears, we should see disappearance of the leader
            expected.add(leaderChange(Option.none(), false));
            router.route(ClusterStateNotification.passive());

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
            router.route(ClusterStateNotification.passive());

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
            router.route(ClusterStateNotification.passive());

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
            router.addRoute(PeerJoined.class, leaderManager::peerJoined);
            router.addRoute(PeerDisconnected.class, leaderManager::peerDisconnected);
            router.addRoute(SelfShutdown.class, leaderManager::selfShutdown);
            router.addRoute(ClusterStateNotification.class, leaderManager::watchClusterState);
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
            localRouter.addRoute(PeerJoined.class, localLeaderManager::peerJoined);
            localRouter.addRoute(ClusterStateNotification.class, localLeaderManager::watchClusterState);

            // Add nodes
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                var topology = list.stream().sorted().toList();
                localRouter.route(peerJoined(nodeId, topology, QUIC));
            }

            // Establish quorum (sets active=true but does NOT auto-trigger initial election)
            localRouter.route(ClusterStateNotification.active());

            // Simulate what AetherNode does: explicitly trigger election after consensus sync
            localLeaderManager.triggerElection();

            // Wait for proposal (KvSync grace 3s + BASE_ELECTION_DELAY of 2s + margin).
            // The cold-boot self-proposal gate (AwaitingKvSync) defers the first proposal by
            // [`LeaderElectionContext#DEFAULT_KV_SYNC_GRACE_DELAY`] (3s); the test runs without
            // a peer KV-sync, so it falls through via the timeout path.
            Thread.sleep(6_500);

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
                router.route(peerJoined(nodeId, topology, QUIC));
            }
            router.route(ClusterStateNotification.active());

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
                router.route(peerJoined(nodeId, topology, QUIC));
            }
            router.route(ClusterStateNotification.active());

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
        void triggerElection_nonMinNode_proposesLexFirstCandidate() throws InterruptedException {
            // Initial-election proposers are no longer restricted to the lex-first node.
            // All nodes propose the same `pool.getFirst()` candidate; Rabia phase resolution
            // dedupes naturally. This test validates that a non-min node (node-b) DOES submit,
            // and submits for the canonical candidate `node-a` (not for itself).
            var localRouter = MessageRouter.mutable();
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            // self=node-b (rank 1), expectedCluster=[node-a, node-b, node-c]
            var localManager = LeaderManager.leaderManager(self, localRouter, handler, nodes);
            localRouter.addRoute(PeerJoined.class, localManager::peerJoined);
            localRouter.addRoute(ClusterStateNotification.class, localManager::watchClusterState);

            // Add nodes to topology
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                localRouter.route(peerJoined(nodeId, list.stream().sorted().toList(), QUIC));
            }

            // Establish quorum (sets active=true)
            localRouter.route(ClusterStateNotification.active());

            // Trigger election — node-b should submit a proposal for the lex-first candidate.
            localManager.triggerElection();

            // Wait for KvSync grace (3s) + stagger delay (rank 1 = 2s base + 1s rank).
            Thread.sleep(8_000);

            var expectedCandidate = nodes.stream().sorted().toList().getFirst();
            assertThat(localProposals).isNotEmpty()
                                       .first()
                                       .extracting(LeaderProposal::candidate)
                                       .isEqualTo(expectedCandidate);
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
            localRouter.addRoute(PeerJoined.class, localManager::peerJoined);
            localRouter.addRoute(ClusterStateNotification.class, localManager::watchClusterState);

            // Build topology
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                localRouter.route(peerJoined(nodeId, list.stream().sorted().toList(), QUIC));
            }

            // Simulate rapid quorum flapping: ESTABLISHED -> DISAPPEARED -> ESTABLISHED -> DISAPPEARED -> ESTABLISHED
            localRouter.route(ClusterStateNotification.active());
            Thread.sleep(100);
            localRouter.route(ClusterStateNotification.passive());
            Thread.sleep(100);
            localRouter.route(ClusterStateNotification.active());
            Thread.sleep(100);
            localRouter.route(ClusterStateNotification.passive());
            Thread.sleep(100);
            localRouter.route(ClusterStateNotification.active());

            // Simulate AetherNode triggering election after consensus sync
            localManager.triggerElection();

            // Wait for proposal (KvSync grace 3s + BASE_ELECTION_DELAY 2s + margin).
            Thread.sleep(6_500);

            // Despite flapping, proposals should eventually be submitted
            assertThat(localProposals).isNotEmpty();
            assertThat(localProposals.getLast().candidate()).isEqualTo(minNode);
        }

        @Test
        void onLeaderCommitted_resetsRetryCount_afterElection() {
            // Use min node (node-a) so proposals are actually submitted
            var minNode = nodes.getFirst();
            var localRouter = MessageRouter.mutable();
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            var localManager = LeaderManager.leaderManager(minNode, localRouter, handler, nodes);
            localRouter.addRoute(PeerJoined.class, localManager::peerJoined);
            localRouter.addRoute(ClusterStateNotification.class, localManager::watchClusterState);

            // Build topology
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                localRouter.route(peerJoined(nodeId, list.stream().sorted().toList(), QUIC));
            }

            // Establish quorum and trigger election (simulating AetherNode)
            localRouter.route(ClusterStateNotification.active());
            localManager.triggerElection();

            // Commit leader — resets retry count and sets hasEverHadLeader=true. We can commit
            // before the AwaitingKvSync grace window expires; LeaderCommitted is handled in
            // AwaitingKvSync (transitioning straight to Led without entering Electing).
            localManager.onLeaderCommitted(minNode);
            await().atMost(Duration.ofSeconds(2))
                   .until(() -> localManager.leader().equals(Option.some(minNode)));

            // Record proposal count after initial election (after the leader-commit takes hold)
            var proposalsAfterInitial = localProposals.size();

            // Simulate leader loss and re-election
            localRouter.route(ClusterStateNotification.passive());
            localRouter.route(ClusterStateNotification.active());

            // Wait for re-election proposal. Path: QuorumLost → AwaitingKvSync (grace 3s) →
            // Electing → propose (base 2s + rank 0 + jitter). Cap generously to absorb staircase
            // + KvSync grace + retry tick under CI load.
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> localProposals.size() > proposalsAfterInitial);

            // Min node should submit a new proposal for re-election
            assertThat(localProposals.size()).isGreaterThan(proposalsAfterInitial);
            assertThat(localProposals.getLast().candidate()).isEqualTo(minNode);
        }

        @Test
        void triggerElection_afterAdoptingExistingLeader_doesNotSubmitProposal() throws InterruptedException {
            // Bug Z scenario: a newly-provisioned CTM node joins an established cluster and
            // would normally be the min-rank candidate (e.g., "aether-core-node-1-xyz" sorts
            // before existing "node-1"). Before triggering election, AetherNode reads the
            // already-committed leader from the kvStore and calls onLeaderCommitted(). After
            // this adoption, triggerElection() must NOT submit a proposal — otherwise the
            // new node would win and trigger leader churn during scale-up.
            var minNode = nodes.getFirst(); // node-a - min-rank candidate
            var localRouter = MessageRouter.mutable();
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            // self = min node, so WOULD submit a proposal if hasEverHadLeader=false
            var localManager = LeaderManager.leaderManager(minNode, localRouter, handler, nodes);
            localRouter.addRoute(PeerJoined.class, localManager::peerJoined);
            localRouter.addRoute(ClusterStateNotification.class, localManager::watchClusterState);

            // Build topology
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                localRouter.route(peerJoined(nodeId, list.stream().sorted().toList(), QUIC));
            }

            // Establish quorum (sets active=true)
            localRouter.route(ClusterStateNotification.active());

            // Simulate adoption of existing leader from kvStore: an existing leader
            // (node-c, a different node) is already committed in the cluster.
            var existingLeader = nodes.getLast();
            localManager.onLeaderCommitted(existingLeader);

            // Now trigger election (as AetherNode does after adoption)
            localManager.triggerElection();

            // Wait past KvSync grace (3s) + base election delay (2s) + rank stagger + margin.
            Thread.sleep(6_500);

            // The min node must NOT submit a proposal because a leader was already adopted.
            // Without the adoption fix, hasEverHadLeader=false and min-rank would propose itself,
            // causing leader churn.
            assertThat(localProposals).isEmpty();
            assertThat(localManager.leader()).isEqualTo(Option.some(existingLeader));
        }

        @Test
        void onLeaderCommitted_adoptsUnconditionally_whenLeaderNotInTopology() throws InterruptedException {
            // FIX 1 (B5-facet-2): a committed leader NOT yet in the local transport topology is
            // adopted UNCONDITIONALLY — a committed LeaderKey is a consensus-validated decision and
            // transport presence must not veto it. This OVERTURNS the prior "drop stale commit"
            // contract, which was the second root of the 600s never-READY wedge. If the leader is
            // genuinely dead, SWIM/FSM death drives re-election via NodeGone — the correct channel.
            var minNode = nodes.getFirst();
            var localRouter = MessageRouter.mutable();
            var localWatcher = new Watcher<LeaderNotification>(new CopyOnWriteArrayList<>());
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            localRouter.addRoute(LeaderChange.class, localWatcher::watch);
            var localManager = LeaderManager.leaderManager(minNode, localRouter, handler);
            localRouter.addRoute(PeerJoined.class, localManager::peerJoined);
            localRouter.addRoute(ClusterStateNotification.class, localManager::watchClusterState);

            // Topology only contains [a, b] — NOT node-c
            localRouter.route(peerJoined(minNode, List.of(minNode), QUIC));
            localRouter.route(peerJoined(self, List.of(minNode, self), QUIC));
            localRouter.route(ClusterStateNotification.active());

            // Committed leader = node-c which is NOT in our transport topology
            var committed = nodes.getLast();
            localManager.onLeaderCommitted(committed);

            Thread.sleep(100);

            assertThat(localManager.leader())
                    .as("committed leader adopted unconditionally despite absence from topology (FIX 1)")
                    .isEqualTo(Option.some(committed));
        }

        @Test
        void nodeRemoved_whenLeaderGone_followerReElects() {
            // Leader dies → follower must clear leader, emit no-leader notification, and start
            // submitting proposals (re-election, any node can propose).
            var follower = self; // node-b
            var leaderNode = nodes.getFirst(); // node-a — will die
            var localRouter = MessageRouter.mutable();
            var localWatcher = new Watcher<LeaderNotification>(new CopyOnWriteArrayList<>());
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            localRouter.addRoute(LeaderChange.class, localWatcher::watch);
            var localManager = LeaderManager.leaderManager(follower, localRouter, handler, nodes);
            localRouter.addRoute(PeerJoined.class, localManager::peerJoined);
            localRouter.addRoute(PeerDisconnected.class, localManager::peerDisconnected);
            localRouter.addRoute(ClusterStateNotification.class, localManager::watchClusterState);

            var list = new ArrayList<NodeId>();
            for (var n : nodes) {
                list.add(n);
                localRouter.route(peerJoined(n, list.stream().sorted().toList(), QUIC));
            }
            localRouter.route(ClusterStateNotification.active());

            localManager.onLeaderCommitted(leaderNode);
            await().atMost(Duration.ofSeconds(2))
                   .until(() -> localManager.leader().equals(Option.some(leaderNode)));
            localWatcher.collected().clear();
            localProposals.clear();

            // Leader (node-a) removed
            var survivors = List.of(follower, nodes.getLast());
            localRouter.route(peerDisconnected(leaderNode, survivors, QUIC));

            // Wait for re-election: leader cleared, no-leader notification emitted, follower
            // submits a proposal. Path: Led → ReElecting (NodeGone of leader) → propose. The
            // ReElecting state passes through AwaitingKvSync only on the cold-boot path; here
            // we reach it from Led, which short-circuits the grace and uses the proposal-retry
            // tick (~500ms + jitter). Cap at 10s to absorb staircase + jitter under CI load.
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> localManager.leader().isEmpty()
                                && localWatcher.collected().stream()
                                               .anyMatch(n -> n instanceof LeaderChange lc && lc.leaderId().isEmpty())
                                && !localProposals.isEmpty());

            assertThat(localManager.leader()).isEqualTo(Option.none());
            assertThat(localWatcher.collected())
                .anyMatch(n -> n instanceof LeaderChange lc && lc.leaderId().isEmpty());
            // Follower (self=b) submits in re-election — any-node-can-propose
            assertThat(localProposals).isNotEmpty();
        }

        @Test
        void stop_drainsEventsAndBecomesInert() {
            // After stop(): events are silently ignored, leader is cleared, no exceptions.
            var localRouter = MessageRouter.mutable();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> Promise.unitPromise();
            var localManager = LeaderManager.leaderManager(self, localRouter, handler);
            localRouter.addRoute(PeerJoined.class, localManager::peerJoined);
            localRouter.addRoute(ClusterStateNotification.class, localManager::watchClusterState);

            localManager.stop();

            localRouter.route(peerJoined(self, List.of(self), QUIC));
            localRouter.route(ClusterStateNotification.active());
            localManager.onLeaderCommitted(self);

            assertThat(localManager.leader()).isEqualTo(Option.none());
            assertThat(localManager.isLeader()).isFalse();
        }

        @Test
        void quorumEstablished_consensusAlreadyReady_autoAdvancesToElecting() throws InterruptedException {
            // AetherNode flow: by the time quorum establishes, the consensus engine may already
            // be active (its readiness is independent of leader election). Per the SSOT design,
            // QuorumWaiting.onEntry queries the injected `consensusReadySupplier` directly and
            // self-dispatches a ConsensusReady when the supplier returns true — no buffered
            // event between FSM states. We model this with an `AtomicBoolean`-backed supplier,
            // pre-set to true, and verify the FSM advances to Electing without any external
            // `triggerElection()` call.
            var minNode = nodes.getFirst();
            var localRouter = MessageRouter.mutable();
            var localProposals = new CopyOnWriteArrayList<LeaderProposal>();
            LeaderManager.LeaderProposalHandler handler = (candidate, viewSequence) -> {
                localProposals.add(new LeaderProposal(candidate, viewSequence));
                return Promise.unitPromise();
            };
            // Consensus-ready supplier latched to `true` from the start — the FSM should advance
            // out of QuorumWaiting on its own as soon as quorum is reported established.
            var consensusReady = new java.util.concurrent.atomic.AtomicBoolean(true);
            var localManager = LeaderManager.leaderManager(minNode, localRouter, handler, nodes,
                                                           () -> 0L, consensusReady::get);
            localRouter.addRoute(PeerJoined.class, localManager::peerJoined);
            localRouter.addRoute(ClusterStateNotification.class, localManager::watchClusterState);

            var list = new ArrayList<NodeId>();
            for (var n : nodes) {
                list.add(n);
                localRouter.route(peerJoined(n, list.stream().sorted().toList(), QUIC));
            }

            // Establish quorum — QuorumWaiting.onEntry queries the supplier (true) and
            // self-dispatches ConsensusReady, advancing through AwaitingKvSync (KvSync grace 3s)
            // to Electing without external nudge.
            localRouter.route(ClusterStateNotification.active());

            // Wait past KvSync grace (3s) + staggered initial delay (base 2s + rank 0) + margin.
            Thread.sleep(6_500);

            assertThat(localProposals).isNotEmpty();
            assertThat(localProposals.getFirst().candidate()).isEqualTo(minNode);
        }

        @Test
        void onQuorumDisappeared_sendsImmediateNotification() throws InterruptedException {
            // Setup cluster
            var list = new ArrayList<NodeId>();
            for (var nodeId : nodes) {
                list.add(nodeId);
                var topology = list.stream().sorted().toList();
                router.route(peerJoined(nodeId, topology, QUIC));
            }
            router.route(ClusterStateNotification.active());

            // Commit leader and wait for async notification to complete
            leaderManager.onLeaderCommitted(nodes.getFirst());
            Thread.sleep(50);

            // Clear watcher to check quorum disappearance
            watcher.collected().clear();

            // Quorum disappears - should send immediate sync notification
            router.route(ClusterStateNotification.passive());

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

        router.route(peerJoined(nodeId, topology, QUIC));
    }

    private void sendNodeRemoved(MessageRouter router, NodeId nodeId) {
        var topology = nodes.stream()
                            .filter(id -> !id.equals(nodeId))
                            .sorted()
                            .toList();

        router.route(peerDisconnected(nodeId, topology, QUIC));
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

                router.route(ClusterStateNotification.active());
            }

            router.route(peerJoined(nodeId, topology, QUIC));
        }

        return expected;
    }
}
