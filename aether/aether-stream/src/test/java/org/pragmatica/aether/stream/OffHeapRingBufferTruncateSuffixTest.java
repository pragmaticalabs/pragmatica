// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

/// #1730 phase 2 (KIP-101): a ring whose tail diverged from its committed owner's is cut back to the last common
/// offset, so the owner's records can take those offsets. The cut keeps the three positions, the event count and the
/// data accounting exact, and a reader that overlaps it never sees a record assembled from two lineages.
class OffHeapRingBufferTruncateSuffixTest {
    @Test
    void truncateSuffix_removesTheEventsAboveTheCut_andTheOwnersRecordsTakeTheirOffsets() {
        try (var ring = offHeapRingBuffer(8, 4096)) {
            appendDurable(ring, "p", 6);

            assertThat(ring.truncateSuffix(2).unwrap()).isEqualTo(3L);

            assertThat(ring.headOffset()).isEqualTo(2L);
            assertThat(ring.eventCount()).isEqualTo(3L);
            assertThat(texts(ring.readAppended(0, 10).unwrap())).containsExactly("p0", "p1", "p2");
            assertThat(ring.durableOffset()).as("durable follows the cut down").isEqualTo(2L);
            assertThat(ring.visibleOffset()).as("visible follows the cut down").isEqualTo(2L);
            assertThat(ring.read(0, 10).unwrap()).hasSize(3);

            assertThat(ring.append(bytes("owner-3"), 33L).unwrap()).isEqualTo(3L);
            assertThat(texts(ring.readAppended(3, 5).unwrap())).containsExactly("owner-3");
        }
    }

    @Test
    void truncateSuffix_atOrAboveTheHead_changesNothing() {
        try (var ring = offHeapRingBuffer(8, 4096)) {
            appendDurable(ring, "p", 3);

            assertThat(ring.truncateSuffix(2).unwrap()).isZero();
            assertThat(ring.truncateSuffix(50).unwrap()).isZero();

            assertThat(ring.headOffset()).isEqualTo(2L);
            assertThat(ring.eventCount()).isEqualTo(3L);
            assertThat(ring.visibleOffset()).isEqualTo(2L);
        }
    }

    /// Every retained event is divergent: the ring empties but keeps its position, as after a seed.
    @Test
    void truncateSuffix_toJustBelowTheTail_emptiesTheRing_keepingItsPosition() {
        try (var ring = offHeapRingBuffer(4, 4096)) {
            appendDurable(ring, "p", 10);

            assertThat(ring.tailOffset()).isEqualTo(6L);
            assertThat(ring.truncateSuffix(5).unwrap()).isEqualTo(4L);

            assertThat(ring.headOffset()).isEqualTo(5L);
            assertThat(ring.tailOffset()).isEqualTo(6L);
            assertThat(ring.eventCount()).isZero();
            assertThat(ring.append(bytes("owner-6"), 66L).unwrap()).isEqualTo(6L);
        }
    }

    /// Below the retained range the ring cannot say the evicted offsets are gone: refused, nothing changed.
    @Test
    void truncateSuffix_belowTheRetainedRange_isRefused_withNoChange() {
        try (var ring = offHeapRingBuffer(4, 4096)) {
            appendDurable(ring, "p", 10);

            ring.truncateSuffix(3).onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must refuse"))
                .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.TruncateBelowRetained.class));

