package org.pragmatica.storage;

/// Configuration for background tier demotion.
///
/// @param strategy eviction strategy for selecting blocks to demote
/// @param highWatermark start demotion when tier utilization exceeds this ratio (e.g. 0.9 = 90%)
/// @param lowWatermark stop demotion when tier utilization drops below this ratio (e.g. 0.7 = 70%)
/// @param batchSize maximum number of blocks to demote per cycle
public record DemotionConfig(DemotionStrategy strategy,
                             double highWatermark,
                             double lowWatermark,
                             int batchSize) {

    /// Clamp configuration parameters to valid ranges on construction.
    public DemotionConfig {
        highWatermark = Math.clamp(highWatermark, 0.01, 1.0);
        lowWatermark = Math.clamp(lowWatermark, 0.0, highWatermark - 0.01);
        batchSize = Math.max(batchSize, 1);
    }

    /// Default configuration: LRU strategy, 90% high / 70% low watermarks, 100-block batches.
    public static DemotionConfig demotionConfig() {
        return new DemotionConfig(DemotionStrategy.LRU, 0.9, 0.7, 100);
    }

    /// Full factory with custom parameters.
    public static DemotionConfig demotionConfig(DemotionStrategy strategy,
                                                double highWatermark,
                                                double lowWatermark,
                                                int batchSize) {
        return new DemotionConfig(strategy, highWatermark, lowWatermark, batchSize);
    }
}
