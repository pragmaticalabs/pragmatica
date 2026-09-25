// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

/// #1505 — [OffHeapRingBuffer#appendOrderedAt], the offset authority the replica's catch-up apply and live
/// receive share. One test per outcome: append at the next offset, verify an identical held record without
/// writing, refuse a different held record (payload or timestamp), refuse a gap, refuse an evicted offset,
/// refuse a closed ring.
class OffHeapRingBufferAppendAtTest {
    private static final long CAPACITY = 4;
    private static final long DATA_REGION = 4096;

    private final List<Long> inOrderRuns = new ArrayList<>();
    private final RecordingFence fence = new RecordingFence();

    @Test
    void appendOrderedAt_nextOffset_appendsThere_andRunsInOrder() {
        try (var ring = ringHolding(2)) {
            var result = ring.appendOrderedAt(2, event(2), 1002L, fence, this::recordInOrder);

            assertThat(result.unwrap()).isEqualTo(2L);
            assertThat(ring.headOffset()).isEqualTo(2L);
            assertThat(inOrderRuns).containsExactly(2L);
        }
    }

    @Test
    void appendOrderedAt_heldIdenticalRecord_succeedsWithoutWriting() {
        try (var ring = ringHolding(3)) {
            var result = ring.appendOrderedAt(1, event(1), 1001L, fence, this::recordInOrder);

            assertThat(result.unwrap()).isEqualTo(1L);
            assertThat(ring.headOffset()).as("nothing appended").isEqualTo(2L);
            assertThat(inOrderRuns).as("no WAL frame for a record already held").isEmpty();
        }
    }

    @Test
    void appendOrderedAt_heldRecordWithDifferentPayload_refusesWithConflict() {
        try (var ring = ringHolding(3)) {
            expectRefusal(ring.appendOrderedAt(1, event(9), 1001L, fence, this::recordInOrder),
                          StreamError.ReplicaEntryConflict.class);

            assertThat(ring.headOffset()).isEqualTo(2L);
            assertThat(new String(ring.readAppended(1, 1).unwrap().getFirst().data(), UTF_8)).isEqualTo("event-1");
        }
    }

    @Test
    void appendOrderedAt_heldRecordWithDifferentTimestamp_refusesWithConflict() {
        try (var ring = ringHolding(3)) {
            expectRefusal(ring.appendOrderedAt(1, event(1), 9999L, fence, this::recordInOrder),
                          StreamError.ReplicaEntryConflict.class);
        }
    }

    @Test
    void appendOrderedAt_pastNextOffset_refusesWithGap_appendsNothing() {
        try (var ring = ringHolding(2)) {
            expectRefusal(ring.appendOrderedAt(3, event(3), 1003L, fence, this::recordInOrder),
                          StreamError.ReplicaOffsetGap.class);

            assertThat(ring.headOffset()).isEqualTo(1L);
            assertThat(inOrderRuns).isEmpty();
        }
    }

    @Test
    void appendOrderedAt_evictedOffset_refusesAsUnverifiable() {
        try (var ring = ringHolding((int) CAPACITY + 2)) {
            assertThat(ring.tailOffset()).as("offset 0 evicted").isPositive();

            expectRefusal(ring.appendOrderedAt(0, event(0), 1000L, fence, this::recordInOrder), StreamError.CursorExpired.class);
        }
    }

    @Test
    void appendOrderedAt_closedRing_refuses() {
        var ring = ringHolding(1);
        ring.close();

        ring.appendOrderedAt(1, event(1), 1001L, fence, this::recordInOrder)
            .onSuccess(offset -> Assertions.fail("a closed ring must refuse, got offset " + offset))
            .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.BUFFER_CLOSED));
    }

    /// #1505 F2: a conflict records the divergent offset in the fence, inside the section that found it.
    @Test
    void appendOrderedAt_conflict_recordsDivergenceInTheFence() {
        try (var ring = ringHolding(3)) {
            expectRefusal(ring.appendOrderedAt(1, event(9), 1001L, fence, this::recordInOrder),
                          StreamError.ReplicaEntryConflict.class);

            assertThat(fence.divergedAt()).isEqualTo(1L);
        }
    }

    /// #1505 F2: once a divergence is known at N, an offer at or past N is refused even when it is the next
    /// offset or an identical held record, while an offer below N still verifies.
    @Test
    void appendOrderedAt_quarantined_refusesAtOrPastDivergence_verifiesBelowIt() {
        try (var ring = ringHolding(3)) {
            fence.recordDivergence(2);

            expectRefusal(ring.appendOrderedAt(3, event(3), 1003L, fence, this::recordInOrder),
                          StreamError.ReplicaQuarantined.class);
            expectRefusal(ring.appendOrderedAt(2, event(2), 1002L, fence, this::recordInOrder),
                          StreamError.ReplicaQuarantined.class);
            assertThat(ring.appendOrderedAt(1, event(1), 1001L, fence, this::recordInOrder).unwrap()).isEqualTo(1L);
            assertThat(ring.headOffset()).as("nothing appended past the divergence").isEqualTo(2L);
            assertThat(inOrderRuns).isEmpty();
        }
    }

    private static final class RecordingFence implements OffHeapRingBuffer.DivergenceFence {
        private long divergedAt = -1L;

        @Override
        public long divergedAt() {
            return divergedAt;
        }

        @Override
        public void recordDivergence(long offset) {
            divergedAt = divergedAt < 0 ? offset : Math.min(divergedAt, offset);
        }
    }

    private OffHeapRingBuffer ringHolding(int count) {
        var ring = offHeapRingBuffer(CAPACITY, DATA_REGION);

        for (var i = 0; i < count; i++) {
            ring.append(event(i), 1000L + i).unwrap();
        }

        return ring;
    }

    private Result<Long> recordInOrder(Long offset) {
        inOrderRuns.add(offset);

        return Result.success(offset);
    }

    private static void expectRefusal(Result<Long> result, Class<? extends StreamError> expected) {
        result.onSuccess(offset -> Assertions.fail("expected a " + expected.getSimpleName() + " refusal, got offset " + offset))
              .onFailure(cause -> assertThat(cause).isInstanceOf(expected));
    }

    private static byte[] event(int i) {
        return ("event-" + i).getBytes(UTF_8);
    }
}
