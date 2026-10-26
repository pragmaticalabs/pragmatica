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

    private record Restored(MemoryTier memory, MemoryTier disk, MetadataStore store) {
        Restored(AccessTracker tracker) {
            this(MemoryTier.memoryTier(200, TierLevel.MEMORY),
                 MemoryTier.memoryTier(2000, TierLevel.LOCAL_DISK),
                 MetadataStore.inMemoryMetadataStore("post-boot", tracker));
        }

        /// A record from before this boot, as a snapshot restore installs it: created at `createdAt`, never read here.
        BlockId add(String content, long createdAt) {
            var id = BlockId.blockId(bytes(content)).unwrap();

            memory.put(id, bytes(content)).await().unwrap();
            store.createLifecycle(BlockLifecycle.blockLifecycle(id,
                                                                java.util.EnumSet.of(TierLevel.MEMORY),
                                                                1,
                                                                createdAt,
                                                                createdAt,
                                                                0));

            return id;
        }

        DemotionManager demotion(DemotionStrategy strategy, int batch) {
            var manager = DemotionManager.demotionManager(List.of(memory, disk),
                                                          store,
                                                          demotionConfig(strategy, 0.01, 0.009, batch));

            manager.activate();

            return manager;
        }

        boolean stillInMemory(BlockId id) {
            return store.getLifecycle(id).unwrap().presentIn().contains(TierLevel.MEMORY);
        }
    }

    /// A tier above its high watermark demotes: pressure overrides the post-boot hold-back, so a node that
    /// restarts in a loop cannot pin a full tier. Pinned at the first and the last instant of the window.
    @Test
    void demotion_ofAFullTier_demotesAnUnknownBlockInsideTheWarmUpWindow() {
        var clock = new AtomicLong(1_000_000);
        var tracker = AccessTracker.accessTracker(WARM_UP_MS, clock::get);
        var fixture = new Restored(tracker);
        var id = fixture.add("restored block content", 500L);
        var manager = fixture.demotion(DemotionStrategy.LRU, 10);

        assertThat(tracker.isHeldBack(fixture.store().getLifecycle(id).unwrap())).as("inside the window and unknown").isTrue();
        assertThat(manager.demote()).as("full tier: demoted at once, window or not").isEqualTo(1);
        assertThat(fixture.stillInMemory(id)).isFalse();
    }

    /// Among blocks whose reads are unknown, the order is oldest-created first whatever the strategy ranks by:
    /// SIZE_PRESSURE alone would take the newest first.
    @Test
    void demotion_ofUnknownBlocksInsideTheWindow_takesTheOldestCreatedFirst() {
        var clock = new AtomicLong(1_000_000);
        var fixture = new Restored(AccessTracker.accessTracker(WARM_UP_MS, clock::get));
        var newer = fixture.add("restored newer", 600L);
        var older = fixture.add("restored older", 500L);

        assertThat(fixture.demotion(DemotionStrategy.SIZE_PRESSURE, 1).demote()).isEqualTo(1);
        assertThat(fixture.stillInMemory(older)).as("the older-created one went").isFalse();
        assertThat(fixture.stillInMemory(newer)).isTrue();
    }

    /// Inside the window a block read since boot is ranked on its reads and the unknown ones come after it;
    /// once the window has passed the unknown block is just an old one and goes first.
    @Test
    void demotion_ranksUnknownBlocksAfterTheOnesReadSinceBoot_untilTheWindowPasses() {
        var clock = new AtomicLong(1_000_000);
        var tracker = AccessTracker.accessTracker(WARM_UP_MS, clock::get);
        var fixture = new Restored(tracker);
        var unknown = fixture.add("restored unknown", 500L);
        var known = fixture.add("restored known", 700L);

        tracker.record(known);

        assertThat(fixture.demotion(DemotionStrategy.LRU, 1).demote()).isEqualTo(1);
        assertThat(fixture.stillInMemory(known)).as("inside the window the read one is demoted first").isFalse();
        assertThat(fixture.stillInMemory(unknown)).isTrue();

        var clockAfter = new AtomicLong(1_000_000);
        var trackerAfter = AccessTracker.accessTracker(WARM_UP_MS, clockAfter::get);
        var after = new Restored(trackerAfter);
        var unknownAfter = after.add("restored unknown", 500L);
        var knownAfter = after.add("restored known", 700L);

        trackerAfter.record(knownAfter);
        clockAfter.addAndGet(WARM_UP_MS);

        assertThat(after.demotion(DemotionStrategy.LRU, 1).demote()).isEqualTo(1);
        assertThat(after.stillInMemory(unknownAfter)).as("after the window the older record goes first").isFalse();
        assertThat(after.stillInMemory(knownAfter)).isTrue();
    }

    /// AGE ranks by creation alone: unknown access changes nothing for it, so inside the window the older
    /// record goes first even though a younger one was read since boot.
    @Test
    void ageDemotion_isNotReorderedByUnknownAccess() {
        var clock = new AtomicLong(1_000_000);
        var tracker = AccessTracker.accessTracker(WARM_UP_MS, clock::get);
        var fixture = new Restored(tracker);
        var older = fixture.add("restored older unknown", 500L);
        var younger = fixture.add("restored younger known", 700L);

        tracker.record(younger);

        assertThat(fixture.demotion(DemotionStrategy.AGE, 1).demote()).isEqualTo(1);
        assertThat(fixture.stillInMemory(older)).isFalse();
        assertThat(fixture.stillInMemory(younger)).isTrue();
    }

    /// The window is measured on a monotonic source from boot: a wall-clock step of an hour either way
    /// changes nothing. Forward, the window does not end at once; backward, it does not stretch.
    @Test
    void warmUpWindow_isNotMovedByAWallClockStep() {
        var wall = new AtomicLong(1_000_000);
        var nanos = new AtomicLong(7_000_000_000L);
        var tracker = AccessTracker.accessTracker(WARM_UP_MS, wall::get, nanos::get);
        var record = BlockLifecycle.blockLifecycle(BlockId.blockId(bytes("stepped")).unwrap(),
                                                   java.util.EnumSet.of(TierLevel.MEMORY), 1, 500L, 500L, 0);

        step(wall, nanos, 1_000);
        wall.addAndGet(3_600_000);

        assertThat(tracker.isHeldBack(record)).as("+1 h wall step: still inside the window").isTrue();

        step(wall, nanos, WARM_UP_MS - 1_000 - 1);

        assertThat(tracker.isHeldBack(record)).as("one ms short of the window on the monotonic clock").isTrue();

        step(wall, nanos, 1);

        assertThat(tracker.isHeldBack(record)).as("the window ends on the monotonic clock").isFalse();

        var wall2 = new AtomicLong(1_000_000);
        var nanos2 = new AtomicLong(7_000_000_000L);
        var tracker2 = AccessTracker.accessTracker(WARM_UP_MS, wall2::get, nanos2::get);

        wall2.addAndGet(-3_600_000);
        step(wall2, nanos2, WARM_UP_MS);

        assertThat(tracker2.isHeldBack(record)).as("-1 h wall step: the window did not stretch").isFalse();
    }

    private static void step(AtomicLong wall, AtomicLong nanos, long ms) {
        wall.addAndGet(ms);
        nanos.addAndGet(ms * 1_000_000L);
    }

    /// Production runs the window the default constructor gives: 15 minutes, to the millisecond.
    @Test
    void productionWarmUpWindow_isFifteenMinutes() {
        var wall = new AtomicLong(1_000_000);
        var nanos = new AtomicLong(7_000_000_000L);
        var tracker = AccessTracker.accessTracker(wall::get, nanos::get);
        var record = BlockLifecycle.blockLifecycle(BlockId.blockId(bytes("production")).unwrap(),
                                                   java.util.EnumSet.of(TierLevel.MEMORY), 1, 500L, 500L, 0);

        wall.addAndGet(3_600_000);
        nanos.addAndGet((15 * 60_000L - 1) * 1_000_000L);

        assertThat(tracker.isHeldBack(record)).as("on the monotonic source, whatever the wall clock did").isTrue();

        nanos.addAndGet(1_000_000L);

        assertThat(tracker.isHeldBack(record)).isFalse();
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

    /// SIZE_PRESSURE demotes the most recently read first, and what was read is in the tracker: the block
    /// written first but read last goes, the unread newer one stays, though its record is the newer.
    @Test
    void sizePressureDemotion_ranksByTheReadsTheTrackerSaw() throws InterruptedException {
        var memory = MemoryTier.memoryTier(200, TierLevel.MEMORY);
        var disk = MemoryTier.memoryTier(2000, TierLevel.LOCAL_DISK);
        var store = MetadataStore.inMemoryMetadataStore("size-pressure");
        var node = StorageInstance.storageInstance("c08-size", List.of(memory, disk), store);
        var readLast = node.put(bytes("written first, read last")).await().unwrap();

        Thread.sleep(5);

        var neverRead = node.put(bytes("written second, never read")).await().unwrap();

        Thread.sleep(5);
        node.get(readLast).await().unwrap();

        var manager = DemotionManager.demotionManager(List.of(memory, disk),
                                                      store,
                                                      demotionConfig(DemotionStrategy.SIZE_PRESSURE, 0.01, 0.009, 1));

        manager.activate();
        manager.demote();

        assertThat(store.getLifecycle(readLast).unwrap().presentIn()).doesNotContain(TierLevel.MEMORY);
        assertThat(store.getLifecycle(neverRead).unwrap().presentIn()).contains(TierLevel.MEMORY);
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
