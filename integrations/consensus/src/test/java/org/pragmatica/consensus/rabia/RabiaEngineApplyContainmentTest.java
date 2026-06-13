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
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.NodeState;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Regression guard for the "7c" consensus apply-worker containment fix.
///
/// The Rabia apply executor is a SINGLE-thread `ThreadPoolExecutor(1, 1, ...)`. Before the fix,
/// a `RuntimeException` thrown by an apply/restore handler escaped the worker's `run()`. The fix
/// wraps every executor task in `RabiaEngine.safeExecute(...)` (try/catch RuntimeException) so the
/// worker thread is preserved.
///
/// Discriminating observable — worker THREAD IDENTITY. A bare `ThreadPoolExecutor(1, 1, ...)`
/// SELF-HEALS: when a worker dies from an uncaught exception it is silently replaced, so
/// "a later task still runs" is true even WITHOUT the guard and cannot detect the regression
/// (verified directly). What differs is WHICH thread services subsequent apply tasks:
///   - WITH the guard:   the same worker thread keeps servicing tasks (no death).
///   - WITHOUT the guard: the worker thread dies on the throw and the executor spins up a NEW
///                        worker thread to drain the queue.
/// This test injects a throw at the live-apply seam (`StateMachine.process(Batch)`), drives the
/// poison through a V1 decision so the throw runs ON the executor, then asserts the worker thread
/// that services a SUBSEQUENT apply task (`StateMachine.merge`, also executor-bound) is the SAME
/// thread that serviced an apply BEFORE the poison. Without the guard the worker is replaced and
/// the thread identity changes, failing the assertion.
class RabiaEngineApplyContainmentTest {

    record TestCommand(String value) implements Command {}

    private static final String POISON = "poison";

    private static final org.pragmatica.serialization.SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);

    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final int CLUSTER_SIZE = 3;

    private TestTopologyManager topologyManager;
    private TestClusterNetwork network;
    private PoisonStateMachine stateMachine;
    private RabiaEngine<TestCommand> engine;

    @BeforeEach
    void setUp() {
        topologyManager = new TestTopologyManager(NODE_1, CLUSTER_SIZE);
        network = new TestClusterNetwork();
        stateMachine = new PoisonStateMachine();
        engine = new RabiaEngine<>(topologyManager, network, stateMachine, ProtocolConfig.testConfig());
    }

    @AfterEach
    void tearDown() {
        engine.stop().await();
    }

    @Test
    void worker_thread_survives_when_apply_handler_throws_so_same_worker_processes_subsequent_task()
        throws InterruptedException {
        activateEngine();

        // Baseline: a normal command driven to a V1 decision is applied via
        // stateMachine.process() ON the executor worker. Capture that worker thread.
        driveCommandToV1Decision(Phase.ZERO, new TestCommand("baseline"));
        var baselineWorker = stateMachine.lastApplyThread.get();
        assertThat(baselineWorker)
            .as("baseline apply must have run on the executor worker")
            .isNotNull();

        // Poison: a command whose apply seam throws a RuntimeException, driven to a V1 decision.
        // stateMachine.process() throws ON the executor worker; the guard must contain it so the
        // worker thread is preserved.
        driveCommandToV1Decision(new Phase(1), new TestCommand(POISON));
        assertThat(stateMachine.poisonAttempts.get())
            .as("poison apply must actually have run on the executor (the throwing seam was reached)")
            .isGreaterThanOrEqualTo(1);

        // Subsequent executor-bound apply work: feed the same fresh batch twice via handleNewBatch.
        // The second arrival routes through stateMachine.merge() (an apply-path call) ON the worker.
        // Capture the worker thread servicing it.
        stateMachine.lastMergeThread.set(null);
        var followUp = Batch.create(SERIALIZER, List.of(new TestCommand("after-poison")));
        engine.handleNewBatch(new NewBatch<>(NODE_2, followUp));
        engine.handleNewBatch(new NewBatch<>(NODE_3, followUp));
        Thread.sleep(150);

        var followUpWorker = stateMachine.lastMergeThread.get();
        assertThat(followUpWorker)
            .as("subsequent apply work (merge) must have run on the executor worker")
            .isNotNull();

        // Containment contract: the SAME worker thread services the post-poison task. Without the
        // guard the poison kills the worker and the executor replaces it with a different thread.
        assertThat(followUpWorker)
            .as("worker thread must survive the apply-handler throw — same thread services the "
                + "subsequent executor task (a replaced thread proves the worker died unguarded)")
            .isSameAs(baselineWorker);
    }

    /// Drives a single command through proposals + round-1 + round-2 votes to a V1 decision in
    /// the given phase, mirroring the protocol-flow idiom from `RabiaEngineTest`. The local
    /// engine commits the decision and applies the batch on its executor thread.
    private void driveCommandToV1Decision(Phase phase, TestCommand command) throws InterruptedException {
        var batch = Batch.create(SERIALIZER, List.of(command));

        engine.processPropose(new Propose<>(NODE_1, phase, batch));
        engine.processPropose(new Propose<>(NODE_2, phase, batch));
        Thread.sleep(50);

        engine.processVoteRound1(new VoteRound1(NODE_2, phase, StateValue.V1));
        engine.processVoteRound1(new VoteRound1(NODE_3, phase, StateValue.V1));
        Thread.sleep(50);

        engine.processVoteRound2(new VoteRound2(NODE_2, phase, StateValue.V1));
        engine.processVoteRound2(new VoteRound2(NODE_3, phase, StateValue.V1));
        Thread.sleep(100);
    }

    private void activateEngine() throws InterruptedException {
        engine.clusterState(ClusterStateNotification.active());
        Thread.sleep(150);
        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty()));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty()));
        Thread.sleep(50);
    }

    /// State machine whose apply seam (`process`) throws on the poison command and behaves
    /// normally otherwise. Records the worker thread servicing each apply-path call so the test
    /// can assert the single executor worker survived the throw.
    static class PoisonStateMachine implements StateMachine<TestCommand> {
        final List<TestCommand> appliedCommands = new CopyOnWriteArrayList<>();
        final AtomicInteger poisonAttempts = new AtomicInteger();
        final AtomicReference<Thread> lastApplyThread = new AtomicReference<>();
        final AtomicReference<Thread> lastMergeThread = new AtomicReference<>();

        @Override
        @SuppressWarnings("unchecked")
        public <R> List<R> process(Batch<TestCommand> batch) {
            return batch.commands()
                        .stream()
                        .map(command -> (R) processOne(command))
                        .toList();
        }

        private String processOne(TestCommand command) {
            if (POISON.equals(command.value())) {
                poisonAttempts.incrementAndGet();
                throw new IllegalStateException("injected apply failure for poison command");
            }
            lastApplyThread.set(Thread.currentThread());
            appliedCommands.add(command);
            return "result:" + command.value();
        }

        @Override
        public Batch<TestCommand> merge(Batch<TestCommand> a, Batch<TestCommand> b) {
            lastMergeThread.set(Thread.currentThread());
            return StateMachine.super.merge(a, b);
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
            appliedCommands.clear();
            return Unit.unit();
        }
    }

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
            return 0;
        }

        @Override
        public Set<NodeId> connectedPeers() {
            return Set.of();
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
}
