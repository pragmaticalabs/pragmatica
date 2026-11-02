// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.segment.SegmentIndex;
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

/// Streams audit B-09 on the REAL substrate (#2201, round 2): `EntityUpdateRetryTripwireTest` runs the entity over a stub substrate whose `append`
/// lands the record by construction. Here the entity runs over the real `StreamEntityLogSubstrate`, the real manager and the real
/// `DefaultReplicationManager`: the committed ISR is shrunk below `CF - 1` for the first update (the record is appended with no floor and the
/// barrier is unmet, as `EntityAppendFloorTripwireTest` shows), then restored, and the caller retries the same plain `update`.
///
/// Because the substrate is real, an A-03 floor on the entity path DOES act here: it refuses the first update before the append, so nothing
/// double-applies. The real assertion therefore flips for the A-03 entity floor; it states only the outcome (one logical update applied
/// once). TRIPWIRE (enabled, GREEN) beside the REAL assertion (`@Disabled`, RED today).
class EntityUpdateRetryRealSubstrateTripwireTest {
    private static final NodeId SELF = new NodeId("owner-self");
    private static final NodeId PEER = new NodeId("replica-peer");
    private static final String KEYSPACE = "orders";
    private static final String ENABLE = "B-09 tripwire (real substrate): a retried update after an unmet barrier no longer applies the mutator twice; delete this tripwire and enable the real assertion beside it (#2201)";

    private final AtomicReference<List<NodeId>> isr = new AtomicReference<>(List.of(SELF, PEER));
    private StreamPartitionManager manager;
    private StorageInstance storage;
    private DurableEntity<String, Integer, AddOp> entity;

    sealed interface AddOp extends Mutator<Integer> {
        record Add(int delta) implements AddOp {
            @Override
            public Integer apply(Integer state) {
                return state + delta;
            }
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
        replication.inSyncReplicaSource((_, _) -> Option.some(isr.get()));
        manager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, replication);
        storage = StorageInstance.storageInstance("entity-retry",
                                                  List.of(MemoryTier.memoryTier(1024L * 1024 * 1024)),
                                                  MetadataStore.inMemoryMetadataStore("entity-retry"));
        var kv = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                throw new UnsupportedOperationException("never snapshotted in this test");
            }
        });
        EntityLogSubstrate substrate = streamEntityLogSubstrate(manager,
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

        substrate.ensureLog(KEYSPACE, 1, new ReplicationFactors(3, 2)).unwrap();
        entity = PartitionFencedDurableEntity.<String, Integer, AddOp> partitionFencedDurableEntity(KEYSPACE,
                                                                                                    substrate,
                                                                                                    EntityPartitionArc.entityPartitionArc(KEYSPACE, 1),
                                                                                                    new IntSerializer(),
                                                                                                    new IntDeserializer(),
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
    void retriedUpdate_afterAnUnmetBarrierOnTheRealSubstrate_appliesTheMutatorTwice_tripwire() {
        var result = updateTwiceAcrossAnUnmetBarrier();

        assertThat(result.first().isFailure()).as("control: the first attempt met the unmet barrier").isTrue();
        result.first().onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.ReplicationBarrierUnmet.class));
        assertThat(result.second().isSuccess()).as("control: the retry's barrier is met").isTrue();
        assertThat(stateOf(result.second())).as(ENABLE).isEqualTo(12);
    }

    @Disabled("B-09 real assertion (real substrate), RED today: one logical update must apply once; flips for the A-03 entity floor (#2201)")
    @Test
    void retriedUpdate_afterAnUnmetBarrierOnTheRealSubstrate_appliesTheMutatorOnce() {
        var result = updateTwiceAcrossAnUnmetBarrier();

        assertThat(stateOf(result.second())).isEqualTo(11);
    }

    private static int stateOf(Result<Integer> outcome) {
        return outcome.fold(_ -> -1, value -> value);
    }

    private record Attempts(Result<Integer> first, Result<Integer> second) {}

    /// k = 10 committed with the full ISR; then an update with the ISR shrunk to the owner; then the same update with the ISR restored.
    private Attempts updateTwiceAcrossAnUnmetBarrier() {
        var created = entity.create("k", 10).await();

        assertThat(created.isSuccess()).as("control: the create is acknowledged by the full ISR: " + created).isTrue();
        isr.set(List.of(SELF));
        var first = entity.update("k", new AddOp.Add(1)).await();

        isr.set(List.of(SELF, PEER));
        var second = entity.update("k", new AddOp.Add(1)).await();

        return new Attempts(first, second);
    }

    private static final class IntSerializer implements Serializer {
        @Override
        public byte[] encode(Object value) {
            return switch (value) {
                case AddOp.Add add -> ("Add:" + add.delta()).getBytes(StandardCharsets.UTF_8);
                case null, default -> String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            };
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class IntDeserializer implements Deserializer {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            var text = new String(bytes, StandardCharsets.UTF_8);

            return text.startsWith("Add:")
                   ? (T) new AddOp.Add(Integer.parseInt(text.substring(4)))
                   : (T) Integer.valueOf(text);
        }

        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
