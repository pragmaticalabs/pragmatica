// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1352 at the partition manager: min-sync 2, a peer that never acknowledges, a ring of capacity 2 (retention
/// `maxCount`), three owner publishes. The third publish evicts offset 0, which nobody acknowledged. Before the
/// fix it was handed to the eviction listener — in production the segment sealer — so an event no consumer was
/// allowed to see reached the durable tier (`PROBE visible=-1 tail=1 head=2 sealed=[0]`), and its publisher's
/// await waited out the 5 s replication timeout for an outcome that was already known.
class StreamPartitionManagerUnacknowledgedEvictionTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final long RING_CAPACITY = 2;

    private StreamPartitionManager manager;

    @AfterEach
    void closeManager() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
    }

    @Nested
    class SealHandOff {
        private final List<Long> sealed = new CopyOnWriteArrayList<>();
        private final EvictionListener recordingSealer = (_, _, events) -> {
            events.forEach(event -> sealed.add(event.offset()));

            return Result.unitResult();
        };

        /// The ticket's probe, inverted.
        @Test
        void evicteeNobodyAcknowledged_isDroppedAndCounted_neverSealed() {
            manager = streamPartitionManager(Long.MAX_VALUE, recordingSealer, replicationWithPeer(replicaRegistry()));
            createStream(manager);

            publish(manager, 3);

            var ring = manager.partitionBuffer(STREAM, PARTITION).unwrap();

            assertThat(ring.visibleOffset()).as("nothing was ever acknowledged").isEqualTo(-1L);
            assertThat(ring.tailOffset()).as("offset 0 was reclaimed").isEqualTo(1L);
            assertThat(sealed).as("offsets handed to the sealer").isEmpty();
            assertThat(manager.unacknowledgedEvictionsSinceBoot()).as("the drop is counted").isEqualTo(1L);
        }

        /// The happy path is unchanged: acknowledged evictees are sealed and not counted as drops.
        @Test
        void acknowledgedEvictee_isSealed_notCounted() {
            var replication = replicationWithPeer(replicaRegistry());
            manager = streamPartitionManager(Long.MAX_VALUE, recordingSealer, replication);
            createStream(manager);

            publish(manager, 2);
            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 1));
            publish(manager, 1);

            assertThat(sealed).containsExactly(0L);
            assertThat(manager.unacknowledgedEvictionsSinceBoot()).isZero();
        }
    }

    @Nested
    class PendingAwait {

        /// The publisher's await for the dropped offset fails at once with a cause that names the drop — not
        /// `REPLICATION_TIMEOUT` (outcome-unknown) 5 s later. The 2 s bound is the discriminator: a timeout of
        /// the await itself would surface as `CoreError.Timeout`, the replication timeout as its own cause.
        /// The continuation is foreign code: it must not run on the thread whose publish evicted the offset,
        /// which holds the partition's append lock at that moment. `mapError` is the shape every writer
        /// continues the barrier with, and a `Promise` runs it on the RESOLVING thread (`onFailure` would be
        /// dispatched asynchronously regardless and pin nothing).
        @Test
        void awaitForTheDroppedOffset_failsPromptly_namingTheDrop_offThePublishingThread() {
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replicationWithPeer(replicaRegistry()));
            createStream(manager);
            var offset = publish(manager, 1);
            var continuationThread = new AtomicReference<Thread>();
            var pending = manager.awaitReplication(STREAM, PARTITION, offset, 1)
                                 .mapError(cause -> {
                                     continuationThread.set(Thread.currentThread());
                                     return cause;
                                 });

            assertThat(pending.isResolved()).as("the peer has not acked, so the await is pending").isFalse();
            publish(manager, 2);

            var outcome = pending.await(timeSpan(2).seconds());

            assertThat(outcome.isFailure()).as("the await failed").isTrue();
            assertThat(causeOf(outcome)).as("the cause names the dropped offset, not a timeout")
                                        .isEqualTo(new StreamError.UnacknowledgedEvicted(STREAM, PARTITION, 0L));
            assertThat(awaitSet(continuationThread)).as("the continuation ran, and not on the publishing thread")
                                                    .isNotSameAs(Thread.currentThread());
        }

        /// The resolving thread runs the callbacks; `await` may return before they finish on another thread.
        private static Thread awaitSet(AtomicReference<Thread> thread) {
            var deadline = System.nanoTime() + 2_000_000_000L;

            while (thread.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(thread.get()).as("the continuation ran").isNotNull();
            return thread.get();
        }

        /// Awaits for offsets still in the ring are untouched by the drop and resolve on the ack as before.
        @Test
        void awaitsForOffsetsStillInTheRing_surviveTheDrop_andResolveOnAck() {
            var replication = replicationWithPeer(replicaRegistry());
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager);
            publish(manager, 1);
            var second = publish(manager, 1);
            var pendingSecond = manager.awaitReplication(STREAM, PARTITION, second, 1);

            publish(manager, 1);

            assertThat(pendingSecond.isResolved()).as("offset 1 is still in the ring; its await is not failed").isFalse();
            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 2));
            assertThat(pendingSecond.await(timeSpan(2).seconds()).isSuccess()).as("the peer ack resolves it").isTrue();
        }
    }

    /// The end-to-end exposure the finding could not induce with one peer: a replica-set change. A NEW peer
    /// catches up from the owner's ring, whose tail is already 1, so it acknowledges from 1 — and the visible
    /// position advances past an offset 0 that nobody ever acknowledged. A consumer read from 0 then misses
    /// the ring (`CursorExpired`) and falls through to the durable tier. Before the fix the tier served offset 0.
    @Nested
    class TieredReadAfterReplicaSetChange {
        private static final NodeId NEW_PEER = NodeId.randomNodeId();
        private static final long ONE_GB = 1024 * 1024 * 1024L;

        private StorageInstance storage;

        @AfterEach
        void shutdownStorage() {
            Option.option(storage).onPresent(StorageInstance::shutdown);
        }

        @Test
        void readFromTheDroppedOffset_isRefusedAsExpired_neverServedFromTheTier() {
            storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
            var index = new SegmentIndex();
            var sealer = segmentSealer(storageSegmentSink(storage, index));
            ReplicaRegistry registry = replicaRegistry();
            var replication = replicationWithPeer(registry);
            manager = streamPartitionManager(Long.MAX_VALUE, sealer, replication);
            createStream(manager);
            var access = access(manager, index);

            publish(manager, 3);
            registry.registerReplica(STREAM, PARTITION, NEW_PEER);
            replication.handleAck(replicateAck(NEW_PEER, STREAM, PARTITION, 1));
            awaitSealerIdle(sealer);

            var ring = manager.partitionBuffer(STREAM, PARTITION).unwrap();

            assertThat(ring.visibleOffset()).as("the new peer's ack made offset 1 visible").isEqualTo(1L);
            assertThat(ring.tailOffset()).as("offset 0 left the ring").isEqualTo(1L);

            var read = access.fetch(PARTITION, 0, 5).await();

            assertThat(read.isFailure()).as("offset 0 is in neither tier; the read must not be served: %s",
                                            read.map(events -> events.stream().map(StreamEvent::offset).toList()))
                                        .isTrue();
            assertThat(causeOf(read)).isEqualTo(new StreamError.CursorExpired(0, 1));
            assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("nothing reached the durable tier").isEqualTo(-1L);

            var fromOne = access.fetch(PARTITION, 1, 5).await().or(List.of());

            assertThat(fromOne.stream().map(StreamEvent::offset)).as("the visible event is served from the ring")
                                                                  .containsExactly(1L);
        }

        /// The sealer takes hand-overs synchronously and seals off-thread; a read that must not find anything in
        /// the tier is meaningful only once the sealer holds nothing pending.
        private static void awaitSealerIdle(SegmentSealer sealer) {
            var deadline = System.nanoTime() + 5_000_000_000L;

            while (sealer.pendingBytes() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(sealer.pendingBytes()).as("the sealer holds nothing pending").isZero();
        }

        private PartitionedStreamAccess<byte[]> access(StreamPartitionManager manager, SegmentIndex index) {
            PartitionedStreamAccess.CursorCheckpointWriter noopWriter = (_, _, _, _) -> Promise.unitPromise();

            return streamAccess(manager,
                                identitySerializer(),
                                identityDeserializer(),
                                STREAM,
                                1,
                                Option.<Function<byte[], Object>>none(),
                                noopWriter,
                                tieredStreamReader(index, storage));
        }
    }

    private static ReplicationManager replicationWithPeer(ReplicaRegistry registry) {
        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);

        return replicationManager(SELF, registry);
    }

    /// Ring capacity [#RING_CAPACITY] via retention `maxCount`; replicas 2, min-sync 2 — the owner plus one peer
    /// must acknowledge before an event is visible.
    private static void createStream(StreamPartitionManager manager) {
        var config = StreamConfig.streamConfig(STREAM,
                                               1,
                                               RetentionPolicy.retentionPolicy(RING_CAPACITY, 1_048_576L, 60_000L),
                                               "earliest",
                                               1_048_576L,
                                               ConsistencyMode.EVENTUAL,
                                               2,
                                               2,
                                               StreamCompression.NONE,
                                               Option.none());

        manager.createStream(config).onFailure(cause -> fail(cause.message()));
    }

    /// Publishes `count` events and returns the LAST offset assigned.
    private static long publish(StreamPartitionManager manager, int count) {
        var last = -1L;

        for (var i = 0; i < count; i++) {
            last = manager.publishLocal(STREAM, PARTITION, "e".getBytes(UTF_8), 1L)
                          .onFailure(cause -> fail("publish failed: " + cause.message()))
                          .or(-1L);
        }

        return last;
    }

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> fail("expected a failure, got " + value));
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
