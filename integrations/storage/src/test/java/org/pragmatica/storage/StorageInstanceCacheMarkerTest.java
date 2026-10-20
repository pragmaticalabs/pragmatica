package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
        }
    }

    @Nested
    class Bounds {
        private static final DefaultStorageInstance.CacheMarkerLimits THREE_MARKERS =
            new DefaultStorageInstance.CacheMarkerLimits(3, 1_000L, 0L);

        @Test
        void get_pastTheMarkerCap_createsNoNewMarkerUntilIdleOnesAreSwept() {
            var clock = new AtomicLong(1_000_000L);
            var bounded = readerInstance(THREE_MARKERS, clock, _ -> {});
            var ids = new ArrayList<BlockId>();

            for (var i = 0; i < 5; i++) {
                ids.add(writeElsewhere("bounded-" + i));
            }

            ids.forEach(id -> bounded.get(id).await().unwrap());

            assertThat(readerStore.listAllLifecycles()).as("3 markers, the cap").hasSize(3);

            // Control: advance the clock past the idle window; the markers are shared-only, so the sweep drops them.
            clock.addAndGet(5_000L);
            bounded.get(ids.get(4)).await().unwrap();

            assertThat(readerStore.getLifecycle(ids.get(4)).isPresent()).as("a sweep made room").isTrue();
            assertThat(readerStore.listAllLifecycles().size()).isLessThanOrEqualTo(3);
        }

        @Test
        void sweep_neverDropsAMarkerThatTracksACopyOnThisNode() {
            var clock = new AtomicLong(1_000_000L);
            var bounded = readerInstance(new DefaultStorageInstance.CacheMarkerLimits(1, 1_000L, 0L), clock, _ -> {});
            var warmed = writeElsewhere("warmed-copy");
            var other = writeElsewhere("another");

            bounded.warm(warmed).await().unwrap();
            clock.addAndGet(5_000L);
            bounded.get(other).await().unwrap();

            assertThat(readerStore.getLifecycle(warmed).unwrap().presentIn()).contains(TierLevel.MEMORY);
            assertThat(readerMemory.exists(warmed).await().unwrap()).isTrue();
        }

        @Test
        void warm_createsItsRecordEvenWhenTheMarkerTableIsFull() {
            var clock = new AtomicLong(1_000_000L);
            var bounded = readerInstance(new DefaultStorageInstance.CacheMarkerLimits(1, 600_000L, 60_000L), clock, _ -> {});
            var first = writeElsewhere("fills-the-table");
            var second = writeElsewhere("warmed-when-full");

            bounded.get(first).await().unwrap();
            assertThat(bounded.warm(second).await().unwrap()).isTrue();

            assertThat(readerStore.getLifecycle(second).unwrap().presentIn())
                .as("a copy with no record would be invisible to demotion")
                .contains(TierLevel.MEMORY);
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
