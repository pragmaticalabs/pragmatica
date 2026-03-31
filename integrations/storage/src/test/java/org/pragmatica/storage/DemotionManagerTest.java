package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.DemotionConfig.demotionConfig;
import static org.pragmatica.storage.DemotionManager.demotionManager;

class DemotionManagerTest {

    private static final long TIER_MAX = 200;
    private static final byte[] BLOCK_A = "alpha-block-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOCK_B = "bravo-block-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOCK_C = "charlie-block-data!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOCK_D = "delta-block-content".getBytes(StandardCharsets.UTF_8);

    private MemoryTier memoryTier;
    private MemoryTier diskTier;
    private MetadataStore metadataStore;

    @BeforeEach
    void setUp() {
        memoryTier = MemoryTier.memoryTier(TIER_MAX, TierLevel.MEMORY);
        diskTier = MemoryTier.memoryTier(TIER_MAX * 10, TierLevel.LOCAL_DISK);
        metadataStore = MetadataStore.inMemoryMetadataStore("demotion-test");
    }

    /// Store a block directly in the memory tier and register its lifecycle metadata.
    private BlockId storeInMemory(byte[] content) {
        var id = BlockId.blockId(content)
                        .fold(c -> { fail("blockId failed: " + c.message()); return null; },
                              blockId -> blockId);
        memoryTier.put(id, content).await()
                  .onFailure(c -> fail("put failed: " + c.message()));
        metadataStore.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
        return id;
    }

    @Nested
    class WatermarkTests {

        @Test
        void demote_tierAboveHighWatermark_movesBlocks() {
            storeInMemory(BLOCK_A);
            storeInMemory(BLOCK_B);
            storeInMemory(BLOCK_C);
            storeInMemory(BLOCK_D);

            // Memory tier has data. Set high watermark very low to trigger demotion.
            var config = demotionConfig(DemotionStrategy.LRU, 0.1, 0.05, 100);
            var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            var demoted = dm.demote();

            assertThat(demoted).isGreaterThan(0);
            assertThat(diskTier.usedBytes()).isGreaterThan(0);
        }

        @Test
        void demote_tierBelowHighWatermark_noAction() {
            storeInMemory(BLOCK_A);

            // High watermark 90% — with small data in 200-byte tier, utilization is well below.
            var config = demotionConfig(DemotionStrategy.LRU, 0.9, 0.7, 100);
            var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            var demoted = dm.demote();

            assertThat(demoted).isZero();
        }
    }

    @Nested
    class StrategyTests {

        @Test
        void demote_ageStrategy_oldestFirst() {
            var idA = storeInMemory(BLOCK_A);
            var idB = storeInMemory(BLOCK_B);

            // Force idA to have older createdAt
            metadataStore.computeLifecycle(idA, lc -> new BlockLifecycle(
                lc.blockId(), lc.presentIn(), lc.refCount(), lc.lastAccessedAt(), 1000L, lc.accessCount()));
            metadataStore.computeLifecycle(idB, lc -> new BlockLifecycle(
                lc.blockId(), lc.presentIn(), lc.refCount(), lc.lastAccessedAt(), 9999L, lc.accessCount()));

            // Batch size 1 so only the oldest is demoted.
            var config = demotionConfig(DemotionStrategy.AGE, 0.01, 0.009, 1);
            var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            dm.demote();

            // idA should be demoted (oldest createdAt), no longer in MEMORY tier
            var lcA = metadataStore.getLifecycle(idA);
            lcA.onPresent(lc -> assertThat(lc.presentIn()).doesNotContain(TierLevel.MEMORY));
        }

