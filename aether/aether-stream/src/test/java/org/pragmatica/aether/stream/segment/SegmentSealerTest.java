// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.lang.Unit.unit;

class SegmentSealerTest {

    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;
    private static final long AWAIT_MS = 10_000;
    private static final long POLL_NANOS = 10_000_000;

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

    /// #1234, ruling B: reclamation stays IMMEDIATE. The ring hands evicted events to the sealer and reclaims
    /// their room at once; the sealer owns them until the sink has stored them (the WAL covers them meanwhile).
    /// A slow sink therefore never refuses an append while the retained copies are under the pending-seal cap.
    /// Pins the regression of the rejected option A, which refused EVENTUAL appends with `SEALING_BEHIND`
    /// whenever the sink was slower than the appends.
    @Nested
    class SlowSinkUnderCap {
        private static final int RING_EVENTS = 16;
        private static final int APPENDS = 200;

        @Test
        void append_slowSinkUnderCap_neverRefused_andRingReclaimsImmediately() {
            var slowSink = new ManualSink();

            try (var ring = OffHeapRingBuffer.offHeapRingBuffer(STREAM, PARTITION, RING_EVENTS, 4096, segmentSealer(slowSink))) {
                for (int i = 0; i < APPENDS; i++) {
                    ring.append(("e-" + i).getBytes(), 1000L + i)
                        .onFailure(cause -> fail("append refused under the pending-seal cap: " + cause.message()));
                }

                assertThat(ring.eventCount()).isEqualTo((long) RING_EVENTS);
                assertThat(ring.tailOffset()).isEqualTo((long) (APPENDS - RING_EVENTS));
            }
        }
    }

    /// #1234: seals of one partition complete in offset order — the next segment is not even sent until the
    /// previous one succeeded — so the sealed range never gains a later segment past an earlier pending one.
    @Nested
    class OrderedSealing {

        @Test
        void onEviction_secondSegmentNotSentUntilFirstSealed_sealsCompleteInOffsetOrder() {
            var sink = new ManualSink();
            var orderedSealer = segmentSealer(sink);

            orderedSealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(0L, "a".getBytes(), 1L)));
            orderedSealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(1L, "b".getBytes(), 2L)));
            orderedSealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(2L, "c".getBytes(), 3L)));

            assertThat(sink.startOffsets()).containsExactly(0L);

            sink.succeed(0);
            awaitCondition(() -> sink.calls() == 2);
            assertThat(sink.startOffsets()).containsExactly(0L, 1L);

            sink.succeed(1);
            awaitCondition(() -> sink.calls() == 3);
            assertThat(sink.startOffsets()).containsExactly(0L, 1L, 2L);
        }
    }

    /// #1234: a failed seal is retried from the sealer's retained copy — never dropped — and each failure is
    /// counted; the segment lands once the sink recovers and its retained bytes are released.
    @Nested
    class RetryFromRetainedCopy {
        private static final int FAILURES = 2;

        @Test
        void onEviction_sinkFailsTwiceThenSucceeds_segmentSealed_failuresCounted_copyReleased() {
            var attempts = new AtomicInteger();
            var retryingSealer = segmentSealer(segment -> failFirstAttempts(attempts, segment));

            retryingSealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(7L, "x".getBytes(), 1L)))
                          .onFailure(cause -> fail(cause.message()));

            assertThat(retryingSealer.holdsUnsealed(STREAM, PARTITION, 7L)).isTrue();

            awaitCondition(() -> !captured.isEmpty());
            awaitCondition(() -> retryingSealer.pendingBytes() == 0);

            assertThat(captured.getFirst().startOffset()).isEqualTo(7L);
            assertThat(retryingSealer.sealFailureCount()).isEqualTo(FAILURES);
            assertThat(attempts.get()).isEqualTo(FAILURES + 1);
            assertThat(retryingSealer.holdsUnsealed(STREAM, PARTITION, 7L)).isFalse();
        }

        private Promise<Unit> failFirstAttempts(AtomicInteger attempts, SealedSegment segment) {
            return attempts.incrementAndGet() <= FAILURES
                   ? Causes.cause("storage down").promise()
                   : captureSegment(segment);
        }
    }

    /// #1234: the retained copies are BOUNDED. Only once they reach the pending-seal cap does the sealer
    /// refuse a hand-over; the ring then keeps its events and refuses the append that needed their room with
    /// `SEALING_BEHIND`, and admission resumes as soon as a pending seal lands.
    @Nested
    class PendingSealCap {
        /// A one-event segment of a 3-byte payload is 23 bytes (20-byte event header), so a 64-byte cap admits
        /// three pending segments (0, 23 and 46 retained before each) and refuses the fourth (69 retained).
        private static final long CAP_BYTES = 64;
        private static final int RING_EVENTS = 4;

        @Test
        void append_pendingSealCapReached_refusedWithSealingBehind_ringKeepsEvents_admittedOnceSealLands() {
            var sink = new ManualSink();
            var cappedSealer = segmentSealer(sink, CAP_BYTES);

            try (var ring = OffHeapRingBuffer.offHeapRingBuffer(STREAM, PARTITION, RING_EVENTS, 4096, cappedSealer)) {
                for (int i = 0; i < RING_EVENTS + 3; i++) {
                    ring.append(("e-" + i).getBytes(), 1000L + i).onFailure(cause -> fail(cause.message()));
                }

                ring.append("e-7".getBytes(), 1007L)
                    .onSuccess(offset -> fail("expected SEALING_BEHIND, appended at " + offset))
                    .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.SEALING_BEHIND));

                assertThat(cappedSealer.refusalCount()).isEqualTo(1L);
                assertThat(ring.tailOffset()).isEqualTo(3L);
                assertThat(ring.eventCount()).isEqualTo((long) RING_EVENTS);
                assertThat(ring.read(3, RING_EVENTS).unwrap()).hasSize(RING_EVENTS);

                sink.succeed(0);
                awaitCondition(() -> cappedSealer.pendingBytes() < CAP_BYTES);

                ring.append("e-7".getBytes(), 1007L)
                    .onFailure(cause -> fail("still refused after a pending seal landed: " + cause.message()))
                    .onSuccess(offset -> assertThat(offset).isEqualTo(7L));
            }
        }
    }

    /// A sink whose seals complete only when the test says so — the slowest possible storage tier.
    private static final class ManualSink implements SegmentSink {
        private final List<SealedSegment> segments = new CopyOnWriteArrayList<>();
        private final List<Promise<Unit>> outcomes = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> seal(SealedSegment segment) {
            var outcome = Promise.<Unit>promise();

            segments.add(segment);
            outcomes.add(outcome);

            return outcome;
        }

        void succeed(int call) {
            outcomes.get(call).succeed(unit());
        }

        int calls() {
            return segments.size();
        }

        List<Long> startOffsets() {
            return segments.stream().map(SealedSegment::startOffset).toList();
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }

        assertThat(condition.getAsBoolean()).as("condition within %d ms", AWAIT_MS).isTrue();
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
