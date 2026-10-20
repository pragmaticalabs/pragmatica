package org.pragmatica.storage;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Default implementation of PromotionManager.
/// Scans blocks in cold tiers (REMOTE, LOCAL_DISK) and promotes frequently-accessed
/// blocks to the next-faster tier. Promotion copies data without removing from the
/// source tier — demotion handles cleanup when the faster tier fills up. A target tier is
/// never filled past [PromotionConfig#targetWatermark()], so the two do not trade the same blocks.
final class DefaultPromotionManager implements PromotionManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultPromotionManager.class);

    private final List<StorageTier> tiers;
    private final MetadataStore metadataStore;
    private final PromotionConfig config;

    private final AtomicReference<PromotionStats> stats = new AtomicReference<>(PromotionStats.promotionStats());

    private volatile boolean active = false;

    DefaultPromotionManager(List<StorageTier> tiers, MetadataStore metadataStore, PromotionConfig config) {
        this.tiers = List.copyOf(tiers);
        this.metadataStore = metadataStore;
        this.config = config;
    }

    @Override
    public Result<Unit> activate() {
        active = true;

        return Result.success(unit());
    }

    @Override
    public Result<Unit> deactivate() {
        active = false;

        return Result.success(unit());
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

        stats.updateAndGet(s -> s.withCycle(result.count(), result.bytes(), result.failed(), endMs));
        log.debug("Promotion cycle completed: {} block(s) promoted, {} bytes moved, {} failed in {}ms",
                  result.count(),
                  result.bytes(),
                  result.failed(),
                  endMs - startMs);

        return result.count();
    }

    private PromotionResult promoteAllTiers() {
        // Iterate from slowest to fastest (reverse order): promote from tier i to tier i-1.
        return IntStream.iterate(tiers.size() - 1,
                                 i -> i > 0,
                                 i -> i - 1)
                        .mapToObj(i -> promoteTier(tiers.get(i),
                                                   tiers.get(i - 1)))
                        .reduce(PromotionResult.NONE, PromotionResult::add);
    }

    private PromotionResult promoteTier(StorageTier sourceTier, StorageTier targetTier) {
        if (isAtTargetWatermark(targetTier)) {
            return PromotionResult.NONE;
        }

        var candidates = selectCandidates(sourceTier.level(), targetTier.level());

        if (candidates.isEmpty()) {
            return PromotionResult.NONE;
        }

        return promoteCandidates(candidates, sourceTier, targetTier);
    }

    /// `takeWhile(active)` re-checks between blocks, so a deactivation takes effect within one block instead of
    /// after the whole batch.
    private PromotionResult promoteCandidates(List<BlockLifecycle> candidates,
                                              StorageTier sourceTier,
                                              StorageTier targetTier) {
        return candidates.stream()
                         .takeWhile(_ -> active)
                         .map(candidate -> promoteBlock(candidate.blockId(),
                                                        sourceTier,
                                                        targetTier))
                         .reduce(PromotionResult.NONE, PromotionResult::add);
    }

    private List<BlockLifecycle> selectCandidates(TierLevel sourceLevel, TierLevel targetLevel) {
        var now = System.currentTimeMillis();
        var windowStart = now - config.windowMs();

        return metadataStore.listBlocksByTier(sourceLevel)
                            .stream()
                            .filter(lc -> isPromotionCandidate(lc, windowStart, targetLevel))
                            .sorted(MOST_ACCESSED_FIRST)
                            .limit(config.batchSize())
                            .toList();
    }

    /// A candidate for `source -> target` is a hot block that is not yet present in THAT target tier. Testing
    /// "present in any faster tier" instead re-copies a block already on the disk from the DHT on every cycle.
    private boolean isPromotionCandidate(BlockLifecycle lifecycle, long windowStart, TierLevel targetLevel) {
        return lifecycle.accessCount() > config.accessThreshold()
               && lifecycle.lastAccessedAt() >= windowStart
               && !lifecycle.presentIn()
                            .contains(targetLevel);
    }

    /// Synchronous block promotion. Uses .await() because promotion runs on a dedicated
    /// background thread, not on the hot path. Blocking here is intentional.
    private PromotionResult promoteBlock(BlockId blockId, StorageTier sourceTier, StorageTier targetTier) {
        return sourceTier.get(blockId)
                         .await()
                         .fold(cause -> logReadFailure(blockId, sourceTier, cause),
                               opt -> opt.fold(() -> logSourceMissing(blockId, sourceTier),
                                               content -> writeToFasterTier(blockId, content, targetTier)));
    }

    private PromotionResult writeToFasterTier(BlockId blockId, byte[] content, StorageTier targetTier) {
        return exceedsTargetWatermark(targetTier, content.length)
               ? logWatermarkSkip(blockId, targetTier)
               : putOnFasterTier(blockId, content, targetTier);
    }

    /// Synchronous write to faster tier. Uses .await() on a dedicated background thread.
    private PromotionResult putOnFasterTier(BlockId blockId, byte[] content, StorageTier targetTier) {
        return targetTier.put(blockId, content)
                         .await()
                         .fold(cause -> failWrite(blockId, targetTier, cause),
                               _ -> completeBlockPromotion(blockId, content.length, targetTier));
    }

    private PromotionResult failWrite(BlockId blockId, StorageTier targetTier, Cause cause) {
        log.debug("Promotion write to {} failed for {}: {}", targetTier.level(), blockId, cause.message());
        discardPartialCopy(blockId, targetTier, cause);

        return PromotionResult.FAILED;
    }

    /// BER (compensate by inverse): a write that failed MID-WRITE can leave a truncated copy, and the read
    /// waterfall stops at the first tier that returns bytes -- the corrupt copy would then fail every read with an
    /// integrity error while the durable copy sits unreachable behind it (the rule `StorageInstance` applies to its
    /// own cache-tier writes). The delete undoes the write. A tier that refused BEFORE writing (`TierFull`) wrote
    /// nothing, and the id may already hold a valid copy there, so it is left alone. Best-effort: a delete that
    /// fails is logged at WARN and not retried; the metadata never recorded the copy, so only reads of it are
    /// affected.
    private Unit discardPartialCopy(BlockId blockId, StorageTier targetTier, Cause cause) {
        if (cause instanceof StorageError.TierFull) {
            return unit();
        }

        return targetTier.delete(blockId)
                         .await()
                         .onFailure(deleteCause -> log.warn("Promotion could not discard the failed write of {} on {}; a partial copy may remain and reads of it will fail their integrity check: {}",
                                                            blockId,
                                                            targetTier.level(),
                                                            deleteCause.message()))
                         .or(unit());
    }

    private PromotionResult logReadFailure(BlockId blockId, StorageTier sourceTier, Cause cause) {
        log.debug("Promotion read from {} failed for {}: {}", sourceTier.level(), blockId, cause.message());

        return PromotionResult.FAILED;
    }

    private PromotionResult logSourceMissing(BlockId blockId, StorageTier sourceTier) {
        log.debug("Promotion found {} absent from {} although its record lists it; skipped", blockId, sourceTier.level());

        return PromotionResult.NONE;
    }

    private PromotionResult logWatermarkSkip(BlockId blockId, StorageTier targetTier) {
        log.debug("Promotion of {} into {} skipped: it would pass the {} target watermark",
                  blockId,
                  targetTier.level(),
                  config.targetWatermark());

        return PromotionResult.NONE;
    }

    private PromotionResult completeBlockPromotion(BlockId blockId, int contentLength, StorageTier targetTier) {
        updateMetadataAfterPromotion(blockId, targetTier.level());

        return new PromotionResult(1, contentLength, 0);
    }

    private void updateMetadataAfterPromotion(BlockId blockId, TierLevel targetLevel) {
        metadataStore.computeLifecycle(blockId, lc -> lc.withTierAdded(targetLevel));
    }

    private boolean isAtTargetWatermark(StorageTier tier) {
        return utilization(tier, 0) >= config.targetWatermark();
    }

    private boolean exceedsTargetWatermark(StorageTier tier, int incomingBytes) {
        return utilization(tier, incomingBytes) > config.targetWatermark();
    }

    private static double utilization(StorageTier tier, long incomingBytes) {
        return tier.maxBytes() == 0
               ? 0.0
               : (double) (tier.usedBytes() + incomingBytes) / tier.maxBytes();
    }

    private static final Comparator<BlockLifecycle> MOST_ACCESSED_FIRST = Comparator.comparingInt(BlockLifecycle::accessCount).reversed();

    private record PromotionResult(int count, long bytes, int failed) {
        static final PromotionResult NONE = new PromotionResult(0, 0, 0);
        static final PromotionResult FAILED = new PromotionResult(0, 0, 1);

        PromotionResult add(PromotionResult other) {
            return new PromotionResult(count + other.count, bytes + other.bytes, failed + other.failed);
        }
    }
}
