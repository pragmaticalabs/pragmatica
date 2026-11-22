// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.stream.RemovalCause;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.RetentionPolicy.retentionPolicy;
import static org.pragmatica.aether.slice.TierAwareRetention.tierAwareRetention;

/// The cause-per-site table of M-2r-1 (state-M-2r-1 L13): every place the ring removes records names ITS cause, and a MEMORY
/// ring's reader is answered with exactly that cause. One test per site, each driving only that removal; a site that names
/// another cause (the mutant swaps it for a neighbour's) fails its own test:
///  - `evictForSpace` COUNT then BYTES in one publish, `evictByCount` COUNT, `evictBySize` BYTES, `evictByAgeChecked` AGE;
///  - all-mode retention, the tier-aware sealed excess and the sealed-by-age sweep: RETENTION;
///  - the thaw at the wrap point: RING_FROZEN.
class OffHeapRingBufferRemovalSitesTest {
    private static final String STREAM = "sites";
    private static final long DATA_REGION = 4096;
    private static final long NEVER = Long.MAX_VALUE;
    private static final long MIB = 1024 * 1024L;

    /// `evictForSpace`: one append that the entry-count capacity AND the data region both force space for. The record the
    /// capacity forces out is `COUNT`, the two the bytes force out are `BYTES`, in one run array of two runs. Mutants: the
    /// halves swapped, the bytes half named COUNT, every record COUNT, every record BYTES.
    @Test
    void evictForSpace_mixedCountAndBytesInOnePublish_namesCountThenBytes() {
        try (var ring = memoryRing(4)) {
            append(ring, 4, 1000);
            append(ring, 1, 2500);

            assertThat(ring.tailOffset()).isEqualTo(3L);
            assertThat(causesAt(ring, 0)).containsExactlyInAnyOrder(RemovalCause.COUNT, RemovalCause.BYTES);
            assertThat(causeAt(ring, 0)).as("the record the capacity forced out").isEqualTo(RemovalCause.COUNT);
            assertThat(causeAt(ring, 1)).as("the first record the bytes forced out").isEqualTo(RemovalCause.BYTES);
            assertThat(causeAt(ring, 2)).as("the last record the bytes forced out").isEqualTo(RemovalCause.BYTES);
            assertThat(causesAt(ring, 1)).containsExactly(RemovalCause.BYTES);
            assertThat(ring.removalLedger().runCount()).as("COUNT run, BYTES run").isEqualTo(2);
        }
    }

    /// `evictForSpace`, the byte pressure alone: nothing is forced by the capacity, so no record is COUNT.
    @Test
    void evictForSpace_bytePressureAlone_namesBytesOnly() {
        try (var ring = memoryRing(8)) {
            append(ring, 4, 1000);
            append(ring, 1, 2500);

            assertThat(ring.tailOffset()).isEqualTo(3L);
            assertThat(causesAt(ring, 0)).containsExactly(RemovalCause.BYTES);
        }
    }

    /// `evictByCount`: the retention pass over a count limit names `COUNT`.
    @Test
    void evictByCount_namesCount() {
        try (var ring = memoryRing(8)) {
            append(ring, 6, 2);

            ring.applyRetention(retentionPolicy(3, NEVER, NEVER));

            assertThat(ring.tailOffset()).isEqualTo(3L);
            assertThat(causesAt(ring, 0)).containsExactly(RemovalCause.COUNT);
        }
    }

    /// `evictBySize`: the retention pass over a byte limit names `BYTES`.
    @Test
    void evictBySize_namesBytes() {
        try (var ring = memoryRing(8)) {
            append(ring, 6, 2);

            ring.applyRetention(retentionPolicy(NEVER, 4, NEVER));

            assertThat(ring.tailOffset()).isEqualTo(4L);
            assertThat(causesAt(ring, 0)).containsExactly(RemovalCause.BYTES);
        }
    }

    /// `evictByAgeChecked`: the age sweep names `AGE`, and takes only the aged records.
    @Test
    void evictByAge_namesAge() {
        try (var ring = memoryRing(8)) {
            appendAt(ring, 2, 0L);
            appendAt(ring, 4, System.currentTimeMillis());

            ring.evictByAge(60_000);

            assertThat(ring.tailOffset()).isEqualTo(2L);
            assertThat(causesAt(ring, 0)).containsExactly(RemovalCause.AGE);
        }
    }

    /// `applyAllModeRetention`: when every configured limit is exceeded the removal is `RETENTION`, the one cause for a
    /// combination, not that of any single dimension.
    @Test
    void allModeRetention_namesRetention() {
        try (var ring = memoryRing(8)) {
            append(ring, 6, 2);

            ring.applyRetention(retentionPolicy(3, 4, 1000, RetentionMode.ALL));

            assertThat(ring.tailOffset()).isEqualTo(3L);
            assertThat(causesAt(ring, 0)).containsExactly(RemovalCause.RETENTION);
        }
    }

