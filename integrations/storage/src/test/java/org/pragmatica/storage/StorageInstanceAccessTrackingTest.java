package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.DemotionConfig.demotionConfig;

/// C-08, access tracking: read access lives in a separate in-memory counter that the block record never
/// carries and no snapshot ever contains. What that buys, and what each test pins:
/// the snapshot's bytes depend only on writes; a restart leaves access unknown, and unknown is not cold.
class StorageInstanceAccessTrackingTest {
    private static final long WARM_UP_MS = 10_000;

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static StorageInstance twoTier(MetadataStore store) {
        return StorageInstance.storageInstance("c08-access",
                                               List.of(MemoryTier.memoryTier(1 << 20, TierLevel.MEMORY),
                                                       MemoryTier.memoryTier(1 << 20, TierLevel.LOCAL_DISK)),
                                               store);
    }

    private static String snapshotBody(MetadataStore store) {
        return MetadataSnapshot.render(store.currentEpoch(), store.listAllLifecycles(), store.listAllRefs()).text();
    }

    /// The same writes give the same snapshot body however many reads there were: the record is not where
    /// reads leave their mark. Control: a write changes the body, so the comparison can differ.
    @Test
    void snapshotBody_dependsOnlyOnWrites_notOnReads() {
        var store = MetadataStore.inMemoryMetadataStore("deterministic");
        var node = twoTier(store);
        var ids = IntStream.range(0, 4).mapToObj(i -> node.put(bytes("block " + i)).await().unwrap()).toList();
        var afterWrites = snapshotBody(store);
        var epochAfterWrites = store.currentEpoch();

        IntStream.range(0, 200).forEach(i -> node.get(ids.get(i % ids.size())).await().unwrap());
        node.exists(ids.getFirst()).await().unwrap();

        assertThat(snapshotBody(store)).as("200 reads left the snapshot body as it was").isEqualTo(afterWrites);
        assertThat(store.currentEpoch()).isEqualTo(epochAfterWrites);

        node.put(bytes("one more write")).await().unwrap();

        assertThat(snapshotBody(store)).as("control: a write does change it").isNotEqualTo(afterWrites);
    }

    /// After a boot nothing is known of how hot a block is. Inside the warm-up window demotion holds the
    /// block back instead of treating "no read yet" as "cold"; once the window has passed it is a candidate.
    @Test
    void demotion_holdsAnUnknownBlockInsideTheWarmUpWindow_andDemotesItAfter() {
        var clock = new AtomicLong(1_000_000);
        var tracker = AccessTracker.accessTracker(WARM_UP_MS, clock::get);
        var memory = MemoryTier.memoryTier(200, TierLevel.MEMORY);
        var disk = MemoryTier.memoryTier(2000, TierLevel.LOCAL_DISK);
        var store = MetadataStore.inMemoryMetadataStore("post-boot", tracker);
        var content = bytes("restored block content");
        var id = BlockId.blockId(content).unwrap();

        memory.put(id, content).await().unwrap();
        // A record from before this boot, as a snapshot restore installs it: created earlier, never read here.
        store.restoreLifecycles(List.of(BlockLifecycle.blockLifecycle(id,
                                                                      java.util.EnumSet.of(TierLevel.MEMORY),
                                                                      1,
                                                                      500L,
                                                                      500L,
                                                                      0)));

        var manager = DemotionManager.demotionManager(List.of(memory, disk),
                                                      store,
                                                      demotionConfig(DemotionStrategy.LRU, 0.01, 0.009, 10));

        manager.activate();
        clock.addAndGet(WARM_UP_MS - 1);

        assertThat(manager.demote()).as("inside the window the unknown block is held back").isZero();
        assertThat(store.getLifecycle(id).unwrap().presentIn()).contains(TierLevel.MEMORY);

        clock.addAndGet(1);

        assertThat(manager.demote()).as("after the window it is demoted").isEqualTo(1);
        assertThat(store.getLifecycle(id).unwrap().presentIn()).doesNotContain(TierLevel.MEMORY);
    }

    /// A read inside the window makes the block known, and known blocks are ranked on what was read: the one
    /// read recently stays, the unread unknown one is still held back until the window passes.
    @Test
    void aBlockReadSinceBoot_isNoLongerUnknown() {
        var clock = new AtomicLong(1_000_000);
        var tracker = AccessTracker.accessTracker(WARM_UP_MS, clock::get);
        var store = MetadataStore.inMemoryMetadataStore("known-after-read", tracker);
        var restored = BlockLifecycle.blockLifecycle(BlockId.blockId(bytes("x")).unwrap(),
                                                     java.util.EnumSet.of(TierLevel.MEMORY),
                                                     1,
                                                     500L,
                                                     500L,
                                                     0);

        assertThat(tracker.isUnknown(restored)).isTrue();

        tracker.record(restored.blockId());

        assertThat(tracker.isUnknown(restored)).as("one read in this process makes it known").isFalse();
        assertThat(tracker.isHeldBack(restored)).isFalse();
        assertThat(store.accessTracker()).isSameAs(tracker);
    }

