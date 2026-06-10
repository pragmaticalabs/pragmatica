// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.Arrays;


public record SealedSegment(String streamName,
                            int partition,
                            long startOffset,
                            long endOffset,
                            int eventCount,
                            long minTimestamp,
                            long maxTimestamp,
                            byte[] serializedEvents) {
    public SealedSegment {
        serializedEvents = serializedEvents.clone();
    }

    public static SealedSegment sealedSegment(String streamName,
                                              int partition,
                                              long startOffset,
                                              long endOffset,
                                              int eventCount,
                                              long minTimestamp,
                                              long maxTimestamp,
                                              byte[] serializedEvents) {
        return new SealedSegment(streamName,
                                 partition,
                                 startOffset,
                                 endOffset,
                                 eventCount,
                                 minTimestamp,
                                 maxTimestamp,
                                 serializedEvents);
    }

    @Override
    public byte[] serializedEvents() {
        return serializedEvents.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SealedSegment other
               && partition == other.partition
               && startOffset == other.startOffset
               && endOffset == other.endOffset
               && eventCount == other.eventCount
               && minTimestamp == other.minTimestamp
               && maxTimestamp == other.maxTimestamp
               && streamName.equals(other.streamName)
               && Arrays.equals(serializedEvents, other.serializedEvents);
    }

    @Override
    public int hashCode() {
        var result = streamName.hashCode();

        result = 31 * result + partition;
        result = 31 * result + Long.hashCode(startOffset);
        result = 31 * result + Long.hashCode(endOffset);
        result = 31 * result + eventCount;
        result = 31 * result + Long.hashCode(minTimestamp);
        result = 31 * result + Long.hashCode(maxTimestamp);
        result = 31 * result + Arrays.hashCode(serializedEvents);

        return result;
    }
}
