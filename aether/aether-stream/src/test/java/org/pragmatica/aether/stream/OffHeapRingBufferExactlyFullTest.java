// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;
import static org.assertj.core.api.Assertions.assertThat;


/// #1340, non-racy neighbour found in review (rev1347 M-1): a data ring that is EXACTLY full with its tail
/// at a non-zero data position. The old space check derived the live byte count from the head record's end
/// and the tail record's start modulo the ring, and `headEnd == tailDataPos` reads as 0 — empty — when the
/// ring is in fact completely full. The next append then evicted nothing and wrote its bytes over the tail
/// record while the tail still pointed at it, so `read(tail)` returned the NEWEST record under the tail's
/// offset: the ticket's exact symptom, with no race and nothing for the post-copy re-check to see.
///
/// Reachable with any fixed payload size that tiles the region (16 B into 4 KiB here) once the region,
/// not the index, is the binding limit. The fix keeps the live byte count exactly (absolute write position
/// minus absolute tail position) instead of reconstructing it from two modular positions.
class OffHeapRingBufferExactlyFullTest {
    /// Index never fills first: the region is the binding limit.
    private static final long CAPACITY = 1024;
    private static final long DATA_REGION = 4096;
    private static final int PAYLOAD = 16;
    private static final long RECORDS_PER_LAP = DATA_REGION / PAYLOAD;

    private static byte[] stamp(long offset) {
        return ByteBuffer.allocate(PAYLOAD)
                         .putLong(offset)
                         .putLong(~offset)
                         .array();
    }

    private static long stamped(byte[] payload) {
        return ByteBuffer.wrap(payload).getLong();
    }

    @Test
    void exactlyFullRing_nextAppendEvictsTheTail_neverOverwritesIt() {
        try (var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION)) {
            appendStamped(buffer, 0, RECORDS_PER_LAP + 2);
            var tail = buffer.tailOffset();

            assertThat(carriedAt(buffer, tail)).as("record read at the tail offset %d carried", tail).isEqualTo(tail);
            assertThat(buffer.eventCount()).as("a full region holds exactly one lap").isEqualTo(RECORDS_PER_LAP);
            assertThat(tail).isEqualTo(2);
        }
    }

    /// A whole lap past the full point: every live record must still decode to its own offset — the
    /// defect overwrote one live record per append until the index-count loop began evicting.
    @Test
    void exactlyFullRing_everyLiveRecordDecodesToItsOwnOffset_acrossAFullLap() {
        try (var buffer = offHeapRingBuffer(CAPACITY, DATA_REGION)) {
            appendStamped(buffer, 0, 2 * RECORDS_PER_LAP + 3);
            var tail = buffer.tailOffset();
            var head = buffer.headOffset();
            var misread = LongStream.rangeClosed(tail, head)
                                    .filter(offset -> carriedAt(buffer, offset) != offset)
                                    .count();

            assertThat(misread).as("live records carrying another offset's stamp").isZero();
            assertThat(head - tail + 1).isEqualTo(RECORDS_PER_LAP);
        }
    }

    private static void appendStamped(OffHeapRingBuffer buffer, long from, long toExclusive) {
        for (long offset = from; offset < toExclusive; offset++) {
            assertThat(buffer.append(stamp(offset), offset).unwrap()).isEqualTo(offset);
        }
    }

    private static long carriedAt(OffHeapRingBuffer buffer, long offset) {
        return buffer.read(offset, 1)
                     .map(OffHeapRingBufferExactlyFullTest::firstStamp)
                     .or(-2L);
    }

    private static long firstStamp(List<OffHeapRingBuffer.RawEvent> events) {
        return events.stream()
                     .findFirst()
                     .map(event -> stamped(event.data()))
                     .orElse(-1L);
    }
}
