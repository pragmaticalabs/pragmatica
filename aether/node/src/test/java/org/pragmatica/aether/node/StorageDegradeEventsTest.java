// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.DegradeReporter;
import org.pragmatica.storage.EncryptionKeyring;
import org.pragmatica.storage.PrefetchManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// A0b: the storage conditions that need an operator reach the operator-warning sink, not only the node log: the unmounted-volume
/// guard being inactive, a stream log fail-stopping, the streams collector and the prefetch manager failing to activate, and the
/// streams disk cap being larger than the space (or the space being unreadable). Each test reads what a bound sink was handed.
class StorageDegradeEventsTest {
    private static final String NODE = "node-events";
    private static final String STATE_DIR_PROPERTY = "aether.state.dir";
    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path tempDir;

    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final AtomicInteger barriers = new AtomicInteger();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(raised::add);
    private String previousStateDir;

    @BeforeEach
    void keepTheStateDir() {
        previousStateDir = System.getProperty(STATE_DIR_PROPERTY);
    }

    @AfterEach
    void restoreTheStateDir() {
        if (previousStateDir == null) {
            System.clearProperty(STATE_DIR_PROPERTY);
        } else {
            System.setProperty(STATE_DIR_PROPERTY, previousStateDir);
        }
    }

    private long raisedOf(OperatorWarningCode code, String subject) {
        return raised.stream().filter(warning -> warning.code() == code && warning.subject().equals(subject)).count();
    }

