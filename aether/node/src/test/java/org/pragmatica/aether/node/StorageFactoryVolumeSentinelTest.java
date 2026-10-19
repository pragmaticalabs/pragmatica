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
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.storage.StorageTier;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// #1473: `StorageFactory` is where the intent is known -- whether the disk tier ARMED or degraded
/// to memory + DHT -- so it is the layer that hands the snapshot manager its volume sentinel. An
/// armed tier gets one in the node state directory (`volume-sentinels`); a degraded tier gets none and
/// still boots on a first boot, which keeps the ordinary "`/data` is not writable" boot (CI, laptops)
/// working -- but a degraded tier whose snapshot directory has a sentinel is the unmounted volume and
/// refuses.
class StorageFactoryVolumeSentinelTest {
    private static final String INSTANCE = "sentinel-vault";
    private static final String ROOT_PROPERTY = "aether.state.dir";

    @TempDir
    Path tempDir;

    private String previousRoot;
    private LogCapture appender;

    @BeforeEach
    void pointTheStateDirAtTheTestDirectory() {
        previousRoot = System.getProperty(ROOT_PROPERTY);
        System.setProperty(ROOT_PROPERTY, stateDir().toString());
        appender = LogCapture.capture(StorageFactory.class, Level.ALL, true);
    }

    @AfterEach
    void restoreStateDir() {
        appender.close();
        if (previousRoot == null) {
            System.clearProperty(ROOT_PROPERTY);
        } else {
            System.setProperty(ROOT_PROPERTY, previousRoot);
        }
    }

    private Path stateDir() {
        return tempDir.resolve("state");
    }

    private Path sentinelDir() {
        return stateDir().resolve("volume-sentinels");
    }

    private Path snapshotDir() {
        return tempDir.resolve("volume").resolve("metadata-snapshots");
    }

    private StorageConfig armedConfig() {
        return armedConfigAt(tempDir.resolve("volume"));
    }

