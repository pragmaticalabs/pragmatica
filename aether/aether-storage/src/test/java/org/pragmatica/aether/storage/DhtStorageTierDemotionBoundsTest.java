// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.LocalDHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockLifecycle;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.DemotionStrategy;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.storage.DemotionConfig.demotionConfig;

/// #2258 review question Q5: `DhtStorageTier.usedBytes()/maxBytes()` now feed the demotion manager's utilisation (they were
/// 0 / `Long.MAX_VALUE`). Two things must hold for that to be harmless: a prefix with no cap reports `Long.MAX_VALUE`, so its
/// utilisation stays at zero however much it holds; and the capped `stream-segments` tier, last in the streams instance, is
/// never a demotion source however far over its cap it is (the cap is a bound on client puts, not a demotion trigger).
class DhtStorageTierDemotionBoundsTest {
    private static final NodeId NODE_ID = new NodeId("node-1");
    private static final String SEGMENTS = "stream-segments";
    private static final byte[] SEGMENT = "s".repeat(60).getBytes(StandardCharsets.UTF_8);
    private static final long CAP = SEGMENT.length;

    private static final class CountingDHTClient implements DHTClient {
        private final DHTClient delegate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingDHTClient(DHTClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            calls.incrementAndGet();

            return delegate.get(key);
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            calls.incrementAndGet();

            return delegate.put(key, value);
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            calls.incrementAndGet();

            return delegate.remove(key);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            calls.incrementAndGet();

            return delegate.exists(key);
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return delegate.partitionFor(key);
        }

        @Override
        public LocalUsage localUsage(String prefix) {
            return delegate.localUsage(prefix);
        }
    }

    private static MemoryStorageEngine cappedEngine() {
        var accounting = DhtPrefixAccounting.dhtPrefixAccounting(CAP);

        accounting.register("docs-blocks");

        return memoryStorageEngine(OwnerEpochGate.noOp(), accounting);
    }

    private static CountingDHTClient clientOver(MemoryStorageEngine engine) {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(NODE_ID);

        return new CountingDHTClient(LocalDHTClient.localDHTClient(dhtNode(NODE_ID, engine, ring, DHTConfig.SINGLE_NODE)));
    }

    private static BlockId idOf(byte[] content) {
        return BlockId.blockId(content).unwrap();
    }

    /// MT: `DhtPrefixAccounting.maxBytes` answering the stream-segments cap for every prefix would report 60 bytes here, and
    /// the 500-byte block would read as 830% utilised: the demotion manager would treat the tier as permanently over its mark.
    @Test
    void anUncappedPrefix_reportsMaxLongMax_soTheUtilisationTheDemotionManagerComputesStaysNearZero() {
        var tier = DhtStorageTier.dhtStorageTier(clientOver(cappedEngine()), "docs-blocks");
        var block = new byte[500];

        tier.put(idOf(block), block).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

        assertThat(tier.usedBytes()).as("the tier holds the block, so the number is real").isEqualTo(500L);
        assertThat(tier.maxBytes()).isEqualTo(Long.MAX_VALUE);
        assertThat((double) tier.usedBytes() / tier.maxBytes()).as("the demotion manager's utilisation").isLessThan(1e-9);
    }

    /// MT: `DefaultDemotionManager.demoteAllTiers` visiting the last tier too would ask for a tier below it and throw; a
    /// manager that demoted out of the DHT tier would read, and later delete, the block. Neither happens: no call reaches
    /// the DHT client and the block stays.
    @Test
    void aCappedStreamSegmentsTier_atAndOverItsCap_asTheLastTier_triggersNoDemotionAttempt() {
        var engine = cappedEngine();
        var client = clientOver(engine);
        var dht = DhtStorageTier.dhtStorageTier(client, SEGMENTS);
        var memory = MemoryTier.memoryTier(1 << 20, TierLevel.MEMORY);
        var store = MetadataStore.inMemoryMetadataStore("demotion-bounds");
        var id = idOf(SEGMENT);

        dht.put(id, SEGMENT).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.REMOTE));
        // A repair write is never refused, so the node can sit past its cap: 120 bytes against 60.
        engine.putVersioned(("stream-segments/repaired").getBytes(StandardCharsets.UTF_8), SEGMENT, 1L, 0L, 0L, 0L, true)
              .await()
              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        assertThat(dht.usedBytes()).as("precondition: over the cap").isGreaterThan(dht.maxBytes());
        var manager = DemotionManager.demotionManager(List.of(memory, dht),
                                                      store,
                                                      demotionConfig(DemotionStrategy.LRU, 0.1, 0.05, 100));
        var callsBefore = client.calls.get();

        manager.activate();

        assertThat(manager.demote()).isZero();
        assertThat(manager.stats().blocksDemoted()).isZero();
        assertThat(client.calls.get()).as("nothing read, written or removed through the DHT tier").isEqualTo(callsBefore);
        assertThat(dht.usedBytes()).as("the blocks are still held").isEqualTo(2L * CAP);
    }
}
