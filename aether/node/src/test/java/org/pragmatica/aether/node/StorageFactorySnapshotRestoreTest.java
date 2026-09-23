// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockLifecycle;
import org.pragmatica.storage.ReadinessState;
import org.pragmatica.storage.TierLevel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1013: `StorageFactory.restoreAndSignalReady` signalled `snapshotLoaded()` UNCONDITIONALLY, so a
/// node whose snapshot was torn, whose `LATEST` dangled, or whose directory held only unreadable
/// files came up read-ready with EMPTY metadata, indistinguishable from a first boot. Three outcomes
/// have to stay apart, each with its own guarantee:
///
/// - **absent** -- no `LATEST` and no `snapshot-*.dat` on disk, established by LOOKING, never inferred
///   from a read that failed: readiness is legitimate, metadata starts empty;
/// - **failed** -- something is on disk and nothing restores: `createAll` REFUSES, naming the instance
///   and the cause. Readiness is never signalled, and the half-built setup (gate included) is
///   discarded as `AetherNode` aborts the boot -- so the assertion below is on the REFUSAL, not on a
///   gate left in `LOADING_SNAPSHOT` for someone to read; no node survives to expose one;
/// - **restored** -- readiness is signalled with the snapshot applied. #1353's fallback to an older
///   retained snapshot counts as restored (WARNed by the manager), not as failed.
///
/// Every test goes through `createAll` (or the `streams` entry `defaultStreamStorage`), the only
/// reachable callers of the private `restoreAndSignalReady`. A "restart" is a second boot over the
/// same directory: a fresh `MetadataStore` that can only learn its state from disk.
class StorageFactorySnapshotRestoreTest {
    private static final String INSTANCE = "restore-vault";
    private static final String NODE_ID = "node-1013";
    private static final long MEMORY_MAX_BYTES = 8L * 1024 * 1024;
    private static final long DISK_MAX_BYTES = 64L * 1024 * 1024;

    @TempDir
    Path tempDir;

    // --- (b) something on disk, nothing restores: refuse ---
    /// The file `LATEST` names is torn and it is the only snapshot. Before #1013 the boot logged a
    /// WARN, restored nothing and signalled read readiness anyway.
    @Test
    void createAll_onlySnapshotTorn_refusesBootAndNamesTheInstance() {
        bootAndSnapshot(3);
        truncateToHalf(latestTarget());
        assertRefused(boot(), "LATEST");
    }

    /// `LATEST` names a file that is not there (the shape #1012's prune left behind).
    @Test
    void createAll_latestPointerDangles_refusesBoot() {
        bootAndSnapshot(3);
        FileOps.delete(latestTarget()).unwrap();
        assertRefused(boot(), "LATEST");
    }

    /// No `LATEST` at all, but the directory holds a snapshot file and it is torn. Before #1013 this
    /// was the quietest failure of all: the fallback tried the file, restored nothing, and did not even
    /// WARN, because the WARN was keyed on `LATEST` being present. A snapshot file on disk is evidence
    /// that metadata existed; it must not read as a first boot.
    @Test
    void createAll_noLatestAndOnlyTornSnapshot_refusesBoot() {
        bootAndSnapshot(3);
        var target = latestTarget();

        FileOps.delete(snapshotDir().resolve("LATEST")).unwrap();
        truncateToHalf(target);
        assertRefused(boot(), "retained snapshot");
    }

    /// The same defect on the `streams` instance, which is assembled by a separate method
    /// (`assembleStreamSetup`) with its own call to `restoreAndSignalReady`.
    @Test
    void defaultStreamStorage_onlySnapshotTorn_refusesBoot() {
        var streamDataDir = tempDir.resolve("streams");
        var first = StorageFactory.defaultStreamStorage(Option.none(),
                                                        streamDataDir,
                                                        NODE_ID,
                                                        Option.none())
                                  .onFailure(cause -> fail("first streams boot must succeed: " + cause.message()))
                                  .unwrap();

        first.metadataStore().createLifecycle(lifecycleOf("stream-ref"));
        first.snapshotManager().forceSnapshot();
        var snapshots = streamDataDir.resolve("snapshots");

        truncateToHalf(snapshots.resolve(FileOps.readString(snapshots.resolve("LATEST")).unwrap().trim()));
        assertRefused(StorageFactory.defaultStreamStorage(Option.none(),
                                                          streamDataDir,
                                                          NODE_ID,
                                                          Option.none())
                                    .map(setup -> Map.of(StorageFactory.STREAMS_NAME, setup)),
                      "LATEST");
    }

    // --- (a) nothing on disk: readiness with empty metadata ---
    /// A first boot: the snapshot directory does not exist yet.
    @Test
    void createAll_noSnapshotDirectory_signalsReadinessWithEmptyMetadata() {
        assertThat(snapshotDir()).doesNotExist();
        var setup = bootExpectingSuccess();

        assertReadReady(setup);
        assertThat(setup.metadataStore().listAllLifecycles()).isEmpty();
    }

