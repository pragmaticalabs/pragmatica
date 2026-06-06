// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

/// Tests for the segmented data region + growth-admission seam (spec §11.2).
///
/// The cap (`maxBytes`) here is deliberately larger than `DEFAULT_SEGMENT_BYTES` (256 KiB) so the
/// buffer must actually grow across segments, unlike the small-region buffers in
/// `OffHeapRingBufferTest`/`EvictionPolicyTest` which fit in a single segment.
class OffHeapRingBufferGrowthTest {

    private static final long SEGMENT = 256 * 1024L;

    @Test
    void floorBytes_lessThanCapBytes_forLargeRetention() {
        var capacity = 10_000L;
        var maxBytes = 4L * 1024 * 1024;

        var floor = OffHeapRingBuffer.floorBytes(capacity, maxBytes);
        var cap = OffHeapRingBuffer.capBytes(capacity, maxBytes);

        // floor = header + index + one 256 KiB segment; cap = header + index + full 4 MiB region
        assertThat(floor).isEqualTo(64 + 24 * capacity + SEGMENT);
        assertThat(cap).isEqualTo(64 + 24 * capacity + maxBytes);
        assertThat(floor).isLessThan(cap);
    }

    @Test
    void floorBytes_equalsCapBytes_whenRegionFitsOneSegment() {
        // maxBytes < DEFAULT_SEGMENT_BYTES: the single segment is the whole cap
        var floor = OffHeapRingBuffer.floorBytes(100, 4096);
        var cap = OffHeapRingBuffer.capBytes(100, 4096);

        assertThat(floor).isEqualTo(cap);
    }

    @Test
    void allocatedBytes_startsAtFloor_growsTowardCap() {
        var capacity = 100L;
        var maxBytes = 2L * SEGMENT; // two segments worth
        var buffer = offHeapRingBuffer("grow", 0, capacity, maxBytes, EvictionListener.NOOP);

        try {
            assertThat(buffer.allocatedBytes()).isEqualTo(OffHeapRingBuffer.floorBytes(capacity, maxBytes));

            // First payload fits in the first segment — no growth
            buffer.append(new byte[1024], 1L)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
            assertThat(buffer.allocatedBytes()).isEqualTo(OffHeapRingBuffer.floorBytes(capacity, maxBytes));

            // A payload that straddles past the first segment forces one growth
            var straddle = new byte[(int) SEGMENT];
            buffer.append(straddle, 2L)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
            assertThat(buffer.allocatedBytes()).isEqualTo(OffHeapRingBuffer.capBytes(capacity, maxBytes));
        } finally {
            buffer.close();
        }
    }

