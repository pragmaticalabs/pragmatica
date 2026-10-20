package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;

/// The cache-only marker as a record: what it is, what ends it, what a claim does to it, and that GC leaves
/// it alone.
class CacheOnlyMarkerTest {
    private static final long GRACE_MS = 1_000;
    private static final byte[] CONTENT = "marker-block".getBytes(StandardCharsets.UTF_8);

    private MetadataStore store;
    private BlockId id;

    @BeforeEach
    void setUp() {
        store = MetadataStore.inMemoryMetadataStore("marker");
        id = BlockId.blockId(CONTENT).unwrap();
    }

    private BlockLifecycle marker(TierLevel... tiers) {
        return BlockLifecycle.cacheOnlyLifecycle(id, EnumSet.copyOf(List.of(tiers)));
    }

    @Test
    void cacheOnlyLifecycle_isUnreferencedAndNotOrphaned() {
        var marker = marker(TierLevel.REMOTE);

        assertThat(marker.cacheOnly()).isTrue();
        assertThat(marker.refCount()).isZero();
        assertThat(marker.isOrphaned()).isFalse();
        // Control: the same shape without the marker IS an orphan, so the assertion above is the marker's doing.
        assertThat(new BlockLifecycle(id, EnumSet.of(TierLevel.REMOTE), 0, 1L, 1L, 0, 1L).isOrphaned()).isTrue();
    }

    @Test
    void withRefCountIncremented_endsTheMarker() {
        var owned = marker(TierLevel.REMOTE).withRefCountIncremented();

        assertThat(owned.cacheOnly()).isFalse();
        assertThat(owned.refCount()).isEqualTo(1);
    }

    @Test
    void withRefCountIncrementedIfOwned_leavesAMarkerAsItIs() {
        var marker = marker(TierLevel.REMOTE);

        assertThat(marker.withRefCountIncrementedIfOwned()).isEqualTo(marker);
        assertThat(BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK).withRefCountIncrementedIfOwned().refCount())
            .isEqualTo(2);
    }

    @Test
    void copyingAMarker_keepsItAMarker() {
        var marker = marker(TierLevel.REMOTE);

        assertThat(marker.withTierAdded(TierLevel.MEMORY).cacheOnly()).isTrue();
        assertThat(marker.withTierRemoved(TierLevel.REMOTE).cacheOnly()).isTrue();
        assertThat(marker.withAccessTimestamp().cacheOnly()).isTrue();
        assertThat(marker.withAccessTimestamp().accessCount()).isEqualTo(1);
        assertThat(marker.withRefCountDecremented().cacheOnly()).isTrue();
    }

    @Test
    void claimBlock_forARealBlock_replacesAMarkerAndKeepsItsTiers() {
        store.claimBlock(id, marker(TierLevel.REMOTE, TierLevel.MEMORY));

        var claimed = store.claimBlock(id, BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK));

        assertThat(claimed).isTrue();
        var record = store.getLifecycle(id).unwrap();
        assertThat(record.cacheOnly()).isFalse();
        assertThat(record.refCount()).isEqualTo(1);
        assertThat(record.presentIn()).containsExactlyInAnyOrder(TierLevel.REMOTE, TierLevel.MEMORY, TierLevel.LOCAL_DISK);
    }

    @Test
    void claimBlock_forAMarker_doesNotReplaceAnOwnedRecord() {
        var owned = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

        store.claimBlock(id, owned);

        assertThat(store.claimBlock(id, marker(TierLevel.REMOTE))).isFalse();
        assertThat(store.getLifecycle(id).unwrap()).isEqualTo(owned);
    }

    @Test
    void claimBlock_forAMarker_doesNotReplaceAnotherMarker() {
        var first = marker(TierLevel.REMOTE);

        store.claimBlock(id, first);

        assertThat(store.claimBlock(id, marker(TierLevel.MEMORY))).isFalse();
        assertThat(store.getLifecycle(id).unwrap()).isEqualTo(first);
    }

    @Test
    void claimBlock_forARealBlock_doesNotReplaceAnOwnedRecord() {
        var owned = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

        store.claimBlock(id, owned);

        assertThat(store.claimBlock(id, BlockLifecycle.blockLifecycle(id, TierLevel.REMOTE))).isFalse();
        assertThat(store.getLifecycle(id).unwrap()).isEqualTo(owned);
    }

    @Test
    void collectGarbage_skipsAMarker_butCollectsAnOrphanBesideIt() {
        var memory = MemoryTier.memoryTier(1 << 20);
        var instance = StorageInstance.storageInstance("gc-marker", List.of(memory), store);
        var gc = storageGarbageCollector(instance, store, garbageCollectorConfig(GRACE_MS, 100));
        var orphanId = BlockId.blockId("orphan".getBytes(StandardCharsets.UTF_8)).unwrap();
        var longAgo = System.currentTimeMillis() - 10 * GRACE_MS;

        gc.activate();
        memory.put(id, CONTENT).await();
        memory.put(orphanId, "orphan".getBytes(StandardCharsets.UTF_8)).await();
        store.createLifecycle(new BlockLifecycle(id, EnumSet.of(TierLevel.MEMORY), 0, longAgo, longAgo, 0, 0L, true));
        store.createLifecycle(new BlockLifecycle(orphanId, EnumSet.of(TierLevel.MEMORY), 0, longAgo, longAgo, 0, longAgo));

        var collected = gc.collectGarbage();

        assertThat(collected).as("only the orphan is collected").isEqualTo(1);
        assertThat(store.containsBlock(id)).isTrue();
        assertThat(memory.exists(id).await().unwrap()).isTrue();
        assertThat(store.containsBlock(orphanId)).isFalse();
    }
}
