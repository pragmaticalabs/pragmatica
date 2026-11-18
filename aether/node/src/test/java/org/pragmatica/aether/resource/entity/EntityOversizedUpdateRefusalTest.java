// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;

/// T-278 refusal arm at the ENTITY level (O19 ruled (c)): the entity-log `max_event_size` is 1 MiB, and it bounds the STORED
/// record: `EntityLogRecord#encode()`, i.e. the serialized record with its envelope (6-byte header + key bytes + encoded
/// state), not the user payload. With the key `k` (one byte) a state of N bytes is a record of N + 7 bytes.
///
/// A `DurableEntity.update` whose stored record is 1 048 577 bytes fails with `EntityError.StorageFailed` wrapping the typed
/// `EventTooLarge(1_048_577, 1_048_576)`, which names both sizes. Nothing is stored: the key reads back its previous state, the log
/// tail (head offset and the bytes at it) is unchanged, and the ring signals no dropped event (the refusal is not the ring's
/// `EVENT_DROPPED` and the eviction listener is never called). An update whose stored record is exactly 1 048 576 bytes is
/// acknowledged, folded and read back, both through the entity and from the log. The entity runs over the real
/// `StreamEntityLogSubstrate`, the real manager and the real `DefaultReplicationManager`.
class EntityOversizedUpdateRefusalTest {
    private static final NodeId SELF = new NodeId("owner-self");
    private static final NodeId PEER = new NodeId("replica-peer");
    private static final String KEYSPACE = "orders";
    private static final String KEY = "k";
    private static final int ENVELOPE_BYTES = 6 + KEY.length();
    private static final int MAX_RECORD_BYTES = 1_048_576;

    private final AtomicInteger evictions = new AtomicInteger();
    private StreamPartitionManager manager;
    private StorageInstance storage;
    private EntityLogSubstrate substrate;
    private DurableEntity<String, String, Fill> entity;

    record Fill(int stateBytes) implements Mutator<String> {
        static Fill fill(int stateBytes) {
            return new Fill(stateBytes);
        }

        @Override
        public String apply(String state) {
            return "x".repeat(stateBytes);
        }
    }

    @BeforeEach
    void setUp() {
        var registry = ReplicaRegistry.replicaRegistry();
        var replication = replicationManager(SELF, registry);
        var arc = EntityPartitionArc.arcName(KEYSPACE);

        registry.registerReplica(arc, 0, SELF);
        registry.registerReplica(arc, 0, PEER);
        registry.updateWatermark(arc, 0, PEER, 1_000L);
        replication.inSyncReplicaSource((_, _) -> Option.some(List.of(SELF, PEER)));
        manager = streamPartitionManager(64L * 1024 * 1024, countingEvictionListener(), replication);
        storage = StorageInstance.storageInstance("entity-oversized",
                                                  List.of(MemoryTier.memoryTier(1024L * 1024 * 1024)),
                                                  MetadataStore.inMemoryMetadataStore("entity-oversized"));
        var kv = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                throw new UnsupportedOperationException("never snapshotted in this test");
            }
        });
        substrate = streamEntityLogSubstrate(manager,
                                             (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                             null,
                                             new SegmentIndex(),
                                             countingEvictionListener(),
                                             storage,
                                             kv,
                                             commands -> applyTo(kv, commands));
        substrate.ensureLog(KEYSPACE, 1, new ReplicationFactors(3, 2)).unwrap();
        entity = PartitionFencedDurableEntity.<String, String, Fill> partitionFencedDurableEntity(KEYSPACE,
                                                                                                   substrate,
                                                                                                   EntityPartitionArc.entityPartitionArc(KEYSPACE, 1),
                                                                                                   new TextSerializer(),
                                                                                                   new TextDeserializer(),
                                                                                                   SELF,
                                                                                                   (_, _) -> Option.some(new CommittedOwner(SELF, Epoch.ZERO)),
                                                                                                   Option.none(),
                                                                                                   Option.some((_, _) -> Promise.success(Unit.unit())));
    }

    @AfterEach
    void tearDown() {
        manager.close();
        storage.shutdown();
    }

    @Test
    void update_whoseStoredRecordIsOneByteOverOneMiB_isRefusedWithTheTypedSizes_andNothingIsStored() {
        assertThat(entity.create(KEY, "seed").await().isSuccess()).as("control: the seed create is acknowledged").isTrue();
        var headBefore = substrate.headOffset(KEYSPACE, 0);
        var tailBefore = lastRecord(headBefore);

        var refused = entity.update(KEY, Fill.fill(MAX_RECORD_BYTES + 1 - ENVELOPE_BYTES)).await();

        assertThat(refused.isFailure()).as("the update whose stored record is 1_048_577 bytes is refused").isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(EntityError.StorageFailed.class)
                                                    .extracting(c -> ((EntityError.StorageFailed) c).cause())
                                                    .isEqualTo(new StreamError.EventTooLarge(MAX_RECORD_BYTES + 1, MAX_RECORD_BYTES))
                                                    .isNotEqualTo(StreamError.General.EVENT_DROPPED));
        assertThat(entity.get(KEY).await().unwrap()).as("the key reads back its previous state").isEqualTo(Option.some("seed"));
        assertThat(substrate.headOffset(KEYSPACE, 0)).as("the log head did not move").isEqualTo(headBefore);
        assertThat(lastRecord(headBefore)).as("the bytes at the log tail are unchanged").isEqualTo(tailBefore);
        assertThat(evictions.get()).as("no dropped-event signal reached the eviction listener").isZero();
    }

    @Test
    void update_whoseStoredRecordIsExactlyOneMiB_isAcked_folded_andReadBack() {
        assertThat(entity.create(KEY, "seed").await().isSuccess()).as("control: the seed create is acknowledged").isTrue();
        var headBefore = substrate.headOffset(KEYSPACE, 0);
        var state = "x".repeat(MAX_RECORD_BYTES - ENVELOPE_BYTES);

        var updated = entity.update(KEY, Fill.fill(state.length())).await();

        assertThat(updated.unwrap()).as("the 1_048_576-byte stored record is acknowledged and folded").isEqualTo(state);
        assertThat(entity.get(KEY).await().unwrap()).as("the key reads back the new state").isEqualTo(Option.some(state));
        assertThat(substrate.headOffset(KEYSPACE, 0)).as("the log head advanced by one record").isEqualTo(headBefore + 1);
        assertThat(lastRecord(headBefore + 1).length).as("the stored record read back from the log is exactly 1 MiB")
                                                    .isEqualTo(MAX_RECORD_BYTES);
    }

    private EvictionListener countingEvictionListener() {
        return (_, _, _) -> countEviction();
    }

    private Result<Unit> countEviction() {
        evictions.incrementAndGet();

        return Result.unitResult();
    }

    private static Promise<List<Object>> applyTo(KVStore<AetherKey, AetherValue> kv, List<KVCommand<AetherKey>> commands) {
        kv.process(kv.createBatch(commands));

        return Promise.success(List.of());
    }

    private byte[] lastRecord(long offset) {
        return substrate.read(KEYSPACE, 0, offset, 1).await().unwrap().getFirst();
    }

    private static final class TextSerializer implements Serializer {
        @Override
        public byte[] encode(Object value) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class TextDeserializer implements Deserializer {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            return (T) new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
