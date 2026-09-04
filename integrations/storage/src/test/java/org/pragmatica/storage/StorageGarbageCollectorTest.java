package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;

class StorageGarbageCollectorTest {

    private static final long MEMORY_MAX = 1024 * 1024;
    private static final long GRACE_PERIOD_MS = 1000;
    private static final int BATCH_SIZE = 500;
    private static final byte[] CONTENT_A = "gc-test-alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "gc-test-bravo".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_C = "gc-test-charlie".getBytes(StandardCharsets.UTF_8);

    private MetadataStore metadataStore;
    private StorageInstance instance;
    private StorageGarbageCollector gc;

    @BeforeEach
    void setUp() {
        var memoryTier = MemoryTier.memoryTier(MEMORY_MAX);
        metadataStore = MetadataStore.inMemoryMetadataStore("gc-test");
        instance = StorageInstance.storageInstance("gc-test", List.of(memoryTier), metadataStore);
        gc = storageGarbageCollector(instance, metadataStore, garbageCollectorConfig(GRACE_PERIOD_MS, BATCH_SIZE));
        gc.activate();
    }

    private BlockId storeBlock(byte[] content) {
        return instance.put(content).await()
                       .fold(c -> { fail("put failed: " + c.message()); return null; },
                             id -> id);
    }

    private void makeOrphanedPastGrace(BlockId blockId) {
        var expired = System.currentTimeMillis() - GRACE_PERIOD_MS - 100;
        metadataStore.computeLifecycle(blockId, lc -> new BlockLifecycle(
            lc.blockId(), lc.presentIn(), 0, expired, lc.createdAt(), lc.accessCount(), expired));
    }

    private void makeOrphanedRecent(BlockId blockId) {
        var now = System.currentTimeMillis();
        metadataStore.computeLifecycle(blockId, lc -> new BlockLifecycle(
            lc.blockId(), lc.presentIn(), 0, now, lc.createdAt(), lc.accessCount(), now));
    }

    /// Backdates lastAccessedAt while leaving refCount/orphanedAt untouched -- simulates a block
    /// that was read a long time ago and has sat referenced (not orphaned) ever since.
    private void backdateLastAccessedAt(BlockId blockId, long millisAgo) {
        var stale = System.currentTimeMillis() - millisAgo;
        metadataStore.computeLifecycle(blockId, lc -> new BlockLifecycle(
            lc.blockId(), lc.presentIn(), lc.refCount(), stale, lc.createdAt(), lc.accessCount(), lc.orphanedAt()));
    }

    @Nested
    class CollectGarbageTests {

        @Test
        void collectGarbage_orphanedBlock_collectsAfterGracePeriod() {
            var id = storeBlock(CONTENT_A);
            makeOrphanedPastGrace(id);

            var collected = gc.collectGarbage();

            assertThat(collected).isEqualTo(1);
            assertThat(metadataStore.containsBlock(id)).isFalse();
            instance.get(id).await()
                    .onFailure(c -> fail("get failed: " + c.message()))
                    .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void collectGarbage_referencedBlock_skips() {
            var id = storeBlock(CONTENT_A);

            var collected = gc.collectGarbage();

            assertThat(collected).isZero();
            assertThat(metadataStore.containsBlock(id)).isTrue();
        }

        @Test
        void collectGarbage_recentlyOrphaned_skipsBeforeGracePeriod() {
            var id = storeBlock(CONTENT_A);
            makeOrphanedRecent(id);

            var collected = gc.collectGarbage();

            assertThat(collected).isZero();
            assertThat(metadataStore.containsBlock(id)).isTrue();
        }

        /// #737 fix round 2: the grace period must run from when the block was ORPHANED, not
        /// from when it was last READ. A block can be read once, held referenced for a long
        /// time, and only orphaned just now -- its stale lastAccessedAt must not rob it of the
        /// grace period a block orphaned this instant is owed.
        @Test
        void collectGarbage_orphanedNow_survivesEvenWithStaleLastAccessedAt() {
            var id = storeBlock(CONTENT_A);
            backdateLastAccessedAt(id, GRACE_PERIOD_MS * 10);
            metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountDecremented);

            var collected = gc.collectGarbage();

            assertThat(collected).isZero();
            assertThat(metadataStore.containsBlock(id)).isTrue();
        }

        @Test
        void collectGarbage_emptyStore_returnsZero() {
            var collected = gc.collectGarbage();

            assertThat(collected).isZero();
        }

