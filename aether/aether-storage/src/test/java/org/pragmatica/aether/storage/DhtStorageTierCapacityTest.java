// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #2258 spec 3.2 item 7, the tier's side (NT-125): a put the DHT refuses for capacity fails `TierFull(REMOTE, used, max)`;
/// a write through the streams instance then fails and leaves no local-disk copy; the tier reports this node's live bytes of
/// its prefix and its cap.
class DhtStorageTierCapacityTest {
    private static final byte[] CONTENT = "a sealed segment".getBytes(StandardCharsets.UTF_8);
    private static final String PREFIX = "stream-segments";

    @TempDir
    Path dir;

    private static final class RefusingDHTClient implements DHTClient {
        private final AtomicReference<String> askedPrefix = new AtomicReference<>();
        private final long used;
        private final long max;
        private final boolean refuse;

        private RefusingDHTClient(long used, long max, boolean refuse) {
            this.used = used;
            this.max = max;
            this.refuse = refuse;
        }

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            return refuse
                   ? DHTError.replicaFull(used, max).promise()
                   : Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(false);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(false);
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        @Override
        public LocalUsage localUsage(String prefix) {
            askedPrefix.set(prefix);

            return new LocalUsage(used, max);
        }
    }

    /// MT-59 at the tier: a `ReplicaFull` passed through unmapped would reach the instance as an unrelated cause.
    @Test
    void aPutTheDhtRefusesForCapacity_failsTierFull_remote_withTheReplicasNumbers() {
        var tier = DhtStorageTier.dhtStorageTier(new RefusingDHTClient(134_217_000L, 134_217_728L, true), PREFIX);
        var id = BlockId.blockId(CONTENT).unwrap();

        var failure = tier.put(id, CONTENT).await().<Cause>fold(cause -> cause, _ -> null);

        assertThat(failure).isInstanceOf(StorageError.TierFull.class);
        var full = (StorageError.TierFull) failure;

        assertThat(full.tier()).isEqualTo(TierLevel.REMOTE);
        assertThat(full.usedBytes()).isEqualTo(134_217_000L);
        assertThat(full.maxBytes()).isEqualTo(134_217_728L);
    }

    @Test
    void aSealThroughTheInstance_failsTierFull_andLeavesNoLocalDiskCopy() {
        var disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1 << 20).unwrap();
        var dht = DhtStorageTier.dhtStorageTier(new RefusingDHTClient(100L, 100L, true), PREFIX);
        var store = MetadataStore.inMemoryMetadataStore("streams");
        var storage = StorageInstance.storageInstance("streams", List.of(MemoryTier.memoryTier(1 << 20), disk, dht), store);
        var id = BlockId.blockId(CONTENT).unwrap();

        var failure = storage.put(CONTENT).await().<Cause>fold(cause -> cause, _ -> null);

        assertThat(failure).as("the required shared tier refused").isInstanceOf(StorageError.TierFull.class);
        assertThat(((StorageError.TierFull) failure).tier()).isEqualTo(TierLevel.REMOTE);
        assertThat(disk.exists(id).await().unwrap()).as("the write's local-disk copy was undone").isFalse();
        assertThat(store.getLifecycle(id).isPresent()).as("no record claims a block stored nowhere").isFalse();
    }

    @Test
    void theTierReportsThisNodesLiveBytesOfItsPrefix_andItsCap() {
        var client = new RefusingDHTClient(4_096L, 134_217_728L, false);
        var tier = DhtStorageTier.dhtStorageTier(client, PREFIX);

        assertThat(tier.usedBytes()).isEqualTo(4_096L);
        assertThat(tier.maxBytes()).isEqualTo(134_217_728L);
        assertThat(client.askedPrefix.get()).as("the tier asks for its own prefix").isEqualTo(PREFIX);
    }

    @Test
    void aClientWithNoLocalEngine_reportsToday() {
        var tier = DhtStorageTier.dhtStorageTier(new DhtStorageTierTest.InMemoryDHTClient(), "test-blocks");

        assertThat(tier.usedBytes()).isZero();
        assertThat(tier.maxBytes()).isEqualTo(Long.MAX_VALUE);
    }
}
