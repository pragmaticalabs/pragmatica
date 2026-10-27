package org.pragmatica.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.DemotionConfig.demotionConfig;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;

/// C-08: a read is not a metadata mutation. Before this, every successful `get` advanced the epoch twice
/// (the access stamp, then the tier presence the read found), so 500 reads forced a full snapshot at the
/// default threshold of 1000, and any steady read load moved the cadence from every 60 s to every 10 s.
class StorageInstanceReadsAreNotMutationsTest {
    private static final String NODE_ID = "c08-node";
    private static final int THRESHOLD = 10;

    @TempDir
    Path dir;

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static StorageInstance twoTier(MetadataStore store) {
        return StorageInstance.storageInstance("c08",
                                               List.of(MemoryTier.memoryTier(1 << 20, TierLevel.MEMORY),
                                                       MemoryTier.memoryTier(1 << 20, TierLevel.LOCAL_DISK)),
                                               store);
    }

    /// Per read kind, the epoch delta of one read. Each is zero; the read still reaches the block.
    @Test
    void eachKindOfRead_advancesTheEpochByZero() {
        var store = MetadataStore.inMemoryMetadataStore("per-read");
        var node = twoTier(store);
        var id = node.put(bytes("read me")).await().unwrap();
        var missing = BlockId.blockId(bytes("never written")).unwrap();
        var before = store.currentEpoch();

        node.get(id).await().unwrap();
        assertThat(store.currentEpoch() - before).as("get, hit").isZero();

        node.get(missing).await().unwrap();
        assertThat(store.currentEpoch() - before).as("get, miss").isZero();

        node.exists(id).await().unwrap();
        assertThat(store.currentEpoch() - before).as("exists").isZero();

        node.resolveRef("no-such-ref");
        assertThat(store.currentEpoch() - before).as("resolveRef").isZero();
    }

    /// The first read of a block another node wrote creates a cache-only marker; that and the reads after it
    /// are observations too.
    @Test
    void readOfABlockAnotherNodeWrote_advancesTheEpochByZero() {
        var shared = new SharedTestTier();
        var writer = StorageInstance.storageInstance("writer",
                                                     List.of(MemoryTier.memoryTier(1 << 20), shared),
                                                     MetadataStore.inMemoryMetadataStore("writer"));
        var readerStore = MetadataStore.inMemoryMetadataStore("reader");
        var reader = StorageInstance.storageInstance("reader",
                                                     List.of(MemoryTier.memoryTier(1 << 20), shared),
                                                     readerStore);
        var id = writer.put(bytes("written elsewhere")).await().unwrap();
        var before = readerStore.currentEpoch();

        reader.get(id).await().unwrap();
        reader.get(id).await().unwrap();

        assertThat(readerStore.getLifecycle(id).unwrap().cacheOnly()).as("the marker was made").isTrue();
        assertThat(readerStore.accessTracker().of(id).map(AccessTracker.Access::count).or(0)).as("and the reads counted").isEqualTo(2);
        assertThat(readerStore.getLifecycle(id).unwrap().accessCount()).as("but not in the record").isZero();
        assertThat(readerStore.currentEpoch() - before).isZero();
    }

    /// A read that finds no record starts a cache marker, but a writer may have claimed the id meanwhile; the
    /// marker then loses and the presence goes onto that record. That path is a read too: no epoch.
    @Test
    void readThatLosesTheMarkerClaim_advancesTheEpochByZero() {
        var inner = MetadataStore.inMemoryMetadataStore("racing");
        var store = new MissesFirstObservation(inner);
        var node = StorageInstance.storageInstance("racing",
                                                   List.of(MemoryTier.memoryTier(1 << 20), new SharedTestTier()),
                                                   store);
        var id = node.put(bytes("claimed meanwhile")).await().unwrap();
        var before = inner.currentEpoch();

        store.missNextObservation();
        node.get(id).await().unwrap();

        assertThat(store.missed()).as("the read took the lost-race path").isTrue();
        assertThat(inner.currentEpoch() - before).isZero();
    }

    /// N reads trigger zero snapshots: a snapshot at the baseline, a hundred reads, and the threshold-driven
    /// trigger stays quiet. The control (writes past the threshold DO trigger one) shows the trigger can fire.
    @Test
    void nReads_triggerZeroSnapshots_butWritesStillDo() throws IOException {
        var store = MetadataStore.inMemoryMetadataStore("trigger");
        var node = twoTier(store);
        var manager = snapshotManager(store, snapshotConfig(dir, THRESHOLD, 600_000, 50, NODE_ID));
        var ids = IntStream.range(0, 3).mapToObj(i -> node.put(bytes("block " + i)).await().unwrap()).toList();

        manager.snapshotNow().unwrap();

        assertThat(snapshotFiles()).isEqualTo(1);

        IntStream.range(0, 100).forEach(i -> node.get(ids.get(i % ids.size())).await().unwrap());
        manager.maybeSnapshot();

        assertThat(snapshotFiles()).as("a hundred reads took no snapshot").isEqualTo(1);

        IntStream.range(0, 3).forEach(i -> node.put(bytes("control " + i)).await().unwrap());
        manager.maybeSnapshot();

        assertThat(snapshotFiles()).as("control: writes past the threshold take one").isEqualTo(2);
    }