    /// Read recency decides LRU demotion, and it is held in the tracker, not the record: the block written
    /// first but read last is the one that stays, though its record is the older.
    @Test
    void lruDemotion_ranksByTheReadsTheTrackerSaw() throws InterruptedException {
        var memory = MemoryTier.memoryTier(200, TierLevel.MEMORY);
        var disk = MemoryTier.memoryTier(2000, TierLevel.LOCAL_DISK);
        var store = MetadataStore.inMemoryMetadataStore("lru");
        var node = StorageInstance.storageInstance("c08-lru", List.of(memory, disk), store);
        var readLast = node.put(bytes("written first, read last")).await().unwrap();

        Thread.sleep(5);

        var neverRead = node.put(bytes("written second, never read")).await().unwrap();

        Thread.sleep(5);
        node.get(readLast).await().unwrap();

        var manager = DemotionManager.demotionManager(List.of(memory, disk),
                                                      store,
                                                      demotionConfig(DemotionStrategy.LRU, 0.01, 0.009, 1));

        manager.activate();
        manager.demote();

        assertThat(store.getLifecycle(readLast).unwrap().presentIn()).contains(TierLevel.MEMORY);
        assertThat(store.getLifecycle(neverRead).unwrap().presentIn()).doesNotContain(TierLevel.MEMORY);
    }

    /// The record's own access fields are frozen at creation: reads leave the record exactly as it was, and
    /// demotion takes no input from them. The unread block's record claims the most recent and most frequent
    /// access there could be; the read block's record claims none. Demotion still keeps the block that was read.
    @Test
    void lruDemotion_readsLeaveTheRecordUnchanged_andTakeNoInputFromItsAccessFields() throws InterruptedException {
        readsLeaveTheRecordUnchanged_andDemotionTakesNoInputFromItsAccessFields(DemotionStrategy.LRU);
    }

    @Test
    void lfuDemotion_readsLeaveTheRecordUnchanged_andTakeNoInputFromItsAccessFields() throws InterruptedException {
        readsLeaveTheRecordUnchanged_andDemotionTakesNoInputFromItsAccessFields(DemotionStrategy.LFU);
    }

    @SuppressWarnings("deprecation")
    private void readsLeaveTheRecordUnchanged_andDemotionTakesNoInputFromItsAccessFields(DemotionStrategy strategy)
    throws InterruptedException {
        var memory = MemoryTier.memoryTier(200, TierLevel.MEMORY);
        var disk = MemoryTier.memoryTier(2000, TierLevel.LOCAL_DISK);
        var store = MetadataStore.inMemoryMetadataStore("frozen-fields");
        var node = StorageInstance.storageInstance("c08-frozen", List.of(memory, disk), store);
        var unread = node.put(bytes("claims a hot record, never read")).await().unwrap();

        Thread.sleep(5);

        var read = node.put(bytes("claims nothing, read often")).await().unwrap();

        store.computeLifecycle(unread,
                               lc -> new BlockLifecycle(lc.blockId(),
                                                        lc.presentIn(),
                                                        lc.refCount(),
                                                        Long.MAX_VALUE / 2,
                                                        lc.createdAt(),
                                                        1_000_000,
                                                        lc.orphanedAt(),
                                                        lc.cacheOnly()));

        var recordBefore = store.getLifecycle(read).unwrap();
        var bodyBefore = snapshotBody(store);

        IntStream.range(0, 25).forEach(i -> node.get(read).await().unwrap());

        assertThat(store.getLifecycle(read).unwrap()).as("25 reads left the record as it was").isEqualTo(recordBefore);
        assertThat(snapshotBody(store)).as("and the serialized snapshot").isEqualTo(bodyBefore);

        var manager = DemotionManager.demotionManager(List.of(memory, disk),
                                                      store,
                                                      demotionConfig(strategy, 0.01, 0.009, 1));

        manager.activate();
        manager.demote();

        assertThat(store.getLifecycle(read).unwrap().presentIn()).contains(TierLevel.MEMORY);
        assertThat(store.getLifecycle(unread).unwrap().presentIn()).doesNotContain(TierLevel.MEMORY);
    }
}
