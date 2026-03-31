package org.pragmatica.storage;

import java.util.List;

/// Manages background tier demotion — moves blocks from faster tiers to slower tiers
/// when utilization exceeds the configured high watermark threshold.
public interface DemotionManager {

    /// Run one demotion cycle across all tiers. Returns total blocks demoted.
    int demote();

    /// Get cumulative demotion statistics.
    DemotionStats stats();

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
