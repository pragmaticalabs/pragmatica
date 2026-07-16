// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.HighWaterOwnerEpochGate;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.dht.OwnerEpochSource;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Fence proof for the [FencedDurableEntity] (plan Phase 2b-i) — the durable-entity sibling of
/// `DhtOwnerEpochFenceWiringTest`, lifted to the entity API. A real [MemoryStorageEngine] fenced by
/// the real [HighWaterOwnerEpochGate] over a real [OwnershipEpochHighWater] backs the entity, and the
/// owner-epoch stamp comes from a mutable [OwnerEpochSource] that models a governor handover:
///
///   - a write stamped at the CURRENT committed owner epoch COMMITS and reads back;
///   - after a new owner takes over (the high-water advances), the now-DEPOSED owner's update is
///     REJECTED with [DurableEntityError.StaleOwner] — the split-brain guarantee of spec §6.
///
/// This is the single-JVM gate the plan reserves for the fence work; it needs no cluster because the
/// engine enforces the fence at its own commit point (enforce-at-replica).
class FencedDurableEntityFenceTest {
    private static final TimeSpan AWAIT = timeSpan(5).seconds();
    private static final String KEYSPACE = "orders";
    private static final String CORE = "core";

    /// Mutable owner-epoch source modeling this node's believed-current owner epoch. Flipping it to a
    /// strictly-older epoch (while the high-water has advanced) models a deposed owner that still
    /// believes it owns the arc.
    private static final class MutableOwnerEpoch implements OwnerEpochSource {
        private final AtomicLong term;
        private final AtomicLong counter;

        private MutableOwnerEpoch(long term, long counter) {
            this.term = new AtomicLong(term);
            this.counter = new AtomicLong(counter);
        }

        private void set(long newTerm, long newCounter) {
            term.set(newTerm);
            counter.set(newCounter);
        }

        @Override
        public long currentEpochTerm() {
            return term.get();
        }

        @Override
        public long currentEpochCounter() {
            return counter.get();
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
    private MutableOwnerEpoch ownerEpoch;
    private DurableEntity<String, Integer> entity;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), intSerializer(), intDeserializer());
        highWater = OwnershipEpochHighWater.ownershipEpochHighWater(store);
        engine = MemoryStorageEngine.memoryStorageEngine(HighWaterOwnerEpochGate.highWaterOwnerEpochGate(highWater, CORE));
        ownerEpoch = new MutableOwnerEpoch(8L, 0L);
        entity = FencedDurableEntity.fencedDurableEntity(engine, ownerEpoch, intSerializer(), intDeserializer(), KEYSPACE);
    }

    /// Advance the partition high-water to model a new owner taking over at `epoch` — the same
    /// notification path `AetherNode` wires via `OwnershipEpochHighWater.onDhtOwnershipPut`.
    private void newOwnerTakesOver(Epoch epoch) {
        highWater.advance(OwnershipDomain.dhtPartition(CORE), epoch);
    }

    @Nested
    class CurrentOwner {
        @Test
        void create_commitsFencedState_atCurrentOwnerEpoch() {
            entity.create("o-1", 7)
                  .await(AWAIT)
                  .onFailure(FencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state).isEqualTo(7));
        }

        @Test
        void update_commitsAndReadsBack_atCurrentOwnerEpoch() {
            entity.create("o-1", 7).await(AWAIT).onFailure(FencedDurableEntityFenceTest::failCause);

            entity.update("o-1", value -> value + 5)
                  .await(AWAIT)
                  .onFailure(FencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state).isEqualTo(12));

            entity.get("o-1")
                  .await(AWAIT)
                  .onFailure(FencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(12));
        }
    }

    @Nested
    class DeposedOwner {
        @Test
        void update_rejectedWithStaleOwner_afterHandover() {
            entity.create("o-1", 7).await(AWAIT).onFailure(FencedDurableEntityFenceTest::failCause);

            // A new owner takes over the "core" arc at epoch 9:0; the high-water advances past 8:0.
            newOwnerTakesOver(Epoch.epoch(9, 0));
            // This node is deposed but still believes it owns the arc at the old epoch 8:0.
            ownerEpoch.set(8L, 0L);

            entity.update("o-1", value -> value + 5)
                  .await(AWAIT)
                  .onSuccess(state -> fail("Deposed owner's update must be rejected, got " + state))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.StaleOwner.class));
        }

        @Test
        void create_rejectedWithStaleOwner_afterHandover() {
            newOwnerTakesOver(Epoch.epoch(9, 0));
            ownerEpoch.set(8L, 0L);

            entity.create("o-2", 1)
                  .await(AWAIT)
                  .onSuccess(state -> fail("Deposed owner's create must be rejected, got " + state))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.StaleOwner.class));
        }

        @Test
        void update_acceptedForNewOwner_afterItStampsTheNewEpoch() {
            entity.create("o-1", 7).await(AWAIT).onFailure(FencedDurableEntityFenceTest::failCause);

            // New owner takes over at 9:0 and this node IS the new owner (stamps 9:0).
            newOwnerTakesOver(Epoch.epoch(9, 0));
            ownerEpoch.set(9L, 0L);

            entity.update("o-1", value -> value + 1)
                  .await(AWAIT)
                  .onFailure(FencedDurableEntityFenceTest::failCause)
                  .onSuccess(state -> assertThat(state).isEqualTo(8));
        }
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }
}
