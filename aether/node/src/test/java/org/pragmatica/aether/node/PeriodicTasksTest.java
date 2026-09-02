// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The #644 arming contract at unit level. Each test pins one edge of the deferral state machine;
/// the never-started and start-then-stop paths are the two that carry the ticket's defect (a
/// created-but-unstarted node ran all fourteen periodic tasks, and a stop() racing a late
/// cluster-formation resolution could re-arm a torn-down node). The Ember-level contract test
/// (`NodeLifecyclePeriodicArmingForgeTest`) pins the WIRING — that `AetherNode.start()` actually
/// calls [PeriodicTasks#arm] — which no unit test here can see.
class PeriodicTasksTest {
    /// The #644 pin: deferring schedules NOTHING. A thunk invoked at defer time would reintroduce
    /// the assembly-armed defect with one refactor.
    @Test
    void defer_beforeArm_invokesNoThunkAndArmsNothing() {
        var tasks = PeriodicTasks.periodicTasks();
        var invocations = new AtomicInteger();

        tasks.defer(() -> countingFuture(invocations));
        tasks.defer(() -> countingFuture(invocations));

        assertThat(invocations.get()).as("a deferred thunk must not run until arm()")
                                     .isZero();
        assertThat(tasks.armedCount()).isZero();
        assertThat(tasks.deferredCount()).isEqualTo(2);
    }

    @Test
    void arm_invokesEachDeferredThunkOnce_andRetainsTheHandles() {
        var tasks = PeriodicTasks.periodicTasks();
        var invocations = new AtomicInteger();

        tasks.defer(() -> countingFuture(invocations));
        tasks.defer(() -> countingFuture(invocations));
        tasks.arm();

        assertThat(invocations.get()).isEqualTo(2);
        assertThat(tasks.armedCount()).isEqualTo(2);
        assertThat(tasks.deferredCount()).isZero();
    }

    /// arm() is invoked from a promise-success callback; nothing structurally prevents a second
    /// resolution path from calling it again. Double-arming would double every periodic task.
    @Test
    void arm_calledTwice_armsEachThunkOnce() {
        var tasks = PeriodicTasks.periodicTasks();
        var invocations = new AtomicInteger();

        tasks.defer(() -> countingFuture(invocations));
        tasks.arm();
        tasks.arm();

        assertThat(invocations.get()).isEqualTo(1);
        assertThat(tasks.armedCount()).isEqualTo(1);
    }

    /// The stop-races-start window: cluster formation resolves AFTER stop() already tore the node
    /// down. The late arm() must schedule nothing — this is the edge that makes the deferral safe
    /// rather than merely late.
    @Test
    void arm_afterCancel_schedulesNothing() {
        var tasks = PeriodicTasks.periodicTasks();
        var invocations = new AtomicInteger();

        tasks.defer(() -> countingFuture(invocations));
        tasks.cancel();
        tasks.arm();

        assertThat(invocations.get()).as("a cancelled node must never gain work from a late arm()")
                                     .isZero();
        assertThat(tasks.armedCount()).isZero();
    }

    /// The failed-boot-guard path (`cancelArmedWork`): at guard time nothing is scheduled, so a
    /// refused node discards thunks without ever invoking them — strictly safer than the pre-#644
    /// cancel of already-ticking timers.
    @Test
    void cancel_beforeArm_discardsThunksWithoutInvokingThem() {
        var tasks = PeriodicTasks.periodicTasks();
        var invocations = new AtomicInteger();

        tasks.defer(() -> countingFuture(invocations));
        tasks.cancel();

        assertThat(invocations.get()).isZero();
        assertThat(tasks.deferredCount()).isZero();
        assertThat(tasks.armedCount()).isZero();
    }

    /// The stop() path on a started node: every armed handle is cancelled with mayInterrupt=false,
    /// mirroring the prior wholesale cancel (an in-flight tick finishes benignly).
    @Test
    void cancel_afterArm_cancelsEveryArmedTaskWithoutInterrupt() {
        var tasks = PeriodicTasks.periodicTasks();
        var cancelled = new ArrayList<Boolean>();

        tasks.defer(() -> cancelRecordingFuture(cancelled));
        tasks.defer(() -> cancelRecordingFuture(cancelled));
        tasks.arm();
        tasks.cancel();

        assertThat(cancelled).as("both armed tasks must be cancelled, each with mayInterrupt=false")
                             .containsExactly(false, false);
        assertThat(tasks.armedCount()).isZero();
    }

    /// Level semantics on a RUNNING node: a defer landing after arm() schedules immediately —
    /// whatever is deferred on a live node runs on it, so ordering between assembly steps and the
    /// formation promise cannot silently drop a task.
    @Test
    void defer_afterArm_armsImmediately() {
        var tasks = PeriodicTasks.periodicTasks();
        var invocations = new AtomicInteger();

        tasks.arm();
        tasks.defer(() -> countingFuture(invocations));

        assertThat(invocations.get()).isEqualTo(1);
        assertThat(tasks.armedCount()).isEqualTo(1);
    }

    @Test
    void defer_afterCancel_isDiscarded() {
        var tasks = PeriodicTasks.periodicTasks();
        var invocations = new AtomicInteger();

        tasks.cancel();
        tasks.defer(() -> countingFuture(invocations));

        assertThat(invocations.get()).isZero();
        assertThat(tasks.armedCount()).isZero();
        assertThat(tasks.deferredCount()).isZero();
    }

    // ---- fakes ---------------------------------------------------------------------------------

    private static ScheduledFuture<?> countingFuture(AtomicInteger invocations) {
        invocations.incrementAndGet();

        return stubFuture(_ -> {});
    }

    private static ScheduledFuture<?> cancelRecordingFuture(List<Boolean> cancelled) {
        return stubFuture(cancelled::add);
    }

    /// Minimal [ScheduledFuture] stub: only `cancel` matters to [PeriodicTasks]; every other method
    /// failing loudly keeps a future misuse from passing silently.
    private static ScheduledFuture<?> stubFuture(java.util.function.Consumer<Boolean> onCancel) {
        return new ScheduledFuture<Object>() {
            @Override
            public long getDelay(TimeUnit unit) {
                throw new UnsupportedOperationException("not used by PeriodicTasks");
            }

            @Override
            public int compareTo(Delayed other) {
                throw new UnsupportedOperationException("not used by PeriodicTasks");
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                onCancel.accept(mayInterruptIfRunning);

                return true;
            }

            @Override
            public boolean isCancelled() {
                throw new UnsupportedOperationException("not used by PeriodicTasks");
            }

            @Override
            public boolean isDone() {
                throw new UnsupportedOperationException("not used by PeriodicTasks");
            }

            @Override
            public Object get() {
                throw new UnsupportedOperationException("not used by PeriodicTasks");
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException("not used by PeriodicTasks");
            }
        };
    }
}
