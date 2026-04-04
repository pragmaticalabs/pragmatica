package org.pragmatica.aether.stream.segment;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.StorageInstance;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Cross-tier read optimization for streaming segments.
///
/// Wraps the segment read path with:
/// 1. Segment index lookup for offset-to-segment resolution
/// 2. Reads via StorageInstance (which does tier-waterfall internally)
/// 3. Async prefetch of the next segment when reading near the end of a segment
public sealed interface TieredStreamReader {
    Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents);
    Promise<Unit> prefetch(String streamName, int partition, long fromOffset);

    static TieredStreamReader tieredStreamReader(SegmentIndex index, StorageInstance storage) {
        return new TieredReader(index, storage);
    }
}

final class TieredReader implements TieredStreamReader {
    private static final Logger log = LoggerFactory.getLogger(TieredReader.class);

    private static final double PREFETCH_THRESHOLD = 0.8;

    private final SegmentIndex index;
    private final SegmentReader reader;
    private final StorageInstance storage;

    TieredReader(SegmentIndex index, StorageInstance storage) {
        this.index = index;
        this.reader = SegmentReader.segmentReader(storage, index);
        this.storage = storage;
    }

    @Override public Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents) {
        return reader.readEvents(streamName, partition, fromOffset, maxEvents)
                                .onSuccess(events -> triggerPrefetchIfNearEnd(streamName,
                                                                              partition,
                                                                              fromOffset,
                                                                              maxEvents,
                                                                              events));
    }

    @Override public Promise<Unit> prefetch(String streamName, int partition, long fromOffset) {
        return index.findSegment(streamName, partition, fromOffset).map(ref -> warmSegment(streamName, partition, ref))
                                .or(Promise.unitPromise());
    }

    private void triggerPrefetchIfNearEnd(String streamName,
                                          int partition,
                                          long fromOffset,
                                          int maxEvents,
                                          List<RawEvent> events) {
        if (events.isEmpty()) {return;}
        var lastReadOffset = events.getLast().offset();
        index.findSegment(streamName, partition, lastReadOffset).filter(ref -> isNearSegmentEnd(lastReadOffset, ref))
                         .onPresent(ref -> prefetchNextSegment(streamName, partition, ref));
    }

    private static boolean isNearSegmentEnd(long currentOffset, SegmentIndex.SegmentRef ref) {
        var segmentSize = ref.endOffset() - ref.startOffset() + 1;
        var positionInSegment = currentOffset - ref.startOffset();
        return positionInSegment >= segmentSize * PREFETCH_THRESHOLD;
    }

    private void prefetchNextSegment(String streamName, int partition, SegmentIndex.SegmentRef currentRef) {
        var nextOffset = currentRef.endOffset() + 1;
        index.findSegment(streamName, partition, nextOffset)
                         .onPresent(nextRef -> firePrefetch(streamName, partition, nextRef));
    }

    private void firePrefetch(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        log.debug("Prefetching segment {}/{}:[{}-{}]", streamName, partition, ref.startOffset(), ref.endOffset());
        warmSegment(streamName, partition, ref).onFailure(cause -> log.debug("Prefetch failed for {}/{}:[{}-{}]: {}",
                                                                             streamName,
                                                                             partition,
                                                                             ref.startOffset(),
                                                                             ref.endOffset(),
                                                                             cause.message()));
    }

    private Promise<Unit> warmSegment(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        var refName = SegmentIndex.buildRefName(streamName, partition, ref);
        return storage.resolveRef(refName).map(blockId -> storage.get(blockId).mapToUnit())
                                 .or(Promise.unitPromise());
    }
}
