package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.lang.Result;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Result.success;

/// Off-heap ring buffer for a single stream partition.
///
/// Memory layout:
/// ```
/// [Header: 64 bytes] [Index: 24 bytes * capacity] [Data: variable]
/// ```
///
/// Header (64 bytes, cache-line aligned):
///   offset 0:  headOffset    (long) -- next logical offset (monotonic, -1 = empty)
///   offset 8:  tailOffset    (long) -- oldest readable logical offset
///   offset 16: eventCount    (long) -- current number of events in buffer
///   offset 24: dataWritePos  (long) -- cumulative write position in data region
///   offset 32: dataSize      (long) -- total data region size in bytes
///   offset 40: capacity      (long) -- max number of events (index slot count)
///   offset 48: reserved      (long)
///   offset 56: reserved      (long)
///
/// Index (24 bytes per slot):
///   offset 0:  dataOffset    (long) -- position in data region (mod dataSize)
///   offset 8:  dataLength    (int)  -- serialized event size
///   offset 12: reserved      (int)  -- padding
///   offset 16: timestamp     (long) -- event timestamp
///
/// Data (circular region):
///   Raw serialized event bytes, appended sequentially with wrap-around.
public final class OffHeapRingBuffer implements AutoCloseable {
    // Header field offsets
    private static final long HEADER_HEAD_OFFSET = 0;
    private static final long HEADER_TAIL_OFFSET = 8;
    private static final long HEADER_EVENT_COUNT = 16;
    private static final long HEADER_DATA_WRITE_POS = 24;
    private static final long HEADER_DATA_SIZE = 32;
    private static final long HEADER_CAPACITY = 40;
    private static final long HEADER_SIZE = 64;

    // Index entry layout
    private static final long INDEX_ENTRY_SIZE = 24;
    private static final long INDEX_DATA_OFFSET = 0;
    private static final long INDEX_DATA_LENGTH = 8;
    private static final long INDEX_TIMESTAMP = 16;

    private final Arena arena;
    private final MemorySegment segment;
    private final long capacity;
    private final long dataRegionSize;
    private final long indexStart;
    private final long dataStart;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private OffHeapRingBuffer(Arena arena, MemorySegment segment, long capacity, long dataRegionSize) {
        this.arena = arena;
        this.segment = segment;
        this.capacity = capacity;
        this.dataRegionSize = dataRegionSize;
        this.indexStart = HEADER_SIZE;
        this.dataStart = HEADER_SIZE + INDEX_ENTRY_SIZE * capacity;
    }

