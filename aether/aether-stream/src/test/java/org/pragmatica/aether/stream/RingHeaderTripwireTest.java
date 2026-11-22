// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.RemovalCause;
import org.pragmatica.aether.slice.stream.RingGeometry;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// The tripwires of [RingHeader] (M-2r-1, ruling 9): a step that would break the ring's own invariants is refused with a typed
/// [RingStateViolated] and leaves the header as it was. Also the geometry checks made at construction.
class RingHeaderTripwireTest {
    @Test
    void publish_ofATailThatDidNotRise_isRefusedAndLeavesRUnchanged() {
        try (var arena = Arena.ofConfined()) {
            var header = header(arena);
            var raised = RemovalLedger.EMPTY.seeded(4).unwrap();

            assertThat(header.publish(raised).isSuccess()).isTrue();
            assertRefused(header.publish(raised));
            assertRefused(header.publish(RemovalLedger.EMPTY));
            assertThat(header.ledger()).isSameAs(raised);
        }
    }

    /// After `cut(k)` the head is k (mutant: store -1, which loses the head), and a cut outside `[tail - 1, head]` is refused.
    @Test
    void cut_withinTheRange_setsTheHeadAndOutsideItIsRefused() {
        try (var arena = Arena.ofConfined()) {
            var header = header(arena);

            header.setHead(8);
            header.publish(RemovalLedger.EMPTY.seeded(2).unwrap());

            assertRefused(header.cut(1));
            assertRefused(header.cut(9));
            assertThat(header.head()).as("a refused cut leaves the head").isEqualTo(8L);

            assertThat(header.cut(5).isSuccess()).isTrue();
            assertThat(header.head()).isEqualTo(5L);

            assertThat(header.cut(2).isSuccess()).as("tail - 1 is the empty suffix boundary").isTrue();
            assertThat(header.head()).isEqualTo(2L);
        }
    }

    @Test
    void appended_belowTheTail_isRefusedAndAboveItStoresTheThreeWords() {
        try (var arena = Arena.ofConfined()) {
            var header = header(arena);
            var next = RemovalLedger.EMPTY.seeded(4).unwrap();

            header.publish(next);

            assertRefused(header.appended(3, 10));
            assertThat(header.eventCount()).isZero();

            assertThat(header.appended(5, 10).isSuccess()).isTrue();
            assertThat(header.head()).isEqualTo(5L);
            assertThat(header.dataWritePos()).isEqualTo(10L);
            assertThat(header.eventCount()).isEqualTo(1L);
        }
    }

    /// The geometry holds the header words, and the index slice starts exactly where the header slice ends: a header over the
    /// index would be corrupted by the first append, a gap would waste bytes.
    @Test
    void ringHeader_andIndex_splitTheControlSegmentAtTheHeaderSize() {
        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(RingGeometry.HEADER_SIZE + 64);
            var header = RingHeader.ringHeader(segment);
            var index = RingHeader.index(segment);

            assertThat(RingGeometry.HEADER_SIZE).as("the real geometry holds the words").isGreaterThanOrEqualTo(RingHeader.SIZE);
            assertThat(index.address() - segment.address()).as("index start").isEqualTo(RingGeometry.HEADER_SIZE);
            assertThat(index.byteSize()).as("index size").isEqualTo(64L);

            header.initialize(4096, 8);
            index.fill((byte) 0x7f);

            assertThat(header.capacity()).as("index writes leave the header words intact").isEqualTo(8L);
            assertThat(header.head()).isEqualTo(-1L);
        }
    }

    @Test
    void removedByACause_thenPublished_isTheNextR() {
        try (var arena = Arena.ofConfined()) {
            var header = header(arena);
            var removed = RemovalLedger.EMPTY.removed(3, RemovalCause.AGE).unwrap();

            assertThat(header.publish(removed).isSuccess()).isTrue();
            assertThat(header.ledger().tail()).isEqualTo(3L);
        }
    }

    private static RingHeader header(Arena arena) {
        MemorySegment segment = arena.allocate(RingGeometry.HEADER_SIZE);
        var header = RingHeader.ringHeader(segment);

        header.initialize(4096, 8);

        return header;
    }

    private static void assertRefused(Result<Unit> result) {
        var seen = new AtomicReference<Cause>();

        result.onFailure(seen::set);

        assertThat(seen.get()).isInstanceOf(RingStateViolated.class);
    }
}
