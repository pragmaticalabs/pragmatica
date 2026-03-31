package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.pragmatica.lang.Option.option;

/// Index of sealed segments for read-back. Lookup by stream/partition/offset.
/// Thread-safe for concurrent reads and updates.
public final class SegmentIndex {

    private final ConcurrentHashMap<PartitionKey, ConcurrentSkipListMap<Long, SegmentRef>> partitions =
        new ConcurrentHashMap<>();

    /// Reference to a sealed segment's location and offset range.
    public record SegmentRef(long startOffset, long endOffset) {

        public static SegmentRef segmentRef(long startOffset, long endOffset) {
            return new SegmentRef(startOffset, endOffset);
        }

        boolean containsOffset(long offset) {
            return offset >= startOffset && offset <= endOffset;
        }
    }

    /// Add a segment reference to the index.
    public void addSegment(String streamName, int partition, long startOffset, long endOffset) {
        var key = PartitionKey.partitionKey(streamName, partition);
        var map = partitions.computeIfAbsent(key, _ -> new ConcurrentSkipListMap<>());
        map.put(startOffset, SegmentRef.segmentRef(startOffset, endOffset));
    }

    /// Find the segment containing the given offset, if any.
    public Option<SegmentRef> findSegment(String streamName, int partition, long offset) {
        return option(partitions.get(PartitionKey.partitionKey(streamName, partition)))
            .flatMap(map -> option(map.floorEntry(offset)))
            .map(Map.Entry::getValue)
            .filter(ref -> ref.containsOffset(offset));
    }

    /// Return all segments whose ranges overlap [fromOffset, toOffset].
    public List<SegmentRef> segmentRange(String streamName, int partition, long fromOffset, long toOffset) {
        return option(partitions.get(PartitionKey.partitionKey(streamName, partition)))
            .map(map -> collectOverlapping(map, fromOffset, toOffset))
            .or(List.of());
    }

    private List<SegmentRef> collectOverlapping(ConcurrentSkipListMap<Long, SegmentRef> map,
                                                long fromOffset, long toOffset) {
        if (map.isEmpty()) {
            return List.of();
        }

        var startKey = option(map.floorKey(fromOffset)).or(map.firstKey());

        return map.subMap(startKey, true, toOffset, true)
                  .values()
                  .stream()
                  .filter(ref -> ref.endOffset >= fromOffset && ref.startOffset <= toOffset)
                  .toList();
    }

    private record PartitionKey(String streamName, int partition) {

        static PartitionKey partitionKey(String streamName, int partition) {
            return new PartitionKey(streamName, partition);
        }
    }
}
