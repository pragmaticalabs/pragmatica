package org.pragmatica.aether.infra.ratelimit;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/**
 * Distributed rate limiting service for overload protection.
 */
public interface RateLimiter extends Slice {
    /**
     * Check if request is allowed and consume one permit.
     *
     * @param key Rate limit key (e.g., user ID, API key)
     * @return Rate limit result
     */
    Promise<RateLimitResult> acquire(String key);

    /**
     * Check if request is allowed and consume specified permits.
     *
     * @param key     Rate limit key
     * @param permits Number of permits to consume
     * @return Rate limit result
     */
    Promise<RateLimitResult> acquire(String key, int permits);

    /**
     * Check rate limit status without consuming permits.
     *
     * @param key Rate limit key
     * @return Rate limit result
     */
    Promise<RateLimitResult> check(String key);

    /**
     * Configure rate limit for a specific key.
     *
     * @param key    Rate limit key
     * @param config Rate limit configuration
     * @return Promise completing when configuration is applied
     */
    Promise<Unit> configure(String key, RateLimitConfig config);

    /**
     * Factory method for in-memory implementation with default config.
     *
     * @param defaultConfig Default configuration for unconfigured keys
     * @return RateLimiter instance
     */
    static RateLimiter inMemory(RateLimitConfig defaultConfig) {
        return new InMemoryRateLimiter(defaultConfig);
    }

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
