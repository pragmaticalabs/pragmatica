package org.pragmatica.aether.infra.cache;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;

/**
 * Configuration for cache service.
 *
 * @param name        Cache name for identification
 * @param maxSize     Maximum number of entries (0 for unlimited)
 * @param defaultTtl  Default TTL for entries without explicit TTL
 * @param eviction    Eviction policy when cache is full
 */
public record CacheConfig(String name,
                          long maxSize,
                          TimeSpan defaultTtl,
                          EvictionPolicy eviction) {
    private static final String DEFAULT_NAME = "default";
    private static final long DEFAULT_MAX_SIZE = 10_000;
    private static final TimeSpan DEFAULT_TTL = TimeSpan.timeSpan(1)
                                                       .hours();
    private static final EvictionPolicy DEFAULT_EVICTION = EvictionPolicy.LRU;

    /**
     * Eviction policies for cache.
     */
    public enum EvictionPolicy {
        /** Least Recently Used */
        LRU,
        /** Least Frequently Used */
        LFU,
        /** First In First Out */
        FIFO,
        /** No eviction - reject new entries when full */
        NONE
    }

    /**
     * Creates default configuration.
     *
     * @return Result containing default configuration
     */
    public static Result<CacheConfig> cacheConfig() {
        return success(new CacheConfig(DEFAULT_NAME, DEFAULT_MAX_SIZE, DEFAULT_TTL, DEFAULT_EVICTION));
    }

    /**
     * Creates configuration with specified name.
     *
     * @param name Cache name
     * @return Result containing configuration
     */
    public static Result<CacheConfig> cacheConfig(String name) {
        return validateName(name)
                           .map(n -> new CacheConfig(n, DEFAULT_MAX_SIZE, DEFAULT_TTL, DEFAULT_EVICTION));
    }

    /**
     * Creates configuration with specified name and max size.
     *
     * @param name    Cache name
     * @param maxSize Maximum number of entries
     * @return Result containing configuration or error if invalid
     */
    public static Result<CacheConfig> cacheConfig(String name, long maxSize) {
        return Result.all(validateName(name),
                          validateMaxSize(maxSize))
                     .map((n, s) -> new CacheConfig(n, s, DEFAULT_TTL, DEFAULT_EVICTION));
    }

    private static Result<String> validateName(String name) {
        return org.pragmatica.lang.Option.option(name)
                  .filter(n -> !n.isBlank())
                  .map(String::trim)
                  .toResult(CacheServiceError.invalidKey("", "Cache name cannot be null or empty"));
    }

    private static Result<Long> validateMaxSize(long maxSize) {
        if (maxSize < 0) {
            return CacheServiceError.invalidKey("", "Max size cannot be negative: " + maxSize)
                                    .result();
        }
        return success(maxSize);
    }

    /**
     * Creates a new configuration with the specified name.
     */
    public CacheConfig withName(String name) {
        return new CacheConfig(name, maxSize, defaultTtl, eviction);
    }

    /**
     * Creates a new configuration with the specified max size.
     */
    public CacheConfig withMaxSize(long maxSize) {
        return new CacheConfig(name, maxSize, defaultTtl, eviction);
    }

    /**
     * Creates a new configuration with the specified default TTL.
     */
    public CacheConfig withDefaultTtl(TimeSpan defaultTtl) {
        return new CacheConfig(name, maxSize, defaultTtl, eviction);
    }

    /**
     * Creates a new configuration with the specified eviction policy.
     */
    public CacheConfig withEviction(EvictionPolicy eviction) {
        return new CacheConfig(name, maxSize, defaultTtl, eviction);
    }

    /**
     * Creates configuration for unlimited size.
     */
    public CacheConfig unlimited() {
        return new CacheConfig(name, 0, defaultTtl, eviction);
    }
}
