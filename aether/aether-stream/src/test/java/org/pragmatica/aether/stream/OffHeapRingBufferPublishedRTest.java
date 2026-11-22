// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.RemovalCause;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// The concurrent and the publication pins of R (M-2r-1, spec section 6): readers against one evicting writer see only answers
/// of an R the writer logged in the window they read in, R is published exactly once per change, and a published R is never
/// updated in place.
class OffHeapRingBufferPublishedRTest {
    private static final String STREAM = "published";
    private static final long DATA_REGION = 4096;
    private static final int READERS = 3;
    private static final int LOG_SIZE = 1 << 21;
    private static final long MIN_STRADDLES = 1000;
    private static final long MIN_READS = 1000;
    private static final long DEADLINE_MILLIS = 20_000;

    /// The T-C3 race arm. The writer appends one record and removes it, alternating the cause, and logs the next R under a
    /// sequence number BEFORE the publish. A reader notes the sequence n0 before a read and n1 after it; its answer must equal
    /// `answer` over a logged R numbered in `[n0 - 1, n1]`. Not exercised (and red) under 1000 straddling reads or 1000 reads.
    /// Mutants (part 4): R's fields updated in place; R published AFTER the slot overwrite.
    @Test
    void readersAgainstAnEvictingWriter_everyAnswerIsOfALoggedRInItsWindow() throws Exception {
        var log = new AtomicReferenceArray<RemovalLedger>(LOG_SIZE);
        var sequence = new AtomicLong();
        var stop = new AtomicBoolean();
        var ready = new AtomicInteger();
        var reads = new AtomicLong();
        var straddles = new AtomicLong();
        var violations = new AtomicInteger();
        var firstViolation = new AtomicReference<String>();
        var pool = Executors.newFixedThreadPool(READERS);

        log.set(0, RemovalLedger.EMPTY);

        try (var ring = memoryRing()) {
            List<Future<?>> readers = new ArrayList<>();

            for (var i = 0; i < READERS; i++) {
                readers.add(pool.submit(() -> readUntilStopped(ring, log, sequence, stop, ready, reads, straddles, violations, firstViolation)));
            }

            while (ready.get() < READERS) {
                Thread.onSpinWait();
            }

            var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);
            var current = RemovalLedger.EMPTY;
            var removed = 0;

            while (removed < LOG_SIZE - 1
                   && violations.get() == 0
                   && System.nanoTime() < deadline
                   && (straddles.get() < MIN_STRADDLES || reads.get() < MIN_READS)) {
                removed++;
                ring.append("e".getBytes(StandardCharsets.UTF_8), removed).unwrap();

                var cause = removed % 2 == 0 ? RemovalCause.AGE : RemovalCause.BYTES;

                current = current.removed(removed, cause).unwrap();
                log.set(removed, current);
                sequence.set(removed);
                ring.advanceTail(removed, cause).unwrap();
            }

            stop.set(true);

            for (var reader : readers) {
                reader.get(30, TimeUnit.SECONDS);
            }

            assertThat(ring.removalLedger().tail()).as("the ring's R is the last logged R").isEqualTo(current.tail());
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }

