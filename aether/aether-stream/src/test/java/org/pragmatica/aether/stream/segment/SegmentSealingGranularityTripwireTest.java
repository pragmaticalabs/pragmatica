// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.AppendLog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;

/// Streams audit C-02 (#2201): with a full ring, every append evicts about one event, and every eviction becomes its own sealed segment: one
/// `SegmentSink.seal` (in production two fsyncs and a DHT quorum put) per event, and no merge anywhere in `SegmentSealer`, not even of the backlog
/// that piles up while one seal is in flight. This counts the segments the real ring and the real sealer produce per `APPENDS` appends.
///
/// The REAL assertion states a floor, not the overhaul's design: at least [MIN_EVENTS_PER_SEGMENT] evicted events per sealed segment on average.
/// The overhaul (1b-perf: drain-time merge plus a size or age accumulation threshold of 1-16 MiB or 1-5 s) coalesces far more than that for
/// 8-byte events; the figure is a deliberately low bar, a guess marked as one, so the test does not pin the threshold. TRIPWIRE (enabled, GREEN)
/// beside the REAL assertion (`@Disabled`, RED today), once for a sink that answers at once and once for a slow sink, where a backlog exists.
class SegmentSealingGranularityTripwireTest {
    private static final String STREAM = "granular";
    private static final int PARTITION = 0;
    private static final int RING_EVENTS = 16;
    private static final int APPENDS = 200;
    private static final int EVICTED = APPENDS - RING_EVENTS;
    private static final int MIN_EVENTS_PER_SEGMENT = 4;
    private static final long AWAIT_MS = 30_000;
    private static final String ENABLE = "C-02 tripwire: eviction no longer seals one segment per event; delete this tripwire and enable the real assertion beside it (#2201)";

    @TempDir
    Path walDir;

    private final List<SealedSegment> segments = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        clock.shutdownNow();
    }

    @Test
    void fullRing_sealsOneSegmentPerEvictedEvent_tripwire() {
        sealAfterAppends(0);

        assertThat(segments.stream().mapToInt(SealedSegment::eventCount).sum()).as("control: every evicted event was sealed exactly once").isEqualTo(EVICTED);
        assertThat(segments).as(ENABLE).hasSize(EVICTED);
    }

    @Disabled("C-02 real assertion, RED today: evicted events must be sealed in coalesced segments, at least 4 per segment on average (#2201)")
    @Test
    void fullRing_coalescesEvictedEventsIntoFewerSegments() {
        sealAfterAppends(0);

        assertThat(segments.size() * MIN_EVENTS_PER_SEGMENT).isLessThanOrEqualTo(EVICTED);
    }

    @Test
    void fullRing_withASlowSink_stillSealsOneSegmentPerEvictedEvent_tripwire() {
        sealAfterAppends(2);

        assertThat(segments.stream().mapToInt(SealedSegment::eventCount).sum()).as("control: every evicted event was sealed exactly once").isEqualTo(EVICTED);
        assertThat(segments).as(ENABLE + " (the backlog behind a slow seal is not merged either)").hasSize(EVICTED);
    }

    @Disabled("C-02 real assertion, RED today: the backlog accumulated behind one slow seal must become the next, larger segment (#2201)")
    @Test
    void fullRing_withASlowSink_mergesTheBacklogBehindASeal() {
        sealAfterAppends(2);

        assertThat(segments.size() * MIN_EVENTS_PER_SEGMENT).isLessThanOrEqualTo(EVICTED);
    }

    /// The WAL-backed entry (`admitSpilling`, what a durable partition takes in production): the same granularity, a different admission path from
    /// `enqueue`, so a fix confined to the no-WAL path leaves this pair as it is.
    @Test
    void fullRingWithAWal_sealsOneSegmentPerEvictedEvent_tripwire() {
        sealAfterAppends(0, true);

        assertThat(segments.stream().mapToInt(SealedSegment::eventCount).sum()).as("control: every evicted event was sealed exactly once").isEqualTo(EVICTED);
        assertThat(segments).as(ENABLE + " (WAL-backed admission)").hasSize(EVICTED);
    }

    @Disabled("C-02 real assertion, RED today: WAL-backed partitions must coalesce evicted events too, at least 4 per segment on average (#2201)")
    @Test
    void fullRingWithAWal_coalescesEvictedEventsIntoFewerSegments() {
        sealAfterAppends(0, true);

        assertThat(segments.size() * MIN_EVENTS_PER_SEGMENT).isLessThanOrEqualTo(EVICTED);
    }

    private void sealAfterAppends(long sealMillis) {
        sealAfterAppends(sealMillis, false);
    }

    /// `APPENDS` appends of one tiny event each into a ring of `RING_EVENTS`, then waits for every seal to land. The sink answers after `sealMillis`.
    private void sealAfterAppends(long sealMillis, boolean withWal) {
        var sealer = segmentSealer((segment, _) -> record(segment, sealMillis));

        if (withWal) {
            sealer.walAttached(STREAM, PARTITION, AppendLog.open(walDir.resolve("p.wal")).onFailure(cause -> fail(cause.message())).unwrap());
        }

        try (var ring = OffHeapRingBuffer.offHeapRingBuffer(STREAM, PARTITION, RING_EVENTS, 4096, sealer)) {
            for (var i = 0; i < APPENDS; i++) {
                ring.append(("e-" + i).getBytes(), 1000L + i).onFailure(cause -> fail("append refused: " + cause.message()));
            }

            assertThat(ring.eventCount()).as("control: the ring is full").isEqualTo((long) RING_EVENTS);
            awaitCondition(() -> segments.stream().mapToInt(SealedSegment::eventCount).sum() == EVICTED && sealer.pendingBytes() == 0);
        }
    }

    private Promise<Unit> record(SealedSegment segment, long sealMillis) {
        var done = Promise.<Unit> promise();

        segments.add(segment);
        clock.schedule(() -> done.succeed(Unit.unit()), sealMillis, TimeUnit.MILLISECONDS);
        return done;
    }

    private static void awaitCondition(BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(10_000_000L);
        }

        assertThat(condition.getAsBoolean()).as("every seal landed within %d ms", AWAIT_MS).isTrue();
    }
}
