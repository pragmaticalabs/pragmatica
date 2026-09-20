// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.PartitionedStreamAccess.streamAccess;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// Tests the read-through fallback from ring buffer to sealed segments.
/// When events are evicted from the ring buffer and sealed to storage,
/// PartitionedStreamAccess should transparently read them from SegmentReader.
class SegmentFallbackTest {

    private static final String STREAM = "fallback-test";
    private static final int PARTITION = 0;
    private static final int PARTITION_COUNT = 1;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    /// Small ring buffer capacity to force eviction quickly.
    private static final int RING_CAPACITY = 5;
    private static final int RING_DATA_BYTES = 1024;
    /// Ring control layout (header then 24-byte index entries); mirrors `OffHeapRingBuffer`'s private constants.
    private static final long HEADER_CAPACITY = 40;
    private static final long INDEX_START = 64;
    private static final long INDEX_ENTRY_SIZE = 24;

    private StorageInstance storage;
    private SegmentIndex index;
    private StorageSegmentSink sink;
    private TieredStreamReader tieredReader;
    private SegmentSealer sealer;
    private StreamPartitionManager partitionManager;
    private PartitionedStreamAccess<byte[]> access;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
        sink = storageSegmentSink(storage, index);
        tieredReader = tieredStreamReader(index, storage);
        sealer = segmentSealer(sink);

        // Wire SegmentSealer as the eviction listener so evicted events get sealed
        partitionManager = streamPartitionManager(Long.MAX_VALUE, sealer);
        var retention = RetentionPolicy.retentionPolicy(RING_CAPACITY, RING_DATA_BYTES, 600_000);
        partitionManager.createStream(StreamConfig.streamConfig(STREAM, PARTITION_COUNT, retention, "earliest"));

