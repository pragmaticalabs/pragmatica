package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;

/// Configuration for cache interceptor.
///
/// @param cacheName  Logical cache name (configs sharing the same name share one cache instance)
/// @param strategy   Caching strategy to apply
/// @param ttlSeconds Time-to-live for cached entries in seconds
/// @param maxEntries Maximum number of entries in the cache
public record CacheConfig(String cacheName, CacheStrategy strategy, int ttlSeconds, int maxEntries) {
    @SuppressWarnings("JBCT-VO-02")
    private static final CacheConfig DEFAULTS = new CacheConfig("default", CacheStrategy.CACHE_ASIDE, 300, 10_000);

    /// Create cache configuration with default values.
    public static CacheConfig cacheConfig() {
        return DEFAULTS;
    }

    /// Create cache configuration with custom parameters.
    public static Result<CacheConfig> cacheConfig(String cacheName,
                                                  CacheStrategy strategy,
                                                  int ttlSeconds,
                                                  int maxEntries) {
        var validName = ensure(cacheName, Verify.Is::notBlank);
        var validStrategy = ensure(strategy, Verify.Is::notNull);
        var validTtl = ensure(ttlSeconds, Verify.Is::positive);
        var validMax = ensure(maxEntries, Verify.Is::positive);
        return all(validName, validStrategy, validTtl, validMax).map(CacheConfig::new);
    }

    /// Create cache configuration with name and strategy, using default TTL and max entries.
    public static Result<CacheConfig> cacheConfig(String cacheName, CacheStrategy strategy) {
        return cacheConfig(cacheName, strategy, 300, 10_000);
    }
}
