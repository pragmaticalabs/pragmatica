/*
 *  Copyright (c) 2023-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.lang;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Promise.continueOn: the returned promise is resolved only by a task on the target executor, so dependent steps
/// chained on it start there, from the pending path and from the already-resolved path alike.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PromiseContinueOnTest {
    private static final String HOP_PREFIX = "continue-on-hop-";
    private static final Cause FAULT_CAUSE = new CoreError.Fault("continueOn fault");
    // A rejected hop must fail the promise, never leave it unresolved: a bounded wait turns "unresolved" into a
    // CoreError.Timeout, which assertRejected reports, instead of a hang.
    private static final TimeSpan REJECTION_BOUND = TimeSpan.timeSpan(5).seconds();

    private ExecutorService hopPool;

    @BeforeEach
    void startPool() {
        hopPool = Executors.newSingleThreadExecutor(Thread.ofPlatform().name(HOP_PREFIX, 0).factory());
    }

    @AfterEach
    void stopPool() {
        hopPool.shutdownNow();
    }

    @Test
    void continueOn_pendingSource_chainedStepStartsOnExecutor_notOnResolverOrEventThread() throws InterruptedException {
        var source = Promise.<Integer>promise();
        var eventThread = new AtomicReference<Thread>();
        var stepThread = new AtomicReference<Thread>();
        var resolverThread = new AtomicReference<Thread>();

        source.onResult(_ -> eventThread.set(Thread.currentThread()));
        var hopped = source.continueOn(hopPool)
                           .map(value -> record(stepThread, value));

        var resolver = Thread.ofPlatform().start(() -> resolveFrom(source, resolverThread));
        resolver.join();

        assertEquals(Result.success(1), hopped.await());
        assertTrue(stepThread.get().getName().startsWith(HOP_PREFIX), "step ran on " + stepThread.get());
        assertNotSame(resolverThread.get(), stepThread.get());
        assertNotSame(eventThread.get(), stepThread.get());
        assertNotSame(Thread.currentThread(), stepThread.get());
    }

    @Test
    void continueOn_resolvedSource_hopIsAlwaysTaken_chainedStepStartsOnExecutor() {
        var gate = new CountDownLatch(1);
        var gated = gatedExecutor(gate);
        var stepThread = new AtomicReference<Thread>();

        var hopped = Promise.success(1)
                            .continueOn(gated);

        assertFalse(hopped.isResolved(), "an already-resolved source must not resolve the hopped promise inline");

        var chained = hopped.map(value -> record(stepThread, value));
        gate.countDown();

        assertEquals(Result.success(1), chained.await());
        assertTrue(stepThread.get().getName().startsWith(HOP_PREFIX), "step ran on " + stepThread.get());
        assertNotSame(Thread.currentThread(), stepThread.get());
    }

    @Test
    void continueOn_noArgForm_chainedStepStartsOnFreshVirtualThread() {
        var source = Promise.<Integer>promise();
        var eventThread = new AtomicReference<Thread>();
        var stepThread = new AtomicReference<Thread>();

        source.onResult(_ -> eventThread.set(Thread.currentThread()));
        var hopped = source.continueOn()
                           .map(value -> record(stepThread, value));

        source.succeed(1);

        assertEquals(Result.success(1), hopped.await());
        assertTrue(stepThread.get().isVirtual(), "step ran on " + stepThread.get());
        assertNotSame(Thread.currentThread(), stepThread.get());
        assertNotSame(eventThread.get(), stepThread.get());
    }

    @Test
    void continueOn_rejectedExecutor_pendingSource_failsReturnedPromise() {
        var source = Promise.<Integer>promise();
        var hopped = source.continueOn(PromiseContinueOnTest::reject);

        source.succeed(1);

        assertRejected(hopped.await(REJECTION_BOUND));
    }

    @Test
    void continueOn_rejectedExecutor_resolvedSource_failsReturnedPromise() {
        assertRejected(Promise.success(1)
                              .continueOn(PromiseContinueOnTest::reject)
                              .await(REJECTION_BOUND));
    }

    @Test
    void continueOn_shutDownExecutor_failsReturnedPromise() {
        hopPool.shutdownNow();

        assertRejected(Promise.success(1)
                              .continueOn(hopPool)
                              .await(REJECTION_BOUND));
    }

    @Test
    void continueOn_failure_passesThroughUnchanged() {
        var result = Promise.<Integer>failure(FAULT_CAUSE)
                            .continueOn(hopPool)
                            .await();

        result.onSuccess(_ -> fail("expected failure"))
              .onFailure(cause -> assertSame(FAULT_CAUSE, cause));
    }

    @Test
    void continueOn_failure_noArgForm_passesThroughUnchanged() {
        var result = Promise.<Integer>failure(FAULT_CAUSE)
                            .continueOn()
                            .await();

        result.onSuccess(_ -> fail("expected failure"))
              .onFailure(cause -> assertSame(FAULT_CAUSE, cause));
    }

    private static void assertRejected(Result<Integer> result) {
        result.onSuccess(_ -> fail("a rejected hop must fail, never deliver the value inline"))
              .onFailure(PromiseContinueOnTest::assertRejectionCause);
    }

    private static void assertRejectionCause(Cause cause) {
        var exception = assertInstanceOf(CoreError.Exception.class, cause);

        assertInstanceOf(RejectedExecutionException.class, exception.cause());
    }

    private static void reject(Runnable task) {
        throw new RejectedExecutionException("continueOn test: rejected");
    }

    private static Integer record(AtomicReference<Thread> holder, Integer value) {
        holder.set(Thread.currentThread());

        return value;
    }

    private static void resolveFrom(Promise<Integer> source, AtomicReference<Thread> resolverThread) {
        resolverThread.set(Thread.currentThread());
        source.succeed(1);
    }

    private Executor gatedExecutor(CountDownLatch gate) {
        return task -> hopPool.execute(() -> runAfter(gate, task));
    }

    private static void runAfter(CountDownLatch gate, Runnable task) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        task.run();
    }
}
