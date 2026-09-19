// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    /// Longer than the next three backoff delays (100 + 200 + 400 ms plus jitter).
    private static final long RETRY_WINDOW_NANOS = 1_000_000_000L;

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
            awaitCondition(() -> !captured.isEmpty());

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
            awaitCondition(() -> !captured.isEmpty());

            var serialized = captured.getFirst().serializedEvents();
            var buffer = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN);

            assertFirstEvent(buffer, 0L, 100L, data1);
            assertFirstEvent(buffer, 1L, 200L, data2);
        }

        @Test
        void onEviction_callsSinkWithSegment() {
            var events = List.of(RawEvent.rawEvent(5L, "x".getBytes(), 500L));

            sealer.onEviction(STREAM, PARTITION, events);
            awaitCondition(() -> !captured.isEmpty());

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
            awaitCondition(() -> !captured.isEmpty());

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
            awaitCondition(() -> sink.calls() == 1);

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

    /// #1234, WAL OFF: a ring with no WAL behind it (here a bare ring; in production a manager built with no
    /// WAL directory — Ember or Forge without a data dir) has no durable holder, so the heap copies are
    /// BOUNDED by refusal. Only once they reach the pending-seal cap does the sealer refuse a hand-over; the
    /// ring then keeps its events and refuses the append that needed their room with `SEALING_BEHIND`, and
    /// admission resumes as soon as a pending seal lands. The one case an EVENTUAL append can fail.
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

                awaitCondition(() -> sink.calls() == 1);
                sink.succeed(0);
                awaitCondition(() -> cappedSealer.pendingBytes() < CAP_BYTES);

                ring.append("e-7".getBytes(), 1007L)
                    .onFailure(cause -> fail("still refused after a pending seal landed: " + cause.message()))
                    .onSuccess(offset -> assertThat(offset).isEqualTo(7L));
            }
        }
    }

    /// #1234: the sealer drops its retained copy only AFTER the sink has made the segment readable. The sink
    /// here models `StorageSegmentSink`: durable once the gate opens, then indexed in a dependent step. At that
    /// index step the sealer must still hold the copy; releasing earlier opens a window in which an evicted
    /// offset is in neither the sealer nor the index, and a read of it is misreported.
    @Nested
    class ReleaseAfterIndex {

        @Test
        void seal_retainedCopyReleasedOnlyAfterIndexUpdate() {
            var gate = Promise.<Unit>promise();
            var index = new SegmentIndex();
            var heldAtIndexUpdate = new AtomicBoolean(false);
            var sealerRef = new AtomicReference<SegmentSealer>();
            var orderedSealer = segmentSealer(segment -> gate.map(_ -> indexWhileObserving(index,
                                                                                            segment,
                                                                                            sealerRef,
                                                                                            heldAtIndexUpdate)));

            sealerRef.set(orderedSealer);
            orderedSealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(3L, "a".getBytes(), 1L)));

            assertThat(orderedSealer.holdsUnsealed(STREAM, PARTITION, 3L)).isTrue();
            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(-1L);

            gate.succeed(unit());
            awaitCondition(() -> !orderedSealer.holdsUnsealed(STREAM, PARTITION, 3L));

            assertThat(heldAtIndexUpdate.get()).as("sealer still held the copy when the index was updated").isTrue();
            assertThat(index.findSegment(STREAM, PARTITION, 3L).isPresent()).isTrue();
            assertThat(orderedSealer.pendingBytes()).isZero();
        }

        private static Unit indexWhileObserving(SegmentIndex index,
                                                SealedSegment segment,
                                                AtomicReference<SegmentSealer> sealer,
                                                AtomicBoolean heldAtIndexUpdate) {
            heldAtIndexUpdate.set(sealer.get().holdsUnsealed(segment.streamName(), segment.partition(), segment.startOffset()));
            index.addSegment(segment.streamName(), segment.partition(), segment.startOffset(), segment.endOffset());

            return unit();
        }
    }

    /// #1234: pending seals of a DELETED stream are cancelled — their WAL is gone with the stream, so there is
    /// nothing left to protect, and retrying them forever would hold the shared pending-seal cap.
    @Nested
    class StreamDeletion {
        private static final String DOOMED = "doomed-stream";

        @Test
        void onStreamDeleted_cancelsPendingSeals_releasesTheirBytes_otherStreamsUntouched() {
            var sink = new ManualSink();
            var deletingSealer = segmentSealer(sink);

            deletingSealer.onEviction(DOOMED, PARTITION, List.of(RawEvent.rawEvent(0L, "a".getBytes(), 1L)));
            deletingSealer.onEviction(DOOMED, PARTITION, List.of(RawEvent.rawEvent(1L, "b".getBytes(), 2L)));
            deletingSealer.onEviction(DOOMED, 1, List.of(RawEvent.rawEvent(0L, "c".getBytes(), 3L)));
            deletingSealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(0L, "d".getBytes(), 4L)));
            awaitCondition(() -> sink.calls() == 3);

            var oneSegment = deletingSealer.pendingBytes() / 4;

            deletingSealer.onStreamDeleted(DOOMED);

            assertThat(deletingSealer.pendingBytes()).isEqualTo(oneSegment);
            assertThat(deletingSealer.holdsUnsealed(DOOMED, PARTITION, 1L)).isFalse();
            assertThat(deletingSealer.holdsUnsealed(STREAM, PARTITION, 0L)).isTrue();

            sink.succeed(0);
            sink.succeed(1);

            assertThat(deletingSealer.pendingBytes()).as("a cancelled seal that lands releases nothing twice")
                                                     .isEqualTo(oneSegment);
            assertThat(sink.calls()).as("the doomed partition's queued segment is never sent").isEqualTo(3);

            sink.succeed(2);
            awaitCondition(() -> deletingSealer.pendingBytes() == 0);
        }

        @Test
        void onStreamDeleted_stopsRetryingAFailingSeal() {
            var attempts = new AtomicInteger();
            var failingSealer = segmentSealer(_ -> failAndCount(attempts));

            failingSealer.onEviction(DOOMED, PARTITION, List.of(RawEvent.rawEvent(0L, "a".getBytes(), 1L)));
            awaitCondition(() -> failingSealer.sealFailureCount() >= 2);

            failingSealer.onStreamDeleted(DOOMED);
            var attemptsAtDeletion = attempts.get();
            var failuresAtDeletion = failingSealer.sealFailureCount();

            LockSupport.parkNanos(RETRY_WINDOW_NANOS);

            // Both failures were counted before deletion and the next retry is ~200 ms out, so nothing is in
            // flight: the retry must stop at the cancelled check, neither calling the sink nor failing again
            // (a counted failure) on the heap copy the deletion dropped.
            assertThat(attempts.get()).isEqualTo(attemptsAtDeletion);
            assertThat(failingSealer.sealFailureCount()).isEqualTo(failuresAtDeletion);
            assertThat(failingSealer.pendingBytes()).isZero();
        }

        /// The manager wiring: deleting a stream hands the deletion to its eviction listener, which frees the
        /// cap the stream's unsealed segments held.
        @Test
        void destroyStream_freesPendingSealBytes() {
            var neverSeals = new ManualSink();
            var managedSealer = segmentSealer(neverSeals);
            var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE, managedSealer);

            manager.createStream(StreamConfig.streamConfig(DOOMED, 1, RetentionPolicy.retentionPolicy(4, 4096, 600_000), "earliest"))
                   .onFailure(cause -> fail(cause.message()));
            for (int i = 0; i < 10; i++) {
                manager.publishLocal(DOOMED, 0, ("e-" + i).getBytes(), 1000L + i).onFailure(cause -> fail(cause.message()));
            }

            assertThat(managedSealer.pendingBytes()).isPositive();

            manager.destroyStream(DOOMED).onFailure(cause -> fail(cause.message()));
            manager.close();

            assertThat(managedSealer.pendingBytes()).isZero();
        }

        private static Promise<Unit> failAndCount(AtomicInteger attempts) {
            attempts.incrementAndGet();

            return Causes.cause("storage down").promise();
        }
    }

    /// #1234, WAL ON: when a pending range must be rebuilt from the WAL and the WAL cannot supply EXACTLY that
    /// range, the seal fails loudly and the segment stays pending — the sink never sees a short or gapped
    /// segment. A zero cap spills every heap copy at once, so every attempt is a WAL rebuild.
    @Nested
    class WalRebuildMissingRange {
        @TempDir
        Path walDir;

        /// The WAL holds offsets 0-4 and 7-9, all durable: the pending range [5-6] is below its durable offset,
        /// so the heap copy is spilled — and the WAL cannot give it back.
        @Test
        void seal_walRangeMissing_neverSealsShortSegment_keepsItPending_countsFailure() {
            var sink = new ManualSink();
            var spillingSealer = segmentSealer(sink, 0);
            var wal = walWith(walDir, 0, 1, 2, 3, 4, 7, 8, 9);

            spillingSealer.walAttached(STREAM, PARTITION, wal);
            spillingSealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(5L, "a".getBytes(), 1L),
                                                                  RawEvent.rawEvent(6L, "b".getBytes(), 2L)))
                          .onFailure(cause -> fail("a partition with a WAL must never refuse: " + cause.message()));

            awaitCondition(() -> spillingSealer.sealFailureCount() >= 1);

            assertThat(sink.calls()).as("no segment may reach the sink").isZero();
            assertThat(spillingSealer.spillCount()).isEqualTo(1L);
            assertThat(spillingSealer.pendingBytes()).isZero();
            assertThat(spillingSealer.holdsUnsealed(STREAM, PARTITION, 5L)).isTrue();
            assertThat(spillingSealer.lowestUnsealed(STREAM, PARTITION)).isEqualTo(Option.some(5L));
            wal.close();
        }
    }

    /// #1234 (review of 510829642, the replica race): a heap copy may be dropped only for a range already
    /// DURABLE in the WAL. The replica path writes its WAL asynchronously, so a range can be handed over
    /// before its WAL write lands; spilling it then made the rebuild read a WAL that did not yet hold it.
    @Nested
    class SpillOnlyDurableRanges {
        @TempDir
        Path walDir;

        @Test
        void onEviction_rangeAboveWalDurableOffset_keepsHeapCopyPastCap_andSealsIt() {
            var sink = new ManualSink();
            var sealer = segmentSealer(sink, 0);
            var wal = walWith(walDir, 0, 1, 2);

            sealer.walAttached(STREAM, PARTITION, wal);
            sealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(3L, "late".getBytes(), 3L)))
                  .onFailure(cause -> fail("a partition with a WAL must never refuse: " + cause.message()));

            assertThat(wal.durableOffset()).isEqualTo(2L);
            assertThat(sealer.spillCount()).as("nothing durable to spill").isZero();
            assertThat(sealer.pendingBytes()).as("the not-yet-durable copy is kept past the cap").isPositive();

            awaitCondition(() -> sink.calls() == 1);
            sink.succeed(0);
            awaitCondition(() -> sealer.pendingBytes() == 0);

            assertThat(sink.startOffsets()).containsExactly(3L);
            assertThat(sealer.sealFailureCount()).isZero();
            wal.close();
        }
    }

    private static PartitionWal walWith(Path dir, long... offsets) {
        var wal = PartitionWal.open(dir.resolve("p.wal")).onFailure(cause -> fail(cause.message())).unwrap();

        for (var offset : offsets) {
            wal.append(offset, ("w-" + offset).getBytes(), offset).await().onFailure(cause -> fail(cause.message()));
        }

        return wal;
    }

    /// #1234 / #1240: the lowest offset still held for sealing — none when nothing is pending, else the head of
    /// the partition's queue, advancing as each seal lands.
    @Nested
    class LowestUnsealed {

        @Test
        void lowestUnsealed_noneWhenEmpty_thenLowestPending_thenAdvancesAsSealsLand() {
            var sink = new ManualSink();
            var trackingSealer = segmentSealer(sink);

            assertThat(trackingSealer.lowestUnsealed(STREAM, PARTITION)).isEqualTo(Option.none());

            trackingSealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(10L, "a".getBytes(), 1L),
                                                                  RawEvent.rawEvent(11L, "b".getBytes(), 2L)));
            trackingSealer.onEviction(STREAM, PARTITION, List.of(RawEvent.rawEvent(12L, "c".getBytes(), 3L)));
            awaitCondition(() -> sink.calls() == 1);

            assertThat(trackingSealer.lowestUnsealed(STREAM, PARTITION)).isEqualTo(Option.some(10L));
            assertThat(trackingSealer.lowestUnsealed(STREAM, 1)).isEqualTo(Option.none());

            sink.succeed(0);
            awaitCondition(() -> sink.calls() == 2);
            assertThat(trackingSealer.lowestUnsealed(STREAM, PARTITION)).isEqualTo(Option.some(12L));

            sink.succeed(1);
            awaitCondition(() -> trackingSealer.lowestUnsealed(STREAM, PARTITION).isEmpty());
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
