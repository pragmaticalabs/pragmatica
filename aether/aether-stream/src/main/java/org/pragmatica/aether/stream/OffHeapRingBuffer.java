// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.TierAwareRetention;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

import static org.pragmatica.lang.Result.success;


public final class OffHeapRingBuffer implements AutoCloseable {
    private static final long HEADER_HEAD_OFFSET = 0;

    private static final long HEADER_TAIL_OFFSET = 8;

    private static final long HEADER_EVENT_COUNT = 16;

    private static final long HEADER_DATA_WRITE_POS = 24;

    private static final long HEADER_DATA_SIZE = 32;

    private static final long HEADER_CAPACITY = 40;

    private static final long HEADER_SIZE = 64;

    private static final long INDEX_ENTRY_SIZE = 24;

    private static final long INDEX_DATA_OFFSET = 0;

    private static final long INDEX_DATA_LENGTH = 8;

    private static final long INDEX_TIMESTAMP = 16;

    /// Fixed data-segment granule. The data region grows one segment at a time toward the cap
    /// (`dataRegionSize`). The last logical segment is clamped so total allocated data never
    /// exceeds the cap. See spec §4.2.
    static final long DEFAULT_SEGMENT_BYTES = 256 * 1024L;

    /// Always-admit growth predicate (default seam — standalone buffers grow without budget gating).
    private static final LongPredicate ALWAYS_ADMIT = _ -> true;

    /// No-op release (default seam).
    private static final LongConsumer NOOP_RELEASE = _ -> {};

    private final Arena arena;
    private final MemorySegment controlSegment;
    private final List<MemorySegment> dataSegments;
    private final long capacity;
    private final long dataRegionSize;
    private final long indexStart;
    private final String streamName;
    private final int partition;
    private final EvictionListener listener;
    private final EvictionPolicy evictionPolicy;
    private final LongPredicate reserve;
    private final LongConsumer release;

    /// Live count of allocated data bytes (Σ dataSegments sizes), always <= dataRegionSize cap.
    private long allocatedDataBytes;

    /// Once a DROP_OLDEST stream is refused growth and starts wrapping within its allocated bytes,
    /// the data ring is frozen at that size: it must never grow again, otherwise an already-wrapped
    /// event (laid out under the smaller ring) would be misread under a larger modulus. This matches
    /// the spec's "behaves like a fixed ring at its current allocated size" + no-reclamation
    /// decisions (§4.2, §10).
    private boolean growthFrozen = false;

    /// Bytes accounted against the seam over this buffer's lifetime (released on close()).
    private long accountedBytes;

    private final List<LongConsumer> appendListeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile long lastSealedOffset = - 1;

    private OffHeapRingBuffer(Arena arena,
                             MemorySegment controlSegment,
                             MemorySegment firstDataSegment,
                             long firstDataSegmentBytes,
                             long capacity,
                             long dataRegionSize,
                             String streamName,
                             int partition,
                             EvictionListener listener,
                             EvictionPolicy evictionPolicy,
                             LongPredicate reserve,
                             LongConsumer release,
                             long accountedBytes) {
        this.arena = arena;
        this.controlSegment = controlSegment;
        this.dataSegments = new ArrayList<>();
        this.dataSegments.add(firstDataSegment);
        this.allocatedDataBytes = firstDataSegmentBytes;
        this.capacity = capacity;
        this.dataRegionSize = dataRegionSize;
        this.indexStart = HEADER_SIZE;
        this.streamName = streamName;
        this.partition = partition;
        this.listener = listener;
        this.evictionPolicy = evictionPolicy;
        this.reserve = reserve;
        this.release = release;
        this.accountedBytes = accountedBytes;
    }

    public static OffHeapRingBuffer offHeapRingBuffer(long capacity, long dataRegionSize) {
        return offHeapRingBuffer("", 0, capacity, dataRegionSize, EvictionListener.NOOP, EvictionPolicy.DROP_OLDEST);
    }

    public static OffHeapRingBuffer offHeapRingBuffer(String streamName,
                                                     int partition,
                                                     long capacity,
                                                     long dataRegionSize,
                                                     EvictionListener listener) {
        return offHeapRingBuffer(streamName, partition, capacity, dataRegionSize, listener, EvictionPolicy.DROP_OLDEST);
    }