    /// Demotion keeps working on access tracking that no longer counts as a mutation: the block read
    /// repeatedly is the one that stays in memory, the unread one goes.
    @Test
    void demotion_stillPrefersTheUnreadBlock() {
        var memory = MemoryTier.memoryTier(200, TierLevel.MEMORY);
        var disk = MemoryTier.memoryTier(2000, TierLevel.LOCAL_DISK);
        var store = MetadataStore.inMemoryMetadataStore("demotion");
        var node = StorageInstance.storageInstance("c08-demotion", List.of(memory, disk), store);
        var hot = node.put(bytes("hot block content")).await().unwrap();
        var cold = node.put(bytes("cold block content")).await().unwrap();

        IntStream.range(0, 20).forEach(_ -> node.get(hot).await().unwrap());

        var before = store.currentEpoch();
        var manager = DemotionManager.demotionManager(List.of(memory, disk),
                                                      store,
                                                      demotionConfig(DemotionStrategy.LFU, 0.01, 0.009, 1));

        manager.activate();
        manager.demote();

        assertThat(store.accessTracker().of(hot).map(AccessTracker.Access::count).or(0)).as("the reads were tracked").isEqualTo(20);
        assertThat(store.getLifecycle(hot).unwrap().accessCount()).as("outside the record").isZero();
        assertThat(store.getLifecycle(hot).unwrap().presentIn()).contains(TierLevel.MEMORY);
        assertThat(store.getLifecycle(cold).unwrap().presentIn()).doesNotContain(TierLevel.MEMORY);
        assertThat(store.currentEpoch()).as("the demotion itself IS a mutation").isGreaterThan(before);
    }

    private long snapshotFiles() throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("snapshot-")).count();
        }
    }

    /// Answers the next [#observeLifecycle] as if the record were not there yet, as a read that lost a race to
    /// a writer's claim would see it; everything else goes to the real store.
    private static final class MissesFirstObservation implements MetadataStore {
        private final MetadataStore delegate;
        private volatile boolean missNext;
        private volatile boolean missed;

        MissesFirstObservation(MetadataStore delegate) {
            this.delegate = delegate;
        }

        void missNextObservation() {
            missNext = true;
        }

        boolean missed() {
            return missed;
        }

        @Override
        public AccessTracker accessTracker() {
            return delegate.accessTracker();
        }

        @Override
        public Option<BlockLifecycle> observeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
            if (missNext) {
                missNext = false;
                missed = true;

                return Option.none();
            }

            return delegate.observeLifecycle(blockId, updater);
        }

        @Override
        public Option<BlockLifecycle> getLifecycle(BlockId blockId) {
            return delegate.getLifecycle(blockId);
        }

        @Override
        public void createLifecycle(BlockLifecycle lifecycle) {
            delegate.createLifecycle(lifecycle);
        }

        @Override
        public boolean claimBlock(BlockId blockId, BlockLifecycle sentinel) {
            return delegate.claimBlock(blockId, sentinel);
        }

        @Override
        public boolean claimUnacknowledged(BlockId blockId, BlockLifecycle sentinel) {
            return delegate.claimUnacknowledged(blockId, sentinel);
        }

        @Override
        public void acknowledgeClaim(BlockId blockId) {
            delegate.acknowledgeClaim(blockId);
        }

        @Override
        public boolean isUnacknowledged(BlockId blockId) {
            return delegate.isUnacknowledged(blockId);
        }

        @Override
        public boolean releaseClaim(BlockId blockId, BlockLifecycle sentinel) {
            return delegate.releaseClaim(blockId, sentinel);
        }

        @Override
        public Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
            return delegate.computeLifecycle(blockId, updater);
        }

        @Override
        public void removeLifecycle(BlockId blockId) {
            delegate.removeLifecycle(blockId);
        }

        @Override
        public void putRef(String refName, BlockId blockId) {
            delegate.putRef(refName, blockId);
        }

        @Override
        public Option<BlockId> resolveRef(String refName) {
            return delegate.resolveRef(refName);
        }

        @Override
        public Option<BlockId> removeRef(String refName) {
            return delegate.removeRef(refName);
        }

        @Override
        public Option<BlockId> replaceRef(String refName, BlockId blockId) {
            return delegate.replaceRef(refName, blockId);
        }

        @Override
        public boolean containsBlock(BlockId blockId) {
            return delegate.containsBlock(blockId);
        }

        @Override
        public String instanceName() {
            return delegate.instanceName();
        }

        @Override
        public List<BlockLifecycle> listBlocksByTier(TierLevel tier) {
            return delegate.listBlocksByTier(tier);
        }

        @Override
        public List<BlockLifecycle> listAllLifecycles() {
            return delegate.listAllLifecycles();
        }

        @Override
        public Map<String, BlockId> listAllRefs() {
            return delegate.listAllRefs();
        }

        @Override
        public long currentEpoch() {
            return delegate.currentEpoch();
        }

        @Override
        public void restoreLifecycles(List<BlockLifecycle> entries) {
            delegate.restoreLifecycles(entries);
        }

        @Override
        public void restoreRefs(Map<String, BlockId> refs) {
            delegate.restoreRefs(refs);
        }

        @Override
        public void restoreEpoch(long epoch) {
            delegate.restoreEpoch(epoch);
        }
    }
}
