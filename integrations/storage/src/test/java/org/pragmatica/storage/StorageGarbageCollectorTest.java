package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
    }

    private BlockId storeBlock(byte[] content) {
        return instance.put(content).await()
                       .fold(c -> { fail("put failed: " + c.message()); return null; },
                             id -> id);
    }

    private void makeOrphanedPastGrace(BlockId blockId) {
        var expired = System.currentTimeMillis() - GRACE_PERIOD_MS - 100;
        metadataStore.computeLifecycle(blockId, lc -> new BlockLifecycle(
            lc.blockId(), lc.presentIn(), 0, expired, lc.createdAt(), lc.accessCount()));
    }

    private void makeOrphanedRecent(BlockId blockId) {
        metadataStore.computeLifecycle(blockId, lc -> new BlockLifecycle(
            lc.blockId(), lc.presentIn(), 0, System.currentTimeMillis(), lc.createdAt(), lc.accessCount()));
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
}
