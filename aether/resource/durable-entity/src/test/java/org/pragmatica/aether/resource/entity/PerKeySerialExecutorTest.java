// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1242 — the per-key executor must not keep an entry for every key it has ever seen. Each entry held its
/// last operation's resolved promise, RESULT included, so a high-cardinality entity (per-order,
/// per-session keys) grew the heap by distinct keys × state size until the node ran out of memory.
///
/// Retiring idle entries is only correct if it cannot break the same-key total order, so the second test
/// forces open the window retirement creates and submits into it.
class PerKeySerialExecutorTest {
    private static final long SETTLE_MILLIS = 10_000;

    @Test
    @Timeout(120)
    void submit_retainsNoEntry_afterEachOfManyDistinctKeysGoesIdle() throws InterruptedException {
        var executor = PerKeySerialExecutor.<Integer> perKeySerialExecutor();
        var submitted = IntStream.range(0, 100_000)
                                 .mapToObj(key -> executor.submit(key, () -> Promise.success(key)))
                                 .toList();

        Promise.allOf(submitted).await().onFailure(cause -> fail(cause.message()));

        assertThat(settledTrackedKeys(executor)).as("an idle key must not keep its entry — or the result it"
                                                    + " retains")
                                                .isZero();
    }

    /// The window retirement opens, forced open deterministically. Key equality is by name, but the FIRST
    /// submit uses an instance whose `hashCode` can be armed to park its caller — and the map calls
    /// `hashCode` at the start of the retiring `remove(key, entry)`, so arming it stops the retirer exactly
    /// between deciding to retire and removing the entry.
    ///
    /// While it is parked, a second operation is submitted and held running; once the retirer finishes, a
    /// third is submitted. The third must not start until the second has finished. A retirement that
    /// decides by READING the tail and then removes lets the second chain onto the entry being removed and
    /// the third start on a fresh entry — concurrently with the second. Deciding by compare-and-set to a
    /// sentinel makes the second see the retired entry and start a fresh one, which the third queues behind.
    @Test
    @Timeout(60)
    void submit_runsOperationAfterItsPredecessor_whenSubmittedWhileTheKeyRetires() throws InterruptedException {
        var executor = PerKeySerialExecutor.<TrapKey> perKeySerialExecutor();
        var trap = new TrapKey("k", true);
        var first = Promise.<Unit> promise();

        executor.submit(trap, () -> first);
        trap.arm();
        first.succeed(Unit.unit());

        assertThat(trap.reached.await(10, TimeUnit.SECONDS)).as("the retirer must reach the armed hashCode")
                                                           .isTrue();

        var second = Promise.<Unit> promise();
        var secondStarted = new CountDownLatch(1);
        var secondResult = executor.submit(new TrapKey("k", false), () -> startThen(secondStarted, second));

        assertThat(secondStarted.await(10, TimeUnit.SECONDS)).as("the second operation must start").isTrue();

        trap.release.countDown();
        awaitRetirerDone(executor);

        var thirdStarted = new CountDownLatch(1);
        var secondDoneWhenThirdStarted = new AtomicBoolean();
        var thirdResult = executor.submit(new TrapKey("k", false),
                                          () -> recordThenStart(second, secondDoneWhenThirdStarted, thirdStarted));
        var thirdStartedEarly = thirdStarted.await(500, TimeUnit.MILLISECONDS);

        second.succeed(Unit.unit());
        secondResult.await().onFailure(cause -> fail(cause.message()));
        thirdResult.await().onFailure(cause -> fail(cause.message()));

        assertThat(thirdStartedEarly).as("the third operation started while the second was still running")
                                     .isFalse();
        assertThat(secondDoneWhenThirdStarted.get()).as("the third operation must run after the second")
                                                    .isTrue();
    }

    /// The install must be a compare-and-set against the tail just read. Forced deterministically through
    /// [PerKeySerialExecutor#tailReadProbe(Runnable)]: the second submit is held after reading the first
    /// operation's tail; inside that window the first operation completes and the key RETIRES. A
    /// `getAndSet` install would then chain the second operation onto the retired sentinel, which never
    /// resolves, so it would never run. The compare-and-set fails instead, re-reads, and starts fresh.
    @Test
    @Timeout(60)
    void submit_runsTheOperation_whenTheKeyRetiresBetweenReadingAndReplacingTheTail() throws InterruptedException {
        var executor = PerKeySerialExecutor.<String> perKeySerialExecutor();
        var first = Promise.<Unit> promise();
        var probed = new AtomicBoolean();

        executor.submit("k", () -> first);
        executor.tailReadProbe(() -> retireInsideTheWindow(executor, first, probed));

        var second = executor.submit("k", () -> Promise.success(Unit.unit()));

        assertThat(probed.get()).as("the probe must have run, or this test proves nothing").isTrue();
        assertThat(second.await(timeSpan(5).seconds()).isSuccess()).as("the second operation must run, not wait"
                                                                    + " on a retired tail")
                                                                .isTrue();
    }

    private static void retireInsideTheWindow(PerKeySerialExecutor<String> executor,
                                              Promise<Unit> first,
                                              AtomicBoolean probed) {
        if (probed.compareAndSet(false, true)) {
            first.succeed(Unit.unit());
            awaitRetired(executor);
        }
    }

    private static void awaitRetired(PerKeySerialExecutor<String> executor) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (executor.trackedKeys() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(executor.trackedKeys()).as("the first operation's key must have retired").isZero();
    }

    private static Promise<Unit> startThen(CountDownLatch started, Promise<Unit> completion) {
        started.countDown();

        return completion;
    }

    private static Promise<Unit> recordThenStart(Promise<Unit> predecessor,
                                                 AtomicBoolean predecessorDone,
                                                 CountDownLatch started) {
        predecessorDone.set(predecessor.isResolved());
        started.countDown();

        return Promise.unitPromise();
    }

    /// The retirer's `remove` gives no signal of its own, so it gets a bounded moment to finish after
    /// release. The early exit is the entry disappearing, which happens only when the retirer removed the
    /// entry the second operation is queued on; otherwise the second operation's own entry stays and the
    /// full bound elapses.
    private static void awaitRetirerDone(PerKeySerialExecutor<?> executor) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 1_000;

        while (executor.trackedKeys() > 0 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private static int settledTrackedKeys(PerKeySerialExecutor<?> executor) throws InterruptedException {
        var deadline = System.currentTimeMillis() + SETTLE_MILLIS;

        while (executor.trackedKeys() > 0 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }

        return executor.trackedKeys();
    }

    /// A key whose equality is its name; an instance built `trapping` parks the FIRST `hashCode` call after
    /// [#arm] until the test releases it.
    private static final class TrapKey {
        private final String name;
        private final boolean trapping;
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        TrapKey(String name, boolean trapping) {
            this.name = name;
            this.trapping = trapping;
        }

        void arm() {
            armed.set(true);
        }

        @Override
        public int hashCode() {
            if (trapping && armed.compareAndSet(true, false)) {
                reached.countDown();
                awaitQuietly(release);
            }

            return name.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TrapKey key && key.name.equals(name);
        }

        private static void awaitQuietly(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
