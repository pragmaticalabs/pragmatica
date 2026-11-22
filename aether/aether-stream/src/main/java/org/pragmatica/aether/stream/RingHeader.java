// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

import org.pragmatica.aether.slice.stream.RingGeometry;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.aether.stream.RingStateViolated.ringStateViolated;
import static org.pragmatica.lang.Result.unitResult;


/// The six header words of an [OffHeapRingBuffer]'s control segment, and the ring's tail value R ([RemovalLedger]); the only
/// code that names either. It holds a slice `[0, RingGeometry.HEADER_SIZE)` of the control segment, so no access through it can
/// reach the index. The word at offset 8 is the former tail word: there is no tail word, the tail is a field of R.
///
/// Every word method is exactly one plain load or store; their ordering, fences and locks stay with the caller. R is the one
/// volatile field, and [#publish] is the one place it is assigned: a volatile store, then a store-store fence as the next
/// statement, so a reader that loads a slot, then fences, then loads R cannot see the overwrite and the old R together.
final class RingHeader {
    private static final long HEADER_HEAD_OFFSET = 0;
    private static final long HEADER_EVENT_COUNT = 16;
    private static final long HEADER_DATA_WRITE_POS = 24;
    private static final long HEADER_DATA_SIZE = 32;
    private static final long HEADER_CAPACITY = 40;
    /// Absolute (never wrapped) data position of the tail record's first byte; advances by the evicted
    /// record's length in `OffHeapRingBuffer#evictOldest`. `dataWritePos() - dataTailPos()` is the exact live byte count
    /// (#1340 review M-1: reconstructing it from two ring-relative positions reads an EXACTLY full ring,
    /// `headEnd == tailDataPos`, as empty).
    private static final long HEADER_DATA_TAIL_POS = 48;
    /// The bytes the header words occupy: the end of the last word.
    static final long SIZE = HEADER_DATA_TAIL_POS + Long.BYTES;

    private final MemorySegment header;
    /// The ring's tail value R, published once per change (see [#publish]); `EMPTY` from construction.
    private volatile RemovalLedger ledger = RemovalLedger.EMPTY;

    private RingHeader(MemorySegment header) {
        this.header = header;
    }

    /// The header view of `controlSegment`: its first `RingGeometry.HEADER_SIZE` bytes.
    static RingHeader ringHeader(MemorySegment controlSegment) {
        return ringHeader(controlSegment, RingGeometry.HEADER_SIZE, RingGeometry.HEADER_SIZE);
    }

    /// The header view over the first `headerSize` bytes of `controlSegment`, refused unless the geometry holds every header
    /// word (`headerSize >= SIZE`) and the index starts at or after them (`indexStart >= SIZE`). A mismatch is a programming
    /// error in the geometry constants, and a header over the index would be corrupted by the first append.
    @SuppressWarnings("JBCT-EX-01")
    static RingHeader ringHeader(MemorySegment controlSegment, long headerSize, long indexStart) {
        if (headerSize < SIZE || indexStart < SIZE) {
            throw new IllegalArgumentException("Ring geometry header size " + headerSize
                                              + " / index start " + indexStart
                                              + " is below the " + SIZE
                                              + " bytes of header words");
        }

        return new RingHeader(controlSegment.asSlice(0, headerSize));
    }

    /// The allocation-time header: an empty ring (head `-1`, count and both data positions `0`) of the given data
    /// region size and capacity, stored in this order. R is `EMPTY` from construction.
    @Contract
    void initialize(long dataRegionSize, long capacity) {
        header.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, -1L);
        header.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, 0L);
        header.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, 0L);
        header.set(ValueLayout.JAVA_LONG, HEADER_DATA_TAIL_POS, 0L);
        header.set(ValueLayout.JAVA_LONG, HEADER_DATA_SIZE, dataRegionSize);
        header.set(ValueLayout.JAVA_LONG, HEADER_CAPACITY, capacity);
    }

    long head() {
        return header.get(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET);
    }

    /// R: one volatile load. A reader takes the tail, and the answer below it, from the one value it loaded here.
    RemovalLedger ledger() {
        return ledger;
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

    /// Publish `next` as R: refused unless its tail rose. The store-store fence is the statement right after the one assignment
    /// of R, so the new R is visible before any slot it frees is overwritten (the writer half of the reader's post-copy check).
    Result<Unit> publish(RemovalLedger next) {
        return next.tail() <= ledger.tail()
               ? ringStateViolated("publish of tail " + next.tail() + " does not raise the tail " + ledger.tail()).result()
               : published(next);
    }

    private Result<Unit> published(RemovalLedger next) {
        ledger = next;
        VarHandle.storeStoreFence();

        return unitResult();
    }

    /// The head after a suffix cut: refused unless `keepThrough` lies in `[tail - 1, head]`.
    Result<Unit> cut(long keepThrough) {
        return keepThrough < ledger.tail() - 1 || keepThrough > head()
               ? ringStateViolated("cut to " + keepThrough + " is outside [" + (ledger.tail() - 1) + ", " + head() + "]").result()
               : storedHead(keepThrough);
    }

    private Result<Unit> storedHead(long value) {
        setHead(value);

        return unitResult();
    }

    /// The header after one append: the head, the data write position and the count, refused when the append lies below the
    /// tail.
    Result<Unit> appended(long newHeadOffset, int payloadLength) {
        return newHeadOffset < ledger.tail()
               ? ringStateViolated("append at " + newHeadOffset + " is below the tail " + ledger.tail()).result()
               : storedAppend(newHeadOffset, payloadLength);
    }

    private Result<Unit> storedAppend(long newHeadOffset, int payloadLength) {
        setHead(newHeadOffset);
        setDataWritePos(dataWritePos() + payloadLength);
        setEventCount(eventCount() + 1);

        return unitResult();
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
