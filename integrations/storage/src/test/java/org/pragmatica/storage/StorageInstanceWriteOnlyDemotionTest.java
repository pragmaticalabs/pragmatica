package org.pragmatica.storage;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.DemotionConfig.demotionConfig;
import static org.pragmatica.storage.DemotionManager.demotionManager;

/// #886: a block that is WRITTEN AND NEVER READ must be visible to demotion.
///
/// Nothing here reads a block back through the instance: the read path re-records tier presence
/// and would repair exactly the metadata these tests pin, which is why every write-then-read test
/// stayed green over the defect. All assertions run against the instance's own [MetadataStore] and
/// the demotion manager's own [DemotionManager.DemotionStats]. This module has no maintenance
/// scheduler; the single `demote()` call is the only producer of those stats, and no
/// [PromotionManager] or [PrefetchManager] is constructed, so nothing else can add MEMORY presence.
class StorageInstanceWriteOnlyDemotionTest {
    private static final long MEMORY_MAX = 1_000;
    private static final int BLOCK_SIZE = 100;
    // 600 of 1000 bytes: above the 0.5 high watermark, below the tier's hard cap (a full MemoryTier
    // rejects the put outright, which is a different path from the one under test).
    private static final int BLOCK_COUNT = 6;
    private static final double HIGH_WATERMARK = 0.5;
    private static final double LOW_WATERMARK = 0.2;

    @TempDir
    Path tempDir;

    private MemoryTier memoryTier;
    private LocalDiskTier diskTier;
    private MetadataStore metadataStore;
    private StorageInstance instance;

