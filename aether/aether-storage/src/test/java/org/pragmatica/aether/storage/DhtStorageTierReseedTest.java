// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;

/// G2 (#1570 A2b) through the real [DhtStorageTier]: a whole-cluster restart empties the in-memory DHT while the block's
/// record and its disk copy survive. The DHT keeps no write times, so it refuses `touch`; `ensureHeld` must still put the
/// block back from the disk copy. Before the fix it failed with `OperationUnsupported[touch]` and wrote nothing (v2829 FIX 1).
class DhtStorageTierReseedTest {
    private static final byte[] CONTENT = "a block the restart dropped".getBytes(StandardCharsets.UTF_8);
    private static final String PREFIX = "artifacts";

    @TempDir
    Path dir;

    private LocalDiskTier disk;
    private MetadataStore store;
    private BlockId id;

    @BeforeEach
    void setUp() {
        disk = LocalDiskTier.localDiskTier(dir, 1 << 20).flatMap(LocalDiskTier::open).unwrap();
        store = MetadataStore.inMemoryMetadataStore("artifacts");
        id = BlockId.blockId(CONTENT).unwrap();
    }

    @Test
    void reseedAfterARestartEmptiedTheDht_putsTheBlockBackFromTheDiskCopy() {
        var beforeRestart = DhtStorageTier.dhtStorageTier(new DhtStorageTierTest.InMemoryDHTClient(), PREFIX);

        StorageInstance.storageInstance("artifacts", List.of(disk, beforeRestart), store).put(CONTENT).await().unwrap();

        var emptied = DhtStorageTier.dhtStorageTier(new DhtStorageTierTest.InMemoryDHTClient(), PREFIX);
        var node = StorageInstance.storageInstance("artifacts", List.of(disk, emptied), store);

        node.put(CONTENT).await().unwrap();
        assertThat(emptied.exists(id).await().unwrap()).as("precondition: a put of the recorded block writes nothing")
                                                       .isFalse();

        node.ensureHeld(id, TierLevel.REMOTE).await().unwrap();

        assertThat(emptied.get(id).await().unwrap().unwrap()).as("the re-seed reached the DHT").isEqualTo(CONTENT);
        assertThat(store.getLifecycle(id).unwrap().presentIn()).contains(TierLevel.REMOTE);
    }

    /// The DHT still refuses `touch` itself: `ensureHeld` writes it instead, so `touch` keeps one meaning on every tier,
    /// "refresh a held block, never create one".
    @Test
    void theDhtStillRefusesTouch() {
        var dht = DhtStorageTier.dhtStorageTier(new DhtStorageTierTest.InMemoryDHTClient(), PREFIX);

        assertThat(dht.touch(id).await().<Cause>fold(cause -> cause, _ -> null))
            .isInstanceOf(StorageError.OperationUnsupported.class);
    }

    @Test
    void reseedOfABlockNoOtherTierHolds_failsWithBlockNotFound_andWritesNothing() {
        var dht = DhtStorageTier.dhtStorageTier(new DhtStorageTierTest.InMemoryDHTClient(), PREFIX);
        var node = StorageInstance.storageInstance("artifacts", List.of(disk, dht), store);

        assertThat(node.ensureHeld(id, TierLevel.REMOTE).await().<Cause>fold(cause -> cause, _ -> null))
            .isInstanceOf(StorageError.BlockNotFound.class);
        assertThat(dht.exists(id).await().unwrap()).isFalse();
    }
}
