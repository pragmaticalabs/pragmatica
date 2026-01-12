package org.pragmatica.aether.infra.cache;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of CacheService.
 * Provides thread-safe key-value storage with TTL support.
 */
final class InMemoryCacheService implements CacheService {
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final CacheConfig config;
    private final AtomicReference<CacheStats> stats = new AtomicReference<>(CacheStats.empty());

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                                                      var thread = new Thread(r,
                                                                                                                              "cache-cleanup");
                                                                                                      thread.setDaemon(true);
                                                                                                      return thread;
                                                                                                  });

    private InMemoryCacheService(CacheConfig config) {
        this.config = config;
        scheduler.scheduleAtFixedRate(this::cleanupExpired, 1, 1, TimeUnit.MINUTES);
    }

    static InMemoryCacheService inMemoryCacheService() {
        return new InMemoryCacheService(CacheConfig.cacheConfig()
                                                   .fold(_ -> new CacheConfig("default",
                                                                              10_000,
                                                                              org.pragmatica.lang.io.TimeSpan.timeSpan(1)
                                                                                 .hours(),
                                                                              CacheConfig.EvictionPolicy.LRU),
                                                         c -> c));
    }

    static InMemoryCacheService inMemoryCacheService(CacheConfig config) {
        return new InMemoryCacheService(config);
    }

    // ========== Basic Operations ==========
    @Override
    public Promise<Unit> set(String key, String value) {
        cache.put(key, CacheEntry.cacheEntry(value, none()));
        updateSize();
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> set(String key, String value, Duration ttl) {
        var expiry = Instant.now()
                            .plus(ttl);
        cache.put(key, CacheEntry.cacheEntry(value, option(expiry)));
        updateSize();
        return Promise.success(unit());
    }

    @Override
    public Promise<Option<String>> get(String key) {
        return Promise.success(option(cache.get(key))
                                     .flatMap(entry -> getIfNotExpired(key, entry))
                                     .onPresent(_ -> recordHit())
                                     .onEmpty(this::recordMiss));
    }

    private Option<String> getIfNotExpired(String key, CacheEntry entry) {
        if (entry.isExpired()) {
            cache.remove(key);
            recordExpiration();
            return none();
        }
        return option(entry.value());
    }

    @Override
    public Promise<Boolean> delete(String key) {
        updateSize();
        return Promise.success(option(cache.remove(key))
                                     .isPresent());
    }

    @Override
    public Promise<Boolean> exists(String key) {
        return Promise.success(option(cache.get(key))
                                     .filter(entry -> !entry.isExpired())
                                     .isPresent());
    }

    // ========== TTL Operations ==========
    @Override
    public Promise<Boolean> expire(String key, Duration ttl) {
        return Promise.success(option(cache.get(key))
                                     .filter(entry -> !entry.isExpired())
                                     .map(entry -> {
                                              var expiry = Instant.now()
                                                                  .plus(ttl);
                                              cache.put(key,
                                                        CacheEntry.cacheEntry(entry.value(),
                                                                              option(expiry)));
                                              return true;
                                          })
                                     .or(false));
    }

    @Override
    public Promise<Option<Duration>> ttl(String key) {
        return Promise.success(option(cache.get(key))
                                     .filter(entry -> !entry.isExpired())
                                     .flatMap(entry -> entry.expiry()
                                                            .map(this::remainingDuration)));
    }

    private Duration remainingDuration(Instant expiry) {
        var remaining = Duration.between(Instant.now(), expiry);
        return remaining.isNegative()
               ? Duration.ZERO
               : remaining;
    }

    // ========== Batch Operations ==========
    @Override
    public Promise<Map<String, String>> getMulti(Set<String> keys) {
        var result = new HashMap<String, String>();
        keys.forEach(key -> option(cache.get(key))
                                  .filter(entry -> !entry.isExpired())
                                  .onPresent(entry -> {
                         result.put(key,
                                    entry.value());
                         recordHit();
                     })
                                  .onEmpty(this::recordMiss));
        return Promise.success(result);
    }

    @Override
    public Promise<Unit> setMulti(Map<String, String> entries) {
        entries.forEach((key, value) -> cache.put(key, CacheEntry.cacheEntry(value, none())));
        updateSize();
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> setMulti(Map<String, String> entries, Duration ttl) {
        var expiry = Instant.now()
                            .plus(ttl);
        entries.forEach((key, value) -> cache.put(key, CacheEntry.cacheEntry(value, option(expiry))));
        updateSize();
        return Promise.success(unit());
    }

    @Override
    public Promise<Integer> deleteMulti(Set<String> keys) {
        var count = (int) keys.stream()
                             .filter(key -> option(cache.remove(key))
                                                  .isPresent())
                             .count();
        updateSize();
        return Promise.success(count);
    }

    // ========== Counter Operations ==========
    @Override
    public Promise<Long> increment(String key) {
        return incrementBy(key, 1);
    }

    @Override
    public Promise<Long> incrementBy(String key, long delta) {
        var entry = cache.compute(key,
                                  (k, existing) -> {
                                      var currentValue = option(existing)
                                                               .filter(e -> !e.isExpired())
                                                               .map(e -> parseLong(e.value()))
                                                               .or(0L);
                                      var newValue = currentValue + delta;
                                      return CacheEntry.cacheEntry(String.valueOf(newValue), none());
                                  });
        updateSize();
        return Promise.success(parseLong(entry.value()));
    }

    @Override
    public Promise<Long> decrement(String key) {
        return incrementBy(key, - 1);
    }

    private long parseLong(String value) {
        try{
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ========== Pattern Operations ==========
    @Override
    public Promise<Set<String>> keys(String pattern) {
        var regex = Pattern.compile(pattern.replace("*", ".*"));
        var result = new HashSet<String>();
        cache.keySet()
             .forEach(key -> {
                 if (regex.matcher(key)
                          .matches()) {
                     result.add(key);
                 }
             });
        return Promise.success(result);
    }

    @Override
    public Promise<Integer> deletePattern(String pattern) {
        var regex = Pattern.compile(pattern.replace("*", ".*"));
        var count = (int) cache.keySet()
                              .stream()
                              .filter(key -> regex.matcher(key)
                                                  .matches())
                              .filter(key -> option(cache.remove(key))
                                                   .isPresent())
                              .count();
        updateSize();
        return Promise.success(count);
    }

    // ========== Statistics ==========
    @Override
    public Promise<CacheStats> stats() {
        return Promise.success(stats.get()
                                    .withSize(cache.size()));
    }

    @Override
    public Promise<Unit> clear() {
        cache.clear();
        updateSize();
        return Promise.success(unit());
    }

    // ========== Lifecycle ==========
    @Override
    public Promise<Unit> stop() {
        scheduler.shutdown();
        cache.clear();
        return Promise.success(unit());
    }

    // ========== Internal ==========
    private void cleanupExpired() {
        var now = Instant.now();
        var expiredCount = cache.entrySet()
                                .stream()
                                .filter(e -> e.getValue()
                                              .isExpiredAt(now))
                                .peek(e -> cache.remove(e.getKey()))
                                .count();
        if (expiredCount > 0) {
            for (int i = 0; i < expiredCount; i++) {
                recordExpiration();
            }
            updateSize();
        }
    }

    private void recordHit() {
        stats.updateAndGet(CacheStats::withHit);
    }

    private void recordMiss() {
        stats.updateAndGet(CacheStats::withMiss);
    }

    private void recordExpiration() {
        stats.updateAndGet(CacheStats::withExpiration);
    }

    private void updateSize() {
        stats.updateAndGet(s -> s.withSize(cache.size()));
    }

    private record CacheEntry(String value, Option<Instant> expiry) {
        static CacheEntry cacheEntry(String value, Option<Instant> expiry) {
            return new CacheEntry(value, expiry);
        }

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