    /// Create a new off-heap ring buffer.
    ///
    /// @param capacity maximum number of events (index slot count)
    /// @param dataRegionSize size of the data region in bytes
    public static OffHeapRingBuffer offHeapRingBuffer(long capacity, long dataRegionSize) {
        var arena = Arena.ofShared();
        var indexSize = INDEX_ENTRY_SIZE * capacity;
        var totalSize = HEADER_SIZE + indexSize + dataRegionSize;
        var segment = arena.allocate(totalSize, 64);
        segment.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, - 1L);
        segment.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, 0L);
        segment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, 0L);
        segment.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, 0L);
        segment.set(ValueLayout.JAVA_LONG, HEADER_DATA_SIZE, dataRegionSize);
        segment.set(ValueLayout.JAVA_LONG, HEADER_CAPACITY, capacity);
        return new OffHeapRingBuffer(arena, segment, capacity, dataRegionSize);
    }

    /// Append a serialized event to the buffer.
    /// Returns the assigned logical offset.
    ///
    /// Thread safety: single-writer assumed (governor thread).
    /// Consumers may read concurrently.
    public Result<Long> append(byte[] payload, long timestamp) {
        if (closed.get()) {
            return StreamError.General.BUFFER_CLOSED.result();
        }
        if (payload.length > dataRegionSize) {
            return new StreamError.EventTooLarge(payload.length, dataRegionSize).result();
        }
        evictForSpace(payload.length);
        var currentHead = headOffset();
        var newOffset = currentHead + 1;
        var slotIndex = Math.floorMod(newOffset, capacity);
        var dataPos = Math.floorMod(dataWritePos(), dataRegionSize);
        writeDataBytes(dataPos, payload);
        writeIndexEntry(slotIndex, dataPos, payload.length, timestamp);
        updateHeaderAfterAppend(newOffset, payload.length);
        return success(newOffset);
    }

    /// Read events starting from the given offset, up to maxEvents.
    /// Returns CURSOR_EXPIRED if fromOffset is before tailOffset.
    ///
    /// Thread safety: safe for concurrent reads (no mutation).
    public Result<List<RawEvent>> read(long fromOffset, int maxEvents) {
        if (closed.get()) {
            return StreamError.General.BUFFER_CLOSED.result();
        }
        var tail = tailOffset();
        var head = headOffset();
        if (head < 0) {
            return success(List.of());
        }
        if (fromOffset < tail) {
            return new StreamError.CursorExpired(fromOffset, tail).result();
        }
        if (fromOffset > head) {
            return success(List.of());
        }
        var count = (int) Math.min(maxEvents, head - fromOffset + 1);
        var events = new ArrayList<RawEvent>(count);
        for (long offset = fromOffset; offset < fromOffset + count; offset++) {
            events.add(readSingleEvent(offset));
        }
        return success(List.copyOf(events));
    }

    /// Current head offset (last assigned logical offset, -1 if empty).
    public long headOffset() {
        return segment.get(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET);
    }

    /// Oldest readable logical offset.
    public long tailOffset() {
        return segment.get(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET);
    }

    /// Current number of events in the buffer.
    public long eventCount() {
        return segment.get(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT);
    }

    /// Total memory allocated for this buffer.
    public long allocatedBytes() {
        return segment.byteSize();
    }

    /// Apply retention policy, evicting events that exceed any limit.
    @SuppressWarnings("JBCT-RET-01") // Mutation operation on off-heap data structure
    public void applyRetention(RetentionPolicy policy) {
        evictByCount(policy.maxCount());
        evictBySize(policy.maxBytes());
        evictByAge(policy.maxAgeMs());
    }

    /// Evict events older than the retention duration.
    @SuppressWarnings("JBCT-RET-01") // Mutation operation on off-heap data structure
    public void evictByAge(long maxAgeMs) {
        var cutoff = System.currentTimeMillis() - maxAgeMs;
        while (eventCount() > 0) {
            var tailSlot = Math.floorMod(tailOffset(), capacity);
            var timestamp = readTimestamp(tailSlot);
            if (timestamp >= cutoff) {
                break;
            }
            evictOldest();
        }
    }

    @SuppressWarnings("JBCT-RET-01") // AutoCloseable contract requires void
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            arena.close();
        }
    }

    // --- Private helpers ---
    private long dataWritePos() {
        return segment.get(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS);
    }

    private void writeDataBytes(long dataPos, byte[] payload) {
        var remaining = dataRegionSize - dataPos;
        if (remaining >= payload.length) {
            MemorySegment.copy(MemorySegment.ofArray(payload), 0, segment, dataStart + dataPos, payload.length);
        } else {
            copyWrappedData(dataPos, payload, remaining);
        }
    }

    private void copyWrappedData(long dataPos, byte[] payload, long firstChunkSize) {
        var src = MemorySegment.ofArray(payload);
        MemorySegment.copy(src, 0, segment, dataStart + dataPos, firstChunkSize);
        MemorySegment.copy(src, firstChunkSize, segment, dataStart, payload.length - firstChunkSize);
    }

    private void writeIndexEntry(long slotIndex, long dataPos, int dataLength, long timestamp) {
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        segment.set(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET, dataPos);
        segment.set(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH, dataLength);
        segment.set(ValueLayout.JAVA_LONG, indexPos + INDEX_TIMESTAMP, timestamp);
    }

    private void updateHeaderAfterAppend(long newHeadOffset, int payloadLength) {
        segment.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, newHeadOffset);
        segment.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, dataWritePos() + payloadLength);
        segment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, eventCount() + 1);
    }

    private RawEvent readSingleEvent(long offset) {
        var slotIndex = Math.floorMod(offset, capacity);
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        var dataPos = segment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET);
        var dataLen = segment.get(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH);
        var timestamp = segment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_TIMESTAMP);
        var eventBytes = readDataBytes(dataPos, dataLen);
        return RawEvent.rawEvent(offset, eventBytes, timestamp);
    }

    private byte[] readDataBytes(long dataPos, int dataLen) {
        var eventBytes = new byte[dataLen];
        var remaining = dataRegionSize - dataPos;
        if (remaining >= dataLen) {
            MemorySegment.copy(segment, dataStart + dataPos, MemorySegment.ofArray(eventBytes), 0, dataLen);
        } else {
            readWrappedData(dataPos, eventBytes, remaining);
        }
        return eventBytes;
    }

    private void readWrappedData(long dataPos, byte[] dest, long firstChunkSize) {
        var dst = MemorySegment.ofArray(dest);
        MemorySegment.copy(segment, dataStart + dataPos, dst, 0, firstChunkSize);
        MemorySegment.copy(segment, dataStart, dst, firstChunkSize, dest.length - firstChunkSize);
    }

    private long readTimestamp(long slotIndex) {
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        return segment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_TIMESTAMP);
    }

    private void evictForSpace(int payloadLength) {
        while (eventCount() >= capacity) {
            evictOldest();
        }
        while (needsDataEviction(payloadLength) && eventCount() > 0) {
            evictOldest();
        }
    }

    private boolean needsDataEviction(int payloadLength) {
        return usedDataBytes() + payloadLength > dataRegionSize;
    }

    private long usedDataBytes() {
        if (eventCount() == 0) {
            return 0;
        }
        var tail = tailOffset();
        var head = headOffset();
        var tailSlot = Math.floorMod(tail, capacity);
        var headSlot = Math.floorMod(head, capacity);
        var tailDataPos = segment.get(ValueLayout.JAVA_LONG,
                                      indexStart + tailSlot * INDEX_ENTRY_SIZE + INDEX_DATA_OFFSET);
        var headDataPos = segment.get(ValueLayout.JAVA_LONG,
                                      indexStart + headSlot * INDEX_ENTRY_SIZE + INDEX_DATA_OFFSET);
        var headDataLen = segment.get(ValueLayout.JAVA_INT, indexStart + headSlot * INDEX_ENTRY_SIZE + INDEX_DATA_LENGTH);
        var headEnd = headDataPos + headDataLen;
        return (headEnd >= tailDataPos)
               ? headEnd - tailDataPos
               : (dataRegionSize - tailDataPos) + headEnd;
    }

    private void evictOldest() {
        var tail = tailOffset();
        if (tail > headOffset()) {
            return;
        }
        segment.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, tail + 1);
        segment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, eventCount() - 1);
    }

    private void evictByCount(long maxCount) {
        while (eventCount() > maxCount) {
            evictOldest();
        }
    }

    private void evictBySize(long maxBytes) {
        while (eventCount() > 0 && usedDataBytes() > maxBytes) {
            evictOldest();
        }
    }

    /// A raw event read from the ring buffer.
    public record RawEvent(long offset, byte[] data, long timestamp) {
        public static RawEvent rawEvent(long offset, byte[] data, long timestamp) {
            return new RawEvent(offset, data, timestamp);
        }
    }
}