    @Test
    void append_spansSegmentBoundary_readBackIdentical() {
        var capacity = 100L;
        var maxBytes = 2L * SEGMENT;
        var buffer = offHeapRingBuffer("boundary", 0, capacity, maxBytes, EvictionListener.NOOP);

        try {
            // First event lands at logical pos 0..200_000; second starts at 200_000 and runs to
            // 320_000 — crossing the 262_144 (256 KiB) segment boundary.
            var first = patterned(200_000, 1);
            var crossing = patterned(120_000, 7);

            buffer.append(first, 100L).onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
            buffer.append(crossing, 200L).onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            buffer.read(0, 2)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(2);
                      assertThat(events.get(0).data()).isEqualTo(first);
                      assertThat(events.get(1).data()).isEqualTo(crossing);
                      assertThat(events.get(1).timestamp()).isEqualTo(200L);
                  });
        } finally {
            buffer.close();
        }
    }

    @Test
    void grow_property_matchesSingleSegmentOracle() {
        // Sizing rationale: capacity (64) is the ONLY binding eviction constraint. Max payload is
        // 8 KiB, so worst-case live data = 64 * 8 KiB = 512 KiB < maxBytes (1 MiB). Thus the
        // buffer's data-region eviction never fires and count-retention (driven identically into
        // both buffer and oracle) is the sole eviction driver — keeping them in exact lockstep
        // while still forcing multi-segment growth (region 1 MiB > 256 KiB segment).
        var capacity = 64L;
        var maxPayload = 8 * 1024;
        var maxBytes = 4L * SEGMENT;        // 1 MiB → up to 4 segments
        var ops = 6_000;
        var seed = 0x5DEECE66DL;

        var buffer = offHeapRingBuffer("prop", 0, capacity, maxBytes, EvictionListener.NOOP);
        var oracle = new RingOracle(capacity, maxBytes);
        var rng = new Lcg(seed);

        try {
            for (var step = 0; step < ops; step++) {
                var choice = rng.nextInt(10);
                if (choice < 8) {
                    var size = 1 + rng.nextInt(maxPayload);
                    var payload = patterned(size, rng.nextInt(251) + 1);
                    var ts = 1_000L + step;

                    var actual = appendOffset(buffer.append(payload, ts));
                    var expected = oracle.append(payload, ts);
                    assertThat(actual)
                            .as("append offset at step %d (size %d)", step, size)
                            .isEqualTo(expected);
                } else {
                    var from = oracle.tail() + rng.nextInt(4);
                    var max = rng.nextInt(8) + 1;
                    assertReadMatches(buffer, oracle, from, max, step);
                }

                // Invariant at every step: tail/head/count agree.
                assertThat(buffer.tailOffset()).as("tail at step %d", step).isEqualTo(oracle.tail());
                assertThat(buffer.headOffset()).as("head at step %d", step).isEqualTo(oracle.head());
                assertThat(buffer.eventCount()).as("count at step %d", step).isEqualTo(oracle.count());
            }

            // Final full read-back of every live event, byte-for-byte.
            var span = (int) (oracle.head() - oracle.tail() + 2);
            assertReadMatches(buffer, oracle, oracle.tail(), Math.max(span, 1), ops);
        } finally {
            buffer.close();
        }
    }

    @Test
    void growthSeamReject_eventual_fallsBackToEvict() {
        var capacity = 1_000L;
        var maxBytes = 5L * SEGMENT;
        // Admit the floor segment (already done at construction, not via this predicate) and exactly
        // ONE growth segment, then reject all further growth.
        var grantsLeft = new AtomicLong(1);
        LongPredicate reserve = _ -> grantsLeft.getAndDecrement() > 0;
        LongConsumer release = _ -> {};

        var buffer = offHeapRingBuffer("evt", 0, capacity, maxBytes, EvictionListener.NOOP,
                                       EvictionPolicy.DROP_OLDEST, reserve, release);

        try {
            // Distinct content per event so a misaddressed read is detectable. 50_000-byte events
            // into a frozen 512 KiB ring force repeated wrap + cross-segment splits.
            var payloads = new ArrayList<byte[]>();
            for (var i = 0; i < 100; i++) {
                var payload = patterned(50_000, (i % 250) + 1);
                payloads.add(payload);
                var ts = 1_000L + i;
                buffer.append(payload, ts)
                      .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("EVENTUAL append must succeed via eviction"));
            }

            // Allocation frozen at floor + 1 granted segment.
            assertThat(buffer.allocatedBytes()).isEqualTo(64 + 24 * capacity + 2 * SEGMENT);

            // Every still-live event must read back byte-identical (across frozen-ring wrap + segment
            // boundaries). Read the whole live range and verify each payload + timestamp.
            var tail = buffer.tailOffset();
            var head = buffer.headOffset();
            assertThat(tail).isGreaterThan(0L); // eviction did happen
            buffer.read(tail, (int) (head - tail + 1))
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      for (var event : events) {
                          var i = (int) event.offset();
                          assertThat(event.data()).as("payload at offset %d", i).isEqualTo(payloads.get(i));
                          assertThat(event.timestamp()).isEqualTo(1_000L + i);
                      }
                  });
        } finally {
            buffer.close();
        }
    }

    @Test
    void growthSeamReject_strong_returnsFull() {
        var capacity = 1_000L;
        var maxBytes = 5L * SEGMENT;
        LongPredicate reserveNever = _ -> false; // never admit any growth
        LongConsumer release = _ -> {};

        var buffer = offHeapRingBuffer("strong", 0, capacity, maxBytes, EvictionListener.NOOP,
                                       EvictionPolicy.REJECT_WHEN_FULL, reserveNever, release);

        try {
            // Fill the single floor segment (256 KiB) with events that don't trigger count/data
            // rejection yet, then the append that needs to grow returns STREAM_MEMORY_EXCEEDED.
            var payload = patterned(60_000, 5);
            // 4 * 60_000 = 240_000 < 262_144 floor — these fit without growth.
            for (var i = 0; i < 4; i++) {
                buffer.append(payload, 1_000L + i)
                      .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success within floor"));
            }
            // 5th needs to grow; growth rejected -> STREAM_MEMORY_EXCEEDED (loud).
            buffer.append(payload, 2_000L)
                  .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected STREAM_MEMORY_EXCEEDED"))
                  .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));
        } finally {
            buffer.close();
        }
    }

    @Test
    void close_releasesAccountedBytesViaSeam() {
        var capacity = 100L;
        var maxBytes = 3L * SEGMENT;        // 768 KiB cap
        var reserved = new AtomicLong(0);
        var released = new AtomicLong(0);
        LongPredicate reserve = bytes -> {
            reserved.addAndGet(bytes);
            return true;
        };
        LongConsumer release = released::addAndGet;

        var buffer = offHeapRingBuffer("acct", 0, capacity, maxBytes, EvictionListener.NOOP,
                                       EvictionPolicy.DROP_OLDEST, reserve, release);

        // Floor segment (256 KiB) is accounted directly (not via reserve) but IS released on close.
        // Three ~200 KiB payloads force two growths:
        //   #1 end 200_000 fits the 262_144 floor (no growth)
        //   #2 needs end 400_000 -> grow to 524_288 (growth 1)
        //   #3 needs end 600_000 -> grow to 786_432 cap (growth 2)
        var floorSegment = SEGMENT;
        for (var i = 0; i < 3; i++) {
            buffer.append(patterned(200_000, i + 1), 1_000L + i)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
        }

        var grownViaSeam = reserved.get();
        assertThat(grownViaSeam).isEqualTo(2 * SEGMENT);

        buffer.close();

        // Released on close == floor + all growth (the full accounted high-water).
        assertThat(released.get()).isEqualTo(floorSegment + grownViaSeam);
    }

    // ---- helpers ----

    private static void assertReadMatches(OffHeapRingBuffer buffer, RingOracle oracle, long from, int max, int step) {
        var expected = oracle.read(from, max);
        buffer.read(from, max)
              .onFailure(cause -> {
                  if (!oracle.expectsCursorExpired(from)) {
                      org.junit.jupiter.api.Assertions.fail("Unexpected read failure at step " + step + ": " + cause.message());
                  }
              })
              .onSuccess(events -> {
                  assertThat(events).as("read size at step %d", step).hasSize(expected.size());
                  for (var i = 0; i < expected.size(); i++) {
                      assertThat(events.get(i).offset()).isEqualTo(expected.get(i).offset());
                      assertThat(events.get(i).data()).as("payload at step %d idx %d", step, i).isEqualTo(expected.get(i).data());
                      assertThat(events.get(i).timestamp()).isEqualTo(expected.get(i).timestamp());
                  }
              });
    }

    private static long appendOffset(org.pragmatica.lang.Result<Long> result) {
        var holder = new AtomicLong(Long.MIN_VALUE);
        result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("append failed"))
              .onSuccess(holder::set);
        return holder.get();
    }

    /// Deterministic byte pattern of given length, parameterized by a seed byte so distinct events
    /// have distinct content. No Math.random.
    private static byte[] patterned(int len, int seedByte) {
        var out = new byte[len];
        var v = (byte) seedByte;
        for (var i = 0; i < len; i++) {
            out[i] = v;
            v = (byte) (v * 31 + i + seedByte);
        }
        return out;
    }

    /// Linear congruential generator (java.util.Random's constants) for deterministic sequences
    /// without importing Random's mutable-state semantics into the test surface.
    private static final class Lcg {
        private long state;

        Lcg(long seed) {
            this.state = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
        }

        int nextInt(int bound) {
            // Non-power-of-two safe, deterministic.
            state = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            var bits = (int) (state >>> 17);
            return Math.floorMod(bits, bound);
        }
    }

    /// In-heap reference ring: models exactly the segmented buffer's observable behavior (offsets,
    /// count/tail/head, eviction order, read payloads/timestamps) for a fixed cap. Eviction here is
    /// driven by count retention to mirror the buffer under the property test, keeping both in lock
    /// step. The data-region capacity bound is enforced the same way the buffer enforces it: when an
    /// append would not fit in the *allocated* (here: full cap, since the oracle allocates eagerly)
    /// data bytes, oldest events are dropped until it fits.
    private static final class RingOracle {
        private record Entry(long offset, byte[] data, long timestamp) {}

        private final long capacity;
        private final long dataRegionSize;
        private final ArrayList<Entry> live = new ArrayList<>();
        private long nextOffset = 0;
        private long tail = 0;
        private long head = -1;
        private long usedData = 0;

        RingOracle(long capacity, long dataRegionSize) {
            this.capacity = capacity;
            this.dataRegionSize = dataRegionSize;
        }

        long append(byte[] payload, long timestamp) {
            // Evict by count first (capacity), then by data fit (cap), mirroring the buffer.
            while (live.size() >= capacity) {
                dropOldest();
            }
            while (!live.isEmpty() && usedData + payload.length > dataRegionSize) {
                dropOldest();
            }
            var offset = nextOffset++;
            live.add(new Entry(offset, payload.clone(), timestamp));
            usedData += payload.length;
            head = offset;
            return offset;
        }

        private void dropOldest() {
            if (live.isEmpty()) {
                return;
            }
            var removed = live.removeFirst();
            usedData -= removed.data().length;
            tail = removed.offset() + 1;
        }

        java.util.List<OffHeapRingBuffer.RawEvent> read(long from, int max) {
            var out = new ArrayList<OffHeapRingBuffer.RawEvent>();
            for (var e : live) {
                if (e.offset() >= from && out.size() < max) {
                    out.add(OffHeapRingBuffer.RawEvent.rawEvent(e.offset(), e.data(), e.timestamp()));
                }
            }
            return out;
        }

        boolean expectsCursorExpired(long from) {
            return from < tail && head >= 0;
        }

        long tail() {
            return tail;
        }

        long head() {
            return head;
        }

        long count() {
            return live.size();
        }
    }
}
