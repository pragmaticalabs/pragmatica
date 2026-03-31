package org.pragmatica.storage;

import java.util.List;

/// Manages background tier promotion — copies frequently-accessed blocks from slower
/// tiers to faster tiers to optimize read latency.
///
/// In a clustered deployment, promotion must run on exactly ONE node per storage instance (the leader).
/// Use the dormant/active lifecycle (activate/deactivate) to enforce single-leader coordination:
/// the leader activates promotion on election and deactivates on step-down.
public interface PromotionManager {

    /// Run one promotion cycle across all tiers. Returns total blocks promoted.
    /// No-ops when not active.
    int promote();

    /// Get cumulative promotion statistics.
    PromotionStats stats();

    /// Activate the promotion manager, allowing promote() to process blocks.
    void activate();

    /// Deactivate the promotion manager. Subsequent promote() calls will no-op.
    void deactivate();

    /// Whether the promotion manager is currently active.
    boolean isActive();

    record PromotionStats(int blocksPromoted, long bytesMoved, long lastRunMs) {

        static PromotionStats empty() {
            return new PromotionStats(0, 0, 0);
        }

        PromotionStats withPromoted(int count, long bytes, long runMs) {
            return new PromotionStats(blocksPromoted + count, bytesMoved + bytes, runMs);
        }
    }

    /// Create a promotion manager for the given tier hierarchy and metadata store.
    static PromotionManager promotionManager(List<StorageTier> tiers,
                                             MetadataStore metadataStore,
                                             PromotionConfig config) {
        return new DefaultPromotionManager(tiers, metadataStore, config);
    }
}
