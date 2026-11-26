package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.StorageGarbageCollector.SharedDeleteLimits;

import static org.assertj.core.api.Assertions.assertThat;

/// #2258 (streams spec 3.2 items 5-6): a block whose last released reference carried shared-delete authority is deleted
/// from EVERY tier, the cluster-shared tier first, by its own collector run, and only once that authority is in a durable
/// metadata snapshot. Everything else keeps today's rule: private tiers only.
class SharedDeleteCollectionTest {
    private static final SharedDeleteLimits NO_GRACE = new SharedDeleteLimits(0L, 8, 10_000, 60_000L);

    @TempDir
    Path dir;

    private MetadataStore store;
    private LocalDiskTier disk;
    private FlakySharedTier shared;
    private StorageInstance storage;
    private SnapshotManager snapshots;
    private StorageGarbageCollector collector;

    @BeforeEach
    void setUp() {
        store = MetadataStore.inMemoryMetadataStore("streams");
        disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1L << 30).unwrap();
        shared = new FlakySharedTier();
        storage = StorageInstance.storageInstance("streams", List.of(disk, shared), store);
        snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir.resolve("snapshots"), "streams"));
        collector = StorageGarbageCollector.storageGarbageCollector(storage, store, GarbageCollectorConfig.garbageCollectorConfig());
        collector.activate();
    }

    /// NT-120 at the storage level: released with authority, the block leaves the shared tier and the private one, and its
    /// record goes; the periodic tick alone does not touch it (MT-60: the tick would delete privately and leak the DHT copy).
    @Test
    void anAuthorisedRelease_isDeletedFromEveryTier_byTheSharedRun_andNeverByTheTick() {
        var id = storage.putRef("streams/orders/0/0-9", bytes("segment")).await().unwrap();

        storage.deleteRef("streams/orders/0/0-9", SharedDelete.AUTHORISED).await();

        assertThat(store.getLifecycle(id).unwrap().sharedDelete()).isEqualTo(SharedDelete.AUTHORISED);
        assertThat(tickPastGrace()).as("the tick leaves an authorised orphan to the shared run").isZero();
        assertThat(store.getLifecycle(id).isPresent()).isTrue();

        var run = collector.collectSharedDeletes(snapshots, NO_GRACE);

        assertThat(run.deleted()).isEqualTo(1);
        assertThat(shared.holds(id)).as("the shared copy is gone").isFalse();
        assertThat(disk.exists(id).await().unwrap()).as("the private copy is gone").isFalse();
        assertThat(store.getLifecycle(id).isPresent()).as("the record goes once every tier succeeded").isFalse();
    }

    /// MT-47: the authority is acted on only once it is durable. A snapshot taken before the release is the durable state a
    /// restart would come back to, with the ref and the block both there, so the run must not delete.
    @Test
    void anAuthorityNotYetInTheDurableSnapshot_isNotActedOn() {
        var id = storage.putRef("a", bytes("not yet durable")).await().unwrap();
        var stale = snapshots.snapshotNow().unwrap();

        storage.deleteRef("a", SharedDelete.AUTHORISED).await();

        var run = collector.collectSharedDeletes(fixed(stale), NO_GRACE);

        assertThat(run.deleted()).isZero();
        assertThat(run.deferred()).isEqualTo(1);
        assertThat(shared.holds(id)).isTrue();
        assertThat(store.getLifecycle(id).isPresent()).isTrue();
    }

    @Test
    void anOrphanInsideTheGrace_isDeferred() {
        var id = storage.putRef("a", bytes("within the grace")).await().unwrap();

        storage.deleteRef("a", SharedDelete.AUTHORISED).await();

        assertThat(collector.collectSharedDeletes(snapshots, SharedDeleteLimits.DEFAULT).deleted()).isZero();
        assertThat(shared.holds(id)).isTrue();
    }

    /// MT-61: a re-credit (a deduplicating put of the same bytes) resets the authority, so a block referenced again is never
    /// deleted on the word of the release that came before; its next release without authority goes the private way.
    @Test
    void aReCredit_clearsTheAuthority_andALaterReleaseWithoutItKeepsTheSharedCopy() {
        var id = storage.putRef("a", bytes("re-credited")).await().unwrap();

        storage.deleteRef("a", SharedDelete.AUTHORISED).await();
        storage.putRef("b", bytes("re-credited")).await().unwrap();

        assertThat(store.getLifecycle(id).unwrap().sharedDelete()).isEqualTo(SharedDelete.NONE);

        storage.deleteRef("b").await();

        assertThat(collector.collectSharedDeletes(snapshots, NO_GRACE).deleted()).isZero();
        assertThat(tickPastGrace()).isEqualTo(1);
        assertThat(shared.holds(id)).as("released without authority: the shared copy stays").isTrue();
    }

    /// Two refs on this node name one block: releasing one with authority orphans nothing and records nothing.
    @Test
    void aReleaseThatLeavesAnotherReference_recordsNoAuthority() {
        var id = storage.putRef("a", bytes("two names")).await().unwrap();

        storage.putRef("b", bytes("two names")).await();
        storage.deleteRef("a", SharedDelete.AUTHORISED).await();

        assertThat(store.getLifecycle(id).unwrap().refCount()).isEqualTo(1);
        assertThat(store.getLifecycle(id).unwrap().sharedDelete()).as("between the releases").isEqualTo(SharedDelete.NONE);

        storage.deleteRef("b").await();

        assertThat(store.getLifecycle(id).unwrap().sharedDelete()).isEqualTo(SharedDelete.NONE);
    }

    /// NT-129 / MT-65: released without knowing, the block leaves the shared tier only if the instance's identification
    /// predicate accepts its bytes; one that fails it goes the private way and its shared copy stays.
    @Test
    void anIfIdentifiedRelease_followsThePredicate() {
        storage.sharedDeleteIdentification(block -> new String(block, StandardCharsets.UTF_8).startsWith("ID:"));

        var identified = storage.putRef("a", bytes("ID: a new segment")).await().unwrap();
        var legacy = storage.putRef("b", bytes("a legacy segment")).await().unwrap();

        storage.deleteRef("a", SharedDelete.IF_IDENTIFIED).await();
        storage.deleteRef("b", SharedDelete.IF_IDENTIFIED).await();
        collector.collectSharedDeletes(snapshots, NO_GRACE);

        assertThat(shared.holds(identified)).as("identified: deleted everywhere").isFalse();
        assertThat(shared.holds(legacy)).as("not identified: the shared copy stays").isTrue();
        assertThat(disk.exists(legacy).await().unwrap()).as("not identified: the private copy is collected").isFalse();
    }

    /// Without a predicate nothing released without knowing is deleted from the shared tier.
    @Test
    void anIfIdentifiedRelease_withNoPredicate_keepsTheSharedCopy() {
        var id = storage.putRef("a", bytes("no predicate")).await().unwrap();

        storage.deleteRef("a", SharedDelete.IF_IDENTIFIED).await();
        collector.collectSharedDeletes(snapshots, NO_GRACE);

        assertThat(shared.holds(id)).isTrue();
    }

    /// NT-131 at the storage level: the shared tier goes first, so a failed shared delete leaves the private copies this node
    /// still serves from and puts the record back; the next run, with the shared tier back, completes the delete.
    @Test
    void aFailedSharedDelete_keepsThePrivateCopy_putsTheRecordBack_andTheNextRunCompletes() {
        var id = storage.putRef("a", bytes("dht down")).await().unwrap();

        storage.deleteRef("a", SharedDelete.AUTHORISED).await();
        shared.failDeletes.set(true);

        var down = collector.collectSharedDeletes(snapshots, NO_GRACE);

        assertThat(down.failed()).isEqualTo(1);
        assertThat(disk.exists(id).await().unwrap()).as("shared first: the private copy is untouched").isTrue();
        assertThat(store.getLifecycle(id).isPresent()).as("the record is back for the next run").isTrue();

        shared.failDeletes.set(false);

        assertThat(collector.collectSharedDeletes(snapshots, NO_GRACE).deleted()).isEqualTo(1);
        assertThat(shared.holds(id)).isFalse();
        assertThat(disk.exists(id).await().unwrap()).isFalse();
    }

    /// NT-139: a slow shared tier (a DHT whose removes wait out their timeouts) stalls only this run, and the run stops at its
    /// time bound and leaves the rest to the next run instead of draining the whole backlog behind the slow removes.
    @Test
    void aSlowSharedTier_stopsTheRunAtItsTimeBound_andDefersTheRest() {
        for (var i = 0; i < 10; i++) {
            storage.putRef("seg-" + i, bytes("slow " + i)).await();
            storage.deleteRef("seg-" + i, SharedDelete.AUTHORISED).await();
        }

        shared.deleteDelayMs.set(100);

        var run = collector.collectSharedDeletes(snapshots, new SharedDeleteLimits(0L, 1, 10_000, 250L));

        assertThat(run.deleted()).as("some, not all").isBetween(1, 5);
        assertThat(run.deferred()).isEqualTo(10 - run.deleted() - run.failed());

        shared.deleteDelayMs.set(0);

        assertThat(collector.collectSharedDeletes(snapshots, NO_GRACE).deleted()).as("the next run completes").isEqualTo(10 - run.deleted());
    }

    /// NT-136 / MT-63: the pressure path deletes an orphan with durable authority from every tier, and leaves one whose
    /// authority is not yet durable to the shared run instead of deleting it privately and losing its record.
    @Test
    void thePressurePath_deletesADurablyAuthorisedOrphanEverywhere_andLeavesANotYetDurableOne() {
        var durable = storage.putRef("a", bytes("durable authority")).await().unwrap();
        var late = storage.putRef("b", bytes("authority after the snapshot")).await().unwrap();

        storage.deleteRef("a", SharedDelete.AUTHORISED).await();

        var snapshot = snapshots.snapshotNow().unwrap();

        storage.deleteRef("b", SharedDelete.AUTHORISED).await();

        assertThat(collector.collectOrphansDurableIn(snapshot)).isEqualTo(1);
        assertThat(shared.holds(durable)).isFalse();
        assertThat(shared.holds(late)).isTrue();
        assertThat(store.getLifecycle(late).isPresent()).as("left for the shared run, record kept").isTrue();
    }

    /// The authority survives a snapshot round trip, and a record without it is written byte-for-byte as before.
    @Test
    void theAuthority_survivesASnapshotRoundTrip() {
        var id = storage.putRef("a", bytes("round trip")).await().unwrap();

        storage.deleteRef("a", SharedDelete.IF_IDENTIFIED).await();

        var written = snapshots.snapshotNow().unwrap();
        var restored = SnapshotManager.snapshotManager(MetadataStore.inMemoryMetadataStore("streams"),
                                                       SnapshotConfig.snapshotConfig(dir.resolve("snapshots"), "streams"))
                                      .restoreFromLatest()
                                      .unwrap()
                                      .unwrap();

        assertThat(restored.lifecycles()).filteredOn(lc -> lc.blockId().equals(id))
                                         .singleElement()
                                         .extracting(BlockLifecycle::sharedDelete)
                                         .isEqualTo(SharedDelete.IF_IDENTIFIED);
        assertThat(written.lifecycles()).hasSize(restored.lifecycles().size());
    }

    /// NT-138 / MT-64: a restored record that a restored ref names loses its authority, because the snapshot captures records
    /// before refs and a re-credit between the two captures can leave a flagged "orphan" a ref names.
    @Test
    void aRestoredRecordThatARestoredRefNames_losesItsAuthority() {
        var id = storage.putRef("a", bytes("named after restore")).await().unwrap();
        var flagged = store.getLifecycle(id).unwrap().withRefCountDecremented(SharedDelete.AUTHORISED);
        var restoredStore = MetadataStore.inMemoryMetadataStore("streams");

        restoredStore.restoreLifecycles(List.of(flagged));
        restoredStore.restoreRefs(java.util.Map.of("a", id));

        assertThat(restoredStore.getLifecycle(id).unwrap().sharedDelete()).isEqualTo(SharedDelete.NONE);
    }

    /// NT-127/NT-128 at the storage level: a PRIVATE write lands on the private durable tier only and its record says so.
    @Test
    void aPrivateWrite_landsOnPrivateTiersOnly_andItsRecordSaysSo() {
        var id = storage.putRef("cursors/g/orders/0", bytes("cursor"), TierScope.PRIVATE).await().unwrap();

        assertThat(shared.holds(id)).as("no shared copy").isFalse();
        assertThat(disk.exists(id).await().unwrap()).isTrue();
        assertThat(store.getLifecycle(id).unwrap().presentIn()).doesNotContain(TierLevel.REMOTE);
        assertThat(storage.privateScopeFallbacks()).isZero();
    }

    /// With no private durable tier (the disk-unavailable stack) a PRIVATE write falls back to every tier and is counted.
    @Test
    void aPrivateWrite_withNoPrivateDurableTier_fallsBackToEveryTier_andIsCounted() {
        var memoryOnly = StorageInstance.storageInstance("streams-degraded",
                                                         List.of(MemoryTier.memoryTier(1 << 20), shared),
                                                         MetadataStore.inMemoryMetadataStore("streams-degraded"));
        var id = memoryOnly.putRef("cursors/g/orders/0", bytes("degraded cursor"), TierScope.PRIVATE).await().unwrap();

        assertThat(shared.holds(id)).isTrue();
        assertThat(memoryOnly.privateScopeFallbacks()).isEqualTo(1);
    }

    /// One periodic tick with no grace: what the 5-minute maintenance tick does to every orphan past its hour. The config
    /// clamps the grace to at least 1 ms, so the tick waits past it.
    private int tickPastGrace() {
        java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);

        var noGrace = StorageGarbageCollector.storageGarbageCollector(storage, store, new GarbageCollectorConfig(0L, 500));

        noGrace.activate();

        return noGrace.collectGarbage();
    }

    private static SnapshotManager fixed(MetadataSnapshot snapshot) {
        return new SnapshotManager() {
            @Override
            public Result<MetadataSnapshot> snapshotNow() {
                return Result.success(snapshot);
            }

            @Override
            public Result<Option<MetadataSnapshot>> restoreFromLatest() {
                return Result.success(Option.some(snapshot));
            }

            @Override
            public void maybeSnapshot() {}

            @Override
            public void forceSnapshot() {}

            @Override
            public long lastSnapshotEpoch() {
                return snapshot.epoch();
            }

            @Override
            public long lastSnapshotTimestamp() {
                return snapshot.timestamp();
            }
        };
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /// The DHT stand-in: shared, and able to fail its deletes.
    private static final class FlakySharedTier implements StorageTier {
        private final MemoryTier backing = MemoryTier.memoryTier(1L << 30, TierLevel.REMOTE);
        final AtomicBoolean failDeletes = new AtomicBoolean();
        final AtomicInteger deletes = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong deleteDelayMs = new java.util.concurrent.atomic.AtomicLong();

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
            deletes.incrementAndGet();
            java.util.concurrent.locks.LockSupport.parkNanos(deleteDelayMs.get() * 1_000_000L);

            return failDeletes.get()
                   ? Causes.cause("DHT write quorum unreachable").promise()
                   : backing.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return backing.exists(id);
        }

        @Override
        public TierLevel level() {
            return TierLevel.REMOTE;
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
            return true;
        }
    }
}
