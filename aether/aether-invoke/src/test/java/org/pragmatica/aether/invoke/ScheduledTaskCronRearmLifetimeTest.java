// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.OwnerLifetime;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// #1191, the third arm path of the scheduler: a cron fire re-arms its next fire from inside the timer body or from the
/// callback that runs when the invocation settles, long after the state that armed it may be gone. Every such re-arm goes
/// through the lifetime the fire was scheduled on (the one its body received), so a fire that runs after its state ended
/// arms nothing, and it cannot reach the lifetime of the state that is current by then.
///
/// The test takes the skip route (the slice is no longer hosted, so the fire is skipped and re-armed at once, on the test
/// thread, with nothing to wait for); the settle route ends in the same `registerCronFire` line.
///
/// GUARD, not red-at-base: at 86795a3965 the re-arm was gated only by the key being present in `activeTimers`, which the
/// state exit's cancel-all had cleared, and the skip route re-armed with no check at all. It cannot run at that commit (the
/// body's lifetime is the signature this change introduces), so the mutant "re-arm through the scheduler's current
/// lifetime" is its proof.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ScheduledTaskCronRearmLifetimeTest {
    private static final TimeSpan HOUR = TimeSpan.timeSpan(1).hours();

    private final NodeId self = new NodeId("node-cron-rearm");
    private final Artifact artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
    private final MethodName method = MethodName.methodName("cleanup").unwrap();
    private final ScheduledTaskKey key = ScheduledTaskKey.scheduledTaskKey("cache", artifact, method);
    private final ScheduledTaskManagerTest.StubSliceInvoker invoker = new ScheduledTaskManagerTest.StubSliceInvoker(new CopyOnWriteArrayList<>(),
                                                                                                                    Option.none());
    private final ScheduledTaskManager manager = ScheduledTaskManager.scheduledTaskManager(ScheduledTaskRegistry.scheduledTaskRegistry(),
                                                                                           invoker,
                                                                                           self,
                                                                                           _ -> {},
                                                                                           _ -> Option.none(),
                                                                                           new ScheduledTaskManagerTest.TestLeaderManager(self));

    @BeforeEach
    void setUp() {
        manager.onQuorumStateChange(ClusterStateNotification.active());
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    @Test
    void cronFireOnAnEndedLifetime_rearmsNothing_andLeavesTheKeysTimerAlone() {
        var ctx = ((ScheduledTaskManager.ScheduledTaskManagerAdapter) manager).ctx();
        var current = ctx.fsm.entered().lifetime();
        var keysTimer = current.schedule(_ -> {}, HOUR);
        var endedLifetime = OwnerLifetime.ownerLifetime();
        var task = new ScheduledTaskRegistry.ScheduledTask("cache", artifact, method, self, "", "0 * * * *", ExecutionMode.ALL, false);
        var cron = CronExpression.parse(task.cron()).unwrap();

        endedLifetime.end();
        invoker.setHosted(artifact, false);
        ctx.activeTimers.put(key, keysTimer);
        ScheduledTaskManager.TaskOps.executeCronTask(ctx, key, task, cron, endedLifetime);

        assertThat(ctx.activeTimers.get(key)).as("the refused re-arm left the key's armed timer in place").isSameAs(keysTimer);
        assertThat(keysTimer.isCancelled()).as("and did not cancel it as a replaced prior").isFalse();
        assertThat(current.size()).as("the current state's lifetime holds only the timer it had").isEqualTo(1);
    }

    /// The timer map's two writers, driven directly: a store cancels the live timer it replaces, and a per-key cancel
    /// cancels the registration it removes, so neither leaves a live timer nothing refers to.
    @Test
    void replaceTimer_cancelsTheLivePrior_andCancelTimer_cancelsTheRegistrationItRemoves() {
        var ctx = ((ScheduledTaskManager.ScheduledTaskManagerAdapter) manager).ctx();
        var current = ctx.fsm.entered().lifetime();
        var prior = current.schedule(_ -> {}, HOUR);
        var next = current.schedule(_ -> {}, HOUR);

        ctx.activeTimers.put(key, prior);
        ScheduledTaskManager.TaskOps.replaceTimer(ctx, key, next);

        assertThat(prior.isCancelled()).as("the replaced timer was cancelled").isTrue();
        assertThat(ctx.activeTimers.get(key)).as("the new timer is the key's").isSameAs(next);
        assertThat(next.isCancelled()).isFalse();

        ScheduledTaskManager.TaskOps.cancelTimer(ctx, key);

        assertThat(next.isCancelled()).as("a per-key cancel cancels the registration it removes").isTrue();
        assertThat(ctx.activeTimers).as("and removes it").doesNotContainKey(key);
    }
}
