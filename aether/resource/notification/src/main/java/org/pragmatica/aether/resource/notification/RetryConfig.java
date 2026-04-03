package org.pragmatica.aether.resource.notification;

public record RetryConfig(int maxAttempts, long initialDelayMs, long maxDelayMs, double backoffMultiplier) {
    public static final RetryConfig DEFAULT = new RetryConfig(3, 1000, 30_000, 2.0);

    public static RetryConfig retryConfig() {
        return DEFAULT;
    }

    public static RetryConfig retryConfig(int maxAttempts,
                                          long initialDelayMs,
                                          long maxDelayMs,
                                          double backoffMultiplier) {
        return new RetryConfig(maxAttempts, initialDelayMs, maxDelayMs, backoffMultiplier);
    }
}
