// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Option.none;


@Codec
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
        return switch (mode) {
            case ANY -> count > maxCount || bytes > maxBytes || ageMs > maxAgeMs;
            case ALL -> exceedsAllConfiguredLimits(count, bytes, ageMs);
        };
    }

    private boolean exceedsAllConfiguredLimits(long count, long bytes, long ageMs) {
        var countExceeded = maxCount == Long.MAX_VALUE || count > maxCount;
        var bytesExceeded = maxBytes == Long.MAX_VALUE || bytes > maxBytes;
        var ageExceeded = maxAgeMs == Long.MAX_VALUE || ageMs > maxAgeMs;

        return countExceeded
               && bytesExceeded
               && ageExceeded;
    }
}
