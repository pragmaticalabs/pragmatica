package org.pragmatica.aether.slice;
/// Configuration for a stream consumer group.
///
/// Controls batch size, processing mode (ordered vs parallel), and error handling strategy.
public record ConsumerConfig(String groupId,
                             int maxBatchSize,
                             ProcessingMode processingMode,
                             ErrorStrategy errorStrategy) {
    /// Controls whether events within a partition are processed sequentially or in parallel.
    public enum ProcessingMode {
        ORDERED,
        PARALLEL
    }

    /// Controls behavior when event processing fails.
    public enum ErrorStrategy {
        RETRY,
        SKIP,
        STALL
    }

    private static final int DEFAULT_BATCH_SIZE = 1;

    /// Create a consumer configuration with defaults (batch size 1, ordered, retry on failure).
    public static ConsumerConfig consumerConfig(String groupId) {
        return new ConsumerConfig(groupId, DEFAULT_BATCH_SIZE, ProcessingMode.ORDERED, ErrorStrategy.RETRY);
    }

    /// Create a consumer configuration with custom values.
    public static ConsumerConfig consumerConfig(String groupId,
                                                int maxBatchSize,
                                                ProcessingMode processingMode,
                                                ErrorStrategy errorStrategy) {
        return new ConsumerConfig(groupId, maxBatchSize, processingMode, errorStrategy);
    }
}
