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
import java.util.stream.LongStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #1258 review round 2 (R2-1): append listeners — foreign code, and in production a slice consumer's
/// handler — run on the ring's own serial notifier, never on a publisher's thread. So no publish can be
/// held hostage by other publishers' listeners, a throwing listener fails no publish and does not stall
/// later notifications, and notifications still arrive in offset order.
class OffHeapRingBufferNotifierTest {

    @Test
    void appendListener_neverRunsOnThePublishingThread() throws InterruptedException {
        var ring = OffHeapRingBuffer.offHeapRingBuffer(1_000, 1024 * 1024);
        var listenerThreads = new ConcurrentLinkedQueue<Thread>();
        var notified = new CountDownLatch(3);

        ring.addAppendListener(_ -> recordThread(listenerThreads, notified));
        for (int i = 0; i < 3; i++) {
            assertThat(ring.append(("e" + i).getBytes(UTF_8), 1L).isSuccess()).isTrue();
        }

        assertThat(notified.await(10, TimeUnit.SECONDS)).as("every append was notified").isTrue();
        assertThat(listenerThreads).as("the publisher thread executes no listener")
                                   .doesNotContain(Thread.currentThread());
        ring.close();
    }

    @Test
    void throwingListener_failsNoAppend_andLaterNotificationsStillArrive() throws InterruptedException {
        var ring = OffHeapRingBuffer.offHeapRingBuffer(1_000, 1024 * 1024);
        var seen = Collections.synchronizedList(new ArrayList<Long>());
        var notified = new CountDownLatch(3);

        ring.addAppendListener(_ -> {
            throw new IllegalStateException("listener failure injected by the test");
        });
        ring.addAppendListener(offset -> recordOffset(seen, notified, offset));

        for (int i = 0; i < 3; i++) {
            assertThat(ring.append(("e" + i).getBytes(UTF_8), 1L).isSuccess()).as("append %d", i).isTrue();
        }

        assertThat(notified.await(10, TimeUnit.SECONDS)).as("notifications continue past the failing listener")
                                                        .isTrue();
        assertThat(seen).containsExactly(0L, 1L, 2L);
        ring.close();
    }

    @Test
    void notifications_arriveInOffsetOrder_underConcurrentAppenders() throws InterruptedException {
        var threads = 4;
        var perThread = 2_000;
        var total = threads * perThread;
        var ring = OffHeapRingBuffer.offHeapRingBuffer(total, 16L * 1024 * 1024);
        var seen = Collections.synchronizedList(new ArrayList<Long>());
        var notified = new CountDownLatch(total);
        var pool = Executors.newFixedThreadPool(threads);

        ring.addAppendListener(offset -> recordOffset(seen, notified, offset));
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> appendAll(ring, perThread));
        }
        pool.shutdown();

        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(notified.await(60, TimeUnit.SECONDS)).as("every append was notified").isTrue();
        assertThat(List.copyOf(seen)).containsExactlyElementsOf(LongStream.range(0, total).boxed().toList());
        ring.close();
    }

    private static void appendAll(OffHeapRingBuffer ring, int count) {
        for (int i = 0; i < count; i++) {
            ring.append(("e" + i).getBytes(UTF_8), 1L);
        }
    }

    private static void recordThread(ConcurrentLinkedQueue<Thread> threads, CountDownLatch notified) {
        threads.add(Thread.currentThread());
        notified.countDown();
    }

    private static void recordOffset(List<Long> seen, CountDownLatch notified, long offset) {
        seen.add(offset);
        notified.countDown();
    }
}
