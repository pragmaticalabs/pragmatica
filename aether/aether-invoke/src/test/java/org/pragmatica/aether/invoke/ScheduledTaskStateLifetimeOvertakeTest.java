// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// #1191 at the scheduler: a timer armed by a state's entry never outlives that state's exit, whatever order the hooks of
/// two successive winners run in. The CAS decides which transition wins; the winners' hooks are not serialised, so the
/// first winner's `Following.onEntry` / `Leading.onEntry` can still be inside `startEligibleTasks` when the second winner
/// (`Shutdown`, so `Stopped`) has already run its hooks. The arm that follows must be refused.
///
/// Both races are DRIVEN, not awaited: the first winner is parked inside the eligibility read (`hasLocalSlice`, the one
/// seam every ALL-mode arm passes), the second transition runs, and only then is the first released.
///
/// - `entry...` (RED at 86795a3965): the entry hook arms after `Stopped`'s cancel-all, so a timer outlives the scheduler.
/// - `registry...` (guard, GREEN at 86795a3965): the registry-callback arm shares its key's `compute` with the exit's
///   cancel-all, so the exit either parks on it or runs first and the arm re-reads the state; green there because of
///   that serialisation, and green here because the arm goes through the lifetime of the state it read.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ScheduledTaskStateLifetimeOvertakeTest {
    private static final String PARKED_THREAD_PREFIX_ENTRY = "first-winner";
    private static final String PARKED_THREAD_PREFIX_REACT = "react-";

    private final NodeId self = new NodeId("node-overtake");
    private final ScheduledTaskKey key = ScheduledTaskKey.scheduledTaskKey("cache",
                                                                           Artifact.artifact("org.example:my-slice:1.0.0")
                                                                                   .unwrap(),
                                                                           MethodName.methodName("cleanup").unwrap());
    private final ScheduledTaskRegistry registry = ScheduledTaskRegistry.scheduledTaskRegistry();
    private final ScheduledTaskManagerTest.TestLeaderManager leaders = new ScheduledTaskManagerTest.TestLeaderManager(self);
    private final AtomicReference<String> parkThreadPrefix = new AtomicReference<>();
    private final CountDownLatch parked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ReactExecutor executor = ReactExecutor.reactExecutor("ScheduledTaskRegistry",
                                                                       ReactGate.alwaysOpen(),
                                                                       ReactFailureSink.loggingOnly());
    private final ScheduledTaskManager manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                                                           parkingInvoker(),
                                                                                           self,
                                                                                           _ -> {},
                                                                                           _ -> Option.none(),
                                                                                           leaders);

    private SliceInvoker parkingInvoker() {
        var stub = new ScheduledTaskManagerTest.StubSliceInvoker(new CopyOnWriteArrayList<>(), Option.none());

        return (SliceInvoker) Proxy.newProxyInstance(SliceInvoker.class.getClassLoader(),
                                                     new Class<?>[]{SliceInvoker.class},
                                                     (proxy, method, args) -> {
                                                         parkIfSelected(method.getName());
                                                         try {
                                                             return method.invoke(stub, args);
                                                         } catch (InvocationTargetException e) {
                                                             throw e.getCause();
                                                         }
                                                     });
    }

    private void parkIfSelected(String methodName) throws InterruptedException {
        var prefix = parkThreadPrefix.get();

        if (methodName.equals("hasLocalSlice")
            && prefix != null
            && Thread.currentThread().getName().startsWith(prefix)
            && parkThreadPrefix.compareAndSet(prefix, null)) {
            parked.countDown();
            release.await(20, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        manager.stop();
        executor.close();
    }

    @Test
    void followingEntryOvertakenByStop_armsNothing() throws Exception {
        leaders.setLeader(false);

        assertThat(armEntryAfterStop()).as("Following.onEntry armed after Stopped").isZero();
    }

    @Test
    void leadingEntryOvertakenByStop_armsNothing() throws Exception {
        leaders.setLeader(true);

        assertThat(armEntryAfterStop()).as("Leading.onEntry armed after Stopped").isZero();
    }

    @Test
    void registryArmOvertakenByStop_armsNothing() throws Exception {
        manager.installReactExecutor(executor);
        manager.onQuorumStateChange(ClusterStateNotification.active());
        parkThreadPrefix.set(PARKED_THREAD_PREFIX_REACT);
        putAllModeTask();
        assertThat(parked.await(10, TimeUnit.SECONDS)).as("the arm reaction is inside its eligibility read").isTrue();

        var exit = new Thread(manager::stop, "state-exit");

        exit.start();
        assertThat(finishedOrBlocked(exit)).as("the stop finished, or parked on the key's compute").isTrue();
        release.countDown();
        exit.join(10_000);
        assertThat(exit.isAlive()).as("the stop finished").isFalse();
        var done = new CountDownLatch(1);

        executor.undo("drain", done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(adapter().fsm().current()).as("the stop happened").isInstanceOf(ScheduledTaskManager.Stopped.class);
        assertThat(manager.activeTimerCount()).as("the registry arm left no timer after the stop").isZero();
    }

    /// The first winner enters Following/Leading on `first-winner` and parks before it arms; the stop wins next on this
    /// thread and completes; only then does the entry hook arm. Returns the timers alive at the end.
    private int armEntryAfterStop() throws Exception {
        putAllModeTask();
        parkThreadPrefix.set(PARKED_THREAD_PREFIX_ENTRY);
        var first = new Thread(() -> manager.onQuorumStateChange(ClusterStateNotification.active()), PARKED_THREAD_PREFIX_ENTRY);

        first.start();
        assertThat(parked.await(10, TimeUnit.SECONDS)).as("precondition: the entry hook is inside its eligibility read").isTrue();
        assertThat(adapter().fsm().current()).as("precondition: the first winner's CAS won")
                                             .isNotInstanceOf(ScheduledTaskManager.Dormant.class);
        manager.stop();
        assertThat(adapter().fsm().current()).as("precondition: the second winner has completed")
                                             .isInstanceOf(ScheduledTaskManager.Stopped.class);
        assertThat(manager.activeTimerCount()).as("precondition: the first winner's hook has not armed yet").isZero();
        release.countDown();
        first.join(10_000);
        assertThat(first.isAlive()).as("the first winner's hook finished").isFalse();

        return manager.activeTimerCount();
    }

    private void putAllModeTask() {
        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(key,
                                                                        ScheduledTaskValue.intervalTask(self,
                                                                                                        "30s",
                                                                                                        ExecutionMode.ALL)),
                                                   Option.none()));
    }

    private ScheduledTaskManager.ScheduledTaskManagerAdapter adapter() {
        return (ScheduledTaskManager.ScheduledTaskManagerAdapter) manager;
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