            assertThat(ring.headOffset()).isEqualTo(9L);
            assertThat(ring.eventCount()).isEqualTo(4L);
        }
    }

    /// The data accounting must not drift across cuts: after many append-then-cut cycles the ring still wraps,
    /// evicts and reads back exactly what was appended.
    @Test
    void truncateSuffix_manyCycles_keepsTheDataAccountingExact() {
        try (var ring = offHeapRingBuffer(16, 1024)) {
            for (var cycle = 0; cycle < 500; cycle++) {
                appendDurable(ring, "c" + cycle + "-", 5);
                ring.truncateSuffix(ring.headOffset() - 3).unwrap();
            }

            for (var i = 0; i < 100; i++) {
                ring.append(bytes("final-" + i), i).unwrap();
            }

            var head = ring.headOffset();
            var events = ring.readAppended(ring.tailOffset(), 1000).unwrap();

            assertThat(events).hasSize((int) ring.eventCount());
            assertThat(events.getLast().offset()).isEqualTo(head);
            assertThat(texts(List.of(events.getLast()))).containsExactly("final-99");
            assertThat(ring.eventCount()).isEqualTo(head - ring.tailOffset() + 1);
        }
    }

    /// The reader half: records carry their offset, a generation and a fill the length of which varies with the
    /// generation. A read assembled from the index entry of one lineage and the bytes of another has the wrong
    /// length or a mixed fill, and is counted torn. Without the seqlock the writer's cut-and-reappend tears reads.
    @Test
    void read_neverReturnsARecordAssembledFromTwoLineages_whileTheTailIsCutAndRewritten() throws InterruptedException {
        try (var ring = offHeapRingBuffer(64, 64 * 1024)) {
            var done = new AtomicBoolean(false);
            var failure = new AtomicReference<String>();
            var generation = new AtomicLong();
            var writer = Thread.ofPlatform().start(() -> rewriteTail(ring, done, failure, generation));
            var reads = 0L;
            var checked = 0L;
            var torn = 0L;
            var firstTorn = "";

            while (!done.get()) {
                var tail = ring.tailOffset();

                if (tail < 0) {
                    continue;
                }

                var result = ring.readAppended(Math.max(tail, 0), 32);

                reads++;
                if (result.isFailure()) {
                    continue;
                }

                for (var event : result.unwrap()) {
                    checked++;
                    var problem = problemWith(event);

                    if (problem != null) {
                        torn++;
                        if (firstTorn.isEmpty()) {
                            firstTorn = problem;
                        }
                    }
                }
            }

            writer.join();
            System.out.println("#1730 truncate/read race: reads=" + reads + " checked=" + checked + " torn=" + torn);
            assertThat(failure.get()).isNull();
            assertThat(checked).as("the reader must have checked records").isGreaterThan(10_000);
            assertThat(torn).as("records assembled from two lineages; first: " + firstTorn).isZero();
        }
    }

    private static void rewriteTail(OffHeapRingBuffer ring, AtomicBoolean done, AtomicReference<String> failure, AtomicLong generation) {
        try {
            for (var round = 0; round < 60_000; round++) {
                var gen = generation.incrementAndGet();
                var count = 10 + (round % 20);

                for (var i = 0; i < count; i++) {
                    var offset = ring.headOffset() + 1;
                    var appended = ring.append(stamped(offset, gen), offset);

                    if (appended.isFailure() || appended.unwrap() != offset) {
                        failure.set("append at " + offset + " returned " + appended);

                        return;
                    }
                }

                var cut = Math.max(ring.tailOffset() - 1, ring.headOffset() - 1 - (round % 15));

                if (ring.truncateSuffix(cut).isFailure()) {
                    failure.set("truncate to " + cut + " refused");

                    return;
                }
            }
        } finally {
            done.set(true);
        }
    }

    /// 8 bytes offset, 8 bytes generation, then `fill` bytes of one character; the fill length depends on the
    /// generation so two lineages never share a record length.
    private static byte[] stamped(long offset, long generation) {
        var fill = 8 + (int) (generation % 23) * 3;
        var buffer = ByteBuffer.allocate(16 + fill);

        buffer.putLong(offset).putLong(generation);
        for (var i = 0; i < fill; i++) {
            buffer.put((byte) ('a' + generation % 26));
        }

        return buffer.array();
    }

    private static String problemWith(OffHeapRingBuffer.RawEvent event) {
        var data = ByteBuffer.wrap(event.data());

        if (data.remaining() < 17) {
            return "offset " + event.offset() + " too short: " + data.remaining();
        }

        var stampedOffset = data.getLong();
        var generation = data.getLong();
        var expectedFill = 8 + (int) (generation % 23) * 3;

        if (stampedOffset != event.offset()) {
            return "offset " + event.offset() + " carried " + stampedOffset;
        }

        if (data.remaining() != expectedFill) {
            return "offset " + event.offset() + " gen " + generation + " has fill " + data.remaining() + ", expected " + expectedFill;
        }

        var expectedChar = (byte) ('a' + generation % 26);

        while (data.hasRemaining()) {
            if (data.get() != expectedChar) {
                return "offset " + event.offset() + " gen " + generation + " has a mixed fill";
            }
        }

        return null;
    }

    private static void appendDurable(OffHeapRingBuffer ring, String prefix, int count) {
        for (var i = 0; i < count; i++) {
            var offset = ring.append(bytes(prefix + (ring.headOffset() + 1)), i).unwrap();

            ring.markDurable(offset);
            ring.advanceVisible(offset);
        }
    }

    private static List<String> texts(List<OffHeapRingBuffer.RawEvent> events) {
        var out = new ArrayList<String>();

        events.forEach(event -> out.add(new String(event.data(), StandardCharsets.UTF_8)));

        return out;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
