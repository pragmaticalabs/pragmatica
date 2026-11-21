// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import org.pragmatica.aether.slice.stream.RingGeometry;
import org.pragmatica.lang.Contract;


/// The seven header words of an [OffHeapRingBuffer]'s control segment, and the only code that names them. It holds a
/// slice `[0, RingGeometry.HEADER_SIZE)` of the control segment, so no access through it can reach the index.
///
/// Every method is exactly one plain load or store. Ordering, fences and the locks that guard these words stay with the
/// caller: this class adds none and removes none.
final class RingHeader {
    private static final long HEADER_HEAD_OFFSET = 0;
    private static final long HEADER_TAIL_OFFSET = 8;
    private static final long HEADER_EVENT_COUNT = 16;
    private static final long HEADER_DATA_WRITE_POS = 24;
    private static final long HEADER_DATA_SIZE = 32;
    private static final long HEADER_CAPACITY = 40;
    /// Absolute (never wrapped) data position of the tail record's first byte; advances by the evicted
    /// record's length in `OffHeapRingBuffer#evictOldest`. `dataWritePos() - dataTailPos()` is the exact live byte count
    /// (#1340 review M-1: reconstructing it from two ring-relative positions reads an EXACTLY full ring,
    /// `headEnd == tailDataPos`, as empty).
    private static final long HEADER_DATA_TAIL_POS = 48;

    private final MemorySegment header;

    private RingHeader(MemorySegment header) {
        this.header = header;
    }

    /// The header view of `controlSegment`: its first `RingGeometry.HEADER_SIZE` bytes.
    static RingHeader ringHeader(MemorySegment controlSegment) {
        return new RingHeader(controlSegment.asSlice(0, RingGeometry.HEADER_SIZE));
    }

    /// The allocation-time header: an empty ring (head `-1`, tail, count and both data positions `0`) of the given data
    /// region size and capacity, stored in this order.
    @Contract
    void initialize(long dataRegionSize, long capacity) {
        header.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, -1L);
        header.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, 0L);
        header.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, 0L);
        header.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, 0L);
        header.set(ValueLayout.JAVA_LONG, HEADER_DATA_TAIL_POS, 0L);
        header.set(ValueLayout.JAVA_LONG, HEADER_DATA_SIZE, dataRegionSize);
        header.set(ValueLayout.JAVA_LONG, HEADER_CAPACITY, capacity);
    }

    long head() {
        return header.get(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET);
    }

    long tail() {
        return header.get(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET);
    }

    long eventCount() {
        return header.get(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT);
    }

    long dataWritePos() {
        return header.get(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS);
    }

    long dataTailPos() {
        return header.get(ValueLayout.JAVA_LONG, HEADER_DATA_TAIL_POS);
    }

    long dataSize() {
        return header.get(ValueLayout.JAVA_LONG, HEADER_DATA_SIZE);
    }

    long capacity() {
        return header.get(ValueLayout.JAVA_LONG, HEADER_CAPACITY);
    }

    @Contract
    void setHead(long value) {
        header.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, value);
    }

    @Contract
    void setTail(long value) {
        header.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, value);
    }

    @Contract
    void setEventCount(long value) {
        header.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, value);
    }

    @Contract
    void setDataWritePos(long value) {
        header.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, value);
    }

    @Contract
    void setDataTailPos(long value) {
        header.set(ValueLayout.JAVA_LONG, HEADER_DATA_TAIL_POS, value);
    }
}
