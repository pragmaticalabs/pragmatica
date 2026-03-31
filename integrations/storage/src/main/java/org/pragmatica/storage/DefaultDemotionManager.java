package org.pragmatica.storage;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Default implementation of DemotionManager.
/// Checks each tier's utilization against the high watermark, selects candidates
/// using the configured strategy, copies blocks to the next-slower tier,
/// and removes them from the current tier.
final class DefaultDemotionManager implements DemotionManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultDemotionManager.class);

    private final List<StorageTier> tiers;
    private final MetadataStore metadataStore;
    private final DemotionConfig config;
    private static final Cause BLOCK_MISSING = Causes.cause("Block not present in source tier");
    private final AtomicReference<DemotionStats> stats = new AtomicReference<>(DemotionStats.empty());
    private volatile boolean active = false;

    DefaultDemotionManager(List<StorageTier> tiers,
                           MetadataStore metadataStore,
                           DemotionConfig config) {
        this.tiers = List.copyOf(tiers);
        this.metadataStore = metadataStore;
        this.config = config;
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public int demote() {
        if (!active) {
            return 0;
        }

        return timedDemotion();
    }

    @Override
    public DemotionStats stats() {
        return stats.get();
    }

    private int timedDemotion() {
        var startMs = System.currentTimeMillis();
        var result = demoteAllTiers();
        var endMs = System.currentTimeMillis();

        stats.updateAndGet(s -> s.withDemoted(result.count(), result.bytes(), endMs));
        log.debug("Demotion cycle completed: {} block(s) demoted, {} bytes moved in {}ms",
                  result.count(), result.bytes(), endMs - startMs);
        return result.count();
    }

    private DemotionResult demoteAllTiers() {
        var totalDemoted = 0;
        var totalBytes = 0L;

        for (var i = 0; i < tiers.size() - 1; i++) {
            var result = demoteTier(tiers.get(i), tiers.get(i + 1));
            totalDemoted += result.count();
            totalBytes += result.bytes();
        }

        return new DemotionResult(totalDemoted, totalBytes);
    }

    private DemotionResult demoteTier(StorageTier sourceTier, StorageTier targetTier) {
        if (!isAboveHighWatermark(sourceTier)) {
            return DemotionResult.NONE;
        }

        return demoteCandidates(sourceTier, targetTier);
    }

    private DemotionResult demoteCandidates(StorageTier sourceTier, StorageTier targetTier) {
        var candidates = selectCandidates(sourceTier.level());
        var demotedCount = 0;
        var demotedBytes = 0L;

        for (var candidate : candidates) {
            if (isBelowLowWatermark(sourceTier)) {
                break;
            }

            var moved = demoteBlock(candidate.blockId(), sourceTier, targetTier);

            if (moved > 0) {
                demotedCount++;
                demotedBytes += moved;
            }
        }

        return new DemotionResult(demotedCount, demotedBytes);
    }

    private List<BlockLifecycle> selectCandidates(TierLevel tierLevel) {
        var candidates = metadataStore.listBlocksByTier(tierLevel);

        return candidates.stream()
                         .sorted(comparatorForStrategy())
                         .limit(config.batchSize())
                         .toList();
    }

    private Comparator<BlockLifecycle> comparatorForStrategy() {
        return switch (config.strategy()) {
            case AGE -> Comparator.comparingLong(BlockLifecycle::createdAt);
            case LFU -> Comparator.comparingInt(BlockLifecycle::accessCount);
            case LRU -> Comparator.comparingLong(BlockLifecycle::lastAccessedAt);
            case SIZE_PRESSURE -> Comparator.<BlockLifecycle>comparingLong(BlockLifecycle::lastAccessedAt).reversed();
        };
    }

    /// Synchronous block demotion. Uses .await() because demotion runs on a dedicated
    /// background thread, not on the hot path. Blocking here is intentional.
    private long demoteBlock(BlockId blockId, StorageTier sourceTier, StorageTier targetTier) {
        return sourceTier.get(blockId).await()
                         .flatMap(opt -> opt.toResult(BLOCK_MISSING))
                         .fold(_ -> 0L, content -> writeThenDelete(blockId, content, sourceTier, targetTier));
    }

    /// Synchronous write-then-delete. Uses .await() on a dedicated background thread.
    private long writeThenDelete(BlockId blockId, byte[] content,
                                 StorageTier sourceTier, StorageTier targetTier) {
        return targetTier.put(blockId, content).await()
                         .fold(cause -> logWriteFailure(blockId, targetTier, cause),
                               _ -> completeBlockDemotion(blockId, content.length, sourceTier, targetTier));
    }

    private long logWriteFailure(BlockId blockId, StorageTier targetTier, Cause cause) {
        log.debug("Demotion write to {} failed for {}: {}",
                  targetTier.level(), blockId, cause.message());
        return 0L;
    }

    /// Synchronous deletion. Uses .await() on a dedicated background thread.
    private long completeBlockDemotion(BlockId blockId, int contentLength,
                                       StorageTier sourceTier, StorageTier targetTier) {
        sourceTier.delete(blockId).await();
        updateMetadataAfterDemotion(blockId, sourceTier.level(), targetTier.level());
        return contentLength;
    }

    private void updateMetadataAfterDemotion(BlockId blockId,
                                             TierLevel sourceLevel,
                                             TierLevel targetLevel) {
        metadataStore.computeLifecycle(blockId, lc -> lc.withTierRemoved(sourceLevel)
                                                        .withTierAdded(targetLevel));
    }

    private boolean isAboveHighWatermark(StorageTier tier) {
        return utilization(tier) > config.highWatermark();
    }

    private boolean isBelowLowWatermark(StorageTier tier) {
        return utilization(tier) < config.lowWatermark();
    }

    private static double utilization(StorageTier tier) {
        return tier.maxBytes() == 0 ? 0.0 : (double) tier.usedBytes() / tier.maxBytes();
    }

    private record DemotionResult(int count, long bytes) {
        static final DemotionResult NONE = new DemotionResult(0, 0);
    }
}
