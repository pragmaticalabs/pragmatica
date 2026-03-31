package org.pragmatica.storage;

/// Configuration for storage garbage collection.
///
/// @param gracePeriodMs minimum time (ms) a block must remain orphaned before collection
/// @param batchSize maximum number of blocks to collect per GC cycle
public record GarbageCollectorConfig(long gracePeriodMs, int batchSize) {

    /// Validate configuration parameters on construction.
    public GarbageCollectorConfig {
        if (gracePeriodMs <= 0) {
            throw new IllegalArgumentException("gracePeriodMs must be positive, got: " + gracePeriodMs);
        }

        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive, got: " + batchSize);
        }
    }

    /// Default configuration: 1-hour grace period, 500-block batch size.
    public static GarbageCollectorConfig garbageCollectorConfig() {
        return new GarbageCollectorConfig(3_600_000, 500);
    }

    /// Full factory with custom parameters.
    public static GarbageCollectorConfig garbageCollectorConfig(long gracePeriodMs, int batchSize) {
        return new GarbageCollectorConfig(gracePeriodMs, batchSize);
    }
}
