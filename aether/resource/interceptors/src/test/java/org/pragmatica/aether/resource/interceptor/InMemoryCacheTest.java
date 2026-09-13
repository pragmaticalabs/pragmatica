// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

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

    /// #279 (4): `put` evicted only EXPIRED entries when full and then inserted unconditionally, so
    /// `maxEntries` bounded nothing for a hot cache — every entry live, every put growing the map.
    @Nested
    class Bound {
        @Test
        void put_beyondMaxEntries_withNothingExpired_evictsToStayWithinTheBound() {
            var cache = InMemoryCache.inMemoryCache(60, 3);

            for (int i = 0; i < 10; i++) {
                cache.put("key" + i, "value" + i);
            }

            var present = 0;

            for (int i = 0; i < 10; i++) {
                present += getCached(cache, "key" + i).isPresent() ? 1 : 0;
            }

            assertThat(present).as("maxEntries is a cap, not a hint").isLessThanOrEqualTo(3);
        }

        @Test
        void put_beyondMaxEntries_keepsTheMostRecentlyUsed() {
            var cache = InMemoryCache.inMemoryCache(60, 2);

            cache.put("a", "1");
            cache.put("b", "2");
            getCached(cache, "a");
            cache.put("c", "3");

            assertThat(getCached(cache, "a").isPresent()).as("a was touched after b, so b is the victim").isTrue();
            assertThat(getCached(cache, "c").isPresent()).isTrue();
            assertThat(getCached(cache, "b").isEmpty()).isTrue();
        }

        @Test
        void put_overwriteAtTheBound_doesNotEvict() {
            var cache = InMemoryCache.inMemoryCache(60, 2);

            cache.put("a", "1");
            cache.put("b", "2");
            cache.put("a", "1'");

            assertThat(getCached(cache, "a").or("missing")).isEqualTo("1'");
            assertThat(getCached(cache, "b").isPresent()).isTrue();
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
        /// Deterministic pin for the monitor (review of #1084, N-3): 16 threads hammering get/put/remove
        /// over 200 keys into a cap of 64. With the `synchronized` blocks removed this throws
        /// `ConcurrentModificationException` from the access-ordered map every run; the older
        /// no-data-loss test below reddened only 2 runs in 3.
        @Test
        @SuppressWarnings("JBCT-EX-01")
        void mixedOperations_underContention_neverThrow_andHonourTheCap() throws InterruptedException {
            var cache = InMemoryCache.inMemoryCache(60, 64);
            var threadCount = 16;
            var operationsPerThread = 20_000;
            var latch = new CountDownLatch(threadCount);
            var errors = new java.util.concurrent.ConcurrentHashMap<String, Integer>();

            try (var executor = Executors.newFixedThreadPool(threadCount)) {
                for (int t = 0; t < threadCount; t++) {
                    var seed = t;
                    executor.submit(() -> {
                        try {
                            hammer(cache, seed, operationsPerThread);
                        } catch (RuntimeException e) {
                            errors.merge(e.getClass().getName(), 1, Integer::sum);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
            }

            var present = 0;

            for (int i = 0; i < 200; i++) {
                present += getCached(cache, "key-" + i).isPresent() ? 1 : 0;
            }

            assertThat(errors).as("no operation may throw under contention").isEmpty();
            assertThat(present).as("the cap holds under contention").isLessThanOrEqualTo(64);
        }

        private static void hammer(InMemoryCache cache, int seed, int operations) {
            for (int i = 0; i < operations; i++) {
                var key = "key-" + ((seed * 31 + i) % 200);

                switch (i % 3) {
                    case 0 -> cache.put(key, i);
                    case 1 -> cache.get(key);
                    default -> cache.remove(key);
                }
            }
        }

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
