// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.LastSealedOffsetSource;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams audit A-11 and A-12 (#2201), on the real ring, the real checkpoint block store and the real KV pointer.
///
/// A-11: a checkpoint claims `min(applied, committed)` but its snapshot is the WHOLE fold, so it holds every applied record, committed or not.
/// The "replay is idempotent" argument covers records that survive; it cannot undo a record a failover cuts. After the cut, the rebuild seeds
/// from the checkpoint (its claim is still within the log, over an untouched lineage) and keeps the ghost key.
///
/// A-12: a checkpoint block is written with `StorageInstance#put` (refCount 1) and named only by a blind KV pointer put, so superseding the
/// pointer never debits the previous block; `release` is called from nowhere and the collector takes only refCount 0.
///
/// Each finding holds a TRIPWIRE (enabled, GREEN, asserts today's behaviour) and the REAL assertion beside it (`@Disabled`, RED today).
class EntityCheckpointTripwireTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024L * 1024 * 1024;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 2L, 1L);

    @TempDir
    java.nio.file.Path storageDir;

    private StreamPartitionManager manager;
    private StorageInstance storage;
    private EntityLogSubstrate substrate;
    private KVStore<AetherKey, AetherValue> kv;
    private String arc;
    private MetadataStore metadata;
    private volatile long sealedFloor = -1L;
    private static final String ENABLE_11 = "A-11 tripwire: a checkpoint no longer carries state above its claim; delete this tripwire and enable the real assertion beside it (#2201)";
    private static final String ENABLE_12 = "A-12 tripwire: a superseded checkpoint block is now released; delete this tripwire and enable the real assertion beside it (#2201)";

    @BeforeEach
    void setUp() {
        start(Option.some(storageDir.resolve("wal")));
    }

    /// `walBaseDir` none is a copy that keeps no log, and so no owner-epoch history.
    private void start(Option<java.nio.file.Path> walBaseDir) {
        arc = EntityPartitionArc.arcName(KEYSPACE);
        metadata = MetadataStore.inMemoryMetadataStore("entity-ckpt");
        manager = streamPartitionManager(64L * 1024 * 1024,
                                         EvictionListener.NOOP,
                                         org.pragmatica.aether.stream.replication.ReplicationManager.NONE,
                                         walBaseDir,
                                         (_, _) -> sealedFloor);
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        storage = StorageInstance.storageInstance("entity-ckpt",
                                                  List.of(MemoryTier.memoryTier(ONE_GB), LocalDiskTier.localDiskTier(storageDir.resolve("blocks"), ONE_GB).unwrap()),
                                                  metadata);
        kv = new KVStore<>(MessageRouter.mutable(), noopSerializer(), noopDeserializer());
        substrate = streamEntityLogSubstrate(manager,
                                             (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                             null,
                                             new SegmentIndex(),
                                             EvictionListener.NOOP,
                                             storage,
                                             kv,
                                             commands -> {
                                                 kv.process(kv.createBatch(commands));

                                                 return Promise.success(List.of());
                                             });
        substrate.ensureLog(KEYSPACE, 1, new ReplicationFactors(1, 1)).unwrap();
    }

    @AfterEach
    void tearDown() {
        manager.close();
        storage.shutdown();
    }

    /// k, j committed; z appended and applied but NOT committed. The checkpoint claims 1 and snapshots k, j AND z.
    @Test
    void checkpointOverUncommittedRecord_keepsTheCutRecord_afterTheRebuild_tripwire() {
        var fold = cutAfterCheckpointOverUncommittedZ();

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();
        assertThat(fold.get(PARTITION, "j").isPresent()).as("control: j is committed and survived").isTrue();
        assertThat(fold.get(PARTITION, "z").isPresent()).as(ENABLE_11).isTrue();
    }

    @Disabled("A-11 real assertion, RED today: after the cut the rebuilt fold must hold nothing from the removed record (#2201)")
    @Test
    void checkpointOverUncommittedRecord_holdsNoCutRecord_afterTheRebuild() {
        var fold = cutAfterCheckpointOverUncommittedZ();

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();
        assertThat(fold.get(PARTITION, "z").isPresent()).as("z was cut: the rebuilt fold must not hold it").isFalse();
    }

    private EntityFold cutAfterCheckpointOverUncommittedZ() {
        landAcknowledged(0L, record("k", 1), E1);
        landAcknowledged(1L, record("j", 2), E1);
        commit(1L, E1);
        landAcknowledged(2L, record("z", 3), E1);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();
        var candidate = fold.checkpointCandidate(PARTITION, -1L).unwrap();

        assertThat(candidate.throughOffset()).as("control: the claim is the committed position").isEqualTo(1L);
        assertThat(EntityFoldSnapshot.decode(candidate.snapshot()).unwrap().state()).as("control: the snapshot runs ahead of its claim").containsKey("z");
        assertThat(substrate.saveCheckpoint(KEYSPACE, PARTITION, candidate.throughOffset(), candidate.snapshot(), candidate.lineage()).await().isSuccess()).isTrue();
        assertThat(manager.appendRecovered(arc, PARTITION, 2L, record("y", 9), 5L, E2).isFailure()).as("control: a divergence at offset 2").isTrue();
        assertThat(manager.repairDivergence(arc, PARTITION, _ -> true).isSuccess()).as("control: the cut removes the uncommitted tail").isTrue();
        assertThat(manager.partitionBuffer(arc, PARTITION).unwrap().headOffset()).as("control: offset 2 is gone from the log").isEqualTo(1L);
        return fold;
    }

    /// Two checkpoints; the second supersedes the first. The first block keeps its credit.
    @Test
    void supersededCheckpointBlock_isNeverReleased_tripwire() {
        var first = saveTwoCheckpoints();

        assertThat(refCountOf(first.superseded())).as(ENABLE_12).isEqualTo(1);
        assertThat(first.current()).as("control: the pointer moved to a different block").isNotEqualTo(first.superseded());
    }

    @Disabled("A-12 real assertion, RED today: the block a new pointer supersedes must be released (#2201)")
    @Test
    void supersededCheckpointBlock_isReleased() {
        var blocks = saveTwoCheckpoints();

        assertThat(refCountOf(blocks.superseded())).as("the superseded block must hold no credit").isLessThanOrEqualTo(0);
        assertThat(refCountOf(blocks.current())).as("control: the live block keeps its credit").isEqualTo(1);
    }

    private record Blocks(BlockId superseded, BlockId current) {}

    private Blocks saveTwoCheckpoints() {
        landAcknowledged(0L, record("k", 1), E1);
        commit(0L, E1);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();
        var first = fold.checkpointCandidate(PARTITION, -1L).unwrap();

        assertThat(substrate.saveCheckpoint(KEYSPACE, PARTITION, first.throughOffset(), first.snapshot(), first.lineage()).await().isSuccess()).isTrue();
        var superseded = pointedBlock();

        landAcknowledged(1L, record("j", 2), E1);
        commit(1L, E1);
        assertThat(fold.caughtUp(PARTITION).await().isSuccess()).as("control: the fold applied the new record").isTrue();
        var second = fold.checkpointCandidate(PARTITION, first.throughOffset()).unwrap();

        assertThat(substrate.saveCheckpoint(KEYSPACE, PARTITION, second.throughOffset(), second.snapshot(), second.lineage()).await().isSuccess()).isTrue();
        return new Blocks(superseded, pointedBlock());
    }

    private BlockId pointedBlock() {
        var pointer = kv.getTyped(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, PARTITION), EntityFoldCheckpointValue.class).unwrap();

        return BlockId.fromHex(pointer.blockIdHex()).unwrap();
    }

    private int refCountOf(BlockId block) {
        return metadata.getLifecycle(block).map(lifecycle -> lifecycle.refCount()).or(0);
    }

    /// The owner reports `through` committed. With a WAL the replica's own write is durable a moment after the append returns, and the position
    /// is exposed only up to that, so the report is repeated (as the owner's tick does) until the substrate shows it.
    private void commit(long through, Epoch epoch) {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);

        manager.syncReplicated(arc, PARTITION).await();

        do {
            manager.commitAdvanced(arc, PARTITION, through, epoch);

            if (substrate.committedOffset(KEYSPACE, PARTITION) >= through) {
                return;
            }

            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
        } while (System.nanoTime() < deadline);

        assertThat(substrate.committedOffset(KEYSPACE, PARTITION)).as("the committed position reached " + through).isGreaterThanOrEqualTo(through);
    }

    private void landAcknowledged(long offset, byte[] record, Epoch epoch) {
        landAcknowledged(offset, record, epoch, 1000L + offset);
    }

    private void landAcknowledged(long offset, byte[] record, Epoch epoch, long timestamp) {
        assertThat(manager.appendRecovered(arc, PARTITION, offset, record, timestamp, epoch).isSuccess()).isTrue();
    }

    /// Retention evicts the records whose timestamp is old: the ones landed with timestamp 1000+.
    private void evictOld() {
        manager.partitionBuffer(arc, PARTITION).or(() -> fail("no ring")).evictByAge(3_600_000L);
    }

    private static byte[] record(String key, int value) {
        return EntityLogRecord.upsert(key, String.valueOf(value).getBytes(StandardCharsets.UTF_8)).encode();
    }

    private static Serializer noopSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer noopDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
