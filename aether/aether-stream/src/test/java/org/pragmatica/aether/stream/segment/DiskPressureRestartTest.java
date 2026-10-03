// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.SnapshotConfig;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.GarbageCollectorConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1604, the verifier's P4: a node's durable segment tier fills with segments sealed before a restart. Before,
/// they had no age after the restart and were never reclaimed, every seal failed with TierFull, and the node
/// wedged. Now retention learns their age from their blocks, drops their refs, and -- under pressure -- makes
/// the drops durable and collects the blocks in the same pass, so seals resume with no manual collection.
class DiskPressureRestartTest {
    private static final long WINDOW_MS = 60 * 60 * 1000L;
    private static final int SEGMENTS = 24;
    private static final int PAYLOAD = 1000;
    private static final long CAP = 25_000L;

    @TempDir
    Path dir;

    @Test
    void afterRestart_aFullTierOfPreRestartSegments_isReclaimedByAge_andSealsResume() {
        var oldEvents = System.currentTimeMillis() - 2 * WINDOW_MS;
        var before = life("before");

        fillWithSegments(before, oldEvents);
        assertThat(sealOne(before, SEGMENTS, System.currentTimeMillis()).isFailure()).as("wedged: the tier is full").isTrue();
        assertThat(before.pressure().utilization()).isGreaterThanOrEqualTo(SegmentTierPressure.REFUSE_AT);
        before.snapshots().forceSnapshot();

        var after = restarted("after");
        var removed = after.enforcer().enforceNow().await().unwrap();

        assertThat(removed).as("every pre-restart segment aged out by its events' time").isEqualTo(SEGMENTS);
        assertThat(after.pressure().utilization()).as("their blocks were collected in the same pass").isLessThan(SegmentTierPressure.WARN_AT);
        assertThat(sealOne(after, SEGMENTS, System.currentTimeMillis()).isSuccess()).as("seals resume").isTrue();
    }

    /// A reader holding refs retention has since dropped and collected gets a TYPED failure -- never an
    /// exception, never a hang.
    @Test
    void aReadRacingAPressurePass_failsTyped() {
        var before = life("before");

        fillWithSegments(before, System.currentTimeMillis() - 2 * WINDOW_MS);
        before.snapshots().forceSnapshot();

        var after = restarted("after");
        var staleView = new SegmentIndex();

        staleView.rebuildFromRefs(after.store());
        after.enforcer().enforceNow().await().unwrap();

        var read = SegmentReader.segmentReader(after.storage(), staleView)
                                .readEvents("orders", 0, 0, 1)
                                .await(TimeSpan.timeSpan(5).seconds());

        assertThat(read.isFailure()).as("the read settles, and fails").isTrue();
        read.onFailure(cause -> assertThat(cause).isInstanceOf(SegmentError.General.class));
    }

    /// Crash between dropping refs and collecting: the forced snapshot cannot be written, so nothing is
    /// collected early. A restart from the last snapshot on disk -- which still names every ref -- finds every
    /// block it names: no ref dangles.
    @Test
    void whenTheForcedSnapshotFails_nothingIsCollectedEarly_andNoRefDanglesAfterRestart() throws Exception {
        var before = life("before");

        fillWithSegments(before, System.currentTimeMillis() - 2 * WINDOW_MS);
        before.snapshots().forceSnapshot();

        var after = restarted("after");

        Files.setPosixFilePermissions(dir.resolve("snapshots"), PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            after.enforcer().enforceNow().await().unwrap();
        } finally {
            Files.setPosixFilePermissions(dir.resolve("snapshots"), PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        var crashed = restarted("crashed");

        assertThat(crashed.store().listAllRefs()).as("the last durable snapshot still names the refs").hasSize(SEGMENTS);
        crashed.store()
               .listAllRefs()
               .forEach((name, id) -> assertThat(crashed.storage().get(id).await().unwrap().isPresent())
                                         .as("ref %s names a block that exists", name)
                                         .isTrue());
    }

    private Life life(String name) {
        var store = MetadataStore.inMemoryMetadataStore(name);

        return assemble(name, store);
    }

    /// A restart: a fresh store restored from the latest snapshot on disk, the same disk directory re-opened,
    /// the index rebuilt from ref names (so every timestamp is unknown).
    private Life restarted(String name) {
        var store = MetadataStore.inMemoryMetadataStore(name);
        var restore = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir.resolve("snapshots"), name))
                                     .restoreFromLatest()
                                     .unwrap()
                                     .unwrap();

        store.restoreLifecycles(restore.lifecycles());
        store.restoreRefs(restore.refs());
        store.restoreEpoch(restore.epoch());

        return assemble(name, store);
    }

    private Life assemble(String name, MetadataStore store) {
        var disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), CAP).unwrap();
        var storage = StorageInstance.storageInstance(name, List.of(MemoryTier.memoryTier(1 << 20), disk), store);
        var snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir.resolve("snapshots"), name));
        var collector = StorageGarbageCollector.storageGarbageCollector(storage, store, GarbageCollectorConfig.garbageCollectorConfig());
        var index = new SegmentIndex();
        var pressure = SegmentTierPressure.localDiskOf(storage);

        collector.activate();
        index.rebuildFromRefs(store);

        var enforcer = RetentionEnforcer.retentionEnforcer(storage,
                                                           index,
                                                           WINDOW_MS,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           RefDurability.snapshotted(snapshots),
                                                           SegmentReader.segmentReader(storage, index),
                                                           pressure,
                                                           PressureRelief.snapshotBounded(snapshots, collector));

        return new Life(store, storage, snapshots, index, pressure, enforcer);
    }

    private static void fillWithSegments(Life life, long eventTime) {
        for (var i = 0; i < SEGMENTS; i++) {
            var segment = i;

            sealOne(life, segment, eventTime).onFailure(cause -> fail("segment " + segment + " did not fit: " + cause.message()));
        }
    }

    private static Result<Unit> sealOne(Life life, int offset, long eventTime) {
        var payload = new byte[PAYLOAD];

        payload[0] = (byte) offset;
        payload[1] = (byte) (offset >> 8);

        var bytes = ByteBuffer.allocate(20 + PAYLOAD).order(ByteOrder.BIG_ENDIAN)
                              .putLong(offset).putLong(eventTime).putInt(PAYLOAD).put(payload).array();

        return storageSegmentSink(life.storage(), life.index()).seal(sealedSegment("orders", 0, offset, offset, 1, eventTime, eventTime, bytes))
                                                                 .await();
    }

    private record Life(MetadataStore store,
                        StorageInstance storage,
                        SnapshotManager snapshots,
                        SegmentIndex index,
                        SegmentTierPressure pressure,
                        RetentionEnforcer enforcer) {}
}
