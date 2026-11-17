// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

/// The geometry of a stream partition's off-heap ring, defined once: the control-region sizes, the data-segment unit,
/// the first data region, the index-capacity bound that moves with it, and the hot-window default.
///
/// The ring (`OffHeapRingBuffer`) delegates every one of these numbers here, and the parsers and declaration defaults
/// that must agree with it read them from here, so no second copy of the arithmetic can drift.
///
/// FIRST DATA REGION. A ring's reserved data region is `min(dataRegion, max(256 KiB, maxEventSize))`, so one maximal event
/// always fits it (a ring refused growth freezes at its allocation, and an event larger than the allocation is dropped).
/// A region above one segment unit is allocated as default-size segments, the last clamped so the total never exceeds the
/// data region, which keeps the ring's `position / unit` addressing valid. The region is therefore rounded UP to a whole
/// unit (clamped by the data region) when `maxEventSize` falls between units; a region that is at most one unit, or the
/// whole data region, is exactly that size. The floor a ring reserves equals what it allocates.
///
/// `maxEventSize = 0` means "no event-size term": the region is `min(dataRegion, 256 KiB)`, the geometry of every ring built
/// before the event size reached the ring.
public sealed interface RingGeometry {
    /// Bytes of the control header at the start of the control region.
    long HEADER_SIZE = 64L;
    /// Bytes of one index slot.
    long INDEX_ENTRY_SIZE = 24L;
    /// The data-segment unit: the region grows one unit at a time toward the data region.
    long SEGMENT_BYTES = 256 * 1024L;
    /// The hot window's default entry count per partition copy.
    long HOT_WINDOW_ENTRIES = 16_384L;
    /// The hot window's default byte floor; the window is `max(this, maxEventSize)` bytes.
    long HOT_WINDOW_MIN_BYTES = 4L * 1024 * 1024;

    /// The data region a ring reserves at creation, in bytes. See the type javadoc for the rounding rule.
    static long firstSegmentBytes(long dataRegion, long maxEventSize) {
        var wanted = Math.min(dataRegion, Math.max(SEGMENT_BYTES, maxEventSize));

        return wanted <= SEGMENT_BYTES
               ? wanted
               : roundedUp(wanted, dataRegion);
    }

    private static long roundedUp(long wanted, long dataRegion) {
        var padding = (SEGMENT_BYTES - wanted % SEGMENT_BYTES) % SEGMENT_BYTES;

        return dataRegion - wanted <= padding
               ? dataRegion
               : wanted + padding;
    }

    /// The number of default-size segments the first region is allocated as: the last one clamped to what remains.
    static int firstSegmentCount(long firstSegmentBytes) {
        return (int) Math.ceilDiv(firstSegmentBytes, SEGMENT_BYTES);
    }

    /// The size of the `index`-th segment of a first region of `firstSegmentBytes`.
    static long firstSegmentSize(long firstSegmentBytes, int index) {
        return Math.min(SEGMENT_BYTES, firstSegmentBytes - index * SEGMENT_BYTES);
    }

    /// The largest capacity whose control region plus first data region is representable in a `long`: beyond it
    /// [#floorBytes] overflows and the ring would be built over a truncated index (#1549).
    static long maxCapacity(long firstSegmentBytes) {
        return (Long.MAX_VALUE - HEADER_SIZE - firstSegmentBytes) / INDEX_ENTRY_SIZE;
    }

    /// Whether a ring of `capacity` entries over a first region of `firstSegmentBytes` can be indexed.
    static boolean indexable(long capacity, long firstSegmentBytes) {
        return capacity <= maxCapacity(firstSegmentBytes);
    }

    /// Bytes a ring reserves at creation: control region (header + index) plus the first data region.
    static long floorBytes(long capacity, long maxBytes, long maxEventSize) {
        return HEADER_SIZE + INDEX_ENTRY_SIZE * capacity + firstSegmentBytes(maxBytes, maxEventSize);
    }

    /// The hot window's default byte bound for a stream whose events are up to `maxEventSize`: never smaller than one event.
    static long hotWindowBytes(long maxEventSize) {
        return Math.max(HOT_WINDOW_MIN_BYTES, maxEventSize);
    }

    record unused() implements RingGeometry {}
}
