// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Verify.ensure;


public record CacheConfig(String cacheName, CacheStrategy strategy, int ttlSeconds, int maxEntries, CacheMode mode) {
    private static final CacheConfig DEFAULTS = new CacheConfig("default",
                                                                                                CacheStrategy.CACHE_ASIDE,
                                                                                                300,
                                                                                                10_000,
                                                                                                CacheMode.LOCAL);

    public static CacheConfig cacheConfig() {
        return DEFAULTS;
    }

    public static Result<CacheConfig> cacheConfig(String cacheName,
                                                  CacheStrategy strategy,
                                                  int ttlSeconds,
                                                  int maxEntries,
                                                  CacheMode mode) {
        return Result.all(ensure(cacheName, Verify.Is::present),
                          ensure(strategy, Verify.Is::notNull),
                          ensure(ttlSeconds, Verify.Is::positive),
                          ensure(maxEntries, Verify.Is::positive),
                          ensure(mode, Verify.Is::notNull))
        .map(CacheConfig::new);
    }

    public static Result<CacheConfig> cacheConfig(String cacheName, CacheStrategy strategy) {
        return cacheConfig(cacheName, strategy, 300, 10_000, CacheMode.LOCAL);
    }

    public static Result<CacheConfig> cacheConfig(String cacheName, CacheStrategy strategy, CacheMode mode) {
        return cacheConfig(cacheName, strategy, 300, 10_000, mode);
    }
}
