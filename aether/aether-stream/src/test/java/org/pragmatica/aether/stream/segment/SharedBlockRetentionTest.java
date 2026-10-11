// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.RetentionEnforcer.retentionEnforcer;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1604: segment blocks are content-addressed, and the segment encoding carries offsets, event timestamps and
/// payloads but NOT the stream or partition -- so two partitions holding the same events share ONE block.
/// Reclaiming one partition's segment must not take the other's: retention drops its own ref, and the block
/// goes only when no ref is left (garbage collection, never a delete by block id).
class SharedBlockRetentionTest {
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long EVENT_TIME = 1_000L;
    private static final String STREAM_A = "orders";
    private static final String STREAM_B = "audit";

    @Test
    void reclaimingOnePartitionsSegment_keepsTheSharedBlockForTheOther() {
        var storage = StorageInstance.storageInstance("shared", List.of(MemoryTier.memoryTier(ONE_GB)));
        var index = new SegmentIndex();
        var sink = storageSegmentSink(storage, index);
        var bytes = encoded(List.of(event(0, "same"), event(1, "payload"), event(2, "everywhere")));

        sink.seal(sealedSegment(STREAM_A, 0, 0, 2, 3, EVENT_TIME, EVENT_TIME, bytes)).await();
        sink.seal(sealedSegment(STREAM_B, 0, 0, 2, 3, EVENT_TIME, EVENT_TIME, bytes)).await();

        assertThat(storage.resolveRef("streams/orders/0/0-2"))
            .as("identical events in two streams dedup onto one block")
            .isEqualTo(storage.resolveRef("streams/audit/0/0-2"));

        retentionEnforcer(storage, index, 1L, (stream, _) -> STREAM_A.equals(stream) ? Long.MAX_VALUE : -1L).enforceNow().await();

        assertThat(storage.resolveRef("streams/orders/0/0-2").isPresent()).as("A's segment was reclaimed").isFalse();
        var readB = SegmentReader.segmentReader(storage, index).readEvents(STREAM_B, 0, 0, 3).await();

        assertThat(readB.isSuccess()).as("B's read of the shared block: %s", readB).isTrue();
        assertThat(readB.unwrap()).extracting(RawEvent::offset).containsExactly(0L, 1L, 2L);
    }

    private static RawEvent event(long offset, String payload) {
        return new RawEvent(offset, payload.getBytes(StandardCharsets.UTF_8), EVENT_TIME);
    }

    /// The sealer's frame format ([SegmentSealer]'s `writeEvent`): u64 offset, u64 timestamp, u32 length, payload.
    private static byte[] encoded(List<RawEvent> events) {
        var size = events.stream().mapToInt(e -> 20 + e.data().length).sum();
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);

        events.forEach(e -> buffer.putLong(e.offset()).putLong(e.timestamp()).putInt(e.data().length).put(e.data()));

        return buffer.array();
    }
}
