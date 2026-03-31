package org.pragmatica.storage;

import java.util.List;

/// Manages background tier demotion — moves blocks from faster tiers to slower tiers
/// when utilization exceeds the configured high watermark threshold.
///
/// In a clustered deployment, demotion must run on exactly ONE node per storage instance (the leader).
/// Use the dormant/active lifecycle (activate/deactivate) to enforce single-leader coordination:
/// the leader activates demotion on election and deactivates on demotion.
public interface DemotionManager {

    /// Run one demotion cycle across all tiers. Returns total blocks demoted.
    /// No-ops when not active.
    int demote();

    /// Get cumulative demotion statistics.
    DemotionStats stats();

    /// Activate the demotion manager, allowing demote() to process blocks.
    void activate();

    /// Deactivate the demotion manager. Subsequent demote() calls will no-op.
    void deactivate();

    /// Whether the demotion manager is currently active.
    boolean isActive();

    record DemotionStats(int blocksDemoted, long bytesMoved, long lastRunMs) {

        static DemotionStats empty() {
            return new DemotionStats(0, 0, 0);
        }

        DemotionStats withDemoted(int count, long bytes, long runMs) {
            return new DemotionStats(blocksDemoted + count, bytesMoved + bytes, runMs);
        }
    }

    /// Create a demotion manager for the given tier hierarchy and metadata store.
    static DemotionManager demotionManager(List<StorageTier> tiers,
                                           MetadataStore metadataStore,
                                           DemotionConfig config) {
        return new DefaultDemotionManager(tiers, metadataStore, config);
    }
}
