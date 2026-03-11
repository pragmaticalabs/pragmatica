package org.pragmatica.aether.dht;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// LRU cache with TTL wrapping a ReplicatedMap for point lookups.
/// Cache invalidation via MapSubscription events ensures consistency.
///
/// @param <K> key type
/// @param <V> value type
public final class CachedReplicatedMap<K, V> implements MapSubscription<K, V> {
    private static final Logger log = LoggerFactory.getLogger(CachedReplicatedMap.class);

    private final ReplicatedMap<K, V> delegate;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<K, CacheEntry<V>> cache;
    private final long ttlMs;

    private CachedReplicatedMap(ReplicatedMap<K, V> delegate, int maxSize, long ttlMs) {
        this.delegate = delegate;
        this.ttlMs = ttlMs;
        this.cache = newLruCache(maxSize);
        delegate.subscribe(this);
    }

    /// Create a cached wrapper around a ReplicatedMap.
    ///
    /// @param delegate the backing ReplicatedMap
    /// @param maxSize  maximum cache entries (LRU eviction)
    /// @param ttlMs    time-to-live in milliseconds for cache entries
    public static <K, V> CachedReplicatedMap<K, V> cachedReplicatedMap(ReplicatedMap<K, V> delegate,
                                                                       int maxSize,
                                                                       long ttlMs) {
        return new CachedReplicatedMap<>(delegate, maxSize, ttlMs);
    }

    /// Put: writes through to DHT. Cache updated via subscription callback.
    public Promise<Unit> put(K key, V value) {
        return delegate.put(key, value);
    }

    /// Get: check cache first, fallback to DHT.
    public Promise<Option<V>> get(K key) {
        var cached = readFromCache(key);
        if (cached.isPresent()) {
            return Promise.success(cached);
        }
        return delegate.get(key)
                       .onSuccess(opt -> opt.onPresent(value -> cacheValue(key, value)));
    }

    /// Remove: writes through to DHT. Cache invalidated via subscription callback.
    public Promise<Boolean> remove(K key) {
        return delegate.remove(key);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01") // Event callback - void required
    public void onPut(K key, V value) {
        cacheValue(key, value);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01") // Event callback - void required
    public void onRemove(K key) {
        evictFromCache(key);
    }

    /// Current cache size (for monitoring).
    public int cacheSize() {
        lock.readLock()
            .lock();
        try{
            return cache.size();
        } finally{
            lock.readLock()
                .unlock();
        }
    }

    private Option<V> readFromCache(K key) {
        lock.readLock()
            .lock();
        try{
            return Option.option(cache.get(key))
                         .filter(entry -> !entry.isExpired(ttlMs))
                         .map(CacheEntry::value);
        } finally{
            lock.readLock()
                .unlock();
        }
    }

    @SuppressWarnings("JBCT-RET-01") // Cache side-effect - void required
    private void cacheValue(K key, V value) {
        lock.writeLock()
            .lock();
        try{
            cache.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
        } finally{
            lock.writeLock()
                .unlock();
        }
    }

    @SuppressWarnings("JBCT-RET-01") // Cache side-effect - void required
    private void evictFromCache(K key) {
        lock.writeLock()
            .lock();
        try{
            cache.remove(key);
        } finally{
            lock.writeLock()
                .unlock();
        }
    }

    private static <K, V> Map<K, CacheEntry<V>> newLruCache(int maxSize) {
        return new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                return size() > maxSize;
            }
        };
    }

    private record CacheEntry<V>(V value, long createdAt) {
        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - createdAt > ttlMs;
        }
    }
}
