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
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// Engine-side tripwires TW-O3, TW-O4 and the engine half of TW-O8 of the worker-replica-feed design (W-OBS).
///
/// - TW-O3 (flipped by W4, the cutover): the passive-client flag is set AND cleared by `setPassiveClientReady(boolean)`, driven from the one
///   readiness computation, so a worker that loses its feed stops reporting ready. The latch `authorizePassiveClient()` (set once, never
///   cleared) is deleted.
/// - TW-O4: a passive engine fed a `Decision` or a quorum notification applies nothing and stays out of every
///   serving state. The replica feed applies outside the engine (W3), so this must remain true; it fails if the
///   apply path is ever routed through the engine on a worker. There is no real-assertion twin: the design
///   asserts the same thing.
/// - TW-O8 (engine half, flipped by W1): `StateMachine.onPhaseCompleted(long nextSlot)` is called after every completed slot, V0 and
///   empty ones included, and at the tail of a state restore; the `KVStore` half is `KVStoreEffectReplayTest`.
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
        final List<Long> phaseFrontiers = new CopyOnWriteArrayList<>();
        volatile boolean failHook;

        @Override
        public Unit onPhaseCompleted(long nextSlot) {
            phaseFrontiers.add(nextSlot);
            if (failHook) {
                throw new IllegalStateException("hook failure");
            }

            return Unit.unit();
        }

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
        return passiveEngine(machine, true);
    }

    private RabiaEngine<TestCommand> passiveEngine(StateMachine<TestCommand> machine, boolean gated) {
        var passive = engine(WORKER, machine, gated);

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

    /// TW-O3. The only setter: it sets and it clears (a quorum-lost notification and a decision change nothing). Mutation: `passiveClientReady =
    /// ready` without `passiveClient &&` (a non-passive engine reports ready), or never clear.
    @Test
    void setPassiveClientReady_setsAndClears_andWritesNoEngineState() {
        var passive = passiveEngine(new TestStateMachine());
        var coreEngine = engine(CORE_1, new TestStateMachine(), false);

        passive.setPassiveClientReady(true);
        assertThat(passive.isPassiveClientReady()).isTrue();
        passive.setPassiveClientReady(false);
        assertThat(passive.isPassiveClientReady()).as("unlike the latch, the seam clears").isFalse();
        coreEngine.setPassiveClientReady(true);
        assertThat(coreEngine.isPassiveClientReady()).as("ignored on an engine that is not a passive client").isFalse();
        assertThat(passive.isActive() || passive.isPaused() || passive.isObserving()).as("no engine state moved").isFalse();
        assertThat(passive.currentPhaseForTesting()).isEqualTo(Phase.ZERO);
    }

    /// Mutation: fire the listeners on an engine that is not a passive client; or never fire them.
    @Test
    void completePassiveRestore_firesThePostRestoreListeners_onlyOnAPassiveClient() {
        var passive = passiveEngine(new TestStateMachine());
        var coreEngine = engine(CORE_1, new TestStateMachine(), false);
        var passiveFired = new AtomicInteger();
        var coreFired = new AtomicInteger();

        passive.onStateRestored(passiveFired::incrementAndGet);
        coreEngine.onStateRestored(coreFired::incrementAndGet);
        passive.completePassiveRestore();
        passive.completePassiveRestore();
        coreEngine.completePassiveRestore();

        assertThat(passiveFired.get()).as("one run per install").isEqualTo(2);
        assertThat(coreFired.get()).isZero();
        assertThat(passive.isActive() || passive.isPaused() || passive.isObserving()).as("no engine state moved").isFalse();
    }

    /// TW-O3. The latch is gone: the setter is the only way to make a passive engine report ready. Mutation: restore `authorizePassiveClient`.
    @Test
    void isPassiveClientReady_isFalse_whenReadinessFails_andTheLatchIsGone() {
        var passive = passiveEngine(new TestStateMachine());

        assertThat(methodOrNull(RabiaEngine.class, "authorizePassiveClient")).as("W4 deleted the latch").isNull();
        passive.setPassiveClientReady(true);
        passive.clusterState(ClusterStateNotification.passive());
        passive.settleForTesting().await();
        passive.processDecision(empty(0));
        passive.settleForTesting().await();
        assertThat(passive.isPassiveClientReady()).as("a quorum-lost notification and a decision change nothing").isTrue();
        passive.setPassiveClientReady(false);
        assertThat(passive.isPassiveClientReady()).as("readiness failed, so the engine reports the worker as not ready").isFalse();
    }

    /// Both activation modes: a gated engine would park an ACTIVE quorum notification on its own, so the ungated run is the one that
    /// shows the notification is ignored because the engine is passive, not because it is gated.
    @Test
    void passiveEngine_appliesNothingAndStaysStopped_whenFedDecisionsAndQuorumNotifications() {
        assertPassiveEngineStaysInert(true);
        assertPassiveEngineStaysInert(false);
    }

    private void assertPassiveEngineStaysInert(boolean gated) {
        var machine = new CountingStateMachine();
        var passive = passiveEngine(machine, gated);
        var mode = gated ? "gated" : "ungated";

        passive.clusterState(ClusterStateNotification.active());
        passive.processDecision(committing(machine, 0, "never applied"));
        passive.processDecision(empty(1));
        passive.settleForTesting().await();
        passive.settleForTesting().await();

        assertThat(machine.getProcessedCommands()).as("TW-O4 (%s): a passive engine applies no command", mode).isEmpty();
        assertThat(machine.committedBatches.get()).as("(%s) and commits no batch", mode).isZero();
        assertThat(passive.isActive()).as("(%s)", mode).isFalse();
        assertThat(passive.isPaused()).as("(%s)", mode).isFalse();
        assertThat(passive.isObserving()).as("(%s)", mode).isFalse();
        assertThat(passive.currentPhaseForTesting()).as("(%s) and never advances its phase", mode).isEqualTo(Phase.ZERO);
        assertThat(passive.bufferedDecisionCountForTesting()).as("(%s) nor buffers a decision for a later replay: the passive engine never reaches the apply path at all", mode).isZero();
        assertThat(passive.isPendingCatchUp()).as("(%s) nor starts a sync (a quorum notification would put a voting engine in Syncing)", mode).isFalse();
        assertThat(network.getMessages()).as("(%s) and sends nothing", mode).isEmpty();
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
    void everyCompletedSlot_reachesTheStateMachineAsAFrontier_V0AndEmptyIncluded() {
        var machine = new CountingStateMachine();
        var active = activeEngine(machine);

        active.processDecision(committing(machine, 0, "first"));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(1)));
        active.processDecision(empty(1));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(2)));
        active.processDecision(empty(2));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(3)));
        await(() -> machine.phaseFrontiers.contains(3L));

        assertThat(machine.committedBatches.get()).as("TW-O8: processCommitted is still told of the one batch only").isEqualTo(1);
        assertThat(machine.phaseFrontiers).as("TW-O8 / NT-O48: the state machine is told 1, 2, 3, the two V0 slots included (mutation: drop the call in advancePhase)")
                                          .containsSequence(1L, 2L, 3L);
    }

    /// A throwing hook must not wedge the engine: the next phase is still started for the batch this node has pending. Mutation: call the hook in
    /// `advancePhase` without `runIsolated`, and no phase-1 proposal is ever made.
    @Test
    void aThrowingHook_doesNotStopTheEngineStartingTheNextPhase() {
        var machine = new CountingStateMachine();
        var active = activeEngine(machine);

        machine.failHook = true;
        active.apply(List.of(new TestCommand("mine")));
        await(() -> network.getMessages().stream().anyMatch(message -> message instanceof RabiaProtocolMessage.Synchronous.Propose<?> propose && propose.phase().equals(Phase.ZERO)));
        active.processDecision(committing(machine, 0, "someone else's"));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(1)));
        await(() -> machine.phaseFrontiers.contains(1L));

        assertThat(machine.phaseFrontiers).as("the hook ran and threw").contains(1L);
        await(() -> network.getMessages().stream().anyMatch(message -> message instanceof RabiaProtocolMessage.Synchronous.Propose<?> propose && propose.phase().equals(Phase.phase(1))));
    }

    /// One hook call per completed slot (plus the one at the restore), across a pause that applies a decision and across a decision buffered
    /// while the engine was still syncing.
    @Test
    void hookCalls_equalTheCompletedSlots_acrossAPausedResumeAndABufferedDrain() {
        var machine = new CountingStateMachine();
        var active = activeEngine(machine);

        active.processDecision(committing(machine, 0, "first"));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(1)));
        active.clusterState(ClusterStateNotification.passive());
        await(active::isPaused);
        active.processDecision(empty(1));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(2)));
        active.clusterState(ClusterStateNotification.active());
        await(active::isActive);
        active.processDecision(empty(2));
        await(() -> active.currentPhaseForTesting().equals(Phase.phase(3)));
        await(() -> machine.phaseFrontiers.contains(3L));

        assertThat(machine.phaseFrontiers).as("the restore, then slots 0, 1 (applied while Paused) and 2, each once").containsExactly(0L, 1L, 2L, 3L);

        var buffering = new CountingStateMachine();
        var syncing = engine(CORE_1, buffering, false);

        syncing.clusterState(ClusterStateNotification.active());
        await(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance));
        syncing.processDecision(committing(buffering, 0, "early"));
        syncing.processSyncResponse(response(CORE_2));
        syncing.processSyncResponse(response(CORE_3));
        await(() -> buffering.phaseFrontiers.contains(1L));

        assertThat(buffering.phaseFrontiers).as("the decision buffered while Syncing is reported when it is drained, once").containsExactly(0L, 1L);
        assertThat(buffering.getProcessedCommands()).extracting(TestCommand::value).containsExactly("early");
    }

    @Test
    void aStateRestore_reportsItsPhaseToTheStateMachine_evenWithAnEmptySnapshot() {
        var machine = new CountingStateMachine();
        var active = engine(CORE_1, machine, false);

        active.clusterState(ClusterStateNotification.active());
        await(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance));
        active.processSyncResponse(new SyncResponse<>(CORE_2, SavedState.savedState(new byte[0], Phase.phase(5), List.of()), ResponderState.COLD));
        active.processSyncResponse(new SyncResponse<>(CORE_3, SavedState.savedState(new byte[0], Phase.phase(5), List.of()), ResponderState.COLD));
        await(active::isActive);
        await(() -> machine.phaseFrontiers.contains(5L));

        assertThat(machine.phaseFrontiers).as("NT-O48 (mutation: drop the applyRestoredState call): the restored phase is reported before any decision").contains(5L);
    }

    /// A throwing hook at a state restore must not wedge the engine either: the restore still completes and the engine becomes active. Mutation:
    /// call the hook in `applyRestoredState` without `runIsolated`, and the engine never becomes active.
    @Test
    void aThrowingHookAtARestore_doesNotStopTheEngineBecomingActive() {
        var machine = new CountingStateMachine();
        var active = engine(CORE_1, machine, false);

        machine.failHook = true;
        active.clusterState(ClusterStateNotification.active());
        await(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance));
        active.processSyncResponse(new SyncResponse<>(CORE_2, SavedState.savedState(new byte[0], Phase.phase(5), List.of()), ResponderState.COLD));
        active.processSyncResponse(new SyncResponse<>(CORE_3, SavedState.savedState(new byte[0], Phase.phase(5), List.of()), ResponderState.COLD));
        await(active::isActive);
        await(() -> machine.phaseFrontiers.contains(5L));

        assertThat(machine.phaseFrontiers).as("the hook ran at the restore and threw").contains(5L);
    }

    /// An observer applies decisions through the same `advancePhase`, and returns from it in the observer branch: the hook sits before that
    /// branch, so an observer's completed slots are reported too. Mutation: move the hook after the observer branch, and the observer reports
    /// only its restore.
    @Test
    void anObserversCompletedSlot_reachesTheStateMachineAsAFrontier() {
        var nodeA = nodeId("node-1").unwrap();
        var nodeB = nodeId("node-2").unwrap();
        var nodeC = nodeId("node-3").unwrap();
        var machine = new CountingStateMachine();
        var observer = new RabiaEngine<>(new TestTopologyManager(nodeA, 5),
                                         network,
                                         machine,
                                         ProtocolConfig.consensusConfig(org.pragmatica.lang.io.TimeSpan.timeSpan(60).seconds(),
                                                                        org.pragmatica.lang.io.TimeSpan.timeSpan(60).seconds()),
                                         ConsensusMetrics.noop(),
                                         false);

        engines.add(observer);
        assertThat(observer.initializeVoters(new VoterConfiguration(0, new ClusterConfig(List.of(nodeA, nodeB, nodeC)))).isSuccess()).isTrue();
        observer.authorizeObservation();
        observer.clusterState(ClusterStateNotification.active());
        observer.processSyncResponse(new SyncResponse<>(nodeB, SavedState.empty(), ResponderState.COLD));
        observer.processSyncResponse(new SyncResponse<>(nodeC, SavedState.empty(), ResponderState.COLD));
        await(observer::isObserving);
        observer.processDecision(new Decision<>(nodeB, Phase.ZERO, StateValue.V1, Batch.create(machine.serializer(), List.of(new TestCommand("observed")))));
        await(() -> observer.currentPhaseForTesting().equals(Phase.phase(1)));
        await(() -> machine.phaseFrontiers.contains(1L));

        assertThat(machine.getProcessedCommands()).as("control: the observer applied the decision").extracting(TestCommand::value).containsExactly("observed");
        assertThat(observer.isObserving()).as("control: it took the observer branch").isTrue();
        assertThat(machine.phaseFrontiers).as("the observer's completed slot 0 is reported as frontier 1").contains(1L);
    }

    private static Method methodOrNull(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException _) {
            return null;
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
