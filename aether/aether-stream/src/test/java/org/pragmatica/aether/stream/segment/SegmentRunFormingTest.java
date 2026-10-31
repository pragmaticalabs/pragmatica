// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SealerThresholds.sealerThresholds;
import static org.pragmatica.aether.stream.segment.SegmentReader.segmentReader;
import static org.pragmatica.aether.stream.segment.SegmentSealer.DEFAULT_PENDING_CAP_BYTES;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The run-forming sealer (spec 4.2, #2201 C-02): a partition's queued evictions are one run, sealed as one segment once
/// it is big enough, numerous enough or old enough, or when a read asks for one of its offsets; a backlog that queued
/// behind an in-flight seal is merged into the next block. Each test names the mutation that reddens it.
class SegmentRunFormingTest {
    private static final String STREAM = "runs";
    private static final int PARTITION = 0;
    /// Every test event is 8 payload bytes behind the 20-byte per-event header.
    private static final long EVENT_BYTES = 28;
    private static final long NEVER_BYTES = Long.MAX_VALUE;
    private static final int NEVER_EVENTS = Integer.MAX_VALUE;
    private static final TimeSpan HOUR = timeSpan(1).hours();
    private static final long MERGE = 4L * 1024 * 1024;
    private static final long QUIET_MS = 300;
    private static final long AWAIT_MS = 10_000;
    private static final long ONE_GB = 1024L * 1024 * 1024;

    /// NT-14: each threshold alone seals the run, nothing seals before one is reached, and the defaults coalesce a full
    /// ring's evictions into at most two segments. Red under MT-06 (the thresholds ignored: every eviction seals at once).
    @Nested
    class Thresholds {
        @Test
        void queuedBytesReachingSealRunBytes_sealTheRun_withoutWaitingForTheAge() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(3 * EVENT_BYTES, NEVER_EVENTS, HOUR, MERGE));

            evict(sealer, 0, 0);
            evict(sealer, 1, 1);
            assertNoSealFor(sink, "two events are below seal_run_bytes");
            evict(sealer, 2, 2);
            awaitCondition(() -> sink.calls() == 1);
            assertThat(range(sink.segment(0))).isEqualTo(List.of(0L, 2L));
        }

        @Test
        void queuedEventsReachingSealRunEvents_sealTheRun_withoutWaitingForTheAge() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, 3, HOUR, MERGE));

            evict(sealer, 0, 1);
            assertNoSealFor(sink, "two events are below seal_run_events");
            evict(sealer, 2, 2);
            awaitCondition(() -> sink.calls() == 1);
            assertThat(range(sink.segment(0))).isEqualTo(List.of(0L, 2L));
        }

        @Test
        void theOldestEntryReachingSealRunAge_sealsTheRun() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, timeSpan(400).millis(), MERGE));
            var firstEviction = System.nanoTime();

            evict(sealer, 0, 0);
            evict(sealer, 1, 1);
            awaitCondition(() -> sink.calls() == 1);
            assertThat(sink.callNanos(0) - firstEviction).as("sealed only once the oldest entry is seal_run_age old")
                                                        .isGreaterThanOrEqualTo(400_000_000L);
            assertThat(range(sink.segment(0))).isEqualTo(List.of(0L, 1L));
        }

        @Test
        void theDefaults_sealTheEvictionsOf200AppendsToAFullRing_inAtMostTwoSegments() {
            var sink = RecordingSink.succeeding();
            var sealer = segmentSealer(sink);
            var evicted = appendThroughARing(sealer, 16, 200);

            awaitCondition(() -> sealedEvents(sink) == evicted && sealer.pendingBytes() == 0, 30_000);
            assertThat(sealedOffsets(sink)).as("every evicted event sealed once, in offset order")
                                           .containsExactlyElementsOf(offsets(0, evicted - 1));
            assertThat(sink.calls()).as("segments for %d evicted events under the default thresholds", evicted)
                                    .isLessThanOrEqualTo(2);
        }
    }

    /// NT-15: runs seal in offset order, one in flight per partition, and the backlog that queued behind the in-flight
    /// seal becomes ONE contiguous next run. Red under "sealHead takes the head alone" (no merge).
    @Test
    void runsSealInOffsetOrder_oneInFlight_andTheBacklogBecomesTheNextContiguousRun() {
        var sink = RecordingSink.holding();
        var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, 4, HOUR, MERGE));

        LongStream.rangeClosed(0, 3).forEach(offset -> evict(sealer, offset, offset));
        awaitCondition(() -> sink.calls() == 1);
        LongStream.rangeClosed(4, 11).forEach(offset -> evict(sealer, offset, offset));
        assertNoSealFor(sink, 1, "one seal in flight per partition");
        sink.succeed(0);
        awaitCondition(() -> sink.calls() == 2);
        sink.succeed(1);
        awaitCondition(() -> sealer.pendingBytes() == 0 && sealer.lowestUnsealed(STREAM, PARTITION).isEmpty());

        assertThat(range(sink.segment(0))).isEqualTo(List.of(0L, 3L));
        assertThat(range(sink.segment(1))).as("the backlog behind the seal, merged").isEqualTo(List.of(4L, 11L));
        assertThat(sealedOffsets(sink)).containsExactlyElementsOf(offsets(0, 11));
    }

    /// NT-16 (I-07): a failing sink keeps the whole merged run, and the retry is the SAME run even though more entries
    /// queued meanwhile; nothing is removed until it lands. Red under "a retry re-forms the run" and under "a failure
    /// releases the run".
    @Test
    void aFailingSink_keepsTheWholeRun_andEveryRetryIsTheSameRun_whileMoreEntriesQueue() {
        var sink = RecordingSink.holding();
        var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, 3, HOUR, MERGE));

        LongStream.rangeClosed(0, 2).forEach(offset -> evict(sealer, offset, offset));
        awaitCondition(() -> sink.calls() == 1);
        LongStream.rangeClosed(3, 5).forEach(offset -> evict(sealer, offset, offset));
        sink.fail(0);
        awaitCondition(() -> sink.calls() == 2);

        assertThat(range(sink.segment(1))).as("the retry is the same run").isEqualTo(List.of(0L, 2L));
        assertThat(sealer.pendingBytes()).as("no entry removed by the failure").isEqualTo(6 * EVENT_BYTES);
        assertThat(sealer.lowestUnsealed(STREAM, PARTITION)).isEqualTo(Option.some(0L));
        assertThat(LongStream.rangeClosed(0, 5).allMatch(offset -> sealer.holdsUnsealed(STREAM, PARTITION, offset))).isTrue();

        sink.fail(1);
        awaitCondition(() -> sink.calls() == 3);
        assertThat(range(sink.segment(2))).isEqualTo(List.of(0L, 2L));
        sink.succeed(2);
        awaitCondition(() -> sink.calls() == 4);
        assertThat(range(sink.segment(3))).as("what queued during the retries is the next run").isEqualTo(List.of(3L, 5L));
        sink.succeed(3);
        awaitCondition(() -> sealer.pendingBytes() == 0);
        assertThat(sealer.sealFailureCount()).isEqualTo(2L);
    }

    /// NT-18: a read of a pending offset seals its run within one seal latency, not after `seal_run_age` (an hour here).
    /// Red under MT-08 (`holdsUnsealedForRead` does not request a flush).
    @Test
    void aReadOfAPendingOffset_sealsItsRun_withinOneSealLatency_notSealRunAge() {
        var sink = RecordingSink.succeeding();
        var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, HOUR, MERGE));

        LongStream.rangeClosed(0, 9).forEach(offset -> evict(sealer, offset, offset));
        assertNoSealFor(sink, "below every threshold");

        assertThat(sealer.holdsUnsealedForRead(STREAM, PARTITION, 5)).as("offset 5 is pending").isTrue();
        awaitCondition(() -> sink.calls() == 1, 2_000);
        assertThat(range(sink.segment(0))).isEqualTo(List.of(0L, 9L));
        awaitCondition(() -> !sealer.holdsUnsealedForRead(STREAM, PARTITION, 5));
    }

    /// NT-19 (F-26): one merged run whose older entries were SPILLED (heap copies dropped past the cap, range durable in
    /// the WAL) and whose newer entries are heap-only (the replica path's WAL has not written them yet). Each spilled
    /// entry is read from ITS OWN WAL range; one WAL read over the merged span would fail on the offsets only the heap
    /// holds. Red under "one WAL read over the merged span" (the seal never lands) and under "no merge".
    @Test
    void aMergedRun_mixingSpilledAndHeapEntries_readsEachSpilledEntryFromItsOwnWalRange(@TempDir Path walDir) {
        var wal = walWith(walDir, 0, 3);
        var sink = RecordingSink.holding();
        var sealer = segmentSealer(sink, 2 * EVENT_BYTES, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, HOUR, MERGE));

        sealer.walAttached(STREAM, PARTITION, wal);
        evict(sealer, 0, 0);
        awaitCondition(() -> sink.calls() == 1);
        LongStream.rangeClosed(1, 5).forEach(offset -> evict(sealer, offset, offset));

        assertThat(sealer.spillCount()).as("the durable entries 0-3 were spilled past the cap").isEqualTo(4L);
        assertThat(sealer.pendingBytes()).as("only 4 and 5, not in the WAL, keep heap copies").isEqualTo(2 * EVENT_BYTES);

        sink.succeed(0);
        awaitCondition(() -> sink.calls() == 2);
        sink.succeed(1);
        awaitCondition(() -> sealer.lowestUnsealed(STREAM, PARTITION).isEmpty());

        assertThat(range(sink.segment(1))).as("1-3 rebuilt from the WAL, 4-5 from the heap, one block").isEqualTo(List.of(1L, 5L));
        assertThat(decoded(sink.segment(1))).containsExactlyElementsOf(expectedEvents(1, 5));
        assertThat(sink.segment(1).minTimestamp()).isEqualTo(1001L);
        assertThat(sink.segment(1).maxTimestamp()).isEqualTo(1005L);
        wal.close();
    }

    /// NT-19: a merged run spanning two owner epochs carries the slice of both through the real sink, and its events read
    /// back in offset order. Red under "no merge" (ten blocks) and under "the slice is taken through the run's FIRST
    /// entry" (e2 lost).
    @Test
    void aMergedRunSpanningTwoEpochs_roundTripsItsProvenanceSlice(@TempDir Path walDir, @TempDir Path tierDir) {
        var storage = StorageInstance.storageInstance("tier", List.of(LocalDiskTier.localDiskTier(tierDir, ONE_GB).unwrap()));
        var index = new SegmentIndex();
        var wal = AppendLog.open(walDir.resolve("p.wal")).onFailure(cause -> fail(cause.message())).unwrap();

        recordEpoch(wal, Epoch.epoch(0, 1, 0), 0);
        appendAll(wal, 0, 4);
        recordEpoch(wal, Epoch.epoch(0, 2, 0), 5);
        appendAll(wal, 5, 9);

        var sealer = segmentSealer(storageSegmentSink(storage, index),
                                   DEFAULT_PENDING_CAP_BYTES,
                                   sealerThresholds(NEVER_BYTES, 10, HOUR, MERGE));

        sealer.walAttached(STREAM, PARTITION, wal);
        LongStream.rangeClosed(0, 9).forEach(offset -> evict(sealer, offset, offset));
        awaitCondition(() -> sealer.lowestUnsealed(STREAM, PARTITION).isEmpty());

        var refs = index.listSegments(STREAM, PARTITION);
        var reader = segmentReader(storage, index);

        assertThat(refs).as("one block for the run").hasSize(1);
        assertThat(reader.readProvenance(STREAM, PARTITION, refs.getFirst()).await().unwrap())
            .as("the slice of both epochs").isEqualTo(Option.some(wal.epochHistory()));
        assertThat(wal.epochHistory()).hasSize(2);
        assertThat(reader.readEvents(STREAM, PARTITION, 0, 10).await().unwrap()).extracting(RawEvent::offset)
                                                                              .containsExactlyElementsOf(offsets(0, 9));
        wal.close();
        storage.shutdown();
    }

    /// NT-20: both admission paths coalesce. Without a WAL (`enqueue`) a full ring's evictions become a few runs; with a
    /// WAL (`admitSpilling`) nothing seals below every threshold and under half the cap, and once the backlog behind a
    /// stalled seal passes the cap and spills, it still seals as merged blocks rebuilt from the WAL. Red under MT-07
    /// (`admitSpilling` drains without `maybeDrain`) and under "`enqueue` drains without `maybeDrain`".
    @Nested
    class AdmissionPaths {
        @Test
        void withoutAWal_theEvictionsOfAFullRing_sealInRunsOfTheEventThreshold() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, 32, timeSpan(200).millis(), MERGE));
            var evicted = appendThroughARing(sealer, 16, 200);

            awaitCondition(() -> sealedEvents(sink) == evicted && sealer.pendingBytes() == 0);
            assertThat(sealedOffsets(sink)).containsExactlyElementsOf(offsets(0, evicted - 1));
            assertThat(sink.calls()).as("%d evictions in runs of at least 32 but the last", evicted).isLessThanOrEqualTo(6);
        }

        @Test
        void withAWal_nothingSealsBelowTheThresholds_andABacklogSpilledPastTheCap_sealsMergedFromTheWal(@TempDir Path walDir) {
            var wal = AppendLog.open(walDir.resolve("p.wal")).onFailure(cause -> fail(cause.message())).unwrap();
            var sink = RecordingSink.holding();
            var sealer = segmentSealer(sink, 40 * EVENT_BYTES, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, HOUR, MERGE));

            sealer.walAttached(STREAM, PARTITION, wal);
            try (var ring = OffHeapRingBuffer.offHeapRingBuffer(STREAM, PARTITION, 16, 4096, sealer)) {
                appendDurably(ring, wal, 0, 25);
                assertNoSealFor(sink, "ten evictions are below every threshold and under half the cap");
                appendDurably(ring, wal, 26, 199);
                assertThat(sealer.spillCount()).as("the backlog behind the stalled seal spilled past the cap").isPositive();
                sink.open();
                awaitCondition(() -> sealedEvents(sink) == 184 && sealer.lowestUnsealed(STREAM, PARTITION).isEmpty());
            }

            assertThat(sealedOffsets(sink)).containsExactlyElementsOf(offsets(0, 183));
            assertThat(sink.segments().stream().flatMap(segment -> decoded(segment).stream()).toList())
                .as("spilled ranges rebuilt from the WAL carry the appended bytes").containsExactlyElementsOf(expectedEvents(0, 183));
            assertThat(sink.calls()).as("the half-cap run, then the merged backlog").isLessThanOrEqualTo(3);
            wal.close();
        }
    }

    /// NT-200 (#1875): close cancels the retry pause a failed cycle scheduled, so nothing of the sealer runs after it.
    /// Red under MT-99 (close does not cancel the handle: it stays armed) and on the base (the retry calls the sink
    /// after close).
    @Test
    void closeCancelsScheduledSealRetries() {
        var calls = new AtomicInteger();
        var sealer = SegmentSealer.segmentSealer((_, _) -> failing(calls),
                                                 DEFAULT_PENDING_CAP_BYTES,
                                                 SealerThresholds.immediate(),
                                                 1,
                                                 timeSpan(10).millis(),
                                                 timeSpan(300).millis());

        evict(sealer, 0, 0);
        awaitCondition(() -> calls.get() == 1);
        LockSupport.parkNanos(100_000_000L);

        var armedBeforeClose = sealer.armedTimers();

        sealer.close();

        var callsAtClose = calls.get();

        LockSupport.parkNanos(1_000_000_000L);
        assertThat(calls.get() - callsAtClose).as("sink calls after close").isZero();
        assertThat(armedBeforeClose).as("control: the failed cycle scheduled its retry before close").isEqualTo(1);
        assertThat(sealer.armedTimers()).as("close cancelled it").isZero();
    }

    private static SegmentSealer sealerWith(SegmentSink sink, SealerThresholds thresholds) {
        return segmentSealer(sink, DEFAULT_PENDING_CAP_BYTES, thresholds);
    }

    private static Promise<Unit> failing(AtomicInteger calls) {
        calls.incrementAndGet();

        return Causes.cause("storage down").promise();
    }

    private static void evict(SegmentSealer sealer, long from, long to) {
        sealer.onEviction(STREAM, PARTITION, events(from, to)).onFailure(cause -> fail("refused: " + cause.message()));
    }

    private static List<RawEvent> events(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(offset -> RawEvent.rawEvent(offset, payload(offset), 1000L + offset)).toList();
    }

    private static byte[] payload(long offset) {
        return "e-%06d".formatted(offset).getBytes();
    }

    /// `appends` appends of one 8-byte event into a ring of `ringEvents`; returns how many were evicted.
    private static long appendThroughARing(SegmentSealer sealer, int ringEvents, int appends) {
        try (var ring = OffHeapRingBuffer.offHeapRingBuffer(STREAM, PARTITION, ringEvents, 4096, sealer)) {
            for (long offset = 0; offset < appends; offset++) {
                ring.append(payload(offset), 1000L + offset).onFailure(cause -> fail("append refused: " + cause.message()));
            }
        }

        return appends - ringEvents;
    }

    /// Each record is fsynced into the WAL before the ring takes it, as the owner's publish path does.
    private static void appendDurably(OffHeapRingBuffer ring, AppendLog wal, long from, long to) {
        for (long offset = from; offset <= to; offset++) {
            wal.append(offset, payload(offset), 1000L + offset).await().onFailure(cause -> fail(cause.message()));
            assertThat(ring.append(payload(offset), 1000L + offset).unwrap()).isEqualTo(offset);
        }
    }

    private static AppendLog walWith(Path dir, long from, long to) {
        var wal = AppendLog.open(dir.resolve("p.wal")).onFailure(cause -> fail(cause.message())).unwrap();

        appendAll(wal, from, to);

        return wal;
    }

    private static void appendAll(AppendLog wal, long from, long to) {
        for (long offset = from; offset <= to; offset++) {
            wal.append(offset, payload(offset), 1000L + offset).await().onFailure(cause -> fail(cause.message()));
        }
    }

    private static void recordEpoch(AppendLog wal, Epoch epoch, long start) {
        wal.recordEpochStart(ProvenanceEntry.provenanceEntry(epoch, start).key().unwrap(), start, ProvenanceEntry.ORDER)
           .onFailure(cause -> fail(cause.message()));
    }

    private static List<Long> range(SealedSegment segment) {
        return List.of(segment.startOffset(), segment.endOffset());
    }

    private static List<Long> offsets(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    private static long sealedEvents(RecordingSink sink) {
        return sink.segments().stream().mapToLong(SealedSegment::eventCount).sum();
    }

    private static List<Long> sealedOffsets(RecordingSink sink) {
        return sink.segments().stream().flatMap(segment -> decoded(segment).stream()).map(Decoded::offset).toList();
    }

    private static List<Decoded> expectedEvents(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(offset -> new Decoded(offset, 1000L + offset, new String(payload(offset)))).toList();
    }

    /// The segment format, `[offset:8][timestamp:8][len:4][data]` per event, decoded.
    static List<Decoded> decoded(SealedSegment segment) {
        var buffer = ByteBuffer.wrap(segment.serializedEvents()).order(ByteOrder.BIG_ENDIAN);
        var events = new ArrayList<Decoded>();

        while (buffer.hasRemaining()) {
            var offset = buffer.getLong();
            var timestamp = buffer.getLong();
            var data = new byte[buffer.getInt()];

            buffer.get(data);
            events.add(new Decoded(offset, timestamp, new String(data)));
        }

        return events;
    }

    record Decoded(long offset, long timestamp, String data) {}

    private static void assertNoSealFor(RecordingSink sink, String reason) {
        assertNoSealFor(sink, 0, reason);
    }

    private static void assertNoSealFor(RecordingSink sink, int calls, String reason) {
        LockSupport.parkNanos(QUIET_MS * 1_000_000L);
        assertThat(sink.calls()).as("no further seal (%s)", reason).isEqualTo(calls);
    }

    private static void awaitCondition(BooleanSupplier condition) {
        awaitCondition(condition, AWAIT_MS);
    }

    private static void awaitCondition(BooleanSupplier condition, long awaitMs) {
        var deadline = System.currentTimeMillis() + awaitMs;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(5_000_000L);
        }

        assertThat(condition.getAsBoolean()).as("condition within %d ms", awaitMs).isTrue();
    }

    /// A sink that records every seal; it either answers at once or holds each answer until the test gives it
    /// ([#succeed], [#fail]) or opens it ([#open]: every held and later seal succeeds).
    static final class RecordingSink implements SegmentSink {
        private final List<SealedSegment> segments = new CopyOnWriteArrayList<>();
        private final List<Long> callNanos = new CopyOnWriteArrayList<>();
        private final List<Promise<Unit>> outcomes = new CopyOnWriteArrayList<>();
        private final AtomicBoolean open;

        private RecordingSink(boolean open) {
            this.open = new AtomicBoolean(open);
        }

        static RecordingSink succeeding() {
            return new RecordingSink(true);
        }

        static RecordingSink holding() {
            return new RecordingSink(false);
        }

        @Override
        public Promise<Unit> seal(SealedSegment segment, Option<AppendLog> log) {
            var outcome = Promise.<Unit> promise();

            // outcomes first: calls() counts segments, so a reader that saw call N must find outcome N.
            outcomes.add(outcome);
            callNanos.add(System.nanoTime());
            segments.add(segment);
            if (open.get()) {
                outcome.succeed(unit());
            }

            return outcome;
        }

        void succeed(int call) {
            outcomes.get(call).succeed(unit());
        }

        void fail(int call) {
            outcomes.get(call).fail(Causes.cause("storage down"));
        }

        void open() {
            open.set(true);
            outcomes.forEach(outcome -> outcome.succeed(unit()));
        }

        int calls() {
            return segments.size();
        }

        SealedSegment segment(int call) {
            return segments.get(call);
        }

        long callNanos(int call) {
            return callNanos.get(call);
        }

        List<SealedSegment> segments() {
            return List.copyOf(segments);
        }
    }
}
