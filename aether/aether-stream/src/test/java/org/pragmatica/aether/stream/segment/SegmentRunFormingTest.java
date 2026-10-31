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

    /// Adversarial hazards of the run former (the PR-1a self-pass). Each names the mutation that reddens it.
    @Nested
    class Hazards {
        /// A gap ends the run: offsets 0-2 and 5-7 never share a block. Red under "a run takes every queued entry".
        @Test
        void aGap_endsTheRun() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, 6, HOUR, MERGE));

            evict(sealer, 0, 2);
            evict(sealer, 5, 7);
            awaitCondition(() -> sink.calls() == 1 && sealer.lowestUnsealed(STREAM, PARTITION).equals(Option.some(5L)));
            assertThat(range(sink.segment(0))).isEqualTo(List.of(0L, 2L));
            assertNoSealFor(sink, 1, "3 events after the gap are below seal_run_events");
        }

        /// `seal_merge_bytes` caps a block; a head larger than the cap still seals, alone. Red under "the cap applies to
        /// the head" (an empty run: nothing ever seals) and under "no cap".
        @Test
        void mergeBytes_capsTheBlock_andAHeadLargerThanTheCapSealsAlone() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, 1, HOUR, 2 * EVENT_BYTES));
            var held = RecordingSink.holding();
            var gated = sealerWith(held, sealerThresholds(NEVER_BYTES, 1, HOUR, 2 * EVENT_BYTES));

            evict(sealer, 0, 4);
            awaitCondition(() -> sink.calls() == 1);
            assertThat(range(sink.segment(0))).as("five events, more than the cap, seal alone").isEqualTo(List.of(0L, 4L));

            evict(gated, 0, 0);
            awaitCondition(() -> held.calls() == 1);
            LongStream.rangeClosed(1, 5).forEach(offset -> evict(gated, offset, offset));
            held.open();
            awaitCondition(() -> gated.lowestUnsealed(STREAM, PARTITION).isEmpty());
            assertThat(held.segments().stream().map(SegmentRunFormingTest::range).toList())
                .as("the backlog 1-5 in blocks of at most two events").containsExactly(List.of(0L, 0L), List.of(1L, 2L), List.of(3L, 4L), List.of(5L, 5L));
        }

        /// I-07 across retry cycles: when a cycle is exhausted, the next cycle retries the SAME run, not one re-formed
        /// from a queue that grew meanwhile. Red under "a new cycle re-forms the run at the head".
        @Test
        void aNewRetryCycle_retriesTheSameRun_notOneGrownByWhatQueuedMeanwhile() {
            var sink = RecordingSink.holding();
            var sealer = SegmentSealer.segmentSealer(sink,
                                                     DEFAULT_PENDING_CAP_BYTES,
                                                     sealerThresholds(NEVER_BYTES, 3, HOUR, MERGE),
                                                     1,
                                                     timeSpan(10).millis(),
                                                     timeSpan(100).millis());

            LongStream.rangeClosed(0, 2).forEach(offset -> evict(sealer, offset, offset));
            awaitCondition(() -> sink.calls() == 1);
            LongStream.rangeClosed(3, 5).forEach(offset -> evict(sealer, offset, offset));
            sink.fail(0);
            awaitCondition(() -> sink.calls() == 2);
            assertThat(range(sink.segment(1))).as("the next cycle's run").isEqualTo(List.of(0L, 2L));
            sink.succeed(1);
            awaitCondition(() -> sink.calls() == 3);
            assertThat(range(sink.segment(2))).isEqualTo(List.of(3L, 5L));
            sink.succeed(2);
            sealer.close();
        }

        /// #1875: close with three things pending: a failed seal backing off (partition 0), an age timer (partition 1) and a
        /// first attempt still at the sink (partition 2, which fails after close). After close nothing reaches the sink,
        /// nothing is a WARN or ERROR (the sealer's or the retry's), nothing is retried, no timer stays armed, and nothing
        /// pending is dropped. Red under "a retry already scheduled runs after close", "close leaves the age timer", "a
        /// failure after close WARNs", "a failed seal is retried after close", "an exhausted cycle after close logs and
        /// re-arms", "`maybeDrain` runs after close" and "`SEALER_CLOSED` is terminal" (the retry WARNs on it).
        @Test
        void close_withARetryBackingOff_anAgeTimer_andASealAtTheSink_sealsNothing_andWarnsNothing_afterIt() {
            var sink = RecordingSink.holding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, 2, timeSpan(400).millis(), MERGE));

            sealer.onEviction(STREAM, 0, events(0, 1));
            awaitCondition(() -> sink.calls() == 1);
            sealer.onEviction(STREAM, 2, events(0, 1));
            awaitCondition(() -> sink.calls() == 2);
            sealer.onEviction(STREAM, 1, events(0, 0));
            assertThat(sealer.armedTimers()).as("control: partition 1 waits on its age timer").isEqualTo(1);
            sink.fail(sink.callFor(0));
            LockSupport.parkNanos(20_000_000L);

            try (var sealerLog = org.pragmatica.testlogging.LogCapture.capture(SegmentSealer.class, org.apache.logging.log4j.Level.WARN, true);
                 var retryLog = org.pragmatica.testlogging.LogCapture.capture(org.pragmatica.lang.utils.Retry.class.getName(),
                                                                             org.apache.logging.log4j.Level.DEBUG,
                                                                             true)) {
                sealer.close();
                sink.fail(sink.callFor(2));
                LockSupport.parkNanos(1_200_000_000L);

                assertThat(sink.calls()).as("no seal after close: no retry of partition 0 or 2, no age seal of partition 1").isEqualTo(2);
                assertThat(sealerLog.messages()).as("sealer WARN/ERROR after close").isEmpty();
                assertThat(retryLog.entries()).as("the retry after close: no WARN, no further attempt")
                                              .noneMatch(entry -> entry.level().isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)
                                                                  || entry.message().contains("retrying after"));
            }

            assertThat(sealer.armedTimers()).isZero();
            assertThat(sealer.sealFailureCount()).as("both failures are still counted").isEqualTo(2L);
            assertThat(List.of(0, 1, 2)).as("nothing pending is dropped by close")
                                        .allMatch(partition -> sealer.holdsUnsealed(STREAM, partition, 0));
        }

        /// After a run seals, the next one counts from zero and waits for its own threshold; it is not sealed because the
        /// previous seal landed. Red under "a landed seal drains the next head at once" and under "a sealed run's events
        /// stay counted".
        @Test
        void afterASeal_theNextRunCountsFromZero_andWaitsForItsThreshold() {
            var sink = RecordingSink.holding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, 3, HOUR, MERGE));

            LongStream.rangeClosed(0, 2).forEach(offset -> evict(sealer, offset, offset));
            awaitCondition(() -> sink.calls() == 1);
            evict(sealer, 3, 3);
            sink.succeed(0);
            assertNoSealFor(sink, 1, "one event behind the landed seal");
            evict(sealer, 4, 4);
            assertNoSealFor(sink, 1, "two events since the seal");
            evict(sealer, 5, 5);
            awaitCondition(() -> sink.calls() == 2);
            assertThat(range(sink.segment(1))).isEqualTo(List.of(3L, 5L));
        }

        /// The byte counter of a run also starts from zero after its seal. Red under "a sealed run's bytes stay counted".
        @Test
        void afterASeal_theNextRunsBytesCountFromZero() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(3 * EVENT_BYTES, NEVER_EVENTS, HOUR, MERGE));

            evict(sealer, 0, 2);
            awaitCondition(() -> sink.calls() == 1);
            evict(sealer, 3, 4);
            assertNoSealFor(sink, 1, "two events' bytes since the seal");
            evict(sealer, 5, 5);
            awaitCondition(() -> sink.calls() == 2);
            assertThat(range(sink.segment(1))).isEqualTo(List.of(3L, 5L));
        }

        /// A timer that fired for one run re-arms for the next: the second run is aged by its own deadline. Red under "a
        /// fired timer stays marked armed" (the second run never seals).
        @Test
        void aSecondRun_isAgedByItsOwnTimer() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, timeSpan(200).millis(), MERGE));

            evict(sealer, 0, 0);
            awaitCondition(() -> sink.calls() == 1);
            LockSupport.parkNanos(100_000_000L);
            evict(sealer, 1, 1);
            awaitCondition(() -> sink.calls() == 2, 2_000);
            assertThat(range(sink.segment(1))).isEqualTo(List.of(1L, 1L));
            assertThat(sealer.ageTimersScheduled()).isEqualTo(2L);
        }

        /// A seal whose first attempt was started before close still runs (the node-stop flush), but its retry does not.
        /// Red under "close refuses a first attempt" (flushAll then close seals nothing).
        @Test
        void flushAllThenClose_sealsTheQueuedRun_once() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, HOUR, MERGE));

            LongStream.rangeClosed(0, 4).forEach(offset -> evict(sealer, offset, offset));
            assertNoSealFor(sink, "below every threshold");
            sealer.flushAll();
            sealer.close();
            awaitCondition(() -> sink.calls() == 1);
            assertThat(range(sink.segment(0))).isEqualTo(List.of(0L, 4L));
            awaitCondition(() -> sealer.lowestUnsealed(STREAM, PARTITION).isEmpty());
            assertThat(sealer.armedTimers()).isZero();
        }

        /// A deleted stream's age timer is cancelled with its pending seals. Red under "deletion leaves the timer".
        @Test
        void onStreamDeleted_cancelsThePartitionsAgeTimer() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, timeSpan(300).millis(), MERGE));

            evict(sealer, 0, 1);
            assertThat(sealer.armedTimers()).as("control").isEqualTo(1);
            sealer.onStreamDeleted(STREAM);

            assertThat(sealer.armedTimers()).isZero();
            LockSupport.parkNanos(600_000_000L);
            assertThat(sink.calls()).isZero();
            assertThat(sealer.pendingBytes()).isZero();
        }

        /// One CAS-guarded age timer per partition, however many hand-overs race to arm it. Red under "every admission
        /// arms a timer".
        @Test
        void concurrentAdmissions_armExactlyOneAgeTimerPerPartition() throws InterruptedException {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, HOUR, MERGE));
            var threads = new ArrayList<Thread>();

            for (var t = 0; t < 4; t++) {
                var lane = t;

                threads.add(Thread.ofPlatform().start(() -> LongStream.range(0, 50)
                                                                     .forEach(i -> evict(sealer, i * 4 + lane, i * 4 + lane))));
            }
            for (var thread : threads) {
                thread.join();
            }

            assertThat(sealer.ageTimersScheduled()).isEqualTo(1L);
            assertThat(sealer.armedTimers()).isEqualTo(1);
            assertThat(sink.calls()).isZero();
            sealer.close();
        }

        /// Demand flush for an offset whose run is NOT the head: every run up to the one holding it is sealed at once, and a
        /// read of a lower offset afterwards does not lower that. Red under "the flush readies only the run whose head
        /// holds the offset" and under "a later read replaces the flush offset".
        @Test
        void aReadOfAnOffsetBehindOtherRuns_sealsEveryRunUpToIt() {
            var sink = RecordingSink.succeeding();
            var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, HOUR, 3 * EVENT_BYTES));

            LongStream.rangeClosed(0, 11).forEach(offset -> evict(sealer, offset, offset));
            assertThat(sealer.holdsUnsealedForRead(STREAM, PARTITION, 7)).isTrue();
            assertThat(sealer.holdsUnsealedForRead(STREAM, PARTITION, 2)).as("a lower offset read later keeps the flush through 7").isTrue();
            awaitCondition(() -> !sealer.holdsUnsealed(STREAM, PARTITION, 7), 2_000);
            assertThat(sink.segments().stream().map(SegmentRunFormingTest::range).toList()).containsExactly(List.of(0L, 2L),
                                                                                                           List.of(3L, 5L),
                                                                                                           List.of(6L, 8L));
            assertNoSealFor(sink, 3, "9-11 lie past the offset read");
        }

        /// Heap copies at half the pending cap make every run ready at once. Red under "no half-cap trigger".
        @Test
        void heapCopiesAtHalfThePendingCap_sealAtOnce() {
            var sink = RecordingSink.succeeding();
            var sealer = segmentSealer(sink, 4 * EVENT_BYTES, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, HOUR, MERGE));

            evict(sealer, 0, 0);
            assertNoSealFor(sink, "a quarter of the cap");
            evict(sealer, 1, 1);
            awaitCondition(() -> sink.calls() == 1, 2_000);
            assertThat(range(sink.segment(0))).isEqualTo(List.of(0L, 1L));
        }

        /// Without a WAL the cap still refuses a hand-over with `SEALING_BEHIND` (the one refusal), runs or not. Red under
        /// "`reserve` admits past the cap".
        @Test
        void withoutAWal_aHandOverPastTheCap_isStillRefusedSealingBehind() {
            var sink = RecordingSink.holding();
            var sealer = segmentSealer(sink, 2 * EVENT_BYTES, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, HOUR, MERGE));

            evict(sealer, 0, 0);
            evict(sealer, 1, 1);
            var refused = sealer.onEviction(STREAM, PARTITION, events(2, 2));

            assertThat(refused.isFailure()).isTrue();
            refused.onFailure(cause -> assertThat(cause).isEqualTo(org.pragmatica.aether.stream.StreamError.General.SEALING_BEHIND));
            assertThat(sealer.refusalCount()).isEqualTo(1L);
            assertThat(sealer.holdsUnsealed(STREAM, PARTITION, 2)).isFalse();
            sink.open();
        }

        /// "Segment format unchanged": a merged block reads back through the real sink and reader in offset order with
        /// each event's own offset, timestamp and bytes, from its start and from inside it; a block sealed one event at a
        /// time (as before runs) reads beside it. Red under "a merged block concatenates its parts out of order" and under
        /// "a merged block takes its first part's end".
        @Test
        void aMergedBlock_readsBackInOffsetOrder_besideABlockOfOneEvent(@TempDir Path tierDir) {
            var storage = StorageInstance.storageInstance("tier", List.of(LocalDiskTier.localDiskTier(tierDir, ONE_GB).unwrap()));
            var index = new SegmentIndex();
            var sink = storageSegmentSink(storage, index);
            var sealer = segmentSealer(sink, DEFAULT_PENDING_CAP_BYTES, sealerThresholds(NEVER_BYTES, 9, HOUR, MERGE));
            var single = segmentSealer(sink, DEFAULT_PENDING_CAP_BYTES, SealerThresholds.immediate());
            var reader = segmentReader(storage, index);

            single.onEviction(STREAM, PARTITION, events(0, 0));
            awaitCondition(() -> single.lowestUnsealed(STREAM, PARTITION).isEmpty());
            LongStream.rangeClosed(1, 9).forEach(offset -> evict(sealer, offset, offset));
            awaitCondition(() -> sealer.lowestUnsealed(STREAM, PARTITION).isEmpty());

            assertThat(index.listSegments(STREAM, PARTITION)).extracting(SegmentIndex.SegmentRef::startOffset, SegmentIndex.SegmentRef::endOffset)
                                                              .containsExactly(org.assertj.core.groups.Tuple.tuple(0L, 0L),
                                                                               org.assertj.core.groups.Tuple.tuple(1L, 9L));
            assertThat(readBack(reader, 0, 10)).containsExactlyElementsOf(expectedEvents(0, 9));
            assertThat(readBack(reader, 4, 3)).as("from inside the merged block").containsExactlyElementsOf(expectedEvents(4, 6));
            storage.shutdown();
        }

        private static List<Decoded> readBack(SegmentReader reader, long from, int count) {
            return reader.readEvents(STREAM, PARTITION, from, count)
                         .await()
                         .unwrap()
                         .stream()
                         .map(event -> new Decoded(event.offset(), event.timestamp(), new String(event.data())))
                         .toList();
        }
    }

    /// The reader seam: `StreamPartitionManager.sealInFlight`, which every tier-fallback and catch-up read asks, is the READ
    /// form, so asking it seals the offset's run. Red under "`sealInFlight` asks `holdsUnsealed`".
    @Test
    void theManagersSealInFlight_isAReadThatSealsTheRun() {
        var sink = RecordingSink.succeeding();
        var sealer = sealerWith(sink, sealerThresholds(NEVER_BYTES, NEVER_EVENTS, HOUR, MERGE));
        var manager = org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE, sealer);

        manager.createStream(org.pragmatica.aether.slice.StreamConfig.streamConfig(STREAM,
                                                                                  1,
                                                                                  org.pragmatica.aether.slice.RetentionPolicy.retentionPolicy(4, 1_048_576L, 600_000L),
                                                                                  "earliest"))
               .onFailure(cause -> fail(cause.message()));
        for (long offset = 0; offset < 10; offset++) {
            manager.publishLocal(STREAM, PARTITION, payload(offset), 1000L + offset).onFailure(cause -> fail("publish: " + cause.message()));
        }
        assertNoSealFor(sink, "below every threshold");

        assertThat(manager.sealInFlight(STREAM, PARTITION, 0)).isTrue();
        awaitCondition(() -> sink.calls() == 1, 2_000);
        assertThat(range(sink.segment(0))).isEqualTo(List.of(0L, 5L));
        manager.close();
    }

    /// A listener that persists but knows nothing of runs answers the read form as `holdsUnsealed`. Red under "the
    /// default answers false".
    @Test
    void theListenersReadForm_defaultsToHoldsUnsealed() {
        org.pragmatica.aether.stream.EvictionListener listener = new org.pragmatica.aether.stream.EvictionListener() {
            @Override
            public org.pragmatica.lang.Result<Unit> onEviction(String streamName, int partition, List<RawEvent> events) {
                return org.pragmatica.lang.Result.unitResult();
            }

            @Override
            public boolean holdsUnsealed(String streamName, int partition, long offset) {
                return offset == 7;
            }
        };

        assertThat(listener.holdsUnsealedForRead(STREAM, PARTITION, 7)).isTrue();
        assertThat(listener.holdsUnsealedForRead(STREAM, PARTITION, 8)).isFalse();
    }

    /// The defaults are the spec's four guesses (4.2), and the node config's defaults are checked against these in
    /// `StreamAssemblySealerThresholdsTest`.
    @Test
    void theDefaults_areTheSpecsGuesses() {
        assertThat(SealerThresholds.defaults()).isEqualTo(sealerThresholds(1024 * 1024L, 4096, timeSpan(5).seconds(), 4 * 1024 * 1024L));
    }

    /// A drain step that throws (never expected; here the failure's own message throws while the exhausted cycle is
    /// logged) is counted, and the partition's run is sealed after the pause, through `maybeDrain`. Red under "a drain
    /// crash does not resume".
    @Test
    void aDrainStepThatThrows_isCounted_andTheRunIsSealedAfterThePause() {
        var calls = new AtomicInteger();
        var sealer = SegmentSealer.segmentSealer((_, _) -> calls.incrementAndGet() == 1
                                                           ? new MessageThrows().<Unit> promise()
                                                           : Promise.success(unit()),
                                                 DEFAULT_PENDING_CAP_BYTES,
                                                 sealerThresholds(NEVER_BYTES, 1, HOUR, MERGE),
                                                 1,
                                                 timeSpan(10).millis(),
                                                 timeSpan(100).millis());

        evict(sealer, 0, 1);
        awaitCondition(() -> sealer.lowestUnsealed(STREAM, PARTITION).isEmpty());
        assertThat(sealer.drainCrashCount()).isGreaterThanOrEqualTo(1L);
        assertThat(calls.get()).isEqualTo(2);
    }

    record MessageThrows() implements org.pragmatica.lang.Cause {
        @Override
        public String message() {
            throw new IllegalStateException("message unavailable");
        }
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

        /// The call that sealed partition `partition` (each partition is sealed once in the tests that use it).
        int callFor(int partition) {
            for (var call = 0; call < segments.size(); call++) {
                if (segments.get(call).partition() == partition) {
                    return call;
                }
            }

            throw new AssertionError("no seal of partition " + partition);
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
