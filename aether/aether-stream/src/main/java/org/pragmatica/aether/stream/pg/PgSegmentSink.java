// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.pg;

import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentSink;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
