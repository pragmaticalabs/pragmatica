// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// The bounded blocking executor that runs the multipart part-limit check, so the scan over a body that may be hundreds of
/// MiB never holds an event loop.
///
/// A fixed number of threads takes work from a queue of fixed length; when both are full, [#submit] refuses at once and
/// the caller answers 503, so a burst of large uploads cannot queue unbounded work or unbounded bodies behind the scan.
/// Idle threads end after [#IDLE_SECONDS], so a node that takes no multipart upload holds none.
final class PartLimitExecutor {
    static final long IDLE_SECONDS = 30;
    private static final String REFUSED = "Too many multipart bodies are waiting for their part-limit check";
    private static final int MAX_THREADS = 8;
    private static final int QUEUE_PER_THREAD = 4;

    private final ThreadPoolExecutor executor;

    private PartLimitExecutor(int threads, int queueLength) {
        var number = new AtomicInteger();
        ThreadFactory factory = task -> daemon(task, number.incrementAndGet());

        this.executor = new ThreadPoolExecutor(threads,
                                               threads,
                                               IDLE_SECONDS,
                                               TimeUnit.SECONDS,
                                               new ArrayBlockingQueue<>(queueLength),
                                               factory);
        executor.allowCoreThreadTimeOut(true);
    }

    /// Half the processors, at least 2 and at most 8, with four queued checks for each.
    static PartLimitExecutor partLimitExecutor() {
        var threads = Math.max(2,
                               Math.min(MAX_THREADS,
                                        Runtime.getRuntime()
                                               .availableProcessors() / 2));

        return partLimitExecutor(threads, threads * QUEUE_PER_THREAD);
    }

    static PartLimitExecutor partLimitExecutor(int threads, int queueLength) {
        return new PartLimitExecutor(threads, queueLength);
    }

    /// Runs `task` on a worker, or fails when every worker is busy and the queue is full.
    Result<Unit> submit(Runnable task) {
        return Result.lift(PartLimitExecutor::refusal, () -> executor.execute(task));
    }

    /// Test seam: stops the workers.
    void shutdownNow() {
        executor.shutdownNow();
    }

    private static Cause refusal(Throwable rejected) {
        return Causes.cause(REFUSED, Option.some(Causes.fromThrowable(rejected)));
    }

    private static Thread daemon(Runnable task, int number) {
        var thread = new Thread(task, "app-http-part-limit-" + number);

        thread.setDaemon(true);

        return thread;
    }
}
