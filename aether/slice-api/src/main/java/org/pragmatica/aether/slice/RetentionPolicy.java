// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// Retention policy for a stream, controlling when events are evicted.
///
/// The `mode` field controls how the three dimensions (count, size, age) are combined:
/// - ANY (default): events are evicted when any single limit is exceeded (whichever triggers first).
/// - ALL: events are evicted only when all configured limits are exceeded simultaneously.
///
/// When tier-aware retention is configured and events have been sealed to persistent storage,
/// the post-seal limits apply instead, enabling more aggressive eviction of already-persisted data.
///
/// Defaults: 100,000 events, 256 MB, 24 hours, ANY mode, no tier-aware retention.
public record RetentionPolicy(long maxCount,
                              long maxBytes,
                              long maxAgeMs,
                              RetentionMode mode,
                              Option<TierAwareRetention> tierAwareRetention) {
    private static final long DEFAULT_MAX_COUNT = 100_000;

    private static final long DEFAULT_MAX_BYTES = 256 * 1024 * 1024L;

    private static final long DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    public static RetentionPolicy retentionPolicy() {
        return new RetentionPolicy(DEFAULT_MAX_COUNT, DEFAULT_MAX_BYTES, DEFAULT_MAX_AGE_MS, RetentionMode.ANY, none());
    }

    public static RetentionPolicy retentionPolicy(long maxCount, long maxBytes, long maxAgeMs) {
        return new RetentionPolicy(maxCount, maxBytes, maxAgeMs, RetentionMode.ANY, none());
    }

    public static RetentionPolicy retentionPolicy(long maxCount, long maxBytes, long maxAgeMs, RetentionMode mode) {
        return new RetentionPolicy(maxCount, maxBytes, maxAgeMs, mode, none());
    }

    public static RetentionPolicy retentionPolicy(long maxCount,
                                                  long maxBytes,
                                                  long maxAgeMs,
                                                  TierAwareRetention tierAware) {
        return new RetentionPolicy(maxCount, maxBytes, maxAgeMs, RetentionMode.ANY, Option.some(tierAware));
    }

    public static RetentionPolicy retentionPolicy(long maxCount,
                                                  long maxBytes,
                                                  long maxAgeMs,
                                                  RetentionMode mode,
                                                  TierAwareRetention tierAware) {
        return new RetentionPolicy(maxCount, maxBytes, maxAgeMs, mode, Option.some(tierAware));
    }

    public boolean shouldEvict(long count, long bytes, long ageMs) {
        return switch (mode){
            case ANY -> count > maxCount || bytes > maxBytes || ageMs > maxAgeMs;
            case ALL -> exceedsAllConfiguredLimits(count, bytes, ageMs);
        };
    }

    private boolean exceedsAllConfiguredLimits(long count, long bytes, long ageMs) {
        var countExceeded = maxCount == Long.MAX_VALUE || count > maxCount;
        var bytesExceeded = maxBytes == Long.MAX_VALUE || bytes > maxBytes;
        var ageExceeded = maxAgeMs == Long.MAX_VALUE || ageMs > maxAgeMs;
        return countExceeded && bytesExceeded && ageExceeded;
    }
}
