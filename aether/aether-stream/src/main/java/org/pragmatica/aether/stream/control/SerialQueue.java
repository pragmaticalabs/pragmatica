// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// One partition's serial queue (#2590 P1, N1): tasks run one at a time, in submission order, on a drainer thread, never
/// on the submitter's. A task is synchronous and must not block on anything that waits for this queue; a task that has
/// to wait (the fsync, the barrier, a tier read) is split, and its continuation is submitted again. Submitting never
/// blocks, so a replication or notifier thread may submit.
final class SerialQueue {
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    <T> Promise<T> submit(Supplier<Result<T>> task) {
        Promise<T> promise = Promise.promise();

        tasks.add(() -> promise.resolve(contained(task)));
        startDrainer();

        return promise;
    }

    /// Recovery: design-out of a wedged queue -- a task returns its failures, so one that throws is a defect; it fails its
    /// own promise with the throwable as the cause and the drainer goes on to the next task. A `VirtualMachineError` still
    /// propagates ([Result#lift] rethrows it).
    private static <T> Result<T> contained(Supplier<Result<T>> task) {
        return Result.lift(Causes::fromThrowable, task::get).flatMap(result -> result);
    }

    @Contract
    private void startDrainer() {
        if (draining.compareAndSet(false, true)) {
            Thread.ofVirtual().name("control-log-queue").start(this::drain);
        }
    }

    /// Runs every queued task, then hands the queue back. A task submitted after the last poll and before the flag
    /// cleared is picked up by the re-check, so none is stranded.
    @Contract
    private void drain() {
        for (var next = tasks.poll(); next != null; next = tasks.poll()) {
            next.run();
        }

        draining.set(false);
        if (!tasks.isEmpty()) {
            startDrainer();
        }
    }
}
