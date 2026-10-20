package org.pragmatica.storage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// A node that READS a block another node wrote records it as a cache-only marker, [StorageInstance#warm]
/// fetches into the cache tiers, and the managers treat such a copy as a cache. Two instances share one
/// "DHT" ([SharedTestTier]); nothing here builds a manager's collaborators by hand except the managers.
class StorageInstanceCacheMarkerTest {
    private static final long MEMORY_MAX = 10_000;
    private static final long DISK_MAX = 1_000_000;

    @TempDir
    Path tempDir;

    private SharedTestTier shared;
    private StorageInstance writer;
    private MemoryTier readerMemory;
    private LocalDiskTier readerDisk;
    private MetadataStore readerStore;
    private final List<BlockId> accessed = new ArrayList<>();
    private StorageInstance reader;

    @BeforeEach
    void setUp() {
        shared = new SharedTestTier();
        writer = StorageInstance.storageInstance("writer",
                                                 List.of(MemoryTier.memoryTier(MEMORY_MAX), shared),
                                                 MetadataStore.inMemoryMetadataStore("writer"));
        readerMemory = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY);
        readerDisk = LocalDiskTier.localDiskTier(tempDir.resolve("reader-blocks"), DISK_MAX).flatMap(LocalDiskTier::open).unwrap();
        readerStore = MetadataStore.inMemoryMetadataStore("reader");
        reader = readerInstance(DefaultStorageInstance.CacheMarkerLimits.DEFAULT, new AtomicLong(), accessed::add);
    }

    private StorageInstance readerInstance(DefaultStorageInstance.CacheMarkerLimits limits,
                                           AtomicLong clock,
                                           Consumer<BlockId> listener) {
        return new DefaultStorageInstance("reader",
                                          List.of(readerMemory, readerDisk, shared),
                                          readerStore,
                                          WritePolicy.WRITE_THROUGH,
                                          Option.none(),
                                          AppendLog.TornTailSink.logOnly(),
                                          listener,
                                          limits,
                                          clock::get);
    }

    private BlockId writeElsewhere(String content) {
        return writer.put(bytes(content)).await().unwrap();
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private BlockLifecycle recordOf(BlockId id) {
        return readerStore.getLifecycle(id).unwrap();
    }

    @Nested
    class Reading {
        @Test
        void get_ofABlockAnotherNodeWrote_recordsAMarkerAndCountsTheAccess() {
            var id = writeElsewhere("read-me");

            reader.get(id).await().unwrap();
            reader.get(id).await().unwrap();
            reader.get(id).await().unwrap();

            var record = recordOf(id);

            assertThat(record.cacheOnly()).isTrue();
            assertThat(record.accessCount()).isEqualTo(3);
            assertThat(record.presentIn()).containsExactly(TierLevel.REMOTE);
            assertThat(record.isOrphaned()).isFalse();
            assertThat(readerStore.listBlocksByTier(TierLevel.REMOTE)).extracting(BlockLifecycle::blockId).containsExactly(id);
        }

        @Test
        void get_tellsTheAccessListenerOncePerHit_andNeverForAMiss() {
            var id = writeElsewhere("listened");
            var missing = BlockId.blockId(bytes("never-written")).unwrap();

            reader.get(id).await().unwrap();
            reader.get(id).await().unwrap();
            reader.get(missing).await().unwrap();

            assertThat(accessed).containsExactly(id, id);
        }

        @Test
        void get_ofABlockThisNodeWroteItself_createsNoMarker() {
            var id = reader.put(bytes("mine")).await().unwrap();

            reader.get(id).await().unwrap();

            assertThat(recordOf(id).cacheOnly()).isFalse();
            assertThat(recordOf(id).refCount()).isEqualTo(1);
        }

        @Test
        void get_ofABlockServedByTheDurableDisk_createsNoMarker() {
            var content = bytes("on-my-disk-record-lost");
            var id = BlockId.blockId(content).unwrap();

            readerDisk.put(id, content).await().unwrap();
            reader.get(id).await().unwrap();

            assertThat(readerStore.getLifecycle(id).isPresent())
                .as("a durable copy is this node's own data whose record was lost; calling it a cache would let demotion evict it")
                .isFalse();
        }

        @Test
        void get_onAnInstanceWithNoSharedTier_createsNoMarker() {
            var memory = MemoryTier.memoryTier(MEMORY_MAX);
            var store = MetadataStore.inMemoryMetadataStore("standalone");
            var instance = StorageInstance.storageInstance("standalone", List.of(memory), store);
            var content = bytes("only-copy");
            var id = BlockId.blockId(content).unwrap();

            memory.put(id, content).await().unwrap();
            instance.get(id).await().unwrap();

            assertThat(store.getLifecycle(id).isPresent()).as("nothing else holds the block, so it is not a cache").isFalse();
        }

        @Test
        void put_ofABlockHeldOnlyAsAMarker_writesTheRequiredTiers() {
            var content = bytes("read-then-written-here");
            var id = writeElsewhere("read-then-written-here");

            reader.get(id).await().unwrap();
            assertThat(recordOf(id).cacheOnly()).isTrue();
            assertThat(readerDisk.exists(id).await().unwrap()).as("control: not on this node's disk yet").isFalse();

            var stored = reader.put(content).await().unwrap();

            assertThat(stored).isEqualTo(id);
            assertThat(readerDisk.exists(id).await().unwrap())
                .as("a put is not a duplicate of a block merely READ here: the durable tier must get it")
                .isTrue();

            var record = recordOf(id);

            assertThat(record.cacheOnly()).isFalse();
            assertThat(record.refCount()).isEqualTo(1);
            assertThat(record.presentIn()).contains(TierLevel.LOCAL_DISK, TierLevel.REMOTE);
        }
    }

    @Test
    void exists_ofABlockOnlyTheSharedTierHolds_isTrue_butWarmStillFetchesIt() {
        var id = writeElsewhere("shared-only");

        assertThat(reader.exists(id).await().unwrap()).as("exists answers for the cluster").isTrue();
        assertThat(readerMemory.exists(id).await().unwrap()).as("and makes nothing local").isFalse();
        assertThat(reader.warm(id).await().unwrap()).isTrue();
    }

    @Test
    void warm_defaultOfAnImplementationWithoutCacheTiers_warmsNothing() {
        InvocationHandler onlyDefaults = (proxy, method, args) -> InvocationHandler.invokeDefault(proxy, method, args);
        var storage = (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                               new Class<?>[]{StorageInstance.class},
                                                               onlyDefaults);

        assertThat(storage.warm(BlockId.blockId(bytes("anything")).unwrap()).await().unwrap()).isFalse();
    }

    /// The two interleavings a lock-free marker has to survive, forced deterministically: a store double
    /// performs the interleaved action at the exact call where the race window opens.
    @Nested
    class Races {
        private MetadataStore interleaving(AtomicBoolean fired, BiConsumer<MetadataStore, Method> onCall) {
            var delegate = readerStore;

            return (MetadataStore) Proxy.newProxyInstance(MetadataStore.class.getClassLoader(),
                                                          new Class<?>[]{MetadataStore.class},
                                                          (_, method, args) -> invoke(delegate, method, args, fired, onCall));
        }

        private Object invoke(MetadataStore delegate,
                              Method method,
                              Object[] args,
                              AtomicBoolean fired,
                              BiConsumer<MetadataStore, Method> onCall) throws Throwable {
            var result = invokeOn(delegate, method, args);

            if (fired.compareAndSet(false, true)) {
                onCall.accept(delegate, method);
            }

            return result;
        }

        private Object invokeOn(MetadataStore delegate, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Test
        void put_whoseDedupeFindsAMarkerWhereAnOwnedRecordWas_writesTheRequiredTiersInstead() {
            var content = bytes("owned-then-marker");
            var id = BlockId.blockId(content).unwrap();
            var fired = new AtomicBoolean();
            // The put's claim fails because an owned record exists; before its credit lands, GC removed that
            // record and a reader of the block planted a marker.
            var racing = interleaving(fired, (store, method) -> swapOwnedForMarker(store, method, id));
            var instance = new DefaultStorageInstance("racing",
                                                      List.of(readerMemory, readerDisk, shared),
                                                      racing,
                                                      WritePolicy.WRITE_THROUGH,
                                                      Option.none(),
                                                      AppendLog.TornTailSink.logOnly(),
                                                      _ -> {},
                                                      DefaultStorageInstance.CacheMarkerLimits.DEFAULT);

            readerStore.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK));
            shared.put(id, content).await().unwrap();

            instance.put(content).await().unwrap();

            assertThat(readerDisk.exists(id).await().unwrap())
                .as("crediting the marker would acknowledge a put that never reached the durable tier")
                .isTrue();
            assertThat(readerStore.getLifecycle(id).unwrap().cacheOnly()).isFalse();
        }

        private static void swapOwnedForMarker(MetadataStore store, Method method, BlockId id) {
            if (method.getName().equals("claimBlock")) {
                store.removeLifecycle(id);
                store.claimBlock(id, BlockLifecycle.cacheOnlyLifecycle(id, EnumSet.of(TierLevel.REMOTE)));
            }
        }

        @Test
        void get_whoseMarkerClaimLosesToAWriter_stillRecordsWhereItFoundTheBlock() {
            var id = writeElsewhere("writer-wins");
            var fired = new AtomicBoolean();
            // The reader's presence update saw no record; a writer's record landed before the marker claim.
            var racing = interleaving(fired, (store, method) -> plantOwnedRecord(store, method, id));
            var instance = new DefaultStorageInstance("racing",
                                                      List.of(readerMemory, readerDisk, shared),
                                                      racing,
                                                      WritePolicy.WRITE_THROUGH,
                                                      Option.none(),
                                                      AppendLog.TornTailSink.logOnly(),
                                                      _ -> {},
                                                      DefaultStorageInstance.CacheMarkerLimits.DEFAULT);

            instance.get(id).await().unwrap();

            assertThat(readerStore.getLifecycle(id).unwrap().presentIn())
                .as("the owned record the writer planted also learns the block was found in the shared tier")
                .contains(TierLevel.LOCAL_DISK, TierLevel.REMOTE);
        }

        private static void plantOwnedRecord(MetadataStore store, Method method, BlockId id) {
            if (method.getName().equals("computeLifecycle")) {
                store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK));
            }
        }
    }

    @Nested
    class Warming {
        @Test
        void warm_copiesABlockFromTheSharedTierIntoTheCacheTier_andRecordsIt() {
            var id = writeElsewhere("warm-me");

            assertThat(reader.warm(id).await().unwrap()).isTrue();

            assertThat(readerMemory.exists(id).await().unwrap()).isTrue();
            assertThat(readerDisk.exists(id).await().unwrap()).as("only cache tiers are written, never the durable one").isFalse();

            var record = recordOf(id);

            assertThat(record.cacheOnly()).isTrue();
            assertThat(record.presentIn()).contains(TierLevel.MEMORY);
            assertThat(readerStore.listBlocksByTier(TierLevel.MEMORY)).extracting(BlockLifecycle::blockId).containsExactly(id);
        }

        @Test
        void warm_isNotARead_countsNoAccessAndTellsNoListener() {
            var id = writeElsewhere("warm-quietly");

            reader.warm(id).await().unwrap();

            assertThat(recordOf(id).accessCount()).isZero();
            assertThat(accessed).isEmpty();
        }

        @Test
        void warm_whenAPrivateTierAlreadyHoldsTheBlock_fetchesNothing() {
            var id = writeElsewhere("already-here");

            assertThat(reader.warm(id).await().unwrap()).as("control: the first warm fetches").isTrue();
            var getsAfterFirst = shared.gets();

            assertThat(reader.warm(id).await().unwrap()).isFalse();
            assertThat(shared.gets()).isEqualTo(getsAfterFirst);
        }

        @Test
        void warm_ofABlockNoTierHolds_resolvesFalse() {
            var missing = BlockId.blockId(bytes("nowhere")).unwrap();

            assertThat(reader.warm(missing).await().unwrap()).isFalse();
            assertThat(readerStore.getLifecycle(missing).isPresent()).isFalse();
        }

        @Test
        void warm_whenTheCacheTierIsFull_resolvesFalseAndLeavesNothingHalfRecorded() {
            var full = MemoryTier.memoryTier(4, TierLevel.MEMORY);
            var store = MetadataStore.inMemoryMetadataStore("full");
            var instance = StorageInstance.storageInstance("full", List.of(full, shared), store);
            var id = writeElsewhere("too-big-for-four-bytes");

            assertThat(instance.warm(id).await().unwrap()).isFalse();
            assertThat(full.usedBytes()).isZero();
            assertThat(store.getLifecycle(id).unwrap().presentIn()).doesNotContain(TierLevel.MEMORY);
        }

        @Test
        void warm_onAnInstanceWithNoCacheTier_resolvesFalse() {
            var instance = StorageInstance.storageInstance("no-cache",
                                                           List.of(readerDisk, shared),
                                                           MetadataStore.inMemoryMetadataStore("no-cache"));
            var id = writeElsewhere("nowhere-to-put-it");

            assertThat(instance.warm(id).await().unwrap()).isFalse();
            assertThat(readerDisk.exists(id).await().unwrap()).isFalse();
            assertThat(shared.gets()).as("nowhere to put the copy, so nothing is fetched").isZero();
        }
    }

    @Nested
    class Bounds {
        private static final DefaultStorageInstance.CacheMarkerLimits THREE_MARKERS =
            DefaultStorageInstance.CacheMarkerLimits.cacheMarkerLimits(3, 1_000L, 100L);

        @Test
        void get_pastTheMarkerCap_createsNoNewMarkerUntilIdleOnesAreSwept() {
            var clock = new AtomicLong(System.currentTimeMillis());
            var bounded = readerInstance(THREE_MARKERS, clock, _ -> {});
            var ids = new ArrayList<BlockId>();

            for (var i = 0; i < 7; i++) {
                ids.add(writeElsewhere("bounded-" + i));
            }

            ids.subList(0, 5).forEach(id -> bounded.get(id).await().unwrap());

            assertThat(readerStore.listAllLifecycles()).as("3 markers, the cap; the other two reads are not tracked").hasSize(3);
            assertThat(readerStore.getLifecycle(ids.get(3)).isPresent()).isFalse();

            // The three markers track only the shared tier and go idle: one sweep drops them, and the room it
            // makes is the whole cap again -- not just the one slot the read that triggered it needed.
            clock.addAndGet(5_000L);
            ids.subList(3, 6).forEach(id -> bounded.get(id).await().unwrap());

            assertThat(readerStore.listAllLifecycles()).extracting(BlockLifecycle::blockId)
                                                       .containsExactlyInAnyOrder(ids.get(3), ids.get(4), ids.get(5));

            // The swept-in markers took their slots: the cap is full again, not one short of full.
            bounded.get(ids.get(6)).await().unwrap();

            assertThat(readerStore.getLifecycle(ids.get(6)).isPresent()).as("the table is full again after the sweep").isFalse();
        }

        @Test
        void get_whileTheTableIsFull_sweepsAtMostOncePerInterval() {
            var clock = new AtomicLong(System.currentTimeMillis());
            var bounded = readerInstance(DefaultStorageInstance.CacheMarkerLimits.cacheMarkerLimits(2, 1_000L, 10_000L), clock, _ -> {});
            var ids = new ArrayList<BlockId>();

            for (var i = 0; i < 4; i++) {
                ids.add(writeElsewhere("interval-" + i));
            }

            ids.subList(0, 3).forEach(id -> bounded.get(id).await().unwrap());

            // The two markers are idle by now (1,000 ms window) but the last sweep, made while the third block
            // was refused, was 2,000 ms ago: not yet due.
            clock.addAndGet(2_000L);
            bounded.get(ids.get(3)).await().unwrap();

            assertThat(readerStore.getLifecycle(ids.get(3)).isPresent()).as("no sweep inside the interval").isFalse();

            clock.addAndGet(10_000L);
            bounded.get(ids.get(3)).await().unwrap();

            assertThat(readerStore.getLifecycle(ids.get(3)).isPresent()).as("control: the next interval sweeps and makes room").isTrue();
        }

        @Test
        void sweep_neverDropsAMarkerThatTracksACopyOnThisNode() {
            var clock = new AtomicLong(System.currentTimeMillis());
            var bounded = readerInstance(DefaultStorageInstance.CacheMarkerLimits.cacheMarkerLimits(1, 1_000L, 100L), clock, _ -> {});
            var warmed = writeElsewhere("warmed-copy");
            var other = writeElsewhere("another");

            bounded.warm(warmed).await().unwrap();
            clock.addAndGet(5_000L);
            bounded.get(other).await().unwrap();

            assertThat(readerStore.getLifecycle(warmed).unwrap().presentIn()).contains(TierLevel.MEMORY);
            assertThat(readerMemory.exists(warmed).await().unwrap()).isTrue();
        }

        @Test
        void warm_whenTheMarkerTableIsFull_isRefusedRatherThanCopyingUntracked() {
            var clock = new AtomicLong(System.currentTimeMillis());
            var bounded = readerInstance(DefaultStorageInstance.CacheMarkerLimits.cacheMarkerLimits(1, 600_000L, 60_000L), clock, _ -> {});
            var first = writeElsewhere("fills-the-table");
            var second = writeElsewhere("warmed-when-full");

            bounded.get(first).await().unwrap();

            assertThat(bounded.warm(second).await().unwrap()).as("a copy that could not be tracked is not made").isFalse();
            assertThat(readerMemory.exists(second).await().unwrap()).isFalse();
            assertThat(readerStore.getLifecycle(second).isPresent()).isFalse();
        }
    }

    @Nested
    class Managers {
        private static final int BLOCK_SIZE = 100;

        private BlockId readerWarmed(String content) {
            var id = writeElsewhere(content);

            reader.warm(id).await().unwrap();

            return id;
        }

        @Test
        void demote_evictsACacheOnlyCopy_insteadOfMovingItToDisk() {
            var cacheId = readerWarmed("x".repeat(BLOCK_SIZE));
            var owned = reader.put(bytes("y".repeat(BLOCK_SIZE))).await().unwrap();
            var config = DemotionConfig.demotionConfig(DemotionStrategy.LRU, 0.0, 0.0, 10);
            var demotion = DemotionManager.demotionManager(List.of(readerMemory, readerDisk, shared), readerStore, config);

            demotion.activate();

            assertThat(readerMemory.exists(cacheId).await().unwrap()).as("control: both sit in memory first").isTrue();
            assertThat(readerMemory.exists(owned).await().unwrap()).isTrue();

            var demoted = demotion.demote();

            assertThat(demoted).isEqualTo(2);
            assertThat(readerMemory.usedBytes()).isZero();
            assertThat(readerDisk.exists(cacheId).await().unwrap()).as("a cached copy is dropped, not kept on disk").isFalse();
            assertThat(readerStore.getLifecycle(cacheId).unwrap().presentIn()).doesNotContain(TierLevel.MEMORY, TierLevel.LOCAL_DISK);
            assertThat(readerStore.getLifecycle(cacheId).unwrap().cacheOnly()).isTrue();
            assertThat(shared.deletes()).as("the shared copy is never touched").isZero();
            assertThat(readerDisk.exists(owned).await().unwrap()).as("control: an owned block is demoted normally").isTrue();
        }

        @Test
        void promote_ofABlockReadOftenFromTheSharedTier_copiesItToMemoryNotToTheDurableDisk() {
            var id = writeElsewhere("hot-on-a-reader");

            for (var i = 0; i < 6; i++) {
                reader.get(id).await().unwrap();
            }

            var promotion = PromotionManager.promotionManager(List.of(readerMemory, readerDisk, shared),
                                                              readerStore,
                                                              PromotionConfig.promotionConfig(5, 60_000L, 10));

            promotion.activate();

            assertThat(readerMemory.exists(id).await().unwrap()).as("control: not in memory before").isFalse();
            assertThat(promotion.promote()).isEqualTo(1);

            assertThat(readerMemory.exists(id).await().unwrap()).isTrue();
            assertThat(readerDisk.exists(id).await().unwrap())
                .as("a copy of another node's block must not land on the durable disk, where nothing collects it")
                .isFalse();
            assertThat(readerStore.getLifecycle(id).unwrap().presentIn()).contains(TierLevel.MEMORY);
        }

        @Test
        void promote_ofAHotMarker_copiesItOnce_thenNeverReadsTheSharedTierAgain() {
            var id = writeElsewhere("promoted-once");

            for (var i = 0; i < 6; i++) {
                reader.get(id).await().unwrap();
            }

            var promotion = PromotionManager.promotionManager(List.of(readerMemory, readerDisk, shared),
                                                              readerStore,
                                                              PromotionConfig.promotionConfig(5, 60_000L, 10));

            promotion.activate();

            var before = shared.gets();

            assertThat(promotion.promote()).as("the first cycle copies it to memory").isEqualTo(1);
            assertThat(shared.gets() - before).as("at the price of exactly one read of the shared tier").isEqualTo(1);

            var afterFirst = shared.gets();

            for (var cycle = 0; cycle < 5; cycle++) {
                assertThat(promotion.promote()).as("cycle %d: it is in the tier it was promoted to", cycle).isZero();
            }

            assertThat(shared.gets()).as("a candidate test that asks the ADJACENT tier (the disk) instead of the tier the block "
                                         + "is written to (memory) re-reads it from the shared tier every cycle")
                                     .isEqualTo(afterFirst);
            assertThat(recordOf(id).presentIn()).containsExactlyInAnyOrder(TierLevel.MEMORY, TierLevel.REMOTE);
        }

        @Test
        void promote_ofAMarker_isJudgedByItsOwnTarget_aFullAdjacentDiskDoesNotStarveIt() throws Exception {
            var fullDisk = LocalDiskTier.localDiskTier(tempDir.resolve("full-disk"), 1_000).flatMap(LocalDiskTier::open).unwrap();
            var filler = bytes("f".repeat(900));

            fullDisk.put(BlockId.blockId(filler).unwrap(), filler).await().unwrap();

            var id = writeElsewhere("memory-has-room");
            var tiers = List.of(readerMemory, fullDisk, shared);
            var diskFull = StorageInstance.storageInstance("disk-full", tiers, readerStore);

            for (var i = 0; i < 6; i++) {
                diskFull.get(id).await().unwrap();
            }

            var promotion = PromotionManager.promotionManager(tiers,
                                                              readerStore,
                                                              PromotionConfig.promotionConfig(5, 60_000L, 10, 0.5));

            promotion.activate();

            assertThat(promotion.promote()).as("the marker goes to memory, which has room; the disk's fullness is not its concern")
                                           .isEqualTo(1);
            assertThat(readerMemory.exists(id).await().unwrap()).isTrue();
        }

        @Test
        void promote_aMarkerWithNoTarget_isNotACandidate_soItCannotUseUpTheBatch() {
            var stranded = writeElsewhere("stranded-marker");
            var tiers = List.of(readerDisk, shared);
            var diskOnly = StorageInstance.storageInstance("disk-only", tiers, readerStore);

            for (var i = 0; i < 9; i++) {
                diskOnly.get(stranded).await().unwrap();
            }

            var content = bytes("owned-and-hot-too");
            var owned = BlockId.blockId(content).unwrap();

            shared.put(owned, content).await().unwrap();
            readerStore.createLifecycle(new BlockLifecycle(owned,
                                                           EnumSet.of(TierLevel.REMOTE),
                                                           1,
                                                           System.currentTimeMillis(),
                                                           System.currentTimeMillis(),
                                                           6,
                                                           0L));

            var promotion = PromotionManager.promotionManager(tiers,
                                                              readerStore,
                                                              PromotionConfig.promotionConfig(5, 60_000L, 1));

            promotion.activate();

            assertThat(recordOf(stranded).accessCount()).as("control: the marker is the hotter of the two").isGreaterThan(recordOf(owned).accessCount());
            assertThat(promotion.promote()).as("a batch of one goes to the block that can move, not to the hotter marker with nowhere to go")
                                           .isEqualTo(1);
            assertThat(readerDisk.exists(owned).await().unwrap()).isTrue();
        }

        @Test
        void promote_ofAMarker_whoseTargetIsAtItsWatermark_readsNothing() {
            var tinyMemory = MemoryTier.memoryTier(1_000, TierLevel.MEMORY);
            var filler = bytes("f".repeat(900));

            tinyMemory.put(BlockId.blockId(filler).unwrap(), filler).await().unwrap();

            var id = writeElsewhere("target-has-no-room");
            var tiers = List.of(tinyMemory, readerDisk, shared);
            var memoryFull = StorageInstance.storageInstance("memory-full", tiers, readerStore);

            for (var i = 0; i < 6; i++) {
                memoryFull.get(id).await().unwrap();
            }

            var promotion = PromotionManager.promotionManager(tiers,
                                                              readerStore,
                                                              PromotionConfig.promotionConfig(5, 60_000L, 10, 0.5));

            promotion.activate();

            var before = shared.gets();

            assertThat(promotion.promote()).isZero();
            assertThat(shared.gets() - before).as("a marker whose own target (memory) is full costs no source read, "
                                                  + "though the adjacent disk has room").isZero();
        }

        @Test
        void demote_ofAMarkerNamingADurableTier_movesTheBlockDown_neverDropsIt() throws Exception {
            // 1,000 bytes so that one small block puts the tier over the 1% high watermark.
            var first = LocalDiskTier.localDiskTier(tempDir.resolve("first-disk"), 1_000).flatMap(LocalDiskTier::open).unwrap();
            var second = LocalDiskTier.localDiskTier(tempDir.resolve("second-disk"), DISK_MAX).flatMap(LocalDiskTier::open).unwrap();
            var content = bytes("durable-but-marked");
            var id = BlockId.blockId(content).unwrap();

            first.put(id, content).await().unwrap();
            readerStore.createLifecycle(BlockLifecycle.cacheOnlyLifecycle(id, EnumSet.of(TierLevel.LOCAL_DISK)));

            var demotion = DemotionManager.demotionManager(List.of(first, second, shared),
                                                           readerStore,
                                                           DemotionConfig.demotionConfig(DemotionStrategy.LRU, 0.01, 0.0, 10));

            demotion.activate();

            assertThat(demotion.demote()).isEqualTo(1);
            assertThat(second.exists(id).await().unwrap())
                .as("a marker is only ever made for non-durable copies; one naming a durable tier is not trusted as a cache")
                .isTrue();
        }

        @Test
        void promote_ofAMarkerWithNoNonDurableTierAbove_promotesNothing() {
            var id = writeElsewhere("nowhere-to-cache");
            var diskOnly = StorageInstance.storageInstance("disk-only",
                                                           List.of(readerDisk, shared),
                                                           readerStore);

            for (var i = 0; i < 6; i++) {
                diskOnly.get(id).await().unwrap();
            }

            var promotion = PromotionManager.promotionManager(List.of(readerDisk, shared),
                                                              readerStore,
                                                              PromotionConfig.promotionConfig(5, 60_000L, 10));

            promotion.activate();

            assertThat(readerStore.getLifecycle(id).unwrap().cacheOnly()).as("control: it is a marker with enough accesses").isTrue();
            assertThat(promotion.promote()).isZero();
            assertThat(readerDisk.exists(id).await().unwrap()).isFalse();
        }

        @Test
        void promote_ofABlockThisNodeOwns_stillMovesOneTierUp() {
            var content = bytes("owned-and-hot");
            var id = BlockId.blockId(content).unwrap();

            shared.put(id, content).await().unwrap();
            readerStore.createLifecycle(new BlockLifecycle(id,
                                                           EnumSet.of(TierLevel.REMOTE),
                                                           1,
                                                           System.currentTimeMillis(),
                                                           System.currentTimeMillis(),
                                                           50,
                                                           0L));

            var promotion = PromotionManager.promotionManager(List.of(readerMemory, readerDisk, shared),
                                                              readerStore,
                                                              PromotionConfig.promotionConfig(5, 60_000L, 10));

            promotion.activate();

            assertThat(promotion.promote()).isPositive();
            assertThat(readerDisk.exists(id).await().unwrap())
                .as("an owned block takes the step the tiers define: one up from the shared tier is the disk")
                .isTrue();
        }

        @Test
        void collect_leavesACacheOnlyCopyAlone() {
            var id = readerWarmed("gc-spares-the-cache");
            var gc = StorageGarbageCollector.storageGarbageCollector(reader,
                                                                     readerStore,
                                                                     GarbageCollectorConfig.garbageCollectorConfig(1L, 100));

            gc.activate();

            assertThat(gc.collectGarbage()).isZero();
            assertThat(readerMemory.exists(id).await().unwrap()).isTrue();
            assertThat(readerStore.containsBlock(id)).isTrue();
        }
    }
}
