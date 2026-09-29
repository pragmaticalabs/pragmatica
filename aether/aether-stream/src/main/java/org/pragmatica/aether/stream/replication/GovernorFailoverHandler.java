// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentIndex.SegmentRef;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.AppendLog.EpochStart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public sealed interface GovernorFailoverHandler {
    Promise<Unit> handleFailover(String streamName,
                                 int partition,
                                 WatermarkTracker localWatermarks,
                                 SegmentIndex segmentIndex,
                                 SegmentReader segmentReader);

    /// `durability` is the replica WAL barrier a replay run commits through once it has applied its last
    /// event (#1244 × #1235); production wires `StreamPartitionManager::syncReplicated`, WAL-less callers
    /// pass [ReplicationReceiveHandler#NO_DURABILITY_BARRIER].
    static GovernorFailoverHandler governorFailoverHandler(ReplicaRegistry registry,
                                                           AlignedRecovery partitionRecovery,
                                                           ReplicationReceiveHandler.ReplicaDurability durability) {
        return new DefaultGovernorFailoverHandler(registry, partitionRecovery, durability);
    }

    record unused() implements GovernorFailoverHandler {
        @Override
        public Promise<Unit> handleFailover(String streamName,
                                            int partition,
                                            WatermarkTracker localWatermarks,
                                            SegmentIndex segmentIndex,
                                            SegmentReader segmentReader) {
            return Promise.success(Unit.unit());
        }
    }
}

/// #1244 backfill-commit ruling, applied to this failover path on 2026-09-20 (CTO ruling, #1235 × #1244):
/// replica WAL frames carry no per-record fsync and a WAL-backed record becomes visible on this replica
/// only at the barrier, so a replay run commits through the replica WAL barrier
/// (`StreamPartitionManager::syncReplicated`) ONCE, after its last `appendRecovered` — one fsync per
/// run, and the replayed records are visible here when the run completes instead of when the next live
/// batch's barrier happens to cover them. The segments it reads are already durable elsewhere; the barrier
/// is what makes them durable and visible HERE.
///
/// #1505 F1: each replayed event lands at ITS OWN segment offset through {@link AlignedRecovery}, the same
/// ordered section the live receive and the catch-up apply use. Sealed segments hold events this node's ring
/// has already evicted or still holds, and the replay floor comes from a registry watermark that can lag the
/// ring. So a replayed offset is usually already held: it is verified and skipped, never re-appended at the
/// tail. An offset the ring has evicted is passed over. The first other refusal stops the replay and fails
/// the run: a gap, or a divergent held entry, which quarantines the partition.
final class DefaultGovernorFailoverHandler implements GovernorFailoverHandler {
    private static final Logger log = LoggerFactory.getLogger(DefaultGovernorFailoverHandler.class);
    private static final int MAX_EVENTS_PER_SEGMENT_READ = 10_000;

    private final ReplicaRegistry registry;
    private final AlignedRecovery partitionRecovery;
    private final ReplicationReceiveHandler.ReplicaDurability durability;

    DefaultGovernorFailoverHandler(ReplicaRegistry registry,
                                   AlignedRecovery partitionRecovery,
                                   ReplicationReceiveHandler.ReplicaDurability durability) {
        this.registry = registry;
        this.partitionRecovery = partitionRecovery;
        this.durability = durability;
    }

    @Override
    public Promise<Unit> handleFailover(String streamName,
                                        int partition,
                                        WatermarkTracker localWatermarks,
                                        SegmentIndex segmentIndex,
                                        SegmentReader segmentReader) {
        var catchupOffset = determineCatchupOffset(streamName, partition, localWatermarks);
        var segments = segmentIndex.listSegments(streamName, partition);

        return catchupOffset.fold(() -> handleNoWatermark(streamName, partition, segments, segmentReader),
                                  offset -> handleWithWatermark(streamName, partition, offset, segments, segmentReader));
    }

    private Promise<Unit> handleNoWatermark(String streamName,
                                            int partition,
                                            List<SegmentRef> segments,
                                            SegmentReader segmentReader) {
        if (segments.isEmpty()) {
            log.info("Failover {}/{}  no watermark, no segments -- nothing to replay", streamName, partition);

            return Promise.success(Unit.unit());
        }

        return replaySegments(streamName,
                              partition,
                              segments.getFirst().startOffset(),
                              segments,
                              segmentReader);
    }

    private Promise<Unit> handleWithWatermark(String streamName,
                                              int partition,
                                              long catchupOffset,
                                              List<SegmentRef> segments,
                                              SegmentReader segmentReader) {
        var relevantSegments = filterSegmentsFrom(segments, catchupOffset);

        if (relevantSegments.isEmpty()) {
            log.info("Failover {}/{} from offset {} -- no segments to replay", streamName, partition, catchupOffset);

            return Promise.success(Unit.unit());
        }

        return replaySegments(streamName, partition, catchupOffset, relevantSegments, segmentReader);
    }

    private Promise<Unit> replaySegments(String streamName,
                                         int partition,
                                         long fromOffset,
                                         List<SegmentRef> segments,
                                         SegmentReader segmentReader) {
        log.info("Failover {}/{} replaying from offset {} across {} segment(s)",
                 streamName,
                 partition,
                 fromOffset,
                 segments.size());

        return segmentReader.readEvents(streamName, partition, fromOffset, MAX_EVENTS_PER_SEGMENT_READ)
                            .flatMap(events -> installThenApply(streamName, partition, segments, segmentReader, events))
                            .flatMap(_ -> durability.sync(streamName, partition));
    }

