// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.RepeatedTest;

import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #1231 defence in depth: the ring's own `append` is mutually exclusive, so even a caller that bypasses
/// the manager's per-partition section cannot hand two writers the same offset or tear the header.
/// `OffHeapRingBufferTest`'s concurrency case has ONE writer; this is the multi-writer case.
class OffHeapRingBufferConcurrentAppendTest {
    private static final int THREADS = 16;
    private static final int PER_THREAD = 5_000;
    private static final int TOTAL = THREADS * PER_THREAD;

    @RepeatedTest(20)
    void append_assignsDistinctContiguousOffsets_underConcurrentWriters() throws InterruptedException {
        var ring = OffHeapRingBuffer.offHeapRingBuffer(TOTAL, 64L * 1024 * 1024);
        var acked = new ConcurrentLinkedQueue<long[]>();
        var failures = new ConcurrentLinkedQueue<String>();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(THREADS);

        for (int t = 0; t < THREADS; t++) {
            var thread = t;

            pool.submit(() -> appendAll(ring, thread, start, acked, failures));
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        var byOffset = new HashMap<Long, String>();

        acked.forEach(a -> byOffset.put(a[0], "t" + a[1] + "-" + a[2]));

        assertThat(failures).isEmpty();
        assertThat(byOffset).as("every append got a DISTINCT offset").hasSize(TOTAL);
        assertThat(byOffset.keySet()).containsExactlyInAnyOrderElementsOf(LongStream.range(0, TOTAL).boxed().toList());
        assertThat(ring.eventCount()).isEqualTo(TOTAL);
        ring.read(0, TOTAL)
            .onFailure(cause -> assertThat(cause.message()).as("read-back").isNull())
            .onSuccess(events -> events.forEach(e -> assertThat(new String(e.data(), UTF_8)).as("payload at %d", e.offset())
                                                                                        .isEqualTo(byOffset.get(e.offset()))));
        ring.close();
    }

    private static void appendAll(OffHeapRingBuffer ring,
                                  int thread,
                                  CountDownLatch start,
                                  ConcurrentLinkedQueue<long[]> acked,
                                  ConcurrentLinkedQueue<String> failures) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.add("interrupted");
            return;
        }

        for (int i = 0; i < PER_THREAD; i++) {
            var index = i;

            ring.append(("t" + thread + "-" + i).getBytes(UTF_8), 1L)
                .onSuccess(offset -> acked.add(new long[]{offset, thread, index}))
                .onFailure(cause -> failures.add(cause.message()));
        }
    }
}
