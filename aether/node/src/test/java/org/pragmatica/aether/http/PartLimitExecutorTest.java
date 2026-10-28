// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The bounded blocking executor of the part-limit check: it refuses when its workers and queue are full, runs work on
/// named daemon workers, sizes itself from the processors, and lets idle workers end.
class PartLimitExecutorTest {
    @Test
    void submit_refusesWhenTheWorkersAreBusyAndTheQueueIsFull_andAcceptsAgainOnceThereIsRoom() throws Exception {
        var executor = PartLimitExecutor.partLimitExecutor(1, 1);
        var gate = new CountDownLatch(1);
        var started = new CountDownLatch(1);

        assertThat(executor.submit(() -> blockOn(started, gate)).isSuccess()).as("the worker").isTrue();
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(() -> {}).isSuccess()).as("the one queue place").isTrue();

        var refused = executor.submit(() -> {});

        assertThat(refused.isFailure()).as("worker busy, queue full").isTrue();
        refused.onFailure(cause -> assertThat(cause.message()).isEqualTo("Too many multipart bodies are waiting for their part-limit check"));
        refused.onFailure(cause -> assertThat(cause.source().isPresent()).as("the rejection is kept as the origin").isTrue());
        gate.countDown();
        awaitIdle(executor);
        assertThat(executor.submit(() -> {}).isSuccess()).as("room again").isTrue();
    }

    private static void blockOn(CountDownLatch started, CountDownLatch gate) {
        started.countDown();
        try {
            gate.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitIdle(PartLimitExecutor executor) throws InterruptedException {
        for (int attempt = 0; attempt < 200 && executor.queued() > 0; attempt++) {
            Thread.sleep(25);
        }
    }

    @Test
    void workers_areNamedDaemonThreads() throws Exception {
        var executor = PartLimitExecutor.partLimitExecutor(1, 1);
        var seen = new AtomicReference<Thread>();
        var done = new CountDownLatch(1);

        executor.submit(() -> {
            seen.set(Thread.currentThread());
            done.countDown();
        });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get().getName()).startsWith("app-http-part-limit-");
        assertThat(seen.get().isDaemon()).as("a worker never keeps the JVM alive").isTrue();
    }

    @Test
    void idleWorkers_end() throws Exception {
        var executor = PartLimitExecutor.partLimitExecutor(2, 2, 1);
        var done = new CountDownLatch(1);

        executor.submit(done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        for (int attempt = 0; attempt < 200 && executor.workers() > 0; attempt++) {
            Thread.sleep(50);
        }

        assertThat(executor.workers()).as("a node that takes no multipart upload holds no worker").isZero();
    }

    @Test
    void threadsFor_isHalfTheProcessors_betweenTwoAndEight() {
        assertThat(PartLimitExecutor.threadsFor(1)).isEqualTo(2);
        assertThat(PartLimitExecutor.threadsFor(4)).isEqualTo(2);
        assertThat(PartLimitExecutor.threadsFor(5)).isEqualTo(2);
        assertThat(PartLimitExecutor.threadsFor(6)).isEqualTo(3);
        assertThat(PartLimitExecutor.threadsFor(15)).isEqualTo(7);
        assertThat(PartLimitExecutor.threadsFor(16)).isEqualTo(8);
        assertThat(PartLimitExecutor.threadsFor(17)).isEqualTo(8);
        assertThat(PartLimitExecutor.threadsFor(64)).isEqualTo(8);
    }

    @Test
    void defaultExecutor_hasThatManyWorkers_andFourQueuedChecksForEach() {
        var executor = PartLimitExecutor.partLimitExecutor();
        var threads = PartLimitExecutor.threadsFor(Runtime.getRuntime().availableProcessors());

        assertThat(executor.threads()).isEqualTo(threads);
        assertThat(executor.queueCapacity()).isEqualTo(threads * 4);
        assertThat(PartLimitExecutor.IDLE_SECONDS).isEqualTo(30);
    }
}
