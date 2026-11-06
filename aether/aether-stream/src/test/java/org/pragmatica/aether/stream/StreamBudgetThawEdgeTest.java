// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #2201 C-03 (spec 4.4 item 4) boundary pins for the thaw of a frozen ring (v-2547).
class StreamBudgetThawEdgeTest {
    private static final long SEGMENT = OffHeapRingBuffer.DEFAULT_SEGMENT_BYTES;
    private static final int RECORD = 4096;

    /// A frozen 256 KiB allocation holding 63 records of 4096 B and one of 4096 + `straddle` B has its newest record laid
    /// across the wrap point by `straddle` bytes, so the live bytes end `straddle` bytes past the allocation and are WRAPPED.
    /// A thaw that re-bases at that moment leaves the straddler's last bytes at physical position 0, which the grown ring no
    /// longer reads.
    @Test
    void aRecordStraddlingTheWrapPointByAFewBytes_isNotMistakenForAnUnwrappedRing_whenTheBudgetIsReleased() {
        for (var straddle = 1; straddle <= 3; straddle++) {
            var tokens = new AtomicLong(0);
            var generation = new AtomicLong(0);
            var evicted = new CopyOnWriteArrayList<RawEvent>();
            var ring = ring(evicted, bytes -> take(tokens, bytes), generation);
            var next = 0L;

            for (var i = 0; i < 63; i++, next++) {
                assertThat(ring.append(payload(next, RECORD), next).isSuccess()).isTrue();
            }
            assertThat(ring.append(payload(next, RECORD + straddle), next++).isSuccess()).isTrue();
            tokens.set(2 * SEGMENT);
            generation.incrementAndGet();
            for (var i = 0; i < 70; i++, next++) {
                assertThat(ring.append(payload(next, RECORD), next).isSuccess()).isTrue();
            }

            List<RawEvent> live = ring.readAppended(ring.tailOffset(), 1000).unwrap();

            assertThat(live).as("straddle %d: the live events", straddle).allMatch(StreamBudgetThawEdgeTest::readsAsWritten);
            assertThat(evicted).as("straddle %d: the handed-over events", straddle)
                               .allMatch(StreamBudgetThawEdgeTest::readsAsWritten);
            ring.close();
        }
    }

    /// A release that lands after the ring read the generation but before it recorded the refusal (a destroy on another
    /// thread, here injected inside the refused reservation) must still thaw the ring: the ring remembers the generation it
    /// read BEFORE asking, so the release has moved it. Remembering the generation read after the refusal swallows that
    /// release, and the ring stays frozen with the freed budget unused until some later release.
    @Test
    void aReleaseLandingBetweenTheGenerationReadAndTheRefusal_stillThawsTheRing() {
        var tokens = new AtomicLong(0);
        var generation = new AtomicLong(0);
        var releasedDuringRefusal = new AtomicBoolean();
        LongPredicate reserve = bytes -> {
            if (take(tokens, bytes)) {
                return true;
            }
            if (!releasedDuringRefusal.getAndSet(true)) {
                tokens.set(2 * SEGMENT);
                generation.incrementAndGet();
            }

            return false;
        };
        var ring = ring(new CopyOnWriteArrayList<>(), reserve, generation);

        for (var next = 0L; next < 200; next++) {
            assertThat(ring.append(payload(next, RECORD), next).isSuccess()).isTrue();
        }

        assertThat(releasedDuringRefusal).as("the growth request was refused once").isTrue();
        assertThat(tokens.get()).as("the ring regrew into the budget released during its refusal").isLessThan(2 * SEGMENT);
        assertThat(ring.readAppended(ring.tailOffset(), 1000).unwrap()).allMatch(StreamBudgetThawEdgeTest::readsAsWritten);
        ring.close();
    }

    /// After a thaw re-bases the ring, a BATCH append must write at the re-based physical position too: records of 5000 B
    /// put the base off any segment boundary, so a batch written at the un-based position lands on live records.
    @Test
    void batchAppendsAfterAThaw_writeAtTheRebasedPosition_andEveryLiveEventReadsBackAsWritten() {
        var tokens = new AtomicLong(0);
        var generation = new AtomicLong(0);
        var evicted = new CopyOnWriteArrayList<RawEvent>();
        var ring = ring(evicted, bytes -> take(tokens, bytes), generation);
        var next = 0L;

        for (var i = 0; i < 200; i++, next++) {
            assertThat(ring.append(payload(next, 5000), next).isSuccess()).isTrue();
        }
        var frozenAt = ring.allocatedBytes();

        tokens.set(4 * SEGMENT);
        generation.incrementAndGet();
        for (var i = 0; i < 150; i++) {
            var batch = List.of(payload(next, 5000), payload(next + 1, 5000), payload(next + 2, 5000));

            assertThat(ring.appendBatch(batch, new long[]{next, next + 1, next + 2}).isSuccess()).isTrue();
            next += 3;
            assertThat(ring.readAppended(ring.tailOffset(), 1000).unwrap()).as("live events after batch ending at %d", next - 1)
                                                                           .allMatch(StreamBudgetThawEdgeTest::readsAsWritten);
        }

        assertThat(ring.allocatedBytes()).as("the ring thawed and grew past %d", frozenAt).isGreaterThan(frozenAt);
        assertThat(evicted).allMatch(StreamBudgetThawEdgeTest::readsAsWritten);
        ring.close();
    }

    private static OffHeapRingBuffer ring(List<RawEvent> evicted, LongPredicate reserve, AtomicLong generation) {
        return OffHeapRingBuffer.offHeapRingBuffer("edge",
                                                   0,
                                                   1000,
                                                   8 * SEGMENT,
                                                   (_, _, events) -> {
                                                       evicted.addAll(events);

                                                       return Result.<Unit> unitResult();
                                                   },
                                                   EvictionPolicy.DROP_OLDEST,
                                                   reserve,
                                                   _ -> generation.incrementAndGet(),
                                                   generation::get)
                                .unwrap();
    }

    private static boolean take(AtomicLong tokens, long bytes) {
        var available = tokens.get();

        return available >= bytes && tokens.compareAndSet(available, available - bytes);
    }

    private static byte[] payload(long offset, int length) {
        var bytes = new byte[length];

        for (var i = 0; i < length; i++) {
            bytes[i] = (byte) (offset * 31 + i * 7);
        }

        return bytes;
    }

    private static boolean readsAsWritten(RawEvent event) {
        var data = event.data();

        for (var i = 0; i < data.length; i++) {
            if (data[i] != (byte) (event.offset() * 31 + i * 7)) {
                return false;
            }
        }

        return true;
    }
}
