package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;

/// Retention policy for a stream, controlling when events are evicted.
///
/// Events are evicted when any limit is exceeded (whichever triggers first).
///
/// When tier-aware retention is configured and events have been sealed to persistent storage,
/// the post-seal limits apply instead, enabling more aggressive eviction of already-persisted data.
///
/// Defaults: 100,000 events, 256 MB, 24 hours, no tier-aware retention.
public record RetentionPolicy( long maxCount,
                               long maxBytes,
                               long maxAgeMs,
                               Option<TierAwareRetention> tierAwareRetention) {
    private static final long DEFAULT_MAX_COUNT = 100_000;
    private static final long DEFAULT_MAX_BYTES = 256 * 1024 * 1024L;
    private static final long DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    /// Create a retention policy with default limits (100K events, 256 MB, 24 hours, no tier-aware).
    public static RetentionPolicy retentionPolicy() {
        return new RetentionPolicy(DEFAULT_MAX_COUNT, DEFAULT_MAX_BYTES, DEFAULT_MAX_AGE_MS, none());
    }

    /// Create a retention policy with custom limits and no tier-aware retention.
    public static RetentionPolicy retentionPolicy(long maxCount, long maxBytes, long maxAgeMs) {
        return new RetentionPolicy(maxCount, maxBytes, maxAgeMs, none());
    }

    /// Create a retention policy with custom limits and tier-aware retention.
    public static RetentionPolicy retentionPolicy(long maxCount,
                                                  long maxBytes,
                                                  long maxAgeMs,
                                                  TierAwareRetention tierAware) {
        return new RetentionPolicy(maxCount, maxBytes, maxAgeMs, Option.some(tierAware));
    }
}
