package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.StorageGarbageCollector.SharedDeleteLimits;

import static org.assertj.core.api.Assertions.assertThat;

/// A failure/crash between the shared-tier delete and the private-tier delete.
class CrashBetweenTiersTest {
    private static final SharedDeleteLimits NO_GRACE = new SharedDeleteLimits(0L, 8, 10_000, 60_000L);

    @TempDir
    Path dir;

    /// Shared delete succeeded, private delete fails, then the process "dies" (all in-memory state lost): the durable snapshot
    /// taken before the delete restores the flagged orphan, and the next run completes both tiers. The ref is gone throughout.
    @Test
    void sharedDeleted_privateFails_thenRestart_theNextRunCompletes_andNoRefEverNamesAHalfDeletedBlock() {
        var shared = new Tier(TierLevel.REMOTE, true);
        var priv = new Tier(TierLevel.LOCAL_DISK, false);
        var store = MetadataStore.inMemoryMetadataStore("streams");
        var storage = StorageInstance.storageInstance("streams", List.of(priv, shared), store);
        var snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir.resolve("snapshots"), "streams"));
        var collector = StorageGarbageCollector.storageGarbageCollector(storage, store, GarbageCollectorConfig.garbageCollectorConfig());

        collector.activate();

        var id = storage.putRef("a", bytes("half deleted")).await().unwrap();

        storage.deleteRef("a", SharedDelete.AUTHORISED).await();
        priv.failDeletes.set(true);

        var crashed = collector.collectSharedDeletes(snapshots, NO_GRACE);

        assertThat(crashed.failed()).isEqualTo(1);
        assertThat(shared.holds(id)).as("shared copy already gone").isFalse();
        assertThat(priv.holds(id)).as("private copy still there").isTrue();
        assertThat(storage.resolveRef("a").isPresent()).as("no ref names it").isFalse();

        // process death: a new store restored from the snapshot on disk, a new instance over the same tiers
        var restored = snapshots.restoreFromLatest().unwrap().unwrap();
        var store2 = MetadataStore.inMemoryMetadataStore("streams");

        store2.restoreLifecycles(restored.lifecycles());
        store2.restoreRefs(restored.refs());
        priv.failDeletes.set(false);

        var storage2 = StorageInstance.storageInstance("streams", List.of(priv, shared), store2);
        var snapshots2 = SnapshotManager.snapshotManager(store2, SnapshotConfig.snapshotConfig(dir.resolve("snapshots2"), "streams"));
        var collector2 = StorageGarbageCollector.storageGarbageCollector(storage2, store2, GarbageCollectorConfig.garbageCollectorConfig());

        collector2.activate();
        assertThat(store2.getLifecycle(id).unwrap().sharedDelete()).as("authority survived the restart").isEqualTo(SharedDelete.AUTHORISED);

        var next = collector2.collectSharedDeletes(snapshots2, NO_GRACE);

        assertThat(next.deleted()).isEqualTo(1);
        assertThat(priv.holds(id)).isFalse();
        assertThat(store2.getLifecycle(id).isPresent()).isFalse();
    }

    /// An identical put that arrives while the delete holds its claim waits for the delete to finish and then writes the block
    /// again: the new reference stands on readable content (a put that did not wait would be wiped by the delete in flight).
    @Test
    void anIdenticalPutDuringTheClaim_waitsForTheDelete_andLeavesTheNewRefOnReadableContent() throws Exception {
        var shared = new Tier(TierLevel.REMOTE, true);
        var priv = new Tier(TierLevel.LOCAL_DISK, false);
        var store = MetadataStore.inMemoryMetadataStore("streams");
        var storage = StorageInstance.storageInstance("streams", List.of(priv, shared), store);
        var snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir.resolve("snapshots"), "streams"));
        var collector = StorageGarbageCollector.storageGarbageCollector(storage, store, GarbageCollectorConfig.garbageCollectorConfig());

        collector.activate();

        var id = storage.putRef("a", bytes("raced")).await().unwrap();

        storage.deleteRef("a", SharedDelete.AUTHORISED).await();
        priv.hold = new CountDownLatch(1);

        var run = new Thread(() -> collector.collectSharedDeletes(snapshots, NO_GRACE));

        run.start();
        priv.reached.await();

        var put = storage.putRef("b", bytes("raced"));

        Thread.sleep(200);
        priv.hold.countDown();
        run.join();

        var id2 = put.await().unwrap();
        var lc = store.getLifecycle(id).unwrap();

        assertThat(id2).as("same content, same block").isEqualTo(id);
        assertThat(storage.resolveRef("b").unwrap()).isEqualTo(id);
        assertThat(storage.get(id).await().unwrap().isPresent()).as("the new reference stands on readable content").isTrue();
        assertThat(lc.refCount()).isEqualTo(1);
        assertThat(lc.sharedDelete()).as("the put revived the block: no authority left").isEqualTo(SharedDelete.NONE);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Tier implements StorageTier {
        private final MemoryTier backing;
        private final boolean shared;
        final AtomicBoolean failDeletes = new AtomicBoolean();
        volatile CountDownLatch hold;
        final CountDownLatch reached = new CountDownLatch(1);

        Tier(TierLevel level, boolean shared) {
            this.backing = MemoryTier.memoryTier(1L << 30, level);
            this.shared = shared;
        }

        boolean holds(BlockId id) {
            return backing.exists(id).await().unwrap();
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return backing.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return backing.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            if (hold != null) {
                reached.countDown();
                try { hold.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }

            return failDeletes.get()
                   ? Causes.cause("tier delete failed").promise()
                   : backing.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return backing.exists(id);
        }

        @Override
        public TierLevel level() {
            return backing.level();
        }

        @Override
        public long usedBytes() {
            return backing.usedBytes();
        }

        @Override
        public long maxBytes() {
            return backing.maxBytes();
        }

        @Override
        public boolean isShared() {
            return shared;
        }
    }
}
