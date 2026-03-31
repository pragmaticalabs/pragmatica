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
import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.PromotionConfig.promotionConfig;
import static org.pragmatica.storage.PromotionManager.promotionManager;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;

/// Integration tests for DemotionManager + PromotionManager coordination
/// and GC orphan collection through StorageInstance.
class TierManagementIntegrationTest {

    private static final long TIER_MAX = 500;
    private static final byte[] CONTENT_A = "tier-management-alpha-block".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "tier-management-bravo-block".getBytes(StandardCharsets.UTF_8);

    private MemoryTier memoryTier;
    private MemoryTier diskTier;
    private MetadataStore metadataStore;
    private StorageInstance instance;

    @BeforeEach
    void setUp() {
        memoryTier = MemoryTier.memoryTier(TIER_MAX, TierLevel.MEMORY);
        diskTier = MemoryTier.memoryTier(TIER_MAX * 10, TierLevel.LOCAL_DISK);
        metadataStore = MetadataStore.inMemoryMetadataStore("tier-mgmt-test");
        instance = StorageInstance.storageInstance("tier-mgmt", List.of(memoryTier, diskTier), metadataStore);
    }

    private BlockId storeBlock(byte[] content) {
        return instance.put(content).await()
                       .fold(c -> { fail("put failed: " + c.message()); return null; },
                             id -> id);
    }

    /// Store a block directly in the memory tier and register lifecycle metadata.
    /// Bypasses StorageInstance to ensure metadata correctly reflects MEMORY tier presence.
    private BlockId storeInMemoryDirectly(byte[] content) {
        var id = BlockId.blockId(content)
                        .fold(c -> { fail("blockId failed: " + c.message()); return null; },
                              blockId -> blockId);
        memoryTier.put(id, content).await()
                  .onFailure(c -> fail("put failed: " + c.message()));
        metadataStore.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
        return id;
    }

    @Nested
    class DemotionPromotionRoundTripTests {

        @Test
        void demotion_then_promotion_roundTrip() {
            // Store directly in memory tier with correct metadata tracking
            var id = storeInMemoryDirectly(CONTENT_A);

            assertThat(memoryTier.usedBytes()).isGreaterThan(0);

            // Trigger demotion with very low watermark to force it
            var demotionCfg = demotionConfig(DemotionStrategy.LRU, 0.01, 0.005, 100);
            var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, demotionCfg);
            dm.activate();

            var demoted = dm.demote();

            assertThat(demoted).isGreaterThan(0);
            assertThat(diskTier.usedBytes()).isGreaterThan(0);

            // Verify block is now in disk tier only
            var lcAfterDemotion = metadataStore.getLifecycle(id);
            lcAfterDemotion.onPresent(lc -> {
                assertThat(lc.presentIn()).contains(TierLevel.LOCAL_DISK);
                assertThat(lc.presentIn()).doesNotContain(TierLevel.MEMORY);
            });

            // Simulate high access count to trigger promotion
            simulateHighAccessCount(id, 100);

            // Trigger promotion with low threshold
            var promConfig = promotionConfig(5, 300_000L, 10);
            var pm = promotionManager(List.of(memoryTier, diskTier), metadataStore, promConfig);
            pm.activate();

            var promoted = pm.promote();

            assertThat(promoted).isGreaterThan(0);

            // Verify block is back in memory tier
            var lcAfterPromotion = metadataStore.getLifecycle(id);
            lcAfterPromotion.onPresent(lc -> {
                assertThat(lc.presentIn()).contains(TierLevel.MEMORY);
                assertThat(lc.presentIn()).contains(TierLevel.LOCAL_DISK);
            });

            // Verify data is readable from memory tier
            memoryTier.get(id).await()
                      .onFailure(c -> fail("get failed: " + c.message()))
                      .onSuccess(opt -> {
                          assertThat(opt.isPresent()).isTrue();
                          opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                      });
        }
    }

    @Nested
    class GarbageCollectionTests {

        @Test
        void gc_collectsOrphanedBlocks() {
            var id = storeBlock(CONTENT_A);
            storeBlock(CONTENT_B);

            // Make block A orphaned and past grace period
            var gracePeriodMs = 100L;
            var expired = System.currentTimeMillis() - gracePeriodMs - 50;
            metadataStore.computeLifecycle(id, lc -> new BlockLifecycle(
                lc.blockId(), lc.presentIn(), 0, expired, lc.createdAt(), lc.accessCount()));

            var gc = storageGarbageCollector(instance, metadataStore, garbageCollectorConfig(gracePeriodMs, 500));
            gc.activate();

            var collected = gc.collectGarbage();

            assertThat(collected).isEqualTo(1);
            assertThat(metadataStore.containsBlock(id)).isFalse();

            // Verify block is no longer retrievable
            instance.get(id).await()
                    .onFailure(c -> fail("get failed: " + c.message()))
                    .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void gc_preservesReferencedBlocks() {
            var id = storeBlock(CONTENT_A);
            instance.createRef("keep-ref", id).await();

            var gracePeriodMs = 1L;
            var gc = storageGarbageCollector(instance, metadataStore, garbageCollectorConfig(gracePeriodMs, 500));
            gc.activate();

            var collected = gc.collectGarbage();

            assertThat(collected).isZero();
            assertThat(metadataStore.containsBlock(id)).isTrue();
        }
    }

    private void simulateHighAccessCount(BlockId id, int count) {
        var now = System.currentTimeMillis();
        metadataStore.computeLifecycle(id, lc -> BlockLifecycle.blockLifecycle(
            lc.blockId(), lc.presentIn(), lc.refCount(), now, lc.createdAt(), count));
    }
}
