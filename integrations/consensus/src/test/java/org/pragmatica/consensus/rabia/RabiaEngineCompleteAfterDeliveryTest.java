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
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.PoisonStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// KVStore dispatcher design §3.3 (P3), the engine half: at commit, the engine hands the caller's apply promise to
/// `StateMachine.completeAfterDelivery` and never succeeds it itself. Every P3 pin above the engine (the RYW tests) drives
/// `completeAfterDelivery` from a mocked node, so without this test the engine call site is unpinned (v-2648 F3).
/// Mutation: `RabiaEngine.commitChanges` succeeds the promise directly (`promise.succeed(results)`): the apply promise
/// resolves while the state machine still holds it, red.
class RabiaEngineCompleteAfterDeliveryTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();

    private final AtomicReference<Runnable> parked = new AtomicReference<>();
    private RabiaEngine<TestCommand> engine;

    @AfterEach
    void tearDown() {
        engine.stop().await();
    }

    @Test
    void applyPromise_resolvesOnlyWhenTheStateMachineCompletesIt() throws InterruptedException {
        var stateMachine = new PoisonStateMachine() {
            @Override
            public <T> Unit completeAfterDelivery(Promise<T> promise, T value) {
                parked.set(() -> promise.succeed(value));

                return Unit.unit();
            }
        };
        var network = new TestClusterNetwork() {
            @Override
            public Set<NodeId> connectedPeers() {
                return Set.of(NODE_2, NODE_3);
            }
        };

        engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, 3), network, stateMachine, ProtocolConfig.testConfig());
        engine.clusterState(ClusterStateNotification.active());
        engine.settleForTesting().await().unwrap();
        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.settleForTesting().await().unwrap();

        Promise<List<Object>> applied = engine.apply(List.of(new TestCommand("ryw")));
        var batch = awaitSubmittedBatch(network);

        engine.processDecision(new Decision<>(NODE_2, Phase.ZERO, StateValue.V1, batch));
        engine.settleForTesting().await().unwrap();

        assertThat(stateMachine.appliedCommands).as("CONTROL: the command committed and applied")
                                                .extracting(TestCommand::value)
                                                .contains("ryw");
        assertThat(parked.get()).as("the engine handed the apply promise to completeAfterDelivery").isNotNull();
        Thread.sleep(200);
        assertThat(applied.isResolved()).as("the apply promise must not resolve before the state machine completes it")
                                        .isFalse();

        parked.get().run();
        assertThat(applied.await(timeSpan(5).seconds()).isSuccess()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static Batch<TestCommand> awaitSubmittedBatch(TestClusterNetwork network) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            var found = network.getMessages()
                               .stream()
                               .filter(NewBatch.class::isInstance)
                               .map(message -> ((NewBatch<TestCommand>) message).batch())
                               .filter(batch -> batch.commands().stream().anyMatch(c -> "ryw".equals(c.value())))
                               .findFirst();

            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("CONTROL: the submitted batch was never broadcast");
    }
}
