// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.storage.MetadataStore;

import static org.pragmatica.lang.Option.option;


public final class SegmentIndex {
    private final ConcurrentHashMap<PartitionKey, ConcurrentSkipListMap<Long, SegmentRef>> partitions = new ConcurrentHashMap<>();

    public record SegmentRef(long startOffset,
                             long endOffset,
                             long maxTimestamp,
                             int compressionOrdinal,
                             boolean encrypted,
                             int originalSize) {
        public static SegmentRef segmentRef(long startOffset, long endOffset, long maxTimestamp) {
            return new SegmentRef(startOffset, endOffset, maxTimestamp, 0, false, 0);
        }

        public static SegmentRef segmentRef(long startOffset, long endOffset) {
            return new SegmentRef(startOffset, endOffset, 0L, 0, false, 0);
        }

        public static SegmentRef segmentRef(long startOffset,
                                            long endOffset,
                                            long maxTimestamp,
                                            int compressionOrdinal,
                                            boolean encrypted,
                                            int originalSize) {
            return new SegmentRef(startOffset, endOffset, maxTimestamp, compressionOrdinal, encrypted, originalSize);
        }

        boolean containsOffset(long offset) {
            return offset >= startOffset && offset <= endOffset;
        }
    }

    @Contract
    public void addSegment(String streamName, int partition, long startOffset, long endOffset, long maxTimestamp) {
        addSegment(streamName, partition, startOffset, endOffset, maxTimestamp, 0, false, 0);
    }

    @Contract
    public void addSegment(String streamName, int partition, long startOffset, long endOffset) {
        addSegment(streamName, partition, startOffset, endOffset, 0L);
    }

    @Contract
    public void addSegment(String streamName,
                           int partition,
                           long startOffset,
                           long endOffset,
                           long maxTimestamp,
                           int compressionOrdinal,
                           boolean encrypted,
                           int originalSize) {
        var key = PartitionKey.partitionKey(streamName, partition);
        var map = partitions.computeIfAbsent(key, _ -> new ConcurrentSkipListMap<>());

        map.put(startOffset,
                SegmentRef.segmentRef(startOffset, endOffset, maxTimestamp, compressionOrdinal, encrypted, originalSize));
    }

    @Contract
    public void removeSegment(String streamName, int partition, long startOffset) {
        var key = PartitionKey.partitionKey(streamName, partition);

        option(partitions.get(key)).onPresent(map -> map.remove(startOffset));
    }

    public List<SegmentRef> listSegments(String streamName, int partition) {
        return option(partitions.get(PartitionKey.partitionKey(streamName, partition))).map(map -> List.copyOf(map.values()))
                     .or(List.of());
    }

    /// The highest offset durably sealed into segments for `(streamName, partition)` — the maximum
    /// `endOffset` across that partition's sealed segments — or `-1` when nothing is sealed. Used to
    /// bound WAL replay on partition recovery (streaming-persistence W4): the recovered ring skips
    /// records at or below this offset (served by the tiered reader) and replays only the un-sealed tail.
    public long lastSealedOffset(String streamName, int partition) {
        return option(partitions.get(PartitionKey.partitionKey(streamName, partition))).map(SegmentIndex::maxEndOffset)
                     .or(-1L);
    }

    private static long maxEndOffset(ConcurrentSkipListMap<Long, SegmentRef> map) {
        return map.values()
                  .stream()
                  .mapToLong(SegmentRef::endOffset)
                  .max()
                  .orElse(-1L);
    }

    public Set<PartitionKey> listPartitionKeys() {
        return Set.copyOf(partitions.keySet());
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

    @Contract
    public void rebuildFromRefs(MetadataStore metadataStore) {
        partitions.clear();
        metadataStore.listAllRefs()
                     .keySet()
                     .stream()
                     .filter(ref -> ref.startsWith(STREAMS_PREFIX))
                     .forEach(this::parseAndAddRef);
    }

    private void parseAndAddRef(String refName) {
        var parts = refName.substring(STREAMS_PREFIX.length()).split("/");

        if (parts.length != 3) {
            return;
        }

        var streamName = parts[0];

        Number.parseInt(parts[1]).onSuccess(partition -> parseOffsetRange(streamName, partition, parts[2]));
    }

    private void parseOffsetRange(String streamName, int partition, String range) {
        var dash = range.indexOf('-');

        if (dash < 0) {
            return;
        }

        Number.parseLong(range.substring(0, dash)).onSuccess(start -> Number.parseLong(range.substring(dash + 1)).onSuccess(end -> addSegment(streamName,
                                                                                                                                              partition,
                                                                                                                                              start,
                                                                                                                                              end)));
    }

    static String buildRefName(String streamName, int partition, SegmentRef ref) {
        return STREAMS_PREFIX + streamName + "/" + partition + "/" + ref.startOffset() + "-" + ref.endOffset();
    }

    private static final String STREAMS_PREFIX = "streams/";

    public record PartitionKey(String streamName, int partition) {
        public static PartitionKey partitionKey(String streamName, int partition) {
            return new PartitionKey(streamName, partition);
        }
    }
}
