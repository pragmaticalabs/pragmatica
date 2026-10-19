// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1516 — a notification implies ACTIVE. While the engine cannot activate, a sync restore installs the state
/// silently: the synthetic notification replay and the `onStateRestored` hooks must not run, however many sync rounds
/// retry. The fixture is the one that fences a node permanently: the re-persist after the restore fails, so
/// `activate()` is refused on every round (the replay used to run on each of them: 4 rounds, 4 restores, 4 replays).
/// The control runs the identical restore with a succeeding save and sees exactly one replay and one hook call, so
/// the guard is not a mute.
class RabiaNoReplayWhileInactiveTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final Phase PEER_PHASE = Phase.phase(10);
    private static final Phase OWN_PHASE = Phase.phase(5);
    private static final byte[] PEER_SNAPSHOT = "peer".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OWN_SNAPSHOT = "own".getBytes(StandardCharsets.UTF_8);
    private static final Cause DISK_FULL = Causes.cause("no space left on device");
    private static final long TIMEOUT_MILLIS = 5_000;
    private static final int ROUNDS = 4;
    private static final long SETTLE_MILLIS = 300;

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        engines.forEach(engine -> engine.stop().await());
    }

    @Test
    void failedRepersist_repeatedSyncRounds_replayNothingAndRunNoHook() {
        var machine = new CountingStateMachine();
        var hooks = new AtomicInteger();
        var engine = coldStarted(machine, emptyDisk(true), hooks);

        for (var round = 1; round <= ROUNDS; round++) {
            engine.processSyncResponse(cold(PEER_PHASE, PEER_SNAPSHOT));
            var expected = round;

            assertThat(awaitCondition(() -> machine.restores.get() >= expected)).as("precondition: round %d restored the snapshot", round).isTrue();
        }

        assertThat(failureRecorded(engine)).as("precondition: the re-persist failed, so activation is refused").isTrue();
        assertThat(engine.isActive()).isFalse();
        assertThat(effectsWithin(machine, hooks)).as("no notification replay and no post-restore hook from a node that is not ACTIVE").isFalse();
        assertThat(machine.replays.get()).isZero();
        assertThat(hooks.get()).isZero();
    }

    @Test
    void failedRepersist_emptySnapshotAnswer_replaysNothingAndRunsNoHook() {
        var machine = new CountingStateMachine();
        var hooks = new AtomicInteger();
        var engine = coldStarted(machine, emptyDisk(true), hooks);

        engine.processSyncResponse(cold(PEER_PHASE, new byte[0]));

        assertThat(awaitCondition(() -> failureRecorded(engine))).as("precondition: the empty-snapshot branch re-persisted and failed").isTrue();
        assertThat(engine.isActive()).isFalse();
        assertThat(effectsWithin(machine, hooks)).as("no replay and no hook, however long the tail of the restore takes").isFalse();
    }

    @Test
    void failedRepersist_ownSnapshotRestore_replaysNothingAndRunsNoHook() {
        var machine = new CountingStateMachine();
        var hooks = new AtomicInteger();
        var engine = coldStarted(machine, failingSaveAt(OWN_PHASE, OWN_SNAPSHOT), hooks);

        engine.processSyncResponse(cold(Phase.phase(4), PEER_SNAPSHOT));

        assertThat(awaitCondition(() -> failureRecorded(engine))).as("precondition: the own restore ran and its re-persist failed").isTrue();
        assertThat(engine.isActive()).isFalse();
        assertThat(effectsWithin(machine, hooks)).isFalse();
    }

    @Test
    void successfulRestore_replaysOnceAndRunsTheHookOnce_afterActivating() {
        var machine = new CountingStateMachine();
        var hooks = new AtomicInteger();
        var activeAtHook = new AtomicInteger();
        var engine = coldStarted(machine, emptyDisk(false), hooks);

        engine.onStateRestored(() -> activeAtHook.addAndGet(engine.isActive() ? 1 : 0));
        engine.processSyncResponse(cold(PEER_PHASE, PEER_SNAPSHOT));

        assertThat(awaitCondition(engine::isActive)).isTrue();
        assertThat(awaitCondition(() -> hooks.get() == 1)).as("the hook runs once").isTrue();
        assertThat(machine.replays.get()).as("the replay runs once").isEqualTo(1);
        assertThat(activeAtHook.get()).as("the hook saw an ACTIVE engine").isEqualTo(1);
    }

    private RabiaEngine<TestCommand> coldStarted(StateMachine<TestCommand> machine, RabiaPersistence<TestCommand> persistence, AtomicInteger hooks) {
        var network = new TestClusterNetwork();
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, 3),
                                       network,
                                       machine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                       ConsensusMetrics.noop(),
                                       false,
                                       persistence,
                                       timeSpan(50).millis());

        engines.add(engine);
        engine.onStateRestored(hooks::incrementAndGet);
        engine.clusterState(ClusterStateNotification.active());
        assertThat(awaitCondition(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance)))
            .as("the engine started its sync round before responses are delivered")
            .isTrue();

        return engine;
    }

    private static SyncResponse<TestCommand> cold(Phase phase, byte[] snapshot) {
        return new SyncResponse<>(NODE_2, SavedState.savedState(snapshot, phase, List.of()), ResponderState.COLD);
    }

    private static boolean failureRecorded(RabiaEngine<TestCommand> engine) {
        return engine.voterReconfigurationStatus().failure().contains(DISK_FULL.message());
    }

    /// True if a replay or a hook shows up within the settle window. A bounded negative wait: on the unfixed engine the
    /// replay follows the failed activation within microseconds, so a quiet window of this length is not a race.
    private static boolean effectsWithin(CountingStateMachine machine, AtomicInteger hooks) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(SETTLE_MILLIS);

        while (System.nanoTime() < deadline) {
            if (machine.replays.get() > 0 || hooks.get() > 0) {
                return true;
            }

            Thread.onSpinWait();
        }

        return machine.replays.get() > 0 || hooks.get() > 0;
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(TIMEOUT_MILLIS);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }

    /// Counts the two restore effects the engine drives: the silent install and the notification replay.
    private static final class CountingStateMachine extends TestStateMachine {
        final AtomicInteger restores = new AtomicInteger();
        final AtomicInteger replays = new AtomicInteger();

        @Override
        public Result<Unit> restoreCommittedSnapshot(byte[] snapshot, long nextSlot) {
            restores.incrementAndGet();

            return super.restoreCommittedSnapshot(snapshot, nextSlot);
        }

        @Override
        public Unit replayNotifications() {
            replays.incrementAndGet();

            return Unit.unit();
        }
    }

    /// No own history; `save` fails when `failSave` is set, so the first save is the re-persist of the restore.
    private static RabiaPersistence<TestCommand> emptyDisk(boolean failSave) {
        record emptyDisk(boolean failSave) implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine,
                                     Phase lastCommittedPhase,
                                     Collection<StateMachine.Batch<TestCommand>> pendingBatches) {
                return failSave
                       ? DISK_FULL.result()
                       : Result.success(Unit.unit());
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.none();
            }
        }

        return new emptyDisk(failSave);
    }

    /// A durable snapshot whose every `save` fails: a node restarted from disk onto a backup path it cannot write.
    private static RabiaPersistence<TestCommand> failingSaveAt(Phase phase, byte[] snapshot) {
        record failingSave(Phase phase, byte[] snapshot) implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine,
                                     Phase lastCommittedPhase,
                                     Collection<StateMachine.Batch<TestCommand>> pendingBatches) {
                return DISK_FULL.result();
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.some(SavedState.savedState(snapshot, phase, List.of()));
            }
        }

        return new failingSave(phase, snapshot);
    }
}
