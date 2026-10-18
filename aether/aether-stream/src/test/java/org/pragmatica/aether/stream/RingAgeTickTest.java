// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.TierAwareRetention;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.slice.RetentionPolicy.retentionPolicy;
import static org.pragmatica.aether.slice.TierAwareRetention.tierAwareRetention;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// PR-0c (owner ruling Q8 A): the ring age tick. [StreamPartitionManager#evictRingsByAge] evicts, from every
/// materialized ring of a non-entity stream that declares a finite max age, the events older than that age, and
/// hands them to the eviction listener (the sealer) exactly as space eviction does. It calls
/// `OffHeapRingBuffer#evictByAge` and nothing else.
///
/// Old events are stamped two hours before "now" and fresh ones with "now"; every max age used is one hour or
/// less, so the split between them is wide against the clock the ring reads inside `evictByAge`.
class RingAgeTickTest {
    private static final long HOUR_MS = 3_600_000L;
    private static final long STALE_MS = 2 * HOUR_MS;
    private static final int OLD_EVENTS = 3;
    private static final int FRESH_EVENTS = 2;
    /// What the TOML parser fills into the dimensions a `time`-typed stream does not declare
    /// (`StreamConfigParser`'s `time` arm): the count and byte defaults, so the policy is finite on all three axes.
    private static final long DEFAULT_MAX_COUNT = 100_000L;
    private static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

