// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.RemovalCause;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// The ring answers a read below its tail from the one R it loaded and from the [ReadAs] the caller holds (M-2r-1, rulings 2,
/// 3, 6 and 7), the ring refuses a step that would break its own invariants (ruling 9), and the tail reader keeps #999's close
/// contract (ruling 5).
class OffHeapRingBufferRemovalAnswersTest {
    private static final String STREAM = "answers";
    private static final long DATA_REGION = 4096;

    /// MEMORY: a read below the tail over two removal causes names both in `causes`, and the cause covering the offset itself.
    @Test
    void memoryRing_readBelowTheTailOverTwoCauses_namesBothCauses() {
        try (var ring = memoryRing(8, EvictionListener.NOOP)) {
            appendEvents(ring, 6);
            ring.advanceTail(2, RemovalCause.AGE).unwrap();
            ring.advanceTail(4, RemovalCause.BYTES).unwrap();

            var readSlice = expired(ring.readSlice(1));
            var readAppended = expired(ring.readAppended(1, 2));

            assertThat(readSlice.cause()).isEqualTo(RemovalCause.AGE);
            assertThat(readSlice.causes()).containsExactlyInAnyOrder(RemovalCause.AGE, RemovalCause.BYTES);
            assertThat(readSlice.tailOffset()).isEqualTo(4L);
            assertThat(readAppended.causes()).containsExactlyInAnyOrder(RemovalCause.AGE, RemovalCause.BYTES);
            assertThat(expired(ring.readSlice(3)).causes()).containsExactly(RemovalCause.BYTES);
        }
    }

    /// The causes at the eviction sites reached through an append: the entry-count capacity forces out `COUNT`.
    @Test
    void memoryRing_appendPastCapacity_namesCount() {
        try (var ring = memoryRing(4, EvictionListener.NOOP)) {
            appendEvents(ring, 6);

            var refused = expired(ring.readSlice(0));

            assertThat(refused.cause()).isEqualTo(RemovalCause.COUNT);
            assertThat(refused.causes()).containsExactly(RemovalCause.COUNT);
            assertThat(refused.tailOffset()).isEqualTo(2L);
        }
    }

    /// Below `firstHeld` the owner is told `LIFE_LOST` from the seed run; `COPY` is told where this copy began.
    @Test
    void memoryRing_readBelowFirstHeld_isLifeLostForTheOwnerAndBelowCopyTailForCopy() {
        try (var ring = memoryRing(8, EvictionListener.NOOP)) {
            ring.seedHead(9).unwrap();
            appendEvents(ring, 2);

            var owner = expired(ownerRead(ring, 3));
            var copy = failureOf(ring.read(3, 4, ReadAs.COPY));

            assertThat(owner.reason()).isEqualTo(StreamError.CursorExpired.Reason.LOST);
            assertThat(owner.cause()).isEqualTo(RemovalCause.LIFE_LOST);
            assertThat(copy).isEqualTo(new StreamError.BelowCopyTail(10, 10));
            assertThat(failureOf(ring.readSlice(3))).isEqualTo(new StreamError.BelowCopyTail(10, 10));
        }
    }

    /// A role bound to another ring is refused, typed (not `BUFFER_CLOSED`) and counted at the raise site.
    @Test
    void read_withARoleBoundToAnotherRing_isRefusedTypedAndCounted() {
        try (var ring = memoryRing(8, EvictionListener.NOOP);
             var other = memoryRing(8, EvictionListener.NOOP)) {
            appendEvents(ring, 2);

            var cause = failureOf(ReadAs.readingAs(STREAM, 0, other.incarnation(), Result::unitResult, role -> ring.read(0, 2, role)));

            assertThat(cause).isInstanceOf(RingStateViolated.class).isNotEqualTo(StreamError.General.BUFFER_CLOSED);
            assertThat(ring.stateViolationCount()).isEqualTo(1L);
            assertThat(ownerRead(ring, 0).isSuccess()).as("the ring's own role still reads").isTrue();
        }
    }

    /// PERSISTENT answers byte-equal to the tip at all three sites: `CursorExpired(offset, tail, RETENTION)`, whatever evicted.
    /// Mutant: drop the mode check, which answers COUNT here.
    @Test
    void persistentRing_answersRetentionAtTheThreeSites() {
        try (var ring = OffHeapRingBuffer.offHeapRingBuffer(4, DATA_REGION)) {
            appendEvents(ring, 6);
            ring.advanceVisible(5);

            var expected = new StreamError.CursorExpired(0, 2, RemovalCause.RETENTION);

            assertThat(failureOf(ring.read(0, 2))).isEqualTo(expected);
            assertThat(failureOf(ring.readAppended(0, 2))).isEqualTo(expected);
            assertThat(failureOf(ring.readSlice(0))).isEqualTo(expected);
        }
    }