    /// `applyTierAwareRetention`, the sealed excess: eight sealed records against a post-seal limit of three.
    @Test
    void tierAwareSealedExcess_namesRetention() {
        try (var ring = memoryRing(16)) {
            appendAt(ring, 10, System.currentTimeMillis());
            ring.updateLastSealedOffset(7);

            ring.applyRetention(retentionPolicy(NEVER, NEVER, NEVER, tierAwareRetention(NEVER, 3)));

            assertThat(ring.tailOffset()).isEqualTo(5L);
            assertThat(causesAt(ring, 0)).containsExactly(RemovalCause.RETENTION);
        }
    }

    /// `evictSealedByAge`: sealed records older than the post-seal buffer, with no sealed excess.
    @Test
    void tierAwareSealedByAge_namesRetention() {
        try (var ring = memoryRing(16)) {
            appendAt(ring, 3, System.currentTimeMillis() - 120_000);
            appendAt(ring, 3, System.currentTimeMillis());
            ring.updateLastSealedOffset(2);

            ring.applyRetention(retentionPolicy(NEVER, NEVER, NEVER, tierAwareRetention(60_000, NEVER)));

            assertThat(ring.tailOffset()).isEqualTo(3L);
            assertThat(causesAt(ring, 0)).containsExactly(RemovalCause.RETENTION);
        }
    }

    /// `thawAtTheWrapPoint`: a ring frozen by a refused budget, then released, hands over the records above the head to unwrap
    /// its live bytes, and names them `RING_FROZEN`; the removals before it were the bytes' (`BYTES`). The ring is a MEMORY
    /// ring built through the package-private budget-seam factory.
    @Test
    void thawAtTheWrapPoint_namesRingFrozen() {
        var allow = new AtomicBoolean(false);
        var freed = new AtomicLong();

        try (var ring = memoryRingWithBudget(_ -> allow.get(), freed::get)) {
            append(ring, 100, 10_000);

            var frozenAt = ring.allocatedBytes();

            assertThat(causesAt(ring, 0)).as("control: a frozen ring evicts for bytes").containsExactly(RemovalCause.BYTES);

            allow.set(true);
            freed.incrementAndGet();
            for (var i = 0; i < 200 && ring.allocatedBytes() == frozenAt; i++) {
                append(ring, 1, 10_000);
            }

            assertThat(ring.allocatedBytes()).as("control: the ring thawed and grew").isGreaterThan(frozenAt);
            assertThat(causesAt(ring, 0)).containsExactlyInAnyOrder(RemovalCause.BYTES, RemovalCause.RING_FROZEN);
        }
    }

    private static OffHeapRingBuffer memoryRing(long capacity) {
        return OffHeapRingBuffer.offHeapRingBuffer(OffHeapRingBuffer.Mode.MEMORY,
                                                   STREAM,
                                                   0,
                                                   capacity,
                                                   DATA_REGION,
                                                   0L,
                                                   EvictionListener.NOOP,
                                                   EvictionPolicy.DROP_OLDEST);
    }

    private static OffHeapRingBuffer memoryRingWithBudget(LongPredicate reserve, LongSupplier budgetFreed) {
        return OffHeapRingBuffer.memoryRingBuffer(STREAM,
                                                  0,
                                                  10_000L,
                                                  MIB,
                                                  0L,
                                                  EvictionListener.NOOP,
                                                  EvictionPolicy.DROP_OLDEST,
                                                  reserve,
                                                  budgetFreed);
    }

    private static void append(OffHeapRingBuffer ring, int count, int size) {
        for (var i = 0; i < count; i++) {
            ring.append(new byte[size], i).unwrap();
        }
    }

    private static void appendAt(OffHeapRingBuffer ring, int count, long timestamp) {
        for (var i = 0; i < count; i++) {
            ring.append(("e" + i).getBytes(StandardCharsets.UTF_8), timestamp).unwrap();
        }
    }

    private static RemovalCause causeAt(OffHeapRingBuffer ring, long offset) {
        return expired(ring.readSlice(offset)).cause();
    }

    private static Set<RemovalCause> causesAt(OffHeapRingBuffer ring, long offset) {
        return expired(ring.readSlice(offset)).causes();
    }

    private static StreamError.CursorExpired expired(Result<?> result) {
        var seen = new AtomicReference<Cause>();

        result.onFailure(seen::set);

        assertThat(seen.get()).as("the read was refused").isInstanceOf(StreamError.CursorExpired.class);

        return (StreamError.CursorExpired) seen.get();
    }
}