    @BeforeEach
    void setUp() {
        memoryTier = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY);
        diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), MEMORY_MAX * 100).unwrap();
        metadataStore = MetadataStore.inMemoryMetadataStore("write-only-886");
        instance = StorageInstance.storageInstance("write-only-886", List.of(memoryTier, diskTier), metadataStore);
    }

    @Test
    void writeOnly_blocksAreListedUnderMemoryTier() {
        var ids = writeBlocksWithoutReading(instance);

        // Control: the bytes really are resident in the memory tier.
        assertThat(memoryTier.usedBytes()).isEqualTo((long) BLOCK_COUNT * BLOCK_SIZE);

        var listed = metadataStore.listBlocksByTier(TierLevel.MEMORY)
                                  .stream()
                                  .map(BlockLifecycle::blockId)
                                  .toList();

        assertThat(listed).containsExactlyInAnyOrderElementsOf(ids);
        ids.forEach(id -> assertThat(metadataStore.getLifecycle(id).unwrap().presentIn())
                              .containsExactlyInAnyOrder(TierLevel.MEMORY, TierLevel.LOCAL_DISK));
    }

    @Test
    void writeOnly_demotionTickMovesBytesOutOfMemory() {
        var ids = writeBlocksWithoutReading(instance);
        var usedBefore = memoryTier.usedBytes();
        var config = demotionConfig(DemotionStrategy.LRU, HIGH_WATERMARK, LOW_WATERMARK, 100);
        var dm = demotionManager(List.of(memoryTier, diskTier), metadataStore, config);

        dm.activate();
        // Control: no tick has run yet, so the stats this test reads start from zero.
        assertThat(dm.stats().bytesMoved()).isZero();

        var demoted = dm.demote();

        var stats = dm.stats();

        assertThat(demoted).isGreaterThan(0);
        assertThat(stats.blocksDemoted()).isEqualTo(demoted);
        assertThat(stats.bytesMoved()).isEqualTo((long) demoted * BLOCK_SIZE);
        assertThat(memoryTier.usedBytes()).isEqualTo(usedBefore - stats.bytesMoved());
        assertThat(memoryTier.usedBytes()).isLessThan((long) (MEMORY_MAX * LOW_WATERMARK));

        var stillInMemory = metadataStore.listBlocksByTier(TierLevel.MEMORY)
                                         .stream()
                                         .map(BlockLifecycle::blockId)
                                         .toList();

        assertThat(stillInMemory).hasSize(BLOCK_COUNT - demoted);

        ids.stream()
           .filter(id -> !stillInMemory.contains(id))
           .forEach(id -> {
               assertThat(metadataStore.getLifecycle(id).unwrap().presentIn())
                   .containsExactly(TierLevel.LOCAL_DISK);
               assertThat(memoryTier.get(id).await().unwrap().isEmpty()).isTrue();
               assertThat(diskTier.get(id).await().unwrap().isPresent()).isTrue();
           });
    }

    /// The write-behind path was never affected by #886: its record names the fast tier, so the
    /// block is listed under MEMORY without a read. Pinned so the fix cannot regress it, and the
    /// record is pinned to exactly `{MEMORY}`: the claim names the fast tier and finalization adds
    /// that same tier, while the durable tier is never declared present until a read finds the
    /// drained bytes there. A claim naming the durable tier instead (the write-through choice)
    /// would finalize as `{MEMORY, LOCAL_DISK}` and declare disk residency for bytes still queued.
    @Test
    void writeBehind_writeOnly_blockIsListedUnderFastTier() {
        var behindMemory = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY);
        var behindDisk = MemoryTier.memoryTier(MEMORY_MAX * 100, TierLevel.LOCAL_DISK);
        var behindStore = MetadataStore.inMemoryMetadataStore("write-behind-886");
        var behind = StorageInstance.storageInstance("write-behind-886",
                                                     List.of(behindMemory, behindDisk),
                                                     behindStore,
                                                     WritePolicy.WRITE_BEHIND);

        try {
            var ids = writeBlocksWithoutReading(behind);

            var listed = behindStore.listBlocksByTier(TierLevel.MEMORY)
                                    .stream()
                                    .map(BlockLifecycle::blockId)
                                    .toList();

            assertThat(listed).containsExactlyInAnyOrderElementsOf(ids);
            ids.forEach(id -> assertThat(behindStore.getLifecycle(id).unwrap().presentIn())
                                  .containsExactly(TierLevel.MEMORY));
        } finally {
            behind.shutdown();
        }
    }

    /// The claim IS the record, so a reference added while the first write is still in flight (a
    /// second put of the same content deduplicates onto the claim) survives finalization. The
    /// previous re-create replaced the record and dropped that count back to 1, under-counting a
    /// live reference. The durable tier's put is held open until the duplicate has been counted.
    @Test
    void writeThrough_duplicatePutWhileWriteInFlight_refCountSurvivesFinalization() {
        var gate = Promise.<Unit>promise();
        var putEntered = new CountDownLatch(1);
        var gatedDisk = new GatedPutTier(MemoryTier.memoryTier(MEMORY_MAX * 100, TierLevel.LOCAL_DISK), gate, putEntered);
        var gatedStore = MetadataStore.inMemoryMetadataStore("in-flight-886");
        var gated = StorageInstance.storageInstance("in-flight-886",
                                                    List.of(MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY), gatedDisk),
                                                    gatedStore);
        var content = distinctBlock(0);

        var first = gated.put(content);

        assertThat(awaitEntered(putEntered)).isTrue();

        var duplicate = gated.put(content).await().unwrap();

        // Control: the duplicate landed on the claim while the durable write was still open.
        assertThat(gatedStore.getLifecycle(duplicate).unwrap().refCount()).isEqualTo(2);

        gate.succeed(unit());

        var id = first.await().unwrap();
        var lifecycle = gatedStore.getLifecycle(id).unwrap();

        assertThat(id).isEqualTo(duplicate);
        assertThat(lifecycle.refCount()).isEqualTo(2);
        assertThat(lifecycle.presentIn()).containsExactlyInAnyOrder(TierLevel.MEMORY, TierLevel.LOCAL_DISK);
    }

    private static boolean awaitEntered(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /// Delegates everything to `delegate`; `put` signals `entered` and completes only after `gate`.
    private record GatedPutTier(StorageTier delegate, Promise<Unit> gate, CountDownLatch entered) implements StorageTier {
        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            entered.countDown();

            return gate.flatMap(_ -> delegate.put(id, content));
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return delegate.level();
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }
    }

    private static List<BlockId> writeBlocksWithoutReading(StorageInstance target) {
        return IntStream.range(0, BLOCK_COUNT)
                        .mapToObj(StorageInstanceWriteOnlyDemotionTest::distinctBlock)
                        .map(content -> target.put(content).await().unwrap())
                        .toList();
    }

    private static byte[] distinctBlock(int seed) {
        var content = new byte[BLOCK_SIZE];

        Arrays.fill(content, (byte) (seed + 1));

        return content;
    }
}
