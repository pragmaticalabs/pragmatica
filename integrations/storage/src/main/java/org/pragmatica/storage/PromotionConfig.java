package org.pragmatica.storage;

/// Configuration for background tier promotion.
///
/// @param accessThreshold promote when accessCount exceeds this value within the time window
/// @param windowMs time window in milliseconds for frequency measurement
/// @param batchSize maximum number of blocks to promote per cycle
/// @param targetWatermark promotion never fills a target tier past this utilization ratio (e.g. 0.7 = 70%);
/// defaults to the demotion low watermark, so promotion cannot push a tier over the high watermark that makes
/// demotion evict from it again
public record PromotionConfig(int accessThreshold, long windowMs, int batchSize, double targetWatermark) {
    /// Clamp configuration parameters to valid ranges on construction.
    public PromotionConfig {
        accessThreshold = Math.max(accessThreshold, 1);
        windowMs = Math.max(windowMs, 1000L);
        batchSize = Math.max(batchSize, 1);
        targetWatermark = Math.clamp(targetWatermark, 0.01, 1.0);
    }

    /// Default configuration: threshold 50 accesses, 5 minute window, 10-block batches, the demotion low
    /// watermark as the target ceiling.
    public static PromotionConfig promotionConfig() {
        return new PromotionConfig(50, 300_000L, 10, DemotionConfig.demotionConfig().lowWatermark());
    }

    /// Custom thresholds with the default target watermark.
    public static PromotionConfig promotionConfig(int accessThreshold, long windowMs, int batchSize) {
        return new PromotionConfig(accessThreshold, windowMs, batchSize, promotionConfig().targetWatermark());
    }

    /// Full factory with custom parameters.
    public static PromotionConfig promotionConfig(int accessThreshold,
                                                  long windowMs,
                                                  int batchSize,
                                                  double targetWatermark) {
        return new PromotionConfig(accessThreshold, windowMs, batchSize, targetWatermark);
    }
}
