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

import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #914 — `await()` / `await(TimeSpan)` parked in a bare loop that never consulted the interrupt
/// status. `LockSupport.park` returns immediately, without clearing the flag, whenever the caller is
/// interrupted, so an interrupted waiter re-parked forever at 100% CPU: the unbounded form never
/// returned and the bounded form spun to its deadline. Interruption is how a supervisor stops a
/// stuck worker; here it converted an idle thread into an unkillable spinning one (observed live:
/// a 586 s `EmberCluster` hang through an 8-minute JUnit backstop, #727/#913; a waiter flipping to
/// RUNNABLE five seconds after `interrupt()`, #915).
///
/// Policy pinned here: **interruption ends the wait with a typed failure and preserves the flag.**
/// The waiter gets `CoreError.Interrupted`, the promise itself stays unresolved (as it does on a
/// timeout), and `Thread.currentThread().isInterrupted()` is still true afterwards so an outer loop
/// that checks it stops too. Chosen over "uninterruptible but non-spinning" because every
/// test-level timeout and executor shutdown in this repository relies on interrupt actually ending
/// a wait, and every `await()` caller already handles a failed `Result`.
class PromiseAwaitInterruptionTest {
    private record Outcome(Result<Unit> result, boolean flagStillSet, long cpuNanos) {}

    /// `Promise`'s static init creates a logger; log4j's provider lookup fails on an interrupted
    /// thread, so the class must be initialised here, on the test thread, before any test runs a
    /// deliberately interrupted one. A harness concern, not the behaviour under test.
    @BeforeAll
    static void initialisePromiseOnAnUninterruptedThread() {
        Promise.promise();
    }

    /// Runs `waiter` on a fresh thread, interrupts it once it is parked, and reports what came back.
    /// The join bound is the whole test: a spinning-forever waiter never fills `outcome`.
    private static Outcome interruptWhileWaiting(Promise<Unit> promise,
                                                 java.util.function.Function<Promise<Unit>, Result<Unit>> waiter) {
        var outcome = new AtomicReference<Outcome>();
        var started = new CountDownLatch(1);
        var threadId = new AtomicReference<Long>();
        var thread = new Thread(() -> {
                                    threadId.set(Thread.currentThread().threadId());
                                    started.countDown();
                                    var result = waiter.apply(promise);
                                    var flag = Thread.currentThread().isInterrupted();
                                    var cpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();

                                    outcome.set(new Outcome(result, flag, cpu));
                                },
                                "await-interruption-waiter");

        thread.start();
        awaitQuietly(started);
        waitUntilParked(thread);
        thread.interrupt();
        joinQuietly(thread, 5_000);
        var cpuIfStuck = ManagementFactory.getThreadMXBean().getThreadCpuTime(threadId.get());

        if (outcome.get() == null) {
            fail("await() did not return within 5 s of interrupt(); thread state " + thread.getState()
                + ", CPU consumed since start " + TimeUnit.NANOSECONDS.toMillis(cpuIfStuck)
                + " ms"
                + " — that is the spin, not a park");
        }

        return outcome.get();
    }

    @Test
    void await_unbounded_endsOnInterrupt_withTypedFailure_flagPreserved_andNoSpin() {
        var outcome = interruptWhileWaiting(Promise.promise(), Promise::await);

        outcome.result().onSuccess(_ -> fail("an unresolved promise cannot yield a success"));
        outcome.result().onFailure(cause -> assertThat(cause).isInstanceOf(CoreError.Interrupted.class));
        assertThat(outcome.flagStillSet()).as("the interrupt is the supervisor's signal to the whole thread, not just to this wait")
                  .isTrue();
        assertThat(TimeUnit.NANOSECONDS.toMillis(outcome.cpuNanos())).as("a parked wait consumes no core; a spinning one consumes the whole wait")
                  .isLessThan(500);
    }

    @Test
    void await_bounded_endsOnInterrupt_beforeItsDeadline() {
        var start = System.nanoTime();
        var outcome = interruptWhileWaiting(Promise.promise(),
                                            p -> p.await(timeSpan(30).seconds()));
        var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        outcome.result().onFailure(cause -> assertThat(cause).isInstanceOf(CoreError.Interrupted.class));
        assertThat(elapsedMillis).as("the interrupt, not the 30 s deadline, must end the wait").isLessThan(5_000);
        assertThat(outcome.flagStillSet()).isTrue();
    }

    /// Review of #1099, SF-1: an interrupt is the supervisor's stop signal to a THREAD. A retry that
    /// re-drives the operation — on `SharedScheduler`'s thread from attempt 2 — escapes exactly the
    /// thread the supervisor addressed. `Interrupted` is therefore `Cause.Terminal`: `Retry` stops on it.
    @Test
    void interrupted_isTerminal_soRetryNeverReDrivesIt() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var result = Retry.retry()
                          .attempts(3)
                          .strategy(BackoffStrategy.fixed().interval(timeSpan(1).millis()))
                          .execute(() -> {
                                       attempts.incrementAndGet();

                                       return Promise.<Unit> failure(new CoreError.Interrupted("stop"));
                                   })
                          .await(timeSpan(5).seconds());

        assertThat(new CoreError.Interrupted("x").isTerminal()).isTrue();
        assertThat(attempts.get()).as("a terminal cause is not re-driven").isEqualTo(1);
        result.onFailure(cause -> assertThat(cause).isInstanceOf(CoreError.Interrupted.class));
    }

    @Test
    void await_alreadyInterruptedThread_onUnresolvedPromise_returnsInterruptedWithoutParking() {
        var seen = new AtomicReference<Result<Unit>>();
        var thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            seen.set(Promise.<Unit> promise().await());
        });

        thread.start();
        joinQuietly(thread, 5_000);
        assertThat(seen.get()).isNotNull();
        seen.get().onFailure(cause -> assertThat(cause).isInstanceOf(CoreError.Interrupted.class));
        seen.get().onSuccess(_ -> fail("unresolved promise, interrupted caller: must be the Interrupted failure"));
    }

    @Test
    void await_resolvedPromise_returnsItsResultEvenOnAnInterruptedThread() {
        var seen = new AtomicReference<Result<Unit>>();
        var thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            seen.set(Promise.success(Unit.unit()).await());
        });

        thread.start();
        joinQuietly(thread, 5_000);
        assertThat(seen.get()).isNotNull();
        seen.get()
            .onFailure(cause -> fail("a resolved promise answers regardless of the caller's interrupt status: " + cause.message()));
    }

    @Test
    void await_resolutionStillWins_whenTheWaiterIsNotInterrupted() {
        var promise = Promise.<Unit> promise();
        var resolved = new AtomicBoolean();
        var thread = new Thread(() -> {
            promise.await();
            resolved.set(true);
        });

        thread.start();
        waitUntilParked(thread);
        promise.succeed(Unit.unit());
        joinQuietly(thread, 5_000);
        assertThat(resolved.get()).as("control: the ordinary resolution path is unchanged").isTrue();
    }

    private static void waitUntilParked(Thread thread) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) {
                fail("waiter never parked; state " + thread.getState());
            }

            Thread.onSpinWait();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
