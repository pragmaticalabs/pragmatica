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

import org.pragmatica.aether.stream.LastSealedOffsetSource;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.storage.MetadataStore;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


public final class SegmentIndex implements LastSealedOffsetSource {
    private static final long NOTHING_SEALED = -1L;
    private static final ConcurrentSkipListMap<Long, Long> NOTHING_RECLAIMED = new ConcurrentSkipListMap<>();

    private final ConcurrentHashMap<PartitionKey, ConcurrentSkipListMap<Long, SegmentRef>> partitions = new ConcurrentHashMap<>();

    /// Per-partition ranges DROP_OLDEST reclaimed without a seal (#1352, [#markReclaimed]): `start -> end`. They
    /// count towards the contiguous watermark exactly as a sealed segment does and are held by nothing else —
    /// the tiered reader never sees them, so a read inside one is "reclaimed", never a hole. In memory only: a
    /// restart re-anchors the watermark at the lowest surviving ref, which treats a reclaimed prefix below it
    /// as reclaimed history anyway (see [#lastSealedOffset]).
    private final ConcurrentHashMap<PartitionKey, ConcurrentSkipListMap<Long, Long>> reclaimed = new ConcurrentHashMap<>();

    /// Per-partition CONTIGUOUS sealed watermark (#1234): every offset at or below it has been durably
    /// sealed or reclaimed without a seal (#1352). Advanced only by [#addSegment] and [#markReclaimed], never
    /// lowered by [#removeSegment] — see [#lastSealedOffset].
    private final ConcurrentHashMap<PartitionKey, Long> sealedThrough = new ConcurrentHashMap<>();

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
        advanceWatermark(key, map);
    }

    /// `[fromOffset, toOffset]` of `(streamName, partition)` was reclaimed by DROP_OLDEST WITHOUT a seal (#1352):
    /// the events were never acknowledged, so they are not in the log, and a drop is retention reclamation, not
    /// a failed seal. The watermark advances over the range as it would over a sealed segment, so WAL
    /// truncation proceeds past it and a read from inside it reports `CursorExpired` (reclaimed), never
    /// [SegmentError.SealedRangeMissing] (a seal that FAILED). A range that is not yet contiguous with the
    /// watermark — an earlier seal still in flight — is kept and counted once that seal lands.
    @Contract
    @Override
    public Unit markReclaimed(String streamName, int partition, long fromOffset, long toOffset) {
        var key = PartitionKey.partitionKey(streamName, partition);

        reclaimed.computeIfAbsent(key, _ -> new ConcurrentSkipListMap<>()).merge(fromOffset, toOffset, Math::max);
        advanceWatermark(key, partitions.computeIfAbsent(key, _ -> new ConcurrentSkipListMap<>()));

        return unit();
    }

    private void advanceWatermark(PartitionKey key, ConcurrentSkipListMap<Long, SegmentRef> map) {
        sealedThrough.compute(key,
                              (_, current) -> sealedOrReclaimedEnd(map,
                                                                   reclaimedOf(key),
                                                                   option(current).or(NOTHING_SEALED)));
    }

    private ConcurrentSkipListMap<Long, Long> reclaimedOf(PartitionKey key) {
        return option(reclaimed.get(key)).or(NOTHING_RECLAIMED);
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

    /// The CONTIGUOUS sealed watermark for `(streamName, partition)`: the highest offset at or below which
    /// EVERY offset has been durably sealed into a segment or reclaimed without a seal (#1352,
    /// [#markReclaimed]) — the lowest offset that is neither, minus 1 — or `-1` when offset 0 is neither. It bounds WAL truncation (records at or below it are discarded) and WAL replay on
    /// partition recovery (the recovered ring skips records at or below it, served by the tiered reader), so
    /// it must never pass a hole: until #1234 it was the MAXIMUM sealed `endOffset`, and a later successful
    /// seal licensed truncating the WAL past a segment that had failed to seal — permanent silent loss.
    ///
    /// Two properties callers rely on:
    ///   - **Monotonic.** Retention reclaiming a sealed segment ([#removeSegment]) does not un-seal it. A
    ///     lowered watermark would make recovery seed a ring below a WAL already truncated past it, and the
    ///     replay would then assign the surviving records the wrong offsets.
    ///   - **Anchored at offset 0 while the node runs, at the lowest surviving ref after a restart.**
    ///     [#rebuildFromRefs] only sees the refs that survived, and a prefix reclaimed by retention is
    ///     indistinguishable from one that was never sealed, so the rebuilt watermark starts at the lowest
    ///     surviving ref and still stops at the first hole above it. A never-sealed prefix below every
    ///     surviving ref is therefore not detected across a restart; [SegmentSealer] seals strictly in offset
    ///     order (one seal in flight per partition), so it produces no such prefix. When retention has
    ///     reclaimed EVERY ref of a partition nothing anchors the rebuild at all (#1278).
    @Override
    public long lastSealedOffset(String streamName, int partition) {
        return option(sealedThrough.get(PartitionKey.partitionKey(streamName, partition))).or(NOTHING_SEALED);
    }

    /// The watermark walk: sealed segments and reclaimed ranges extend it in turn until neither reaches
    /// past it — a reclaimed range may bridge two sealed runs and a sealed run may bridge two reclaimed ones.
    private static long sealedOrReclaimedEnd(ConcurrentSkipListMap<Long, SegmentRef> map,
                                             ConcurrentSkipListMap<Long, Long> reclaimed,
                                             long through) {
        var end = through;

        for (var next = extend(map, reclaimed, end); next > end; next = extend(map, reclaimed, end)) {
            end = next;
        }

        return end;
    }

    private static long extend(ConcurrentSkipListMap<Long, SegmentRef> map,
                               ConcurrentSkipListMap<Long, Long> reclaimed,
                               long through) {
        return Math.max(contiguousEnd(map, through), reclaimedEnd(reclaimed, through));
    }

    /// [#contiguousEnd] over reclaimed ranges: extend `through` across every range starting at or before
    /// `through + 1`.
    private static long reclaimedEnd(ConcurrentSkipListMap<Long, Long> reclaimed, long through) {
        var end = through;

        for (var range : reclaimed.tailMap(option(reclaimed.floorKey(through + 1)).or(Long.MIN_VALUE)).entrySet()) {
            if (range.getKey() > end + 1) {
                break;
            }

            end = Math.max(end, range.getValue());
        }

        return end;
    }

    /// Extend `through` across every segment that starts at or before `through + 1`, walking the map in
    /// start order and stopping at the first segment that would leave a gap. Segments only: the tiered
    /// reader bounds a read by this, and a read must stop at a reclaimed range (the next read then starts
    /// inside it and is told it expired) rather than skip over it. The walk begins at the segment
    /// with the greatest start at or below `through + 1`; a segment starting earlier that overlaps past it
    /// (only a re-seal with different boundaries produces one) is not consulted, which can only leave the
    /// watermark LOWER — the direction that keeps more WAL, never the direction that loses it.
    private static long contiguousEnd(ConcurrentSkipListMap<Long, SegmentRef> map, long through) {
        var end = through;

        for (var ref : map.tailMap(option(map.floorKey(through + 1)).or(Long.MIN_VALUE)).values()) {
            if (ref.startOffset() > end + 1) {
                break;
            }

            end = Math.max(end, ref.endOffset());
        }

        return end;
    }

    /// End offset of the contiguous run of sealed segments that starts with the segment holding
    /// `fromOffset`, or [Option#none] when no sealed segment holds it. The tiered reader reads no further
    /// than this, so a hole above the run is never skipped (#1234).
    public Option<Long> contiguousSealedEnd(String streamName, int partition, long fromOffset) {
        return option(partitions.get(PartitionKey.partitionKey(streamName, partition))).flatMap(map -> findContainingEnd(map,
                                                                                                                         fromOffset));
    }

    private static Option<Long> findContainingEnd(ConcurrentSkipListMap<Long, SegmentRef> map, long fromOffset) {
        return option(map.floorEntry(fromOffset)).map(Map.Entry::getValue)
                     .filter(ref -> ref.containsOffset(fromOffset))
                     .map(ref -> contiguousEnd(map,
                                               ref.endOffset()));
    }

    /// Start offset of the first sealed segment above `fromOffset`, or [Option#none] when nothing is sealed
    /// above it. With no segment holding `fromOffset`, a present value means `[fromOffset, next)` is a hole.
    public Option<Long> nextSealedOffset(String streamName, int partition, long fromOffset) {
        return option(partitions.get(PartitionKey.partitionKey(streamName, partition))).flatMap(map -> option(map.higherKey(fromOffset)));
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
        reclaimed.clear();
        sealedThrough.clear();
        metadataStore.listAllRefs()
                     .keySet()
                     .stream()
                     .filter(ref -> ref.startsWith(STREAMS_PREFIX))
                     .forEach(this::parseAndAddRef);
        partitions.forEach(this::anchorAtLowestRef);
    }

    /// Re-anchor a rebuilt partition's watermark at its lowest surviving ref (see [#lastSealedOffset]):
    /// the refs were added in listing order, anchored at offset 0, which a retention-reclaimed prefix would
    /// otherwise pin at `-1` forever.
    private void anchorAtLowestRef(PartitionKey key, ConcurrentSkipListMap<Long, SegmentRef> map) {
        sealedThrough.put(key, contiguousEnd(map, map.firstKey() - 1));
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
