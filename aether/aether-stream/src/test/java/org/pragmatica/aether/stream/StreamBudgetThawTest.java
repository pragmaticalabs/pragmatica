// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager.Exhaustion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 C-03, spec 4.4 items 4 and 5.
///
/// - NT-17 (the frozen-ring half of C-03): a ring frozen by an exhausted budget grows again after the manager releases budget.
///   Red under MT-09 (the budget-freed generation is not bumped on release).
/// - The thaw keeps every live event intact when the frozen ring has wrapped: the index holds data positions wrapped at the
///   frozen allocation, so a ring may lengthen only past its last live byte, and does so once its live bytes sit in one
///   unwrapped stretch.
/// - The floor headroom (F-07): growth is admitted only up to three quarters of the budget, a floor against the whole budget, so
///   a stream created after the rings have filled finds room for its floor. Red under MT-37 (growth against the whole budget).
///
/// Payloads are 10,000 bytes, which does not divide the 256 KiB segment, so records straddle the wrap point of a frozen ring.
class StreamBudgetThawTest {
    private static final long SEGMENT = 256 * 1024L;
    private static final long MIB = 1024 * 1024L;
    private static final int PAYLOAD = 10_000;
    /// 64 B header + 24 B x 10,000 index + a 256 KiB first segment.
    private static final long FLOOR = 64L + 24L * 10_000 + SEGMENT;

    private static StreamConfig stream(String name, long maxBytes) {
        return StreamConfig.streamConfig(name, 1, RetentionPolicy.retentionPolicy(10_000, maxBytes, 3_600_000L), "latest");
    }

    @Nested
    class FrozenRingRegrows {
        /// NT-17: two floors leave room for a little growth of `a`, which then freezes; while nothing is released it stays at that
        /// allocation however much is appended (it wraps within it); destroying `b` releases its floor, and `a` grows again, every
        /// live event reading back as written throughout.
        @Test
        void aRingFrozenByAnExhaustedBudget_regrowsAfterBudgetIsReleased_andEveryLiveEventReadsBackAsWritten() {
            var sink = new GrowthRefusals();
            var manager = streamPartitionManager(2 * MIB);

            manager.exhaustionSink(sink);
            try {
                create(manager, stream("a", 4 * MIB));
                create(manager, stream("b", SEGMENT));

                var offsets = new AtomicLong();
                var ring = manager.partitionBuffer("a", 0).unwrap();

                appendUntilFrozen(manager, sink, offsets);

                var frozenAt = ring.allocatedBytes();

                appendBytes(manager, ring, offsets, 3 * frozenAt);
                assertThat(ring.allocatedBytes()).as("control: a frozen ring with nothing released stays at its allocation, though it wrapped")
                                                 .isEqualTo(frozenAt);

                manager.destroyStream("b").onFailure(cause -> fail("destroy b: " + cause.message()));
                appendBytes(manager, ring, offsets, 3 * frozenAt);

                assertThat(ring.allocatedBytes()).as("NT-17: after b's floor was released, the frozen ring grew past %d", frozenAt)
                                                 .isGreaterThan(frozenAt);
            } finally {
                manager.close();
            }
        }
    }

    /// The ring's side of item 4, with the generation as a plain counter: a frozen ring asks the budget again only after the
    /// generation moved, once per move, and grows once it is admitted.
    @Nested
    class Generation {
        @Test
        void aFrozenRing_retriesGrowthOnlyWhenTheGenerationMoved_onceEachTime_andGrowsWhenAdmitted() {
            var admit = new AtomicBoolean(false);
            var asked = new AtomicInteger();
            var generation = new AtomicLong();
            var ring = OffHeapRingBuffer.offHeapRingBuffer("gen",
                                                           0,
                                                           10_000,
                                                           8 * SEGMENT,
                                                           EvictionListener.NOOP,
                                                           EvictionPolicy.DROP_OLDEST,
                                                           bytes -> asked.incrementAndGet() > 0 && admit.get(),
                                                           _ -> {},
                                                           generation::get)
                                        .unwrap();
            var offsets = new AtomicLong();

            try {
                appendBytes(ring, offsets, 2 * SEGMENT);
                assertThat(asked.get()).as("the first growth past the first segment was refused once").isEqualTo(1);

                appendBytes(ring, offsets, 2 * SEGMENT);
                assertThat(asked.get()).as("frozen at an unmoved generation: never asks again").isEqualTo(1);

                generation.incrementAndGet();
                appendBytes(ring, offsets, 2 * SEGMENT);
                assertThat(asked.get()).as("the generation moved: asked once more within one lap, refused, frozen again").isEqualTo(2);

                admit.set(true);
                appendBytes(ring, offsets, 2 * SEGMENT);
                assertThat(asked.get()).as("admitted, but the generation has not moved since the last refusal").isEqualTo(2);
                assertThat(ring.allocatedBytes() - ring.controlBytes()).isEqualTo(SEGMENT);

                generation.incrementAndGet();
                appendBytes(ring, offsets, 2 * SEGMENT);
                assertThat(ring.allocatedBytes() - ring.controlBytes()).as("grown past the first segment once admitted")
                                                                       .isGreaterThan(SEGMENT);
            } finally {
                ring.close();
            }
        }
    }

