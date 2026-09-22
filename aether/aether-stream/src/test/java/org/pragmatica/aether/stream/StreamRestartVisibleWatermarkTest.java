// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import java.nio.file.Path;
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

/// #1387: `visible = min(durable, acknowledged)` must survive a restart. WAL replay used to make every
/// replayed record visible at once — `seedHead` set `visibleOffset` to the sealed floor and `placeRecord`
/// used the plain `append`, which has no durability gate — so a restart promoted "durable on the owner,
/// unacknowledged by min-sync" to "visible". Readers then saw records whose publishers were told the
/// outcome is unknown and which no replica is known to hold.
///
/// The scenario (rev1373's P5, the pin the ticket names): min-sync 2, one peer, ring capacity 2, offsets
/// 0..4 published, 0..2 evicted and sealed, the peer acknowledged through 2. `fetch(0)` is `[0,1,2]` before
/// the restart and must stay `[0,1,2]` after it, until the peer re-acknowledges.
///
/// **RF=1 is blind to all of this and cannot serve as a control.** `DefaultReplicationManager.replicatedThrough`
/// answers `Long.MAX_VALUE` for `minAcks <= 0`, and `minSyncReplicas <= 1` asks for exactly that, so visible
/// and durable coincide and nothing a restart does to visibility is observable. The min-sync-1 case below is
/// present as the opposite guard — that the fix does not HIDE a tail no peer was ever required to hold.
class StreamRestartVisibleWatermarkTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final long RING_CAPACITY = 2;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final int PUBLISHED = 5;
    private static final long SEALED_THROUGH = 2;

    @TempDir
    Path walDir;

    private MetadataStore metadataStore;
    private StorageInstance storage;
    private SegmentIndex index;
    private ReplicationManager replication;
    private StreamPartitionManager manager;
    private PartitionedStreamAccess<byte[]> access;

    @BeforeEach
    void setUp() {
        metadataStore = MetadataStore.inMemoryMetadataStore("restart-visible");
        storage = StorageInstance.storageInstance("restart-visible", List.of(MemoryTier.memoryTier(ONE_GB)), metadataStore);
    }

    @AfterEach
    void tearDown() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
        Option.option(storage).onPresent(StorageInstance::shutdown);
    }

    /// The defect itself. Before the restart offsets 3 and 4 are durable on the owner and unacknowledged, so
    /// they are invisible; the restart must not change that verdict. A failure here reads
    /// `expected [0,1,2] but was [0,1,2,3,4]` — the replayed tail served to a reader.
    @Test
    void restart_keepsUnacknowledgedOffsetsInvisible_whenThePeerHasNotReAcked() {
        startOwner(2);
        publish(PUBLISHED);
        awaitSealedThrough(SEALED_THROUGH);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, SEALED_THROUGH));

        assertThat(visible()).as("min(durable 4, acknowledged 2)").isEqualTo(SEALED_THROUGH);
        assertThat(fetch(0)).as("honest before the restart").containsExactly(0L, 1L, 2L);

        restartOwner(2);

        assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("the sealed floor was rebuilt from the refs")
                                                             .isEqualTo(SEALED_THROUGH);
        assertThat(durable()).as("the whole WAL tail is durable again").isEqualTo(PUBLISHED - 1L);
        assertThat(visible()).as("no acknowledgement survived the restart, so visibility stops at the sealed floor")
                             .isEqualTo(SEALED_THROUGH);
        assertThat(fetch(0)).as("a replayed but unacknowledged offset is not served to a reader")
                            .containsExactly(0L, 1L, 2L);
    }

    /// Positive control for the test above: the reader's `[0,1,2]` is the acknowledgement state talking, not a
    /// recovery that lost the tail. The same peer acknowledging 4 after the restart releases 3 and 4.
    @Test
    void restart_thenPeerReAck_makesTheReplayedTailVisible() {
        startOwner(2);
        publish(PUBLISHED);
        awaitSealedThrough(SEALED_THROUGH);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, SEALED_THROUGH));
        restartOwner(2);

        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, PUBLISHED - 1L));

        assertThat(visible()).as("the re-acknowledgement advances the watermark").isEqualTo(PUBLISHED - 1L);
        assertThat(fetch(0)).as("the replayed tail is intact and served once it is acknowledged")
                            .containsExactly(0L, 1L, 2L, 3L, 4L);
    }

    /// The over-hiding guard, NOT a control for the defect (see the class note: min-sync 1 cannot observe it).
    /// A partition that requires no peer acknowledgement must find its whole replayed tail visible the moment
    /// recovery finishes — before any publish or ack — because `durable` alone is the watermark there.
    @Test
    void restart_makesTheReplayedTailVisibleAtOnce_whenNoPeerAckIsRequired() {
        startOwner(1);
        publish(PUBLISHED);
        awaitSealedThrough(SEALED_THROUGH);

        assertThat(visible()).as("min-sync 1: visible tracks durable").isEqualTo(PUBLISHED - 1L);

        restartOwner(1);

        assertThat(visible()).as("nothing to wait for, so nothing is withheld").isEqualTo(PUBLISHED - 1L);
        assertThat(fetch(0)).containsExactly(0L, 1L, 2L, 3L, 4L);
    }

    // ---- fixture -------------------------------------------------------------------------------------------

    /// A fresh owner over the SAME WAL directory and the SAME storage, with a fresh segment index rebuilt from
    /// the surviving refs and a fresh replica registry holding no acknowledgements — a restart, as recovery
    /// sees one.
    private void restartOwner(int minSyncReplicas) {
        manager.close();
        index = new SegmentIndex();
        index.rebuildFromRefs(metadataStore);
        startOwnerOn(index, minSyncReplicas);
    }

    private void startOwner(int minSyncReplicas) {
        index = new SegmentIndex();
        startOwnerOn(index, minSyncReplicas);
    }

    private void startOwnerOn(SegmentIndex segmentIndex, int minSyncReplicas) {
        var sealer = segmentSealer(storageSegmentSink(storage, segmentIndex));

        replication = replicationWithPeer();
        manager = streamPartitionManager(Long.MAX_VALUE,
                                         sealer,
                                         replication,
                                         Option.some(walDir),
                                         segmentIndex::lastSealedOffset);
        manager.createStream(config(minSyncReplicas)).onFailure(cause -> fail(cause.message()));
        access = streamAccess(manager,
                              identitySerializer(),
                              identityDeserializer(),
                              STREAM,
                              1,
                              Option.<Function<byte[], Object>>none(),
                              noopCheckpointWriter(),
                              tieredStreamReader(segmentIndex, storage));
    }

    private static ReplicationManager replicationWithPeer() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);

        return replicationManager(SELF, registry);
    }

    private static StreamConfig config(int minSyncReplicas) {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(RING_CAPACITY, 1_048_576L, 60_000L),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         2,
                                         minSyncReplicas,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static PartitionedStreamAccess.CursorCheckpointWriter noopCheckpointWriter() {
        return (_, _, _, _) -> Promise.unitPromise();
    }

    private List<Long> fetch(long fromOffset) {
        return access.fetch(PARTITION, fromOffset, 10)
                     .await()
                     .onFailure(cause -> fail("fetch(" + fromOffset + ") failed: " + cause.message()))
                     .or(List.<StreamEvent<byte[]>>of())
                     .stream()
                     .map(StreamEvent::offset)
                     .toList();
    }

    private long visible() {
        return manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::visibleOffset).or(Long.MIN_VALUE);
    }

    private long durable() {
        return manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::durableOffset).or(Long.MIN_VALUE);
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
        assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("sealed through %d", offset).isEqualTo(offset);
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
