// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.stream.LongStream;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// Test support (#2201 C-16): a sealed tier whose contents a test writes directly, so it can hold exactly the ranges a
/// scenario needs: a reclaimed prefix (sealed, then its segment removed below the watermark, which is what retention
/// does) or a hole (a range never sealed between two sealed ones). Event `i` carries the payload `evt-i`, the payload
/// the scenarios publish to the ring for the same offset.
final class SealedTierFixture {
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private final StorageInstance storage = StorageInstance.storageInstance("tier", List.of(MemoryTier.memoryTier(ONE_GB)));
    private final SegmentIndex index = new SegmentIndex();
    private final StorageSegmentSink sink = storageSegmentSink(storage, index);
    private final TieredStreamReader reader = tieredStreamReader(index, storage);

    TieredStreamReader reader() {
        return reader;
    }

    SegmentIndex index() {
        return index;
    }

    /// Seals `[from, to]` as one segment.
    void seal(String stream, int partition, long from, long to) {
        var events = LongStream.rangeClosed(from, to)
                               .mapToObj(offset -> RawEvent.rawEvent(offset, payload(offset), 1_000L + offset))
                               .toList();

        sink.seal(sealedSegment(stream, partition, from, to, events.size(), 1_000L + from, 1_000L + to, serialize(events)))
            .await()
            .onFailure(cause -> fail("seal [" + from + ", " + to + "]: " + cause.message()));
    }

    /// Removes the segment that starts at `from`: below the sealed watermark that is what retention leaves behind.
    void reclaim(String stream, int partition, long from) {
        index.removeSegment(stream, partition, from);
    }

    void shutdown() {
        storage.shutdown();
    }

    static byte[] payload(long offset) {
        return ("evt-" + offset).getBytes(UTF_8);
    }

    private static byte[] serialize(List<RawEvent> events) {
        var buffer = ByteBuffer.allocate(events.stream()
                                               .mapToInt(event -> Long.BYTES + Long.BYTES + Integer.BYTES + event.data().length)
                                               .sum())
                               .order(ByteOrder.BIG_ENDIAN);

        for (var event : events) {
            buffer.putLong(event.offset());
            buffer.putLong(event.timestamp());
            buffer.putInt(event.data().length);
            buffer.put(event.data());
        }

        return buffer.array();
    }
}
