package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.storage.MetadataStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.pragmatica.lang.Option.option;


/// Index of sealed segments for read-back. Lookup by stream/partition/offset.
/// Thread-safe for concurrent reads and updates.
public final class SegmentIndex {
    private final ConcurrentHashMap<PartitionKey, ConcurrentSkipListMap<Long, SegmentRef>> partitions = new ConcurrentHashMap<>();

    public record SegmentRef(long startOffset, long endOffset) {
        public static SegmentRef segmentRef(long startOffset, long endOffset) {
            return new SegmentRef(startOffset, endOffset);
        }

        boolean containsOffset(long offset) {
            return offset >= startOffset && offset <= endOffset;
        }
    }

    @Contract public void addSegment(String streamName, int partition, long startOffset, long endOffset) {
        var key = PartitionKey.partitionKey(streamName, partition);
        var map = partitions.computeIfAbsent(key, _ -> new ConcurrentSkipListMap<>());
        map.put(startOffset, SegmentRef.segmentRef(startOffset, endOffset));
    }

    public Option<SegmentRef> findSegment(String streamName, int partition, long offset) {
        return option(partitions.get(PartitionKey.partitionKey(streamName, partition))).flatMap(map -> option(map.floorEntry(offset)))
                     .map(Map.Entry::getValue)
                     .filter(ref -> ref.containsOffset(offset));
    }

    public List<SegmentRef> segmentRange(String streamName, int partition, long fromOffset, long toOffset) {
        return option(partitions.get(PartitionKey.partitionKey(streamName, partition))).map(map -> collectOverlapping(map,
                                                                                                                      fromOffset,
                                                                                                                      toOffset))
                     .or(List.of());
    }

    private List<SegmentRef> collectOverlapping(ConcurrentSkipListMap<Long, SegmentRef> map,
                                                long fromOffset,
                                                long toOffset) {
        if (map.isEmpty()) {return List.of();}
        var startKey = option(map.floorKey(fromOffset)).or(map.firstKey());
        return map.subMap(startKey, true, toOffset, true).values()
                         .stream()
                         .filter(ref -> ref.endOffset >= fromOffset && ref.startOffset <= toOffset)
                         .toList();
    }

    @Contract public void rebuildFromRefs(MetadataStore metadataStore) {
        partitions.clear();
        metadataStore.listAllRefs().keySet()
                                 .stream()
                                 .filter(ref -> ref.startsWith(STREAMS_PREFIX))
                                 .forEach(this::parseAndAddRef);
    }

    private void parseAndAddRef(String refName) {
        var parts = refName.substring(STREAMS_PREFIX.length()).split("/");
        if (parts.length != 3) {return;}
        var streamName = parts[0];
        Number.parseInt(parts[1]).onSuccess(partition -> parseOffsetRange(streamName, partition, parts[2]));
    }

    private void parseOffsetRange(String streamName, int partition, String range) {
        var dash = range.indexOf('-');
        if (dash <0) {return;}
        Number.parseLong(range.substring(0, dash))
                        .onSuccess(start -> Number.parseLong(range.substring(dash + 1))
                                                            .onSuccess(end -> addSegment(streamName,
                                                                                         partition,
                                                                                         start,
                                                                                         end)));
    }

    private static final String STREAMS_PREFIX = "streams/";

    private record PartitionKey(String streamName, int partition) {
        static PartitionKey partitionKey(String streamName, int partition) {
            return new PartitionKey(streamName, partition);
        }
    }
}