    /// #1596: the replayed records' provenance is installed BEFORE any of them is appended. It is the slice the
    /// segment holding the LAST replayed record carries -- the sealing log's history over `[base, its end]`, so it
    /// covers every record read -- checked against this copy's own history first (N13, as a catch-up is). A
    /// segment without one (sealed before slices existed, or its header lost) falls back to `UNKNOWN(d)` for
    /// whatever lands past this copy's head: fail closed, never attributed to the epoch before it.
    ///
    /// When replay reaches past the head at all, from code at the change: the index lists only segments this
    /// node's own sink sealed, and a WAL-backed ring is rebuilt seeded at the contiguous sealed end with every
    /// WAL record above it, so with a WAL the replayed offsets sit at or below the head (verified, or passed
    /// over as evicted). Past-head appends come from a partition WITHOUT a log (Forge, the non-durable opt-in),
    /// which records no provenance at all. This path is the defense for a future tier or seed that breaks that.
    private Promise<Long> installThenApply(String streamName,
                                           int partition,
                                           List<SegmentRef> segments,
                                           SegmentReader segmentReader,
                                           List<RawEvent> events) {
        if (events.isEmpty()) {
            return Promise.success(0L);
        }

        var first = events.getFirst().offset();
        var last = events.getLast().offset();

        return sliceCovering(streamName, partition, segments, segmentReader, last).flatMap(slice -> applyPage(streamName,
                                                                                                              partition,
                                                                                                              first,
                                                                                                              last,
                                                                                                              slice,
                                                                                                              events).async());
    }

    /// The slice of the segment holding `offset`, decoded; none when no segment holds it, when the segment carries
    /// none, or when its entries do not decode (fail closed: unattributed).
    private static Promise<Option<List<ProvenanceEntry>>> sliceCovering(String streamName,
                                                                        int partition,
                                                                        List<SegmentRef> segments,
                                                                        SegmentReader segmentReader,
                                                                        long offset) {
        return Option.from(segments.stream()
                                   .filter(ref -> ref.startOffset() <= offset && offset <= ref.endOffset())
                                   .findFirst())
                     .map(ref -> segmentReader.readProvenance(streamName, partition, ref)
                                              .map(DefaultGovernorFailoverHandler::decoded))
                     .or(Promise.success(Option.none()));
    }

    private static Option<List<ProvenanceEntry>> decoded(Option<List<EpochStart>> slice) {
        return slice.flatMap(starts -> Result.allOf(starts.stream().map(ProvenanceEntry::provenanceEntry).toList()).option());
    }

    /// The install and its settlement wrap the appends (#1638 B1): a failed apply trims what the install recorded.
    private Result<Long> applyPage(String streamName,
                                   int partition,
                                   long first,
                                   long last,
                                   Option<List<ProvenanceEntry>> slice,
                                   List<RawEvent> events) {
        return slice.fold(() -> partitionRecovery.applyUnattributed(streamName,
                                                                    partition,
                                                                    last,
                                                                    () -> applyEvents(streamName, partition, events)),
                          entries -> partitionRecovery.applyAttributed(streamName,
                                                                       partition,
                                                                       first,
                                                                       last,
                                                                       entries,
                                                                       () -> applyEvents(streamName, partition, events)));
    }

    /// Sequential fail-fast fold: each event at its own offset. An evicted offset ([StreamError.CursorExpired])
    /// was held here once and is passed over; any other refusal stops the replay and fails the run.
    private Result<Long> applyEvents(String streamName, int partition, List<RawEvent> events) {
        for (var event : events) {
            var result = partitionRecovery.appendRecovered(streamName,
                                                           partition,
                                                           event.offset(),
                                                           event.data(),
                                                           event.timestamp());

            if (result.isFailure() && !isEvicted(result)) {
                return result;
            }
        }

        log.info("Failover {}/{} replayed {} event(s) at their own offsets", streamName, partition, events.size());

        return Result.success((long) events.size());
    }

    private static boolean isEvicted(Result<Long> result) {
        return result.fold(cause -> cause instanceof StreamError.CursorExpired, _ -> false);
    }

    private Option<Long> determineCatchupOffset(String streamName, int partition, WatermarkTracker localWatermarks) {
        var localWm = localWatermarks.watermark(streamName, partition);
        var replicaWm = highestReplicaWatermark(streamName, partition);

        return bestWatermark(localWm, replicaWm).map(wm -> wm + 1);
    }

    private Option<Long> highestReplicaWatermark(String streamName, int partition) {
        var replicas = registry.replicasFor(streamName, partition);

        if (replicas.isEmpty()) {
            return Option.none();
        }

        var max = replicas.stream().mapToLong(ReplicaDescriptor::confirmedOffset).max();

        return Option.from(max.stream().boxed().findFirst());
    }

    private static Option<Long> bestWatermark(Option<Long> a, Option<Long> b) {
        return a.flatMap(aVal -> b.map(bVal -> Math.max(aVal, bVal)))
                .orElse(a)
                .orElse(b);
    }

    private static List<SegmentRef> filterSegmentsFrom(List<SegmentRef> segments, long fromOffset) {
        return segments.stream()
                       .filter(ref -> ref.endOffset() >= fromOffset)
                       .toList();
    }
}
