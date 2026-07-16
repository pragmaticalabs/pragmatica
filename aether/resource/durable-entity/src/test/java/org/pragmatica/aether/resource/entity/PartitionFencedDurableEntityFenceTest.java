// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.dht.PartitionOwnerEpochGate;
import org.pragmatica.aether.dht.PartitionOwnerEpochSource;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Per-`(keyspace, partition)` fence proof for [PartitionFencedDurableEntity] (plan Phase 2b-ii) — the
/// per-partition sibling of [FencedDurableEntityFenceTest]. A real [MemoryStorageEngine] fenced by the
/// real [PartitionOwnerEpochGate] over a real [OwnershipEpochHighWater] backs the entity, and the
/// per-partition owner-epoch stamp comes from a mutable [PartitionOwnerEpochSource] that models a
/// SAME-GENERATION reshuffle handover of a single partition.
///
/// ## The non-negotiable acceptance (the gap 2b-i misses)
/// The coarse 2b-i fence advances the `"core"` governor arc, which moves only on a GOVERNOR change. This
/// test advances ONLY the `(keyspace, partition)` high-water — NO governor change, NO community-arc
/// change, NO `dhtPartition("core")` change — exactly a same-generation reshuffle. It then proves:
///
///   - the deposed partition owner's write (stamped at the OLD per-partition epoch) is REJECTED with
///     [DurableEntityError.StaleOwner];
///   - the current partition owner's write (stamped at the NEW per-partition epoch) COMMITS and reads
///     back;
///   - a key in a DIFFERENT partition (whose high-water never moved) is UNAFFECTED — proving the fence
///     is per-partition, not coarse.
///
/// Single-JVM, no cluster: the engine enforces the fence at its own commit point (enforce-at-replica),
/// and the high-water is advanced directly via the same notification path `AetherNode` wires
/// (`OwnershipEpochHighWater.onStreamPartitionOwnershipPut`).
class PartitionFencedDurableEntityFenceTest {
    private static final TimeSpan AWAIT = timeSpan(5).seconds();
    private static final String KEYSPACE = "orders";
    private static final int PARTITION_COUNT = 8;

    /// Mutable per-partition owner-epoch source modeling this node's believed-current owner epoch for
    /// each partition. Flipping a partition to a strictly-older epoch (while that partition's high-water
    /// has advanced) models a deposed owner of THAT partition that still believes it owns the arc.
    private static final class MutablePartitionOwnerEpoch implements PartitionOwnerEpochSource {
        private final ConcurrentHashMap<Integer, Epoch> epochs = new ConcurrentHashMap<>();

        private void set(int partition, Epoch epoch) {
            epochs.put(partition, epoch);
        }

        @Override
        public Epoch currentOwnerEpoch(int partition) {
            return epochs.getOrDefault(partition, Epoch.ZERO);
        }
    }

