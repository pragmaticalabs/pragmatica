// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Cause;


public sealed interface SegmentError extends Cause {
    enum General implements SegmentError {
        SEGMENT_REF_NOT_FOUND("Segment named reference not found in storage"),
        SEGMENT_DATA_NOT_FOUND("Segment data block not found in storage");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// `fromOffset` was handed to the segment sealer and its seal has not landed yet (#1234): the ring has
    /// reclaimed it, the sealer still retains it, and the partition WAL holds it. It is IN FLIGHT, not lost —
    /// a caller backs off and re-reads the same offset, which succeeds once the seal lands. Never a reason to
    /// skip the offset or dead-letter anything.
    record SealInFlight(String streamName, int partition, long fromOffset) implements SegmentError, Cause.Transient {
        @Override
        public String message() {
            return "Offset %d of %s/%d is being sealed to storage; retry the read".formatted(fromOffset,
                                                                                             streamName,
                                                                                             partition);
        }
    }

    /// `fromOffset` is MISSING: no sealed segment holds it, a later offset is sealed, it is above the
    /// contiguous sealed watermark (so retention did not reclaim it), and it is not in flight. That is a hole
    /// in the sealed range (#1234), reported instead of skipping to `nextSealedOffset` (the consumer would
    /// silently lose the hole) or answering an empty read (the consumer would stall at `fromOffset` forever).
    /// No retry of the read can fill it. The operator's recovery is the WAL: the sealed watermark never
    /// passes a hole, so WAL truncation keeps the range and a partition recovery replays it into the ring.
    record SealedRangeMissing(String streamName, int partition, long fromOffset, long nextSealedOffset) implements SegmentError, Cause.Terminal {
        @Override
        public String message() {
            return "Offsets [%d, %d) of %s/%d are in no sealed segment (hole in the sealed range)".formatted(fromOffset,
                                                                                                             nextSealedOffset,
                                                                                                             streamName,
                                                                                                             partition);
        }
    }
}
