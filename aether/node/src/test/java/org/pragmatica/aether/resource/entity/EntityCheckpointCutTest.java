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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2087 (B4-cut, round 4): a checkpoint is usable only over the lineage it was taken over. On the real ring, the real checkpoint block store
/// and the real KV pointer: `k` and `j` are committed and checkpointed (claim 1); the bounded promotion escape's contract is that a node may
/// then cut ACKNOWLEDGED records, so `j` is cut and a different record `z` lands at offset 1 and commits. The checkpoint's claim (1) is still
/// within the log, but it folded `j`. A rebuild that seeded from it would hold `j` and never fold `z`.
class EntityCheckpointCutTest {
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

    @BeforeEach
    void setUp() {
        arc = EntityPartitionArc.arcName(KEYSPACE);
        manager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, org.pragmatica.aether.stream.replication.ReplicationManager.NONE);
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        storage = StorageInstance.storageInstance("entity-ckpt",
                                                  List.of(MemoryTier.memoryTier(ONE_GB), LocalDiskTier.localDiskTier(storageDir, ONE_GB).unwrap()),
                                                  MetadataStore.inMemoryMetadataStore("entity-ckpt"));
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

    @Test
    void aCheckpointTakenOverACutRecord_isDiscarded_andTheRebuildFoldsTheNewLineage() {
        landAcknowledged(0L, record("k", 1), E1);
        landAcknowledged(1L, record("j", 2), E1);
        manager.commitAdvanced(arc, PARTITION, 1L, E1);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();
        var candidate = fold.checkpointCandidate(PARTITION, -1L).unwrap();

        assertThat(candidate.throughOffset()).as("control: the claim covers both committed records").isEqualTo(1L);
        assertThat(candidate.lineage().isPresent()).as("control: the lineage of the record at the claim was recorded").isTrue();
        assertThat(substrate.saveCheckpoint(KEYSPACE, PARTITION, candidate.throughOffset(), candidate.snapshot(), candidate.lineage()).await().isSuccess())
            .as("control: saved through the real block store and the real KV pointer").isTrue();
        assertThat(substrate.loadCheckpoint(KEYSPACE, PARTITION).await().unwrap().unwrap().lineage().isPresent()).as("control: the lineage survives the block").isTrue();

        // the bounded escape's lineage: it cuts back to offset 0 and puts a DIFFERENT record at offset 1, which is acknowledged in turn
        var z = record("z", 9);

        assertThat(manager.appendRecovered(arc, PARTITION, 1L, z, 5L, E2).isFailure()).as("control: a divergence at offset 1").isTrue();
        assertThat(manager.repairDivergence(arc, PARTITION, _ -> true).isSuccess()).as("control: the cut").isTrue();
        assertThat(manager.appendRecovered(arc, PARTITION, 1L, z, 5L, E2).isSuccess()).as("control: the new lineage lands at offset 1").isTrue();
        manager.commitAdvanced(arc, PARTITION, 1L, E2);

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();
        assertThat(fold.get(PARTITION, "j").isPresent()).as("j was cut: the rebuilt fold must not hold it").isFalse();
        assertThat(fold.get(PARTITION, "z").isPresent()).as("z is the new lineage's record at offset 1").isTrue();
        assertThat(fold.get(PARTITION, "k").isPresent()).as("k, at offset 0, is shared by both lineages").isTrue();
    }

    /// The identity covers a WINDOW of records: a new lineage that reproduces the record at the claim byte for byte, but not the ones before it,
    /// still differs from what the checkpoint folded.
    @Test
    void aCutThatRewritesAnEarlierRecord_butReproducesTheClaimRecordByteForByte_isStillDetected() {
        var b = record("b", 2);

        landAcknowledged(0L, record("a", 1), E1);
        landAcknowledged(1L, b, E1);
        manager.commitAdvanced(arc, PARTITION, 1L, E1);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        fold.ready(PARTITION).await();
        var candidate = fold.checkpointCandidate(PARTITION, -1L).unwrap();

        substrate.saveCheckpoint(KEYSPACE, PARTITION, candidate.throughOffset(), candidate.snapshot(), candidate.lineage()).await();

        assertThat(manager.appendRecovered(arc, PARTITION, 0L, record("a2", 5), 5L, E2).isFailure()).as("control: a divergence at offset 0").isTrue();
        assertThat(manager.repairDivergence(arc, PARTITION, _ -> true).isSuccess()).as("control: the whole tail is cut").isTrue();
        assertThat(manager.appendRecovered(arc, PARTITION, 0L, record("a2", 5), 5L, E2).isSuccess()).isTrue();
        assertThat(manager.appendRecovered(arc, PARTITION, 1L, b, 6L, E2).isSuccess()).as("control: the claim record is reproduced byte for byte").isTrue();
        manager.commitAdvanced(arc, PARTITION, 1L, E2);

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();
        assertThat(fold.get(PARTITION, "a").isPresent()).as("a was cut: not folded from the stale checkpoint").isFalse();
        assertThat(fold.get(PARTITION, "a2").isPresent()).as("the new lineage's record at offset 0").isTrue();
    }

    /// Control: the validation does not discard a checkpoint whose lineage still holds -- a cut ABOVE the claim leaves it valid, and the
    /// rebuild seeds from it (the log below the claim is not needed).
    @Test
    void aCheckpointOverAnUntouchedPrefix_isStillUsed_afterACutAboveItsClaim() {
        landAcknowledged(0L, record("k", 1), E1);
        landAcknowledged(1L, record("j", 2), E1);
        manager.commitAdvanced(arc, PARTITION, 0L, E1);
        landAcknowledged(2L, record("x", 3), E1);
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        assertThat(fold.ready(PARTITION).await().isSuccess()).isTrue();
        var candidate = fold.checkpointCandidate(PARTITION, -1L).unwrap();

        assertThat(candidate.throughOffset()).as("control: the claim is the committed position").isEqualTo(0L);
        assertThat(substrate.saveCheckpoint(KEYSPACE, PARTITION, candidate.throughOffset(), candidate.snapshot(), candidate.lineage()).await().isSuccess()).isTrue();
        var seeded = substrate.loadCheckpoint(KEYSPACE, PARTITION).await().unwrap().unwrap();

        assertThat(EntityFoldSnapshot.decode(seeded.snapshot()).isSuccess()).isTrue();
        assertThat(substrate.lineageAt(KEYSPACE, PARTITION, 0L).unwrap()).as("the local record at the claim still has the recorded identity")
                                                                         .isEqualTo(seeded.lineage().unwrap());
    }

    private void landAcknowledged(long offset, byte[] record, Epoch epoch) {
        assertThat(manager.appendRecovered(arc, PARTITION, offset, record, 1000L + offset, epoch).isSuccess()).isTrue();
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
