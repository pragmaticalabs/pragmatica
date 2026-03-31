package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;

/// Destination for sealed segments. Implementations store segments in AHSE, disk, etc.
@FunctionalInterface
public interface SegmentSink {

    /// Persist the sealed segment to durable storage.
    Promise<Unit> seal(SealedSegment segment);

    /// No-op sink that discards segments (for testing).
    SegmentSink DISCARD = _ -> Promise.success(unit());
}
