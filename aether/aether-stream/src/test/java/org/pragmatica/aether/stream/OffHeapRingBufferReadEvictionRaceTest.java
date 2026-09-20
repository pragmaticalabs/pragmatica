// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Functions.Fn1;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;
import static org.assertj.core.api.Assertions.assertThat;


/// #1340 — a read racing a concurrent eviction returned ANOTHER offset's record. `readChecked` checked
/// `fromOffset >= tail`, then copied the slots with no lock and no re-check; a slot the wrapping writer
/// reclaimed in between was copied AFTER the overwrite and labelled with the REQUESTED offset, so the
/// result was well-formed, decodable and silently wrong (offset N carrying the record of N + capacity).
/// Measured at rc4 `ccba0dba5`: 1,268 torn of 7,384 reads through `StreamPartitionManager.readLocal`.
///
/// The contract: a read returns the record for the requested offset, or fails `CursorExpired` when that
/// offset was reclaimed — never another offset's record. The fix is a seqlock-style re-check of the
/// tail AFTER the copy, so a read that lost the race fails typed instead of returning the newer slot.
///
/// Each payload is the offset it was appended at, so a torn read is detected by content, not by the
/// label the ring puts on it (the label is exactly what the defect gets right).
class OffHeapRingBufferReadEvictionRaceTest {
    /// Small ring so the single writer wraps it thousands of times per run; the reader sits at the
    /// tail, the slot the next wrap reclaims.
    private static final long CAPACITY = 64;
    private static final long DATA_REGION = 64 * 1024;
    private static final int BATCH = 64;
    /// Appends per run (about 0.5 s on a 16-vCPU x86 host). Sized from the measured base at rc4
    /// `ccba0dba5`: one run tore 3,499 of 23,984 batch reads and 646 of 236,109 slice reads, so a
    /// single run of this length reddens with thousands of margin rather than by luck.
    private static final long APPENDS = 400_000;

    /// One reader's tally. `torn`: a returned event whose payload belongs to another offset. `expired`:
    /// reads refused `CursorExpired` — the typed outcome of losing the race, asserted > 0 so a green run
    /// proves the race was entered rather than absent.
    private record Tally(long reads, long torn, long expired, String firstTorn) {}

    @Test
    void read_neverReturnsAnotherOffsetsRecord_whileAppendsEvictTheTail() throws InterruptedException {
        try (var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION)) {
            var tally = raceReaderAgainstWriter(buffer, from -> tallyBatch(buffer, from));

            System.out.println("#1340 read: " + tally);
            assertThat(tally.reads()).as("the reader must have run").isGreaterThan(1_000);
            assertThat(tally.expired()).as("the race must have been entered (reads refused CursorExpired)")
                      .isGreaterThan(0);
            assertThat(tally.torn()).as("reads whose payload belongs to another offset; first: " + tally.firstTorn())
                      .isZero();
        }
    }

    @Test
    void readSlice_neverReturnsAnotherOffsetsRecord_whileAppendsEvictTheTail() throws InterruptedException {
        try (var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION)) {
            var tally = raceReaderAgainstWriter(buffer, from -> tallySlice(buffer, from));

            System.out.println("#1340 readSlice: " + tally);
            assertThat(tally.reads()).as("the reader must have run").isGreaterThan(1_000);
            assertThat(tally.expired()).as("the race must have been entered (reads refused CursorExpired)")
                      .isGreaterThan(0);
            assertThat(tally.torn()).as("slices whose payload belongs to another offset; first: " + tally.firstTorn())
                      .isZero();
        }
    }

    /// The single writer appends `APPENDS` offset-stamped payloads while the reader loops on the tail.
    /// Every read is tallied; a non-`CursorExpired` failure counts as a read that is neither torn nor
    /// expired.
    private Tally raceReaderAgainstWriter(OffHeapRingBuffer buffer, ReadStep step) throws InterruptedException {
        var done = new AtomicBoolean(false);
        var writerFailure = new AtomicReference<String>();
        var writer = Thread.ofPlatform().start(() -> appendStamped(buffer, done, writerFailure));
        var reads = 0L;
        var torn = 0L;
        var expired = 0L;
        var firstTorn = "";

        while (!done.get()) {
            var from = buffer.tailOffset();

            if (from < 0) {
                continue;
            }

            var outcome = step.read(from);

            reads++;
            if (outcome.torn()) {
                torn++;
                if (firstTorn.isEmpty()) {
                    firstTorn = outcome.detail();
                }
            } else if (outcome.expired()) {
                expired++;
            }
        }

        writer.join();
        assertThat(writerFailure.get()).as("the writer must have appended every offset it stamped").isNull();

        return new Tally(reads, torn, expired, firstTorn);
    }

    private static void appendStamped(OffHeapRingBuffer buffer, AtomicBoolean done, AtomicReference<String> failure) {
        try {
            for (long offset = 0; offset < APPENDS; offset++) {
                var expected = offset;
                var appended = buffer.append(stamp(offset), offset);

                if (appended.isFailure() || appended.unwrap() != expected) {
                    failure.set("append of " + expected + " returned " + appended);

                    return;
                }
            }
        } finally {
            done.set(true);
        }
    }

    private static byte[] stamp(long offset) {
        return ByteBuffer.allocate(Long.BYTES)
                         .putLong(offset)
                         .array();
    }

    private static long stampedOffset(byte[] payload) {
        return ByteBuffer.wrap(payload).getLong();
    }

    private static Outcome tallyBatch(OffHeapRingBuffer buffer, long from) {
        return classify(buffer.read(from, BATCH), events -> checkBatch(from, events));
    }

    private static Outcome checkBatch(long from, List<OffHeapRingBuffer.RawEvent> events) {
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            var stamped = stampedOffset(event.data());

            if (event.offset() != from + i || stamped != event.offset()) {
                return Outcome.torn("offset " + event.offset()
                                   + " (requested " + from
                                   + "+" + i
                                   + ") carried " + stamped);
            }
        }

        return Outcome.ALIGNED;
    }

    private static Outcome tallySlice(OffHeapRingBuffer buffer, long from) {
        return classify(buffer.readSlice(from), slice -> checkSlice(from, sliceOffset(slice)));
    }

    private static Outcome checkSlice(long from, long stamped) {
        return stamped == from
               ? Outcome.ALIGNED
               : Outcome.torn("offset " + from + " carried " + stamped);
    }

    private static long sliceOffset(MemorySegment slice) {
        return slice.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0);
    }

    private static <T> Outcome classify(Result<T> result, Fn1<Outcome, T> check) {
        return result.fold(cause -> cause instanceof StreamError.CursorExpired
                                    ? Outcome.EXPIRED
                                    : Outcome.OTHER,
                           check);
    }

    private record Outcome(boolean torn, boolean expired, String detail) {
        static final Outcome ALIGNED = new Outcome(false, false, "");
        static final Outcome EXPIRED = new Outcome(false, true, "");
        static final Outcome OTHER = new Outcome(false, false, "");

        static Outcome torn(String detail) {
            return new Outcome(true, false, detail);
        }
    }

    @FunctionalInterface
    private interface ReadStep {
        Outcome read(long from);
    }
}
