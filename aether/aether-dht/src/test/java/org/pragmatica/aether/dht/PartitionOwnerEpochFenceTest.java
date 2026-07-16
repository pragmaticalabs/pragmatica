// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipDomain.StreamPartition;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit proof for the per-`(keyspace, partition)` entity fence primitives (#345 plan Phase 2b-ii): the
/// arc derivation ([EntityPartitionArc]), the per-partition data-plane gate ([PartitionOwnerEpochGate])
/// and the committed-epoch source ([KvPartitionOwnerEpochSource]). These are the pieces the durable
/// entity stamps and checks against; the entity-level enforce-at-replica proof lives in the
/// durable-entity module's `PartitionFencedDurableEntityFenceTest`.
class PartitionOwnerEpochFenceTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION_COUNT = 8;
    private static final NodeId OWNER = NodeId.nodeId("core-1").unwrap();

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    private EntityPartitionArc arc;

    @BeforeEach
    void setUp() {
        arc = EntityPartitionArc.entityPartitionArc(KEYSPACE, PARTITION_COUNT);
    }

    @Nested
    class ArcDerivation {
        @Test
        void dhtKey_roundTripsToTheSameArc_asPartitionOf() {
            var key = "o-42";
            var expected = OwnershipDomain.streamPartition(KEYSPACE, arc.partitionOf(key));

            assertThat(EntityPartitionArc.arcOf(arc.dhtKey(key)).or(otherArc()))
                .as("the arc parsed from the DHT key bytes must equal the arc the entity stamps against")
                .isEqualTo(expected);
        }

        @Test
        void partitionOf_isStableAndInRange() {
            for (var i = 0; i < 1000; i++) {
                var partition = arc.partitionOf("o-" + i);

                assertThat(partition).isBetween(0, PARTITION_COUNT - 1);
                assertThat(partition).isEqualTo(arc.partitionOf("o-" + i));
            }
        }

        @Test
        void arcOf_isNone_forANonEntityKey() {
            assertThat(EntityPartitionArc.arcOf("no-slashes-here".getBytes(StandardCharsets.UTF_8)).isEmpty())
                .as("a key without the keyspace/partition/ prefix is unfenced")
                .isTrue();
        }

        private static StreamPartition otherArc() {
            return OwnershipDomain.streamPartition("other", -1);
        }
    }

    @Nested
    class Gate {
        private KVStore<AetherKey, AetherValue> store;
        private OwnershipEpochHighWater highWater;
        private PartitionOwnerEpochGate gate;

        @BeforeEach
        void setUp() {
            store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
            highWater = OwnershipEpochHighWater.ownershipEpochHighWater(store);
            gate = PartitionOwnerEpochGate.partitionOwnerEpochGate(highWater);
        }

        @Test
        void isStale_false_whenPartitionHighWaterUnset() {
            var key = arc.dhtKey("o-1");

            assertThat(gate.isStale(key, 8L, 1L)).isFalse();
        }

        @Test
        void isStale_true_whenPresentedEpochStrictlyBelowPartitionHighWater_sameGeneration() {
            var key = arc.dhtKey("o-1");
            var partition = arc.partitionOf("o-1");

            // Same generation (rabiaTerm 8), advanced ownershipTerm 1 -> 2: a same-generation reshuffle.
            highWater.advance(OwnershipDomain.streamPartition(KEYSPACE, partition), Epoch.epoch(8, 2));

            assertThat(gate.isStale(key, 8L, 1L))
                .as("a deposed partition owner's (8,1) write is stale against the reshuffled (8,2) high-water")
                .isTrue();
            assertThat(gate.isStale(key, 8L, 2L))
                .as("the current owner's (8,2) write is NOT stale")
                .isFalse();
        }

        @Test
        void isStale_isPerPartition_notCoarse() {
            var fencedKey = arc.dhtKey("o-1");
            var fencedPartition = arc.partitionOf("o-1");
            var otherPartition = (fencedPartition + 1) % PARTITION_COUNT;

            highWater.advance(OwnershipDomain.streamPartition(KEYSPACE, fencedPartition), Epoch.epoch(8, 2));

            assertThat(gate.isStale(fencedKey, 8L, 1L)).isTrue();
            // A key whose partition we synthesize directly in the OTHER partition is unaffected.
            assertThat(gate.isStale(keyInPartition(otherPartition), 8L, 1L))
                .as("a different partition's high-water never moved, so its (8,1) write is not stale")
                .isFalse();
        }

        /// Build a DHT key whose embedded partition is exactly `partition` (bypassing the hash) to probe
        /// a specific arc deterministically.
        private byte[] keyInPartition(int partition) {
            return (KEYSPACE + "/" + partition + "/probe").getBytes(StandardCharsets.UTF_8);
        }
    }

    @Nested
    class CommittedEpochSource {
        private KVStore<AetherKey, AetherValue> store;
        private KvPartitionOwnerEpochSource source;

        @BeforeEach
        void setUp() {
            store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
            source = KvPartitionOwnerEpochSource.kvPartitionOwnerEpochSource(store, KEYSPACE);
        }

        @Test
        void currentOwnerEpoch_isFloor_whenNoRecordCommitted() {
            assertThat(source.currentOwnerEpoch(3)).isEqualTo(Epoch.ZERO);
        }

        @Test
        void currentOwnerEpoch_readsCommittedRecord() {
            commitOwnership(3, Epoch.epoch(8, 2), 2L);

            assertThat(source.currentOwnerEpoch(3))
                .as("the source reads the committed StreamPartitionOwnershipValue.ownerEpoch for the arc")
                .isEqualTo(Epoch.epoch(8, 2));
        }

        private void commitOwnership(int partition, Epoch epoch, long ownershipTerm) {
            var value = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(OWNER, epoch, ownershipTerm, HlcTimestamp.ZERO);
            var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey(KEYSPACE, partition);

            store.process(store.createBatch(List.of(new Put<>(key, value))));
        }
    }
}
