package org.pragmatica.aether.stream.segment;

import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Contract;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/// Captures evicted events from the ring buffer and seals them into immutable segments.
/// Implements EvictionListener -- wire as the listener on OffHeapRingBuffer.
///
/// Serialization format per event: [offset:8][timestamp:8][len:4][data:len]
public final class SegmentSealer implements EvictionListener {
    private static final int PER_EVENT_HEADER = Long.BYTES + Long.BYTES + Integer.BYTES;

    private final SegmentSink sink;

    private SegmentSealer(SegmentSink sink) {
        this.sink = sink;
    }

    /// Factory method following JBCT naming convention.
    public static SegmentSealer segmentSealer(SegmentSink sink) {
        return new SegmentSealer(sink);
    }

    @Contract @Override public void onEviction(String streamName, int partition, List<RawEvent> events) {
        if ( events.isEmpty()) {
        return;}
        var segment = buildSegment(streamName, partition, events);
        sink.seal(segment);
    }

    private SealedSegment buildSegment(String streamName, int partition, List<RawEvent> events) {
        var startOffset = events.getFirst().offset();
        var endOffset = events.getLast().offset();
        var timestamps = extractTimestamps(events);
        var serialized = serializeEvents(events);
        return SealedSegment.sealedSegment(streamName,
                                           partition,
                                           startOffset,
                                           endOffset,
                                           events.size(),
                                           timestamps[0],
                                           timestamps[1],
                                           serialized);
    }

    private long[] extractTimestamps(List<RawEvent> events) {
        var min = Long.MAX_VALUE;
        var max = Long.MIN_VALUE;
        for ( var event : events) {
            min = Math.min(min, event.timestamp());
            max = Math.max(max, event.timestamp());
        }
        return new long[]{min, max};
    }

    private byte[] serializeEvents(List<RawEvent> events) {
        var totalSize = events.stream().mapToInt(e -> PER_EVENT_HEADER + e.data().length)
                                     .sum();
        var buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        events.forEach(event -> writeEvent(buffer, event));
        return buffer.array();
    }

    private void writeEvent(ByteBuffer buffer, RawEvent event) {
        var data = event.data();
        buffer.putLong(event.offset());
        buffer.putLong(event.timestamp());
        buffer.putInt(data.length);
        buffer.put(data);
    }
}
