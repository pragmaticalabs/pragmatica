package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #1604: under disk pressure, garbage collection may skip its grace period only for orphans whose ref drop a
/// metadata snapshot already on disk records -- an ORDER bound on the snapshot's content, no clock compared.
class DurableOrphanCollectionTest {
    @TempDir
    Path dir;

    @Test
    void collectsExactlyTheOrphansTheSnapshotRecords_andStillOrphanedNow() {
        var store = MetadataStore.inMemoryMetadataStore("gc");
        var storage = StorageInstance.storageInstance("gc", List.of(MemoryTier.memoryTier(1 << 20)), store);
        var snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir, "gc"));
        var collector = StorageGarbageCollector.storageGarbageCollector(storage, store, GarbageCollectorConfig.garbageCollectorConfig());
        var durablyDropped = storage.putRef("a", bytes("durably dropped")).await().unwrap();
        var droppedAfter = storage.putRef("b", bytes("dropped after the snapshot")).await().unwrap();
        var reReferenced = storage.putRef("c", bytes("re-referenced")).await().unwrap();

        collector.activate();
        storage.deleteRef("a").await();
        storage.deleteRef("c").await();

        var durable = snapshots.snapshotNow().unwrap();

        storage.deleteRef("b").await();
        storage.createRef("c2", reReferenced).await();

        assertThat(collector.collectOrphansDurableIn(durable)).isEqualTo(1);
        assertThat(store.getLifecycle(durablyDropped).isPresent()).as("orphaned in the snapshot and now").isFalse();
        assertThat(store.getLifecycle(droppedAfter).isPresent()).as("its drop is not durable yet").isTrue();
        assertThat(store.getLifecycle(reReferenced).isPresent()).as("referenced again").isTrue();
    }

    /// `snapshotNow` returns what it wrote -- or a failure, when nothing reached disk, so nothing acts on it.
    @Test
    void snapshotNow_failsWhenTheSnapshotCannotBeWritten() throws Exception {
        var store = MetadataStore.inMemoryMetadataStore("gc");
        var snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir.resolve("snaps"), "gc"));

        assertThat(snapshots.snapshotNow().isSuccess()).isTrue();

        Files.setPosixFilePermissions(dir.resolve("snaps"), PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(snapshots.snapshotNow().isFailure()).isTrue();
        } finally {
            Files.setPosixFilePermissions(dir.resolve("snaps"), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
