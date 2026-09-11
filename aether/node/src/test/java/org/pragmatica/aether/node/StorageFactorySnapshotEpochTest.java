// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockLifecycle;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1012: `StorageFactory.applySnapshot` reads the restored snapshot's epoch and used it for a log
/// line only -- `restoreLifecycles`/`restoreRefs` merely INCREMENT a store that starts at zero, so
/// the mutation epoch restarted near zero on every boot. Since `DefaultSnapshotManager` derives the
/// snapshot file name from that epoch, the next snapshot was written UNDER every retained
/// predecessor and became the prune's first victim -- while `LATEST` had just been pointed at it.
///
/// Everything here is behavioural and goes through the real `createAll` boot path, which is the only
/// reachable caller of the private `applySnapshot`. The restart is a second `createAll` over the same
/// snapshot directory: a fresh `MetadataStore` that can only learn the epoch from disk.
class StorageFactorySnapshotEpochTest {

    private static final String INSTANCE = "epoch-vault";
    private static final String NODE_ID = "node-1012";
    private static final long MEMORY_MAX_BYTES = 8L * 1024 * 1024;
    private static final long DISK_MAX_BYTES = 64L * 1024 * 1024;
    private static final int MUTATIONS_BEFORE_RESTART = 6;

    @TempDir
    Path tempDir;

    @Test
    void createAll_afterRestart_continuesSnapshotEpochFromDisk() {
        var firstBoot = bootInstance();

        for (var i = 0; i < MUTATIONS_BEFORE_RESTART; i++) {
            firstBoot.metadataStore().createLifecycle(lifecycleOf("pre-restart-" + i));
        }

        firstBoot.snapshotManager().forceSnapshot();

        var epochOnDisk = firstBoot.snapshotManager().lastSnapshotEpoch();

        // Fixture control: a snapshot that never reached disk leaves this at zero, so the two
        // assertions below would compare against nothing and pass vacuously.
        assertThat(epochOnDisk).isEqualTo(MUTATIONS_BEFORE_RESTART);

        var secondBoot = bootInstance();

        assertThat(secondBoot.metadataStore().currentEpoch()).isGreaterThanOrEqualTo(epochOnDisk);

        secondBoot.metadataStore().createLifecycle(lifecycleOf("post-restart"));
        secondBoot.snapshotManager().forceSnapshot();

        assertThat(secondBoot.snapshotManager().lastSnapshotEpoch()).isGreaterThan(epochOnDisk);
    }

    private StorageFactory.StorageSetup bootInstance() {
        var setups = StorageFactory.createAll(Map.of(INSTANCE, instanceConfig()), NODE_ID, Option.none(), Option.none())
                                   .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                                   .unwrap();

        assertThat(setups).containsKey(INSTANCE);

        return setups.get(INSTANCE);
    }

    private StorageConfig instanceConfig() {
        return StorageConfig.storageConfig(MEMORY_MAX_BYTES,
                                           DISK_MAX_BYTES,
                                           tempDir.resolve("disk").toString(),
                                           tempDir.resolve("snapshots").toString(),
                                           1000,
                                           "60s",
                                           5,
                                           "",
                                           false);
    }

    private static BlockLifecycle lifecycleOf(String tag) {
        return BlockLifecycle.blockLifecycle(BlockId.blockId(tag.getBytes(StandardCharsets.UTF_8)).unwrap(), TierLevel.MEMORY);
    }
}
