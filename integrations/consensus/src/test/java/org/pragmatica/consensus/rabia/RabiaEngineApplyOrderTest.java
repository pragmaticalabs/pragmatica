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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.PoisonStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #1109 T3: the rabia-level half of "the leader's command must not act on a state the follower has not applied".
///
/// The leader (here a scripted peer) decided the target put in slot 1 and the LOAD in slot 2. The follower's apply
/// executor is busy (apply lag), so both Decisions queue behind it and are handled in REVERSE slot order: LOAD first.
/// The contract pinned: the follower's state machine applies strictly by slot, so when the LOAD runs the target is
/// already in its store. Mechanism: a Decision past the current slot is buffered (`handleDecision`) and released only
/// when the missing slot has applied (#1755). No sleeps: the executor is held by a latch and quiesced with
/// `settleForTesting()`.
class RabiaEngineApplyOrderTest {
    private static final org.pragmatica.serialization.SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final String HELD = "held";
    private static final String TARGET = "target-put";
    private static final String LOAD = "load";

    private final CountDownLatch heldEntered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch loadApplied = new CountDownLatch(1);
    private final List<String> order = new CopyOnWriteArrayList<>();
    /// What the follower's store held at the moment each command was applied.
    private final Map<String, List<String>> storeWhenApplied = new ConcurrentHashMap<>();
    private RabiaEngine<TestCommand> engine;

    @BeforeEach
    void setUp() throws InterruptedException {
        var stateMachine = new PoisonStateMachine() {
            @Override
            @SuppressWarnings("unchecked")
            public <R> List<R> process(Batch<TestCommand> batch) {
                batch.commands().forEach(this::applyOne);

                return (List<R>) batch.commands().stream().map(command -> "result:" + command.value()).toList();
            }

            private void applyOne(TestCommand command) {
                storeWhenApplied.put(command.value(), List.copyOf(order));
                order.add(command.value());
                if (HELD.equals(command.value())) {
                    heldEntered.countDown();
                    Result.lift(() -> release.await()).unwrap();
                }
                if (LOAD.equals(command.value())) {
                    loadApplied.countDown();
                }
            }
        };

        engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, 3), new TestClusterNetwork(), stateMachine,
                                   ProtocolConfig.testConfig());
        engine.clusterState(ClusterStateNotification.active());
        engine.settleForTesting().await().unwrap();
        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.settleForTesting().await().unwrap();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        engine.stop().await();
    }

    @Test
    void loadDecisionDeliveredBeforeTargetDecision_isAppliedAfterTargetOnALaggingFollower() throws InterruptedException {
        engine.processDecision(decision(Phase.ZERO, HELD));
        assertThat(heldEntered.await(5, TimeUnit.SECONDS)).as("CONTROL: the apply executor is held inside slot 0").isTrue();

        // Reverse order, while the executor is held: LOAD (slot 2) is queued ahead of the target (slot 1).
        engine.processDecision(decision(new Phase(2), LOAD));
        engine.processDecision(decision(new Phase(1), TARGET));
        assertThat(order).as("CONTROL: nothing past slot 0 applied while the executor is held").containsExactly(HELD);

        release.countDown();
        assertThat(loadApplied.await(5, TimeUnit.SECONDS)).as("the LOAD decision must eventually apply").isTrue();
        engine.settleForTesting().await().unwrap();

        assertThat(storeWhenApplied.get(LOAD)).as("the follower's store when the LOAD ran").contains(TARGET);
        assertThat(order).containsExactly(HELD, TARGET, LOAD);
        assertThat(engine.currentPhaseForTesting()).isEqualTo(new Phase(3));
        assertThat(engine.bufferedDecisionCountForTesting()).as("nothing left buffered").isZero();
    }

    private static Decision<TestCommand> decision(Phase phase, String value) {
        return new Decision<>(NODE_2, phase, StateValue.V1, Batch.create(SERIALIZER, List.of(new TestCommand(value))));
    }
}
