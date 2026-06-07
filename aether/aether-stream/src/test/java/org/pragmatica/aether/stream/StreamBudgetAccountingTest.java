// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager.Exhaustion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Floor-reservation + lazy-growth budget accounting (spec §4.1 / §4.3 / §4.4 / §5.2; reconciliation
/// #1, #2, #5, #8, #16 + sink). Separate from `StreamPartitionManagerTest` to avoid churning that
/// file's line baseline.
///
/// The seam composition under test: the manager floor-reserves `Σ(control + firstSegment)` per
/// partition; each buffer's seam separately tracks `firstSegment + grown` and releases it on close;
/// the manager releases ONLY the control bytes on destroy. Sum reserved == floor at create, sum
/// released == live allocation on destroy — no double-count, no leak.
class StreamBudgetAccountingTest {

    private static final long SEGMENT = 256 * 1024L;
    private static final long HEADER = 64L;
    private static final long INDEX_ENTRY = 24L;

    /// The management-API default retention (StreamRoutes.MANAGEMENT_API_RETENTION): 10_000 count,
    /// 4 MiB bytes, 1h. 4 partitions per the stream default.
    private static StreamConfig mgmtDefault(String name) {
        var retention = RetentionPolicy.retentionPolicy(10_000, 4L * 1024 * 1024, 60 * 60 * 1000L);
        return StreamConfig.streamConfig(name, 4, retention, "latest");
    }

    private static long perPartitionFloor(long maxCount, long maxBytes) {
        return HEADER + INDEX_ENTRY * maxCount + Math.min(SEGMENT, maxBytes);
    }

    @Nested
    class FloorReservation {

