// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.lang.Unit.unit;

class SegmentSealerTest {

    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;

    private final List<SealedSegment> captured = new CopyOnWriteArrayList<>();
    private SegmentSealer sealer;

    @BeforeEach
    void setUp() {
        sealer = segmentSealer(this::captureSegment);
    }

    private Promise<Unit> captureSegment(SealedSegment segment) {
        captured.add(segment);
        return Promise.success(unit());
    }

    @Nested
    class OnEviction {

        @Test
        void onEviction_createsSegmentWithCorrectOffsets() {
            var events = List.of(
                RawEvent.rawEvent(10L, "a".getBytes(), 1000L),
                RawEvent.rawEvent(11L, "b".getBytes(), 2000L),
                RawEvent.rawEvent(12L, "c".getBytes(), 3000L)
            );

            sealer.onEviction(STREAM, PARTITION, events);

            assertThat(captured).hasSize(1);
            var segment = captured.getFirst();
            assertThat(segment.streamName()).isEqualTo(STREAM);
            assertThat(segment.partition()).isEqualTo(PARTITION);
            assertThat(segment.startOffset()).isEqualTo(10L);
            assertThat(segment.endOffset()).isEqualTo(12L);
            assertThat(segment.eventCount()).isEqualTo(3);
            assertThat(segment.minTimestamp()).isEqualTo(1000L);
            assertThat(segment.maxTimestamp()).isEqualTo(3000L);
        }

        @Test
        void onEviction_serializedEventsContainAllData() {
            var data1 = "hello".getBytes();
            var data2 = "world".getBytes();
            var events = List.of(
                RawEvent.rawEvent(0L, data1, 100L),
                RawEvent.rawEvent(1L, data2, 200L)
            );

            sealer.onEviction(STREAM, PARTITION, events);

            var serialized = captured.getFirst().serializedEvents();
            var buffer = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN);

            assertFirstEvent(buffer, 0L, 100L, data1);
            assertFirstEvent(buffer, 1L, 200L, data2);
        }

        @Test
        void onEviction_callsSinkWithSegment() {
            var events = List.of(RawEvent.rawEvent(5L, "x".getBytes(), 500L));

            sealer.onEviction(STREAM, PARTITION, events);

            assertThat(captured).hasSize(1);
            assertThat(captured.getFirst().startOffset()).isEqualTo(5L);
        }

        @Test
        void onEviction_emptyEvents_noSealCall() {
            sealer.onEviction(STREAM, PARTITION, List.of());

            assertThat(captured).isEmpty();
        }

        @Test
        void onEviction_singleEvent_offsetsMatch() {
            var events = List.of(RawEvent.rawEvent(42L, "only".getBytes(), 9999L));

            sealer.onEviction(STREAM, PARTITION, events);

            var segment = captured.getFirst();
            assertThat(segment.startOffset()).isEqualTo(42L);
            assertThat(segment.endOffset()).isEqualTo(42L);
            assertThat(segment.minTimestamp()).isEqualTo(9999L);
            assertThat(segment.maxTimestamp()).isEqualTo(9999L);
        }
    }

    /// #1234: the ring must keep evicted events until the sink has DURABLY sealed them. A sink that fails
    /// (disk full, DHT error) used to be ignored: the ring advanced its sealed watermark and reclaimed the
    /// slots anyway, so the only in-memory copy was gone and nothing had been persisted.
    @Nested
    class SealFailure {
        private static final int EVENTS = 5;
        private static final long RETAIN = 2;
        private static final long RECOVERY_DEADLINE_MS = 10_000;

        private final AtomicBoolean storageDown = new AtomicBoolean(true);

        private Promise<Unit> sealWhileStorageUp(SealedSegment segment) {
            return storageDown.get()
                   ? Causes.cause("storage down").promise()
                   : captureSegment(segment);
        }

        @Test
        void onEviction_firstSealFails_ringRetainsEventsAndSealedOffsetDoesNotAdvance() {
            try (var ring = OffHeapRingBuffer.offHeapRingBuffer(STREAM, PARTITION, 16, 4096, segmentSealer(this::sealWhileStorageUp))) {
                appendEvents(ring);

                ring.applyRetention(RetentionPolicy.retentionPolicy(RETAIN, Long.MAX_VALUE, Long.MAX_VALUE));

                assertThat(ring.tailOffset()).isEqualTo(0L);
                assertThat(ring.eventCount()).isEqualTo((long) EVENTS);
                assertThat(ring.lastSealedOffset()).isEqualTo(-1L);
                assertThat(ring.read(0, EVENTS).unwrap()).hasSize(EVENTS);
            }
        }

        @Test
        void onEviction_sealSucceedsAfterFailure_ringReclaimsSealedEvents() throws InterruptedException {
            try (var ring = OffHeapRingBuffer.offHeapRingBuffer(STREAM, PARTITION, 16, 4096, segmentSealer(this::sealWhileStorageUp))) {
                appendEvents(ring);
                ring.applyRetention(RetentionPolicy.retentionPolicy(RETAIN, Long.MAX_VALUE, Long.MAX_VALUE));
                storageDown.set(false);

                var deadline = System.currentTimeMillis() + RECOVERY_DEADLINE_MS;

                while (ring.tailOffset() < EVENTS - RETAIN && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                    ring.applyRetention(RetentionPolicy.retentionPolicy(RETAIN, Long.MAX_VALUE, Long.MAX_VALUE));
                }

                assertThat(ring.tailOffset()).isEqualTo(EVENTS - RETAIN);
                assertThat(ring.lastSealedOffset()).isGreaterThanOrEqualTo(EVENTS - RETAIN - 1);
                assertThat(captured).isNotEmpty();
                assertThat(captured.getFirst().startOffset()).isEqualTo(0L);
            }
        }

        private void appendEvents(OffHeapRingBuffer ring) {
            for (int i = 0; i < EVENTS; i++) {
                ring.append(("e-" + i).getBytes(), 1000L + i).unwrap();
            }
        }
    }

    private void assertFirstEvent(ByteBuffer buffer, long expectedOffset, long expectedTimestamp, byte[] expectedData) {
        assertThat(buffer.getLong()).isEqualTo(expectedOffset);
        assertThat(buffer.getLong()).isEqualTo(expectedTimestamp);
        var len = buffer.getInt();
        assertThat(len).isEqualTo(expectedData.length);
        var data = new byte[len];
        buffer.get(data);
        assertThat(data).isEqualTo(expectedData);
    }
}
