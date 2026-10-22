// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// Engine-side tripwires TW-O3, TW-O4 and the engine half of TW-O8 of the worker-replica-feed design (W-OBS).
/// Each is an ENABLED test of today's behaviour, with the real assertion of the target design `@Disabled` beside it.
///
/// - TW-O3: `authorizePassiveClient()` sets the passive-client flag once and nothing clears it, so a worker that
///   loses its feed stays "ready" (W4 replaces the setter with `setPassiveClientReady(boolean)`).
/// - TW-O4: a passive engine fed a `Decision` or a quorum notification applies nothing and stays out of every
///   serving state. The replica feed applies outside the engine (W3), so this must remain true; it fails if the
///   apply path is ever routed through the engine on a worker. There is no real-assertion twin: the design
///   asserts the same thing.
/// - TW-O8 (engine half): the state machine is told about batches that commit a non-empty V1 decision and nothing
///   else, so a V0 or empty slot advances the engine phase without the state machine learning of it. W1 adds
///   `StateMachine.onPhaseCompleted(long nextSlot)` for exactly this gap; the `KVStore` half is
///   `KVStoreEffectReplayTest`.
class ReplicaFeedEngineTripwireTest {
    private static final NodeId WORKER = nodeId("worker-1").unwrap();
    private static final NodeId CORE_1 = nodeId("core-1").unwrap();
    private static final NodeId CORE_2 = nodeId("core-2").unwrap();
    private static final NodeId CORE_3 = nodeId("core-3").unwrap();
    private static final Set<NodeId> CORES = Set.of(CORE_1, CORE_2, CORE_3);

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopEngines() {
        engines.forEach(engine -> engine.stop().await());
    }

    /// A state machine that records what the engine tells it, beyond what `TestStateMachine` keeps.
    private static final class CountingStateMachine extends TestStateMachine {
        final AtomicInteger committedBatches = new AtomicInteger();

        @Override
        public <R> List<R> processCommitted(Batch<TestCommand> batch, long nextSlot) {
            committedBatches.incrementAndGet();

            return super.processCommitted(batch, nextSlot);
        }
    }

    private final TestClusterNetwork network = new TestClusterNetwork();

    private RabiaEngine<TestCommand> engine(NodeId self, StateMachine<TestCommand> machine, boolean gated) {
        var topology = new TestTopologyManager(self, 3) {
            @Override
            public java.util.Set<NodeId> coreNodes() {
                return CORES;
            }

            @Override
            public boolean isConsensusMember(NodeId id) {
                return CORES.contains(id);
            }
        };
        var created = new RabiaEngine<>(topology,
                                        network,
                                        machine,
                                        ProtocolConfig.consensusConfig(org.pragmatica.lang.io.TimeSpan.timeSpan(60).seconds(),
                                                                       org.pragmatica.lang.io.TimeSpan.timeSpan(60).seconds()),
                                        ConsensusMetrics.noop(),
                                        gated);

        engines.add(created);

        return created;
    }

    private RabiaEngine<TestCommand> passiveEngine(StateMachine<TestCommand> machine) {
        var passive = engine(WORKER, machine, true);

        assertThat(passive.configurePassiveClient().isSuccess()).as("precondition: the engine is a passive client").isTrue();

        return passive;
    }

    private RabiaEngine<TestCommand> activeEngine(StateMachine<TestCommand> machine) {
        var active = engine(CORE_1, machine, false);

        active.clusterState(ClusterStateNotification.active());
        await(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance));
        active.processSyncResponse(response(CORE_2));
        active.processSyncResponse(response(CORE_3));
        await(active::isActive);