        @Test
        void createStream_reservesFloorNotFullCapacity() {
            var manager = streamPartitionManager(Long.MAX_VALUE);
            try {
                var config = mgmtDefault("orders");

                manager.createStream(config).onFailure(_ -> fail("Expected success"));

                var floor = perPartitionFloor(10_000, 4L * 1024 * 1024) * 4;
                var fullCapacity = (HEADER + INDEX_ENTRY * 10_000 + 4L * 1024 * 1024) * 4;

                assertThat(manager.totalAllocatedBytes()).isEqualTo(floor);
                // The whole point: floor is far below the old full-capacity reservation (~17.7 MB).
                assertThat(manager.totalAllocatedBytes()).isLessThan(fullCapacity);
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_floorAdmission_succeedsPastOldSevenStreamWall() {
            // Production budget. Old eager model: ~7 mgmt-default streams (~17.7 MB each) fit.
            var manager = streamPartitionManager(128 * 1024 * 1024L);
            try {
                for (var i = 0; i < 20; i++) {
                    var idx = i;
                    manager.createStream(mgmtDefault("stream-" + i))
                           .onFailure(_ -> fail("stream-" + idx + " should fit under the floor model"));
                }
                assertThat(manager.listStreams()).hasSize(20);
                assertThat(manager.totalAllocatedBytes()).isLessThanOrEqualTo(manager.maxTotalBytes());
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_exhaustionAtFloor_returnsMemoryExceeded_andCallsSink() {
            var sink = new RecordingSink();
            // 1 KiB budget — even a tiny stream's floor (index alone is huge for mgmt default) won't fit.
            var manager = streamPartitionManager(1024);
            manager.exhaustionSink(sink);
            try {
                manager.createStream(mgmtDefault("too-big"))
                       .onSuccess(_ -> fail("Expected STREAM_MEMORY_EXCEEDED"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));

                // No allocation leaked: a failed floor admission reserves nothing.
                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);

                assertThat(sink.events).hasSize(1);
                var e = sink.events.getFirst();
                assertThat(e.phase()).isEqualTo(Exhaustion.Phase.CREATE_FLOOR);
                assertThat(e.streamName()).isEqualTo("too-big");
                assertThat(e.partitions()).isEqualTo(4);
                assertThat(e.maxTotalBytes()).isEqualTo(1024L);
                assertThat(e.details()).containsEntry("phase", "create-floor");
            } finally {
                manager.close();
            }
        }

        @Test
        void reserveThenAllocateFails_releasesReservation() {
            // The "reserve succeeds but the guarded allocate fails" branch lives in the buffer growth
            // path: growOneSegment() reserves via the seam, then allocateGuarded; on failure it calls
            // release(nextSegmentBytes). We assert the reserve/release pairing is conserved end-to-end
            // through the manager's shared pool by driving growth that the SEAM rejects after granting
            // exactly the floor — i.e. every byte the seam hands out is handed back.
            var reservedTotal = new java.util.concurrent.atomic.AtomicLong(0);
            var releasedTotal = new java.util.concurrent.atomic.AtomicLong(0);
            var grantsLeft = new java.util.concurrent.atomic.AtomicLong(1); // grant one growth, then reject
            var buffer = unwrap(OffHeapRingBuffer.offHeapRingBuffer("frag",
                                                                    0,
                                                                    1_000,
                                                                    5L * SEGMENT,
                                                                    EvictionListener.NOOP,
                                                                    EvictionPolicy.DROP_OLDEST,
                                                                    bytes -> {
                                                                        if (grantsLeft.getAndDecrement() > 0) {
                                                                            reservedTotal.addAndGet(bytes);
                                                                            return true;
                                                                        }
                                                                        return false;
                                                                    },
                                                                    releasedTotal::addAndGet));
            try {
                for (var i = 0; i < 50; i++) {
                    buffer.append(new byte[50_000], 1_000L + i)
                          .onFailure(_ -> fail("EVENTUAL append must succeed via eviction"));
                }
                // The one granted segment is held for the buffer's lifetime (released on close), so
                // mid-life released==0; closing returns floor + the granted growth.
                assertThat(reservedTotal.get()).isEqualTo(SEGMENT);
            } finally {
                buffer.close();
            }
            // close() releases floor segment + the single granted growth segment via the seam.
            assertThat(releasedTotal.get()).isEqualTo(2 * SEGMENT);
        }
    }

    @Nested
    class GrowthAccounting {

        @Test
        void append_growsTowardCap_accountsEachSegment() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                var retention = RetentionPolicy.retentionPolicy(1_000, 4L * SEGMENT, 60_000);
                var config = StreamConfig.streamConfig("grow", 1, retention, "latest");
                manager.createStream(config).onFailure(_ -> fail("Expected success"));

                var floor = perPartitionFloor(1_000, 4L * SEGMENT);
                assertThat(manager.totalAllocatedBytes()).isEqualTo(floor);

                // Each ~200 KiB event forces one growth once the floor segment is full.
                manager.publishLocal("grow", 0, new byte[200_000], 1L).onFailure(_ -> fail("append"));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(floor); // still in floor segment

                manager.publishLocal("grow", 0, new byte[200_000], 2L).onFailure(_ -> fail("append"));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(floor + SEGMENT);

                manager.publishLocal("grow", 0, new byte[200_000], 3L).onFailure(_ -> fail("append"));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(floor + 2 * SEGMENT);

                // allocatedBytes telemetry tracks the live high-water (control + data segments).
                manager.streamInfo("grow")
                       .onPresent(si -> assertThat(si.totalBytes()).isEqualTo(manager.totalAllocatedBytes()));
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class ReleaseLifecycle {

        @Test
        void destroyStream_releasesLiveAllocatedBytes_returnsToZero() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                var retention = RetentionPolicy.retentionPolicy(1_000, 4L * SEGMENT, 60_000);
                var config = StreamConfig.streamConfig("temp", 2, retention, "latest");
                manager.createStream(config).onFailure(_ -> fail("Expected success"));

                // Grow both partitions past the floor so destroy must release MORE than the floor.
                for (var i = 0; i < 4; i++) {
                    manager.publishLocal("temp", 0, new byte[200_000], 1_000L + i).onFailure(_ -> fail("append p0"));
                    manager.publishLocal("temp", 1, new byte[200_000], 2_000L + i).onFailure(_ -> fail("append p1"));
                }
                assertThat(manager.totalAllocatedBytes()).isGreaterThan(perPartitionFloor(1_000, 4L * SEGMENT) * 2);

                manager.destroyStream("temp").onFailure(_ -> fail("Expected success"));

                // THE leak detector: create + grow + destroy must return EXACTLY to the pre-create value.
                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);
                assertThat(manager.availableBytes()).isEqualTo(manager.maxTotalBytes());
            } finally {
                manager.close();
            }
        }

        @Test
        void destroyStream_freshFloorOnly_returnsToZero() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                manager.createStream(mgmtDefault("orders")).onFailure(_ -> fail("Expected success"));
                assertThat(manager.totalAllocatedBytes()).isGreaterThan(0L);

                manager.destroyStream("orders").onFailure(_ -> fail("Expected success"));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class Concurrency {

        @Test
        void reserve_isAtomicUnderConcurrentCreate() throws InterruptedException {
            // Sized so exactly K=5 floors fit; N=16 threads each create a distinct stream.
            var maxCount = 1_000L;
            var maxBytes = 4096L; // < SEGMENT, so floor == perPartitionFloor and is small/predictable
            var floor = perPartitionFloor(maxCount, maxBytes); // single partition
            var k = 5;
            var max = floor * k + floor / 2; // room for exactly 5, not 6
            var manager = streamPartitionManager(max);
            var n = 16;
            try {
                var ready = new CountDownLatch(n);
                var go = new CountDownLatch(1);
                var succeeded = new AtomicInteger(0);
                var threads = new ArrayList<Thread>();
                for (var i = 0; i < n; i++) {
                    var name = "s-" + i;
                    var t = new Thread(() -> {
                        ready.countDown();
                        awaitQuietly(go);
                        var retention = RetentionPolicy.retentionPolicy(maxCount, maxBytes, 60_000);
                        manager.createStream(StreamConfig.streamConfig(name, 1, retention, "latest"))
                               .onSuccess(_ -> succeeded.incrementAndGet());
                        // Invariant must hold at EVERY observation: never over-commit the pool.
                        assertThat(manager.totalAllocatedBytes()).isLessThanOrEqualTo(max);
                    });
                    threads.add(t);
                    t.start();
                }
                ready.await();
                go.countDown();
                for (var t : threads) {
                    t.join();
                }

                assertThat(succeeded.get()).isEqualTo(k);
                assertThat(manager.totalAllocatedBytes()).isEqualTo(floor * k);
                assertThat(manager.totalAllocatedBytes()).isLessThanOrEqualTo(max);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class Hydration {

        @Test
        void hydrateEntry_reservesFloor() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                var put = streamConfigPut(mgmtDefault("hydrated"));
                manager.onStreamConfigPut(put);

                assertThat(manager.streamInfo("hydrated").isPresent()).isTrue();
                var floor = perPartitionFloor(10_000, 4L * 1024 * 1024) * 4;
                assertThat(manager.totalAllocatedBytes()).isEqualTo(floor);
            } finally {
                manager.close();
            }
        }

        @Test
        void hydrateEntry_cannotAdmitFloor_stillCreatesEntry() {
            var sink = new RecordingSink();
            // 1 KiB budget — floor can't be admitted, but a follower must not diverge from committed config.
            var manager = streamPartitionManager(1024);
            manager.exhaustionSink(sink);
            try {
                manager.onStreamConfigPut(streamConfigPut(mgmtDefault("committed")));

                // Entry exists despite the floor not fitting (no cluster divergence).
                assertThat(manager.streamInfo("committed").isPresent()).isTrue();
                // The floor is over-subscribed (added past max) to keep reserve/release symmetric, so
                // the pool reflects the floor even though it exceeds maxTotalBytes.
                var floor = perPartitionFloor(10_000, 4L * 1024 * 1024) * 4;
                assertThat(manager.totalAllocatedBytes()).isEqualTo(floor);
                assertThat(manager.totalAllocatedBytes()).isGreaterThan(manager.maxTotalBytes());
                assertThat(sink.events).hasSize(1);
                assertThat(sink.events.getFirst().phase()).isEqualTo(Exhaustion.Phase.CREATE_FLOOR);

                // And destroy returns it cleanly to zero (the leak detector applies on the degraded path too).
                manager.destroyStream("committed").onFailure(_ -> fail("Expected success"));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class PartialConstructionFailure {

        /// Bug #6: a native floor-allocation failure on a LATER partition during multi-partition
        /// construction must (a) close the partitions already built (no Arena leak), (b) release the
        /// reserved floor budget (no budget leak), and (c) surface STREAM_MEMORY_EXCEEDED through the
        /// Result chain (no escaped OutOfMemoryError). Fault-injected via the buffer's floor-alloc seam
        /// so the partition-k failure is deterministic.
        @Test
        void createStream_partialPartitionAllocFailure_releasesBudgetAndClosesBuffers() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            var pre = manager.totalAllocatedBytes();
            // Fail the floor allocation of partition 2 (of 4) — partitions 0,1 build, 2 fails, 3 never opens.
            OffHeapRingBuffer.floorAllocFaultInjector(partition -> partition != 2);
            try {
                manager.createStream(mgmtDefault("partial"))
                       .onSuccess(_ -> fail("Expected STREAM_MEMORY_EXCEEDED from partition-2 floor failure"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));

                // No entry materialized, budget returned to the pre-create value, no leak.
                assertThat(manager.streamInfo("partial").isPresent()).isFalse();
                assertThat(manager.totalAllocatedBytes()).isEqualTo(pre);
                assertThat(manager.availableBytes()).isEqualTo(manager.maxTotalBytes());
            } finally {
                OffHeapRingBuffer.clearFloorAllocFaultInjector();
                manager.close();
            }
        }

        /// Bug #6 (first partition fails): partition 0's floor allocation fails — nothing was built — and
        /// the reserved floor budget is still released with STREAM_MEMORY_EXCEEDED returned.
        @Test
        void createStream_firstPartitionAllocFailure_releasesBudget() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            var pre = manager.totalAllocatedBytes();
            OffHeapRingBuffer.floorAllocFaultInjector(partition -> partition != 0);
            try {
                manager.createStream(mgmtDefault("first-fail"))
                       .onSuccess(_ -> fail("Expected STREAM_MEMORY_EXCEEDED"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));

                assertThat(manager.streamInfo("first-fail").isPresent()).isFalse();
                assertThat(manager.totalAllocatedBytes()).isEqualTo(pre);
            } finally {
                OffHeapRingBuffer.clearFloorAllocFaultInjector();
                manager.close();
            }
        }
    }

    // ---- helpers ----

    /// Unwrap the guarded seam-factory `Result<OffHeapRingBuffer>` (bug #6) to a raw buffer; a
    /// (never-expected, tiny-allocation) floor failure fails the test loudly.
    private static OffHeapRingBuffer unwrap(org.pragmatica.lang.Result<OffHeapRingBuffer> result) {
        var holder = new java.util.concurrent.atomic.AtomicReference<OffHeapRingBuffer>();
        result.onFailure(cause -> fail("buffer construction failed: " + cause.message()))
              .onSuccess(holder::set);
        return holder.get();
    }

    private static org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<
            org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey,
            org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue> streamConfigPut(StreamConfig config) {
        var key = org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey.streamConfigKey(config.name());
        var value = org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue.streamConfigValue(config);
        var put = new org.pragmatica.cluster.state.kvstore.KVCommand.Put<>(key, value);
        return new org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<>(put, org.pragmatica.lang.Option.none());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    private static final class RecordingSink implements java.util.function.Consumer<Exhaustion> {
        private final List<Exhaustion> events = new CopyOnWriteArrayList<>();

        @Override public void accept(Exhaustion e) {
            events.add(e);
        }
    }
}
