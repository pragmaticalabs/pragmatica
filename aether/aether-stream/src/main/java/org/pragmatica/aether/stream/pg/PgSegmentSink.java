package org.pragmatica.aether.stream.pg;

import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentSink;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// SegmentSink that stores sealed segments in PostgreSQL via PgStreamStore.
///
/// Used as the cold-tier destination: the RetentionEnforcer can demote segments
/// to PostgreSQL instead of deleting them, enabling long-term durable retention.
public final class PgSegmentSink implements SegmentSink {
    private static final Logger log = LoggerFactory.getLogger(PgSegmentSink.class);

    private final PgStreamStore store;

    private PgSegmentSink(PgStreamStore store) {
        this.store = store;
    }

    public static PgSegmentSink pgSegmentSink(PgStreamStore store) {
        return new PgSegmentSink(store);
    }

    @Override
    public Promise<Unit> seal(SealedSegment segment) {
        return store.storeSegment(segment.streamName(),
                                  segment.partition(),
                                  segment.startOffset(),
                                  segment.endOffset(),
                                  segment.serializedEvents())
                    .onSuccess(_ -> logDemoted(segment));
    }

    private static void logDemoted(SealedSegment segment) {
        log.debug("Segment demoted to PG: {}/{}:[{}-{}] events={}",
                  segment.streamName(),
                  segment.partition(),
                  segment.startOffset(),
                  segment.endOffset(),
                  segment.eventCount());
    }
}
