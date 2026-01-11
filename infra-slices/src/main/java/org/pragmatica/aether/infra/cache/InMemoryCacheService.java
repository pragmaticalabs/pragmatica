package org.pragmatica.aether.infra.cache;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of CacheService.
 * Provides thread-safe key-value storage with TTL support.
 */
final class InMemoryCacheService implements CacheService {
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                                                      var thread = new Thread(r,
                                                                                                                              "cache-cleanup");
                                                                                                      thread.setDaemon(true);
                                                                                                      return thread;
                                                                                                  });

    InMemoryCacheService() {
        scheduler.scheduleAtFixedRate(this::cleanupExpired, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public Promise<Unit> set(String key, String value) {
        cache.put(key, new CacheEntry(value, none()));
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> set(String key, String value, Duration ttl) {
        var expiry = Instant.now()
                            .plus(ttl);
        cache.put(key, new CacheEntry(value, option(expiry)));
        return Promise.success(unit());
    }

    @Override
    public Promise<Option<String>> get(String key) {
        var entry = cache.get(key);
        if (entry == null) {
            return Promise.success(none());
        }
        if (entry.isExpired()) {
            cache.remove(key);
            return Promise.success(none());
        }
        return Promise.success(option(entry.value()));
    }

    @Override
    public Promise<Boolean> delete(String key) {
        var removed = cache.remove(key);
        return Promise.success(removed != null);
    }

    @Override
    public Promise<Boolean> exists(String key) {
        var entry = cache.get(key);
        if (entry == null) {
            return Promise.success(false);
        }
        if (entry.isExpired()) {
            cache.remove(key);
            return Promise.success(false);
        }
        return Promise.success(true);
    }

    @Override
    public Promise<Boolean> expire(String key, Duration ttl) {
        var entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
            return Promise.success(false);
        }
        var expiry = Instant.now()
                            .plus(ttl);
        cache.put(key, new CacheEntry(entry.value(), option(expiry)));
        return Promise.success(true);
    }

    @Override
    public Promise<Option<Duration>> ttl(String key) {
        var entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
            return Promise.success(none());
        }
        return Promise.success(entry.expiry()
                                    .map(this::remainingDuration));
    }

    private Duration remainingDuration(Instant expiry) {
        var remaining = Duration.between(Instant.now(), expiry);
        return remaining.isNegative()
               ? Duration.ZERO
               : remaining;
    }

    @Override
    public Promise<Unit> stop() {
        scheduler.shutdown();
        cache.clear();
        return Promise.success(unit());
    }

    private void cleanupExpired() {
        var now = Instant.now();
        cache.entrySet()
             .removeIf(e -> e.getValue()
                             .isExpiredAt(now));
    }

    private record CacheEntry(String value, Option<Instant> expiry) {
        boolean isExpired() {
            return expiry.map(exp -> Instant.now()
                                            .isAfter(exp))
                         .or(false);
        }

        boolean isExpiredAt(Instant time) {
            return expiry.map(time::isAfter)
                         .or(false);
        }
    }
}
