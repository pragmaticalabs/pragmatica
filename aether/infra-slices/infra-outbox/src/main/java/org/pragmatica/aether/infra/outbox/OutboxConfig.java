package org.pragmatica.aether.infra.outbox;

import org.pragmatica.lang.Result;

import java.time.Duration;

import static org.pragmatica.lang.Result.success;

/**
 * Configuration for outbox service.
 *
 * @param batchSize       Maximum messages to process in one batch
 * @param maxRetries      Maximum retry attempts before marking as failed
 * @param retryDelay      Base delay between retries (exponential backoff applied)
 * @param processingTimeout Timeout for processing a single message
 * @param cleanupAge      Age after which delivered messages are cleaned up
 */
public record OutboxConfig(int batchSize,
                           int maxRetries,
                           Duration retryDelay,
                           Duration processingTimeout,
                           Duration cleanupAge) {
    /**
     * Default configuration.
     */
    public static final OutboxConfig DEFAULT = new OutboxConfig(100,
                                                                3,
                                                                Duration.ofSeconds(30),
                                                                Duration.ofMinutes(5),
                                                                Duration.ofDays(7));

    /**
     * Creates outbox configuration with validation.
     */
    public static Result<OutboxConfig> outboxConfig(int batchSize,
                                                    int maxRetries,
                                                    Duration retryDelay,
                                                    Duration processingTimeout,
                                                    Duration cleanupAge) {
        if (batchSize <= 0) {
            return OutboxError.invalidConfiguration("Batch size must be positive");
        }
        if (maxRetries < 0) {
            return OutboxError.invalidConfiguration("Max retries cannot be negative");
        }
        if (retryDelay.isNegative() || retryDelay.isZero()) {
            return OutboxError.invalidConfiguration("Retry delay must be positive");
        }
        if (processingTimeout.isNegative() || processingTimeout.isZero()) {
            return OutboxError.invalidConfiguration("Processing timeout must be positive");
        }
        if (cleanupAge.isNegative() || cleanupAge.isZero()) {
            return OutboxError.invalidConfiguration("Cleanup age must be positive");
        }
        return success(new OutboxConfig(batchSize, maxRetries, retryDelay, processingTimeout, cleanupAge));
    }

    /**
     * Calculates retry delay with exponential backoff.
     */
    public Duration retryDelayFor(int attempt) {
        return retryDelay.multipliedBy((long) Math.pow(2, attempt));
    }
}
