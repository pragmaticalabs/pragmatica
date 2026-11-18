// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.RingGeometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

/// S2b-1: the ring's first data region holds one maximal event (T-218), the index-capacity bound moves with it (T-267),
/// and the arities without `maxEventSize` keep today's values (the T-08 arithmetic, by hand until S0-3 lands it).
class OffHeapRingBufferFirstRegionTest {
    private static final long ENTRIES = 16_384L;
    private static final long KIB = 1024L;
    private static final long MIB = 1024L * KIB;

    /// T-218: the floor holds one maximal event; the mutants are `min(256 KiB, region)` (first row 655 424) and a dropped
    /// 256 KiB term (second row 458 816).
    @Test
    void floorBytes_holdsOneMaximalEvent() {
        assertThat(OffHeapRingBuffer.floorBytes(ENTRIES, 4 * MIB, MIB)).isEqualTo(1_441_856L);
        assertThat(OffHeapRingBuffer.floorBytes(ENTRIES, 4 * MIB, 64 * KIB)).isEqualTo(655_424L);
        assertThat(OffHeapRingBuffer.floorBytes(ENTRIES, 128 * KIB, 64 * KIB)).isEqualTo(524_352L);
    }

    /// T-08 by hand (S0-3 is not on this base): the arities without `maxEventSize` mean `maxEventSize = 0`.
    @Test
    void floorBytes_withoutEventSize_keepsTodaysValues() {
        assertThat(OffHeapRingBuffer.floorBytes(ENTRIES, 4 * MIB)).isEqualTo(655_424L);
        assertThat(OffHeapRingBuffer.floorBytes(ENTRIES, 128 * KIB)).isEqualTo(524_352L);
        assertThat(OffHeapRingBuffer.capBytes(ENTRIES, 4 * MIB)).isEqualTo(4_587_584L);
    }

    @Test
    void allocatedBytes_atCreation_equalsTheFloor() {
        var oldArity = offHeapRingBuffer("old", 0, 100, 4 * MIB, EvictionListener.NOOP);
        var newArity = offHeapRingBuffer("new", 0, 100, 4 * MIB, MIB, EvictionListener.NOOP, EvictionPolicy.DROP_OLDEST);

        try {
            assertThat(oldArity.allocatedBytes()).isEqualTo(OffHeapRingBuffer.floorBytes(100, 4 * MIB));
            assertThat(newArity.allocatedBytes()).isEqualTo(OffHeapRingBuffer.floorBytes(100, 4 * MIB, MIB));
            assertThat(newArity.allocatedBytes() - oldArity.allocatedBytes()).isEqualTo(MIB - 256 * KIB);
        } finally {
            oldArity.close();
            newArity.close();
        }
    }

    /// T-267 (U): the bound moves with the first segment; the check admits `maxCapacity(1 MiB)` and `floorBytes` stays
    /// positive for it; `MAX_CAPACITY` is the 256 KiB case. Mutant: keep the constant in the check.
    @Test
    void maxCapacity_movesWithTheFirstSegment() {
        var atOneMiB = RingGeometry.maxCapacity(RingGeometry.firstSegmentBytes(16 * MIB, MIB));

        assertThat(atOneMiB).isEqualTo(384_307_168_202_238_631L).isEqualTo(OffHeapRingBuffer.MAX_CAPACITY - 32_768L);
        assertThat(RingGeometry.maxCapacity(256 * KIB)).isEqualTo(384_307_168_202_271_399L).isEqualTo(OffHeapRingBuffer.MAX_CAPACITY);
        assertThat(RingGeometry.indexable(atOneMiB, MIB)).isTrue();
        assertThat(OffHeapRingBuffer.floorBytes(atOneMiB, 16 * MIB, MIB)).isPositive();
        assertThat(RingGeometry.indexable(OffHeapRingBuffer.MAX_CAPACITY, MIB)).isFalse();
        assertThat(OffHeapRingBuffer.floorBytes(OffHeapRingBuffer.MAX_CAPACITY, 16 * MIB, MIB)).isNegative();
    }

    /// The [plan-added] addressing test: a 1 MiB first region (four default-size segments) with events straddling every
    /// region boundary, those inside the first region and those of the segments grown after it, reads back byte-identical.
    /// Mutant: allocate the first region as one segment while addressing stays `position / 256 KiB`.
    @Test
    void firstRegionAboveOneSegment_eventsStraddlingEveryBoundary_readBackIdentical() {
        var length = 100_003;
        var events = IntStream.range(0, 20).mapToObj(index -> patterned(length, index)).toList();
        var buffer = offHeapRingBuffer("addressing", 0, 64, 2 * MIB, MIB, EvictionListener.NOOP, EvictionPolicy.DROP_OLDEST);

        try {
            assertThat(buffer.allocatedBytes()).isEqualTo(OffHeapRingBuffer.floorBytes(64, 2 * MIB, MIB));
            events.forEach(event -> buffer.append(event, 1L).onFailure(cause -> fail(cause.message())));
            assertThat(buffer.allocatedBytes()).isEqualTo(OffHeapRingBuffer.capBytes(64, 2 * MIB));
            assertThat(boundariesStraddled(events.size() * (long) length)).isEqualTo(events.size() * (long) length / (256 * KIB));
            buffer.read(0, events.size())
                  .onFailure(cause -> fail(cause.message()))
                  .onSuccess(read -> {
                      assertThat(read).hasSize(events.size());
                      IntStream.range(0, events.size())
                               .forEach(index -> assertThat(read.get(index).data()).isEqualTo(events.get(index)));
                  });
        } finally {
            buffer.close();
        }
    }

    /// Every multiple of 256 KiB below `total` lies strictly inside some fixed-length event (none ends exactly on one).
    private static long boundariesStraddled(long total) {
        return IntStream.rangeClosed(1, (int) (total / (256 * KIB)))
                        .filter(boundary -> boundary * 256 * KIB % 100_003L != 0)
                        .count();
    }

    private static byte[] patterned(int length, int seed) {
        var bytes = new byte[length];

        IntStream.range(0, length).forEach(index -> bytes[index] = (byte) (index * 31 + seed));

        return bytes;
    }
}
