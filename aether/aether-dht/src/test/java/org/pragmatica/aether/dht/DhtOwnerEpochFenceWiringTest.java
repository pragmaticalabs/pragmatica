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
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Cross-module enforce-at-replica proof for the DHT owner-epoch fence (#345 piece 1c): a real
/// [MemoryStorageEngine] fenced by the real [HighWaterOwnerEpochGate] over a real
/// [OwnershipEpochHighWater] rejects a deposed owner's strictly-older-epoch put — and the writer's
/// stamp is read from committed KV by the real [KvOwnerEpochSource]. This is the seam that keeps the
/// BSL-1.1 [Epoch] out of the Apache-2.0 DHT engine while still enforcing monotonicity at the replica.
class DhtOwnerEpochFenceWiringTest {
    private static final NodeId DEPOSED = NodeId.nodeId("core-old").unwrap();
    private static final NodeId CURRENT = NodeId.nodeId("core-new").unwrap();
    private static final String CORE = "core";

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

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private KVStore<AetherKey, AetherValue> store;
    private OwnershipEpochHighWater highWater;
    private MemoryStorageEngine engine;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        highWater = OwnershipEpochHighWater.ownershipEpochHighWater(store);
        engine = MemoryStorageEngine.memoryStorageEngine(HighWaterOwnerEpochGate.highWaterOwnerEpochGate(highWater, CORE));
    }

    /// Commit an ownership record AND feed the high-water from it — mirroring the production wiring
    /// where `AetherNode` routes committed `DhtPartitionOwnershipKey` puts to
    /// `OwnershipEpochHighWater.onDhtOwnershipPut`. The committed KV is the source of truth; the
    /// high-water is its data-plane mirror, advanced as the cluster observes ownership changes.
    private void commitOwnership(NodeId owner, Epoch epoch, long ownershipTerm) {
        var ownership = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(owner, CORE, epoch, ownershipTerm, HlcTimestamp.ZERO);
        store.process(store.createBatch(List.of(new Put<>(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(CORE), ownership))));
        highWater.advance(OwnershipDomain.dhtPartition(CORE), ownership.fenceEpoch());
    }

    @Nested
    class EnforceAtReplica {
        @Test
        void putVersioned_deposedOwnerStaleEpoch_rejectedAtReplica() {
            // A new owner took over at epoch 8:0 — the replica observes that committed ownership.
            commitOwnership(CURRENT, Epoch.epoch(8, 0), 2L);

            // The deposed owner (still believing it owns "core") writes at its old epoch 7:0.
            engine.putVersioned(key("k"), value("deposed-write"), 9_999L, 7L, 0L)
                  .await()
                  .onSuccess(_ -> fail("Deposed owner's stale-epoch write must be rejected at the replica"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.StaleEpochWrite.class));
        }

        @Test
        void putVersioned_currentOwnerEpoch_acceptedAtReplica() {
            commitOwnership(CURRENT, Epoch.epoch(8, 0), 2L);

            engine.putVersioned(key("k"), value("current-write"), 100L, 8L, 0L)
                  .await()
                  .onFailure(c -> fail("Current owner's write must be accepted: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());
        }
    }

    @Nested
    class WriterStamping {
        @Test
        void kvOwnerEpochSource_readsCommittedOwnerEpoch_forStamping() {
            commitOwnership(CURRENT, Epoch.epoch(8, 3), 2L);

            var source = KvOwnerEpochSource.kvOwnerEpochSource(store, CORE);

            assertThat(source.currentEpochTerm()).isEqualTo(8L);
            assertThat(source.currentEpochCounter()).isEqualTo(3L);
        }

        @Test
        void kvOwnerEpochSource_noCommittedOwnership_stampsEpochZero() {
            var source = KvOwnerEpochSource.kvOwnerEpochSource(store, CORE);

            assertThat(source.currentEpochTerm()).isEqualTo(Epoch.ZERO.rabiaTerm());
            assertThat(source.currentEpochCounter()).isEqualTo(Epoch.ZERO.localCounter());
        }

        @Test
        void stampThenEnforce_currentOwnerStampAccepted_thenDeposedStampRejected() {
            commitOwnership(CURRENT, Epoch.epoch(8, 0), 2L);
            var source = KvOwnerEpochSource.kvOwnerEpochSource(store, CORE);

            // Current owner stamps with the source and is accepted.
            engine.putVersioned(key("k"), value("v1"), 100L, source.currentEpochTerm(), source.currentEpochCounter())
                  .await()
                  .onFailure(c -> fail("Current owner stamp must be accepted: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());

            // A deposed owner at the prior epoch is rejected even with a newer HLC version.
            engine.putVersioned(key("k"), value("v2"), 999L, 7L, 9L)
                  .await()
                  .onSuccess(_ -> fail("Deposed stamp must be rejected"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.StaleEpochWrite.class));
        }
    }

    @Nested
    class HighWaterSeededFromOwnership {
        @Test
        void notificationFedHighWater_thenStaleWriteRejected() {
            // Drive the high-water via the same notification path AetherNode wires (onDhtOwnershipPut).
            var ownership = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(CURRENT, CORE, Epoch.epoch(8, 0), 2L, HlcTimestamp.ZERO);
            highWater.advance(OwnershipDomain.dhtPartition(CORE), ownership.fenceEpoch());

            engine.putVersioned(key("k"), value("stale"), 1L, 7L, 0L)
                  .await()
                  .onSuccess(_ -> fail("Stale epoch below the advanced high-water must be rejected"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.StaleEpochWrite.class));
        }
    }
}