    /// `advanceTail` refuses `to > head + 1` BEFORE the hand-over: the listener is called zero times, nothing moved, and the
    /// refusal is a counted `RingStateViolated`, not `BUFFER_CLOSED`. Mutant: the check after the hand-over, red (calls == 1).
    @Test
    void advanceTail_pastHeadPlusOne_isRefusedBeforeTheHandOver() {
        var handedOver = new AtomicInteger();

        try (var ring = memoryRing(8, countingListener(handedOver))) {
            appendEvents(ring, 3);

            var cause = failureOf(ring.advanceTail(5, RemovalCause.AGE));

            assertThat(cause).isInstanceOf(RingStateViolated.class).isNotEqualTo(StreamError.General.BUFFER_CLOSED);
            assertThat(handedOver).as("listener calls").hasValue(0);
            assertThat(ring.stateViolationCount()).isEqualTo(1L);
            assertThat(ring.tailOffset()).as("not closed, tail unmoved").isEqualTo(0L);

            assertThat(ring.advanceTail(3, RemovalCause.AGE).isSuccess()).as("head + 1 is allowed").isTrue();
            assertThat(handedOver).as("one hand-over for the valid move").hasValue(1);
            assertThat(failureOf(ring.advanceTail(3, RemovalCause.AGE))).as("not above the tail").isInstanceOf(RingStateViolated.class);
            assertThat(handedOver).hasValue(1);
        }
    }

    /// After a suffix cut the head is the keep point (plant 5: a store of -1).
    @Test
    void truncateSuffix_setsTheHeadToTheKeepPoint() {
        try (var ring = memoryRing(8, EvictionListener.NOOP)) {
            appendEvents(ring, 6);

            ring.truncateSuffix(2).unwrap();

            assertThat(ring.headOffset()).isEqualTo(2L);
            assertThat(ring.stateViolationCount()).isZero();
        }
    }

    /// The close race's deterministic arm: the probe closes the ring inside the read window, before R is loaded; the tail
    /// reader re-checks `closed` after the load, returns the refusal encoding and counts the race once. Mutant: drop the
    /// re-check, which answers R's tail with count 0.
    @Test
    void tailOffset_closedInsideTheReadWindow_isRefusedAndCountedOnce() {
        var ring = memoryRing(8, EvictionListener.NOOP);

        appendEvents(ring, 2);
        ring.readWindowProbe(ring::close);

        assertThat(ring.tailOffset()).isEqualTo(-1L);
        assertThat(ring.closedUnderReaderCount()).isEqualTo(1L);
    }

    private static OffHeapRingBuffer memoryRing(long capacity, EvictionListener listener) {
        return OffHeapRingBuffer.offHeapRingBuffer(OffHeapRingBuffer.Mode.MEMORY,
                                                   STREAM,
                                                   0,
                                                   capacity,
                                                   DATA_REGION,
                                                   0L,
                                                   listener,
                                                   EvictionPolicy.DROP_OLDEST);
    }

    private static EvictionListener countingListener(AtomicInteger calls) {
        return (stream, partition, events) -> {
            calls.incrementAndGet();
            return Result.unitResult();
        };
    }

    private static void appendEvents(OffHeapRingBuffer ring, int count) {
        for (var i = 0; i < count; i++) {
            ring.append(("e" + i).getBytes(StandardCharsets.UTF_8), i).unwrap();
        }
    }

    private static Result<List<OffHeapRingBuffer.RawEvent>> ownerRead(OffHeapRingBuffer ring, long from) {
        return ReadAs.readingAs(STREAM, 0, ring.incarnation(), Result::unitResult, role -> ring.read(from, 4, role));
    }

    private static StreamError.CursorExpired expired(Result<?> result) {
        return (StreamError.CursorExpired) failureOf(result);
    }

    private static Cause failureOf(Result<?> result) {
        var seen = new AtomicReference<Cause>();

        result.onFailure(seen::set);

        assertThat(seen.get()).as("the read was refused").isNotNull();

        return seen.get();
    }
}
