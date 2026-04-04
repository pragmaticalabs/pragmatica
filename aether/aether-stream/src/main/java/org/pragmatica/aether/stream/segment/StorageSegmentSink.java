package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.StorageInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// SegmentSink that stores sealed segments in a StorageInstance.
/// Creates a named reference per segment: streams/{streamName}/{partition}/{startOffset}-{endOffset}
public final class StorageSegmentSink implements SegmentSink {
    private static final Logger log = LoggerFactory.getLogger(StorageSegmentSink.class);

    private final StorageInstance storage;
    private final SegmentIndex index;

    private StorageSegmentSink(StorageInstance storage, SegmentIndex index) {
        this.storage = storage;
        this.index = index;
    }

    public static StorageSegmentSink storageSegmentSink(StorageInstance storage, SegmentIndex index) {
        return new StorageSegmentSink(storage, index);
    }

    @Override public Promise<Unit> seal(SealedSegment segment) {
        return storage.put(segment.serializedEvents()).flatMap(blockId -> storage.createRef(refName(segment),
                                                                                            blockId))
                          .onSuccess(_ -> updateIndex(segment))
                          .onSuccess(_ -> logSealed(segment));
    }

    private void updateIndex(SealedSegment segment) {
        index.addSegment(segment.streamName(),
                         segment.partition(),
                         segment.startOffset(),
                         segment.endOffset(),
                         segment.maxTimestamp());
    }

    private void logSealed(SealedSegment segment) {
        log.debug("Sealed segment {} partition={} offsets=[{}-{}] events={}",
                  segment.streamName(),
                  segment.partition(),
                  segment.startOffset(),
                  segment.endOffset(),
                  segment.eventCount());
    }

    static String refName(SealedSegment segment) {
        return "streams/" + segment.streamName() + "/" + segment.partition() + "/" + segment.startOffset() + "-" + segment.endOffset();
    }
}
