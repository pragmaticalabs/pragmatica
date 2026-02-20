package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCacheTest {

    private static Option<Object> getCached(CacheBackend cache, Object key) {
        return cache.get(key).await().fold(_ -> Option.none(), v -> v);
    }

    @Nested
    class PutAndGet {
        @Test
        void putAndGet_basicFlow_returnsStoredValue() {
            var cache = InMemoryCache.inMemoryCache(60, 100);

            cache.put("key1", "value1");

            var result = getCached(cache, "key1");
            assertThat(result.isPresent()).isTrue();
            assertThat(result.or("missing")).isEqualTo("value1");
        }

        @Test
        void putAndGet_multipleEntries_returnsCorrectValues() {
            var cache = InMemoryCache.inMemoryCache(60, 100);

            cache.put("key1", "value1");
            cache.put("key2", "value2");

            assertThat(getCached(cache, "key1").or("missing")).isEqualTo("value1");
            assertThat(getCached(cache, "key2").or("missing")).isEqualTo("value2");
        }

        @Test
        void putAndGet_overwriteEntry_returnsLatestValue() {
            var cache = InMemoryCache.inMemoryCache(60, 100);

            cache.put("key1", "value1");
            cache.put("key1", "value2");

            assertThat(getCached(cache, "key1").or("missing")).isEqualTo("value2");
        }
    }

    @Nested
    class GetMiss {
        @Test
        void get_nonExistentKey_returnsNone() {
            var cache = InMemoryCache.inMemoryCache(60, 100);

            var result = getCached(cache, "missing");

            assertThat(result.isEmpty()).isTrue();
        }
    }

    @Nested
    class Expiry {
        @Test
        void get_expiredEntry_returnsNone() throws InterruptedException {
            var cache = InMemoryCache.inMemoryCache(1, 100);

            cache.put("key1", "value1");
            assertThat(getCached(cache, "key1").isPresent()).isTrue();

            Thread.sleep(1_100);

            assertThat(getCached(cache, "key1").isEmpty()).isTrue();
        }
    }

    @Nested
    class Remove {
        @Test
        void remove_existingEntry_returnsNoneAfterRemoval() {
            var cache = InMemoryCache.inMemoryCache(60, 100);

            cache.put("key1", "value1");
            assertThat(getCached(cache, "key1").isPresent()).isTrue();

            cache.remove("key1");

            assertThat(getCached(cache, "key1").isEmpty()).isTrue();
        }

        @Test
        void remove_nonExistentKey_noError() {
            var cache = InMemoryCache.inMemoryCache(60, 100);

            cache.remove("missing");

            assertThat(getCached(cache, "missing").isEmpty()).isTrue();
        }
    }

    @Nested
    class ConcurrentAccess {
        @Test
        void putAndGet_concurrentAccess_noDataLoss() throws InterruptedException {
            var cache = InMemoryCache.inMemoryCache(60, 10_000);
            var threadCount = 8;
            var entriesPerThread = 100;
            var latch = new CountDownLatch(threadCount);
            var errors = new AtomicInteger(0);

            try (var executor = Executors.newFixedThreadPool(threadCount)) {
                for (int t = 0; t < threadCount; t++) {
                    var threadId = t;
                    executor.submit(() -> {
                        try {
                            for (int i = 0; i < entriesPerThread; i++) {
                                var key = "thread-" + threadId + "-key-" + i;
                                var value = "value-" + threadId + "-" + i;
                                cache.put(key, value);
                                var retrieved = getCached(cache, key);
                                if (retrieved.isEmpty()) {
                                    errors.incrementAndGet();
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
            }

            assertThat(errors.get()).isZero();
        }
    }
}
