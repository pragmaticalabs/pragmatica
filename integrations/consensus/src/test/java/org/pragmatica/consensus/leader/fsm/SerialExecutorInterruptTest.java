/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the drainer's interrupt policy (#3605 ruling): the interrupt status is cleared between tasks, so an interrupt one
/// task raises reaches neither the next task nor the submitting thread after the drain. Everything runs synchronously on
/// the test thread: the outer task queues both inner tasks, and the same drain runs them in order.
class SerialExecutorInterruptTest {
    @Test
    void interruptRaisedByATask_reachesNeitherTheNextTaskNorTheSubmitter() {
        var executor = SerialExecutor.serialExecutor();
        var nextTaskSawInterrupt = new AtomicReference<Boolean>();

        try {
            executor.execute(() -> queueInterrupterThenProbe(executor, nextTaskSawInterrupt));

            assertThat(nextTaskSawInterrupt.get()).as("the task after the interrupting one ran with a clear status")
                                                  .isFalse();
            assertThat(Thread.currentThread().isInterrupted()).as("the submitter's status is clear after the drain")
                                                              .isFalse();
        } finally {
            Thread.interrupted();  // never leave the JUnit thread interrupted, whatever the verdict
        }
    }

    private static void queueInterrupterThenProbe(SerialExecutor executor, AtomicReference<Boolean> sawInterrupt) {
        executor.execute(() -> Thread.currentThread().interrupt());
        executor.execute(() -> sawInterrupt.set(Thread.currentThread().isInterrupted()));
    }
}
