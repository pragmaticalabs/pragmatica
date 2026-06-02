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
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
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

/// Pinpoints the #230 fix in `RabiaEngine.doHandleSyncRequest(SyncRequest)`:
/// the branch was widened from `isActive() || isObserving()` to additionally include
/// `|| isPaused()`. Effect: a Paused engine, which RETAINS its in-memory protocol state
/// (currentPhase + pendingBatches), now serves a LIVE-equivalent `SyncResponse` carrying
/// those pending batches — instead of the persisted/empty fallback that omits them and
/// wedges a joiner unable to re-propose an in-flight batch.
///
/// Deterministic, engine-level, no chaos. The only sleeps are the same executor-drain
/// waits the established `RabiaEnginePauseResumeTest` harness uses (single-threaded
/// in-process executor; not timing-dependent on wall-clock behaviour).
class RabiaPausedSyncResponseTest {

    record TestCommand(String value) implements Command {}

    private static final org.pragmatica.serialization.SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);

    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final NodeId JOINER = nodeId("joiner").unwrap();
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

    private SyncResponse<TestCommand> lastSyncResponseTo() {
        @SuppressWarnings("unchecked")
        var responses = network.getMessages().stream()
                               .filter(m -> m instanceof SyncResponse<?>)
                               .map(m -> (SyncResponse<TestCommand>) m)
                               .toList();
        assertThat(responses)
            .as("engine must have sent exactly one SyncResponse to the SyncRequest sender")
            .hasSize(1);
        return responses.getLast();
    }

    /// GREEN with the fix: a Paused engine holding a non-empty pendingBatches set and an
    /// advanced currentPhase answers a SyncRequest with a SyncResponse that INCLUDES the
    /// seeded batch and the retained phase. RED without `|| isPaused()`: the engine falls to
    /// the persisted/empty branch and the response carries zero pending batches.
    ///
    /// DISCRIMINATOR: the seeded batch is delivered AFTER the engine is already Paused. The
    /// pause handler persists a snapshot of pendingBatches AT pause time, so any batch present
    /// before the pause also lives in the persisted fallback and could NOT distinguish the two
    /// branches. A batch arriving while Paused (`handleNewBatch`) enters only LIVE pendingBatches
    /// and is never re-persisted — so it appears in the response if and only if the Paused branch
    /// serves live state. This is the exact #230 wedge the fix repairs.
    @Test
    void pausedEngine_servesPendingBatches_onSyncRequest() throws InterruptedException {
        activateEngine();
        assertThat(engine.isActive()).isTrue();

        // Advance currentPhase past ZERO via a committed Decision (same mechanism as the
        // pause/resume suite) so we can also assert the retained phase travels in the response.
        var decisionBatch = Batch.<TestCommand>create(SERIALIZER, List.of(new TestCommand("committed")));
        engine.processDecision(new Decision<>(NODE_2, Phase.ZERO, StateValue.V1, decisionBatch));
        Thread.sleep(80);
        var phaseBeforePause = engine.currentPhaseForTesting();
        assertThat(phaseBeforePause).as("phase must advance past ZERO").isNotEqualTo(Phase.ZERO);

        // Quorum loss → Paused. State (currentPhase + pendingBatches) is retained AND a snapshot
        // is persisted at this instant.
        engine.clusterState(ClusterStateNotification.passive());
        Thread.sleep(80);
        assertThat(engine.isPaused()).as("DISAPPEARED must transition to Paused").isTrue();

        // Seed an in-flight batch AFTER the pause via the supported NewBatch receiver. This batch
        // enters LIVE pendingBatches but is NOT in the pause-time persisted snapshot — it is the
        // discriminator between the live-state and persisted-fallback branches.
        var postPauseBatch = Batch.<TestCommand>create(SERIALIZER, List.of(new TestCommand("arrived-while-paused")));
        engine.handleNewBatch(new NewBatch<>(NODE_2, postPauseBatch));
        Thread.sleep(50);
        assertThat(engine.pendingBatchCountForTesting())
            .as("post-pause batch must be retained in LIVE pendingBatches")
            .isGreaterThanOrEqualTo(1);

        network.clearMessages();

        // A joiner asks the Paused engine to sync.
        engine.handleSyncRequest(new SyncRequest(JOINER));
        Thread.sleep(80);

        var response = lastSyncResponseTo();
        assertThat(response.sender()).isEqualTo(NODE_1);
        assertThat(response.state().pendingBatches())
            .as("Paused engine must serve its LIVE retained pendingBatches incl. the post-pause "
                + "batch (fix: || isPaused()); the persisted fallback omits this batch")
            .isNotEmpty()
            .contains(postPauseBatch);
        assertThat(response.state().lastCommittedPhase())
            .as("Paused engine must serve its retained currentPhase")
            .isEqualTo(phaseBeforePause);
    }

    /// Guard: confirms the fix did NOT over-broaden. A Stopped engine (fresh, never activated,
    /// no valid live state) must still answer from the persisted/empty fallback — i.e. an
    /// EMPTY pendingBatches set — proving only Active/Observing/Paused serve live state.
    @Test
    void stoppedEngine_servesEmptyFallback_onSyncRequest() throws InterruptedException {
        // Fresh engine: initial state is Stopped(), in-memory persistence is empty.
        assertThat(engine.isActive()).isFalse();
        assertThat(engine.isPaused()).isFalse();

        network.clearMessages();
        engine.handleSyncRequest(new SyncRequest(JOINER));
        Thread.sleep(80);

        var response = lastSyncResponseTo();
        assertThat(response.state().pendingBatches())
            .as("Stopped engine must fall back to persisted/empty state — no live pendingBatches")
            .isEmpty();
        assertThat(response.state().lastCommittedPhase())
            .as("empty fallback carries Phase.ZERO")
            .isEqualTo(Phase.ZERO);
    }

    // ==================== Stub Implementations ====================
    // (Mirrors RabiaEnginePauseResumeTest's stubs to keep this test independent.)

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
