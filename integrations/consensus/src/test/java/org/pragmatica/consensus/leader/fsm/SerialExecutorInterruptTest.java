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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins the drainer's interrupt policy (#3605 ruling): a submitter that becomes the drainer takes its own interrupt status
/// at entry and re-asserts it on release (also when a task throws); between tasks the status is cleared, so an interrupt a
/// task raises reaches neither later tasks nor the caller. Everything runs synchronously on the test thread: the outer task
/// queues the inner tasks, and the same drain runs them in order.
class SerialExecutorInterruptTest {
    @Test
    void interruptRaisedByATask_doesNotReachTheNextTask() {
        var executor = SerialExecutor.serialExecutor();
        var nextTaskSawInterrupt = new AtomicReference<Boolean>();

        try {
            executor.execute(() -> queueInterrupterThenProbe(executor, nextTaskSawInterrupt));

            assertThat(nextTaskSawInterrupt.get()).as("the task after the interrupting one ran with a clear status")
                                                  .isFalse();
        } finally {
            Thread.interrupted();  // never leave the JUnit thread interrupted, whatever the verdict
        }
    }

    @Test
    void interruptRaisedByATask_doesNotReachTheSubmittingCaller() {
        var executor = SerialExecutor.serialExecutor();

        try {
            executor.execute(() -> Thread.currentThread().interrupt());

            assertThat(Thread.currentThread().isInterrupted()).as("the caller's status is clear after the drain")
                                                              .isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void callerInterruptedBeforeSubmit_isStillInterruptedAfterSubmitReturns() {
        var executor = SerialExecutor.serialExecutor();
        var taskSawInterrupt = new AtomicReference<Boolean>();

        try {
            Thread.currentThread().interrupt();
            executor.execute(() -> taskSawInterrupt.set(Thread.currentThread().isInterrupted()));

            assertThat(taskSawInterrupt.get()).as("the caller's status is taken at entry, the task runs with a clear one")
                                              .isFalse();
            assertThat(Thread.currentThread().isInterrupted()).as("the caller returns still interrupted")
                                                              .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void callerInterruptedBeforeSubmit_isStillInterruptedWhenItsTaskThrows() {
        var executor = SerialExecutor.serialExecutor();

        try {
            Thread.currentThread().interrupt();
            executor.execute(() -> {
                throw new IllegalStateException("task failed");
            });

            assertThat(Thread.currentThread().isInterrupted()).as("an absorbed task failure keeps the caller's status")
                                                              .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void callerInterruptedBeforeSubmit_isStillInterruptedWhenItsTaskThrowsAVmError() {
        var executor = SerialExecutor.serialExecutor();

        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> executor.execute(() -> {
                throw new InternalError("vm failed");
            })).isInstanceOf(InternalError.class);
            assertThat(Thread.currentThread().isInterrupted()).as("a propagating VM error still releases the status")
                                                              .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static void queueInterrupterThenProbe(SerialExecutor executor, AtomicReference<Boolean> sawInterrupt) {
        executor.execute(() -> Thread.currentThread().interrupt());
        executor.execute(() -> sawInterrupt.set(Thread.currentThread().isInterrupted()));
    }
}