    public static OffHeapRingBuffer offHeapRingBuffer(String streamName,
                                                     int partition,
                                                     long capacity,
                                                     long dataRegionSize,
                                                     EvictionListener listener,
                                                     EvictionPolicy policy) {
        return offHeapRingBuffer(streamName, partition, capacity, dataRegionSize, listener, policy, ALWAYS_ADMIT, NOOP_RELEASE);
    }

    /// Floor bytes a buffer reserves at creation: control region (header + index) plus the first
    /// data segment (`min(DEFAULT_SEGMENT_BYTES, maxBytes)`). See spec §4.0.
    public static long floorBytes(long capacity, long maxBytes) {
        return HEADER_SIZE + INDEX_ENTRY_SIZE * capacity + firstSegmentBytes(maxBytes);
    }

    /// Total bytes if the buffer grows to the retention cap: control region plus the full data
    /// region. Equals today's per-partition full allocation. See spec §4.0.
    public static long capBytes(long capacity, long maxBytes) {
        return HEADER_SIZE + INDEX_ENTRY_SIZE * capacity + maxBytes;
    }

    /// Factory with an injected growth-admission seam. `reserve` is consulted (and on success has
    /// already reserved the bytes) before each data-segment allocation; `release` returns bytes to
    /// the pool. The control region and the first data segment (the floor) are accounted against the
    /// seam at construction. See spec §4.3.
    public static OffHeapRingBuffer offHeapRingBuffer(String streamName,
                                                     int partition,
                                                     long capacity,
                                                     long dataRegionSize,
                                                     EvictionListener listener,
                                                     EvictionPolicy policy,
                                                     LongPredicate reserve,
                                                     LongConsumer release) {
        var arena = Arena.ofShared();
        var indexSize = INDEX_ENTRY_SIZE * capacity;
        var controlSize = HEADER_SIZE + indexSize;
        var firstSegmentBytes = firstSegmentBytes(dataRegionSize);
        var controlSegment = arena.allocate(controlSize, 64);
        var firstDataSegment = arena.allocate(firstSegmentBytes, 64);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, - 1L);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, 0L);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, 0L);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, 0L);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_DATA_SIZE, dataRegionSize);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_CAPACITY, capacity);
        return new OffHeapRingBuffer(arena,
                                     controlSegment,
                                     firstDataSegment,
                                     firstSegmentBytes,
                                     capacity,
                                     dataRegionSize,
                                     streamName,
                                     partition,
                                     listener,
                                     policy,
                                     reserve,
                                     release,
                                     firstSegmentBytes);
    }

    private static long firstSegmentBytes(long dataRegionSize) {
        return Math.min(DEFAULT_SEGMENT_BYTES, dataRegionSize);
    }

    public Result<Long> append(byte[] payload, long timestamp) {
        if (closed.get()) {return StreamError.General.BUFFER_CLOSED.result();}
        if (payload.length > dataRegionSize) {return new StreamError.EventTooLarge(payload.length, dataRegionSize).result();}
        return ensureGrownFor(payload.length).flatMap(_ -> appendWritten(payload, timestamp));
    }

    /// Runs AFTER growth so the REJECT_WHEN_FULL fullness check is evaluated against the grown
    /// allocation: a STRONG stream only reports BUFFER_FULL when it genuinely cannot fit even after
    /// growing to the cap. Seam-rejected growth has already returned STREAM_MEMORY_EXCEEDED upstream
    /// (in `ensureGrownFor`). See spec §4.2.
    private Result<Long> appendWritten(byte[] payload, long timestamp) {
        if (evictionPolicy == EvictionPolicy.REJECT_WHEN_FULL && countEvictionsForSpace(payload.length) > 0) {return StreamError.General.BUFFER_FULL.result();}
        evictForSpace(payload.length);
        var currentHead = headOffset();
        var newOffset = currentHead + 1;
        var slotIndex = Math.floorMod(newOffset, capacity);
        var dataPos = Math.floorMod(dataWritePos(), dataRing());
        writeDataBytes(dataPos, payload);
        writeIndexEntry(slotIndex, dataPos, payload.length, timestamp);
        updateHeaderAfterAppend(newOffset, payload.length);
        notifyAppendListeners(newOffset);
        return success(newOffset);
    }

    public Result<Long> appendBatch(List<byte[]> payloads, long[] timestamps) {
        if (closed.get()) {return StreamError.General.BUFFER_CLOSED.result();}
        if (payloads.isEmpty()) {return success(headOffset());}
        var totalSize = totalPayloadSize(payloads);
        if (totalSize > dataRegionSize) {return new StreamError.EventTooLarge((int) totalSize, dataRegionSize).result();}
        return ensureGrownFor((int) totalSize).flatMap(_ -> appendBatchWritten(payloads, timestamps));
    }

    private Result<Long> appendBatchWritten(List<byte[]> payloads, long[] timestamps) {
        var totalSize = totalPayloadSize(payloads);
        if (evictionPolicy == EvictionPolicy.REJECT_WHEN_FULL && countEvictionsForSpace((int) totalSize) > 0) {return StreamError.General.BUFFER_FULL.result();}
        evictForSpace((int) totalSize);
        var lastOffset = appendPayloads(payloads, timestamps);
        notifyAppendListeners(lastOffset);
        return success(lastOffset);
    }

    /// Grow the data region until it can hold a write of `writeLen` bytes at the current logical
    /// write position, or until the cap is reached. A write spanning the logical ring-wrap point
    /// only happens at full cap, so growth (which always precedes wrap) needs to cover the highest
    /// linear position the next write touches: `floorMod(dataWritePos, dataRegionSize) + writeLen`,
    /// clamped to the cap. See spec §4.2.
    private Result<Long> ensureGrownFor(int writeLen) {
        var dataPos = Math.floorMod(dataWritePos(), dataRegionSize);
        var requiredLinearEnd = Math.min(dataPos + writeLen, dataRegionSize);
        return growUntil(requiredLinearEnd);
    }

    /// Grow until allocation reaches the required linear end or the cap, OR a growth attempt makes
    /// no progress (DROP_OLDEST stream whose seam rejected the pool — it will fall back to eviction
    /// in the write path). A STRONG (REJECT_WHEN_FULL) seam rejection short-circuits to a failure.
    private Result<Long> growUntil(long requiredAllocatedBytes) {
        if (allocatedDataBytes >= requiredAllocatedBytes || allocatedDataBytes >= dataRegionSize || growthFrozen) {
            return success(allocatedDataBytes);
        }
        var before = allocatedDataBytes;
        return growOneSegment().flatMap(after -> continueGrowth(requiredAllocatedBytes, before, after));
    }

    private Result<Long> continueGrowth(long requiredAllocatedBytes, long before, long after) {
        if (after > before) {
            return growUntil(requiredAllocatedBytes);
        }
        return success(after);
    }

    private Result<Long> growOneSegment() {
        var nextSegmentBytes = Math.min(DEFAULT_SEGMENT_BYTES, dataRegionSize - allocatedDataBytes);
        if (!reserve.test(nextSegmentBytes)) {
            return rejectGrowth();
        }
        return allocateGuarded(nextSegmentBytes).onSuccess(this::attachSegment)
                                                .map(_ -> allocatedDataBytes)
                                                .onFailure(_ -> release.accept(nextSegmentBytes));
    }

    /// DROP_OLDEST: freeze the ring at the current allocation and return it so `growUntil` halts and
    /// the caller falls back to eviction within the frozen ring. REJECT_WHEN_FULL: loud failure.
    /// See spec §4.2.
    private Result<Long> rejectGrowth() {
        if (evictionPolicy == EvictionPolicy.REJECT_WHEN_FULL) {
            return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
        }
        growthFrozen = true;
        return success(allocatedDataBytes);
    }

    @Contract private void attachSegment(MemorySegment segment) {
        dataSegments.add(segment);
        allocatedDataBytes += segment.byteSize();
        accountedBytes += segment.byteSize();
    }

    /// The single justified try/catch in this file: native off-heap allocation (`Arena.allocate`)
    /// can fail with `OutOfMemoryError`. We isolate it here and convert to a `Result` failure so the
    /// caller releases the just-reserved bytes (accounting never leaks). See spec §4.3.
    @SuppressWarnings("JBCT-EX-01") private Result<MemorySegment> allocateGuarded(long bytes) {
        try {
            return success(arena.allocate(bytes, 64));
        } catch (OutOfMemoryError _) {
            return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
        }
    }

    public Result<MemorySegment> readSlice(long offset) {
        if (closed.get()) {return StreamError.General.BUFFER_CLOSED.result();}
        var head = headOffset();
        var tail = tailOffset();
        if (head <0) {return StreamError.General.BUFFER_EMPTY.result();}
        if (offset <tail) {return new StreamError.CursorExpired(offset, tail).result();}
        if (offset > head) {return StreamError.General.BUFFER_EMPTY.result();}
        return readSliceAtOffset(offset);
    }

    @Contract public void addAppendListener(LongConsumer listener) {
        appendListeners.add(listener);
    }

    @Contract public void removeAppendListener(LongConsumer listener) {
        appendListeners.remove(listener);
    }

    public Result<List<RawEvent>> read(long fromOffset, int maxEvents) {
        if (closed.get()) {return StreamError.General.BUFFER_CLOSED.result();}
        var tail = tailOffset();
        var head = headOffset();
        if (head <0) {return success(List.of());}
        if (fromOffset <tail) {return new StreamError.CursorExpired(fromOffset, tail).result();}
        if (fromOffset > head) {return success(List.of());}
        var count = (int) Math.min(maxEvents, head - fromOffset + 1);
        var events = new ArrayList<RawEvent>(count);
        for (long offset = fromOffset;offset <fromOffset + count;offset++) {events.add(readSingleEvent(offset));}
        return success(List.copyOf(events));
    }

    public long headOffset() {
        return controlSegment.get(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET);
    }

    public long tailOffset() {
        return controlSegment.get(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET);
    }

    public long eventCount() {
        return controlSegment.get(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT);
    }

    public long allocatedBytes() {
        return controlSegment.byteSize() + allocatedDataBytes;
    }

    /// Control-region bytes (header + index). This portion of the floor is NOT accounted against the
    /// seam (the seam tracks only data segments), so the manager releases it separately on destroy to
    /// avoid double-release. See spec §4.3.
    public long controlBytes() {
        return controlSegment.byteSize();
    }

    @Contract public void applyRetention(RetentionPolicy policy) {
        policy.tierAwareRetention().filter(_ -> lastSealedOffset >= 0)
                                 .onPresent(this::applyTierAwareRetention);
        applyNormalRetention(policy);
    }

    @Contract public void updateLastSealedOffset(long sealedOffset) {
        lastSealedOffset = sealedOffset;
    }

    public long lastSealedOffset() {
        return lastSealedOffset;
    }

    @Contract public void evictByAge(long maxAgeMs) {
        var cutoff = System.currentTimeMillis() - maxAgeMs;
        var countToEvict = countEvictionsByAge(cutoff);
        notifyAndEvict(countToEvict);
    }

    @Contract@Override public void close() {
        if (closed.compareAndSet(false, true)) {
            appendListeners.clear();
            release.accept(accountedBytes);
            arena.close();
        }
    }

    @SuppressWarnings("JBCT-ZONE-02") private void applyNormalRetention(RetentionPolicy policy) {
        if (policy.mode() == RetentionMode.ALL) {applyAllModeRetention(policy);} else {applyAnyModeRetention(policy);}
    }

    private void applyAnyModeRetention(RetentionPolicy policy) {
        evictByCount(policy.maxCount());
        evictBySize(policy.maxBytes());
        evictByAge(policy.maxAgeMs());
    }

    @SuppressWarnings("JBCT-ZONE-02") private void applyAllModeRetention(RetentionPolicy policy) {
        var countConfigured = policy.maxCount() != Long.MAX_VALUE;
        var sizeConfigured = policy.maxBytes() != Long.MAX_VALUE;
        var ageConfigured = policy.maxAgeMs() != Long.MAX_VALUE;
        var countExcess = countConfigured
                         ? Math.max(0, eventCount() - policy.maxCount())
                         : Long.MAX_VALUE;
        var sizeExcess = sizeConfigured
                        ? countEvictionsBySize(policy.maxBytes())
                        : Long.MAX_VALUE;
        var ageExcess = ageConfigured
                       ? countEvictionsByAge(System.currentTimeMillis() - policy.maxAgeMs())
                       : Long.MAX_VALUE;
        var allExceeded = (!countConfigured || countExcess > 0) && (!sizeConfigured || sizeExcess > 0) && (!ageConfigured || ageExcess > 0);
        if (allExceeded) {notifyAndEvict(minConfiguredExcess(countExcess, sizeExcess, ageExcess));}
    }

    private static long minConfiguredExcess(long countExcess, long sizeExcess, long ageExcess) {
        var min = Long.MAX_VALUE;
        if (countExcess != Long.MAX_VALUE && countExcess > 0) {min = Math.min(min, countExcess);}
        if (sizeExcess != Long.MAX_VALUE && sizeExcess > 0) {min = Math.min(min, sizeExcess);}
        if (ageExcess != Long.MAX_VALUE && ageExcess > 0) {min = Math.min(min, ageExcess);}
        return min == Long.MAX_VALUE
              ? 0
              : min;
    }

    @SuppressWarnings("JBCT-ZONE-02") private void applyTierAwareRetention(TierAwareRetention tierAware) {
        var sealedCount = countSealedEvents();
        var sealedExcess = sealedCount - tierAware.postSealMaxCount();
        if (sealedExcess > 0) {notifyAndEvict(sealedExcess);}
        evictSealedByAge(tierAware.postSealBufferMs());
    }

    private long countSealedEvents() {
        var tail = tailOffset();
        var sealed = lastSealedOffset;
        if (sealed <tail) {return 0;}
        return sealed - tail + 1;
    }

    @SuppressWarnings("JBCT-PAT-01") private void evictSealedByAge(long postSealBufferMs) {
        var cutoff = System.currentTimeMillis() - postSealBufferMs;
        var tail = tailOffset();
        var sealed = lastSealedOffset;
        var count = 0L;
        while (tail + count <= sealed) {
            var slotIndex = Math.floorMod(tail + count, capacity);
            var timestamp = readTimestamp(slotIndex);
            if (timestamp >= cutoff) {break;}
            count++;
        }
        notifyAndEvict(count);
    }

    private void notifyAppendListeners(long offset) {
        appendListeners.forEach(listener -> listener.accept(offset));
    }

    private static long totalPayloadSize(List<byte[]> payloads) {
        var total = 0L;
        for (var payload : payloads) {total += payload.length;}
        return total;
    }

    private long appendPayloads(List<byte[]> payloads, long[] timestamps) {
        var currentHead = headOffset();
        var lastOffset = currentHead;
        for (int i = 0;i <payloads.size();i++) {
            var payload = payloads.get(i);
            lastOffset = currentHead + 1 + i;
            var slotIndex = Math.floorMod(lastOffset, capacity);
            var dataPos = Math.floorMod(dataWritePos(), dataRing());
            writeDataBytes(dataPos, payload);
            writeIndexEntry(slotIndex, dataPos, payload.length, timestamps[i]);
            updateHeaderAfterAppend(lastOffset, payload.length);
        }
        return lastOffset;
    }

    private Result<MemorySegment> readSliceAtOffset(long offset) {
        var slotIndex = Math.floorMod(offset, capacity);
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        var dataPos = controlSegment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET);
        var dataLen = controlSegment.get(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH);
        return success(MemorySegment.ofArray(readDataBytes(dataPos, dataLen)));
    }

    private long dataWritePos() {
        return controlSegment.get(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS);
    }

    /// Current wrap modulus of the logical data ring = the live allocated data bytes. While the
    /// region is still growing this is raised before each write to cover it (so writes never wrap
    /// below cap); once a stream can no longer grow (EVENTUAL pool-exhausted) it stays fixed and the
    /// ring wraps within the allocated bytes — i.e. behaves like a fixed ring at its current size.
    /// At full cap this equals `dataRegionSize`. Always <= `dataRegionSize`. See spec §4.2.
    private long dataRing() {
        return allocatedDataBytes;
    }

    /// Write a payload into the logical data ring at `dataPos`. Splits on the ring-wrap boundary
    /// (`dataRing()`), then each chunk is further split across data-segment boundaries by
    /// `copyIntoData`. See spec §4.2.
    private void writeDataBytes(long dataPos, byte[] payload) {
        var remaining = dataRing() - dataPos;
        if (remaining >= payload.length) {
            copyIntoData(dataPos, payload, 0, payload.length);
        } else {
            copyIntoData(dataPos, payload, 0, (int) remaining);
            copyIntoData(0, payload, (int) remaining, (int) (payload.length - remaining));
        }
    }

    /// Read a payload from the logical data ring at `dataPos`. Mirror of `writeDataBytes`.
    private byte[] readDataBytes(long dataPos, int dataLen) {
        var eventBytes = new byte[dataLen];
        var remaining = dataRing() - dataPos;
        if (remaining >= dataLen) {
            copyOutOfData(dataPos, eventBytes, 0, dataLen);
        } else {
            copyOutOfData(dataPos, eventBytes, 0, (int) remaining);
            copyOutOfData(0, eventBytes, (int) remaining, (int) (dataLen - remaining));
        }
        return eventBytes;
    }

    /// Copy `len` bytes from `src[srcOffset..]` into the logical data ring starting at linear
    /// position `pos`, crossing whatever data-segment boundaries the range [pos, pos+len) spans.
    /// `pos + len` never exceeds `dataRegionSize` (ring-wrap is handled by the caller); and a write
    /// that reaches segment N implies the data region has already grown to cover N (growth precedes
    /// every write). See spec §4.2.
    private void copyIntoData(long pos, byte[] src, int srcOffset, int len) {
        var srcSeg = MemorySegment.ofArray(src);
        var copied = 0;
        while (copied < len) {
            var linearPos = pos + copied;
            var segmentIndex = (int) (linearPos / DEFAULT_SEGMENT_BYTES);
            var segmentOffset = linearPos % DEFAULT_SEGMENT_BYTES;
            var segment = dataSegments.get(segmentIndex);
            var spaceInSegment = segment.byteSize() - segmentOffset;
            var chunk = (int) Math.min(len - copied, spaceInSegment);
            MemorySegment.copy(srcSeg, srcOffset + copied, segment, segmentOffset, chunk);
            copied += chunk;
        }
    }

    /// Copy `len` bytes out of the logical data ring starting at linear position `pos` into
    /// `dest[destOffset..]`, crossing whatever data-segment boundaries the range spans. Mirror of
    /// `copyIntoData`.
    private void copyOutOfData(long pos, byte[] dest, int destOffset, int len) {
        var destSeg = MemorySegment.ofArray(dest);
        var copied = 0;
        while (copied < len) {
            var linearPos = pos + copied;
            var segmentIndex = (int) (linearPos / DEFAULT_SEGMENT_BYTES);
            var segmentOffset = linearPos % DEFAULT_SEGMENT_BYTES;
            var segment = dataSegments.get(segmentIndex);
            var spaceInSegment = segment.byteSize() - segmentOffset;
            var chunk = (int) Math.min(len - copied, spaceInSegment);
            MemorySegment.copy(segment, segmentOffset, destSeg, destOffset + copied, chunk);
            copied += chunk;
        }
    }

    private void writeIndexEntry(long slotIndex, long dataPos, int dataLength, long timestamp) {
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        controlSegment.set(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET, dataPos);
        controlSegment.set(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH, dataLength);
        controlSegment.set(ValueLayout.JAVA_LONG, indexPos + INDEX_TIMESTAMP, timestamp);
    }

    private void updateHeaderAfterAppend(long newHeadOffset, int payloadLength) {
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, newHeadOffset);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, dataWritePos() + payloadLength);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, eventCount() + 1);
    }

    private RawEvent readSingleEvent(long offset) {
        var slotIndex = Math.floorMod(offset, capacity);
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        var dataPos = controlSegment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET);
        var dataLen = controlSegment.get(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH);
        var timestamp = controlSegment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_TIMESTAMP);
        var eventBytes = readDataBytes(dataPos, dataLen);
        return RawEvent.rawEvent(offset, eventBytes, timestamp);
    }

    private long readTimestamp(long slotIndex) {
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        return controlSegment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_TIMESTAMP);
    }

    private void evictForSpace(int payloadLength) {
        var countToEvict = countEvictionsForSpace(payloadLength);
        notifyAndEvict(countToEvict);
    }

    private long countEvictionsForSpace(int payloadLength) {
        var count = 0L;
        var simulatedTail = tailOffset();
        var simulatedCount = eventCount();
        while (simulatedCount >= capacity) {
            simulatedTail++;
            simulatedCount--;
            count++;
        }
        while (simulatedCount > 0 && wouldNeedDataEviction(payloadLength, simulatedTail)) {
            simulatedTail++;
            simulatedCount--;
            count++;
        }
        return count;
    }

    /// Data-region pressure is measured against the **allocated** data bytes, not the cap: an
    /// EVENTUAL stream that could not grow (pool exhausted) must evict to fit within what it has.
    /// While the region can still grow (allocated < cap) growth covers the write, so eviction is a
    /// no-op here. See spec §4.2.
    private boolean wouldNeedDataEviction(int payloadLength, long simulatedTail) {
        var head = headOffset();
        if (simulatedTail > head) {return false;}
        var tailSlot = Math.floorMod(simulatedTail, capacity);
        var headSlot = Math.floorMod(head, capacity);
        var tailDataPos = controlSegment.get(ValueLayout.JAVA_LONG,
                                             indexStart + tailSlot * INDEX_ENTRY_SIZE + INDEX_DATA_OFFSET);
        var headDataPos = controlSegment.get(ValueLayout.JAVA_LONG,
                                             indexStart + headSlot * INDEX_ENTRY_SIZE + INDEX_DATA_OFFSET);
        var headDataLen = controlSegment.get(ValueLayout.JAVA_INT, indexStart + headSlot * INDEX_ENTRY_SIZE + INDEX_DATA_LENGTH);
        var headEnd = headDataPos + headDataLen;
        var used = (headEnd >= tailDataPos)
                  ? headEnd - tailDataPos
                  : (dataRing() - tailDataPos) + headEnd;
        return used + payloadLength > allocatedDataBytes;
    }

    private void evictOldest() {
        var tail = tailOffset();
        if (tail > headOffset()) {return;}
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, tail + 1);
        controlSegment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, eventCount() - 1);
    }

    private void evictByCount(long maxCount) {
        var excess = eventCount() - maxCount;
        if (excess > 0) {notifyAndEvict(excess);}
    }

    private void evictBySize(long maxBytes) {
        var countToEvict = countEvictionsBySize(maxBytes);
        notifyAndEvict(countToEvict);
    }

    private void notifyAndEvict(long count) {
        if (count <= 0) {return;}
        if (listener != EvictionListener.NOOP) {
            var events = collectEvictedEvents(count);
            listener.onEviction(streamName, partition, events);
            updateSealedOffsetFromEvents(events);
        }
        for (long i = 0;i <count;i++) {evictOldest();}
    }

    private void updateSealedOffsetFromEvents(List<RawEvent> events) {
        if (events.isEmpty()) {return;}
        var sealedTo = events.getLast().offset();
        if (sealedTo > lastSealedOffset) {lastSealedOffset = sealedTo;}
    }

    private List<OffHeapRingBuffer.RawEvent> collectEvictedEvents(long count) {
        var tail = tailOffset();
        var events = new ArrayList<RawEvent>((int) count);
        for (long i = 0;i <count;i++) {events.add(readSingleEvent(tail + i));}
        return List.copyOf(events);
    }

    private long countEvictionsByAge(long cutoff) {
        var count = 0L;
        var tail = tailOffset();
        while (count <eventCount()) {
            var slotIndex = Math.floorMod(tail + count, capacity);
            var timestamp = readTimestamp(slotIndex);
            if (timestamp >= cutoff) {break;}
            count++;
        }
        return count;
    }

    private long countEvictionsBySize(long maxBytes) {
        var count = 0L;
        var simulatedTail = tailOffset();
        var head = headOffset();
        while (simulatedTail + count <= head) {
            var tailSlot = Math.floorMod(simulatedTail + count, capacity);
            var headSlot = Math.floorMod(head, capacity);
            var tailDataPos = controlSegment.get(ValueLayout.JAVA_LONG,
                                                 indexStart + tailSlot * INDEX_ENTRY_SIZE + INDEX_DATA_OFFSET);
            var headDataPos = controlSegment.get(ValueLayout.JAVA_LONG,
                                                 indexStart + headSlot * INDEX_ENTRY_SIZE + INDEX_DATA_OFFSET);
            var headDataLen = controlSegment.get(ValueLayout.JAVA_INT,
                                                 indexStart + headSlot * INDEX_ENTRY_SIZE + INDEX_DATA_LENGTH);
            var headEnd = headDataPos + headDataLen;
            var used = (headEnd >= tailDataPos)
                      ? headEnd - tailDataPos
                      : (dataRing() - tailDataPos) + headEnd;
            if (used <= maxBytes) {break;}
            count++;
        }
        return count;
    }

    public record RawEvent(long offset, byte[] data, long timestamp) {
        public RawEvent {
            data = data.clone();
        }

        public static RawEvent rawEvent(long offset, byte[] data, long timestamp) {
            return new RawEvent(offset, data, timestamp);
        }

        @Override public byte[] data() {
            return data.clone();
        }

        @Override public boolean equals(Object o) {
            return o instanceof RawEvent other && offset == other.offset && timestamp == other.timestamp && Arrays.equals(data,
                                                                                                                          other.data);
        }

        @Override public int hashCode() {
            return 31 * (31 * Long.hashCode(offset) + Arrays.hashCode(data)) + Long.hashCode(timestamp);
        }
    }
}
