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
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MetadataStore;

import static org.pragmatica.lang.Option.option;


public final class SegmentIndex {
    private static final long NOTHING_SEALED = -1L;

    private final ConcurrentHashMap<PartitionKey, ConcurrentSkipListMap<Long, SegmentRef>> partitions = new ConcurrentHashMap<>();

    /// Per-partition CONTIGUOUS sealed watermark (#1234): every offset at or below it has been durably
    /// sealed. Advanced only by [#addSegment] and never lowered by [#removeSegment] — see [#lastSealedOffset].
    private final ConcurrentHashMap<PartitionKey, Long> sealedThrough = new ConcurrentHashMap<>();
    /// Per-partition reclaimed-through floor (#1278): the highest offset retention has deliberately reclaimed.
    /// Persisted as a `stream-floors/` ref before the segment refs it licenses are dropped, so a rebuild can tell
    /// a reclaimed prefix from one whose refs were lost.
    private final ConcurrentHashMap<PartitionKey, Long> reclaimedThrough = new ConcurrentHashMap<>();
    /// #1278 review: the incarnation (one LIFE of the stream name) this index serves per stream, adopted when the
    /// stream's committed config materializes here. Every durable ref name embeds it ([#durableName]); a stream never
    /// adopted serves incarnation `0` (a config built outside a cluster create).
    private final ConcurrentHashMap<String, Long> adopted = new ConcurrentHashMap<>();
    /// Refs of a rebuilt listing that belong to an incarnation this index does not (yet) serve, by stream and
    /// incarnation. [#adopt] installs the matching ones and hands back the rest as garbage.
    private final ConcurrentHashMap<String, Map<Long, List<String>>> staged = new ConcurrentHashMap<>();

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
        sealedThrough.compute(key, (_, current) -> contiguousEnd(map, option(current).or(NOTHING_SEALED)));
    }

    /// Record a segment's max event timestamp learned after the fact (#1604): a ref rebuilt from its name
    /// after a restart carries no timestamp, and retention reads the block once to find it. Only a ref still
    /// present with an unknown timestamp is updated; a known one is never overwritten.
    @Contract
    public void recordMaxTimestamp(String streamName, int partition, long startOffset, long maxTimestamp) {
        option(partitions.get(PartitionKey.partitionKey(streamName, partition))).onPresent(map -> map.computeIfPresent(startOffset,
                                                                                                                       (_, ref) -> withKnownTimestamp(ref,
                                                                                                                                                      maxTimestamp)));
    }

    private static SegmentRef withKnownTimestamp(SegmentRef ref, long maxTimestamp) {
        return ref.maxTimestamp() > 0
               ? ref
               : SegmentRef.segmentRef(ref.startOffset(),
                                       ref.endOffset(),
                                       maxTimestamp,
                                       ref.compressionOrdinal(),
                                       ref.encrypted(),
                                       ref.originalSize());
    }

    @Contract
    public void removeSegment(String streamName, int partition, long startOffset) {
        var key = PartitionKey.partitionKey(streamName, partition);

        option(partitions.get(key)).onPresent(map -> map.remove(startOffset));
    }

    /// Record that retention reclaimed `(streamName, partition)` through `through`, AFTER its floor ref is
    /// durable ([#floorRefOf]). Never lowers the floor.
    @Contract
    public void recordReclaimed(String streamName, int partition, long through) {
        reclaimedThrough.merge(PartitionKey.partitionKey(streamName, partition), through, Math::max);
    }

    /// [#recordReclaimed] for the life `incarnation` decided the reclaim under: a retention pass that outlived its
    /// stream's destroy (or a recreate) must not resurrect the old life's floor in the new one.
    @Contract
    public void recordReclaimed(String streamName, long incarnation, int partition, long through) {
        if (incarnation == incarnationOf(streamName)) {
            recordReclaimed(streamName, partition, through);
        }
    }

    /// [#addSegment] for the life `incarnation` the seal was stored under: a seal still in flight when its stream
    /// was destroyed or recreated never lands in the new life's index.
    @Contract
    public void addSegment(String streamName,
                           long incarnation,
                           int partition,
                           long startOffset,
                           long endOffset,
                           long maxTimestamp,
                           int compressionOrdinal,
                           boolean encrypted,
                           int originalSize) {
        if (incarnation == incarnationOf(streamName)) {
            addSegment(streamName,
                       partition,
                       startOffset,
                       endOffset,
                       maxTimestamp,
                       compressionOrdinal,
                       encrypted,
                       originalSize);
        }
    }

    /// The incarnation this index serves for `streamName`: the adopted one, else `0`.
    public long incarnationOf(String streamName) {
        return option(adopted.get(streamName)).or(0L);
    }

    /// Serve incarnation `incarnation` of `streamName` from now on (#1278 review). A different life's in-memory
    /// state is dropped and the rebuilt refs of this one are installed. Returns the durable refs that belong to any
    /// OTHER life of the name — garbage, for the caller to drop best-effort; nothing reads them again.
    public List<String> adopt(String streamName, long incarnation) {
        var previous = incarnationOf(streamName);
        var garbage = new java.util.ArrayList<String>();

        if (previous != incarnation) {
            garbage.addAll(liveRefNames(streamName));
            dropLive(streamName);
        }

        adopted.put(streamName, incarnation);
        var byIncarnation = option(staged.remove(streamName)).or(Map.of());

        byIncarnation.forEach((life, refs) -> {
            if (life == incarnation) {
                refs.forEach(this::installRef);
            } else {
                garbage.addAll(refs);
            }
        });
        anchorStream(streamName);

        return garbage;
    }

    /// The incarnations adopted here, for an index rebuilt from a snapshot to serve the same lives
    /// ([org.pragmatica.aether.stream.DurableSealedOffsetSource]).
    public Map<String, Long> adoptions() {
        return Map.copyOf(adopted);
    }

    /// Adopt every life of `adoptions` without collecting garbage: a read-only view of the same lives.
    @Contract
    public void adoptAll(Map<String, Long> adoptions) {
        adoptions.forEach(this::adopt);
    }

    /// The reclaimed-through floor of `(streamName, partition)`, or `-1` when retention never reclaimed any of it.
    public long reclaimedThrough(String streamName, int partition) {
        return option(reclaimedThrough.get(PartitionKey.partitionKey(streamName, partition))).or(NOTHING_SEALED);
    }

    public List<SegmentRef> listSegments(String streamName, int partition) {
        return option(partitions.get(PartitionKey.partitionKey(streamName, partition))).map(map -> List.copyOf(map.values()))
                     .or(List.of());
    }

    /// The CONTIGUOUS sealed watermark for `(streamName, partition)`: the highest offset at or below which
    /// EVERY offset has been durably sealed into a segment (lowest unsealed offset - 1), or `-1` when offset
    /// 0 is not sealed. It bounds WAL replay on partition recovery (the recovered ring skips records at or
    /// below it, served by the tiered reader) and — computed from the refs in the metadata snapshot ON DISK,
    /// not from this live index (#1345, [org.pragmatica.aether.stream.DurableSealedOffsetSource]) — WAL
    /// truncation, so it must never pass a hole: until #1234 it was the MAXIMUM sealed `endOffset`, and a
    /// later successful seal licensed truncating the WAL past a segment that had failed to seal — permanent
    /// silent loss.
    ///
    /// Two properties callers rely on:
    ///   - **Monotonic.** Retention reclaiming a sealed segment ([#removeSegment]) does not un-seal it. A
    ///     lowered watermark would make recovery seed a ring below a WAL already truncated past it, and the
    ///     replay would then assign the surviving records the wrong offsets.
    ///   - **Anchored at offset 0 while the node runs, at the persisted reclaimed-through floor after a
    ///     restart (#1278).** Retention persists the floor before it drops the refs below it, so after a
    ///     [#rebuildFromRefs] the watermark starts at the floor (or `-1` when nothing was ever reclaimed) and
    ///     stops at the first hole above it. A prefix missing below the lowest surviving ref and above the
    ///     floor was NOT reclaimed: its refs were lost, and the watermark stays below it (#1014), so recovery
    ///     refuses a WAL compacted past it instead of treating the lost history as reclaimed. With every ref
    ///     reclaimed the watermark is the floor itself.
    public long lastSealedOffset(String streamName, int partition) {
        return option(sealedThrough.get(PartitionKey.partitionKey(streamName, partition))).or(NOTHING_SEALED);
    }

    /// Extend `through` across every segment that starts at or before `through + 1`, walking the map in
    /// start order and stopping at the first segment that would leave a gap. The walk begins at the segment
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
        rebuildFromRefs(metadataStore.listAllRefs());
    }

    /// Rebuild from a ref listing — the live store's at boot, or a metadata snapshot's (#1345: the sealed
    /// watermark a restart WOULD rebuild is the bound WAL truncation may use, and only the snapshot on disk
    /// can say what that is).
    @Contract
    public void rebuildFromRefs(Map<String, BlockId> refs) {
        partitions.clear();
        sealedThrough.clear();
        reclaimedThrough.clear();
        staged.clear();
        refs.keySet()
            .stream()
            .filter(ref -> ref.startsWith(FLOORS_PREFIX) || ref.startsWith(STREAMS_PREFIX))
            .forEach(this::placeRef);
        sealedThrough.clear();
        partitions.forEach(this::anchorAtFloor);
        reclaimedThrough.forEach(sealedThrough::putIfAbsent);
    }

    /// A ref of the life this index serves for its stream is installed; any other life's is staged for [#adopt].
    private void placeRef(String refName) {
        var name = DurableName.parse(streamSegment(refName));

        if (name.incarnation() == incarnationOf(name.streamName())) {
            installRef(refName);
        } else {
            staged.computeIfAbsent(name.streamName(),
                                   _ -> new ConcurrentHashMap<>())
                  .computeIfAbsent(name.incarnation(),
                                   _ -> new java.util.concurrent.CopyOnWriteArrayList<>())
                  .add(refName);
        }
    }

    private void installRef(String refName) {
        if (refName.startsWith(FLOORS_PREFIX)) {
            parseFloorRef(refName);
        } else {
            parseAndAddRef(refName);
        }
    }

    /// The `<stream>[@<incarnation>]` segment of a `streams/` or `stream-floors/` ref.
    private static String streamSegment(String refName) {
        var rest = refName.startsWith(FLOORS_PREFIX)
                   ? refName.substring(FLOORS_PREFIX.length())
                   : refName.substring(STREAMS_PREFIX.length());
        var slash = rest.indexOf('/');

        return slash < 0
               ? rest
               : rest.substring(0, slash);
    }

    private void anchorStream(String streamName) {
        partitions.forEach((key, map) -> {
            if (key.streamName()
                   .equals(streamName)) {
                anchorAtFloor(key, map);
            }
        });
        reclaimedThrough.forEach((key, floor) -> {
            if (key.streamName()
                   .equals(streamName)) {
                sealedThrough.putIfAbsent(key, floor);
            }
        });
    }

    private List<String> liveRefNames(String streamName) {
        var names = new java.util.ArrayList<String>();

        partitions.forEach((key, map) -> {
            if (key.streamName()
                   .equals(streamName)) {
                map.values()
                   .forEach(ref -> names.add(refNameOf(streamName,
                                                       key.partition(),
                                                       ref)));
            }
        });
        reclaimedThrough.forEach((key, floor) -> {
            if (key.streamName()
                   .equals(streamName)) {
                names.add(floorRefOf(streamName, key.partition(), floor));
            }
        });

        return names;
    }

    private void dropLive(String streamName) {
        partitions.keySet().removeIf(key -> key.streamName()
                                               .equals(streamName));
        sealedThrough.keySet().removeIf(key -> key.streamName()
                                                  .equals(streamName));
        reclaimedThrough.keySet().removeIf(key -> key.streamName()
                                                     .equals(streamName));
    }

    /// Re-anchor a rebuilt partition's watermark at its persisted reclaimed-through floor (see
    /// [#lastSealedOffset]): only a prefix retention recorded as reclaimed counts as sealed below the lowest
    /// surviving ref.
    private void anchorAtFloor(PartitionKey key, ConcurrentSkipListMap<Long, SegmentRef> map) {
        sealedThrough.put(key,
                          contiguousEnd(map,
                                        option(reclaimedThrough.get(key)).or(NOTHING_SEALED)));
    }

    /// `stream-floors/<stream>[@<incarnation>]/<partition>/<through>`; a partition may briefly hold two (the new one
    /// is written before the old one is dropped), and the highest wins. Installed only for the life this index serves.
    private void parseFloorRef(String refName) {
        var parts = refName.substring(FLOORS_PREFIX.length()).split("/");

        if (parts.length != 3) {
            return;
        }

        var streamName = DurableName.parse(parts[0]).streamName();

        Number.parseInt(parts[1]).onSuccess(partition -> Number.parseLong(parts[2]).onSuccess(through -> recordReclaimed(streamName,
                                                                                                                         partition,
                                                                                                                         through)));
    }

    /// Stop serving `streamName`: its in-memory state and its adopted life are dropped, so nothing of it anchors
    /// anything until a config of the name is adopted again. Returns the durable refs of the life it served, for the
    /// caller to drop best-effort.
    public List<String> forgetStream(String streamName) {
        var garbage = liveRefNames(streamName);

        dropLive(streamName);
        adopted.remove(streamName);
        staged.remove(streamName);

        return garbage;
    }

    /// `<stream>` for incarnation `0`, `<stream>@<incarnation>` otherwise: the name every durable artifact of one life
    /// of a stream is keyed by — its WAL directory and its `streams/` and `stream-floors/` refs (#1278 review).
    public static String durableName(String streamName, long incarnation) {
        return incarnation == 0L
               ? streamName
               : streamName + INCARNATION_SEPARATOR + incarnation;
    }

    /// The life a `streams/` or `stream-floors/` ref belongs to; none for any other ref.
    public static Option<DurableName> lifeOf(String refName) {
        return refName.startsWith(FLOORS_PREFIX) || refName.startsWith(STREAMS_PREFIX)
               ? Option.some(DurableName.parse(streamSegment(refName)))
               : Option.none();
    }

    /// A parsed [#durableName]. A name with no numeric `@<incarnation>` suffix is incarnation `0`.
    public record DurableName(String streamName, long incarnation) {
        static DurableName parse(String durable) {
            var at = durable.lastIndexOf(INCARNATION_SEPARATOR);

            return at < 0
                   ? new DurableName(durable, 0L)
                   : Number.parseLong(durable.substring(at + 1))
                           .map(incarnation -> new DurableName(durable.substring(0, at),
                                                               incarnation))
                           .or(new DurableName(durable, 0L));
        }
    }

    /// The ref recording that `(streamName, partition)` of the life this index serves was reclaimed through
    /// `through` (#1278).
    public String floorRefOf(String streamName, int partition, long through) {
        return floorRefName(durableName(streamName, incarnationOf(streamName)), partition, through);
    }

    /// The ref of a sealed segment of `(streamName, partition)` in the life this index serves.
    public String refNameOf(String streamName, int partition, SegmentRef ref) {
        return buildRefName(durableName(streamName, incarnationOf(streamName)), partition, ref);
    }

    /// The ref recording that `(durableName, partition)` was reclaimed through `through` (#1278). `durableName` is
    /// the stream name for incarnation `0`.
    public static String floorRefName(String durableName, int partition, long through) {
        return floorRefPrefix(durableName, partition) + through;
    }

    /// The prefix every floor ref of `(durableName, partition)` starts with.
    public static String floorRefPrefix(String durableName, int partition) {
        return FLOORS_PREFIX + durableName + "/" + partition + "/";
    }

    private void parseAndAddRef(String refName) {
        var parts = refName.substring(STREAMS_PREFIX.length()).split("/");

        if (parts.length != 3) {
            return;
        }

        var streamName = DurableName.parse(parts[0]).streamName();

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

    /// The ref of a sealed segment under `durableName` (the stream name for incarnation `0`).
    static String buildRefName(String durableName, int partition, SegmentRef ref) {
        return STREAMS_PREFIX + durableName + "/" + partition + "/" + ref.startOffset() + "-" + ref.endOffset();
    }

    private static final String STREAMS_PREFIX = "streams/";
    private static final String FLOORS_PREFIX = "stream-floors/";

    private static final String INCARNATION_SEPARATOR = "@";

    public record PartitionKey(String streamName, int partition) {
        public static PartitionKey partitionKey(String streamName, int partition) {
            return new PartitionKey(streamName, partition);
        }
    }
}
