package org.pragmatica.aether.infra.cache;
/**
 * Cache statistics.
 *
 * @param hits       Number of cache hits
 * @param misses     Number of cache misses
 * @param size       Current number of entries
 * @param evictions  Number of entries evicted
 * @param expirations Number of entries expired
 */
public record CacheStats(long hits,
                         long misses,
                         long size,
                         long evictions,
                         long expirations) {
    /**
     * Creates empty statistics.
     */
    public static CacheStats empty() {
        return new CacheStats(0, 0, 0, 0, 0);
    }

    /**
     * Creates statistics with specified values.
     */
    public static CacheStats cacheStats(long hits, long misses, long size, long evictions, long expirations) {
        return new CacheStats(hits, misses, size, evictions, expirations);
    }

    /**
     * Returns the hit rate as a percentage (0.0 to 1.0).
     */
    public double hitRate() {
        var total = hits + misses;
        return total == 0
               ? 0.0
               : (double) hits / total;
    }

    /**
     * Returns the miss rate as a percentage (0.0 to 1.0).
     */
    public double missRate() {
        return 1.0 - hitRate();
    }

    /**
     * Returns total number of requests (hits + misses).
     */
    public long totalRequests() {
        return hits + misses;
    }

    /**
     * Creates new stats with incremented hits.
     */
    public CacheStats withHit() {
        return new CacheStats(hits + 1, misses, size, evictions, expirations);
    }

    /**
     * Creates new stats with incremented misses.
     */
    public CacheStats withMiss() {
        return new CacheStats(hits, misses + 1, size, evictions, expirations);
    }

    /**
     * Creates new stats with updated size.
     */
    public CacheStats withSize(long newSize) {
        return new CacheStats(hits, misses, newSize, evictions, expirations);
    }

    /**
     * Creates new stats with incremented evictions.
     */
    public CacheStats withEviction() {
        return new CacheStats(hits, misses, size, evictions + 1, expirations);
    }

    /**
     * Creates new stats with incremented expirations.
     */
    public CacheStats withExpiration() {
        return new CacheStats(hits, misses, size, evictions, expirations + 1);
    }
}
