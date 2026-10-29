// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// #2224 (I-32): a tier read returns events starting at `fromOffset`, or answers `CursorExpired(fromOffset, firstOffset)`.
/// `TieredReader.read` asks the index for the contiguous sealed end, then `SegmentReader.readEvents` asks it for the segment
/// range; retention removing the cursor's segment between the two used to start the read at the NEXT segment and return
/// its events with no `CursorExpired` (a silent skip).
class TieredReaderRetentionRaceTest {
    private static final String STREAM = "race-test";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    /// An index that lets retention reclaim the segment starting at `reclaimAt` inside `contiguousSealedEnd`, once, after
    /// the end has been computed: the interleaving the reader must survive.
    private static final class ReclaimingIndex extends SegmentIndex {
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private long reclaimAt;

        void reclaimInsideNextEndLookup(long segmentStart) {
            reclaimAt = segmentStart;
            armed.set(true);
        }

        @Override
        public Option<Long> contiguousSealedEnd(String streamName, int partition, long fromOffset) {
            var end = super.contiguousSealedEnd(streamName, partition, fromOffset);

            if (armed.compareAndSet(true, false)) {
                removeSegment(streamName, partition, reclaimAt);
            }

            return end;
        }
    }

    private ReclaimingIndex index;
    private StorageSegmentSink sink;
    private TieredStreamReader reader;

    @BeforeEach
    void setUp() {
        var storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));

        index = new ReclaimingIndex();
        sink = storageSegmentSink(storage, index);
        reader = tieredStreamReader(index, storage);
        seal(0, 4);
        seal(5, 9);
    }

    @Test
    void read_segmentRemovedBetweenEndAndRange_failsCursorExpired() {
        index.reclaimInsideNextEndLookup(0);

        reader.read(STREAM, PARTITION, 2, 10)
              .await()
              .onSuccess(events -> fail("expected CursorExpired, got events starting at " + events.getFirst().offset()))
              .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.CursorExpired(2, 5)));
    }

    /// NT-164 control: the same read with no reclaim in between returns events starting at `fromOffset`.
    @Test
    void read_notRacingAReclaim_returnsEventsFromFromOffset() {
        reader.read(STREAM, PARTITION, 2, 10)
              .await()
              .onFailure(cause -> fail(cause.message()))
              .onSuccess(events -> assertThat(events).extracting(RawEvent::offset).containsExactly(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));
    }

    private void seal(long startOffset, long endOffset) {
        var events = LongStream.rangeClosed(startOffset, endOffset)
                               .mapToObj(offset -> RawEvent.rawEvent(offset, ("evt-" + offset).getBytes(), 100L + offset))
                               .toList();
        var count = (int) (endOffset - startOffset + 1);

        sink.seal(sealedSegment(STREAM, PARTITION, startOffset, endOffset, count, 100L, 1000L, serialize(events))).await();
    }

    private static byte[] serialize(List<RawEvent> events) {
        var totalSize = events.stream().mapToInt(e -> Long.BYTES + Long.BYTES + Integer.BYTES + e.data().length).sum();
        var buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);

        for (var event : events) {
            buffer.putLong(event.offset());
            buffer.putLong(event.timestamp());
            buffer.putInt(event.data().length);
            buffer.put(event.data());
        }

        return buffer.array();
    }
}