    /// The directory exists (a previous boot created it) but holds neither `LATEST` nor a snapshot:
    /// still absent, still a legitimate readiness.
    @Test
    void createAll_emptySnapshotDirectory_signalsReadiness() {
        FileOps.createDirectories(snapshotDir()).unwrap();
        assertReadReady(bootExpectingSuccess());
    }

    // --- (c) restored ---
    @Test
    void createAll_completeSnapshot_restoresAndSignalsReadiness() {
        bootAndSnapshot(3);
        var setup = bootExpectingSuccess();

        assertReadReady(setup);
        assertThat(setup.metadataStore().listAllLifecycles()).hasSize(3);
    }

    /// #1353's fallback is a RESTORE, not a failure: the newest snapshot is torn but an older complete
    /// one is retained, so the boot proceeds on the older state (the manager WARNs which file it used).
    @Test
    void createAll_newestTornButOlderRetained_restoresOlderAndSignalsReadiness() {
        var first = bootAndSnapshot(2);

        first.metadataStore().createLifecycle(lifecycleOf("after-first-snapshot"));
        first.snapshotManager().forceSnapshot();
        truncateToHalf(latestTarget());
        var setup = bootExpectingSuccess();

        assertReadReady(setup);
        assertThat(setup.metadataStore().listAllLifecycles()).as("the older retained snapshot's two lifecycles")
                  .hasSize(2);
    }

    // --- Helpers ---
    private StorageFactory.StorageSetup bootAndSnapshot(int lifecycles) {
        var setup = bootExpectingSuccess();

        for (var i = 0; i < lifecycles; i++) {
            setup.metadataStore().createLifecycle(lifecycleOf("lifecycle-" + i));
        }

        setup.snapshotManager().forceSnapshot();
        // Fixture control: a snapshot that never reached disk would leave nothing to corrupt, and the
        // refusal assertions below would fail for the wrong reason.
        assertThat(latestTarget()).exists();

        return setup;
    }

    private Result<Map<String, StorageFactory.StorageSetup>> boot() {
        return StorageFactory.createAll(Map.of(INSTANCE, instanceConfig()),
                                        NODE_ID,
                                        Option.none(),
                                        Option.none(),
                                        HermeticStorage.synthesisDefaultsIn(tempDir));
    }

    private StorageFactory.StorageSetup bootExpectingSuccess() {
        var setups = boot().onFailure(cause -> fail("createAll must succeed: " + cause.message())).unwrap();

        assertThat(setups).containsKey(INSTANCE);

        return setups.get(INSTANCE);
    }

    private static void assertRefused(Result<Map<String, StorageFactory.StorageSetup>> boot, String expectedInCause) {
        boot.onSuccess(setups -> fail("createAll must refuse the boot, but succeeded with readiness " + setups.values()
                                                                                                              .stream()
                                                                                                              .map(setup -> setup.name()
                                                                                                                           + "=" + setup.readinessGate()
                                                                                                                                        .state()
                                                                                                                           + "/isReadReady=" + setup.readinessGate()
                                                                                                                                                    .isReadReady())
                                                                                                              .toList()))
            .onFailure(cause -> assertThat(cause.message()).contains(expectedInCause));
    }

    private static void assertReadReady(StorageFactory.StorageSetup setup) {
        assertThat(setup.readinessGate().state()).isEqualTo(ReadinessState.SNAPSHOT_LOADED);
        assertThat(setup.readinessGate().isReadReady()).isTrue();
    }

    private Path snapshotDir() {
        return tempDir.resolve("snapshots");
    }

    private Path latestTarget() {
        return snapshotDir().resolve(FileOps.readString(snapshotDir().resolve("LATEST")).unwrap().trim());
    }

    private static void truncateToHalf(Path file) {
        var bytes = FileOps.readBytes(file).unwrap();

        assertThat(bytes.length).as("a file worth tearing").isGreaterThan(1);
        FileOps.writeBytes(file, Arrays.copyOf(bytes, bytes.length / 2)).unwrap();
    }

    private StorageConfig instanceConfig() {
        return StorageConfig.storageConfig(MEMORY_MAX_BYTES,
                                           DISK_MAX_BYTES,
                                           tempDir.resolve("disk").toString(),
                                           snapshotDir().toString(),
                                           1000,
                                           "60s",
                                           5,
                                           "",
                                           false);
    }

    private static BlockLifecycle lifecycleOf(String tag) {
        return BlockLifecycle.blockLifecycle(BlockId.blockId(tag.getBytes(StandardCharsets.UTF_8)).unwrap(),
                                             TierLevel.MEMORY);
    }
}
