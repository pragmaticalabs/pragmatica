// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the L2 fix: timers are atomically replaced (with cancellation of any prior future)
/// when a task is re-registered, and `stop()` / quorum-loss cancel all active timers.
///
/// Smaller-blast-radius approach: `replaceTimer` in `TaskOps` ensures `activeTimers.put` cannot
/// orphan a prior `ScheduledFuture` for the same key. The existing `containsKey` guard in
/// `startEligibleTasks` prevents double-scheduling on `onEntry`.
class ScheduledTaskManagerStaleTimerTest {
    private ScheduledTaskRegistry registry;
    private ScheduledTaskManagerTest.StubSliceInvoker stubInvoker;
    private ScheduledTaskManager manager;
    private NodeId self;
    private Artifact artifact;
    private MethodName method;
    private CopyOnWriteArrayList<KVCommand<AetherKey>> stateWrites;
    private ScheduledTaskManagerTest.TestLeaderManager leaderManager;

    @BeforeEach
    void setUp() {
        registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        stubInvoker = new ScheduledTaskManagerTest.StubSliceInvoker(new CopyOnWriteArrayList<>(), Option.none());
        self = new NodeId("node-stale-timer");
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        method = MethodName.methodName("cleanup").unwrap();
        stateWrites = new CopyOnWriteArrayList<>();
        leaderManager = new ScheduledTaskManagerTest.TestLeaderManager(self);
        manager = ScheduledTaskManager.scheduledTaskManager(registry, stubInvoker, self,
                                                            stateWrites::add, _ -> Option.none(), leaderManager);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    private void putTask(String section, String interval, ExecutionMode mode) {
        var key = ScheduledTaskKey.scheduledTaskKey(section, artifact, method);
        var value = ScheduledTaskValue.intervalTask(self, interval, mode);
        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
    }

    private void establishQuorum() {
        manager.onQuorumStateChange(ClusterStateNotification.active());
    }

    private void loseQuorum() {
        manager.onQuorumStateChange(ClusterStateNotification.passive());
    }

    @Test
    void reRegisteringTask_doesNotOrphanPriorTimer() {
        putTask("cache", "30s", ExecutionMode.ALL);
        establishQuorum();
        assertThat(manager.activeTimerCount()).isEqualTo(1);

        // Re-register the SAME key — the registry calls cancelTimer THEN startTimer in
        // handleTaskAdded. Active count must remain at 1; the prior future must have been
        // cancelled (no leaked second timer).
        putTask("cache", "30s", ExecutionMode.ALL);

        assertThat(manager.activeTimerCount())
                .as("re-registering a task must not double-count active timers")
                .isEqualTo(1);
    }

    @Test
    void quorumLoss_cancelsAllTimersOnStateExit() {
        putTask("cache", "30s", ExecutionMode.ALL);
        establishQuorum();
        assertThat(manager.activeTimerCount()).isEqualTo(1);

        loseQuorum();

        assertThat(manager.activeTimerCount())
                .as("Following.onExit cancels all timers on QuorumDisappeared")
                .isZero();
    }

    @Test
    void stop_cancelsAllTimers() {
        putTask("cache", "30s", ExecutionMode.ALL);
        establishQuorum();
        assertThat(manager.activeTimerCount()).isEqualTo(1);

        manager.stop();

        assertThat(manager.activeTimerCount())
                .as("Shutdown path drains all active timers")
                .isZero();
    }

    @Test
    void quorumReEstablished_doesNotDoubleSchedule() {
        // Verifies idempotency of `startEligibleTasks` under repeated entry: after a
        // quorum-loss / quorum-establish cycle, exactly ONE timer per task remains.
        putTask("cache", "30s", ExecutionMode.ALL);
        establishQuorum();
        assertThat(manager.activeTimerCount()).isEqualTo(1);

        loseQuorum();
        assertThat(manager.activeTimerCount()).isZero();

        establishQuorum();

        assertThat(manager.activeTimerCount())
                .as("repeated entry into Following must not orphan or double-schedule timers")
                .isEqualTo(1);
    }
}
