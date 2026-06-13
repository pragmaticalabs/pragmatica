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
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.Server;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// R1 — RabiaEngine Paused state and explicit reconfigure.
///
/// Implements membership-architecture-spec §4.5 / §7.3 acceptance tests:
///   * Quorum-loss transitions Active → Paused while RETAINING in-memory state
///     (currentPhase, pendingBatches, etc.).
///   * Quorum-return resumes Paused → Idle without resetting; same currentPhase carries.
///   * `reconfigure(ClusterConfig)` is the ONLY path that wipes proposal state.
///   * `isActive()` and `isPaused()` are mutually exclusive at all times.
///   * `apply()` while Paused fails with `ConsensusError.QuorumPaused` (no data loss —
///     caller may retry after resume).
class RabiaEnginePauseResumeTest {

    record TestCommand(String value) implements Command {}

    private static final org.pragmatica.serialization.SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);

    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
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
        Thread.sleep(150);
        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty()));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty()));
        Thread.sleep(50);
    }

    @Nested
    class PauseResumeRetainsState {

        @Test
        void paused_onQuorumLoss_retainsState() throws InterruptedException {
            activateEngine();
            assertThat(engine.isActive()).isTrue();

            // Drive state forward: apply a Decision so currentPhase advances past ZERO.
            // We deliver the Decision as if it came from another node — engine commits it
            // and advancePhase moves currentPhase from 0 → 1.
            var batch = Batch.<TestCommand>create(SERIALIZER, List.of(new TestCommand("paused-state")));
            var decision = new Decision<>(NODE_2, Phase.ZERO, StateValue.V1, batch);
            engine.processDecision(decision);
            Thread.sleep(100);

            var phaseBeforePause = engine.currentPhaseForTesting();
            assertThat(phaseBeforePause).as("phase should advance past ZERO after a Decision").isNotEqualTo(Phase.ZERO);

            // Quorum loss → Paused.
            engine.clusterState(ClusterStateNotification.passive());
            Thread.sleep(80);

            assertThat(engine.isPaused()).as("DISAPPEARED must transition to Paused").isTrue();
            assertThat(engine.isActive()).as("Paused engine must not appear active").isFalse();
            assertThat(engine.currentPhaseForTesting())
                .as("currentPhase must be retained across pause (no reset to ZERO)")
                .isEqualTo(phaseBeforePause);

            // While paused, apply a same-phase Decision — engine commits it, demonstrating
            // that committed-state can advance even during pause.
            var nextDecision = new Decision<>(NODE_3, phaseBeforePause, StateValue.V1, Batch.<TestCommand>emptyBatch());
            engine.processDecision(nextDecision);
            Thread.sleep(80);

            var phaseDuringPause = engine.currentPhaseForTesting();
            assertThat(phaseDuringPause).as("Decision applied while Paused must advance phase").isEqualTo(phaseBeforePause.successor());

            // Quorum return → resume to Active without re-syncing. Same phase carries.
            engine.clusterState(ClusterStateNotification.active());
            Thread.sleep(80);

            assertThat(engine.isActive()).as("ESTABLISHED after Paused must resume to Active").isTrue();
            assertThat(engine.isPaused()).isFalse();
            assertThat(engine.currentPhaseForTesting())
                .as("phase must be retained across resume (no fresh sync round)")
                .isEqualTo(phaseDuringPause);
        }

        @Test
        void resume_doesNotTriggerSync() throws InterruptedException {
            activateEngine();
            engine.clusterState(ClusterStateNotification.passive());
            Thread.sleep(80);
            assertThat(engine.isPaused()).isTrue();

            network.clearMessages();
            engine.clusterState(ClusterStateNotification.active());
            Thread.sleep(100);

            // The cold-start path broadcasts a SyncRequest. Resume from Paused MUST NOT.
            var hasSyncRequest = network.getMessages().stream()
                .anyMatch(m -> m instanceof RabiaProtocolMessage.Asynchronous.SyncRequest);
            assertThat(hasSyncRequest)
                .as("Resume from Paused must not broadcast SyncRequest — state was retained")
                .isFalse();
            assertThat(engine.isActive()).isTrue();
        }
    }

    @Nested
    class ReconfigureResets {

        @Test
        void reconfigure_appliesNewMembership_resetsState() throws InterruptedException {
            activateEngine();

            // Advance state.
            var decision = new Decision<>(NODE_2, Phase.ZERO, StateValue.V1,
                                          Batch.<TestCommand>create(SERIALIZER, List.of(new TestCommand("pre-reconfigure"))));
            engine.processDecision(decision);
            Thread.sleep(80);
            assertThat(engine.currentPhaseForTesting()).isNotEqualTo(Phase.ZERO);

            // Apply a new membership — must wipe state.
            var newConfig = ClusterConfig.clusterConfig(List.of(NODE_1, NODE_2, NODE_3,
                                                                nodeId("node-4").unwrap(),
                                                                nodeId("node-5").unwrap())).unwrap();
            var reconfigureResult = engine.reconfigure(newConfig).await();
            Thread.sleep(50);

            assertThat(reconfigureResult.isSuccess()).as("reconfigure must succeed").isTrue();
            assertThat(engine.currentPhaseForTesting())
                .as("reconfigure must reset currentPhase to ZERO")
                .isEqualTo(Phase.ZERO);
            assertThat(engine.pendingBatchCountForTesting())
                .as("reconfigure must clear pendingBatches")
                .isZero();
            assertThat(engine.isActive())
                .as("after reconfigure engine awaits new quorum (Stopped, not Active)")
                .isFalse();
            var stored = engine.currentConfigForTesting();
            assertThat(stored.isPresent()).as("reconfigure must store new config").isTrue();
            stored.onPresent(cfg -> assertThat(cfg).isEqualTo(newConfig));
        }

        @Test
        void reconfigure_sameMembership_isNoOp() throws InterruptedException {
            // Lock in the membership BEFORE activation so the activation itself takes place
            // under a known config — this matters because the very first reconfigure call
            // (none → Some) is treated as a real change (it has to be: the engine was
            // previously oblivious to its membership and may need to align).
            var sameMembers = ClusterConfig.clusterConfig(List.of(NODE_1, NODE_2, NODE_3)).unwrap();
            engine.reconfigure(sameMembers).await();
            Thread.sleep(30);

            activateEngine();

            // Advance state under same config.
            var decision = new Decision<>(NODE_2, Phase.ZERO, StateValue.V1,
                                          Batch.<TestCommand>create(SERIALIZER, List.of(new TestCommand("under-same-config"))));
            engine.processDecision(decision);
            Thread.sleep(80);
            var phaseAfterDecision = engine.currentPhaseForTesting();
            assertThat(phaseAfterDecision).isNotEqualTo(Phase.ZERO);

            // Replay same membership — must NOT wipe state.
            var second = engine.reconfigure(sameMembers).await();
            Thread.sleep(30);
            assertThat(second.isSuccess()).isTrue();
            assertThat(engine.currentPhaseForTesting())
                .as("reconfigure with identical membership must not reset phase")
                .isEqualTo(phaseAfterDecision);
            assertThat(engine.isActive())
                .as("no-op reconfigure must not transition out of Active")
                .isTrue();
        }

        @Test
        void clusterConfig_rejectsEmptyMembership() {
            var result = ClusterConfig.clusterConfig(List.of());
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause ->
                assertThat(cause).isEqualTo(ClusterConfig.ClusterConfigError.EMPTY_MEMBERSHIP)
            );
        }

        @Test
        void clusterConfig_rejectsDuplicateMembers() {
            var result = ClusterConfig.clusterConfig(List.of(NODE_1, NODE_1, NODE_2));
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause ->
                assertThat(cause).isEqualTo(ClusterConfig.ClusterConfigError.DUPLICATE_MEMBERS)
            );
        }
    }

    @Nested
    class StateInvariants {

        @Test
        void isActive_isPaused_areMutuallyExclusive() throws InterruptedException {
            // Stopped: neither.
            assertThat(engine.isActive()).isFalse();
            assertThat(engine.isPaused()).isFalse();

            // Active.
            activateEngine();
            assertThat(engine.isActive()).isTrue();
            assertThat(engine.isPaused()).isFalse();

            // Paused.
            engine.clusterState(ClusterStateNotification.passive());
            Thread.sleep(80);
            assertThat(engine.isActive()).isFalse();
            assertThat(engine.isPaused()).isTrue();

            // Resumed → Active.
            engine.clusterState(ClusterStateNotification.active());
            Thread.sleep(80);
            assertThat(engine.isActive()).isTrue();
            assertThat(engine.isPaused()).isFalse();
        }

        @Test
        void pausedEngine_rejectsNewProposals_withoutDataLoss() throws InterruptedException {
            // Spec §4.5: "Paused state refuses new propose() calls (returns Result.failure(NotInQuorum))".
            // Implementation maps this to ConsensusError.QuorumPaused. Existing pendingBatches and
            // correlationMap are NOT touched — caller's prior submissions remain queued for the
            // resume, and the rejection itself carries no data (the new submission never enters
            // the protocol).
            activateEngine();

            engine.clusterState(ClusterStateNotification.passive());
            Thread.sleep(80);
            assertThat(engine.isPaused()).isTrue();

            var pendingBefore = engine.pendingBatchCountForTesting();

            var result = engine.apply(List.of(new TestCommand("rejected"))).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause ->
                assertThat(cause)
                    .as("Paused engine must reject submissions with QuorumPaused, not NodeInactive")
                    .isInstanceOf(ConsensusError.QuorumPaused.class)
            );
            assertThat(engine.pendingBatchCountForTesting())
                .as("Rejected submission must not affect existing pendingBatches")
                .isEqualTo(pendingBefore);
            assertThat(engine.isPaused())
                .as("Engine must remain Paused after rejection — state is preserved")
                .isTrue();
        }
    }

    // ==================== Stub Implementations ====================
    // (Duplicated from RabiaEngineTest deliberately — keeps R1 tests independent of the
    //  pre-existing test file's stub evolution. These stubs will be retired alongside
    //  the parent test once a shared test fixture is introduced in a later phase.)

    static class TestTopologyManager implements TopologyManager {
        private final NodeInfo self;
        private final int clusterSize;

        TestTopologyManager(NodeId selfId, int clusterSize) {
            this.self = NodeInfo.nodeInfo(selfId, NodeAddress.nodeAddress("localhost", 5000).unwrap());
            this.clusterSize = clusterSize;
        }

        @Override public NodeInfo self() { return self; }

        @Override public Option<NodeInfo> get(NodeId id) {
            return Option.option(NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", 5000).unwrap()));
        }

        @Override public int clusterSize() { return clusterSize; }

        @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) { return Option.empty(); }

        @Override public Promise<Unit> start() { return Promise.success(Unit.unit()); }

        @Override public Promise<Unit> stop() { return Promise.success(Unit.unit()); }

        @Override public TimeSpan pingInterval() { return timeSpan(1).seconds(); }

        @Override public TimeSpan helloTimeout() { return timeSpan(5).seconds(); }

        @Override public Option<NodeState> getState(NodeId id) { return Option.empty(); }

        @Override public List<NodeId> topology() { return List.of(); }
    }

    static class TestClusterNetwork implements ClusterNetwork {
        private final List<ProtocolMessage> messages = new CopyOnWriteArrayList<>();

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {
            messages.add(message);
            return Unit.unit();
        }

        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}

        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}

        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}

        @Override public void handleSend(NetworkServiceMessage.Send send) {}

        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            messages.add(message);
            return Unit.unit();
        }

        @Override public Promise<Unit> start() { return Promise.success(Unit.unit()); }

        @Override public Promise<Unit> stop() { return Promise.success(Unit.unit()); }

        @Override public int connectedNodeCount() { return 0; }

        @Override public Set<NodeId> connectedPeers() { return Set.of(); }

        @Override public Option<Server> server() { return Option.none(); }

        List<ProtocolMessage> getMessages() { return Collections.unmodifiableList(messages); }

        void clearMessages() { messages.clear(); }
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

        @Override public org.pragmatica.serialization.Serializer serializer() { return SERIALIZER; }

        @Override public Result<byte[]> makeSnapshot() { return Result.success(new byte[0]); }

        @Override public Result<Unit> restoreSnapshot(byte[] snapshot) { return Result.success(Unit.unit()); }

        @Override public Unit reset() {
            processedCommands.clear();
            return Unit.unit();
        }
    }
}
