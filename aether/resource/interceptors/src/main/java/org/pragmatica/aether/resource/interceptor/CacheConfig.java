package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Verify.ensure;

/// Configuration for cache interceptor.
///
/// @param cacheName  Logical cache name (configs sharing the same name share one cache instance)
/// @param strategy   Caching strategy to apply
/// @param ttlSeconds Time-to-live for cached entries in seconds
/// @param maxEntries Maximum number of entries in the cache
/// @param mode       Cache storage mode (local, distributed, or tiered)
public record CacheConfig(String cacheName, CacheStrategy strategy, int ttlSeconds, int maxEntries, CacheMode mode) {
    @SuppressWarnings("JBCT-VO-02")
    private static final CacheConfig DEFAULTS = new CacheConfig("default",
                                                                CacheStrategy.CACHE_ASIDE,
                                                                300,
                                                                10_000,
                                                                CacheMode.LOCAL);

    /// Create cache configuration with default values.
    public static CacheConfig cacheConfig() {
        return DEFAULTS;
    }

    /// Create cache configuration with custom parameters.
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

    /// Create cache configuration with name and strategy, using default TTL, max entries, and local mode.
    public static Result<CacheConfig> cacheConfig(String cacheName, CacheStrategy strategy) {
        return cacheConfig(cacheName, strategy, 300, 10_000, CacheMode.LOCAL);
    }

    /// Create cache configuration with name, strategy, and mode, using default TTL and max entries.
    public static Result<CacheConfig> cacheConfig(String cacheName, CacheStrategy strategy, CacheMode mode) {
        return cacheConfig(cacheName, strategy, 300, 10_000, mode);
    }
}
