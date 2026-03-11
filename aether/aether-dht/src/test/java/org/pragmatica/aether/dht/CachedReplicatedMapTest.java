package org.pragmatica.aether.dht;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.dht.CachedReplicatedMap.cachedReplicatedMap;

class CachedReplicatedMapTest {
    private StubReplicatedMap delegate;
    private CachedReplicatedMap<String, String> cached;

    @BeforeEach
    void setUp() {
        delegate = new StubReplicatedMap();
        cached = cachedReplicatedMap(delegate, 10, 5000);
    }

    @Nested
    class CacheHit {
        @Test
        void get_afterPut_returnsCachedValue() {
            cached.put("k1", "v1").await();
            // Simulate DHT returning the value (subscription fires onPut)
            delegate.getCount.set(0);

            var result = cached.get("k1").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo("v1")));
            // Should not have called delegate.get since value was cached via onPut
            assertThat(delegate.getCount.get()).isZero();
        }
    }

    @Nested
    class CacheMiss {
        @Test
        void get_uncachedKey_fetchesFromDelegate() {
            delegate.valueToReturn = Option.some("from-dht");

            var result = cached.get("missing").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo("from-dht")));
            assertThat(delegate.getCount.get()).isEqualTo(1);
        }

        @Test
        void get_noneFromDelegate_returnsNone() {
            delegate.valueToReturn = Option.none();

            var result = cached.get("missing").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
        }
    }

    @Nested
    class CacheEviction {
        @Test
        void get_lruEviction_evictsOldestEntry() {
            var smallCached = cachedReplicatedMap(delegate, 2, 5000);

            // Fill cache to capacity
            smallCached.put("k1", "v1").await();
            smallCached.put("k2", "v2").await();
            // Evict k1 by adding k3
            smallCached.put("k3", "v3").await();

            assertThat(smallCached.cacheSize()).isEqualTo(2);

            // k1 should now require a DHT fetch
            delegate.getCount.set(0);
            delegate.valueToReturn = Option.some("v1-refetched");
            smallCached.get("k1").await();

            assertThat(delegate.getCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class TtlExpiry {
        @Test
        void get_expiredEntry_fetchesFromDelegate() {
            var shortTtl = cachedReplicatedMap(delegate, 10, 1);

            shortTtl.put("k1", "v1").await();

            // Wait for TTL to expire
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            delegate.getCount.set(0);
            delegate.valueToReturn = Option.some("v1-fresh");
            shortTtl.get("k1").await();

            assertThat(delegate.getCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class Invalidation {
        @Test
        void onPut_updatesCache() {
            cached.put("k1", "v1").await();
            // Update via subscription
            cached.onPut("k1", "v2");

            delegate.getCount.set(0);
            var result = cached.get("k1").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo("v2")));
            assertThat(delegate.getCount.get()).isZero();
        }

        @Test
        void onRemove_invalidatesCache() {
            cached.put("k1", "v1").await();
            cached.onRemove("k1");

            delegate.getCount.set(0);
            delegate.valueToReturn = Option.none();
            cached.get("k1").await();

            // Should fetch from delegate since cache was invalidated
            assertThat(delegate.getCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class WriteThrough {
        @Test
        void put_delegatesToUnderlyingMap() {
            cached.put("k1", "v1").await();

            assertThat(delegate.putCount.get()).isEqualTo(1);
        }

        @Test
        void remove_delegatesToUnderlyingMap() {
            delegate.removeResult = true;
            cached.remove("k1").await();

            assertThat(delegate.removeCount.get()).isEqualTo(1);
        }
    }

    /// Stub ReplicatedMap for cache testing.
    static class StubReplicatedMap implements ReplicatedMap<String, String> {
        Option<String> valueToReturn = Option.none();
        boolean removeResult = false;
        final AtomicInteger getCount = new AtomicInteger(0);
        final AtomicInteger putCount = new AtomicInteger(0);
        final AtomicInteger removeCount = new AtomicInteger(0);
        private MapSubscription<String, String> subscription;

        @Override
        public Promise<Unit> put(String key, String value) {
            putCount.incrementAndGet();
            if (subscription != null) {
                subscription.onPut(key, value);
            }
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Option<String>> get(String key) {
            getCount.incrementAndGet();
            return Promise.success(valueToReturn);
        }

        @Override
        public Promise<Boolean> remove(String key) {
            removeCount.incrementAndGet();
            if (removeResult && subscription != null) {
                subscription.onRemove(key);
            }
            return Promise.success(removeResult);
        }

        @Override
        public ReplicatedMap<String, String> subscribe(MapSubscription<String, String> subscription) {
            this.subscription = subscription;
            return this;
        }

        @Override
        public String name() {
            return "stub-map";
        }
    }
}