        @Test
        void demote_lfuStrategy_leastAccessedFirst() {
            var idA = storeInMemory(BLOCK_A);
            var idB = storeInMemory(BLOCK_B);

            // Give idB more access count than idA
            metadataStore.computeLifecycle(idB, lc -> new BlockLifecycle(
                lc.blockId(), lc.presentIn(), lc.refCount(), lc.lastAccessedAt(), lc.createdAt(), 100));

            var config = demotionConfig(DemotionStrategy.LFU, 0.01, 0.009, 1);
            var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            dm.demote();

            // idA (accessCount=0) should be demoted first
            var lcA = metadataStore.getLifecycle(idA);
            lcA.onPresent(lc -> assertThat(lc.presentIn()).doesNotContain(TierLevel.MEMORY));

            // idB should still be in MEMORY
            var lcB = metadataStore.getLifecycle(idB);
            lcB.onPresent(lc -> assertThat(lc.presentIn()).contains(TierLevel.MEMORY));
        }

        @Test
        void demote_lruStrategy_leastRecentFirst() {
            var idA = storeInMemory(BLOCK_A);
            var idB = storeInMemory(BLOCK_B);

            // Make idA least recently accessed
            metadataStore.computeLifecycle(idA, lc -> new BlockLifecycle(
                lc.blockId(), lc.presentIn(), lc.refCount(), 1000L, lc.createdAt(), lc.accessCount()));
            metadataStore.computeLifecycle(idB, lc -> new BlockLifecycle(
                lc.blockId(), lc.presentIn(), lc.refCount(), Long.MAX_VALUE, lc.createdAt(), lc.accessCount()));

            var config = demotionConfig(DemotionStrategy.LRU, 0.01, 0.009, 1);
            var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            dm.demote();

            // idA should be demoted (least recently accessed)
            var lcA = metadataStore.getLifecycle(idA);
            lcA.onPresent(lc -> assertThat(lc.presentIn()).doesNotContain(TierLevel.MEMORY));
        }
    }

    @Nested
    class BatchAndEdgeCaseTests {

        @Test
        void demote_batchLimited() {
            storeInMemory(BLOCK_A);
            storeInMemory(BLOCK_B);
            storeInMemory(BLOCK_C);
            storeInMemory(BLOCK_D);

            // Low high watermark to trigger, batch size 2
            var config = demotionConfig(DemotionStrategy.LRU, 0.01, 0.001, 2);
            var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            var demoted = dm.demote();

            assertThat(demoted).isLessThanOrEqualTo(2);
        }

        @Test
        void demote_noNextTier_skips() {
            var singleTier = MemoryTier.memoryTier(TIER_MAX);
            var singleMeta = MetadataStore.inMemoryMetadataStore("single-tier");

            var id = BlockId.blockId(BLOCK_A)
                            .fold(c -> { fail("blockId failed: " + c.message()); return null; },
                                  blockId -> blockId);
            singleTier.put(id, BLOCK_A).await();
            singleMeta.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            var config = demotionConfig(DemotionStrategy.LRU, 0.01, 0.005, 100);
            var dm = demotionManager(List.of(singleTier), singleMeta, config);

            var demoted = dm.demote();

            assertThat(demoted).isZero();
        }
    }

    @Nested
    class StatsTests {

        @Test
        void stats_initiallyEmpty() {
            var config = demotionConfig(DemotionStrategy.LRU, 0.9, 0.7, 100);
            var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            var s = dm.stats();

            assertThat(s.blocksDemoted()).isZero();
            assertThat(s.bytesMoved()).isZero();
            assertThat(s.lastRunMs()).isZero();
        }

        @Test
        void stats_tracksMovedBlocks() {
            storeInMemory(BLOCK_A);
            storeInMemory(BLOCK_B);

            var config = demotionConfig(DemotionStrategy.LRU, 0.01, 0.005, 100);
            var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            dm.demote();

            var s = dm.stats();
            assertThat(s.blocksDemoted()).isGreaterThan(0);
            assertThat(s.bytesMoved()).isGreaterThan(0);
            assertThat(s.lastRunMs()).isGreaterThan(0);
        }
    }
}
