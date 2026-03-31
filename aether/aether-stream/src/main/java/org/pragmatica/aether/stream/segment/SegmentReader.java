package org.pragmatica.aether.stream.segment;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Promise;
import org.pragmatica.storage.StorageInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/// Reads events from sealed segments stored in AHSE.
/// Used as fallback when the ring buffer no longer holds the requested offset.
///
/// Deserialization format per event: [offset:8][timestamp:8][len:4][data:len]
public final class SegmentReader {
    private static final Logger log = LoggerFactory.getLogger(SegmentReader.class);
    private static final int PER_EVENT_HEADER = Long.BYTES + Long.BYTES + Integer.BYTES;

    private final StorageInstance storage;
    private final SegmentIndex index;

    private SegmentReader(StorageInstance storage, SegmentIndex index) {
        this.storage = storage;
        this.index = index;
    }

    /// Factory method following JBCT naming convention.
    public static SegmentReader segmentReader(StorageInstance storage, SegmentIndex index) {
        return new SegmentReader(storage, index);
    }

    /// Read up to maxEvents starting from fromOffset for the given stream/partition.
    /// Returns empty list when no sealed segments cover the requested range.
    public Promise<List<RawEvent>> readEvents(String streamName, int partition, long fromOffset, int maxEvents) {
        var endOffset = fromOffset + maxEvents - 1;
        var refs = index.segmentRange(streamName, partition, fromOffset, endOffset);

        if (refs.isEmpty()) {
            return Promise.success(List.of());
        }

        return readFromSegmentRefs(streamName, partition, refs, fromOffset, maxEvents);
    }

    private Promise<List<RawEvent>> readFromSegmentRefs(String streamName, int partition,
                                                        List<SegmentIndex.SegmentRef> refs,
                                                        long fromOffset, int maxEvents) {
        return readNextSegment(streamName, partition, refs, 0, fromOffset, maxEvents, new ArrayList<>());
    }

    private Promise<List<RawEvent>> readNextSegment(String streamName, int partition,
                                                    List<SegmentIndex.SegmentRef> refs, int refIndex,
                                                    long fromOffset, int remaining, List<RawEvent> accumulated) {
        if (refIndex >= refs.size() || remaining <= 0) {
            return Promise.success(List.copyOf(accumulated));
        }

        var ref = refs.get(refIndex);
        var refName = buildRefName(streamName, partition, ref);

        return storage.resolveRef(refName)
                      .async(SegmentError.General.SEGMENT_REF_NOT_FOUND)
                      .flatMap(storage::get)
                      .flatMap(opt -> opt.async(SegmentError.General.SEGMENT_DATA_NOT_FOUND))
                      .map(bytes -> deserializeAndFilter(bytes, fromOffset, remaining))
                      .flatMap(events -> continueReading(streamName, partition, refs, refIndex,
                                                         fromOffset, remaining, accumulated, events));
    }

    private Promise<List<RawEvent>> continueReading(String streamName, int partition,
                                                    List<SegmentIndex.SegmentRef> refs, int refIndex,
                                                    long fromOffset, int remaining,
                                                    List<RawEvent> accumulated, List<RawEvent> events) {
        accumulated.addAll(events);
        var newRemaining = remaining - events.size();

        return readNextSegment(streamName, partition, refs, refIndex + 1, fromOffset, newRemaining, accumulated);
    }

    private static String buildRefName(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        return "streams/" + streamName + "/" + partition + "/" + ref.startOffset() + "-" + ref.endOffset();
    }

    static List<RawEvent> deserializeAndFilter(byte[] serialized, long fromOffset, int maxEvents) {
        var buffer = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN);
        var result = new ArrayList<RawEvent>();

        while (buffer.remaining() >= PER_EVENT_HEADER && result.size() < maxEvents) {
            var event = readSingleEvent(buffer);

            if (event.offset() >= fromOffset) {
                result.add(event);
            }
        }

        return List.copyOf(result);
    }

    private static RawEvent readSingleEvent(ByteBuffer buffer) {
        var offset = buffer.getLong();
        var timestamp = buffer.getLong();
        var len = buffer.getInt();
        var data = new byte[len];
        buffer.get(data);

        return RawEvent.rawEvent(offset, data, timestamp);
    }
}