        PartitionedStreamAccess.CursorCheckpointWriter noopWriter = (_, _, _, _) -> org.pragmatica.lang.Promise.unitPromise();
        access = streamAccess(partitionManager, identitySerializer(), identityDeserializer(),
                              STREAM, PARTITION_COUNT, Option.<Function<byte[], Object>>none(),
                              noopWriter, tieredReader);
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
        storage.shutdown();
    }

    @Nested
    class RecentEvents {

        @Test
        void fetch_recentEvents_readsFromRingBuffer() {
            publishEvents(3);

            var result = access.fetch(PARTITION, 0, 10).await();
            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(3);
                      assertThat(events.get(0).offset()).isEqualTo(0L);
                      assertThat(events.get(1).offset()).isEqualTo(1L);
                      assertThat(events.get(2).offset()).isEqualTo(2L);
                  });
        }
    }

    @Nested
    class EvictedEvents {

        @Test
        void fetch_evictedEvents_readsFromSealedSegment() {
            // Publish enough events to force eviction (capacity = 5, publish 10)
            publishEvents(10);
            awaitSealedThrough(RING_CAPACITY - 1);

            // Offset 0 should have been evicted from the ring buffer and sealed to storage
            var result = access.fetch(PARTITION, 0, 5).await();
            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).isNotEmpty();
                      assertThat(events.getFirst().offset()).isEqualTo(0L);
                  });
        }
    }

    @Nested
    class MixedRange {

        @Test
        void fetch_mixedRange_combinesBothSources() {
            // Publish enough to evict early events, keep recent ones in ring buffer
            publishEvents(10);
            awaitSealedThrough(RING_CAPACITY - 1);

            // Request from offset 0 -- should combine sealed + ring buffer
            var result = access.fetch(PARTITION, 0, 20).await();
            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSizeGreaterThanOrEqualTo(5);
                      // Events should be in offset order
                      for (int i = 1; i < events.size(); i++) {
                          assertThat(events.get(i).offset())
                              .isGreaterThan(events.get(i - 1).offset());
                      }
                  });
        }
    }

    /// #1234: an offset the ring has already dropped and the cold tier never received must surface as an
    /// explicit error. Answering `[]` for it stalls the consumer at that cursor forever.
    @Nested
    class UnsealedHole {

        @Test
        void fetch_offsetInNeitherTier_failsExplicitly_insteadOfEmpty() {
            var unsealedManager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP);
            var retention = RetentionPolicy.retentionPolicy(RING_CAPACITY, RING_DATA_BYTES, 600_000);

            unsealedManager.createStream(StreamConfig.streamConfig(STREAM, PARTITION_COUNT, retention, "earliest"));

            PartitionedStreamAccess.CursorCheckpointWriter noopWriter = (_, _, _, _) -> org.pragmatica.lang.Promise.unitPromise();
            var unsealedAccess = streamAccess(unsealedManager, identitySerializer(), identityDeserializer(),
                                              STREAM, PARTITION_COUNT, Option.<Function<byte[], Object>>none(),
                                              noopWriter, tieredReader);

            for (int i = 0; i < 10; i++) {
                unsealedManager.publishLocal(STREAM, PARTITION, ("event-" + i).getBytes(), 1000L + i);
            }

            var result = unsealedAccess.fetch(PARTITION, 0, 3).await();

            unsealedManager.close();
            result.onSuccess(events -> org.junit.jupiter.api.Assertions.fail("Expected an explicit error, got " + events.size() + " events"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.CursorExpired.class));
        }

        /// #1234, ruling B: the ring reclaimed the offset and its seal has not landed — it is IN FLIGHT, not
        /// lost. The read fails with a TRANSIENT `SealInFlight` so the caller backs off and re-reads the same
        /// offset; it must not look like an expired cursor (which invites skipping) or an empty read (a stall).
        @Test
        void fetch_offsetWhoseSealIsPending_failsInFlightAndTransient() {
            var pendingSeals = new CopyOnWriteArrayList<Promise<Unit>>();
            var inFlightManager = streamPartitionManager(Long.MAX_VALUE, segmentSealer(_ -> pendingSeal(pendingSeals)));
            var retention = RetentionPolicy.retentionPolicy(RING_CAPACITY, RING_DATA_BYTES, 600_000);

            inFlightManager.createStream(StreamConfig.streamConfig(STREAM, PARTITION_COUNT, retention, "earliest"));

            PartitionedStreamAccess.CursorCheckpointWriter noopWriter = (_, _, _, _) -> Promise.unitPromise();
            var inFlightAccess = streamAccess(inFlightManager, identitySerializer(), identityDeserializer(),
                                              STREAM, PARTITION_COUNT, Option.<Function<byte[], Object>>none(),
                                              noopWriter, tieredReader);

            for (int i = 0; i < 10; i++) {
                inFlightManager.publishLocal(STREAM, PARTITION, ("event-" + i).getBytes(), 1000L + i);
            }

            var result = inFlightAccess.fetch(PARTITION, 0, 3).await();

            inFlightManager.close();
            result.onSuccess(events -> org.junit.jupiter.api.Assertions.fail("Expected SealInFlight, got " + events.size() + " events"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(SegmentError.SealInFlight.class))
                  .onFailure(cause -> assertThat(cause.isTransient()).isTrue());
        }

        private static Promise<Unit> pendingSeal(List<Promise<Unit>> pendingSeals) {
            var seal = Promise.<Unit>promise();

            pendingSeals.add(seal);

            return seal;
        }
    }

    /// Sealing runs off the appending thread (#1234), so an evicted offset is readable from storage only once
    /// its seal has landed; until then a read of it is IN FLIGHT.
    private void awaitSealedThrough(long offset) {
        var deadline = System.currentTimeMillis() + 10_000;

        while (index.lastSealedOffset(STREAM, PARTITION) < offset && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(10_000_000);
        }

        assertThat(index.lastSealedOffset(STREAM, PARTITION)).isGreaterThanOrEqualTo(offset);
    }

    /// #1247 review M2: after the CursorExpired segment fallback, the ring read for the tail used to be
    /// `.or(List.of())`, so a corrupted ring silently truncated the read to the sealed events. The distinct
    /// cause must reach the caller instead.
    @Nested
    class CorruptedRingAfterFallback {

        @Test
        void fetch_mixedRange_corruptedRing_failsWithRingIndexCorrupted_notTruncated() {
            publishEvents(10);
            corruptEveryIndexSlot(partitionManager.partitionBuffer(STREAM, PARTITION)
                                                  .fold(() -> org.junit.jupiter.api.Assertions.fail("no ring"),
                                                        ring -> ring));

            access.fetch(PARTITION, 0, 20)
                  .await()
                  .onSuccess(events -> org.junit.jupiter.api.Assertions.fail("expected RingIndexCorrupted, got "
                                                                             + events.size() + " events"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.RingIndexCorrupted.class));
        }

        private static void corruptEveryIndexSlot(OffHeapRingBuffer ring) {
            var control = controlSegment(ring);
            var capacity = control.get(ValueLayout.JAVA_LONG, HEADER_CAPACITY);

            assertThat(capacity).as("PRECONDITION: ring capacity read from the header").isPositive();

            for (long slot = 0; slot < capacity; slot++) {
                control.set(ValueLayout.JAVA_LONG, INDEX_START + slot * INDEX_ENTRY_SIZE, -1_000L);
            }
        }

        private static MemorySegment controlSegment(OffHeapRingBuffer ring) {
            try {
                var field = OffHeapRingBuffer.class.getDeclaredField("controlSegment");

                field.setAccessible(true);

                return (MemorySegment) field.get(ring);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("controlSegment field not reachable", e);
            }
        }
    }

    private void publishEvents(int count) {
        for (int i = 0; i < count; i++) {
            var payload = ("event-" + i).getBytes();
            partitionManager.publishLocal(STREAM, PARTITION, payload, 1000L + i);
        }
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> byte[] encode(T object) {
                return (byte[]) object;
            }

            @Override
            public <T> void write(ByteBuf byteBuf, T object) {
                byteBuf.writeBytes((byte[]) object);
            }
        };
    }

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T decode(byte[] bytes) {
                return (T) bytes;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T read(ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(bytes);
                return (T) bytes;
            }
        };
    }
}
