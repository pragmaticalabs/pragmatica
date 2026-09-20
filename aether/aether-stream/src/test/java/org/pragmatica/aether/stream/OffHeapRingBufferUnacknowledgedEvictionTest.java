// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.stream.OffHeapRingBuffer.SealBound;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.SealBound.APPENDED;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.SealBound.VISIBLE;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

/// #1352: DROP_OLDEST hands the eviction listener (the segment sealer) only evictees at or below the VISIBLE
/// position. An evictee above it was never acknowledged by the stream's min-sync peers, so it is dropped —
/// reclaimed, reported to the [UnacknowledgedEvictionListener], never sealed. Before the fix the hand-over read
/// `[tail, tail + count)` raw, so an event no consumer was ever allowed to see reached the durable tier.
///
/// The ring is driven the way the partition manager drives it: [OffHeapRingBuffer#appendOrdered] (no
/// visibility of its own) followed by `markDurable` / `advanceVisible` for whatever the caller decides is
/// visible. The clamp is a property of the write path ([SealBound]): the owner's append seals only
/// acknowledged evictees; a replica's append seals every evictee, because what a replica holds is already in
/// the owner's log and its own fsync lag says nothing about that.
class OffHeapRingBufferUnacknowledgedEvictionTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long DATA_BYTES = 1024;

    private final List<Long> sealed = new CopyOnWriteArrayList<>();
    private final List<long[]> dropped = new CopyOnWriteArrayList<>();
    private final AtomicInteger handOvers = new AtomicInteger();
    private final EvictionListener sealer = (_, _, events) -> {
        handOvers.incrementAndGet();
        events.forEach(event -> sealed.add(event.offset()));

        return Result.unitResult();
    };
    private final UnacknowledgedEvictionListener dropListener = (stream, partition, from, to) -> {
        assertThat(stream).isEqualTo(STREAM);
        assertThat(partition).isEqualTo(PARTITION);
        dropped.add(new long[]{from, to});
    };
    private OffHeapRingBuffer ring;

    @AfterEach
    void closeRing() {
        Option.option(ring).onPresent(OffHeapRingBuffer::close);
    }

    /// The ticket's probe, inverted: nothing visible, three appends into capacity 2. Offset 0 is evicted and
    /// must NOT reach the sealer.
    @Test
    void dropOldest_nothingVisible_evicteeIsDroppedNotSealed() {
        ring = ring(2);

        appendUnacknowledged(3);

        assertThat(ring.visibleOffset()).isEqualTo(-1L);
        assertThat(ring.tailOffset()).as("offset 0 was reclaimed").isEqualTo(1L);
        assertThat(sealed).as("offsets handed to the sealer").isEmpty();
        assertThat(handOvers).as("the sealer is not called at all — not even with an empty run").hasValue(0);
        assertThat(ranges(dropped)).as("the drop was reported as [0, 0]").containsExactly("[0, 0]");
    }

    /// A retention sweep evicting a run that straddles the visible position: `[tail, visible]` is sealed,
    /// `(visible, tail + count)` is dropped, and the whole run is reclaimed.
    @Test
    void retentionSweep_splitsTheEvictedRunAtVisible() {
        ring = ring(8);
        appendUnacknowledged(6);
        ring.markDurable(2);
        ring.advanceVisible(2);

        ring.applyRetention(RetentionPolicy.retentionPolicy(1, Long.MAX_VALUE, Long.MAX_VALUE));

        assertThat(sealed).as("sealed: the visible prefix of the evicted run").containsExactly(0L, 1L, 2L);
        assertThat(ranges(dropped)).as("dropped: the unacknowledged rest of it").containsExactly("[3, 4]");
        assertThat(ring.tailOffset()).as("all five evictees were reclaimed").isEqualTo(5L);
        assertThat(ring.lastSealedOffset()).as("the ring's sealed high-water follows the sealed part only").isEqualTo(2L);
    }

    /// The replica arm. A replica advances visible = its own durable prefix only after each WAL fsync
    /// (`StreamPartitionManager.replicaDurable`), so a fast replication stream evicts before the fsync lands.
    /// Everything a replica holds is already in the owner's log, so its append seals every evictee and drops
    /// none — the #1234 contract (`StreamPartitionManagerWalTruncateTest.appendRecovered_zeroCap_…`) unchanged.
    @Test
    void replicaAppend_sealsEveryEvictee_evenBelowItsOwnDurablePrefix() {
        ring = ring(2);

        appendUnacknowledged(APPENDED, 4);

        assertThat(ring.visibleOffset()).as("nothing is durable here yet").isEqualTo(-1L);
        assertThat(sealed).as("both evictees were sealed").containsExactly(0L, 1L);
        assertThat(dropped).isEmpty();
        assertThat(ring.tailOffset()).isEqualTo(2L);
    }

    /// The same ring written by both paths in turn: an owner append clamps, a replica append does not. The
    /// bound belongs to the append, not to the ring.
    @Test
    void sealBound_isDecidedPerAppend() {
        ring = ring(2);
        appendUnacknowledged(VISIBLE, 3);

        assertThat(ranges(dropped)).as("the owner append dropped offset 0").containsExactly("[0, 0]");
        assertThat(sealed).isEmpty();

        appendUnacknowledged(APPENDED, 1);

        assertThat(sealed).as("the replica append sealed offset 1").containsExactly(1L);
        assertThat(ranges(dropped)).containsExactly("[0, 0]");
    }

    /// An acknowledged prefix that is also fully evicted stays entirely sealed — the clamp changes nothing
    /// for the pre-#1352 happy path.
    @Test
    void fullyVisibleEvictees_areAllSealed_noneDropped() {
        ring = ring(2);
        appendUnacknowledged(2);
        ring.markDurable(1);
        ring.advanceVisible(1);

        appendUnacknowledged(2);

        assertThat(sealed).containsExactly(0L, 1L);
        assertThat(dropped).isEmpty();
    }

    /// The listener's refusal still protects everything: no seal, no drop, no reclamation, and the append that
    /// needed the room fails with the listener's cause (#1234 contract, unchanged by the clamp).
    @Test
    void listenerRefusal_dropsNothing_andFailsTheAppend() {
        EvictionListener refusing = (_, _, _) -> StreamError.General.SEALING_BEHIND.result();
        ring = offHeapRingBuffer(STREAM, PARTITION, 2, DATA_BYTES, refusing, dropListener, EvictionPolicy.DROP_OLDEST, _ -> true, _ -> {})
                .or(() -> { throw new AssertionError("ring"); });

        appendUnacknowledged(2);
        ring.markDurable(0);
        ring.advanceVisible(0);
        var third = ring.appendOrdered("e2".getBytes(UTF_8), 1L, VISIBLE, Result::success);

        assertThat(third.isFailure()).as("the append is refused with the listener's cause").isTrue();
        assertThat(ring.tailOffset()).as("nothing was reclaimed").isEqualTo(0L);
        assertThat(dropped).as("a refused hand-over drops nothing either").isEmpty();
    }

    /// A ring without a sealer (`EvictionListener.NOOP`) still reports unacknowledged evictees: the partition
    /// manager needs the report to fail their publishers' awaits whether or not anything is ever sealed.
    @Test
    void noopSealer_unacknowledgedEvicteesAreStillReported() {
        ring = offHeapRingBuffer(STREAM, PARTITION, 2, DATA_BYTES, EvictionListener.NOOP, dropListener, EvictionPolicy.DROP_OLDEST, _ -> true, _ -> {})
                .or(() -> { throw new AssertionError("ring"); });

        appendUnacknowledged(4);

        assertThat(ranges(dropped)).containsExactly("[0, 0]", "[1, 1]");
    }

    private OffHeapRingBuffer ring(long capacity) {
        return offHeapRingBuffer(STREAM, PARTITION, capacity, DATA_BYTES, sealer, dropListener, EvictionPolicy.DROP_OLDEST, _ -> true, _ -> {})
                .or(() -> { throw new AssertionError("ring"); });
    }

    /// The owner path's shape: appended, not yet acknowledged.
    private void appendUnacknowledged(int count) {
        appendUnacknowledged(VISIBLE, count);
    }

    private void appendUnacknowledged(SealBound sealBound, int count) {
        for (var i = 0; i < count; i++) {
            ring.appendOrdered(("e" + ring.headOffset()).getBytes(UTF_8), 1L, sealBound, Result::success)
                .onFailure(cause -> { throw new AssertionError(cause.message()); });
        }
    }

    private static List<String> ranges(List<long[]> ranges) {
        return ranges.stream()
                     .map(range -> "[" + range[0] + ", " + range[1] + "]")
                     .toList();
    }
}
