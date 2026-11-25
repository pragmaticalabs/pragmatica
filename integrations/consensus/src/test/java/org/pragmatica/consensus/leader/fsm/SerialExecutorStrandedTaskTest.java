/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins the drainer's release re-check (#3605, se04): a task submitted by another thread in the window between the
/// drainer's last empty poll and its release finds a live drainer and returns, so only the re-check after the release
/// runs it. The window holds no user code, so the test opens it deterministically: the executor's queue is replaced by
/// one whose first empty poll runs a submitter thread to completion (joined) before returning null.
class SerialExecutorStrandedTaskTest {
    @Test
    void taskSubmittedDuringDrainerRelease_isNotStranded() throws Exception {
        var executor = SerialExecutor.serialExecutor();
        var ran = new AtomicInteger();
        var lateSubmitted = new AtomicBoolean();
        var queue = new ConcurrentLinkedQueue<Runnable>() {
            private final AtomicBoolean armed = new AtomicBoolean(true);

            @Override
            public Runnable poll() {
                var next = super.poll();

                if (next == null && armed.compareAndSet(true, false)) {
                    submitFromAnotherThread(executor, ran, lateSubmitted);
                }
                return next;
            }
        };

        replaceQueue(executor, queue);

        executor.execute(ran::incrementAndGet);

        assertThat(lateSubmitted).as("the late submitter ran inside the release window").isTrue();
        assertThat(ran.get()).as("the first task and the one submitted during the release both ran").isEqualTo(2);
        assertThat(executor.pendingForTest()).as("nothing stranded in the queue").isZero();
    }

    @Test
    void vmErrorInATask_propagatesToTheCaller_andTheDrainerIsReleased() {
        var executor = SerialExecutor.serialExecutor();
        var ran = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(() -> {
            throw new TestVmError();
        })).isInstanceOf(TestVmError.class);

        assertThat(executor.isConfined()).as("the drainer was released on the propagating exit").isFalse();

        executor.execute(ran::incrementAndGet);

        assertThat(ran.get()).as("a task submitted after the VM error runs: nothing is pinned").isEqualTo(1);
    }

    /// #3605 round-4 ruling 2: a VM error is never deferred. The first task queues a second one, then throws a VM error:
    /// the error reaches the caller at once and the second task has not run; the next submission drains it, in order.
    @Test
    void vmErrorInATask_laterQueuedTasksDoNotRunInThatDrain_theNextSubmissionRunsThem() {
        var executor = SerialExecutor.serialExecutor();
        var order = new ConcurrentLinkedQueue<String>();

        assertThatThrownBy(() -> executor.execute(() -> queueThenFailWithAVmError(executor, order)))
            .isInstanceOf(TestVmError.class);

        assertThat(order).as("the task queued before the VM error did not run in that drain").isEmpty();
        assertThat(executor.pendingForTest()).as("it is still queued").isEqualTo(1);

        executor.execute(() -> order.add("next"));

        assertThat(order).as("the next submission drains the queued task first, then its own")
                         .containsExactly("queued", "next");
    }

    /// The deferral boundary is exactly [VirtualMachineError] (#3605 v3605i row 31): a task that throws an
    /// [AssertionError], an Error that is not a VM error, is logged and absorbed like any other throwable. The caller
    /// returns normally and the task it queued before throwing runs in that same drain.
    @Test
    void nonVmErrorInATask_isAbsorbed_theCallerReturns_theNextQueuedTaskRuns() {
        var executor = SerialExecutor.serialExecutor();
        var order = new ConcurrentLinkedQueue<String>();

        executor.execute(() -> queueThenFailWithAnAssertionError(executor, order));

        assertThat(order).as("the task queued before the AssertionError ran in that drain").containsExactly("queued");
        assertThat(executor.pendingForTest()).as("nothing is left queued").isZero();
    }

    private static void queueThenFailWithAnAssertionError(SerialExecutor executor, ConcurrentLinkedQueue<String> order) {
        executor.execute(() -> order.add("queued"));
        throw new AssertionError("an Error that is not a VirtualMachineError");
    }

    private static void queueThenFailWithAVmError(SerialExecutor executor, ConcurrentLinkedQueue<String> order) {
        executor.execute(() -> order.add("queued"));
        throw new TestVmError();
    }

    private static final class TestVmError extends VirtualMachineError {}

    private static void submitFromAnotherThread(SerialExecutor executor, AtomicInteger ran, AtomicBoolean submitted) {
        var failure = new AtomicReference<Throwable>();
        var submitter = new Thread(() -> submit(executor, ran, submitted, failure));

        submitter.start();
        joinOrFail(submitter);
        assertThat(failure.get()).as("the submitter thread did not fail").isNull();
    }

    private static void submit(SerialExecutor executor, AtomicInteger ran, AtomicBoolean submitted, AtomicReference<Throwable> failure) {
        try {
            executor.execute(ran::incrementAndGet);
            submitted.set(true);
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    private static void joinOrFail(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining the submitter", e);
        }
    }

    private static void replaceQueue(SerialExecutor executor, ConcurrentLinkedQueue<Runnable> queue) throws Exception {
        Field field = SerialExecutor.class.getDeclaredField("queue");

        field.setAccessible(true);
        field.set(executor, queue);
    }
}
