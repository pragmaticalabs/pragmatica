// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #1258 review rounds 2 and 3: append listeners — foreign code, and in production a slice consumer's
/// wake-up — run on the ring's own serial notifier, never on a publisher's thread. A listener learns that
/// the ring advanced to an offset: pending notifications are coalesced into one high-water offset, so
/// a slow listener costs O(1) state, never one entry per publish. A listener that throws — an exception
/// or an `Error` — fails no append and never stops later notifications.
class OffHeapRingBufferNotifierTest {

    @Test
    void appendListener_neverRunsOnThePublishingThread() {
        var ring = OffHeapRingBuffer.offHeapRingBuffer(1_000, 1024 * 1024);
        var listenerThreads = new ConcurrentLinkedQueue<Thread>();
        var highWater = new AtomicLong(-1);

        ring.addAppendListener(offset -> recordThread(listenerThreads, highWater, offset));
        for (int i = 0; i < 3; i++) {
            assertThat(ring.append(("e" + i).getBytes(UTF_8), 1L).isSuccess()).isTrue();
        }

        awaitHighWater(highWater, 2);
        assertThat(listenerThreads).as("the publisher thread executes no listener")
                                   .isNotEmpty()
                                   .doesNotContain(Thread.currentThread());
        ring.close();
    }

    @Test
    void throwingListener_failsNoAppend_andLaterNotificationsStillArrive() {
        var ring = OffHeapRingBuffer.offHeapRingBuffer(1_000, 1024 * 1024);
        var highWater = new AtomicLong(-1);

        ring.addAppendListener(_ -> {
            throw new IllegalStateException("listener failure injected by the test");
        });
        ring.addAppendListener(offset -> highWater.set(offset));

        for (int i = 0; i < 3; i++) {
            assertThat(ring.append(("e" + i).getBytes(UTF_8), 1L).isSuccess()).as("append %d", i).isTrue();
            awaitHighWater(highWater, i);
        }
        ring.close();
    }

    /// #1258 review round 3 (R3-1): an `Error` from a listener — an `AssertionError`, or a
    /// `StackOverflowError` from runaway slice code — used to kill the notifier with its flag still set,
    /// so the ring never notified again (reviewer probe H: `seen=[0]` across five later publishes).
    @Test
    void errorThrowingListeners_failNoAppend_andEveryLaterPublishStillNotifiesAllListeners() {
        var ring = OffHeapRingBuffer.offHeapRingBuffer(1_000, 1024 * 1024);
        var assertionCalls = new AtomicInteger();
        var overflowCalls = new AtomicInteger();
        var highWater = new AtomicLong(-1);

        ring.addAppendListener(throwing(assertionCalls, new AssertionError("assertion injected by the test")));
        ring.addAppendListener(throwing(overflowCalls, new StackOverflowError("overflow injected by the test")));
        ring.addAppendListener(offset -> highWater.set(offset));

        for (int i = 0; i < 6; i++) {
            assertThat(ring.append(("e" + i).getBytes(UTF_8), 1L).isSuccess()).as("append %d", i).isTrue();
            awaitHighWater(highWater, i);
        }

        assertThat(assertionCalls).as("the AssertionError listener was notified of every publish").hasValue(6);
        assertThat(overflowCalls).as("the StackOverflowError listener was notified of every publish").hasValue(6);
        assertThat(ring.appendListenerFailures()).as("each listener failure is counted").isEqualTo(12);
        ring.close();
    }

    /// #1258 addendum (a): pending notifications coalesce into one high-water offset. 10,000 publishes
    /// while the only listener is blocked leave one pending offset, and on release the listener observes
    /// the final offset in a single call — never one queued entry per publish.
    @Test
    void notificationsCoalesce_toTheHighWaterOffset_whileAListenerIsBlocked() throws InterruptedException {
        var ring = OffHeapRingBuffer.offHeapRingBuffer(20_000, 16L * 1024 * 1024);
        var seen = Collections.synchronizedList(new ArrayList<Long>());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var highWater = new AtomicLong(-1);

        ring.addAppendListener(offset -> blockOnFirst(seen, entered, release, highWater, offset));
        ring.append("e0".getBytes(UTF_8), 1L);
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the listener is blocked on offset 0").isTrue();

        for (int i = 1; i <= 10_000; i++) {
            ring.append(("e" + i).getBytes(UTF_8), 1L);
        }
        release.countDown();
        awaitHighWater(highWater, 10_000);

        assertThat(List.copyOf(seen)).as("offset 0, then the coalesced high-water in one call").containsExactly(0L, 10_000L);
        ring.close();
    }

    @Test
    void notifications_areMonotonic_andReachTheLastOffset_underConcurrentAppenders() throws InterruptedException {
        var threads = 4;
        var perThread = 2_000;
        var total = threads * perThread;
        var ring = OffHeapRingBuffer.offHeapRingBuffer(total, 16L * 1024 * 1024);
        var seen = Collections.synchronizedList(new ArrayList<Long>());
        var highWater = new AtomicLong(-1);
        var pool = Executors.newFixedThreadPool(threads);

        ring.addAppendListener(offset -> record(seen, highWater, offset));
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> appendAll(ring, perThread));
        }
        pool.shutdown();

        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        awaitHighWater(highWater, total - 1);
        assertThat(List.copyOf(seen)).as("offsets only move forward").isSorted().doesNotHaveDuplicates();
        ring.close();
    }

    private static LongConsumer throwing(AtomicInteger calls, Error error) {
        return _ -> {
            calls.incrementAndGet();
            throw error;
        };
    }

    private static void blockOnFirst(List<Long> seen,
                                     CountDownLatch entered,
                                     CountDownLatch release,
                                     AtomicLong highWater,
                                     long offset) {
        seen.add(offset);
        if (offset == 0) {
            entered.countDown();
            awaitQuietly(release);
        }
        highWater.set(offset);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitHighWater(AtomicLong highWater, long offset) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (highWater.get() < offset && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(highWater.get()).as("notified up to offset %d", offset).isGreaterThanOrEqualTo(offset);
    }

    private static void appendAll(OffHeapRingBuffer ring, int count) {
        for (int i = 0; i < count; i++) {
            ring.append(("e" + i).getBytes(UTF_8), 1L);
        }
    }

    private static void recordThread(ConcurrentLinkedQueue<Thread> threads, AtomicLong highWater, long offset) {
        threads.add(Thread.currentThread());
        highWater.set(offset);
    }

    private static void record(List<Long> seen, AtomicLong highWater, long offset) {
        seen.add(offset);
        highWater.set(offset);
    }
}
