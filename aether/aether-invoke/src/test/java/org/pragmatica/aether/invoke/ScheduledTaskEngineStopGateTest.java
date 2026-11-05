// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.invoke;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Exits (ab) timer kind and (ab+) row 25 (KVStore dispatcher design §5, §3.5.1 rows 25/28; D1-react). Row 25 stands in
/// for (ab)'s row 44, whose CDM row moved to the new deployment FSM (know bee940f92f). Replaces the C9 tripwire
/// ScheduledTaskEngineStopTripwireTest, which pinned the inline arm this gate removes.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ScheduledTaskEngineStopGateTest {
    private static final String COMPONENT = "ScheduledTaskRegistry";

    /// Exit (ab) log line (design R5: logged once per component per stop): the drop warns once; closed in tearDown.
    private final LogCapture dropLog = LogCapture.capture(ReactExecutor.class, Level.WARN, false);

    /// The react executor under test, its engine flag (the gate's engine half) and the hold seam (§5 (ab): "hold the
    /// component's react executor, a latch on its next task"). The hold is an UNDO task, so the gate never drops it.
    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final ReactExecutor executor = ReactExecutor.reactExecutor(COMPONENT,
                                                                       ReactGate.reactGate(() -> !engineStopped.get(),
                                                                                           () -> false),
                                                                       ReactFailureSink.loggingOnly());

    private CountDownLatch hold() throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        executor.undo("test-hold", () -> awaitQuietly(started, release));
        assertThat(started.await(10, TimeUnit.SECONDS)).as("hold task started").isTrue();

        return release;
    }

    private void drain() throws InterruptedException {
        var done = new CountDownLatch(1);

        executor.undo("test-drain", done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).as("executor drained").isTrue();
    }

    private static void awaitQuietly(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final NodeId self = new NodeId("node-gate");
    private final ScheduledTaskKey key = ScheduledTaskKey.scheduledTaskKey("cache",
                                                                           Artifact.artifact("org.example:my-slice:1.0.0")
                                                                                   .unwrap(),
                                                                           MethodName.methodName("cleanup").unwrap());
    private final ScheduledTaskRegistry registry = ScheduledTaskRegistry.scheduledTaskRegistry();
    private final ScheduledTaskManagerTest.TestLeaderManager leaderManager = new ScheduledTaskManagerTest.TestLeaderManager(self);
    private final ScheduledTaskManager manager = ScheduledTaskManager.scheduledTaskManager(
        registry,
        new ScheduledTaskManagerTest.StubSliceInvoker(new CopyOnWriteArrayList<>(), Option.none()),
        self,
        _ -> {},
        _ -> Option.none(),
        leaderManager);

    @BeforeEach
    void setUp() {
        manager.installReactExecutor(executor);
        manager.onQuorumStateChange(ClusterStateNotification.active());
    }

    @AfterEach
    void tearDown() {
        manager.stop();
        executor.close();
        dropLog.close();
    }

    @Test
    void taskPut_armQueuedBeforeEngineStop_isDroppedAndCounted() throws InterruptedException {
        var release = hold();

        registry.onScheduledTaskPut(put());
        assertThat(executor.queueDepth()).as("the arm is queued, so the probe reaches the gate").isEqualTo(1);
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(manager.activeTimerCount()).as("no timer armed after the engine stop").isZero();
        assertThat(executor.droppedEngineStopped()).as("dropped count rose by exactly 1").isEqualTo(1);
        assertThat(dropLog.messages()).as("one warn for the first drop, naming the component")
                                      .hasSize(1)
                                      .allMatch(message -> message.contains("Engine stopped: react executor " + COMPONENT
                                                                            + " drops side-effect reactions"));
    }

    @Test
    void taskPut_engineAlive_armsOneTimer_dropsNothing() throws InterruptedException {
        var release = hold();

        registry.onScheduledTaskPut(put());
        assertThat(executor.queueDepth()).isEqualTo(1);
        release.countDown();
        drain();

        assertThat(manager.activeTimerCount()).as("control arm: exactly one timer").isEqualTo(1);
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    /// (ab+) row 25: Put then Remove absorbed before the arm runs. The cancel stays in absorb and bumps the key's
    /// generation (U2), so the arm, run later, sees the bump and arms nothing.
    @Test
    void taskPutThenRemove_beforeArmRuns_leavesNoTimer() throws InterruptedException {
        var release = hold();

        registry.onScheduledTaskPut(put());
        registry.onScheduledTaskRemove(remove());
        release.countDown();
        drain();

        assertThat(manager.activeTimerCount()).as("no armed-after-cancel").isZero();
    }

    /// (ab+) second arm: the engine stops before release. The cancel is not a reaction (it ran in absorb), and the arm is
    /// dropped by the gate: still no timer.
    @Test
    void taskPutThenRemove_engineStoppedBeforeRelease_leavesNoTimer() throws InterruptedException {
        var release = hold();

        registry.onScheduledTaskPut(put());
        registry.onScheduledTaskRemove(remove());
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(manager.activeTimerCount()).isZero();
    }

    /// (ab+) state-level undo: the scheduler leaves its state (quorum lost) after the arm was queued; the exit's
    /// cancel-all bumps every registry key's generation inside its `compute` (v-2653 F1), so the arm, run later, sees
    /// the bump and arms nothing.
    @Test
    void taskPut_stateExitBeforeArmRuns_leavesNoTimer() throws InterruptedException {
        var release = hold();

        registry.onScheduledTaskPut(put());
        manager.onQuorumStateChange(ClusterStateNotification.passive());
        manager.onQuorumStateChange(ClusterStateNotification.active());
        var armedByEntry = manager.activeTimerCount();
        release.countDown();
        drain();

        assertThat(armedByEntry).as("state entry re-arms every eligible task (the row's heal path)").isEqualTo(1);
        assertThat(manager.activeTimerCount()).as("the stale arm adds nothing").isEqualTo(1);
    }

    /// v-2653: a drain stops ALL-mode fires only (a SINGLE task fires on the leader, hosting or draining). A SINGLE arm
    /// queued before the drain still arms when it runs: the drain's cancel invalidates only the keys it cancels.
    @Test
    void singleTaskPut_drainBeforeArmRuns_stillArms() throws InterruptedException {
        leaderManager.setLeader(true);
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.some(self), true));
        assertThat(((ScheduledTaskManager.ScheduledTaskManagerAdapter) manager).fsm()
                                                                       .current()).as("precondition: leading")
                                                                                  .isInstanceOf(ScheduledTaskManager.Leading.class);
        var release = hold();

        registry.onScheduledTaskPut(put(ExecutionMode.SINGLE));
        manager.onDrainInitiated();
        release.countDown();
        drain();

        assertThat(manager.activeTimerCount()).as("the drain does not refuse a SINGLE arm").isEqualTo(1);
    }

    private ValuePut<ScheduledTaskKey, ScheduledTaskValue> put() {
        return put(ExecutionMode.ALL);
    }

    private ValuePut<ScheduledTaskKey, ScheduledTaskValue> put(ExecutionMode mode) {
        return new ValuePut<>(new KVCommand.Put<>(key, ScheduledTaskValue.intervalTask(self, "30s", mode)),
                              Option.none());
    }

    private ValueRemove<ScheduledTaskKey, ScheduledTaskValue> remove() {
        return new ValueRemove<>(new KVCommand.Remove<>(key), Option.none());
    }
}
