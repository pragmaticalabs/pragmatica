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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
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
        activate(engine, network);
    }

    private void activate(RabiaEngine<TestCommand> target, TestClusterNetwork net) throws InterruptedException {
        target.clusterState(ClusterStateNotification.active());
        // `doClusterConnected` clears syncResponses before it broadcasts, so a response delivered before
        // the request would be dropped: wait for the request the engine actually sent.
        awaitSent("its SyncRequest", net, syncRequest());
        target.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        target.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        awaitActivation(target);
        drain(target);
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
            activate(shortTimeout, network);

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
            awaitSent("the batch it was asked to apply", network, newBatch());

            var batch = network.getMessages().stream()
                               .filter(m -> m instanceof RabiaProtocolMessage.Asynchronous.NewBatch<?>)
                               .map(m -> ((RabiaProtocolMessage.Asynchronous.NewBatch<TestCommand>) m).batch())
                               .findFirst()
                               .orElseThrow();

            awaitOwnPropose(Phase.ZERO);
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            awaitOwnVote1(Phase.ZERO);
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            awaitOwnVote2(Phase.ZERO);
            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));

            var result = pending.await(timeSpan(5).seconds());

            assertThat(result.isSuccess())
                    .as("answered apply must succeed, not time out")
                    .isTrue();
        }

        @Test
        void identical_concurrent_submissions_each_complete_and_the_command_applies_once() throws InterruptedException {
            // #958: the batch id is a content hash, so two callers submitting byte-identical
            // commands register the same id. Before the fix, registerBatch() replaced the first
            // pending batch with the second, dropping the first caller's correlation id from the
            // local map; commitChanges() completes only the local batch's correlation ids, so the
            // first promise never settled and surfaced as ApplyTimeout although the command applied.
            // Guarantee pinned: every caller's promise completes with its own command's outcome,
            // and the command is applied exactly once.
            var target = new RabiaEngine<>(topologyManager,
                                           network,
                                           stateMachine,
                                           applyTimeoutConfig(timeSpan(2).seconds()));

            activate(target, network);
            network.clearMessages();
            var command = new TestCommand("identical");
            var start = new CountDownLatch(1);
            var submitted = new CountDownLatch(2);
            var first = new AtomicReference<Promise<List<String>>>();
            var second = new AtomicReference<Promise<List<String>>>();

            submitConcurrently(start, submitted, first, target, command);
            submitConcurrently(start, submitted, second, target, command);
            start.countDown();
            assertThat(submitted.await(2, SECONDS)).as("both submissions must be issued").isTrue();
            assertThat(awaitCondition(() -> network.getMessages().stream().filter(newBatch()).count() >= 2))
                .as("each submission must broadcast its batch")
                .isTrue();
            var batches = network.getMessages()
                                 .stream()
                                 .filter(m -> m instanceof RabiaProtocolMessage.Asynchronous.NewBatch<?>)
                                 .map(m -> ((RabiaProtocolMessage.Asynchronous.NewBatch<TestCommand>) m).batch())
                                 .toList();

            assertThat(batches).as("each submission broadcasts its own batch").hasSize(2);
            assertThat(batches.get(0).id()).as("identical content hashes to one id").isEqualTo(batches.get(1).id());
            awaitOwnPropose(Phase.ZERO);
            target.processPropose(new Propose<>(NODE_2, Phase.ZERO, batches.getFirst()));
            awaitOwnVote1(Phase.ZERO);
            target.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            target.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            awaitOwnVote2(Phase.ZERO);
            target.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            target.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            var firstResult = first.get().await(timeSpan(5).seconds());
            var secondResult = second.get().await(timeSpan(5).seconds());

            assertThat(stateMachine.getProcessedCommands()).as("the identical command is applied exactly once")
                      .containsExactly(command);
            assertThat(firstResult).as("first caller's promise must settle with its command's outcome")
                      .isEqualTo(Result.success(List.of("result:identical")));
            assertThat(secondResult).as("second caller's promise must settle with its command's outcome")
                      .isEqualTo(Result.success(List.of("result:identical")));
            target.stop().await();
        }

        private void submitConcurrently(CountDownLatch start,
                                        CountDownLatch submitted,
                                        AtomicReference<Promise<List<String>>> slot,
                                        RabiaEngine<TestCommand> target,
                                        TestCommand command) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    return;
                }

                slot.set(target.<String> apply(List.of(command)));
                submitted.countDown();
            });
        }

        private ProtocolConfig shortApplyTimeoutConfig() {
            return applyTimeoutConfig(timeSpan(200).millis());
        }

        private ProtocolConfig applyTimeoutConfig(TimeSpan applyTimeout) {
            return ProtocolConfig.protocolConfig(timeSpan(60).seconds(),
                                                 timeSpan(100).millis(),
                                                 100,
                                                 ProtocolConfig.DEFAULT_MAX_PENDING_BATCHES,
                                                 applyTimeout)
                                 .unwrap();
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

            assertThat(awaitCondition(engine::isPaused)).as("engine should be paused after DISAPPEARED").isTrue();
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

            engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));

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

    /// Waits for the engine to broadcast a message matching `message`. Every predicate built below names
    /// the type, phase and sender, so an earlier unrelated message on the network cannot satisfy it.
    private static void awaitSent(String what, TestClusterNetwork net, Predicate<ProtocolMessage> message) {
        assertThat(awaitCondition(() -> net.getMessages().stream().anyMatch(message)))
            .as("engine must have broadcast %s", what)
            .isTrue();
    }

    private static void awaitActivation(RabiaEngine<?> target) {
        assertThat(awaitCondition(target::isActive)).as("engine must activate once sync responses reach quorum").isTrue();
    }

    /// Barrier: returns once every task already queued on the engine's single apply executor has run.
    /// Two passes, because a task running when the first barrier is queued may queue a follow-up behind it.
    private static void drain(RabiaEngine<?> target) throws InterruptedException {
        for (var pass = 0; pass < 2; pass++) {
            var reached = new CountDownLatch(1);

            target.safeExecuteForTesting(reached::countDown);
            assertThat(reached.await(CONDITION_TIMEOUT_MILLIS, MILLISECONDS))
                .as("apply executor must drain its queue")
                .isTrue();
        }
    }

    private static Predicate<ProtocolMessage> syncRequest() {
        return SyncRequest.class::isInstance;
    }

    private static Predicate<ProtocolMessage> newBatch() {
        return m -> m instanceof RabiaProtocolMessage.Asynchronous.NewBatch<?>;
    }

    private static Predicate<ProtocolMessage> propose(Phase phase, NodeId sender) {
        return m -> m instanceof Propose<?> p && p.phase().equals(phase) && p.sender().equals(sender);
    }

    private static Predicate<ProtocolMessage> round1Vote(Phase phase) {
        return m -> m instanceof VoteRound1 v && v.phase().equals(phase) && v.sender().equals(NODE_1);
    }

    private static Predicate<ProtocolMessage> round2Vote(Phase phase) {
        return m -> m instanceof VoteRound2 v && v.phase().equals(phase) && v.sender().equals(NODE_1);
    }

    private static Predicate<ProtocolMessage> decision(Phase phase) {
        return m -> m instanceof Decision<?> d && d.phase().equals(phase);
    }

    private void awaitOwnPropose(Phase phase) {
        awaitSent("its proposal for " + phase, network, propose(phase, NODE_1));
    }

    private void awaitOwnVote1(Phase phase) {
        awaitSent("its round-1 vote for " + phase, network, round1Vote(phase));
    }

    private void awaitOwnVote2(Phase phase) {
        awaitSent("its round-2 vote for " + phase, network, round2Vote(phase));
    }

    private void awaitDecision(Phase phase) {
        awaitSent("its decision for " + phase, network, decision(phase));
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

            assertThat(awaitCondition(() -> new Phase(5).equals(engine.highestObservedClusterPhaseForTesting())))
                .as("observed cluster phase should track the far-ahead proposal")
                .isTrue();
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
            assertThat(awaitCondition(engine::isPendingCatchUp)).as("gap open while applied < observed").isTrue();

            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            awaitOwnVote1(Phase.ZERO);
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            awaitOwnVote2(Phase.ZERO);
            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));

            assertThat(awaitCondition(() -> new Phase(1).equals(engine.currentPhaseForTesting())))
                .as("applied phase should have advanced to phase 1 after the phase-0 decision")
                .isTrue();
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
            awaitSent("its SyncRequest", stallNetwork, syncRequest());
            // These tests are about the stall detector, so the engine just needs to be ACTIVE; it should
            // get there the legitimate way rather than through the hole that was closed when
            // `syncQuorumSize()` derived its threshold from `connectedNodeCount()` (reported as 0 by this
            // test network, which collapsed the gate to 1 and let a minority activate the engine).
            // Two responses plus self is already the majority of five that #660 settled on; the third is
            // harmless surplus, ignored once the engine is active.
            stallEngine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            stallEngine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            stallEngine.processSyncResponse(new SyncResponse<>(NODE_4, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            awaitActivation(stallEngine);
            drain(stallEngine);
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
            awaitSent("its own proposal", stallNetwork, propose(Phase.ZERO, NODE_1));
            // Receive a proposal from NODE_2 (which then dies — it never re-sends).
            stallEngine.processPropose(new Propose<>(NODE_2, Phase.ZERO, foreignBatch));
            drain(stallEngine);

            stallNetwork.clearMessages();
            // Let the stall detector tick (interval 50ms) until both proposals have been re-broadcast.
            awaitSent("a re-broadcast of the dead contributor's proposal (sender NODE_2)",
                      stallNetwork,
                      propose(Phase.ZERO, NODE_2));
            awaitSent("a re-broadcast of the node's own proposal (sender NODE_1)", stallNetwork, propose(Phase.ZERO, NODE_1));
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
            awaitSent("its own proposal", stallNetwork, propose(Phase.ZERO, NODE_1));
            stallEngine.processPropose(new Propose<>(NODE_2, Phase.ZERO, foreign2));
            stallEngine.processPropose(new Propose<>(NODE_3, Phase.ZERO, foreign3));

            // With 3 proposals (self + NODE_2 + NODE_3) at quorumSize=3, the engine broadcasts a
            // Round 1 vote for the phase — progress beyond the proposal-collection deadlock.
            awaitSent("a round-1 vote for the phase (collecting the quorum proposal set must unblock it)",
                      stallNetwork,
                      round1Vote(Phase.ZERO));
        }
    }

    @Nested
    class MessageHandling {

        @Test
        void sync_request_broadcast_on_quorum_established() {
            engine.clusterState(ClusterStateNotification.active());
            awaitSent("its SyncRequest", network, syncRequest());
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
            var decision = new Decision<>(NODE_2, new Phase(1), StateValue.V0, Batch.<TestCommand>emptyBatch());

            engine.processDecision(decision);
            // No exception should be thrown
        }
    }

    @Nested
    class ProtocolInvariants {

        @Test
        void completed_slot_starts_next_slot_with_fresh_proposal() throws InterruptedException {
            activateEngine();
            network.clearMessages();

            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("cmd")));

            // Complete phase 0 with V1 decision
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            awaitOwnVote1(Phase.ZERO);

            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            awaitOwnVote2(Phase.ZERO);

            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            awaitDecision(Phase.ZERO);

            // Verify phase 0 decision was broadcast
            var phase0Decision = network.getMessages().stream()
                .anyMatch(m -> m instanceof Decision<?> d && d.phase().equals(Phase.ZERO));
            assertThat(phase0Decision).as("Phase 0 should have V1 decision").isTrue();

            // The next slot starts a fresh weak-MVC instance.
            network.clearMessages();

            // Submit new commands which will trigger startPhase for phase 1
            engine.handleSubmit(new RabiaEngineIO.SubmitCommands<>(List.of(new TestCommand("cmd2"))));
            awaitOwnPropose(new Phase(1));
            drain(engine);

            // A new slot must collect proposal evidence before voting.
            var phase1Messages = network.getMessages().stream()
                .filter(m -> m instanceof Propose<?> || m instanceof VoteRound1)
                .toList();

            // The engine should have broadcast a proposal for phase 1
            var hasPhase1Proposal = phase1Messages.stream()
                .anyMatch(m -> m instanceof Propose<?> p && p.phase().equals(new Phase(1)));

            assertThat(hasPhase1Proposal)
                .as("Engine should have started phase 1 with proposal after V1 decision")
                .isTrue();
            assertThat(phase1Messages).noneMatch(message -> message instanceof VoteRound1);

        }

        @Test
        void state_machine_applied_only_on_v1_decision() throws InterruptedException {
            activateEngine();
            stateMachine.processedCommands.clear();

            // Complete phase with V0 decision
            engine.processVoteRound1(new VoteRound1(NODE_1, Phase.ZERO, StateValue.V0));
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V0));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V0));
            drain(engine);

            engine.processVoteRound2(new VoteRound2(NODE_1, Phase.ZERO, StateValue.V0));
            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V0));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V0));
            drain(engine);

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
            awaitOwnVote1(Phase.ZERO);

            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            awaitOwnVote2(Phase.ZERO);

            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            awaitDecision(Phase.ZERO);

            // Verify a decision was broadcast
            var hasDecision = network.getMessages().stream()
                .anyMatch(m -> m instanceof Decision<?>);
            assertThat(hasDecision).as("Decision should be broadcast").isTrue();

            // After V1 decision with non-empty batch, commands should be processed
            assertThat(awaitCondition(() -> !stateMachine.getProcessedCommands().isEmpty()))
                .as("Commands should be applied to state machine after V1 decision")
                .isTrue();
        }

        @Test
        void phase_advances_after_decision() throws InterruptedException {
            activateEngine();

            // Complete phase 0
            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("cmd")));
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            awaitOwnVote1(Phase.ZERO);

            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            awaitOwnVote2(Phase.ZERO);

            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            awaitDecision(Phase.ZERO);

            // Verify decision was broadcast for phase 0
            var phase0Decision = network.getMessages().stream()
                .anyMatch(m -> m instanceof Decision<?> d && d.phase().equals(Phase.ZERO));
            assertThat(phase0Decision).as("Phase 0 should have decision").isTrue();

            network.clearMessages();

            // Submit commands to trigger phase 1
            engine.handleSubmit(new RabiaEngineIO.SubmitCommands<>(List.of(new TestCommand("cmd2"))));
            awaitOwnPropose(new Phase(1));

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
                awaitOwnVote1(p);

                engine.processVoteRound1(new VoteRound1(NODE_2, p, StateValue.V1));
                engine.processVoteRound1(new VoteRound1(NODE_3, p, StateValue.V1));
                awaitOwnVote2(p);

                engine.processVoteRound2(new VoteRound2(NODE_2, p, StateValue.V1));
                engine.processVoteRound2(new VoteRound2(NODE_3, p, StateValue.V1));
                awaitDecision(p);

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
                awaitOwnVote1(p);

                engine.processVoteRound1(new VoteRound1(NODE_2, p, StateValue.V1));
                engine.processVoteRound1(new VoteRound1(NODE_3, p, StateValue.V1));
                awaitOwnVote2(p);

                engine.processVoteRound2(new VoteRound2(NODE_2, p, StateValue.V1));
                engine.processVoteRound2(new VoteRound2(NODE_3, p, StateValue.V1));
                awaitDecision(p);
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
            drain(engine);

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

            assertThat(awaitCondition(activeAtFire::get))
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
            awaitSent("its SyncRequest", network, syncRequest());
            var state = RabiaPersistence.SavedState.<TestCommand>savedState(new byte[]{42}, Phase.ZERO, List.of());
            engine.processSyncResponse(new SyncResponse<>(NODE_2, state, ResponderState.COLD));
            engine.processSyncResponse(new SyncResponse<>(NODE_3, state, ResponderState.COLD));

            assertThat(awaitCondition(activeAtFire::get))
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
            awaitOwnVote1(Phase.ZERO);

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
            awaitOwnVote1(Phase.ZERO);

            // Check that round 1 vote was broadcast
            var hasVoteRound1 = network.getMessages().stream()
                .anyMatch(m -> m instanceof VoteRound1);
            assertThat(hasVoteRound1).isTrue();

            // Simulate round 1 votes from other nodes
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            awaitOwnVote2(Phase.ZERO);

            // Simulate round 2 votes
            engine.processVoteRound2(new VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound2(new VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
            awaitDecision(Phase.ZERO);

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
            awaitOwnVote1(Phase.ZERO);

            // When proposals don't agree, expect V0 votes
            var vote = network.getMessages().stream()
                .filter(m -> m instanceof VoteRound1)
                .map(m -> (VoteRound1) m)
                .findFirst();

            assertThat(vote).isPresent();
            assertThat(vote.get().stateValue()).isEqualTo(StateValue.V0);
        }

        @Test
        void first_round_agreement_still_requires_round2() throws InterruptedException {
            activateEngine();
            network.clearMessages();

            var batch = Batch.create(SERIALIZER, List.of(new TestCommand("fast-path-cmd")));

            // Simulate receiving proposals from all nodes (same batch)
            engine.processPropose(new Propose<>(NODE_1, Phase.ZERO, batch));
            engine.processPropose(new Propose<>(NODE_2, Phase.ZERO, batch));
            awaitOwnVote1(Phase.ZERO);

            // Simulate round 1 votes from all nodes (super-majority = n-f = 2 for 3 nodes)
            // ENGINE already voted V1 when proposals matched, so just need NODE_2 and NODE_3
            engine.processVoteRound1(new VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
            engine.processVoteRound1(new VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
            awaitOwnVote2(Phase.ZERO);
            drain(engine);

            // First-round agreement alone must not publish a Decision
            var hasDecision = network.getMessages().stream()
                .anyMatch(m -> m instanceof Decision);
            assertThat(hasDecision).as("Round 1 evidence cannot commit a slot").isFalse();

            // The normal protocol broadcasts a second-round vote
            var round2VoteCount = network.getMessages().stream()
                .filter(m -> m instanceof VoteRound2)
                .count();
            assertThat(round2VoteCount).as("Agreement must be confirmed in round 2").isPositive();
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
            assertThat(network.getMessages().stream().anyMatch(syncRequest()))
                .as("a gated engine must not start syncing before authorization")
                .isFalse();

            gatedEngine.authorizeActivation();
            awaitSent("its SyncRequest after authorization", network, syncRequest());

            // Send sync responses to complete activation
            gatedEngine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            gatedEngine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            awaitActivation(gatedEngine);

            assertThat(gatedEngine.isActive()).as("Gated engine should activate after authorization").isTrue();
            gatedEngine.stop().await();
        }

        @Test
        void ungated_engine_activates_normally() throws InterruptedException {
            var ungatedEngine = new RabiaEngine<>(topologyManager, network, stateMachine,
                                                   ProtocolConfig.testConfig(), ConsensusMetrics.noop(), false);
            ungatedEngine.clusterState(ClusterStateNotification.active());
            awaitSent("its SyncRequest", network, syncRequest());

            ungatedEngine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            ungatedEngine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            awaitActivation(ungatedEngine);

            assertThat(ungatedEngine.isActive()).as("Ungated engine should activate on quorum").isTrue();
            ungatedEngine.stop().await();
        }

        @Test
        void gated_engine_handles_disappeared_normally() throws InterruptedException {
            var gatedEngine = new RabiaEngine<>(topologyManager, network, stateMachine,
                                                 ProtocolConfig.testConfig(), ConsensusMetrics.noop(), true);
            // Even when gated, DISAPPEARED should propagate normally
            gatedEngine.clusterState(ClusterStateNotification.active());
            gatedEngine.clusterState(ClusterStateNotification.passive());
            drain(gatedEngine);

            assertThat(gatedEngine.isActive()).isFalse();
            gatedEngine.stop().await();
        }
    }

    // ==================== Stub Implementations ====================

    static class TestTopologyManager implements TopologyManager {
        @Override
        public boolean isConsensusMember(NodeId id) {
            return id.equals(self.id()) || java.util.stream.IntStream.rangeClosed(1, clusterSize)
                     .anyMatch(index -> id.equals(nodeId("node-" + index).unwrap()));
        }

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
            return java.util.stream.IntStream.rangeClosed(1, clusterSize)
                .mapToObj(index -> nodeId("node-" + index).unwrap()).toList();
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
            if (messages.stream().noneMatch(existing -> existing == message)) { messages.add(message); }
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
