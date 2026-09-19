// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class SegmentSealer implements EvictionListener {
    private static final int PER_EVENT_HEADER = Long.BYTES + Long.BYTES + Integer.BYTES;

    /// Bounded retry of one seal (#1234): 5 attempts over ~1.5 s absorb a transient storage error without
    /// the ring noticing. Giving up is not final — the ring keeps the events and hands them over again on
    /// its next eviction pass, which starts a fresh budget — so this bound only caps how long one attempt
    /// cycle holds the partition's single in-flight seal.
    private static final Retry SEAL_RETRY = Retry.retry()
                                                 .attempts(5)
                                                 .strategy(BackoffStrategy.exponential()
                                                                          .initialDelay(timeSpan(100).millis())
                                                                          .maxDelay(timeSpan(1).seconds())
                                                                          .factor(2.0)
                                                                          .withJitter());

    private final SegmentSink sink;

    private SegmentSealer(SegmentSink sink) {
        this.sink = sink;
    }

    public static SegmentSealer segmentSealer(SegmentSink sink) {
        return new SegmentSealer(sink);
    }

    /// Seal `events` as one segment and return the sink's outcome, retried within [#SEAL_RETRY]. Until
    /// #1234 the sink's promise was dropped here, so a failed seal was indistinguishable from a durable one.
    @Override
    public Promise<Unit> onEviction(String streamName, int partition, List<RawEvent> events) {
        if (events.isEmpty()) {
            return Promise.unitPromise();
        }

        var segment = buildSegment(streamName, partition, events);

        return SEAL_RETRY.execute(() -> sink.seal(segment));
    }

    private SealedSegment buildSegment(String streamName, int partition, List<RawEvent> events) {
        var startOffset = events.getFirst().offset();
        var endOffset = events.getLast().offset();
        var timestamps = extractTimestamps(events);
        var serialized = serializeEvents(events);

        return SealedSegment.sealedSegment(streamName,
                                           partition,
                                           startOffset,
                                           endOffset,
                                           events.size(),
                                           timestamps[0],
                                           timestamps[1],
                                           serialized);
    }

    private long[] extractTimestamps(List<RawEvent> events) {
        var min = Long.MAX_VALUE;
        var max = Long.MIN_VALUE;

        for (var event : events) {
            min = Math.min(min, event.timestamp());
            max = Math.max(max, event.timestamp());
        }

        return new long[]{min, max};
    }

    private byte[] serializeEvents(List<RawEvent> events) {
        var totalSize = events.stream().mapToInt(e -> PER_EVENT_HEADER + e.data().length).sum();
        var buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);

        events.forEach(event -> writeEvent(buffer, event));

        return buffer.array();
    }

    private void writeEvent(ByteBuffer buffer, RawEvent event) {
        var data = event.data();

        buffer.putLong(event.offset());
        buffer.putLong(event.timestamp());
        buffer.putInt(data.length);
        buffer.put(data);
    }
}
