package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentIndex.SegmentRef;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Handles governor failover by catching up from AHSE segments and replica watermarks.
/// Recovery sequence:
/// 1. Query watermarks from surviving replicas (via ReplicaRegistry)
/// 2. Find the highest watermark (most up-to-date replica)
/// 3. Read AHSE segments from that watermark to the end of sealed segments
/// 4. Replay events into the new governor's ring buffer (via StreamPartitionRecovery)
/// 5. Resume accepting new writes
public sealed interface GovernorFailoverHandler {
    /// Handle failover: catch up from AHSE segments and replica watermarks.
    Promise<Unit> handleFailover(String streamName,
                                 int partition,
                                 WatermarkTracker localWatermarks,
                                 SegmentIndex segmentIndex,
                                 SegmentReader segmentReader);

    /// Factory
    static GovernorFailoverHandler governorFailoverHandler(ReplicaRegistry registry,
                                                           StreamPartitionRecovery partitionRecovery) {
        return new DefaultGovernorFailoverHandler(registry, partitionRecovery);
    }

    record unused() implements GovernorFailoverHandler {
        @Override public Promise<Unit> handleFailover(String streamName,
                                                      int partition,
                                                      WatermarkTracker localWatermarks,
                                                      SegmentIndex segmentIndex,
                                                      SegmentReader segmentReader) {
            return Promise.success(Unit.unit());
        }
    }
}


final class DefaultGovernorFailoverHandler implements GovernorFailoverHandler {
    private static final Logger log = LoggerFactory.getLogger(DefaultGovernorFailoverHandler.class);

    private static final int MAX_EVENTS_PER_SEGMENT_READ = 10_000;

    private final ReplicaRegistry registry;
    private final StreamPartitionRecovery partitionRecovery;

    DefaultGovernorFailoverHandler(ReplicaRegistry registry, StreamPartitionRecovery partitionRecovery) {
        this.registry = registry;
        this.partitionRecovery = partitionRecovery;
    }

    @Override public Promise<Unit> handleFailover(String streamName,
                                                   int partition,
                                                   WatermarkTracker localWatermarks,
                                                   SegmentIndex segmentIndex,
                                                   SegmentReader segmentReader) {
        var catchupOffset = determineCatchupOffset(streamName, partition, localWatermarks);
        var segments = segmentIndex.listSegments(streamName, partition);
        return catchupOffset.fold(
            () -> handleNoWatermark(streamName, partition, segments, segmentReader),
            offset -> handleWithWatermark(streamName, partition, offset, segments, segmentReader)
        );
    }

    private Promise<Unit> handleNoWatermark(String streamName,
                                             int partition,
                                             List<SegmentRef> segments,
                                             SegmentReader segmentReader) {
        if (segments.isEmpty()) {
            log.info("Failover {}/{}  no watermark, no segments -- nothing to replay", streamName, partition);
            return Promise.success(Unit.unit());
        }
        return replaySegments(streamName, partition, segments.getFirst().startOffset(), segments, segmentReader);
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
                 streamName, partition, fromOffset, segments.size());
        return segmentReader.readEvents(streamName, partition, fromOffset, MAX_EVENTS_PER_SEGMENT_READ)
                            .map(events -> applyEvents(streamName, partition, events))
                            .mapToUnit();
    }

    private long applyEvents(String streamName, int partition, List<RawEvent> events) {
        var count = 0L;
        for (var event : events) {
            partitionRecovery.appendRecoveredEvent(streamName, partition, event.data(), event.timestamp());
            count++;
        }
        log.info("Failover {}/{} replayed {} event(s)", streamName, partition, count);
        return count;
    }

    private Option<Long> determineCatchupOffset(String streamName,
                                                 int partition,
                                                 WatermarkTracker localWatermarks) {
        var localWm = localWatermarks.watermark(streamName, partition);
        var replicaWm = highestReplicaWatermark(streamName, partition);
        return bestWatermark(localWm, replicaWm).map(wm -> wm + 1);
    }

    private Option<Long> highestReplicaWatermark(String streamName, int partition) {
        var replicas = registry.replicasFor(streamName, partition);
        if (replicas.isEmpty()) { return Option.none(); }
        var max = replicas.stream()
                          .mapToLong(ReplicaDescriptor::confirmedOffset)
                          .max();
        return Option.from(max.stream().boxed().findFirst());
    }

    private static Option<Long> bestWatermark(Option<Long> a, Option<Long> b) {
        return a.fold(
            () -> b,
            aVal -> b.fold(
                () -> a,
                bVal -> Option.some(Math.max(aVal, bVal))
            )
        );
    }

    private static List<SegmentRef> filterSegmentsFrom(List<SegmentRef> segments, long fromOffset) {
        return segments.stream()
                       .filter(ref -> ref.endOffset() >= fromOffset)
                       .toList();
    }
}
