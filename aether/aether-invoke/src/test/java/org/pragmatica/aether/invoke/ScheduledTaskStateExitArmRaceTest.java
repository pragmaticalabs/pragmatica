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
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Row 25 U2 against the STATE-level cancel-all (KVStore dispatcher design §3.5.1 U2: the generation bump must be
/// atomic with the undo). The arm reaction passes its generation checks and its state read, then a state exit
/// (quorum lost: Following/Leading onExit -> cancelAllTimers) runs on another thread before the arm stores its timer.
/// The armed timer must not survive the cancel-all. Deterministic: the arm is parked inside its key's `compute` (the
/// eligibility read), and the exit thread is released only after it either finished (unguarded cancel-all) or parked
/// on that `compute` (the fixed path), observed by a spin with a deadline. Found by the v-2653 verifier (F1).
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ScheduledTaskStateExitArmRaceTest {
    private final ReactExecutor executor = ReactExecutor.reactExecutor("ScheduledTaskRegistry",
                                                                       ReactGate.alwaysOpen(),
                                                                       ReactFailureSink.loggingOnly());
    private final NodeId self = new NodeId("node-race");
    private final ScheduledTaskKey key = ScheduledTaskKey.scheduledTaskKey("cache",
                                                                           Artifact.artifact("org.example:my-slice:1.0.0")
                                                                                   .unwrap(),
                                                                           MethodName.methodName("cleanup").unwrap());
    private final ScheduledTaskRegistry registry = ScheduledTaskRegistry.scheduledTaskRegistry();
    private final AtomicBoolean blockArmOnce = new AtomicBoolean(false);
    private final CountDownLatch armInsideEligibility = new CountDownLatch(1);
    private final CountDownLatch releaseArm = new CountDownLatch(1);
    private final ScheduledTaskManager manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                                                           blockingInvoker(),
                                                                                           self,
                                                                                           _ -> {},
                                                                                           _ -> Option.none(),
                                                                                           new ScheduledTaskManagerTest.TestLeaderManager(self));

    private SliceInvoker blockingInvoker() {
        var stub = new ScheduledTaskManagerTest.StubSliceInvoker(new CopyOnWriteArrayList<>(), Option.none());

        return (SliceInvoker) Proxy.newProxyInstance(SliceInvoker.class.getClassLoader(),
                                                     new Class<?>[]{SliceInvoker.class},
                                                     (proxy, method, args) -> {
                                                         if (method.getName().equals("hasLocalSlice")
                                                             && Thread.currentThread().getName().startsWith("react-")
                                                             && blockArmOnce.compareAndSet(true, false)) {
                                                             armInsideEligibility.countDown();
                                                             releaseArm.await(20, TimeUnit.SECONDS);
                                                         }
                                                         try {
                                                             return method.invoke(stub, args);
                                                         } catch (InvocationTargetException e) {
                                                             throw e.getCause();
                                                         }
                                                     });
    }

    @BeforeEach
    void setUp() {
        manager.installReactExecutor(executor);
        manager.onQuorumStateChange(ClusterStateNotification.active());
    }

    @AfterEach
    void tearDown() {
        releaseArm.countDown();
        manager.stop();
        executor.close();
    }

    @Test
    void stateExitDuringArm_leavesNoTimer() throws Exception {
        blockArmOnce.set(true);
        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(key,
                                                                        ScheduledTaskValue.intervalTask(self,
                                                                                                        "30s",
                                                                                                        ExecutionMode.ALL)),
                                                   Option.none()));
        assertThat(armInsideEligibility.await(10, TimeUnit.SECONDS)).as("arm reaction is inside its eligibility read")
                                                                    .isTrue();
        var exit = new Thread(() -> manager.onQuorumStateChange(ClusterStateNotification.passive()), "state-exit");
        exit.start();
        assertThat(finishedOrBlocked(exit)).as("the state exit finished, or parked on the key's compute").isTrue();
        releaseArm.countDown();
        exit.join(10_000);
        assertThat(exit.isAlive()).as("state exit finished").isFalse();
        var done = new CountDownLatch(1);
        executor.undo("drain", done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(((ScheduledTaskManager.ScheduledTaskManagerAdapter) manager).fsm()
                                                                       .current()).as("the state exit happened")
                                                                                  .isInstanceOf(ScheduledTaskManager.Dormant.class);
        assertThat(manager.activeTimerCount()).as("no timer armed after the state-level cancel-all (Dormant)").isZero();
    }

    private static boolean finishedOrBlocked(Thread thread) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.TERMINATED || thread.getState() == Thread.State.BLOCKED) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }
}