    /// Returns once everything handed to the sink before the call has been published: the hand-off is one FIFO worker.
    private void drain() throws InterruptedException {
        var subject = "barrier-" + barriers.incrementAndGet();
        var barrier = DegradeReporter.degradeReporter(subject);

        barrier.bind(sink);
        barrier.degraded(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED, "barrier");
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (raised.stream().noneMatch(warning -> warning.subject().equals(subject)) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(raised).extracting(OperatorWarning::subject).as("the barrier came out of the hand-off within 5 s").contains(subject);
    }

    private static StorageConfig volumeAt(Path root) {
        return HermeticStorage.storageConfigAt(root, false);
    }

    // --- integrity failures on read reach the instance's own reporter ---
    /// A built instance is handed its reporter by the factory: a block whose stored copy was damaged fails its read and leaves the
    /// condition open on the setup. The memory tier is cut to one byte so the read reaches the disk copy.
    @Test
    void builtInstance_whoseStoredBlockIsDamaged_reportsTheIntegrityFailureThroughItsSetup() throws Exception {
        var config = volumeAt(tempDir.resolve("volume")).withMemoryMaxBytes(1);
        var setups = StorageFactory.createAll(Map.of("vault", config),
                                              NODE,
                                              Option.none(),
                                              Option.none(),
                                              HermeticStorage.synthesisDefaultsIn(tempDir)).unwrap();

        try {
            var vault = setups.get("vault");
            var id = vault.instance().put("a block that will be damaged on disk".getBytes()).await().unwrap();
            var blocks = damageTheBlockFile(Path.of(config.diskPath()));

            assertThat(vault.instance().get(id).await().isFailure()).as("control: the damaged copy fails the read (%s)", blocks).isTrue();
            assertThat(vault.degrade().isDegraded(OperatorWarningCode.STORAGE_INTEGRITY_FAILED)).isTrue();
        } finally {
            setups.values().forEach(setup -> setup.instance().shutdown());
        }
    }

    /// The same for the `streams` instance, which is assembled by its own arm: a restarted instance has an empty memory ring, so the
    /// read reaches the damaged disk copy.
    @Test
    void streamsInstance_whoseStoredBlockIsDamaged_reportsTheIntegrityFailureThroughItsSetup() throws Exception {
        var dataDir = tempDir.resolve("streams");
        var first = StorageFactory.defaultStreamStorage(Option.none(), dataDir, NODE).unwrap();
        var id = first.instance().put("a stream segment that will be damaged on disk".getBytes()).await().unwrap();

        first.snapshotManager().forceSnapshot();
        first.instance().shutdown();
        var blocks = damageTheBlockFile(dataDir.resolve("segments"));
        var restarted = StorageFactory.defaultStreamStorage(Option.none(), dataDir, NODE).unwrap();

        try {
            assertThat(restarted.instance().get(id).await().isFailure()).as("control: the damaged copy fails the read (%s)", blocks).isTrue();
            assertThat(restarted.degrade().isDegraded(OperatorWarningCode.STORAGE_INTEGRITY_FAILED)).isTrue();
        } finally {
            restarted.instance().shutdown();
        }
    }

    private static List<Path> damageTheBlockFile(Path diskPath) throws java.io.IOException {
        try (var files = java.nio.file.Files.walk(diskPath)) {
            var blocks = files.filter(java.nio.file.Files::isRegularFile)
                              .filter(file -> !file.getFileName().toString().startsWith("."))
                              .toList();

            for (var block : blocks) {
                var bytes = java.nio.file.Files.readAllBytes(block);

                bytes[0] ^= 0x01;
                java.nio.file.Files.write(block, bytes);
            }

            return blocks;
        }
    }

    // --- (a) the unmounted-volume guard ---
    @Test
    void guardInactive_whenTheStateDirSharesTheVolumesFilesystem_raisesOneOperatorWarningForTheInstance() throws Exception {
        System.setProperty(STATE_DIR_PROPERTY, tempDir.resolve("state").toString());
        var setups = StorageFactory.createAll(Map.of("vault", volumeAt(tempDir.resolve("volume"))),
                                              NODE,
                                              Option.none(),
                                              Option.none(),
                                              HermeticStorage.synthesisDefaultsIn(tempDir)).unwrap();

        try {
            StorageFactory.bindOperatorWarnings(setups, sink);
            drain();

            assertThat(raisedOf(OperatorWarningCode.STORAGE_VOLUME_GUARD_INACTIVE, "vault")).isEqualTo(1);
            assertThat(raised.stream().filter(w -> w.code() == OperatorWarningCode.STORAGE_VOLUME_GUARD_INACTIVE).findFirst().orElseThrow().message())
                    .contains(tempDir.resolve("volume").toString()).contains(tempDir.resolve("state").toString());
            assertThat(raisedOf(OperatorWarningCode.STORAGE_VOLUME_GUARD_INACTIVE, "artifacts"))
                    .as("an instance with no disk tier has no guard to be inactive").isZero();
        } finally {
            setups.values().forEach(setup -> setup.instance().shutdown());
        }
    }

    // --- (b) a stream log fail-stopping ---
    @Test
    void streamsSink_whenALogFailStops_raisesTheFailStopWarningOncePerInstance() throws Exception {
        var degrade = DegradeReporter.degradeReporter("streams");
        var sinkOfStreams = StorageFactory.tornTailSinkOf(degrade);

        degrade.bind(sink);
        sinkOfStreams.failStopped(tempDir.resolve("orders-0.wal"), Causes.cause("fsync failed"));
        sinkOfStreams.failStopped(tempDir.resolve("orders-1.wal"), Causes.cause("fsync failed again"));
        drain();

        assertThat(raisedOf(OperatorWarningCode.STORAGE_WAL_FAIL_STOPPED, "streams")).isEqualTo(1);
        assertThat(raised.getFirst().message()).contains("orders-0.wal").contains("fsync failed");
    }

    // --- (c) the streams collector and the prefetch manager failing to activate ---
    @Test
    void streamsCollector_thatFailsToActivate_raisesTheMaintenanceWarningForStreams() throws Exception {
        var collector = mock(StorageGarbageCollector.class);
        var degrade = DegradeReporter.degradeReporter("streams");

        when(collector.activate()).thenReturn(Result.failure(Causes.cause("no scheduler")));
        degrade.bind(sink);

        var result = StorageFactory.activateStreamsCollector(collector, degrade);
        drain();

        assertThat(result.isFailure()).isTrue();
        assertThat(raisedOf(OperatorWarningCode.STORAGE_MAINTENANCE_NOT_STARTED, "streams")).isEqualTo(1);
        assertThat(raised.getFirst().message()).contains("no scheduler");
    }

    @Test
    void streamsInstance_whenBuilt_hasItsCollectorActive() {
        var streams = StorageFactory.defaultStreamStorage(Option.none(), tempDir.resolve("streams"), NODE).unwrap();

        try {
            assertThat(streams.garbageCollector().isActive()).as("the streams collector is activated when the instance is built").isTrue();
        } finally {
            streams.instance().shutdown();
        }
    }

    @Test
    void prefetchManager_thatFailsToActivate_raisesOncePerInstance_andItsRecoveryWhenALaterActivationWorks() throws Exception {
        var manager = mock(PrefetchManager.class);
        var degrade = DegradeReporter.degradeReporter("artifacts");
        var setups = Map.of("artifacts", new StorageFactory.StorageSetup("artifacts", null, null, null, null, null, null, Option.none(), Option.none(),
                                                                         org.pragmatica.storage.PromotionManager.NOOP, manager, degrade));

        when(manager.activate()).thenReturn(Result.failure(Causes.cause("gossip unavailable")));
        degrade.bind(sink);

        StorageFactory.activatePrefetch(setups);
        StorageFactory.activatePrefetch(setups);
        drain();
        assertThat(raisedOf(OperatorWarningCode.STORAGE_PREFETCH_NOT_STARTED, "artifacts")).isEqualTo(1);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_PREFETCH_STARTED, "artifacts")).as("no recovery while it is still failing").isZero();
        assertThat(raised.getFirst().message()).contains("gossip unavailable");