    private static Serializer intSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {
                byteBuf.writeInt((Integer) object);
            }
        };
    }

    private static Deserializer intDeserializer() {
        return new Deserializer() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T read(ByteBuf byteBuf) {
                return (T) Integer.valueOf(byteBuf.readInt());
            }
        };
    }

    private KVStore<AetherKey, AetherValue> store;
    private OwnershipEpochHighWater highWater;
    private StorageEngine engine;
    private EntityPartitionArc arc;
    private MutablePartitionOwnerEpoch ownerEpoch;
    private DurableEntity<String, Integer> entity;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), intSerializer(), intDeserializer());
        highWater = OwnershipEpochHighWater.ownershipEpochHighWater(store);
        engine = MemoryStorageEngine.memoryStorageEngine(PartitionOwnerEpochGate.partitionOwnerEpochGate(highWater));
        arc = EntityPartitionArc.entityPartitionArc(KEYSPACE, PARTITION_COUNT);
        ownerEpoch = new MutablePartitionOwnerEpoch();
        entity = PartitionFencedDurableEntity.partitionFencedDurableEntity(engine, ownerEpoch, arc, intSerializer(), intDeserializer());
    }

    /// Advance ONLY the `(keyspace, partition)` high-water to model a new owner taking over THAT
    /// partition at `epoch` — the SAME notification path `AetherNode` wires via
    /// `OwnershipEpochHighWater.onStreamPartitionOwnershipPut`. No governor / community / core-arc change.
    private void newPartitionOwnerTakesOver(int partition, Epoch epoch) {
        highWater.advance(OwnershipDomain.streamPartition(KEYSPACE, partition), epoch);
    }

    /// Two keys that land in DIFFERENT partitions under [EntityPartitionArc], so advancing one
    /// partition's high-water leaves the other untouched.
    private String[] twoKeysInDifferentPartitions() {
        var first = "o-1";
        var firstPartition = arc.partitionOf(first);

        for (var i = 2; i < 10_000; i++) {
            var candidate = "o-" + i;

            if (arc.partitionOf(candidate) != firstPartition) {
                return new String[]{first, candidate};
            }
        }

        return fail("no two sample keys landed in different partitions — check PARTITION_COUNT");
    }

    @Nested
    class CurrentOwner {
        @Test
        void create_commitsFencedState_atPartitionOwnerEpoch() {
            ownerEpoch.set(arc.partitionOf("o-1"), Epoch.epoch(8, 1));

            entity.create("o-1", 7)
                  .await(AWAIT)
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state).isEqualTo(7));
        }

        @Test
        void update_commitsAndReadsBack_atPartitionOwnerEpoch() {
            ownerEpoch.set(arc.partitionOf("o-1"), Epoch.epoch(8, 1));
            entity.create("o-1", 7).await(AWAIT).onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            entity.update("o-1", value -> value + 5)
                  .await(AWAIT)
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state).isEqualTo(12));

            entity.get("o-1")
                  .await(AWAIT)
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(12));
        }
    }

    @Nested
    class DeposedPartitionOwnerSameGeneration {
        /// THE acceptance: a deposed partition owner is rejected on a SAME-GENERATION reshuffle. The
        /// partition's epoch advances on its LOCAL counter only (8:1 -> 8:2) — same `rabiaTerm` 8, no
        /// governor change — and the deposed owner stamping the old 8:1 is fenced.
        @Test
        void update_rejectedWithStaleOwner_afterSameGenerationReshuffle() {
            var partition = arc.partitionOf("o-1");

            ownerEpoch.set(partition, Epoch.epoch(8, 1));
            entity.create("o-1", 7).await(AWAIT).onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            // Same-generation reshuffle: a new owner takes over THIS partition at 8:2 (same rabiaTerm 8,
            // advanced ownershipTerm). The governor / "core" arc is NEVER touched.
            newPartitionOwnerTakesOver(partition, Epoch.epoch(8, 2));
            // This node is deposed as the partition's owner but still believes it owns it at 8:1.
            ownerEpoch.set(partition, Epoch.epoch(8, 1));

            entity.update("o-1", value -> value + 5)
                  .await(AWAIT)
                  .onSuccess(state -> fail("Deposed partition owner's same-generation update must be rejected, got " + state))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.StaleOwner.class));
        }

        @Test
        void create_rejectedWithStaleOwner_afterSameGenerationReshuffle() {
            var partition = arc.partitionOf("o-2");

            newPartitionOwnerTakesOver(partition, Epoch.epoch(8, 2));
            ownerEpoch.set(partition, Epoch.epoch(8, 1));

            entity.create("o-2", 1)
                  .await(AWAIT)
                  .onSuccess(state -> fail("Deposed partition owner's same-generation create must be rejected, got " + state))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.StaleOwner.class));
        }

        @Test
        void update_acceptedForNewPartitionOwner_atTheAdvancedEpoch() {
            var partition = arc.partitionOf("o-1");

            ownerEpoch.set(partition, Epoch.epoch(8, 1));
            entity.create("o-1", 7).await(AWAIT).onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            // New owner takes over at 8:2 and this node IS the new owner (stamps 8:2).
            newPartitionOwnerTakesOver(partition, Epoch.epoch(8, 2));
            ownerEpoch.set(partition, Epoch.epoch(8, 2));

            entity.update("o-1", value -> value + 1)
                  .await(AWAIT)
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state).isEqualTo(8));
        }
    }

    @Nested
    class PerPartitionGranularity {
        /// A reshuffle of ONE partition must not fence a key in a DIFFERENT partition — the property the
        /// coarse `"core"` fence cannot provide (it would fence every key once the single arc advanced).
        @Test
        void update_unaffected_whenADifferentPartitionReshuffles() {
            var keys = twoKeysInDifferentPartitions();
            var fenced = keys[0];
            var other = keys[1];
            var fencedPartition = arc.partitionOf(fenced);
            var otherPartition = arc.partitionOf(other);

            ownerEpoch.set(fencedPartition, Epoch.epoch(8, 1));
            ownerEpoch.set(otherPartition, Epoch.epoch(8, 1));
            entity.create(other, 100).await(AWAIT).onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            // Reshuffle ONLY the fenced partition. The other partition's high-water never moves.
            newPartitionOwnerTakesOver(fencedPartition, Epoch.epoch(8, 2));

            // A write to the OTHER partition, stamped at its still-current 8:1, must COMMIT.
            entity.update(other, value -> value + 1)
                  .await(AWAIT)
                  .onFailure(PartitionFencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state).isEqualTo(101));
        }

        /// And the SAME reshuffle DOES fence the deposed owner of the reshuffled partition — the two
        /// halves of granularity proven against one high-water state.
        @Test
        void update_rejected_forTheReshuffledPartition_inTheSameRun() {
            var keys = twoKeysInDifferentPartitions();
            var fenced = keys[0];
            var fencedPartition = arc.partitionOf(fenced);

            ownerEpoch.set(fencedPartition, Epoch.epoch(8, 1));
            entity.create(fenced, 7).await(AWAIT).onFailure(PartitionFencedDurableEntityFenceTest::failCause);

            newPartitionOwnerTakesOver(fencedPartition, Epoch.epoch(8, 2));
            var deposedEpochHolder = new AtomicReference<>(Epoch.epoch(8, 1));
            ownerEpoch.set(fencedPartition, deposedEpochHolder.get());

            entity.update(fenced, value -> value + 5)
                  .await(AWAIT)
                  .onSuccess(state -> fail("Deposed owner of the reshuffled partition must be rejected, got " + state))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.StaleOwner.class));
        }
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }
}
