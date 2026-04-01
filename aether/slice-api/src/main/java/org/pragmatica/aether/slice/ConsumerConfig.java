package org.pragmatica.aether.slice;
public record ConsumerConfig( String groupId,
                              int maxBatchSize,
                              ProcessingMode processingMode,
                              ErrorStrategy errorStrategy,
                              long checkpointIntervalMs,
                              int maxRetries,
                              String deadLetterStream,
                              ReadPreference readPreference) {
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
    private static final long DEFAULT_CHECKPOINT_INTERVAL_MS = 1000L;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final String DEFAULT_DEAD_LETTER_STREAM = "";

    /// Create a consumer configuration with defaults (batch size 1, ordered, retry on failure, leader read preference).
    public static ConsumerConfig consumerConfig(String groupId) {
        return new ConsumerConfig(groupId,
                                  DEFAULT_BATCH_SIZE,
                                  ProcessingMode.ORDERED,
                                  ErrorStrategy.RETRY,
                                  DEFAULT_CHECKPOINT_INTERVAL_MS,
                                  DEFAULT_MAX_RETRIES,
                                  DEFAULT_DEAD_LETTER_STREAM,
                                  ReadPreference.LEADER);
    }

    /// Create a consumer configuration with core values (uses defaults for checkpoint/retry/DLQ/read preference).
    public static ConsumerConfig consumerConfig(String groupId,
                                                int maxBatchSize,
                                                ProcessingMode processingMode,
                                                ErrorStrategy errorStrategy) {
        return new ConsumerConfig(groupId,
                                  maxBatchSize,
                                  processingMode,
                                  errorStrategy,
                                  DEFAULT_CHECKPOINT_INTERVAL_MS,
                                  DEFAULT_MAX_RETRIES,
                                  DEFAULT_DEAD_LETTER_STREAM,
                                  ReadPreference.LEADER);
    }

    /// Create a consumer configuration with all values except read preference (defaults to LEADER).
    public static ConsumerConfig consumerConfig(String groupId,
                                                int maxBatchSize,
                                                ProcessingMode processingMode,
                                                ErrorStrategy errorStrategy,
                                                long checkpointIntervalMs,
                                                int maxRetries,
                                                String deadLetterStream) {
        return new ConsumerConfig(groupId,
                                  maxBatchSize,
                                  processingMode,
                                  errorStrategy,
                                  checkpointIntervalMs,
                                  maxRetries,
                                  deadLetterStream,
                                  ReadPreference.LEADER);
    }

    /// Create a consumer configuration with all values including read preference.
    public static ConsumerConfig consumerConfig(String groupId,
                                                int maxBatchSize,
                                                ProcessingMode processingMode,
                                                ErrorStrategy errorStrategy,
                                                long checkpointIntervalMs,
                                                int maxRetries,
                                                String deadLetterStream,
                                                ReadPreference readPreference) {
        return new ConsumerConfig(groupId,
                                  maxBatchSize,
                                  processingMode,
                                  errorStrategy,
                                  checkpointIntervalMs,
                                  maxRetries,
                                  deadLetterStream,
                                  readPreference);
    }
}
