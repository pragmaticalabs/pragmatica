package org.pragmatica.aether.infra.cache;

import org.pragmatica.aether.infra.InfraStore;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cache service providing key-value operations with TTL support.
 * Built on Aether primitives, in-memory first implementation.
 */
public interface CacheService extends Slice {
    // ========== Basic Operations ==========
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

    // ========== TTL Operations ==========
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

    // ========== Batch Operations ==========
    /**
     * Get multiple values at once.
     *
     * @param keys The keys to retrieve
     * @return Map of key to value (only existing keys included)
     */
    Promise<Map<String, String>> getMulti(Set<String> keys);

    /**
     * Set multiple key-value pairs at once.
     *
     * @param entries Map of key-value pairs
     * @return Unit on success
     */
    Promise<Unit> setMulti(Map<String, String> entries);

    /**
     * Set multiple key-value pairs with TTL.
     *
     * @param entries Map of key-value pairs
     * @param ttl     Time to live for all entries
     * @return Unit on success
     */
    Promise<Unit> setMulti(Map<String, String> entries, Duration ttl);

    /**
     * Delete multiple keys at once.
     *
     * @param keys The keys to delete
     * @return Number of keys deleted
     */
    Promise<Integer> deleteMulti(Set<String> keys);

    // ========== Counter Operations ==========
    /**
     * Increment a counter by 1.
     *
     * @param key The counter key
     * @return The new value after increment
     */
    Promise<Long> increment(String key);

    /**
     * Increment a counter by a specific amount.
     *
     * @param key   The counter key
     * @param delta The amount to increment by
     * @return The new value after increment
     */
    Promise<Long> incrementBy(String key, long delta);

    /**
     * Decrement a counter by 1.
     *
     * @param key The counter key
     * @return The new value after decrement
     */
    Promise<Long> decrement(String key);

    // ========== Pattern Operations ==========
    /**
     * Get all keys matching a pattern.
     *
     * @param pattern The pattern (supports * wildcard)
     * @return Set of matching keys
     */
    Promise<Set<String>> keys(String pattern);

    /**
     * Delete all keys matching a pattern.
     *
     * @param pattern The pattern (supports * wildcard)
     * @return Number of keys deleted
     */
    Promise<Integer> deletePattern(String pattern);

    // ========== Statistics ==========
    /**
     * Get cache statistics.
     *
     * @return Current cache statistics
     */
    Promise<CacheStats> stats();

    /**
     * Clear all entries from the cache.
     *
     * @return Unit on success
     */
    Promise<Unit> clear();

    // ========== Factory Methods ==========
    String ARTIFACT_KEY = "org.pragmatica-lite.aether:infra-cache";
    String VERSION = "0.7.0";

    /**
     * Factory method for shared in-memory implementation with default config.
     * <p>
     * Uses InfraStore to ensure all slices share the same CacheService instance.
     *
     * @return Shared CacheService instance
     */
    static CacheService cacheService() {
        return InfraStore.instance()
                         .map(store -> store.getOrCreate(ARTIFACT_KEY,
                                                         VERSION,
                                                         CacheService.class,
                                                         InMemoryCacheService::inMemoryCacheService))
                         .or(InMemoryCacheService::inMemoryCacheService);
    }

    /**
     * Factory method for shared in-memory implementation with custom config.
     * <p>
     * Uses InfraStore to ensure all slices share the same CacheService instance.
     * Note: Config is only applied on first creation; subsequent calls return existing instance.
     *
     * @param config Cache configuration (used only on first creation)
     * @return Shared CacheService instance
     */
    static CacheService cacheService(CacheConfig config) {
        return InfraStore.instance()
                         .map(store -> store.getOrCreate(ARTIFACT_KEY,
                                                         VERSION,
                                                         CacheService.class,
                                                         () -> InMemoryCacheService.inMemoryCacheService(config)))
                         .or(() -> InMemoryCacheService.inMemoryCacheService(config));
    }

    // ========== Slice Lifecycle ==========
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
