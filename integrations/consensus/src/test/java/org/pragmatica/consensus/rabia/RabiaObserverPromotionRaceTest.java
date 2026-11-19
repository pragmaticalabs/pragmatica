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
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3452: an observer's promotion to voter decides on the engine's executor, in the same step as its write.
///
/// [RabiaEngine#authorizeActivation] used to test `isObserving()` on the caller thread and queue a promotion that wrote
/// `Idle` unconditionally, so a quorum-loss pause queued ahead of it was overwritten and the engine left a pause it
/// should hold. The race arm makes the interleaving deterministic: the apply executor is parked in a task, the pause and
/// then the authorization are queued behind it, and only then is the executor released. The control arm pins that a
/// promotion from `Observing` still promotes.
class RabiaObserverPromotionRaceTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        engines.forEach(engine -> engine.stop().await());
    }

    @Test
    void promotionQueuedBehindAQuorumLossPause_leavesTheEnginePaused() throws InterruptedException {
        var events = new CopyOnWriteArrayList<String>();
        var engine = observingEngine(events);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        engine.safeExecuteForTesting(() -> parked(entered, release));
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("the executor must be parked before anything is queued").isTrue();
        engine.clusterState(ClusterStateNotification.passive());
        engine.authorizeActivation();
        release.countDown();
        quiesce(engine);

        assertThat(engine.isPaused()).as("a pause queued first must survive the promotion; events=" + events).isTrue();
        assertThat(engine.isActive()).as("events=" + events).isFalse();
    }

    @Test
    void promotionRefusedWhilePaused_isNotLost_theEngineResumesAsAVoter() throws InterruptedException {
        var events = new CopyOnWriteArrayList<String>();
        var engine = observingEngine(events);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        engine.safeExecuteForTesting(() -> parked(entered, release));
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("the executor must be parked before anything is queued").isTrue();
        engine.clusterState(ClusterStateNotification.passive());
        engine.authorizeActivation();
        release.countDown();
        quiesce(engine);
        engine.clusterState(ClusterStateNotification.active());
        quiesce(engine);

        assertThat(engine.isActive()).as("the authorization outlives the pause; events=" + events).isTrue();
        assertThat(engine.isObserving()).as("events=" + events).isFalse();
    }

    @Test
    void promotionFromObserving_promotesToActive() {
        var events = new CopyOnWriteArrayList<String>();
        var engine = observingEngine(events);

        engine.authorizeActivation();
        quiesce(engine);

        assertThat(engine.isActive()).as("events=" + events).isTrue();
        assertThat(engine.isObserving()).as("events=" + events).isFalse();
    }

    private RabiaEngine<TestCommand> observingEngine(List<String> events) {
        var network = new TestClusterNetwork();
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, 3),
                                       network,
                                       new TestStateMachine(),
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                       ConsensusMetrics.noop(),
                                       true,
                                       emptyDisk(),
                                       timeSpan(50).millis(),
                                       event -> events.add(event.getClass().getSimpleName()));

        engines.add(engine);
        engine.clusterState(ClusterStateNotification.active());
        engine.authorizeObservation();
        assertThat(await(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance))).isTrue();
        engine.processSyncResponse(cold(NODE_2));
        engine.processSyncResponse(cold(NODE_3));
        assertThat(await(engine::isObserving)).as("observing; events=" + events).isTrue();

        return engine;
    }

    private static void parked(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Two barriers in a row: the first runs after everything queued so far, the second after any task those queued
    /// (the promotion queues `startPhase`).
    private static void quiesce(RabiaEngine<TestCommand> engine) {
        barrier(engine);
        barrier(engine);
    }

    private static void barrier(RabiaEngine<TestCommand> engine) {
        var done = new CountDownLatch(1);

        engine.safeExecuteForTesting(done::countDown);
        try {
            assertThat(done.await(5, TimeUnit.SECONDS)).as("executor barrier").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static SyncResponse<TestCommand> cold(NodeId sender) {
        return new SyncResponse<>(sender,
                                  SavedState.savedState(new byte[]{1, 2, 3}, Phase.phase(10), List.of()),
                                  ResponderState.COLD);
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
