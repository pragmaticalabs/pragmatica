// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.PartitionedStreamAccess.streamAccess;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// #1352: DROP_OLDEST seals EVERY evictee, acknowledged or not — the WAL and the replicas hold it, and its
/// publisher was told at most that the outcome is unknown, so it may be in the log. The durable tier can
/// therefore hold offsets above the partition's VISIBLE position, and the cold read a consumer falls through to
/// (`PartitionedStreamAccess.readWithSegmentFallback`) must be bounded by visible exactly as the ring read is.
/// Before the fix the ring refused an offset above visible while the tier served the same offset.
///
/// The N3 shape: min-sync 2, one peer that never acknowledges, ring capacity 2 (retention `maxCount`), three
/// owner publishes. Offset 0 is evicted and sealed with nothing visible.
class TieredReadVisibleBoundTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final long RING_CAPACITY = 2;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private StorageInstance storage;
    private SegmentIndex index;
    private ReplicationManager replication;
    private StreamPartitionManager manager;
    private PartitionedStreamAccess<byte[]> access;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        replication = replicationManager(SELF, registry);
        manager = streamPartitionManager(Long.MAX_VALUE, segmentSealer(storageSegmentSink(storage, index)), replication);
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(RING_CAPACITY, 1_048_576L, 60_000L),
                                                       "earliest",
                                                       1_048_576L,
                                                       ConsistencyMode.EVENTUAL,
                                                       2,
                                                       2,
                                                       StreamCompression.NONE,
                                                       Option.none()))
               .onFailure(cause -> fail(cause.message()));
        PartitionedStreamAccess.CursorCheckpointWriter noopWriter = (_, _, _, _) -> Promise.unitPromise();
        access = streamAccess(manager,
                              identitySerializer(),
                              identityDeserializer(),
                              STREAM,
                              1,
                              Option.<Function<byte[], Object>>none(),
                              noopWriter,
                              tieredStreamReader(index, storage));
    }

    @AfterEach
    void tearDown() {
        manager.close();
        storage.shutdown();
    }

    /// The N3 scenario itself: the evictee nobody acknowledged IS sealed, and a read of it while nothing is
    /// visible returns the ring's not-yet-visible shape — `[]` — not the event.
    @Test
    void unacknowledgedEvictee_isSealed_andReadsAsNotYetVisible() {
        publish(3);
        awaitSealedThrough(0);

        assertThat(visible()).as("nothing was ever acknowledged").isEqualTo(-1L);
        assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("offset 0 reached the durable tier").isEqualTo(0L);
        assertThat(fetch(0)).as("the not-yet-visible shape, as the ring gives it").isEmpty();
    }

    /// The exposure: the tier holds an offset ABOVE visible (1, sealed after the peer acknowledged only 0) and
    /// the ring holds only offsets above it. A read from 1 misses the ring (`CursorExpired`) and falls through
    /// to the tier — which must answer as the ring answers for an in-ring offset above visible: `[]`.
    @Test
    void tierRead_neverServesAnOffsetAboveVisible() {
        publish(3);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0));
        publish(1);
        awaitSealedThrough(1);

        assertThat(visible()).isEqualTo(0L);
        assertThat(ringRead(2)).as("the ring's shape for an in-ring offset above visible").isEmpty();
        assertThat(fetch(1)).as("the tier's shape for a sealed offset above visible mirrors it").isEmpty();
        assertThat(fetch(0)).as("a read from below visible stops AT visible, not at what the tier holds").containsExactly(0L);
    }

    /// Positive control: once visible advances, the same sealed offset is served from the tier, followed by the
    /// ring's visible tail.
    @Test
    void tierRead_servesTheSealedOffset_onceItIsVisible() {
        publish(3);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0));
        publish(1);
        awaitSealedThrough(1);

        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 3));

        assertThat(visible()).isEqualTo(3L);
        assertThat(fetch(1)).containsExactly(1L, 2L, 3L);
        assertThat(fetch(0)).containsExactly(0L, 1L, 2L, 3L);
    }

    private List<Long> fetch(long fromOffset) {
        return access.fetch(PARTITION, fromOffset, 5)
                     .await()
                     .onFailure(cause -> fail("fetch(" + fromOffset + ") failed: " + cause.message()))
                     .or(List.of())
                     .stream()
                     .map(StreamEvent::offset)
                     .toList();
    }

    private List<OffHeapRingBuffer.RawEvent> ringRead(long fromOffset) {
        return manager.readLocal(STREAM, PARTITION, fromOffset, 5)
                      .onFailure(cause -> fail("readLocal(" + fromOffset + ") failed: " + cause.message()))
                      .or(List.of());
    }

    private long visible() {
        return manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::visibleOffset).or(Long.MIN_VALUE);
    }

    private void publish(int count) {
        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, "e".getBytes(UTF_8), 1L).onFailure(cause -> fail("publish failed: " + cause.message()));
        }
    }

    /// Sealing runs off the appending thread (#1234); the tier holds the offset only once its seal is indexed.
    private void awaitSealedThrough(long offset) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (index.lastSealedOffset(STREAM, PARTITION) < offset && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("sealed through %d", offset).isGreaterThanOrEqualTo(offset);
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
