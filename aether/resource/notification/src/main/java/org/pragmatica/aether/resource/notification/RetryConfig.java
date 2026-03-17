package org.pragmatica.aether.resource.notification;

/// Retry configuration for notification delivery.
///
/// @param maxAttempts Maximum number of delivery attempts
/// @param initialDelayMs Initial delay between retries in milliseconds
/// @param maxDelayMs Maximum delay between retries in milliseconds
/// @param backoffMultiplier Multiplier applied to delay after each retry
public record RetryConfig(int maxAttempts,
                          long initialDelayMs,
                          long maxDelayMs,
                          double backoffMultiplier) {

    public static final RetryConfig DEFAULT = new RetryConfig(3, 1000, 30_000, 2.0);

    /// Create default retry configuration: 3 attempts, 1s initial delay, 30s max, 2x backoff.
    public static RetryConfig retryConfig() {
        return DEFAULT;
    }

    /// Create custom retry configuration.
    public static RetryConfig retryConfig(int maxAttempts, long initialDelayMs, long maxDelayMs, double backoffMultiplier) {
        return new RetryConfig(maxAttempts, initialDelayMs, maxDelayMs, backoffMultiplier);
    }
}