        assertThat(violations.get()).as("answers outside their window: " + firstViolation.get()).isZero();
        assertThat(straddles.get()).as("reads that straddled a publish").isGreaterThanOrEqualTo(MIN_STRADDLES);
        assertThat(reads.get()).as("reads below the tail").isGreaterThanOrEqualTo(MIN_READS);
    }

    /// R is published once per change: every removal gives the ring ONE new R instance, an append that removes nothing gives
    /// none. Mutants (part 4): a second publish in one removal (count 2N + 1), a publish on append.
    @Test
    void removal_publishesOneNewRPerChange() {
        var distinct = new IdentityHashMap<RemovalLedger, Boolean>();

        try (var ring = memoryRing()) {
            var previous = ring.removalLedger();

            distinct.put(previous, true);

            for (var i = 1; i <= 40; i++) {
                ring.append("e".getBytes(StandardCharsets.UTF_8), i).unwrap();

                assertThat(ring.removalLedger()).as("an append that removes nothing publishes nothing").isSameAs(previous);

                ring.advanceTail(i, i % 3 == 0 ? RemovalCause.COUNT : RemovalCause.AGE).unwrap();
                previous = ring.removalLedger();
                distinct.put(previous, true);

                assertThat(distinct).as("one new R after removal " + i).hasSize(i + 1);
            }
        }
    }

    /// R's fields are never updated in place: after later publishes, every earlier R still answers as it did when it was
    /// published, and every field of the type is `final`. Mutant (part 4): the successor mutates the current R.
    @Test
    void publishedR_isNeverUpdatedInPlace() {
        var seen = new ArrayList<RemovalLedger>();
        var snapshots = new ArrayList<String>();

        try (var ring = memoryRing()) {
            for (var i = 1; i <= 60; i++) {
                ring.append("e".getBytes(StandardCharsets.UTF_8), i).unwrap();
                ring.advanceTail(i, i % 2 == 0 ? RemovalCause.AGE : RemovalCause.BYTES).unwrap();
                seen.add(ring.removalLedger());
                snapshots.add(describe(ring.removalLedger()));
            }
        }

        for (var i = 0; i < seen.size(); i++) {
            assertThat(describe(seen.get(i))).as("R after removal " + (i + 1)).isEqualTo(snapshots.get(i));
        }

        for (var field : RemovalLedger.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertThat(Modifier.isFinal(field.getModifiers())).as("field " + field.getName() + " is final").isTrue();
            }
        }
    }

    private static void readUntilStopped(OffHeapRingBuffer ring,
                                         AtomicReferenceArray<RemovalLedger> log,
                                         AtomicLong sequence,
                                         AtomicBoolean stop,
                                         AtomicInteger ready,
                                         AtomicLong reads,
                                         AtomicLong straddles,
                                         AtomicInteger violations,
                                         AtomicReference<String> firstViolation) {
        ready.incrementAndGet();

        while (!stop.get()) {
            var n0 = sequence.get();

            if (n0 < 2) {
                Thread.onSpinWait();
                continue;
            }

            var offset = n0 - 2;
            var result = ring.read(offset, 4);
            var n1 = sequence.get();

            reads.incrementAndGet();

            if (n1 > n0) {
                straddles.incrementAndGet();
            }

            if (!matchesALoggedR(result, log, offset, n0 - 1, n1)) {
                violations.incrementAndGet();
                firstViolation.compareAndSet(null, "offset " + offset + " window [" + (n0 - 1) + ", " + n1 + "] answer " + result);
            }
        }
    }

    private static boolean matchesALoggedR(Result<?> result, AtomicReferenceArray<RemovalLedger> log, long offset, long from, long to) {
        var seen = new AtomicReference<Cause>();

        result.onFailure(seen::set);

        if (seen.get() == null) {
            return false;
        }

        for (var k = from; k <= to; k++) {
            if (log.get((int) k).answer(offset, false).equals(seen.get())) {
                return true;
            }
        }

        return false;
    }

    private static String describe(RemovalLedger ledger) {
        var builder = new StringBuilder();

        builder.append(ledger.firstHeld()).append('/').append(ledger.floor()).append('/').append(ledger.tail())
               .append('/').append(ledger.runCount()).append('/').append(ledger.droppedMask());

        for (var offset = 0L; offset < ledger.tail(); offset++) {
            builder.append('|').append(ledger.answer(offset, true)).append(ledger.answer(offset, false));
        }

        return builder.toString();
    }

    private static OffHeapRingBuffer memoryRing() {
        return OffHeapRingBuffer.offHeapRingBuffer(OffHeapRingBuffer.Mode.MEMORY,
                                                   STREAM,
                                                   0,
                                                   8,
                                                   DATA_REGION,
                                                   0L,
                                                   EvictionListener.NOOP,
                                                   EvictionPolicy.DROP_OLDEST);
    }
}
