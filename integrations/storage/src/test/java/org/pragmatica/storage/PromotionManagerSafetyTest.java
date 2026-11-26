package org.pragmatica.storage;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.PromotionConfig.promotionConfig;
import static org.pragmatica.storage.PromotionManager.promotionManager;

/// Pins the promotion fixes that wiring the manager into production needs: the target-tier candidate test, the
/// target watermark, the between-blocks `active` check, the partial-copy discard and the failure counters.
class PromotionManagerSafetyTest {
    private static final long MEMORY_MAX = 2000;
    private static final int BLOCK_BYTES = 400;

    private MemoryTier memory;
    private SpyTier disk;
    private SpyTier remote;
    private MetadataStore metadata;

    @BeforeEach
    void setUp() {
        memory = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY);
        disk = new SpyTier(MemoryTier.memoryTier(100_000, TierLevel.LOCAL_DISK));
        remote = new SpyTier(MemoryTier.memoryTier(100_000, TierLevel.REMOTE));
        metadata = MetadataStore.inMemoryMetadataStore("promotion-safety-test");
    }

    @Nested
    class CandidateSelection {
        @Test
        void promote_blockOnDiskAndRemote_isNotRecopiedWhenMemoryIsFull() {
            fill(memory, 1900);
            var id = storeHot(remote, EnumSet.of(TierLevel.LOCAL_DISK, TierLevel.REMOTE), block(1), disk);
            var pm = promotionManager(List.of(memory, disk, remote), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();
            disk.puts.set(0);
            pm.promote();
            pm.promote();

            assertThat(disk.puts.get()).as("the block is already on the disk tier; a candidate test that only asks "
                                           + "'is it in memory' copies it from the remote tier on every cycle")
                                       .isZero();
            assertThat(metadata.getLifecycle(id).unwrap().presentIn()).containsExactlyInAnyOrder(TierLevel.LOCAL_DISK,
                                                                                                   TierLevel.REMOTE);
        }
    }

    /// Every promotion step writes to the tier `targetFor` names, and only that step's target: a block that exists only on the
    /// remote tier is copied to the DISK tier, not to memory, and not past it. Memory sits above its watermark, so the disk to
    /// memory step is skipped and the block's landing place is unambiguous.
    @Nested
    class TargetTier {
        @Test
        void promote_remoteBlock_landsInTheDiskTier_notInMemory() {
            fill(memory, 1900);
            var id = storeHot(remote, EnumSet.of(TierLevel.REMOTE), block(1));
            var pm = promotionManager(List.of(memory, disk, remote), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();

            assertThat(pm.promote()).isEqualTo(1);
            assertThat(disk.exists(id).await().unwrap()).as("the copy is in the DISK tier").isTrue();
            assertThat(memory.exists(id).await().unwrap()).as("and not in memory").isFalse();
            assertThat(metadata.getLifecycle(id).unwrap().presentIn()).containsExactlyInAnyOrder(TierLevel.LOCAL_DISK, TierLevel.REMOTE);
        }
    }

    @Nested
    class TargetWatermark {
        @Test
        void promote_targetAtWatermark_readsAndCopiesNothing() {
            fill(memory, 1450);
            storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(1));
            var pm = promotionManager(List.of(memory, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();

            assertThat(pm.promote()).isZero();
            assertThat(disk.gets.get()).as("a target at its watermark must not cost a source read").isZero();
            assertThat(memory.usedBytes()).isEqualTo(1450);
        }

        @Test
        void promote_targetExactlyAtWatermark_readsNothing() {
            fill(memory, 1400);
            storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(1));
            var pm = promotionManager(List.of(memory, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();

            assertThat(pm.promote()).isZero();
            assertThat(disk.gets.get()).as("a tier already AT its ceiling has no room for any block: no source read")
                                       .isZero();
        }

        @Test
        void promote_blockWouldPassWatermark_isSkippedWithoutFailure() {
            fill(memory, 1100);
            storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(1));
            var pm = promotionManager(List.of(memory, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();

            assertThat(pm.promote()).as("1100 + 400 bytes is past the 70% target of 2000").isZero();
            assertThat(memory.usedBytes()).isEqualTo(1100);
            assertThat(pm.stats().failed()).as("a policy skip is not a failed copy").isZero();
        }

        @Test
        void promote_blockLandingExactlyOnTheWatermark_isCopied() {
            fill(memory, 1000);
            var id = storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(1));
            var pm = promotionManager(List.of(memory, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();

            assertThat(pm.promote()).as("1000 + 400 bytes is exactly the 70% target of 2000: at the ceiling, not past it")
                                    .isEqualTo(1);
            assertThat(memory.exists(id).await().unwrap()).isTrue();
        }

        @Test
        void promote_thenDemote_noBlockMovesBothWays() {
            var demotion = DemotionManager.demotionManager(List.of(memory, disk),
                                                           metadata,
                                                           DemotionConfig.demotionConfig());

            for (var i = 0; i < 5; i++) {
                storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(i));
            }

            var pm = promotionManager(List.of(memory, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();
            demotion.activate();

            for (var cycle = 0; cycle < 5; cycle++) {
                demotion.demote();
                pm.promote();
            }

            assertThat(demotion.stats().blocksDemoted()).as("promotion stayed under the demotion high watermark, so "
                                                            + "demotion never had a block to take back")
                                                        .isZero();
            assertThat(memory.usedBytes()).isLessThanOrEqualTo((long) (MEMORY_MAX * 0.7));
            assertThat(pm.stats().blocksPromoted()).isEqualTo(3);
        }
    }

    @Nested
    class Deactivation {
        @Test
        void promote_deactivatedDuringBatch_stopsWithinOneBlock() {
            var pmRef = new AtomicReference<PromotionManager>();
            var deactivating = new FaultyTier(memory, () -> {
                pmRef.get().deactivate();
                return Option.<Cause>none();
            });

            for (var i = 0; i < 3; i++) {
                storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(i));
            }

            var pm = promotionManager(List.of(deactivating, disk), metadata, promotionConfig(5, 300_000L, 10));

            pmRef.set(pm);
            pm.activate();

            assertThat(pm.promote()).as("the first copy lands, the deactivation it triggers ends the batch").isEqualTo(1);
        }
    }

    @Nested
    class FailedWrites {
        private static final Cause DISK_ERROR = Causes.cause("simulated target I/O error");

        @Test
        void promote_failedWrite_discardsPartialCopyAndCountsFailure() {
            var faulty = new FaultyTier(memory, () -> Option.some(DISK_ERROR), true);
            var id = storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(1));
            var pm = promotionManager(List.of(faulty, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();

            assertThat(pm.promote()).isZero();
            assertThat(memory.exists(id).await().unwrap()).as("the truncated copy the failed write left behind must "
                                                              + "be deleted, or it shadows the durable copy")
                                                          .isFalse();
            assertThat(pm.stats().failed()).isEqualTo(1);
            assertThat(metadata.getLifecycle(id).unwrap().presentIn()).as("no record of a copy that failed")
                                                                      .containsExactly(TierLevel.LOCAL_DISK);
        }

        @Test
        void stats_failedAccumulatesAcrossCycles() {
            var faulty = new FaultyTier(memory, () -> Option.some(DISK_ERROR));

            storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(1));
            storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(2));

            var pm = promotionManager(List.of(faulty, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();
            pm.promote();

            assertThat(pm.stats().failed()).as("both candidates failed in the one cycle").isEqualTo(2);

            pm.promote();

            assertThat(pm.stats().failed()).as("the next cycle adds to the running total").isEqualTo(4);
        }

        @Test
        void promote_tierFullWrite_keepsTheCopyAlreadyThere() {
            var full = StorageError.TierFull.tierFull(TierLevel.MEMORY, 0, 0);
            var faulty = new FaultyTier(memory, () -> Option.<Cause>some(full));
            var id = storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(1));

            memory.put(id, block(1)).await();

            var pm = promotionManager(List.of(faulty, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();
            pm.promote();

            assertThat(memory.exists(id).await().unwrap()).as("a tier that refused before writing wrote nothing, so a "
                                                              + "valid copy already there must survive")
                                                          .isTrue();
            assertThat(pm.stats().failed()).isEqualTo(1);
        }
    }

    @Nested
    class SourceTier {
        @Test
        void promote_sourceReadFails_countsAFailureAndCopiesNothing() {
            var broken = new ReadFailingTier(disk);
            var id = storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(1));
            var pm = promotionManager(List.of(memory, broken), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();

            assertThat(pm.promote()).isZero();
            assertThat(pm.stats().failed()).as("a source that cannot be read is a failed copy").isEqualTo(1);
            assertThat(memory.exists(id).await().unwrap()).isFalse();
        }

        @Test
        void promote_recordNamesABlockTheSourceLacks_isSkippedNotCounted() {
            var now = System.currentTimeMillis();
            var id = BlockId.blockId(block(1)).unwrap();

            metadata.createLifecycle(BlockLifecycle.blockLifecycle(id, EnumSet.of(TierLevel.LOCAL_DISK), 1, now, now, 100));
            reads(id, 100);

            var pm = promotionManager(List.of(memory, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.activate();

            assertThat(pm.promote()).isZero();
            assertThat(pm.stats().failed()).as("an absent source copy is a stale record, not a failing tier").isZero();
        }
    }

    @Nested
    class Configuration {
        @Test
        void promotionConfig_defaultTargetWatermark_isTheDemotionLowWatermark() {
            assertThat(PromotionConfig.promotionConfig().targetWatermark())
                    .isEqualTo(DemotionConfig.demotionConfig().lowWatermark());
            assertThat(promotionConfig(5, 300_000L, 10).targetWatermark())
                    .as("the three-argument factory keeps the default")
                    .isEqualTo(DemotionConfig.demotionConfig().lowWatermark());
        }

        @Test
        void promotionConfig_targetWatermark_isClampedToARatio() {
            assertThat(PromotionConfig.promotionConfig(5, 300_000L, 10, 7.0).targetWatermark()).isEqualTo(1.0);
            assertThat(PromotionConfig.promotionConfig(5, 300_000L, 10, -1.0).targetWatermark()).isEqualTo(0.01);
        }
    }

    @Nested
    class Counters {
        @Test
        void stats_countCyclesAndCopies_onlyWhileActive() {
            storeHot(disk, EnumSet.of(TierLevel.LOCAL_DISK), block(1));
            var pm = promotionManager(List.of(memory, disk), metadata, promotionConfig(5, 300_000L, 10));

            pm.promote();
            assertThat(pm.stats().cycles()).as("a dormant manager runs no cycle").isZero();

            pm.activate();
            pm.promote();
            pm.promote();

            assertThat(pm.stats().cycles()).isEqualTo(2);
            assertThat(pm.stats().blocksPromoted()).isEqualTo(1);
            assertThat(pm.stats().failed()).as("a successful copy is not a failed one").isZero();
            assertThat(pm.stats().bytesMoved()).isEqualTo(BLOCK_BYTES);
        }

        @Test
        void noop_neverActivatesAndNeverPromotes() {
            PromotionManager.NOOP.activate();

            assertThat(PromotionManager.NOOP.isActive()).isFalse();
            assertThat(PromotionManager.NOOP.promote()).isZero();
            assertThat(PromotionManager.NOOP.stats()).isEqualTo(PromotionManager.PromotionStats.promotionStats());
        }
    }

    private static byte[] block(int seed) {
        var content = new byte[BLOCK_BYTES];

        java.util.Arrays.fill(content, (byte) (seed + 1));
        content[0] = (byte) seed;

        return content;
    }

    private static void fill(MemoryTier tier, int bytes) {
        var filler = BlockId.blockId(("filler-" + bytes).getBytes()).unwrap();

        tier.put(filler, new byte[bytes]).await();
    }

    private void reads(BlockId id, int count) {
        for (var i = 0; i < count; i++) {
            metadata.accessTracker().record(id);
        }
    }

    private BlockId storeHot(StorageTier tier, Set<TierLevel> presentIn, byte[] content, StorageTier... alsoOn) {
        var id = BlockId.blockId(content).unwrap();
        var now = System.currentTimeMillis();

        tier.put(id, content).await();
        List.of(alsoOn).forEach(other -> other.put(id, content).await());
        metadata.createLifecycle(BlockLifecycle.blockLifecycle(id, presentIn, 1, now, now, 100));
        reads(id, 100);

        return id;
    }

    /// A source tier whose reads fail.
    private static final class ReadFailingTier implements StorageTier {
        private final StorageTier delegate;

        ReadFailingTier(StorageTier delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return Causes.cause("simulated read failure").promise();
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return delegate.put(id, content);
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

    /// A tier that counts the calls promotion makes against it.
    private static final class SpyTier implements StorageTier {
        final AtomicInteger puts = new AtomicInteger();
        final AtomicInteger gets = new AtomicInteger();
        private final MemoryTier delegate;

        SpyTier(MemoryTier delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            gets.incrementAndGet();

            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            puts.incrementAndGet();

            return delegate.put(id, content);
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

    /// A target tier whose `put` consults a hook first: an empty answer lets the write through, a cause fails it
    /// (optionally after leaving a truncated copy behind, as a tier failing mid-write does).
    private static final class FaultyTier implements StorageTier {
        private final MemoryTier delegate;
        private final Supplier<Option<Cause>> hook;
        private final boolean leaveTruncatedCopy;

        FaultyTier(MemoryTier delegate, Supplier<Option<Cause>> hook) {
            this(delegate, hook, false);
        }

        FaultyTier(MemoryTier delegate, Supplier<Option<Cause>> hook, boolean leaveTruncatedCopy) {
            this.delegate = delegate;
            this.hook = hook;
            this.leaveTruncatedCopy = leaveTruncatedCopy;
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return hook.get().fold(() -> delegate.put(id, content), cause -> fail(id, cause));
        }

        private Promise<Unit> fail(BlockId id, Cause cause) {
            if (leaveTruncatedCopy) {
                delegate.put(id, new byte[1]).await();
            }

            return cause.promise();
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
}
