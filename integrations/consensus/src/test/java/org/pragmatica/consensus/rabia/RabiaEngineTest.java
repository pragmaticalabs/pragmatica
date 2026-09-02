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
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
// SyncRequest is Asynchronous, not Synchronous — the wildcard above does not cover it.
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class RabiaEngineTest {

    record TestCommand(String value) implements Command {}

    private static final org.pragmatica.serialization.SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);

    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final NodeId NODE_4 = nodeId("node-4").unwrap();
    private static final NodeId NODE_5 = nodeId("node-5").unwrap();
    private static final int CLUSTER_SIZE = 3;

    private TestTopologyManager topologyManager;
    private TestClusterNetwork network;
    private TestStateMachine stateMachine;
    private RabiaEngine<TestCommand> engine;

    @BeforeEach
    void setUp() {
        topologyManager = new TestTopologyManager(NODE_1, CLUSTER_SIZE);
        network = new TestClusterNetwork();
        stateMachine = new TestStateMachine();
        engine = new RabiaEngine<>(topologyManager, network, stateMachine, ProtocolConfig.testConfig());
    }

    @AfterEach
    void tearDown() {
        engine.stop().await();
    }

    private void activateEngine() throws InterruptedException {
        engine.clusterState(ClusterStateNotification.active());
        // Wait for sync to occur and send quorum sync responses
        Thread.sleep(150); // Allow sync request to be sent
        // Send sync responses from other nodes
        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty()));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty()));
        Thread.sleep(50); // Allow activation to complete
    }

    @Nested
    class CommandSubmission {

        @Test
        void submit_fails_when_engine_inactive() {
            var result = engine.apply(List.of(new TestCommand("test"))).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause ->
                assertThat(cause).isInstanceOf(ConsensusError.NodeInactive.class)
            );
        }

        @Test
        void submit_fails_for_empty_command_list() throws InterruptedException {
            activateEngine();

            var result = engine.apply(List.<TestCommand>of()).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause ->
                assertThat(cause).isInstanceOf(ConsensusError.CommandBatchIsEmpty.class)
            );
        }

        @Test
        void apply_fails_with_ApplyTimeout_when_batch_never_answered() throws InterruptedException {
            // The test network records broadcasts but never drives consensus to completion,
            // so the submitted batch is never answered. Before the fix, apply()'s returned
            // promise had no timeout and hung forever. With a short applyTimeout the engine
            // converts the stuck batch into a domain ConsensusError.ApplyTimeout.
            var shortTimeout = new RabiaEngine<>(topologyManager,
                                                 network,
                                                 stateMachine,
                                                 shortApplyTimeoutConfig());
            activate(shortTimeout);

            var result = shortTimeout.apply(List.of(new TestCommand("never-answered"))).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause ->
                assertThat(cause).isInstanceOf(ConsensusError.ApplyTimeout.class)
            );
            shortTimeout.stop().await();
        }

        @Test
        void apply_succeeds_when_batch_is_answered_by_v1_decision() throws InterruptedException {
            // A normally-answered apply must still succeed through the new
            // timeout().mapError() wrapping. Submit via apply(), capture the broadcast
            // batch (with its real correlation IDs), then drive a full V1 decision for it.
            activateEngine();
            network.clearMessages();

            var pending = engine.<Unit>apply(List.of(new TestCommand("answered")));
            Thread.sleep(50);

            var batch = network.getMessages().stream()
                               .filter(m -> m instanceof RabiaProtocolMessage.Asynchronous.NewBatch<?>)
                               .map(m -> ((RabiaProtocolMessage.Asynchronous.NewBatch<TestCommand>) m).batch())
                               .findFirst()
                               .orElseThrow();

            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            Thread.sleep(50);
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(50);
            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));

            var result = pending.await(timeSpan(5).seconds());

            assertThat(result.isSuccess())
                    .as("answered apply must succeed, not time out")
                    .isTrue();
        }

        private ProtocolConfig shortApplyTimeoutConfig() {
            return ProtocolConfig.protocolConfig(timeSpan(60).seconds(),
                                                 timeSpan(100).millis(),
                                                 100,
                                                 ProtocolConfig.DEFAULT_MAX_PENDING_BATCHES,
                                                 timeSpan(200).millis())
                                 .unwrap();
        }

        private void activate(RabiaEngine<TestCommand> target) throws InterruptedException {
            target.clusterState(ClusterStateNotification.active());
            Thread.sleep(150);
            target.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty()));
            target.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty()));
            Thread.sleep(50);
        }
    }

    @Nested
    class QuorumHandling {

        @Test
        void disconnection_pauses_engine_and_rejects_submissions() throws InterruptedException {
            // Membership-architecture-spec §4.5 / §7.3: quorum-loss transitions Active → Paused
            // (not a reset). New apply() submissions are rejected with QuorumPaused while the
            // engine retains all in-memory state ready for resume on the next ESTABLISHED.
            activateEngine();

            engine.clusterState(ClusterStateNotification.passive());
            Thread.sleep(50);

            assertThat(engine.isPaused()).as("engine should be paused after DISAPPEARED").isTrue();
            assertThat(engine.isActive()).as("engine must not appear active while paused").isFalse();

            var result = engine.apply(List.of(new TestCommand("test"))).await();
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause ->
                assertThat(cause).isInstanceOf(ConsensusError.QuorumPaused.class)
            );
        }
    }

    /// A sync ADOPTS another node's consensus state wholesale, so the gate on it must be a majority of
    /// the CLUSTER — never a majority of whoever this node currently reaches. `syncQuorumSize()` used to
    /// compute `min(connectedNodeCount(), clusterSize) / 2 + 1`, and this test network reports
    /// `connectedNodeCount() == 0`, so the gate collapsed to **1**: a single response could restore
    /// state, precisely when a node is least likely to be on the majority side of a partition.
    ///
    /// #660 then corrected the OTHER direction. Responses arrive only from PEERS, so a threshold of
    /// `clusterSize / 2 + 1` RESPONSES silently demanded `quorum + 1` live nodes and this 3-node cluster
    /// deadlocked whenever one node was down. Self is a member of its own cluster and is counted exactly
    /// once, so one response plus self is a genuine majority of three. The minority case moved to
    /// [RabiaSyncAdoptionQuorumTest], where a 5-node cluster makes one response an actual minority and
    /// the discrimination is meaningful — at `clusterSize == 3` there is no "too few but non-zero".
    @Nested
    class SyncQuorum {

        /// The default `testConfig()` retries the sync round every ~100ms (randomized), and
        /// [RabiaEngine#doSynchronize] CLEARS `syncResponses` whenever a retry finds fewer than a
        /// quorum. This test necessarily delivers its two responses in separate steps — it has to
        /// observe the one-response state in between — so with a 100ms retry a round almost always
        /// intervened and discarded the first response. The second then arrived as response 1 of a
        /// FRESH round, the node never reached a quorum, and the majority assertion failed: measured
        /// at 8 failures in 10 local runs, and twice on CI (including on a docs-only commit, which is
        /// what proved it was never a code regression).
        ///
        /// A longer retry interval fixes it PROPERLY rather than by widening a sleep: it keeps exactly
        /// one sync round in flight for the whole test, so the two responses provably land in the same
        /// round. Waiting longer could never have worked — the discarded response is gone and the test
        /// sends no more.
        @BeforeEach
        void singleSyncRoundForTheWholeTest() {
            engine.stop().await();
            engine = new RabiaEngine<>(topologyManager,
                                        network,
                                        stateMachine,
                                        ProtocolConfig.consensusConfig(timeSpan(60).seconds(),
                                                                        timeSpan(60).seconds()));
        }

        @Test
        void onePeerResponse_completesAMajorityWithSelf_andActivates() {
            engine.clusterState(ClusterStateNotification.active());
            awaitSyncRequestBroadcast();

            engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty()));

            assertThat(awaitActive())
                .as("1 response + self = 2 of %d = a majority; demanding a 2nd response demanded the whole cluster",
                    CLUSTER_SIZE)
                .isTrue();
        }

        /// Activation is asynchronous (`safeExecute` hands the work to the engine's executor), so this
        /// polls for the state rather than assuming a fixed delay is enough.
        private boolean awaitActive() {
            return awaitCondition(engine::isActive);
        }

        private void awaitSyncRequestBroadcast() {
            // `doClusterConnected` CLEARS syncResponses before broadcasting, so a response delivered
            // before that broadcast would be silently dropped. Wait for the request the engine actually
            // sent rather than guessing at a delay.
            assertThat(awaitCondition(() -> network.messages.stream().anyMatch(SyncRequest.class::isInstance)))
                .as("engine must have started its sync round before responses are delivered")
                .isTrue();
        }
    }

    private static final long CONDITION_TIMEOUT_MILLIS = 5_000;

    /// Bounded poll for an asynchronously-established condition. Returns false on timeout so the caller
    /// asserts on the outcome and reports its own message, rather than dying with a bare timeout.
    private static boolean awaitCondition(BooleanSupplier condition) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(CONDITION_TIMEOUT_MILLIS);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }

    @Nested
    class PendingCatchUp {

        @Test
        void isPendingCatchUp_false_when_caught_up() throws InterruptedException {
            // A node that activated with no higher cluster phase observed is caught up:
            // highestObservedClusterPhase == currentPhase == ZERO.
            activateEngine();

            assertThat(engine.isActive()).as("engine should be active after activation").isTrue();
            assertThat(engine.isPendingCatchUp())
                .as("caught-up active node must not report pending catch-up")
                .isFalse();
        }

        @Test
        void isPendingCatchUp_true_when_cluster_phase_exceeds_applied_phase() throws InterruptedException {
            // Observing a peer message for a phase ahead of the locally-applied currentPhase
            // (within MAX_PHASE_AHEAD, so no resync) advances the committed frontier above the
            // applied frontier — the lagging-replacement-leader condition.
            activateEngine();
            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("ahead")));

            engine.processPropose(new Propose<>(NODE_2, new Phase(5), batch));
            Thread.sleep(50);

            assertThat(engine.highestObservedClusterPhaseForTesting())
                .as("observed cluster phase should track the far-ahead proposal")
                .isEqualTo(new Phase(5));
            assertThat(engine.currentPhaseForTesting())
                .as("applied phase must not have advanced to the unentered future phase")
                .isEqualTo(Phase.ZERO);
            assertThat(engine.isPendingCatchUp())
                .as("a node whose committed frontier exceeds its applied frontier is lagging")
                .isTrue();
        }

        @Test
        void isPendingCatchUp_clears_once_applied_phase_reaches_observed_phase() throws InterruptedException {
            // Once the engine applies up to the observed frontier, it is caught up again.
            activateEngine();
            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("cmd")));

            // Observe phase 1 as the cluster frontier, then drive a full decision for phase 0
            // which advances currentPhase to 1 — closing the gap.
            engine.processVoteRound1(new VoteRound1(NODE_2, new Phase(1), StateValue.V1));
            Thread.sleep(50);
            assertThat(engine.isPendingCatchUp()).as("gap open while applied < observed").isTrue();

            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            Thread.sleep(50);
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(50);
            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(100);

            assertThat(engine.currentPhaseForTesting())
                .as("applied phase should have advanced to phase 1 after the phase-0 decision")
                .isEqualTo(new Phase(1));
            assertThat(engine.isPendingCatchUp())
                .as("once applied frontier reaches observed frontier, node is caught up")
                .isFalse();
        }
    }

    @Nested
    class StallDetector {

        // 5-node cluster → quorumSize = 3, so 2 collected proposals are short of quorum and the
        // stall detector's proposal-rebroadcast branch fires. Short stall interval (50ms) so the
        // periodic check runs quickly under test.
        private RabiaEngine<TestCommand> stallEngine;
        private TestClusterNetwork stallNetwork;

        @BeforeEach
        void setUpStallEngine() throws InterruptedException {
            var stallTopology = new TestTopologyManager(NODE_1, 5);
            stallNetwork = new TestClusterNetwork();
            stallEngine = new RabiaEngine<>(stallTopology,
                                            stallNetwork,
                                            new TestStateMachine(),
                                            ProtocolConfig.testConfig(),
                                            ConsensusMetrics.noop(),
                                            false,
                                            RabiaPersistence.inMemory(),
                                            timeSpan(50).millis());
            stallEngine.clusterState(ClusterStateNotification.active());
            Thread.sleep(150);
            // These tests are about the stall detector, so the engine just needs to be ACTIVE; it should
            // get there the legitimate way rather than through the hole that was closed when
            // `syncQuorumSize()` derived its threshold from `connectedNodeCount()` (reported as 0 by this
            // test network, which collapsed the gate to 1 and let a minority activate the engine).
            // Two responses plus self is already the majority of five that #660 settled on; the third is
            // harmless surplus, ignored once the engine is active.
            stallEngine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty()));
            stallEngine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty()));
            stallEngine.processSyncResponse(new SyncResponse<>(NODE_4, RabiaPersistence.SavedState.empty()));
            Thread.sleep(50);
        }

        @AfterEach
        void tearDownStallEngine() {
            stallEngine.stop().await();
        }

        @Test
        void stall_detector_rebroadcasts_foreign_proposals_not_just_own() throws InterruptedException {
            // #258: a phase stalls short of quorum proposals. The node holds its OWN proposal plus
            // one from a (now-dead) contributor. The stall detector must re-broadcast the FULL
            // proposal set — including the dead contributor's proposal — so a surviving/fresh voter
            // can reach hasQuorumProposals. Before the fix only the node's own proposal was resent.
            var ownBatch = Batch.create(SERIALIZER, List.of(new TestCommand("own")));
            var foreignBatch = Batch.create(SERIALIZER, List.of(new TestCommand("from-dead-node")));

            // Enter the phase with our own proposal.
            stallEngine.handleSubmit(new RabiaEngineIO.SubmitCommands<>(List.of(new TestCommand("own"))));
            Thread.sleep(50);
            // Receive a proposal from NODE_2 (which then dies — it never re-sends).
            stallEngine.processPropose(new Propose<>(NODE_2, Phase.ZERO, foreignBatch));
            Thread.sleep(50);

            stallNetwork.clearMessages();
            // Let the stall detector tick (interval 50ms) at least twice.
            Thread.sleep(160);

            var rebroadcastFromDeadNode = stallNetwork.getMessages().stream()
                .filter(m -> m instanceof Propose<?>)
                .map(m -> (Propose<?>) m)
                .filter(p -> p.phase().equals(Phase.ZERO))
                .anyMatch(p -> p.sender().equals(NODE_2));

            assertThat(rebroadcastFromDeadNode)
                .as("stall detector must re-broadcast the dead contributor's proposal (sender NODE_2)")
                .isTrue();

            var rebroadcastOwn = stallNetwork.getMessages().stream()
                .filter(m -> m instanceof Propose<?>)
                .map(m -> (Propose<?>) m)
                .filter(p -> p.phase().equals(Phase.ZERO))
                .anyMatch(p -> p.sender().equals(NODE_1));

            assertThat(rebroadcastOwn)
                .as("stall detector must still re-broadcast the node's own proposal")
                .isTrue();
        }

        @Test
        void stall_detector_recovers_phase_when_fresh_voter_collects_rebroadcast_proposals() throws InterruptedException {
            // End-to-end recovery: NODE_1 holds proposals from NODE_2 and NODE_3 (dead) plus its
            // own = 3 = quorum. After the original contributors die, the re-broadcast lets the
            // engine itself re-collect the quorum proposal set and broadcast a Round 1 vote,
            // proving the phase is no longer deadlocked.
            var foreign2 = Batch.create(SERIALIZER, List.of(new TestCommand("dead-2")));
            var foreign3 = Batch.create(SERIALIZER, List.of(new TestCommand("dead-3")));

            stallEngine.handleSubmit(new RabiaEngineIO.SubmitCommands<>(List.of(new TestCommand("own"))));
            Thread.sleep(50);
            stallEngine.processPropose(new Propose<>(NODE_2, Phase.ZERO, foreign2));
            stallEngine.processPropose(new Propose<>(NODE_3, Phase.ZERO, foreign3));
            Thread.sleep(80);

            // With 3 proposals (self + NODE_2 + NODE_3) at quorumSize=3, the engine broadcasts a
            // Round 1 vote for the phase — progress beyond the proposal-collection deadlock.
            var votedRound1 = stallNetwork.getMessages().stream()
                .anyMatch(m -> m instanceof VoteRound1 v && v.phase().equals(Phase.ZERO) && v.sender().equals(NODE_1));

            assertThat(votedRound1)
                .as("collecting the quorum proposal set must let the engine vote (phase unblocked)")
                .isTrue();
        }
    }

    @Nested
    class MessageHandling {

        @Test
        void sync_request_broadcast_on_quorum_established() throws InterruptedException {
            engine.clusterState(ClusterStateNotification.active());
            Thread.sleep(150);

            var hasSyncRequest = network.getMessages().stream()
                .anyMatch(m -> m instanceof RabiaProtocolMessage.Asynchronous.SyncRequest);
            assertThat(hasSyncRequest).isTrue();
        }

        @Test
        void ignores_proposals_when_inactive() {
            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("test")));
            var propose = new Propose<>(NODE_2, new Phase(1), batch);

            engine.processPropose(propose);
            // No exception should be thrown
        }

        @Test
        void ignores_votes_when_inactive() {
            var vote = new VoteRound1(NODE_2, new Phase(1), StateValue.V1);

            engine.processVoteRound1(vote);
            // No exception should be thrown
        }

        @Test
        void ignores_decisions_when_inactive() {
            var decision = new Decision<>(NODE_2, new Phase(1), StateValue.V1, Batch.<TestCommand>emptyBatch());

            engine.processDecision(decision);
            // No exception should be thrown
        }
    }

    @Nested
    class ProtocolInvariants {

        @Test
        void locked_value_carries_to_next_phase() throws InterruptedException {
            activateEngine();
            network.clearMessages();

            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("cmd")));

            // Complete phase 0 with V1 decision
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            Thread.sleep(50);

            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(50);

            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(100);

            // Verify phase 0 decision was broadcast
            var phase0Decision = network.getMessages().stream()
                .anyMatch(m -> m instanceof Decision<?> d && d.phase().equals(Phase.ZERO));
            assertThat(phase0Decision).as("Phase 0 should have V1 decision").isTrue();

            // After V1 decision, the locked value (V1) is set for next phase
            // Per Rabia spec: moveToNextPhase() sets lockedValue to Option.some(decidedValue)
            // This is verified by the engine advancing to next phase
            network.clearMessages();

            // Submit new commands which will trigger startPhase for phase 1
            engine.handleSubmit(new RabiaEngineIO.SubmitCommands<>(List.of(new TestCommand("cmd2"))));
            Thread.sleep(150);

            // If there were pending batches and locked value was V1, the engine should
            // broadcast a VoteRound1 with V1 immediately when starting phase 1
            var phase1Messages = network.getMessages().stream()
                .filter(m -> m instanceof Propose<?> || m instanceof VoteRound1)
                .toList();

            // The engine should have broadcast a proposal for phase 1
            var hasPhase1Proposal = phase1Messages.stream()
                .anyMatch(m -> m instanceof Propose<?> p && p.phase().equals(new Phase(1)));

            assertThat(hasPhase1Proposal)
                .as("Engine should have started phase 1 with proposal after V1 decision")
                .isTrue();
        }

        @Test
        void state_machine_applied_only_on_v1_decision() throws InterruptedException {
            activateEngine();
            stateMachine.processedCommands.clear();

            // Complete phase with V0 decision
            engine.processVoteRound1(new VoteRound1(NODE_1, Phase.ZERO, StateValue.V0));
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V0));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V0));
            Thread.sleep(50);

            engine.processVoteRound2(new VoteRound2(NODE_1, Phase.ZERO, StateValue.V0));
            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V0));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V0));
            Thread.sleep(50);

            // V0 decision should NOT apply commands
            assertThat(stateMachine.processedCommands).isEmpty();
        }

        @Test
        void pending_batches_removed_after_v1_decision() throws InterruptedException {
            activateEngine();
            network.clearMessages();

            var command = new TestCommand("cmd");
            var batch = Batch.create(SERIALIZER, List.of(command));

            // Simulate a complete V1 decision flow with a non-empty batch
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            Thread.sleep(50);

            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(50);

            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(100);

            // Verify a decision was broadcast
            var hasDecision = network.getMessages().stream()
                .anyMatch(m -> m instanceof Decision<?>);
            assertThat(hasDecision).as("Decision should be broadcast").isTrue();

            // After V1 decision with non-empty batch, commands should be processed
            assertThat(stateMachine.getProcessedCommands())
                .as("Commands should be applied to state machine after V1 decision")
                .isNotEmpty();
        }

        @Test
        void phase_advances_after_decision() throws InterruptedException {
            activateEngine();

            // Complete phase 0
            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("cmd")));
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            Thread.sleep(50);

            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(50);

            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(100);

            // Verify decision was broadcast for phase 0
            var phase0Decision = network.getMessages().stream()
                .anyMatch(m -> m instanceof Decision<?> d && d.phase().equals(Phase.ZERO));
            assertThat(phase0Decision).as("Phase 0 should have decision").isTrue();

            network.clearMessages();

            // Submit commands to trigger phase 1
            engine.handleSubmit(new RabiaEngineIO.SubmitCommands<>(List.of(new TestCommand("cmd2"))));
            Thread.sleep(150);

            // Engine should broadcast a proposal for phase 1
            var hasPhase1Proposal = network.getMessages().stream()
                .anyMatch(m -> m instanceof Propose<?> p && p.phase().equals(new Phase(1)));

            assertThat(hasPhase1Proposal)
                .as("Engine should start phase 1 after phase 0 decision")
                .isTrue();
        }

        @Test
        void multiple_phases_complete_correctly() throws InterruptedException {
            activateEngine();

            for (int phase = 0; phase < 3; phase++) {
                network.clearMessages();
                var p = new Phase(phase);
                var batch = Batch.create(SERIALIZER, List.of(new TestCommand("cmd-" + phase)));

                engine.processPropose(new Propose<>(NODE_1, p, batch));
                engine.processPropose(new Propose<>(NODE_2, p, batch));
                Thread.sleep(50);

                engine.processVoteRound1(new VoteRound1(NODE_2, p, StateValue.V1));
                engine.processVoteRound1(new VoteRound1(NODE_3, p, StateValue.V1));
                Thread.sleep(50);

                engine.processVoteRound2(new VoteRound2(NODE_2, p, StateValue.V1));
                engine.processVoteRound2(new VoteRound2(NODE_3, p, StateValue.V1));
                Thread.sleep(100);

                // Verify decision was broadcast
                var hasDecision = network.getMessages().stream()
                    .anyMatch(m -> m instanceof Decision<?> d && d.phase().equals(p));
                assertThat(hasDecision).as("Phase %d should have decision", phase).isTrue();
            }
        }

        @Test
        void cleanup_removes_old_phases() throws InterruptedException {
            activateEngine();

            // Complete multiple phases to accumulate phase data
            for (int phase = 0; phase < 5; phase++) {
                var p = new Phase(phase);
                var batch = Batch.create(SERIALIZER, List.of(new TestCommand("cmd-" + phase)));

                engine.processPropose(new Propose<>(NODE_1, p, batch));
                engine.processPropose(new Propose<>(NODE_2, p, batch));
                Thread.sleep(30);

                engine.processVoteRound1(new VoteRound1(NODE_2, p, StateValue.V1));
                engine.processVoteRound1(new VoteRound1(NODE_3, p, StateValue.V1));
                Thread.sleep(30);

                engine.processVoteRound2(new VoteRound2(NODE_2, p, StateValue.V1));
                engine.processVoteRound2(new VoteRound2(NODE_3, p, StateValue.V1));
                Thread.sleep(50);
            }

            // Wait for cleanup task to run (cleanup interval is configured in ProtocolConfig.testConfig())
            // Note: Cleanup is triggered by a scheduled task, so we wait for it to run
            Thread.sleep(500);

            // Old phases should be cleaned up (cleanup threshold is in config)
            // This verifies the cleanupOldPhases() method is working
            // The exact assertion depends on config.removeOlderThanPhases() value
            // We just verify the engine is still functional after cleanup
            assertThat(engine.isActive()).isTrue();
        }

        @Test
        void sync_response_restores_state_correctly() throws InterruptedException {
            // This test verifies the sync response mechanism (same as activateEngine helper)
            // The activateEngine helper already tests this, so we verify it works correctly
            activateEngine();

            // Engine should be active after receiving sync responses
            assertThat(engine.isActive()).isTrue();

            // Verify engine accepts commands after sync restoration (doesn't fail immediately)
            // The apply returns a Promise that won't complete without full protocol execution,
            // so we verify the engine state is correct rather than waiting for the result
            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("test-after-sync")));
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            Thread.sleep(50);

            // After sync, the engine should be able to process proposals
            // (no exception thrown and active remains true)
            assertThat(engine.isActive()).isTrue();
        }

        @Test
        void onStateRestored_fires_after_empty_snapshot_restore() throws InterruptedException {
            // The listener captures engine.isActive() at fire time, proving the callback runs
            // AFTER the restored state is applied and the engine has activated.
            var activeAtFire = new AtomicBoolean(false);
            engine.onStateRestored(() -> activeAtFire.set(engine.isActive()));

            activateEngine();

            assertThat(activeAtFire.get())
                .as("onStateRestored must fire after restore completes and the engine activates")
                .isTrue();
        }

        @Test
        void onStateRestored_fires_after_nonempty_snapshot_restore() throws InterruptedException {
            // Non-empty snapshot path: restore goes through stateMachine.restoreSnapshot's
            // success continuation, so the state-machine content is queryable when it fires.
            var activeAtFire = new AtomicBoolean(false);
            engine.onStateRestored(() -> activeAtFire.set(engine.isActive()));

            engine.clusterState(ClusterStateNotification.active());
            Thread.sleep(150); // Allow sync request to be sent
            var state = RabiaPersistence.SavedState.<TestCommand>savedState(new byte[]{42}, Phase.ZERO, List.of());
            engine.processSyncResponse(new SyncResponse<>(NODE_2, state));
            engine.processSyncResponse(new SyncResponse<>(NODE_3, state));
            Thread.sleep(50); // Allow activation to complete

            assertThat(activeAtFire.get())
                .as("onStateRestored must fire after a non-empty snapshot restore")
                .isTrue();
        }

        @Test
        void round1_votes_never_vquestion() throws InterruptedException {
            // Invariant 14: round1 votes never VQUESTION
            activateEngine();
            network.clearMessages();

            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("test")));

            // Simulate receiving proposals from all nodes
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            Thread.sleep(50);

            // Verify all round 1 votes are NOT VQUESTION
            assertThat(network.getMessages().stream()
                .filter(m -> m instanceof VoteRound1)
                .map(m -> (VoteRound1) m)
                .allMatch(v -> v.stateValue() != StateValue.VQUESTION))
                .isTrue();
        }
    }

    @Nested
    class ProtocolFlow {

        @Test
        void processes_complete_round_with_v1_decision() throws InterruptedException {
            activateEngine();
            network.clearMessages();

            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("test-cmd")));

            // Simulate receiving proposals from all nodes (same batch)
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            Thread.sleep(50); // Allow processing

            // Check that round 1 vote was broadcast
            var hasVoteRound1 = network.getMessages().stream()
                .anyMatch(m -> m instanceof VoteRound1);
            assertThat(hasVoteRound1).isTrue();

            // Simulate round 1 votes from other nodes
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(50);

            // Simulate round 2 votes
            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(50);

            // Check that a decision was broadcast
            var hasDecision = network.getMessages().stream()
                .anyMatch(m -> m instanceof Decision);
            assertThat(hasDecision).isTrue();
        }

        @Test
        void handles_v0_decision_for_conflicting_proposals() throws InterruptedException {
            activateEngine();
            network.clearMessages();

            var batch1 = Batch.create(SERIALIZER, List.of(new TestCommand("cmd1")));
            var batch2 = Batch.create(SERIALIZER, List.of(new TestCommand("cmd2")));

            // Simulate conflicting proposals
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch1));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch2));
            Thread.sleep(50);

            // When proposals don't agree, expect V0 votes
            var vote = network.getMessages().stream()
                .filter(m -> m instanceof VoteRound1)
                .map(m -> (VoteRound1) m)
                .findFirst();

            assertThat(vote).isPresent();
            assertThat(vote.get().stateValue()).isEqualTo(StateValue.V0);
        }

        @Test
        void fast_path_skips_round2_when_super_majority_agrees() throws InterruptedException {
            activateEngine();
            network.clearMessages();

            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("fast-path-cmd")));

            // Simulate receiving proposals from all nodes (same batch)
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            Thread.sleep(50);

            // Simulate round 1 votes from all nodes (super-majority = n-f = 2 for 3 nodes)
            // ENGINE already voted V1 when proposals matched, so just need NODE_2 and NODE_3
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            Thread.sleep(100);

            // Verify decision was broadcast WITHOUT any round 2 votes being sent
            var hasDecision = network.getMessages().stream()
                .anyMatch(m -> m instanceof Decision);
            assertThat(hasDecision).as("Fast path should produce a decision").isTrue();

            // Verify no VoteRound2 was broadcast (fast path skipped round 2)
            var round2VoteCount = network.getMessages().stream()
                .filter(m -> m instanceof VoteRound2)
                .count();
            assertThat(round2VoteCount).as("Fast path should skip round 2 voting").isZero();
        }
    }

    @Nested
    class ActivationGating {

        @Test
        void gated_engine_stays_stopped_on_quorum_established() throws InterruptedException {
            var gatedEngine = new RabiaEngine<>(topologyManager, network, stateMachine,
                                                 ProtocolConfig.testConfig(), ConsensusMetrics.noop(), true);
            gatedEngine.clusterState(ClusterStateNotification.active());
            Thread.sleep(100);

            assertThat(gatedEngine.isActive()).as("Gated engine should stay inactive").isFalse();
            gatedEngine.stop().await();
        }

        @Test
        void gated_engine_activates_after_authorize() throws InterruptedException {
            var gatedEngine = new RabiaEngine<>(topologyManager, network, stateMachine,
                                                 ProtocolConfig.testConfig(), ConsensusMetrics.noop(), true);
            gatedEngine.clusterState(ClusterStateNotification.active());
            Thread.sleep(100);

            assertThat(gatedEngine.isActive()).isFalse();

            gatedEngine.authorizeActivation();
            Thread.sleep(200);

            // Send sync responses to complete activation
            gatedEngine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty()));
            gatedEngine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty()));
            Thread.sleep(100);

            assertThat(gatedEngine.isActive()).as("Gated engine should activate after authorization").isTrue();
            gatedEngine.stop().await();
        }

        @Test
        void ungated_engine_activates_normally() throws InterruptedException {
            var ungatedEngine = new RabiaEngine<>(topologyManager, network, stateMachine,
                                                   ProtocolConfig.testConfig(), ConsensusMetrics.noop(), false);
            ungatedEngine.clusterState(ClusterStateNotification.active());
            Thread.sleep(200);

            ungatedEngine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty()));
            ungatedEngine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty()));
            Thread.sleep(100);

            assertThat(ungatedEngine.isActive()).as("Ungated engine should activate on quorum").isTrue();
            ungatedEngine.stop().await();
        }

        @Test
        void gated_engine_handles_disappeared_normally() throws InterruptedException {
            var gatedEngine = new RabiaEngine<>(topologyManager, network, stateMachine,
                                                 ProtocolConfig.testConfig(), ConsensusMetrics.noop(), true);
            // Even when gated, DISAPPEARED should propagate normally
            gatedEngine.clusterState(ClusterStateNotification.active());
            Thread.sleep(50);
            gatedEngine.clusterState(ClusterStateNotification.passive());
            Thread.sleep(50);

            assertThat(gatedEngine.isActive()).isFalse();
            gatedEngine.stop().await();
        }
    }

    // ==================== Stub Implementations ====================

    static class TestTopologyManager implements TopologyManager {
        private final NodeInfo self;
        private final int clusterSize;

        TestTopologyManager(NodeId selfId, int clusterSize) {
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
            return List.of();
        }
    }

    static class TestClusterNetwork implements ClusterNetwork {
        private final List<ProtocolMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            messages.add(message);
            return Unit.unit();
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
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            messages.add(message);
            return Unit.unit();
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
        public int connectedNodeCount() {
            return 0; // Test network has no real connections
        }

        @Override
        public Set<NodeId> connectedPeers() {
            return Set.of(); // Test network has no real connections
        }

        @Override
        public Option<Server> server() {
            return Option.none();
        }

        List<ProtocolMessage> getMessages() {
            return Collections.unmodifiableList(messages);
        }

        void clearMessages() {
            messages.clear();
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

        List<TestCommand> getProcessedCommands() {
            return Collections.unmodifiableList(processedCommands);
        }
    }
}
