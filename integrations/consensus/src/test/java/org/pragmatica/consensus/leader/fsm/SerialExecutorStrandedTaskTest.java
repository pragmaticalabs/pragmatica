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