    /// F-07: the growth share and the floor headroom. 4 MiB budget: growth stops at 3 MiB, and the floors of two more streams
    /// (502,208 B each) are admitted past it against the whole budget; a third does not fit the budget.
    @Nested
    class FloorHeadroom {
        @Test
        void growthStopsAtThreeQuartersOfTheBudget_andLaterFloorsAreAdmittedAgainstTheWholeBudget() {
            var sink = new GrowthRefusals();
            var manager = streamPartitionManager(4 * MIB);

            manager.exhaustionSink(sink);
            try {
                create(manager, stream("fill", 8 * MIB));
                appendUntilFrozen(manager, sink, "fill", new AtomicLong());

                assertThat(manager.totalAllocatedBytes()).as("growth is admitted only up to three quarters of the budget")
                                                         .isLessThanOrEqualTo(3 * MIB)
                                                         .isGreaterThan(3 * MIB - SEGMENT);

                create(manager, stream("late-1", SEGMENT));
                create(manager, stream("late-2", SEGMENT));
                // The fill froze at its floor plus 10 segments: 502,208 + 10 x 262,144 = 3,123,648 = 3 MiB - 22,080.
                assertThat(manager.totalAllocatedBytes()).as("both floors were admitted past the growth share").isEqualTo(3 * MIB - 22_080L + 2 * FLOOR);

                manager.createStream(stream("late-3", SEGMENT))
                       .onSuccess(_ -> fail("a third floor does not fit the whole budget"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));
            } finally {
                manager.close();
            }
        }
    }

    private static void create(StreamPartitionManager manager, StreamConfig config) {
        manager.createStream(config).onFailure(cause -> fail("create " + config.name() + ": " + cause.message()));
    }

    private static void appendUntilFrozen(StreamPartitionManager manager, GrowthRefusals sink, AtomicLong offsets) {
        appendUntilFrozen(manager, sink, "a", offsets);
    }

    private static void appendUntilFrozen(StreamPartitionManager manager, GrowthRefusals sink, String stream, AtomicLong offsets) {
        for (var i = 0; i < 2_000 && sink.refused(stream) == 0; i++) {
            publish(manager, stream, offsets.getAndIncrement());
        }

        assertThat(sink.refused(stream)).as("the growth of %s was refused", stream).isPositive();
    }

    private static void appendBytes(StreamPartitionManager manager, OffHeapRingBuffer ring, AtomicLong offsets, long bytes) {
        for (var written = 0L; written < bytes; written += PAYLOAD) {
            publish(manager, "a", offsets.getAndIncrement());
            assertEveryLiveEventReadsBack(ring);
        }
    }

    private static void appendBytes(OffHeapRingBuffer ring, AtomicLong offsets, long bytes) {
        for (var written = 0L; written < bytes; written += PAYLOAD) {
            var offset = offsets.getAndIncrement();

            ring.append(payload(offset), offset)
                .onFailure(cause -> fail("append " + offset + ": " + cause.message()))
                .onSuccess(appended -> assertThat(appended).isEqualTo(offset));
            assertEveryLiveEventReadsBack(ring);
        }
    }

    private static void publish(StreamPartitionManager manager, String stream, long offset) {
        manager.publishLocal(stream, 0, payload(offset), offset)
               .onFailure(cause -> fail("publish " + stream + " " + offset + ": " + cause.message()))
               .onSuccess(appended -> assertThat(appended).isEqualTo(offset));
    }

    private static void assertEveryLiveEventReadsBack(OffHeapRingBuffer ring) {
        var tail = ring.tailOffset();

        var events = ring.read(tail, 10_000).onFailure(cause -> fail("read from " + tail + ": " + cause.message())).unwrap();

        assertThat(events.size()).as("every live event is read: %d..%d", tail, ring.headOffset()).isEqualTo(ring.headOffset() - tail + 1);
        events.forEach(event -> assertThat(event.data()).as("offset %d reads as written", event.offset()).isEqualTo(payload(event.offset())));
    }

    /// The offset in the first 8 bytes, then a byte pattern that differs per offset, so a record read from the wrong position
    /// (another record's bytes, or a stale segment) cannot pass.
    private static byte[] payload(long offset) {
        var bytes = new byte[PAYLOAD];

        ByteBuffer.wrap(bytes).putLong(offset);
        for (var i = Long.BYTES; i < PAYLOAD; i++) {
            bytes[i] = (byte) (offset * 31 + i);
        }

        return bytes;
    }

    private static final class GrowthRefusals implements Consumer<Exhaustion> {
        private final List<Exhaustion> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(Exhaustion exhaustion) {
            events.add(exhaustion);
        }

        long refused(String stream) {
            return events.stream()
                         .filter(e -> e.phase() == Exhaustion.Phase.GROWTH && e.streamName().equals(stream))
                         .count();
        }
    }
}
