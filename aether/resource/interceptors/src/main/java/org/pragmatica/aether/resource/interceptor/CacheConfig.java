// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Verify.ensure;


/// `mode` is the topology (`LOCAL` per instance, `DISTRIBUTED` shared, `TIERED` a per-instance L1 over a shared
/// store). `backend` names the store behind it (#3658): `memory` (per instance) or `dht`; absent, it follows the
/// mode — `memory` for `LOCAL`, `dht` otherwise — so existing sections keep their behaviour. `[guess: key name
/// and values, #3658]`. Selection, mode/backend conflicts and per-runtime availability live in [CacheBackends].
public record CacheConfig(String cacheName,
                          CacheStrategy strategy,
                          int ttlSeconds,
                          int maxEntries,
                          CacheMode mode,
                          Option<String> backend) {
    // Deliberately named DEFAULTS, not DEFAULT: cacheName is identity-bearing. Exposing this as a
    // binder-visible DEFAULT would let multiple TOML-configured call sites silently collapse onto
    // the same cache namespace ("default") when cacheName is omitted (#278). Callers MUST supply
    // an explicit cacheName; a missing [cache.*] section fails loud via ConfigError.sectionNotFound.
    private static final CacheConfig DEFAULTS = new CacheConfig("default",
                                                                CacheStrategy.CACHE_ASIDE,
                                                                300,
                                                                10_000,
                                                                CacheMode.LOCAL,
                                                                none());

    public static CacheConfig cacheConfig() {
        return DEFAULTS;
    }

    public static Result<CacheConfig> cacheConfig(String cacheName,
                                                  CacheStrategy strategy,
                                                  int ttlSeconds,
                                                  int maxEntries,
                                                  CacheMode mode) {
        return cacheConfig(cacheName, strategy, ttlSeconds, maxEntries, mode, none());
    }

    public static Result<CacheConfig> cacheConfig(String cacheName,
                                                  CacheStrategy strategy,
                                                  int ttlSeconds,
                                                  int maxEntries,
                                                  CacheMode mode,
                                                  Option<String> backend) {
        return Result.all(ensure(cacheName, Verify.Is::present),
                          ensure(strategy, Verify.Is::notNull),
                          ensure(ttlSeconds, Verify.Is::positive),
                          ensure(maxEntries, Verify.Is::positive),
                          ensure(mode, Verify.Is::notNull),
                          ensure(backend, Verify.Is::notNull)).map(CacheConfig::new);
    }

    public static Result<CacheConfig> cacheConfig(String cacheName, CacheStrategy strategy) {
        return cacheConfig(cacheName, strategy, 300, 10_000, CacheMode.LOCAL);
    }

    public static Result<CacheConfig> cacheConfig(String cacheName, CacheStrategy strategy, CacheMode mode) {
        return cacheConfig(cacheName, strategy, 300, 10_000, mode);
    }
}