        when(manager.activate()).thenReturn(Result.success(Unit.unit()));
        StorageFactory.activatePrefetch(setups);
        drain();

        assertThat(raisedOf(OperatorWarningCode.STORAGE_PREFETCH_STARTED, "artifacts")).isEqualTo(1);
    }

    // --- (d) the streams disk cap against the space ---
    @Test
    void streamDiskCap_largerThanTheUsableSpace_isKeptAndRaisesTheOperatorWarning() throws Exception {
        var degrade = DegradeReporter.degradeReporter("streams");

        degrade.bind(sink);

        var cap = StorageFactory.streamDiskMaxBytes(10 * GIB, tempDir.resolve("segments"), _ -> Option.some(GIB), degrade);
        drain();

        assertThat(cap).as("the operator's cap is kept").isEqualTo(10 * GIB);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_STREAM_DISK_CAP_OVER_SPACE, "streams")).isEqualTo(1);
        assertThat(raised.getFirst().message()).contains(Long.toString(10 * GIB)).contains(Long.toString(GIB));
    }

    @Test
    void streamDiskCap_withinTheUsableSpace_raisesNothing() throws Exception {
        var degrade = DegradeReporter.degradeReporter("streams");

        degrade.bind(sink);
        StorageFactory.streamDiskMaxBytes(GIB, tempDir.resolve("segments"), _ -> Option.some(10 * GIB), degrade);
        drain();

        assertThat(raised.stream().filter(w -> w.subject().equals("streams"))).isEmpty();
    }

    @Test
    void streamDiskCap_whenTheUsableSpaceCannotBeRead_fallsBackToTheFloorAndRaisesTheOperatorWarning() throws Exception {
        var degrade = DegradeReporter.degradeReporter("streams");

        degrade.bind(sink);

        var cap = StorageFactory.streamDiskMaxBytes(StreamingConfig.DERIVE_SEGMENT_DISK_MAX_BYTES, tempDir.resolve("segments"), _ -> Option.none(), degrade);
        drain();

        assertThat(cap).isEqualTo(StorageFactory.derivedStreamDiskCap(0L));
        assertThat(raisedOf(OperatorWarningCode.STORAGE_STREAM_DISK_SPACE_UNREADABLE, "streams")).isEqualTo(1);
    }

    /// The reporter handed to the cap check is the instance's own: a cap larger than the real space, through the production entry
    /// point, leaves the condition open on the `streams` setup (plain and encrypted arm alike).
    @Test
    void streamsSetup_withACapLargerThanTheDisk_carriesTheCapWarningOnItsOwnReporter() throws Exception {
        var hugeCap = 1L << 60;
        var plain = streamsSetup(Option.none(), tempDir.resolve("plain"), hugeCap);
        var encrypted = streamsSetup(Option.some(keyring()), tempDir.resolve("encrypted"), hugeCap);

        assertThat(plain.degrade().isDegraded(OperatorWarningCode.STORAGE_STREAM_DISK_CAP_OVER_SPACE)).as("plain arm").isTrue();
        assertThat(encrypted.degrade().isDegraded(OperatorWarningCode.STORAGE_STREAM_DISK_CAP_OVER_SPACE)).as("encrypted arm").isTrue();
        plain.instance().shutdown();
        encrypted.instance().shutdown();
    }

    private StorageFactory.StorageSetup streamsSetup(Option<EncryptionKeyring> keyring, Path dataDir, long cap) {
        var request = new StorageFactory.StreamSetupRequest(Option.none(), dataDir, NODE, keyring, cap, Option.none());

        return StorageFactory.createAll(Map.of(),
                                        NODE + "-" + dataDir.getFileName(),
                                        Option.none(),
                                        keyring,
                                        request,
                                        HermeticStorage.synthesisDefaultsIn(tempDir),
                                        () -> 16 * GIB).unwrap().get("streams");
    }

    private static EncryptionKeyring keyring() {
        var key = new byte[32];

        new SecureRandom().nextBytes(key);

        return EncryptionKeyring.encryptionKeyring(Map.of("k1", BlockEncryptor.aesGcm(key, "k1").unwrap()), "k1").unwrap();
    }
}