        return active;
    }

    private static SyncResponse<TestCommand> response(NodeId sender) {
        return new SyncResponse<>(sender, SavedState.savedState(new byte[0], Phase.ZERO, List.of()), ResponderState.COLD);
    }

    private static Decision<TestCommand> committing(StateMachine<TestCommand> machine, long phase, String value) {
        return new Decision<>(CORE_2, Phase.phase(phase), StateValue.V1, Batch.create(machine.serializer(), List.of(new TestCommand(value))));
    }

    private static Decision<TestCommand> empty(long phase) {
        return new Decision<>(CORE_2, Phase.phase(phase), StateValue.V0, Batch.emptyBatch());
    }

    @Test
    void authorizePassiveClient_setsTheFlagOnce_andNothingClearsIt() {
        var passive = passiveEngine(new TestStateMachine());

        assertThat(passive.isPassiveClientReady()).as("before authorization").isFalse();
        passive.authorizePassiveClient();
        assertThat(passive.isPassiveClientReady()).as("after authorization").isTrue();

        passive.clusterState(ClusterStateNotification.passive());
        passive.settleForTesting().await();
        passive.processDecision(empty(0));
        passive.settleForTesting().await();

        assertThat(passive.isPassiveClientReady()).as("TW-O3: a quorum-lost notification and a decision change nothing, the flag is a latch").isTrue();
        assertThat(clearingSetter()).as("TW-O3: no public method of the engine can clear the flag today; W4 adds setPassiveClientReady(boolean) and deletes authorizePassiveClient").isNull();
    }

    @Disabled("W4 (cutover): enable when RabiaEngine.setPassiveClientReady(boolean) replaces authorizePassiveClient and readiness is computed by ReplicaReadiness")
    @Test
    void isPassiveClientReady_isFalse_whenReadinessFails() {
        var passive = passiveEngine(new TestStateMachine());
        var setter = clearingSetter();

        assertThat(setter).as("W4 adds RabiaEngine.setPassiveClientReady(boolean); readiness is set and cleared from one computation").isNotNull();
        invoke(setter, passive, true);
        assertThat(passive.isPassiveClientReady()).isTrue();
        invoke(setter, passive, false);
        assertThat(passive.isPassiveClientReady()).as("readiness failed, so the engine reports the worker as not ready").isFalse();
    }

    @Test
    void passiveEngine_appliesNothingAndStaysStopped_whenFedDecisionsAndQuorumNotifications() {
        var machine = new CountingStateMachine();
        var passive = passiveEngine(machine);

        passive.clusterState(ClusterStateNotification.active());
        passive.processDecision(committing(machine, 0, "never applied"));
        passive.processDecision(empty(1));
        passive.settleForTesting().await();
        passive.settleForTesting().await();

        assertThat(machine.getProcessedCommands()).as("TW-O4: a passive engine applies no command").isEmpty();
        assertThat(machine.committedBatches.get()).as("and commits no batch").isZero();
        assertThat(passive.isActive()).isFalse();
        assertThat(passive.isPaused()).isFalse();
        assertThat(passive.isObserving()).isFalse();
        assertThat(passive.currentPhaseForTesting()).as("and never advances its phase").isEqualTo(Phase.ZERO);
        assertThat(passive.bufferedDecisionCountForTesting()).as("nor buffers a decision for a later replay: the passive engine never reaches the apply path at all").isZero();
        assertThat(passive.isPendingCatchUp()).as("nor starts a sync (a quorum notification would put a voting engine in Syncing)").isFalse();
        assertThat(network.getMessages()).as("and sends nothing").isEmpty();
    }

    @Test
    void controlEngine_appliesAnAgreedDecision() {
        var machine = new CountingStateMachine();
        var active = activeEngine(machine);

        active.processDecision(committing(machine, 0, "applied"));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(1)));

        assertThat(machine.getProcessedCommands()).as("control for TW-O4: the same Decision on a voting engine IS applied, so the passive result above is the guard and not a dead harness")
                                                  .extracting(TestCommand::value)
                                                  .containsExactly("applied");
        assertThat(machine.committedBatches.get()).isEqualTo(1);
    }

    @Test
    void emptyDecision_advancesTheEnginePhase_withoutTheStateMachineLearningOfIt() {
        var machine = new CountingStateMachine();
        var active = activeEngine(machine);

        active.processDecision(committing(machine, 0, "first"));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(1)));
        active.processDecision(empty(1));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(2)));
        active.processDecision(empty(2));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(3)));

        assertThat(machine.committedBatches.get()).as("TW-O8: the engine is at phase 3 but the state machine was told of one batch only (slot 0); the two V0 slots are invisible to it, so a store's revision cannot be the engine phase")
                                                  .isEqualTo(1);
    }

    @Disabled("W1 (Q-E phase hook): enable when StateMachine.onPhaseCompleted(long) exists and the engine calls it for every completed slot, V0 and empty included")
    @Test
    void stateMachine_isToldTheNewFrontier_afterEveryCompletedSlot() {
        var hook = phaseHook();

        assertThat(hook).as("W1 adds the default method StateMachine.onPhaseCompleted(long nextSlot), called from the tail of advancePhase and of applyRestoredState").isNotNull();
        // With the hook, the test body is: drive the same three decisions as the enabled twin, record the nextSlot values the
        // hook receives, and expect 1, 2, 3 (V0 and empty slots included). Wire a recording StateMachine override here when enabling.
        assertThat(hook.getParameterTypes()).containsExactly(long.class);
    }

    private static Method clearingSetter() {
        return methodOrNull(RabiaEngine.class, "setPassiveClientReady", boolean.class);
    }

    private static Method phaseHook() {
        return methodOrNull(StateMachine.class, "onPhaseCompleted", long.class);
    }

    private static Method methodOrNull(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException _) {
            return null;
        }
    }

    private static void invoke(Method method, Object target, Object... arguments) {
        try {
            method.invoke(target, arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void await(BooleanSupplier condition) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
