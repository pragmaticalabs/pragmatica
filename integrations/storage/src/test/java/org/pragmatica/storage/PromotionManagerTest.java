package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.PromotionConfig.promotionConfig;
import static org.pragmatica.storage.PromotionManager.promotionManager;

class PromotionManagerTest {

    private static final long TIER_MAX = 2000;
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
        metadataStore = MetadataStore.inMemoryMetadataStore("promotion-test");
    }

    /// Store a block in the disk tier and register its lifecycle metadata with given access count.
    private BlockId storeInDisk(byte[] content, int accessCount) {
        var id = BlockId.blockId(content)
                        .fold(c -> { fail("blockId failed: " + c.message()); return null; },
                              blockId -> blockId);
        diskTier.put(id, content).await()
                .onFailure(c -> fail("put failed: " + c.message()));

        var now = System.currentTimeMillis();
        var lifecycle = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);
        // Reconstruct with desired access count and recent access time
        var updated = BlockLifecycle.blockLifecycle(
            id, lifecycle.presentIn(), lifecycle.refCount(), now, lifecycle.createdAt(), accessCount);
        metadataStore.createLifecycle(updated);
        return id;
    }

    /// Store a block in the disk tier with zero access count.
    private BlockId storeInDiskCold(byte[] content) {
        return storeInDisk(content, 0);
    }

    @Nested
    class PromotionTests {

        @Test
        void promote_highAccessCount_promotesToFasterTier() {
            // threshold=5, window=5min, batch=10
            var config = promotionConfig(5, 300_000L, 10);
            var id = storeInDisk(BLOCK_A, 100);

            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, config);
            pm.activate();

            var promoted = pm.promote();

            assertThat(promoted).isEqualTo(1);
            assertThat(memoryTier.usedBytes()).isGreaterThan(0);

            // Block should now be present in both MEMORY and LOCAL_DISK tiers.
            var lc = metadataStore.getLifecycle(id);
            lc.onPresent(l -> {
                assertThat(l.presentIn()).contains(TierLevel.MEMORY);
                assertThat(l.presentIn()).contains(TierLevel.LOCAL_DISK);
            });
        }

        @Test
        void promote_lowAccessCount_noAction() {
            // threshold=50, window=5min — block has 0 accesses
            var config = promotionConfig(50, 300_000L, 10);
            storeInDiskCold(BLOCK_A);

            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, config);
            pm.activate();

            var promoted = pm.promote();

            assertThat(promoted).isZero();
            assertThat(memoryTier.usedBytes()).isZero();
        }

        @Test
        void promote_outsideWindow_noAction() {
            var config = promotionConfig(5, 300_000L, 10);
            var id = BlockId.blockId(BLOCK_A)
                            .fold(c -> { fail("blockId failed: " + c.message()); return null; },
                                  blockId -> blockId);
            diskTier.put(id, BLOCK_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()));

            // Set lastAccessedAt to 10 minutes ago (outside 5 min window)
            var tenMinutesAgo = System.currentTimeMillis() - 600_000L;
            var lifecycle = BlockLifecycle.blockLifecycle(
                id, EnumSet.of(TierLevel.LOCAL_DISK), 1, tenMinutesAgo, tenMinutesAgo, 100);
            metadataStore.createLifecycle(lifecycle);

            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, config);
            pm.activate();

            var promoted = pm.promote();

            assertThat(promoted).isZero();
        }

        @Test
        void promote_alreadyInFastestTier_noAction() {
            var config = promotionConfig(5, 300_000L, 10);
            var id = BlockId.blockId(BLOCK_A)
                            .fold(c -> { fail("blockId failed: " + c.message()); return null; },
                                  blockId -> blockId);
            diskTier.put(id, BLOCK_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()));
            memoryTier.put(id, BLOCK_A).await()
                      .onFailure(c -> fail("put failed: " + c.message()));

            // Block already in MEMORY and LOCAL_DISK
            var now = System.currentTimeMillis();
            var lifecycle = BlockLifecycle.blockLifecycle(
                id, EnumSet.of(TierLevel.MEMORY, TierLevel.LOCAL_DISK), 1, now, now, 100);
            metadataStore.createLifecycle(lifecycle);

            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, config);
            pm.activate();

            var promoted = pm.promote();

            assertThat(promoted).isZero();
        }
    }

    @Nested
    class DormantActiveTests {

        @Test
        void promote_dormant_noAction() {
            var config = promotionConfig(5, 300_000L, 10);
            storeInDisk(BLOCK_A, 100);

            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, config);
            // Not activated — dormant

            var promoted = pm.promote();

            assertThat(promoted).isZero();
            assertThat(memoryTier.usedBytes()).isZero();
        }

        @Test
        void promote_activateDeactivate_lifecycle() {
            var config = promotionConfig(5, 300_000L, 10);
            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            assertThat(pm.isActive()).isFalse();

            pm.activate();
            assertThat(pm.isActive()).isTrue();

            pm.deactivate();
            assertThat(pm.isActive()).isFalse();
        }
    }

    @Nested
    class BatchTests {

        @Test
        void promote_batchLimited() {
            // batch size 2, all blocks have high access counts
            var config = promotionConfig(5, 300_000L, 2);
            storeInDisk(BLOCK_A, 100);
            storeInDisk(BLOCK_B, 200);
            storeInDisk(BLOCK_C, 150);
            storeInDisk(BLOCK_D, 300);

            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, config);
            pm.activate();

            var promoted = pm.promote();

            assertThat(promoted).isLessThanOrEqualTo(2);
        }
    }

    @Nested
    class StatsTests {

        @Test
        void stats_initiallyEmpty() {
            var config = promotionConfig(50, 300_000L, 10);
            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, config);

            var s = pm.stats();

            assertThat(s.blocksPromoted()).isZero();
            assertThat(s.bytesMoved()).isZero();
            assertThat(s.lastRunMs()).isZero();
        }

        @Test
        void stats_tracksPromotions() {
            var config = promotionConfig(5, 300_000L, 10);
            storeInDisk(BLOCK_A, 100);
            storeInDisk(BLOCK_B, 200);

            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, config);
            pm.activate();

            pm.promote();

            var s = pm.stats();
            assertThat(s.blocksPromoted()).isGreaterThan(0);
            assertThat(s.bytesMoved()).isGreaterThan(0);
            assertThat(s.lastRunMs()).isGreaterThan(0);
        }
    }
}
