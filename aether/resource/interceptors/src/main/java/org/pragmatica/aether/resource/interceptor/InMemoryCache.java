package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.ConcurrentHashMap;

/// Simple in-memory cache with TTL-based expiry.
///
/// Thread-safe via {@link ConcurrentHashMap} operations. No background eviction —
/// expired entries are lazily removed on access.
final class InMemoryCache implements CacheBackend {
    private final ConcurrentHashMap<Object, CacheEntry> storage = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final int maxEntries;

    private record CacheEntry(Object value, long expiresAtNanos) {
        boolean isExpired() {
            return System.nanoTime() > expiresAtNanos;
        }
    }

    private InMemoryCache(long ttlNanos, int maxEntries) {
        this.ttlNanos = ttlNanos;
        this.maxEntries = maxEntries;
    }

    /// Create an in-memory cache with the given TTL and max entries.
    static InMemoryCache inMemoryCache(int ttlSeconds, int maxEntries) {
        return new InMemoryCache(ttlSeconds * 1_000_000_000L, maxEntries);
    }

    @Override
    public Promise<Option<Object>> get(Object key) {
        var entry = storage.get(key);
        if (entry == null) {
            return Promise.success(Option.none());
        }
        if (entry.isExpired()) {
            storage.remove(key, entry);
            return Promise.success(Option.none());
        }
        return Promise.success(Option.some(entry.value()));
    }

    @Override
    public Promise<Unit> put(Object key, Object value) {
        if (storage.size() >= maxEntries) {
            evictExpired();
        }
        storage.put(key, new CacheEntry(value, System.nanoTime() + ttlNanos));
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Unit> remove(Object key) {
        storage.remove(key);
        return Promise.success(Unit.unit());
    }

    private void evictExpired() {
        storage.entrySet()
               .removeIf(entry -> entry.getValue()
                                       .isExpired());
    }
}
