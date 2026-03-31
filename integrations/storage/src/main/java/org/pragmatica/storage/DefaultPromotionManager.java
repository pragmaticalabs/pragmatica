package org.pragmatica.storage;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Default implementation of PromotionManager.
/// Scans blocks in cold tiers (REMOTE, LOCAL_DISK) and promotes frequently-accessed
/// blocks to the next-faster tier. Promotion copies data without removing from the
/// source tier — demotion handles cleanup when the faster tier fills up.
final class DefaultPromotionManager implements PromotionManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultPromotionManager.class);

    private final List<StorageTier> tiers;
    private final MetadataStore metadataStore;
    private final PromotionConfig config;
    private static final Cause BLOCK_MISSING = Causes.cause("Block not present in source tier");
    private final AtomicReference<PromotionStats> stats = new AtomicReference<>(PromotionStats.empty());
    private volatile boolean active = false;

    DefaultPromotionManager(List<StorageTier> tiers,
                            MetadataStore metadataStore,
                            PromotionConfig config) {
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
    public int promote() {
        if (!active) {
            return 0;
        }

        return timedPromotion();
    }

    @Override
    public PromotionStats stats() {
        return stats.get();
    }

    private int timedPromotion() {
        var startMs = System.currentTimeMillis();
        var result = promoteAllTiers();
        var endMs = System.currentTimeMillis();

        stats.updateAndGet(s -> s.withPromoted(result.count(), result.bytes(), endMs));
        log.debug("Promotion cycle completed: {} block(s) promoted, {} bytes moved in {}ms",
                  result.count(), result.bytes(), endMs - startMs);
        return result.count();
    }

    private PromotionResult promoteAllTiers() {
        var totalPromoted = 0;
        var totalBytes = 0L;

        // Iterate from slowest to fastest (reverse order): promote from tier i to tier i-1.
        for (var i = tiers.size() - 1; i > 0; i--) {
            var result = promoteTier(tiers.get(i), tiers.get(i - 1));
            totalPromoted += result.count();
            totalBytes += result.bytes();
        }

        return new PromotionResult(totalPromoted, totalBytes);
    }

    private PromotionResult promoteTier(StorageTier sourceTier, StorageTier targetTier) {
        var candidates = selectCandidates(sourceTier.level());

        if (candidates.isEmpty()) {
            return PromotionResult.NONE;
        }

        return promoteCandidates(candidates, sourceTier, targetTier);
    }

    private PromotionResult promoteCandidates(List<BlockLifecycle> candidates,
                                              StorageTier sourceTier,
                                              StorageTier targetTier) {
        var promotedCount = 0;
        var promotedBytes = 0L;

        for (var candidate : candidates) {
            var moved = promoteBlock(candidate.blockId(), sourceTier, targetTier);

            if (moved > 0) {
                promotedCount++;
                promotedBytes += moved;
            }
        }

        return new PromotionResult(promotedCount, promotedBytes);
    }

    private List<BlockLifecycle> selectCandidates(TierLevel tierLevel) {
        var now = System.currentTimeMillis();
        var windowStart = now - config.windowMs();

        return metadataStore.listBlocksByTier(tierLevel).stream()
                            .filter(lc -> isPromotionCandidate(lc, windowStart))
                            .sorted(MOST_ACCESSED_FIRST)
                            .limit(config.batchSize())
                            .toList();
    }

    private boolean isPromotionCandidate(BlockLifecycle lifecycle, long windowStart) {
        return lifecycle.accessCount() > config.accessThreshold()
               && lifecycle.lastAccessedAt() >= windowStart
               && !isAlreadyInFasterTier(lifecycle);
    }

    private boolean isAlreadyInFasterTier(BlockLifecycle lifecycle) {
        var lowestTierOrdinal = lifecycle.presentIn().stream()
                                         .mapToInt(TierLevel::ordinal)
                                         .min()
                                         .orElse(Integer.MAX_VALUE);
        // Already in the fastest possible tier.
        return lowestTierOrdinal == 0;
    }

    /// Synchronous block promotion. Uses .await() because promotion runs on a dedicated
    /// background thread, not on the hot path. Blocking here is intentional.
    private long promoteBlock(BlockId blockId, StorageTier sourceTier, StorageTier targetTier) {
        return sourceTier.get(blockId).await()
                         .flatMap(opt -> opt.toResult(BLOCK_MISSING))
                         .fold(_ -> 0L, content -> writeToFasterTier(blockId, content, targetTier));
    }

    /// Synchronous write to faster tier. Uses .await() on a dedicated background thread.
    private long writeToFasterTier(BlockId blockId, byte[] content, StorageTier targetTier) {
        return targetTier.put(blockId, content).await()
                         .fold(cause -> logWriteFailure(blockId, targetTier, cause),
                               _ -> completeBlockPromotion(blockId, content.length, targetTier));
    }

    private long logWriteFailure(BlockId blockId, StorageTier targetTier, Cause cause) {
        log.debug("Promotion write to {} failed for {}: {}",
                  targetTier.level(), blockId, cause.message());
        return 0L;
    }

    private long completeBlockPromotion(BlockId blockId, int contentLength, StorageTier targetTier) {
        updateMetadataAfterPromotion(blockId, targetTier.level());
        return contentLength;
    }

    private void updateMetadataAfterPromotion(BlockId blockId, TierLevel targetLevel) {
        metadataStore.computeLifecycle(blockId, lc -> lc.withTierAdded(targetLevel));
    }

    private static final Comparator<BlockLifecycle> MOST_ACCESSED_FIRST =
        Comparator.comparingInt(BlockLifecycle::accessCount).reversed();

    private record PromotionResult(int count, long bytes) {
        static final PromotionResult NONE = new PromotionResult(0, 0);
    }
}