    private StorageConfig armedConfigAt(Path volume) {
        return StorageConfig.storageConfig(8L * 1024 * 1024,
                                           64L * 1024 * 1024,
                                           volume.resolve("blocks").toString(),
                                           volume.resolve("metadata-snapshots").toString(),
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
        try (Stream<Path> files = Files.list(sentinelDir())) {
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
        assertThat(Files.exists(sentinelDir())).as("a degraded tier writes no sentinel")
                                                .isFalse();
    }

    @Test
    void createAll_refusesToBoot_whenTheDiskTierDegradedAfterTheVolumeWasWritten() throws IOException {
        boot(armedConfig()).unwrap().get(INSTANCE).snapshotManager().forceSnapshot();
        var blocker = tempDir.resolve("not-a-directory");

        Files.writeString(blocker, "no directory can be created beneath a regular file");

        var degradedOverTheSameSnapshots = StorageConfig.storageConfig(8L * 1024 * 1024,
                                                                       64L * 1024 * 1024,
                                                                       blocker.resolve("blocks").toString(),
                                                                       snapshotDir().toString(),
                                                                       1000,
                                                                       "60s",
                                                                       5,
                                                                       "",
                                                                       false);

        boot(degradedOverTheSameSnapshots).onSuccess(_ -> fail("a sentinel with no disk tier is an unmounted volume, not a first boot"))
                                          .onFailure(cause -> assertThat(cause.message()).contains(snapshotDir().toString())
                                                                                        .contains(sentinelPathOrFail().toString()));
    }

    @Test
    void createAll_keepsTwoVolumesApart_whenTheirPathsDifferOnlyBySeparatorVersusUnderscore() throws IOException {
        var underscore = armedConfigAt(tempDir.resolve("vol").resolve("a_b"));
        var nested = armedConfigAt(tempDir.resolve("vol").resolve("a").resolve("b"));

        boot(underscore).unwrap().get(INSTANCE).snapshotManager().forceSnapshot();
        boot(nested).unwrap().get(INSTANCE).snapshotManager().forceSnapshot();

        try (Stream<Path> sentinels = Files.list(sentinelDir())) {
            assertThat(sentinels.count()).as("one sentinel per volume").isEqualTo(2);
        }

        deleteChildren(tempDir.resolve("vol").resolve("a").resolve("b").resolve("metadata-snapshots"));

        boot(nested).onSuccess(_ -> fail("the unmounted nested volume must refuse"));
        boot(underscore).onFailure(cause -> fail("the other volume is mounted and must boot: " + cause.message()));
    }

    @Test
    void defaultStreamStorage_refusesToBoot_whenTheStreamsDiskTierDegradedAfterTheVolumeWasWritten() throws IOException {
        var streamDir = tempDir.resolve("stream-volume");

        StorageFactory.defaultStreamStorage(Option.none(), streamDir, "node-1473", Option.none())
                      .unwrap()
                      .snapshotManager()
                      .forceSnapshot();
        var segments = streamDir.resolve("segments");

        deleteChildren(segments);
        Files.delete(segments);
        Files.writeString(segments, "a regular file where the segments directory was: the disk tier cannot open");

        StorageFactory.defaultStreamStorage(Option.none(), streamDir, "node-1473", Option.none())
                      .onSuccess(_ -> fail("a sentinel with no streams disk tier is an unmounted volume, not a first boot"))
                      .onFailure(cause -> assertThat(cause.message()).contains(streamDir.resolve("snapshots").toString())
                                                                     .contains(sentinelPathOrFail().toString()));
    }

    @Test
    void createAll_refusesToBoot_whenTheWholeVolumeIsGoneAndTheSentinelRemains() throws IOException {
        boot(armedConfig()).unwrap().get(INSTANCE).snapshotManager().forceSnapshot();
        deleteChildren(snapshotDir());
        Files.delete(snapshotDir());
        deleteChildren(tempDir.resolve("volume").resolve("blocks"));

        boot(armedConfig()).onSuccess(_ -> fail("the volume directory is gone and the sentinel remains: not a first boot"))
                           .onFailure(cause -> assertThat(cause.message()).contains(snapshotDir().toString()));
    }

    @Test
    void createAll_warnsOnce_whenTheStateDirSharesAFilesystemWithTheGuardedVolume() {
        boot(armedConfig());

        var warns = warnsMentioning(snapshotDir().toString());

        assertThat(warns).hasSize(1);
        assertThat(warns.getFirst()).contains("INACTIVE").contains(stateDir().toString());
    }

    @Test
    void createAll_doesNotWarn_whenTheStateDirIsOnAnotherFilesystem() throws IOException {
        var elsewhere = Path.of("/dev/shm");

        // the precondition is read straight from the file stores, never from the function under test
        assumeTrue(Files.isDirectory(elsewhere) && !Files.getFileStore(elsewhere).equals(Files.getFileStore(tempDir)),
                   "needs a second filesystem (Linux /dev/shm)");
        var otherState = Files.createTempDirectory(elsewhere, "aether-state-1473");

        try {
            System.setProperty(ROOT_PROPERTY, otherState.toString());
            boot(armedConfig());

            assertThat(warnsMentioning(snapshotDir().toString())).isEmpty();
        } finally {
            deleteTree(otherState);
        }
    }

    @Test
    void onSameFilesystem_isTrue_withinOneTreeEvenBeforeTheDirectoryExists() {
        assertThat(StorageFactory.onSameFilesystem(tempDir, tempDir.resolve("not").resolve("yet"))).isTrue();
    }

    private java.util.List<String> warnsMentioning(String fragment) {
        return appender.messages(Level.WARN).stream().filter(w -> w.contains(fragment)).toList();
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    void sentinelFor_isDistinct_forPathsThatDifferOnlyBySeparatorVersusUnderscore() {
        var underscore = StorageFactory.sentinelFor(Path.of("/data/a_b")).unwrap();
        var nested = StorageFactory.sentinelFor(Path.of("/data/a/b")).unwrap();

        assertThat(underscore).isNotEqualTo(nested);
        assertThat(underscore.getFileName().toString()).endsWith("-a_b.id");
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
