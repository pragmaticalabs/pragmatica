/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Integration tests simulating multi-node consensus scenarios.
/// These tests verify the complete protocol flow including:
/// - Multi-node proposal agreement
/// - Voting rounds (R1 and R2)
/// - Decision agreement
/// - Phase-local initial votes and agreeing decisions across consecutive phases
class RabiaConsensusIntegrationTest {

    record TestCommand(String value) implements Command {}

    private static final org.pragmatica.serialization.SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);

    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final int CLUSTER_SIZE = 3;

    private ClusterSimulator cluster;

    @BeforeEach
    void setUp() {
        cluster = new ClusterSimulator(List.of(NODE_1, NODE_2, NODE_3));
    }

    @AfterEach
    void tearDown() {
        cluster.stopAll();
    }

    @Nested
    class ConsensusAgreement {

        @Test
        void all_nodes_agree_on_same_proposal() throws InterruptedException {
            cluster.activateAll();

            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("test-cmd")));

            // All nodes propose the same batch
            cluster.simulateProposal(NODE_1, batch);
            cluster.simulateProposal(NODE_2, batch);
            cluster.simulateProposal(NODE_3, batch);
            cluster.deliverUntilQuiescent();

            // Every node must have voted in round 1 of phase 0, and every vote must be V1. Counting the
            // distinct voters first keeps allMatch from passing on an empty or partial vote list.
            var votes = cluster.getMessagesByType(VoteRound1.class).stream()
                .filter(vote -> vote.phase().equals(Phase.ZERO) && vote.round() == 0).toList();
            assertThat(votes.stream().map(VoteRound1::sender).distinct()).hasSize(CLUSTER_SIZE);
            assertThat(votes).allMatch(v -> v.stateValue() == StateValue.V1);
        }

        @RepeatedTest(20)
        void conflicting_proposals_lead_to_v0_votes() throws InterruptedException {
            cluster.activateAll();

            var batch1 = Batch.create(SERIALIZER, List.of(new TestCommand("cmd1")));
            var batch2 = Batch.create(SERIALIZER, List.of(new TestCommand("cmd2")));
            var batch3 = Batch.create(SERIALIZER, List.of(new TestCommand("cmd3")));

            // Fix each real local proposal before delivering any peer proposal. Receiving a
            // peer proposal first is allowed to teach a node that batch and produce agreement.
            cluster.engines.get(NODE_1).handleNewBatch(new NewBatch<>(NODE_1, batch1));
            cluster.engines.get(NODE_2).handleNewBatch(new NewBatch<>(NODE_2, batch2));
            cluster.engines.get(NODE_3).handleNewBatch(new NewBatch<>(NODE_3, batch3));
            cluster.networks.values().forEach(network ->
                assertThat(network.firstProposal.await(timeSpan(3).seconds()).isSuccess())
                    .as("local proposal emitted by %s", network.self).isTrue());
            var proposed = cluster.getMessagesByType(Propose.class);
            assertThat(proposed.stream().map(Propose::sender).distinct()).hasSize(3);
            assertThat(proposed.stream().map(value -> value.value().id()).distinct()).hasSize(3);
            cluster.deliverAllPendingMessages();
            cluster.networks.values().forEach(network ->
                assertThat(network.firstVote.await(timeSpan(3).seconds()).isSuccess())
                    .as("initial vote emitted by %s", network.self).isTrue());

            // With no majority agreement, votes should be V0
            var votes = cluster.getMessagesByType(VoteRound1.class).stream()
                .filter(vote -> vote.phase().equals(Phase.ZERO) && vote.round() == 0).toList();
            assertThat(votes.stream().map(VoteRound1::sender).distinct()).hasSize(3);
            assertThat(votes).allMatch(v -> v.stateValue() == StateValue.V0);
        }

        @Test
        void majority_proposal_leads_to_v1_decision() throws InterruptedException {
            cluster.activateAll();

            var majorityBatch = Batch.create(SERIALIZER, List.of(new TestCommand("majority")));
            var minorityBatch = Batch.create(SERIALIZER, List.of(new TestCommand("minority")));

            // 2/3 nodes propose the same batch
            cluster.simulateProposal(NODE_1, majorityBatch);
            cluster.simulateProposal(NODE_2, majorityBatch);
            cluster.simulateProposal(NODE_3, minorityBatch);
            cluster.deliverUntilQuiescent();

            var decisions = cluster.getMessagesByType(Decision.class);
            assertThat(decisions).isNotEmpty();
            // With majority agreement, decision should be V1
            assertThat(decisions.getFirst().stateValue()).isEqualTo(StateValue.V1);
        }
    }

    // VotingRounds tests removed - duplicated by PhaseDataTest.EvaluateRound2Vote

    @Nested
    class DecisionProcessing {

        @Test
        void v1_decision_returns_batch_with_quorum_support() {
            var phaseData = new PhaseData<TestCommand>(new Phase(1));
            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("test")));

            // Register quorum proposals for same batch
            phaseData.registerProposal(NODE_1, batch);
            phaseData.registerProposal(NODE_2, batch);
            phaseData.registerProposal(NODE_3, batch);

            // Register V1 votes (f+1=2)
            phaseData.registerRound2Vote(NODE_1, StateValue.V1);
            phaseData.registerRound2Vote(NODE_2, StateValue.V1);
            phaseData.registerRound2Vote(NODE_3, StateValue.V1);

            var outcome = phaseData.processRound2Completion(NODE_1, 2, 2);

            assertThat(outcome).isInstanceOf(Round2Outcome.Decided.class);
            var decision = ((Round2Outcome.Decided<TestCommand>) outcome).decision();
            assertThat(decision.stateValue()).isEqualTo(StateValue.V1);
            assertThat(decision.value().id()).isEqualTo(batch.id());
        }

        @Test
        void v0_decision_returns_empty_batch() {
            var phaseData = new PhaseData<TestCommand>(new Phase(1));

            // Register V0 votes (f+1=2)
            phaseData.registerRound2Vote(NODE_1, StateValue.V0);
            phaseData.registerRound2Vote(NODE_2, StateValue.V0);
            phaseData.registerRound2Vote(NODE_3, StateValue.V0);

            var outcome = phaseData.processRound2Completion(NODE_1, 2, 2);

            assertThat(outcome).isInstanceOf(Round2Outcome.Decided.class);
            var decision = ((Round2Outcome.Decided<TestCommand>) outcome).decision();
            assertThat(decision.stateValue()).isEqualTo(StateValue.V0);
            assertThat(decision.value().isNotEmpty()).isFalse();
        }

        @Test
        void coin_flip_when_all_votes_are_vquestion() {
            var phaseData = new PhaseData<TestCommand>(new Phase(1));

            // All VQUESTION votes -> coin flip
            phaseData.registerRound2Vote(NODE_1, StateValue.VQUESTION);
            phaseData.registerRound2Vote(NODE_2, StateValue.VQUESTION);
            phaseData.registerRound2Vote(NODE_3, StateValue.VQUESTION);

            var outcome = phaseData.processRound2Completion(NODE_1, 2, 2);

            assertThat(outcome).isInstanceOf(Round2Outcome.CarryForward.class);
            assertThat(outcome.lockedValue()).isEqualTo(StateValue.V1);
            assertThat(phaseData.isDecided()).isFalse();
        }

        @Test
        void carries_forward_when_non_question_vote_but_less_than_f_plus_one() {
            var phaseData = new PhaseData<TestCommand>(new Phase(1));

            // One V0, rest VQUESTION (< f+1=2) -> CarryForward
            phaseData.registerRound2Vote(NODE_1, StateValue.V0);
            phaseData.registerRound2Vote(NODE_2, StateValue.VQUESTION);
            phaseData.registerRound2Vote(NODE_3, StateValue.VQUESTION);

            var outcome = phaseData.processRound2Completion(NODE_1, 2, 2);

            // Per spec Case 2: any non-question vote but < f+1 -> CarryForward
            assertThat(outcome).isInstanceOf(Round2Outcome.CarryForward.class);
            assertThat(outcome.lockedValue()).isEqualTo(StateValue.V0);
        }
    }

    @Nested
    class DeterministicBehavior {

        @Test
        void find_agreed_proposal_is_deterministic() {
            var phaseData1 = new PhaseData<TestCommand>(new Phase(1));
            var phaseData2 = new PhaseData<TestCommand>(new Phase(1));

            var batch1 = Batch.create(SERIALIZER, List.of(new TestCommand("a")));
            var batch2 = Batch.create(SERIALIZER, List.of(new TestCommand("b")));

            // Same proposals in different order
            phaseData1.registerProposal(NODE_1, batch1);
            phaseData1.registerProposal(NODE_2, batch2);

            phaseData2.registerProposal(NODE_2, batch2);
            phaseData2.registerProposal(NODE_1, batch1);

            // Should return same batch regardless of insertion order
            var result1 = phaseData1.findAgreedProposal(2);
            var result2 = phaseData2.findAgreedProposal(2);

            assertThat(result1.id()).isEqualTo(result2.id());
        }

        @Test
        void coin_flip_is_deterministic_across_nodes() {
            var phaseData1 = new PhaseData<TestCommand>(new Phase(42));
            var phaseData2 = new PhaseData<TestCommand>(new Phase(42));
            var phaseData3 = new PhaseData<TestCommand>(new Phase(42));

            assertThat(phaseData1.coinFlip())
                .isEqualTo(phaseData2.coinFlip())
                .isEqualTo(phaseData3.coinFlip());
        }
    }

    @Nested
    class StateMachineIntegration {

        @Test
        void state_machine_receives_commands_on_v1_decision() throws InterruptedException {
            cluster.activateAll();

            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("execute-me")));

            // All nodes propose same batch
            cluster.simulateProposal(NODE_1, batch);
            cluster.simulateProposal(NODE_2, batch);
            cluster.simulateProposal(NODE_3, batch);
            cluster.deliverUntilQuiescent();

            // Every node's state machine must have applied the committed command exactly once
            for (var nodeId : List.of(NODE_1, NODE_2, NODE_3)) {
                assertThat(cluster.stateMachines.get(nodeId).processedCommands)
                    .as("commands applied by %s", nodeId)
                    .containsExactly(new TestCommand("execute-me"));
            }
        }

        @Test
        void promise_resolved_with_results_on_v1_decision() throws InterruptedException {
            // The Promise returned by apply() must resolve with the state machine's results once the
            // batch is decided V1
            cluster.activateAll();

            var promise = cluster.engines.get(NODE_1).<String>apply(List.of(new TestCommand("cmd")));

            // apply() broadcasts NewBatch, which deliverMessage does not route; relay it as the network would
            cluster.deliverUntil(() -> !cluster.getMessagesByType(NewBatch.class).isEmpty());
            var newBatch = cluster.getMessagesByType(NewBatch.class).getFirst();
            cluster.engines.get(NODE_2).handleNewBatch(newBatch);
            cluster.engines.get(NODE_3).handleNewBatch(newBatch);
            cluster.deliverUntilQuiescent();

            assertThat(promise.await(timeSpan(5).seconds())).isEqualTo(Result.success(List.of("result:cmd")));
        }

        @Test
        void multiple_consecutive_decisions_maintain_agreement() throws InterruptedException {
            cluster.activateAll();

            var batches = new ArrayList<Batch<TestCommand>>();
            for (int i = 0; i < 3; i++) {
                var batch = Batch.create(SERIALIZER, List.of(new TestCommand("cmd-" + i)));
                var phase = new Phase(i);
                batches.add(batch);

                // Simulate complete consensus for each phase
                for (var nodeId : List.of(NODE_1, NODE_2, NODE_3)) {
                    cluster.simulateProposalForPhase(nodeId, phase, batch);
                }
                cluster.deliverUntilQuiescent();
            }

            // All decisions should agree within each phase
            var decisions = cluster.getMessagesByType(Decision.class);
            assertThat(decisions.stream().map(Decision::phase).distinct()).hasSize(3);
            var byPhase = decisions.stream().collect(
                java.util.stream.Collectors.groupingBy(Decision::phase));

            for (int i = 0; i < batches.size(); i++) {
                var phaseDecisions = byPhase.get(new Phase(i));
                assertThat(phaseDecisions).as("Phase %s decisions", i).isNotEmpty();
                assertThat(phaseDecisions.stream().map(Decision::stateValue).distinct())
                    .as("Phase %s decisions must agree", i).containsExactly(StateValue.V1);
                assertThat(phaseDecisions.stream().map(decision -> decision.value().id()).distinct())
                    .as("Phase %s decisions must carry that phase's batch", i).containsExactly(batches.get(i).id());
            }
        }

        @Test
        void phase1_initial_vote_is_v1_when_phase1_proposals_agree() throws InterruptedException {
            cluster.activateAll();

            // Phase 0: V1 decision
            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("locked")));
            cluster.simulateProposal(NODE_1, batch);
            cluster.simulateProposal(NODE_2, batch);
            cluster.simulateProposal(NODE_3, batch);
            cluster.deliverUntilQuiescent();

            // Verify V1 decision was made
            var phase0Decisions = cluster.getMessagesByType(Decision.class).stream()
                .filter(d -> d.phase().equals(Phase.ZERO))
                .toList();
            assertThat(phase0Decisions).isNotEmpty();
            assertThat(phase0Decisions.getFirst().stateValue()).isEqualTo(StateValue.V1);

            // Phase 1: a fresh slot with three identical proposals
            var batch2 = Batch.create(SERIALIZER, List.of(new TestCommand("cmd2")));
            cluster.simulateProposalForPhase(NODE_1, new Phase(1), batch2);
            cluster.simulateProposalForPhase(NODE_2, new Phase(1), batch2);
            cluster.simulateProposalForPhase(NODE_3, new Phase(1), batch2);
            // Deliver until the awaited condition holds (every node has voted), not until the cluster is
            // idle: a wrong vote must fail the value assertion below rather than a quiescence timeout.
            cluster.deliverUntil(() -> phase1InitialVotes().stream().map(VoteRound1::sender).distinct().count() == CLUSTER_SIZE);

            var phase1Votes = phase1InitialVotes();

            // Phase 1's initial vote is evaluated from phase-1 proposals only (PhaseData.evaluateInitialVote);
            // three identical proposals must yield V1 from every node.
            assertThat(phase1Votes.stream().map(VoteRound1::sender).distinct()).hasSize(3);
            assertThat(phase1Votes).allMatch(v -> v.stateValue() == StateValue.V1);
        }
    }

    @Nested
    class StaggeredActivation {

        @Test
        void staggeredActivation_dormantNodesAccumulateBatches_consensusCompletesAfterActivation() throws InterruptedException {
            // Step 1: Activate only node-1 (simulates first node ready)
            cluster.activateNode(NODE_1);
            assertThat(cluster.engines.get(NODE_1).isActive()).isTrue();
            assertThat(cluster.engines.get(NODE_2).isActive()).isFalse();
            assertThat(cluster.engines.get(NODE_3).isActive()).isFalse();

            // Step 2: Node-1 broadcasts a batch (simulates leader proposal)
            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("leader-proposal")));
            cluster.engines.get(NODE_1).handleNewBatch(new NewBatch<>(NODE_1, batch));
            // Deliver to dormant nodes (simulates network delivering NewBatch)
            cluster.engines.get(NODE_2).handleNewBatch(new NewBatch<>(NODE_1, batch));
            cluster.engines.get(NODE_3).handleNewBatch(new NewBatch<>(NODE_1, batch));
            cluster.settleAll();

            // Step 3: Verify dormant nodes did NOT broadcast Propose
            var node2Messages = cluster.networks.get(NODE_2).getAllMessages();
            var node3Messages = cluster.networks.get(NODE_3).getAllMessages();
            var node2Proposals = node2Messages.stream()
                .filter(m -> m instanceof Propose<?>)
                .count();
            var node3Proposals = node3Messages.stream()
                .filter(m -> m instanceof Propose<?>)
                .count();
            assertThat(node2Proposals).as("Dormant node-2 must not broadcast Propose").isZero();
            assertThat(node3Proposals).as("Dormant node-3 must not broadcast Propose").isZero();

            // Step 4: Activate node-2 and node-3 (staggered, with interval)
            cluster.activateNode(NODE_2);
            cluster.activateNode(NODE_3);

            // Step 5: Deliver all messages and complete consensus rounds
            cluster.deliverUntilQuiescent();

            // Step 6: Verify consensus was reached — at least one decision must exist
            var decisions = cluster.getMessagesByType(Decision.class);
            assertThat(decisions).as("Consensus must complete after staggered activation").isNotEmpty();
        }

        @Test
        void staggeredActivation_batchAccumulatedWhileDormant_processedAfterActivation() throws InterruptedException {
            // Activate only node-1
            cluster.activateNode(NODE_1);

            // Send multiple batches to dormant nodes
            var batch1 = Batch.create(SERIALIZER, List.of(new TestCommand("batch-1")));
            var batch2 = Batch.create(SERIALIZER, List.of(new TestCommand("batch-2")));
            cluster.engines.get(NODE_2).handleNewBatch(new NewBatch<>(NODE_1, batch1));
            cluster.engines.get(NODE_2).handleNewBatch(new NewBatch<>(NODE_1, batch2));
            cluster.settleAll();

            // Dormant node-2 should have zero outbound messages
            assertThat(cluster.networks.get(NODE_2).getAllMessages()).isEmpty();

            // Activate node-2
            cluster.activateNode(NODE_2);

            // Poll for proposals with timeout — activation and phase start are async
            long proposalCount = 0;
            for (int attempt = 0; attempt < 40; attempt++) {
                Thread.sleep(50);
                proposalCount = cluster.networks.get(NODE_2).getAllMessages().stream()
                    .filter(m -> m instanceof Propose<?>)
                    .count();
                if (proposalCount > 0) {
                    break;
                }
            }
            assertThat(proposalCount).as("Activated node must process accumulated batches").isPositive();
        }
    }

    private List<VoteRound1> phase1InitialVotes() {
        return cluster.getMessagesByType(VoteRound1.class).stream()
                      .filter(v -> v.phase().equals(new Phase(1)) && v.round() == 0)
                      .toList();
    }

    // ==================== Cluster Simulator ====================

    static class ClusterSimulator {
        private static final int MAX_HOPS = 100;
        private final Map<NodeId, RabiaEngine<TestCommand>> engines = new ConcurrentHashMap<>();
        private final Map<NodeId, SimulatedNetwork> networks = new ConcurrentHashMap<>();
        private final Map<NodeId, TestStateMachine> stateMachines = new ConcurrentHashMap<>();
        private final List<NodeId> nodeIds;

        ClusterSimulator(List<NodeId> nodeIds) {
            this.nodeIds = nodeIds;
            for (var nodeId : nodeIds) {
                var network = new SimulatedNetwork(nodeId, this);
                var stateMachine = new TestStateMachine();
                var topologyManager = new SimulatedTopologyManager(nodeId, nodeIds.size());
                var engine = new RabiaEngine<>(topologyManager, network, stateMachine, ProtocolConfig.testConfig());
                networks.put(nodeId, network);
                stateMachines.put(nodeId, stateMachine);
                engines.put(nodeId, engine);
            }
        }

        void activateNode(NodeId nodeId) throws InterruptedException {
            engines.get(nodeId).clusterState(ClusterStateNotification.active());
            awaitActive(nodeId);
        }

        void activateAll() throws InterruptedException {
            for (var engine : engines.values()) {
                engine.clusterState(ClusterStateNotification.active());
            }
            for (var nodeId : nodeIds) {
                awaitActive(nodeId);
            }
        }

        /// Sync responses arriving before the engine has entered Syncing are ignored, so re-offer them
        /// until the engine reports active rather than guessing how long the sync request takes.
        private void awaitActive(NodeId nodeId) throws InterruptedException {
            var engine = engines.get(nodeId);
            var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!engine.isActive()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(nodeId + " did not activate within 5s");
                }
                for (var otherId : nodeIds) {
                    if (!nodeId.equals(otherId)) {
                        engine.processSyncResponse(new SyncResponse<>(otherId, SavedState.empty(), ResponderState.COLD));
                    }
                }
                awaitSettled(engine);
                Thread.sleep(10);
            }
            awaitSettled(engine);
        }

        void stopAll() {
            for (var engine : engines.values()) {
                engine.stop().await();
            }
        }

        void simulateProposal(NodeId sender, Batch<TestCommand> batch) {
            var propose = new Propose<>(sender, Phase.ZERO, batch);
            for (var engine : engines.values()) {
                engine.processPropose(propose);
            }
        }

        void simulateProposalForPhase(NodeId sender, Phase phase, Batch<TestCommand> batch) {
            var propose = new Propose<>(sender, phase, batch);
            for (var engine : engines.values()) {
                engine.processPropose(propose);
            }
        }

        /// One delivery hop. Drains exactly the messages it delivers, so a message an engine broadcasts
        /// concurrently is kept for the next hop instead of being cleared unseen.
        boolean deliverAllPendingMessages() {
            var delivered = false;
            for (var network : networks.values()) {
                for (var message : network.drainPendingMessages()) {
                    deliverMessage(message);
                    delivered = true;
                }
            }
            return delivered;
        }

        /// Waits until every engine has run all work queued so far on its single apply executor.
        void settleAll() {
            for (var engine : engines.values()) {
                awaitSettled(engine);
            }
        }

        /// A barrier that timed out has not settled anything, so it must fail rather than be read as idle.
        private static void awaitSettled(RabiaEngine<TestCommand> engine) {
            engine.settleForTesting()
                  .await(timeSpan(5).seconds())
                  .onFailure(cause -> {
                      throw new AssertionError("engine did not settle within 5s: " + cause.message());
                  });
        }

        /// Delivers hop by hop until no engine has work queued and no message is pending. Replaces fixed
        /// sleeps: the outcome no longer depends on how fast the executors happen to be scheduled.
        void deliverUntilQuiescent() {
            deliverUntil(() -> false);
        }

        /// Delivers hop by hop until the condition holds or the cluster goes idle, whichever comes first.
        /// Returning on idle with the condition still false is deliberate: the caller's assertion then
        /// reports the real shortfall instead of a generic timeout.
        void deliverUntil(java.util.function.BooleanSupplier condition) {
            for (int hop = 0; hop < MAX_HOPS; hop++) {
                settleAll();
                settleAll(); // a task may enqueue a follow-up behind the first barrier (carry-forward)
                if (condition.getAsBoolean() || !deliverAllPendingMessages()) {
                    return;
                }
            }
            throw new AssertionError("cluster did not quiesce within " + MAX_HOPS + " delivery hops");
        }

        @SuppressWarnings("unchecked")
        private void deliverMessage(ProtocolMessage message) {
            for (var engine : engines.values()) {
                switch (message) {
                    case Propose<?> p -> engine.processPropose((Propose<TestCommand>) p);
                    case VoteRound1 v -> engine.processVoteRound1(v);
                    case VoteRound2 v -> engine.processVoteRound2(v);
                    case Decision<?> d -> engine.processDecision((Decision<TestCommand>) d);
                    default -> {}
                }
            }
        }

        @SuppressWarnings("unchecked")
        <M extends ProtocolMessage> List<M> getMessagesByType(Class<M> type) {
            var result = new ArrayList<M>();
            for (var network : networks.values()) {
                for (var message : network.getAllMessages()) {
                    if (type.isInstance(message)) {
                        result.add((M) message);
                    }
                }
            }
            return result;
        }
    }

    static class SimulatedNetwork implements ClusterNetwork {
        private final NodeId self;
        private final ClusterSimulator cluster;
        private final Promise<Unit> firstProposal = Promise.promise();
        private final Promise<Unit> firstVote = Promise.promise();
        private final List<ProtocolMessage> allMessages = new CopyOnWriteArrayList<>();
        private final java.util.Queue<ProtocolMessage> pendingMessages = new java.util.concurrent.ConcurrentLinkedQueue<>();

        SimulatedNetwork(NodeId self, ClusterSimulator cluster) {
            this.self = self;
            this.cluster = cluster;
        }

        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            return recordMessage(message);
        }

        private Unit recordMessage(ProtocolMessage message) {
            allMessages.add(message);
            pendingMessages.add(message);
            switch (message) {
                case Propose<?> ignored -> firstProposal.succeed(Unit.unit());
                case VoteRound1 ignored -> firstVote.succeed(Unit.unit());
                default -> {}
            }
            return Unit.unit();
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            return recordMessage(message);
        }

        @Override
        public void connect(NetworkServiceMessage.ConnectNode connectNode) {}

        @Override
        public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}

        @Override
        public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}

        @Override
        public void handleSend(NetworkServiceMessage.Send send) {}

        @Override
        public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @Override
        public int connectedNodeCount() {
            return cluster.nodeIds.size() - 1; // All nodes except self
        }

        @Override
        public Set<NodeId> connectedPeers() {
            return cluster.nodeIds.stream()
                          .filter(id -> !id.equals(self))
                          .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public Option<Server> server() {
            return Option.none();
        }

        List<ProtocolMessage> getAllMessages() {
            return Collections.unmodifiableList(allMessages);
        }

        List<ProtocolMessage> drainPendingMessages() {
            var drained = new ArrayList<ProtocolMessage>();
            for (var message = pendingMessages.poll(); message != null; message = pendingMessages.poll()) {
                drained.add(message);
            }
            return drained;
        }
    }

    static class SimulatedTopologyManager implements TopologyManager {
        private final NodeInfo self;
        private final int clusterSize;

        SimulatedTopologyManager(NodeId selfId, int clusterSize) {
            this.self = NodeInfo.nodeInfo(selfId, NodeAddress.nodeAddress("localhost", 5000).unwrap());
            this.clusterSize = clusterSize;
        }

        @Override
        public NodeInfo self() {
            return self;
        }

        @Override
        public Option<NodeInfo> get(NodeId id) {
            return Option.option(NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", 5000).unwrap()));
        }

        @Override
        public int clusterSize() {
            return clusterSize;
        }

        @Override
        public int quorumSize() {
            return clusterSize / 2 + 1; // Majority quorum
        }

        @Override
        public int fPlusOne() {
            int f = (clusterSize - 1) / 2;
            return f + 1;
        }

        @Override
        public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
            return Option.empty();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @Override
        public TimeSpan pingInterval() {
            return timeSpan(1).seconds();
        }

        @Override
        public TimeSpan helloTimeout() {
            return timeSpan(5).seconds();
        }

        @Override
        public Option<NodeState> getState(NodeId id) {
            return Option.empty();
        }

        @Override
        public List<NodeId> topology() {
            return java.util.stream.IntStream.rangeClosed(1, clusterSize)
                       .mapToObj(index -> nodeId("node-" + index).unwrap()).toList();
        }
    }

    static class TestStateMachine implements StateMachine<TestCommand> {
        private final List<TestCommand> processedCommands = new CopyOnWriteArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <R> List<R> process(Batch<TestCommand> batch) {
            return batch.commands()
                        .stream()
                        .map(command -> (R) processOne(command))
                        .toList();
        }

        private String processOne(TestCommand command) {
            processedCommands.add(command);
            return "result:" + command.value();
        }

        @Override
        public org.pragmatica.serialization.Serializer serializer() {
            return SERIALIZER;
        }

        @Override
        public Result<byte[]> makeSnapshot() {
            return Result.success(new byte[0]);
        }

        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            return Result.success(Unit.unit());
        }

        @Override
        public Unit reset() {
            processedCommands.clear();
            return Unit.unit();
        }
    }
}
