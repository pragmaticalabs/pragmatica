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

/// #1191, the third arm path of the scheduler: a cron fire re-arms its next fire from the callback that runs when the
/// invocation settles, long after the state that armed it may be gone. The re-arm goes through the lifetime the fire was
/// scheduled on (the one its body received), so a fire that settles after its state ended arms nothing, and it cannot reach
/// the lifetime of the state that is current by then.
///
/// GUARD, not red-at-base: at 86795a3965 the re-arm was gated only by `activeTimers.containsKey(key)`, which the state
/// exit's cancel-all had cleared, so the sequence below was already green there; the check and the arm were two steps,
/// and this test pins the one that closes the gap now. It cannot run at that commit (the body's lifetime is the
/// signature this change introduces), so the mutant "re-arm through the scheduler's current lifetime" is its proof.
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
    void cronFireSettlingOnAnEndedLifetime_rearmsNothing_andLeavesTheKeysTimerAlone() throws Exception {
        var ctx = ((ScheduledTaskManager.ScheduledTaskManagerAdapter) manager).ctx();
        var current = ctx.fsm.entered().lifetime();
        var keysTimer = current.schedule(_ -> {}, HOUR);
        var endedLifetime = OwnerLifetime.ownerLifetime();
        var task = new ScheduledTaskRegistry.ScheduledTask("cache", artifact, method, self, "", "0 * * * *", ExecutionMode.ALL, false);
        var cron = CronExpression.parse(task.cron()).unwrap();

        endedLifetime.end();
        ctx.activeTimers.put(key, keysTimer);
        ScheduledTaskManager.TaskOps.executeCronTask(ctx, key, task, cron, endedLifetime);
        awaitFireSettled(ctx);
        // The settle callback runs the re-arm right after the release, on the same thread; a mutant that re-arms replaces the
        // key's timer within microseconds of it, and this window is far longer than that.
        Thread.sleep(300);

        assertThat(ctx.activeTimers.get(key)).as("the refused re-arm left the key's armed timer in place").isSameAs(keysTimer);
        assertThat(keysTimer.isCancelled()).as("and did not cancel it as a replaced prior").isFalse();
        assertThat(current.size()).as("the current state's lifetime holds only the timer it had").isEqualTo(1);
    }

    private static void awaitFireSettled(ScheduledTaskManager.Context ctx) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline && !ctx.inFlight.isEmpty()) {
            Thread.sleep(10);
        }
        assertThat(ctx.inFlight).as("the cron fire settled and released its claim").isEmpty();
    }
}