    private final List<Long> handedOver = new CopyOnWriteArrayList<>();
    private final List<String> handedOverStreams = new CopyOnWriteArrayList<>();
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE, this::takeEvents);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private Result<Unit> takeEvents(String stream, int partition, List<OffHeapRingBuffer.RawEvent> events) {
        events.forEach(event -> handedOver.add(event.offset()));
        handedOverStreams.add(stream);
        return Result.unitResult();
    }

    private static RetentionPolicy timeTyped(long maxAgeMs) {
        return retentionPolicy(DEFAULT_MAX_COUNT, DEFAULT_MAX_BYTES, maxAgeMs);
    }

    private static RetentionPolicy timeTyped(long maxAgeMs, TierAwareRetention tierAware) {
        return retentionPolicy(DEFAULT_MAX_COUNT, DEFAULT_MAX_BYTES, maxAgeMs, tierAware);
    }

    private OffHeapRingBuffer ringWithOldAndFreshEvents(String stream, RetentionPolicy retention) {
        manager.createStream(StreamConfig.streamConfig(stream, 1, retention, "earliest"))
               .onFailure(cause -> fail(cause.message()));

        var ring = manager.partitionBuffer(stream, 0).unwrap();
        var now = System.currentTimeMillis();

        for (int i = 0; i < OLD_EVENTS; i++) {
            append(ring, "old-" + i, now - STALE_MS + i);
        }

        for (int i = 0; i < FRESH_EVENTS; i++) {
            append(ring, "fresh-" + i, now);
        }

        return ring;
    }

    private static void append(OffHeapRingBuffer ring, String payload, long timestamp) {
        ring.append(payload.getBytes(), timestamp).onFailure(cause -> fail(cause.message()));
    }

    @Test
    void evictRingsByAge_handsEventsOlderThanTheMaxAgeToTheSealer_forATimeTypedStream() {
        var ring = ringWithOldAndFreshEvents("timed", timeTyped(HOUR_MS));

        var swept = manager.evictRingsByAge();

        assertThat(swept).as("the one materialized ring of the stream was swept").isEqualTo(1);
        assertThat(handedOver).as("the old events went to the sealer, in offset order").containsExactly(0L, 1L, 2L);
        assertThat(handedOverStreams).containsOnly("timed");
        assertThat(ring.tailOffset()).as("the old events left the ring").isEqualTo(OLD_EVENTS);
        assertThat(ring.eventCount()).as("the fresh events stayed").isEqualTo(FRESH_EVENTS);
    }

    @Test
    void evictRingsByAge_appliesTheDefaultTwentyFourHourAge_forAnApiCreatedStream() {
        manager.createStream(StreamConfig.streamConfig("api-default"))
               .onFailure(cause -> fail(cause.message()));

        var ring = manager.partitionBuffer("api-default", 0).unwrap();
        var now = System.currentTimeMillis();

        append(ring, "stale", now - 25 * HOUR_MS);
        append(ring, "recent", now - 23 * HOUR_MS);
        manager.evictRingsByAge();

        assertThat(handedOver).containsExactly(0L);
        assertThat(ring.eventCount()).isEqualTo(1L);
    }

    @Test
    void evictRingsByAge_evictsByAge_forACompoundStreamWhoseCountAndSizeAreNotExceeded() {
        var compound = retentionPolicy(1_000, 64L * 1024, HOUR_MS, RetentionMode.ALL);
        var ring = ringWithOldAndFreshEvents("compound", compound);

        manager.evictRingsByAge();

        assertThat(handedOver).as("Q8 A wires evictByAge for compound streams: age alone evicts").containsExactly(0L, 1L, 2L);
        assertThat(ring.eventCount()).isEqualTo(FRESH_EVENTS);
    }

    @Test
    void evictRingsByAge_leavesACountTypedStreamUntouched_andDoesNotSweepIt() {
        var ring = ringWithOldAndFreshEvents("counted", retentionPolicy(100, DEFAULT_MAX_BYTES, Long.MAX_VALUE));

        var swept = manager.evictRingsByAge();

        assertThat(swept).as("a stream with no declared age is skipped, not swept to a no-op").isZero();
        assertThat(handedOver).isEmpty();
        assertThat(ring.eventCount()).isEqualTo(OLD_EVENTS + FRESH_EVENTS);
    }

    @Test
    void evictRingsByAge_leavesAnEntityRingUntouched_evenWhenItsTierAwareBranchIsArmed() {
        var tierAware = timeTyped(HOUR_MS, tierAwareRetention(0, 0));
        var ring = ringWithOldAndFreshEvents("entity:orders", tierAware);

        ring.updateLastSealedOffset(OLD_EVENTS + FRESH_EVENTS - 1);

        var swept = manager.evictRingsByAge();

        assertThat(swept).as("an entity ring is excluded from the tick").isZero();
        assertThat(handedOver).isEmpty();
        assertThat(ring.eventCount()).as("neither the age rule nor the armed tier-aware branch reached it")
                                     .isEqualTo(OLD_EVENTS + FRESH_EVENTS);
    }

    @Test
    void evictRingsByAge_doesNotRunTheTierAwarePath_whenTheStreamsPolicyCarriesIt() {
        var tierAware = timeTyped(10 * HOUR_MS, tierAwareRetention(0, 0));
        var ring = ringWithOldAndFreshEvents("tiered", tierAware);

        ring.updateLastSealedOffset(OLD_EVENTS + FRESH_EVENTS - 1);
        manager.evictRingsByAge();

        assertThat(handedOver).as("nothing is older than the 10 h max age; a tier-aware pass would evict every sealed event").isEmpty();
        assertThat(ring.eventCount()).isEqualTo(OLD_EVENTS + FRESH_EVENTS);
    }

    @Test
    void evictRingsByAge_keepsTheEventsInTheRing_whenTheSealerRefuses() {
        manager.close();
        manager = streamPartitionManager(Long.MAX_VALUE, (_, _, _) -> StreamError.General.SEALING_BEHIND.result());

        var ring = ringWithOldAndFreshEvents("refused", timeTyped(HOUR_MS));

        manager.evictRingsByAge();

        assertThat(ring.eventCount()).as("a refused hand-over leaves the events in place, as for space eviction")
                                     .isEqualTo(OLD_EVENTS + FRESH_EVENTS);
        assertThat(ring.tailOffset()).isZero();
    }

    /// `PeriodicTasks.cancel` lets an in-flight tick finish, and a release closes a ring while the stream entry may
    /// still hand it out, so a tick can meet a closed ring; the sweep must end quietly and the event stay unhanded.
    @Test
    void evictRingsByAge_againstAClosedRing_endsWithoutThrowing_andHandsNothingOver() {
        var ring = ringWithOldAndFreshEvents("closing", timeTyped(HOUR_MS));

        ring.close();

        assertThat(manager.evictRingsByAge()).isEqualTo(1);
        assertThat(handedOver).isEmpty();
    }

    @Test
    void evictRingsByAge_sweepsEveryMaterializedRing_ofEveryBoundStream() {
        manager.createStream(StreamConfig.streamConfig("a", 2, timeTyped(HOUR_MS), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        manager.createStream(StreamConfig.streamConfig("b", 1, timeTyped(HOUR_MS), "earliest"))
               .onFailure(cause -> fail(cause.message()));

        assertThat(manager.evictRingsByAge()).isEqualTo(3);
    }
}
