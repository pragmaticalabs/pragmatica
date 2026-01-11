package org.pragmatica.aether.infra.cache;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Duration;
import java.util.List;

/**
 * Cache service providing basic key-value operations.
 * Built on Aether primitives, in-memory first implementation.
 */
public interface CacheService extends Slice {
    /**
     * Set a value for the given key.
     *
     * @param key   The key
     * @param value The value
     * @return Unit on success
     */
    Promise<Unit> set(String key, String value);

    /**
     * Set a value with expiration.
     *
     * @param key   The key
     * @param value The value
     * @param ttl   Time to live
     * @return Unit on success
     */
    Promise<Unit> set(String key, String value, Duration ttl);

    /**
     * Get the value for the given key.
     *
     * @param key The key
     * @return Option containing the value if present
     */
    Promise<Option<String>> get(String key);

    /**
     * Delete the value for the given key.
     *
     * @param key The key
     * @return true if key existed and was deleted
     */
    Promise<Boolean> delete(String key);

    /**
     * Check if a key exists.
     *
     * @param key The key
     * @return true if key exists
     */
    Promise<Boolean> exists(String key);

    /**
     * Set TTL on an existing key.
     *
     * @param key The key
     * @param ttl Time to live
     * @return true if key exists and TTL was set
     */
    Promise<Boolean> expire(String key, Duration ttl);

    /**
     * Get remaining TTL for a key.
     *
     * @param key The key
     * @return Option containing remaining TTL if key exists and has TTL
     */
    Promise<Option<Duration>> ttl(String key);

    /**
     * Factory method for in-memory implementation.
     *
     * @return CacheService instance
     */
    static CacheService cacheService() {
        return new InMemoryCacheService();
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
