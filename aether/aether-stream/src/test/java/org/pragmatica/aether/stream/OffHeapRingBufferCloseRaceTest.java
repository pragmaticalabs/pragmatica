// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

/// #999 — the primitive header accessors are read by LIVE paths (the replication receive handler's
/// `nextExpectedOffset`, the backfill thread's `partitionInfo`, the status surfaces) while a concurrent
/// `close()` can free the shared arena under the reader. The closing side is NOT shutdown-specific:
/// `StreamPartitionManager.reconcileReshuffle` releases a materialized ring on confirmed role loss every
/// 5s, and `destroyStream` closes one from the Management API.
///
/// `Arena.ofShared()` keeps that memory-safe by throwing `IllegalStateException` AT THE READER, so a
/// `long`-returning accessor with no failure channel let the throw escape — killing the
/// `stream-partition-backfill` thread outright and, on a Netty event loop, being swallowed by
/// `RabiaNode.dispatchLoudly` which DROPPED a replication message.
class OffHeapRingBufferCloseRaceTest {

    /// Rounds of the close-under-readers race. Each round enters the window by construction (one reader is
    /// parked past the `closed` check until `close()` completes — #1253), so the count no longer buys
    /// reliability; it buys repetitions of the free-running readers racing the same `close()`.
    private static final int ROUNDS = 200;
    private static final long CAPACITY = 64;
    private static final long DATA_REGION = 4096;

    /// Closed-ring reads report the EMPTY encoding (head/tail `-1`, count `0`) rather than throwing.
    /// That is what every caller already treats as "this node does not hold the partition" — true by
    /// construction once the ring is released — so the racy path converges on the behaviour the non-racy
    /// path (ring already removed from the entry's map) has always had.
    @Test
    void headOffset_reportsEmpty_whenBufferClosed() {
        var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);

        buffer.append("payload".getBytes(), 1L);
        buffer.close();

