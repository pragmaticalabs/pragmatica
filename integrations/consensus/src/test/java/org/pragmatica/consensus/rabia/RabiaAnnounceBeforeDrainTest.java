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
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.SliceCodec;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3452 (related): the restore's notification replay is "world-as-of-N", and the Decisions buffered while the engine
/// synchronised are "N+1 onward" ([RabiaEngine] `replayStateNotifications`). So an activation announces the restore
/// BEFORE it drains the buffer: draining first applied (and notified) the live Decisions ahead of the replay, and on an
/// observer the announce fired from inside the drain, at the first applied Decision.
class RabiaAnnounceBeforeDrainTest {
    private static final SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final Phase PEER_PHASE = Phase.phase(10);
    private static final byte[] PEER_SNAPSHOT = "peer".getBytes(StandardCharsets.UTF_8);
    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        engines.forEach(engine -> engine.stop().await());
    }

    @Test
    void activation_replaysTheRestoreBeforeApplyingBufferedDecisions() {
        var machine = new OrderingStateMachine();
        var engine = syncing(machine, false);

        bufferLiveDecisions(engine);
        engine.processSyncResponse(cold(NODE_2));

        assertThat(await(() -> machine.order.contains("replay") && machine.order.contains("apply"))).as("order=" + machine.order)
                                                                                                    .isTrue();
        assertThat(machine.order.indexOf("replay")).as("world-as-of-N before N+1 onward; order=" + machine.order)
                                                   .isLessThan(machine.order.indexOf("apply"));
    }

    @Test
    void observerActivation_replaysTheRestoreBeforeApplyingBufferedDecisions() {
        var machine = new OrderingStateMachine();
        var engine = syncing(machine, true);

        bufferLiveDecisions(engine);
        engine.processSyncResponse(cold(NODE_2));
        engine.processSyncResponse(cold(NODE_3));

        assertThat(await(() -> machine.order.contains("replay") && machine.order.contains("apply"))).as("order=" + machine.order)
                                                                                                    .isTrue();
        assertThat(machine.order.indexOf("replay")).as("world-as-of-N before N+1 onward; order=" + machine.order)
                                                   .isLessThan(machine.order.indexOf("apply"));
    }

    /// Decisions at the adopted frontier and one past it, delivered while the engine is still `Syncing`: buffered.
    private static void bufferLiveDecisions(RabiaEngine<TestCommand> engine) {
        engine.processDecision(decision(PEER_PHASE, "at-frontier"));
        engine.processDecision(decision(PEER_PHASE.successor(), "past-frontier"));
    }

    private RabiaEngine<TestCommand> syncing(OrderingStateMachine machine, boolean gatedObserver) {
        var network = new TestClusterNetwork();
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, 3),
                                       network,
                                       machine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                       ConsensusMetrics.noop(),
                                       gatedObserver,
                                       emptyDisk(),
                                       timeSpan(50).millis());

        engines.add(engine);
        engine.clusterState(ClusterStateNotification.active());
        if (gatedObserver) {
            engine.authorizeObservation();
        }
        assertThat(await(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance))).as("sync round open")
                                                                                                       .isTrue();

        return engine;
    }

    private static Decision<TestCommand> decision(Phase phase, String value) {
        return new Decision<>(NODE_2, phase, StateValue.V1, Batch.create(SERIALIZER, List.of(new TestCommand(value))));
    }

    private static SyncResponse<TestCommand> cold(NodeId sender) {
        return new SyncResponse<>(sender, SavedState.savedState(PEER_SNAPSHOT, PEER_PHASE, List.of()), ResponderState.COLD);
    }

    /// Records the restore's replay and every live apply, in the order the apply thread runs them.
    private static final class OrderingStateMachine extends TestStateMachine {
        final List<String> order = new CopyOnWriteArrayList<>();

        @Override
        public <R> List<R> process(Batch<TestCommand> batch) {
            order.add("apply");

            return super.process(batch);
        }

        @Override
        public Unit replayNotifications() {
            order.add("replay");

            return Unit.unit();
        }
    }

    private static RabiaPersistence<TestCommand> emptyDisk() {
        record emptyDisk() implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine,
                                     Phase lastCommittedPhase,
                                     Collection<StateMachine.Batch<TestCommand>> pendingBatches) {
                return Result.unitResult();
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.none();
            }
        }
        return new emptyDisk();
    }

    private static boolean await(BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }
}