        @Test
        void collectGarbage_batchLimited() {
            var id1 = storeBlock(CONTENT_A);
            var id2 = storeBlock(CONTENT_B);
            var id3 = storeBlock(CONTENT_C);
            makeOrphanedPastGrace(id1);
            makeOrphanedPastGrace(id2);
            makeOrphanedPastGrace(id3);

            var smallBatchGc = storageGarbageCollector(instance, metadataStore, garbageCollectorConfig(GRACE_PERIOD_MS, 2));
            smallBatchGc.activate();
            var collected = smallBatchGc.collectGarbage();

            assertThat(collected).isEqualTo(2);
        }
    }

    @Nested
    class StatsTests {

        @Test
        void stats_initiallyEmpty() {
            var stats = gc.stats();

            assertThat(stats.blocksCollected()).isZero();
            assertThat(stats.lastRunMs()).isZero();
        }

        @Test
        void stats_tracksCollectedCount() {
            var id = storeBlock(CONTENT_A);
            makeOrphanedPastGrace(id);

            gc.collectGarbage();

            var stats = gc.stats();
            assertThat(stats.blocksCollected()).isEqualTo(1);
            assertThat(stats.lastRunMs()).isGreaterThan(0);
        }

        @Test
        void stats_accumulatesAcrossCycles() {
            var id1 = storeBlock(CONTENT_A);
            makeOrphanedPastGrace(id1);
            gc.collectGarbage();

            var id2 = storeBlock(CONTENT_B);
            makeOrphanedPastGrace(id2);
            gc.collectGarbage();

            assertThat(gc.stats().blocksCollected()).isEqualTo(2);
        }
    }

    /// #250: the real hazard is not the noOp wiring but what the real GC does once wired --
    /// it determines orphan status from THIS node's local metadata, then (pre-fix) deleted from
    /// every configured tier, including a cluster-shared one. A shared tier reports
    /// [StorageTier#isShared] and GC must never issue a delete against it, no matter what tier
    /// list it was constructed with -- the durable copy there may still be referenced by other
    /// nodes' local views. Demotion is untouched: DefaultDemotionManager only ever demotes
    /// tiers[i] -> tiers[i+1], so a shared tier placed last (the DHT convention) is always a
    /// demotion target, never a demotion source, and needed no change.
    @Nested
    class SharedTierSafetyTests {

        private MemoryTier privateTier;
        private TrackingTier sharedTier;
        private MetadataStore sharedMetadataStore;
        private StorageInstance sharedInstance;
        private StorageGarbageCollector sharedGc;

        @BeforeEach
        void setUp() {
            privateTier = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY);
            sharedTier = new TrackingTier(TierLevel.REMOTE);
            sharedMetadataStore = MetadataStore.inMemoryMetadataStore("gc-shared-test");
            sharedInstance = StorageInstance.storageInstance("gc-shared-test",
                                                             List.of(privateTier, sharedTier),
                                                             sharedMetadataStore);
            sharedGc = storageGarbageCollector(sharedInstance,
                                               sharedMetadataStore,
                                               garbageCollectorConfig(GRACE_PERIOD_MS, BATCH_SIZE));
            sharedGc.activate();
        }

        @Test
        void collectGarbage_blockPresentInSharedTier_neverDeletesFromSharedTier() {
            var id = sharedInstance.put(CONTENT_A).await()
                                   .fold(c -> { fail("put failed: " + c.message()); return null; },
                                         blockId -> blockId);
            sharedMetadataStore.computeLifecycle(id, lc -> {
                var expired = System.currentTimeMillis() - GRACE_PERIOD_MS - 100;

                return new BlockLifecycle(lc.blockId(), lc.presentIn(), 0, expired, lc.createdAt(), lc.accessCount(), expired);
            });

            var collected = sharedGc.collectGarbage();

            assertThat(collected).isEqualTo(1);
            assertThat(sharedTier.deleteCount())
                .as("GC must never delete from a shared tier based on node-local refcounts")
                .isZero();
            sharedInstance.get(id).await()
                          .onFailure(c -> fail("get failed: " + c.message()))
                          .onSuccess(opt -> {
                              assertThat(opt.isPresent())
                                  .as("block must remain readable from the shared tier after local GC (cache miss, not data loss)")
                                  .isTrue();
                              opt.onPresent(content -> assertThat(content).isEqualTo(CONTENT_A));
                          });
        }
    }

    /// Tracking tier used only to prove GC never calls [StorageTier#delete] on a shared tier.
    private static final class TrackingTier implements StorageTier {
        private final MemoryTier backing;
        private final AtomicInteger deleteCount = new AtomicInteger();

        TrackingTier(TierLevel level) {
            this.backing = MemoryTier.memoryTier(MEMORY_MAX, level);
        }

        int deleteCount() {
            return deleteCount.get();
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
            deleteCount.incrementAndGet();

            return backing.delete(id);
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
            return true;
        }
    }
}
