// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageTier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1473: `StorageFactory` is where the intent is known -- whether the disk tier ARMED or degraded
/// to memory + DHT -- so it is the layer that hands the snapshot manager its volume sentinel. An
/// armed tier gets one under the node's state directory; a degraded tier gets none, which is what
/// keeps the ordinary "`/data` is not writable" boot (CI, laptops) booting on empty metadata.
class StorageFactoryVolumeSentinelTest {
    private static final String INSTANCE = "sentinel-vault";
    private static final String STATE_DIR_PROPERTY = "aether.state.dir";

    @TempDir
    Path tempDir;

    private String previousStateDir;

    @BeforeEach
    void pointStateDirAtTheTestDirectory() {
        previousStateDir = System.getProperty(STATE_DIR_PROPERTY);
        System.setProperty(STATE_DIR_PROPERTY, tempDir.resolve("state").toString());
    }

    @AfterEach
    void restoreStateDir() {
        if (previousStateDir == null) {
            System.clearProperty(STATE_DIR_PROPERTY);
        } else {
            System.setProperty(STATE_DIR_PROPERTY, previousStateDir);
        }
    }

    private Path snapshotDir() {
        return tempDir.resolve("volume").resolve("metadata-snapshots");
    }

    private StorageConfig armedConfig() {
        return StorageConfig.storageConfig(8L * 1024 * 1024,
                                           64L * 1024 * 1024,
                                           tempDir.resolve("volume").resolve("blocks").toString(),
                                           snapshotDir().toString(),
                                           1000,
                                           "60s",
                                           5,
                                           "",
                                           false);
    }

    private Result<Map<String, StorageFactory.StorageSetup>> boot(StorageConfig config) {
        return StorageFactory.createAll(Map.of(INSTANCE, config),
                                        "node-1473",
                                        Option.none(),
                                        Option.none(),
                                        HermeticStorage.synthesisDefaultsIn(tempDir));
    }

    private Path sentinelPath() throws IOException {
        try (Stream<Path> files = Files.list(tempDir.resolve("state").resolve("volume-sentinels"))) {
            return files.findFirst().orElseThrow();
        }
    }

    private void unmount() throws IOException {
        deleteChildren(snapshotDir());
    }

    private static void deleteChildren(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (var file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    @Test
    void createAll_refusesToBoot_whenAnArmedTierComesBackOverAnEmptyMountPoint() throws IOException {
        boot(armedConfig()).onFailure(cause -> fail("first boot must succeed: " + cause.message()))
                           .unwrap()
                           .get(INSTANCE)
                           .snapshotManager()
                           .forceSnapshot();
        unmount();

        boot(armedConfig()).onSuccess(_ -> fail("an armed tier over an unmounted volume must not boot on empty metadata"))
                           .onFailure(cause -> assertThat(cause.message()).contains(snapshotDir().toString())
                                                                         .contains(sentinelPathOrFail().toString()));
    }

    @Test
    void defaultStreamStorage_refusesToBoot_whenTheStreamsVolumeComesBackEmpty() throws IOException {
        var streamDir = tempDir.resolve("stream-volume");

        StorageFactory.defaultStreamStorage(Option.none(), streamDir, "node-1473", Option.none())
                      .onFailure(cause -> fail("first streams boot must succeed: " + cause.message()))
                      .unwrap()
                      .snapshotManager()
                      .forceSnapshot();
        deleteChildren(streamDir.resolve("snapshots"));

        StorageFactory.defaultStreamStorage(Option.none(), streamDir, "node-1473", Option.none())
                      .onSuccess(_ -> fail("the streams instance has a disk tier too; an unmounted volume must not boot empty"))
                      .onFailure(cause -> assertThat(cause.message()).contains(streamDir.resolve("snapshots").toString()));
    }

    @Test
    void createAll_boots_whenAnArmedTierIsBootedForTheFirstTime() {
        boot(armedConfig()).onFailure(cause -> fail("a first boot has no sentinel and must boot: " + cause.message()));
    }

    @Test
    void createAll_boots_whenTheRightVolumeIsRemounted() {
        boot(armedConfig()).unwrap().get(INSTANCE).snapshotManager().forceSnapshot();

        boot(armedConfig()).onFailure(cause -> fail("the volume written before must boot: " + cause.message()));
    }

    @Test
    void createAll_boots_whenTheDiskTierDegradedAndNothingWasEverWritten() throws IOException {
        var blocker = tempDir.resolve("not-a-directory");

        Files.writeString(blocker, "no directory can be created beneath a regular file");

        var degraded = StorageConfig.storageConfig(8L * 1024 * 1024,
                                                   64L * 1024 * 1024,
                                                   blocker.resolve("blocks").toString(),
                                                   blocker.resolve("snapshots").toString(),
                                                   1000,
                                                   "60s",
                                                   5,
                                                   "",
                                                   false);

        boot(degraded).onFailure(cause -> fail("a degraded tier has no volume to lose: " + cause.message()));
        assertThat(Files.exists(tempDir.resolve("state"))).as("a degraded tier writes no sentinel")
                                                          .isFalse();
    }

    @Test
    void volumeSentinel_isAbsent_forTiersWithoutALocalDisk() {
        List<StorageTier> tiers = List.of(MemoryTier.memoryTier(1024));

        assertThat(StorageFactory.volumeSentinel(tiers, snapshotDir()).isPresent()).isFalse();
    }

    private Path sentinelPathOrFail() {
        try {
            return sentinelPath();
        } catch (IOException e) {
            throw new AssertionError("the first boot's snapshot must have written a sentinel", e);
        }
    }
}