        assertThat(buffer.headOffset()).isEqualTo(-1L);
    }

    @Test
    void tailOffset_reportsEmpty_whenBufferClosed() {
        var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);

        buffer.append("payload".getBytes(), 1L);
        buffer.close();

        assertThat(buffer.tailOffset()).isEqualTo(-1L);
    }

    @Test
    void eventCount_reportsZero_whenBufferClosed() {
        var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);

        buffer.append("payload".getBytes(), 1L);
        buffer.close();

        assertThat(buffer.eventCount()).isEqualTo(0L);
    }

    /// Asserted as "does not throw" rather than through `eventCount()`, so this pins the retention guard
    /// ALONE: routing the assertion through another guarded accessor would make the test reddened by either
    /// hunk and attributable to neither.
    @Test
    void applyRetention_doesNotThrow_whenBufferClosed() {
        var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);

        buffer.append("payload".getBytes(), 1L);
        buffer.close();

        assertThatCode(() -> buffer.applyRetention(RetentionPolicy.retentionPolicy(1L, 1L, 1L))).doesNotThrowAnyException();
    }

    @Test
    void evictByAge_doesNotThrow_whenBufferClosed() {
        var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);

        buffer.append("payload".getBytes(), 1L);
        buffer.close();

        assertThatCode(() -> buffer.evictByAge(0L)).doesNotThrowAnyException();
    }

    /// `seedHead` already refused a closed ring via its `closed` flag, but the flag is a TOCTOU test a
    /// concurrent release can win; the native reads and writes behind it are now inside the guard too.
    @Test
    void seedHead_reportsBufferClosed_whenBufferClosed() {
        var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);

        buffer.close();

        assertThat(buffer.seedHead(10L).isFailure()).isTrue();
    }

    /// `allocatedBytes`/`controlBytes` read `MemorySegment.byteSize()`, which is segment METADATA and not a
    /// scoped memory access, so they remain readable after the arena is freed and need no guard. Asserted
    /// rather than reasoned: `buildStreamInfo` calls `allocatedBytes()` on every materialized ring, so if
    /// that assumption were wrong it would be another unguarded hole on the same live path.
    @Test
    void byteSizeAccessors_doNotThrow_whenBufferClosed() {
        var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);

        buffer.append("payload".getBytes(), 1L);
        buffer.close();

        assertThatCode(buffer::allocatedBytes).doesNotThrowAnyException();
        assertThatCode(buffer::controlBytes).doesNotThrowAnyException();
    }

    /// The ASYNC half of the race, which the `closed` fast path above cannot reach: readers already past
    /// the flag check when `close()` completes receive the JDK's asynchronous `IllegalStateException`. The
    /// assertion is that NOTHING escapes to the reader thread — that is precisely what killed
    /// `stream-partition-backfill` and what `dispatchLoudly` swallowed.
    ///
    /// #1253: entering that window used to be left to the scheduler, and under CPU load 200 rounds could see
    /// none of it (4 of 40 fresh-JVM runs under a 32-thread hog). One reader per round is now parked through
    /// [OffHeapRingBuffer#readWindowProbe] until `close()` has completed, so every round enters it.
    @Test
    void concurrentReaders_seeNoEscapingThrowable_whenArenaClosedUnderThem() throws InterruptedException {
        var escaped = new CopyOnWriteArrayList<Throwable>();
        var observedRaces = 0L;

        for (int round = 0; round < ROUNDS; round++) {
            observedRaces += runOneCloseRace(escaped);
        }

        assertThat(escaped).as("throwables escaping a reader while the arena was closed under it").isEmpty();
        // Non-vacuity: an empty `escaped` list proves nothing unless the window was genuinely entered, and a
        // concurrency test that never reaches the race passes identically to one that handles it. The
        // refusal counter is the only evidence that these rounds exercised the path they exist to cover —
        // and since every round parks a reader in the window, every round must have contributed a refusal.
        assertThat(observedRaces).as("reads refused because the arena closed UNDER an in-flight reader "
                                    + "(fewer than " + ROUNDS + " means a round never entered the race window)")
                                 .isGreaterThanOrEqualTo(ROUNDS);
    }

    private long runOneCloseRace(List<Throwable> escaped) throws InterruptedException {
        var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);

        buffer.append("payload".getBytes(), 1L);

        var running = new AtomicBoolean(true);
        var started = new CountDownLatch(3);
        var finished = new CountDownLatch(3);
        var parkClaimed = new AtomicBoolean(false);
        var parkedInWindow = new CountDownLatch(1);
        var closeCompleted = new CountDownLatch(1);

        buffer.readWindowProbe(() -> parkFirstReader(parkClaimed, parkedInWindow, closeCompleted));

        startReader(buffer::headOffset, running, started, finished, escaped);
        startReader(buffer::tailOffset, running, started, finished, escaped);
        startReader(buffer::eventCount, running, started, finished, escaped);

        started.await(5, TimeUnit.SECONDS);

        var parked = parkedInWindow.await(5, TimeUnit.SECONDS);

        buffer.close();
        closeCompleted.countDown();
        running.set(false);
        finished.await(5, TimeUnit.SECONDS);

        // Asserted after the readers are stopped, and per round rather than only through the total: a probe
        // that never parks means the seam is not wired, so fail on the first round instead of letting all
        // of them wait out the timeout and enter the window only by chance again.
        assertThat(parked).as("a reader parked in the close-race window").isTrue();

        return buffer.closedUnderReaderCount();
    }

    /// The first reader to pass the `closed` check parks there until `close()` has completed, so its native
    /// read is refused by the JDK — the genuine race — however the scheduler treats the other readers. A park
    /// that times out before `close()` completes is not hidden: that round then contributes no refusal, and
    /// the non-vacuity check reddens.
    private static void parkFirstReader(AtomicBoolean parkClaimed,
                                        CountDownLatch parkedInWindow,
                                        CountDownLatch closeCompleted) {
        if (parkClaimed.compareAndSet(false, true)) {
            parkedInWindow.countDown();
            awaitRestoringInterrupt(closeCompleted);
        }
    }

    private static void awaitRestoringInterrupt(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void startReader(Reader reader,
                             AtomicBoolean running,
                             CountDownLatch started,
                             CountDownLatch finished,
                             List<Throwable> escaped) {
        var thread = new Thread(() -> {
            started.countDown();

            try {
                while (running.get()) {
                    reader.read();
                }
            } catch (Throwable t) {
                escaped.add(t);
            } finally {
                finished.countDown();
            }
        });

        thread.setDaemon(true);
        thread.start();
    }

    @FunctionalInterface
    private interface Reader {
        long read();
    }
}
