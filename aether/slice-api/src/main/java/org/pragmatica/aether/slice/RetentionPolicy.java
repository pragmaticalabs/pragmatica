package org.pragmatica.aether.slice;

/// Retention policy for a stream, controlling when events are evicted.
///
/// Events are evicted when any limit is exceeded (whichever triggers first).
///
/// Defaults: 100,000 events, 256 MB, 24 hours.
public record RetentionPolicy(long maxCount, long maxBytes, long maxAgeMs) {

    private static final long DEFAULT_MAX_COUNT = 100_000;
    private static final long DEFAULT_MAX_BYTES = 256 * 1024 * 1024L;
    private static final long DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    /// Create a retention policy with default limits (100K events, 256 MB, 24 hours).
    public static RetentionPolicy retentionPolicy() {
        return new RetentionPolicy(DEFAULT_MAX_COUNT, DEFAULT_MAX_BYTES, DEFAULT_MAX_AGE_MS);
    }

    /// Create a retention policy with custom limits.
    public static RetentionPolicy retentionPolicy(long maxCount, long maxBytes, long maxAgeMs) {
        return new RetentionPolicy(maxCount, maxBytes, maxAgeMs);
    }
}
