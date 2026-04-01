package org.pragmatica.storage;

/// Configuration for background tier promotion.
///
/// @param accessThreshold promote when accessCount exceeds this value within the time window
/// @param windowMs time window in milliseconds for frequency measurement
/// @param batchSize maximum number of blocks to promote per cycle
public record PromotionConfig(int accessThreshold,
                              long windowMs,
                              int batchSize) {

    /// Clamp configuration parameters to valid ranges on construction.
    public PromotionConfig {
        accessThreshold = Math.max(accessThreshold, 1);
        windowMs = Math.max(windowMs, 1000L);
        batchSize = Math.max(batchSize, 1);
    }

    /// Default configuration: threshold 50 accesses, 5 minute window, 10-block batches.
    public static PromotionConfig promotionConfig() {
        return new PromotionConfig(50, 300_000L, 10);
    }

    /// Full factory with custom parameters.
    public static PromotionConfig promotionConfig(int accessThreshold,
                                                  long windowMs,
                                                  int batchSize) {
        return new PromotionConfig(accessThreshold, windowMs, batchSize);
    }
}
