package org.pragmatica.storage;

import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// Manages background tier promotion — copies frequently-accessed blocks from slower
/// tiers to faster tiers to optimize read latency.
///
/// Promotion runs on EVERY node, not on one leader: it writes only to faster tiers (memory, local disk), which
/// are private to the node, so a leader-pinned manager would warm only the leader's caches. Use the
/// dormant/active lifecycle (activate/deactivate) to gate it: the node activates promotion once it reports ready
/// and deactivates it when it stops. It needs demotion running on the same node, or the fast tier fills and every
/// promotion write is refused.
public interface PromotionManager {
    /// Run one promotion cycle across all tiers. Returns total blocks promoted.
    /// No-ops when not active.
    int promote();
    /// Get cumulative promotion statistics.
    PromotionStats stats();
    /// Activate the promotion manager, allowing promote() to process blocks.
    Result<Unit> activate();
    /// Deactivate the promotion manager. Subsequent promote() calls will no-op.
    Result<Unit> deactivate();

    /// Whether the promotion manager is currently active.
    boolean isActive();

    /// @param blocksPromoted blocks copied into a faster tier
    /// @param bytesMoved bytes copied into a faster tier
    /// @param failed copy attempts that failed on the source read or the target write
    /// @param cycles promotion cycles that ran (an inactive manager runs none)
    /// @param lastRunMs completion time of the last cycle that ran
    record PromotionStats(int blocksPromoted, long bytesMoved, int failed, int cycles, long lastRunMs) {
        public static PromotionStats promotionStats() {
            return new PromotionStats(0, 0, 0, 0, 0);
        }

        PromotionStats withCycle(int count, long bytes, int failures, long runMs) {
            return new PromotionStats(blocksPromoted + count, bytesMoved + bytes, failed + failures, cycles + 1, runMs);
        }
    }

    /// Create a promotion manager for the given tier hierarchy and metadata store.
    static PromotionManager promotionManager(List<StorageTier> tiers,
                                             MetadataStore metadataStore,
                                             PromotionConfig config) {
        return new DefaultPromotionManager(tiers, metadataStore, config);
    }

    /// Inert implementation for an instance that does not promote (disabled by configuration, or excluded).
    PromotionManager NOOP = new NoOpPromotionManager();
}

final class NoOpPromotionManager implements PromotionManager {
    @Override
    public int promote() {
        return 0;
    }

    @Override
    public PromotionStats stats() {
        return PromotionStats.promotionStats();
    }

    @Override
    public Result<Unit> activate() {
        return Result.success(unit());
    }

    @Override
    public Result<Unit> deactivate() {
        return Result.success(unit());
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
