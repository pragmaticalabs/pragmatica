package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
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

    /// The shared (DHT) tier has no path to deletion from a node's pass: an orphan collected early leaves its
    /// private copies and keeps the shared one, which other nodes may still reference (#250, #1604).
    @Test
    void earlyCollection_neverDeletesFromASharedTier() {
        var store = MetadataStore.inMemoryMetadataStore("gc-shared");
        var shared = new SharedMemoryTier();
        var privateTier = MemoryTier.memoryTier(1 << 20);
        var storage = StorageInstance.storageInstance("gc-shared", List.of(privateTier, shared), store);
        var snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir.resolve("shared"), "gc"));
        var collector = StorageGarbageCollector.storageGarbageCollector(storage, store, GarbageCollectorConfig.garbageCollectorConfig());
        var id = storage.putRef("a", bytes("shared block")).await().unwrap();

        collector.activate();
        storage.deleteRef("a").await();

        assertThat(collector.collectOrphansDurableIn(snapshots.snapshotNow().unwrap())).isEqualTo(1);
        assertThat(shared.exists(id).await().unwrap()).as("the shared copy survives").isTrue();
        assertThat(privateTier.exists(id).await().unwrap()).as("the private copy is collected").isFalse();
    }

    /// A memory tier that reports itself cluster-shared, as the DHT tier does.
    private static final class SharedMemoryTier implements StorageTier {
        private final MemoryTier delegate = MemoryTier.memoryTier(1 << 20, TierLevel.REMOTE);

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return delegate.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return delegate.level();
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }

        @Override
        public boolean isShared() {
            return true;
        }
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
