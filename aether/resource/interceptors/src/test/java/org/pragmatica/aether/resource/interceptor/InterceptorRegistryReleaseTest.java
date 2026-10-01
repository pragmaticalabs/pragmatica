// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Functions.Fn1;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// #894: the name-keyed registries of [CacheInterceptorFactory] and [IdempotencyInterceptorFactory]
/// must drop a name once nothing holds it, and only then.
class InterceptorRegistryReleaseTest {

    /// One extractor instance for every provision, so two interceptors over one shared backend are
    /// equal by value — the case where tracking holders by `equals` would merge two holds into one.
    private static final Fn1<Object, Object> SHARED_EXTRACTOR = key -> key;
    private static final ProvisioningContext CONTEXT = ProvisioningContext.provisioningContext()
                                                                          .withKeyExtractor(SHARED_EXTRACTOR);

    @Nested
    class CacheFactory {
        private final CacheInterceptorFactory factory = new CacheInterceptorFactory();
        private final CacheConfig config = CacheConfig.cacheConfig("orders", CacheStrategy.CACHE_ASIDE)
                                                      .fold(_ -> null, v -> v);

        private CacheMethodInterceptor provision() {
            return factory.provision(config, CONTEXT).await().fold(_ -> null, v -> v);
        }

        private void release(CacheMethodInterceptor interceptor) {
            assertThat(factory.close(interceptor).await().isSuccess()).isTrue();
        }

        @Test
        void close_prunesName_whenSoleHolderReleased() {
            var interceptor = provision();
            assertThat(factory.retains("orders")).isTrue();

            release(interceptor);

            assertThat(factory.retains("orders")).isFalse();
        }

        @Test
        void close_keepsSharedBackend_untilLastHolderReleased() {
            var first = provision();
            var second = provision();
            assertThat(first.cache()).isSameAs(second.cache());

            release(first);
            assertThat(factory.retains("orders")).isTrue();
            assertThat(provisionAndRelease().cache()).isSameAs(second.cache());

            release(second);
            assertThat(factory.retains("orders")).isFalse();
        }

        @Test
        void close_isHarmless_whenSameInstanceReleasedTwice() {
            var first = provision();
            var second = provision();

            release(first);
            release(first);

            assertThat(factory.retains("orders")).isTrue();
            release(second);
            assertThat(factory.retains("orders")).isFalse();
        }

        @Test
        void close_tracksEqualInstancesAsDistinctHolders() {
            var first = provision();
            var second = provision();
            assertThat(first).isEqualTo(second)
                             .isNotSameAs(second);

            release(first);

            assertThat(factory.retains("orders")).isTrue();
            release(second);
            assertThat(factory.retains("orders")).isFalse();
        }

        @Test
        void provision_createsFreshBackend_afterNamePruned() {
            var before = provision();
            release(before);

            var after = provision();

            assertThat(after.cache()).isNotSameAs(before.cache());
            assertThat(after.cacheName().or("")).isEqualTo("orders");
        }

        private CacheMethodInterceptor provisionAndRelease() {
            var interceptor = provision();
            release(interceptor);
            return interceptor;
        }
    }

    @Nested
    class IdempotencyFactory {
        private final IdempotencyInterceptorFactory factory = new IdempotencyInterceptorFactory();
        private final IdempotencyConfig config = IdempotencyConfig.idempotencyConfig("payments")
                                                                  .fold(_ -> null, v -> v);

        private IdempotencyMethodInterceptor provision() {
            return factory.provision(config, CONTEXT).await().fold(_ -> null, v -> v);
        }

        private void release(IdempotencyMethodInterceptor interceptor) {
            assertThat(factory.close(interceptor).await().isSuccess()).isTrue();
        }

        @Test
        void close_prunesName_whenSoleHolderReleased() {
            var interceptor = provision();
            assertThat(factory.retains("payments")).isTrue();

            release(interceptor);

            assertThat(factory.retains("payments")).isFalse();
        }

        @Test
        void close_keepsSharedStoreAndClaims_untilLastHolderReleased() {
            var first = provision();
            var second = provision();
            assertThat(first.store()).isSameAs(second.store());
            assertThat(first.claims()).isSameAs(second.claims());

            release(first);
            assertThat(factory.retains("payments")).isTrue();
            var third = provision();
            assertThat(third.store()).isSameAs(second.store());
            assertThat(third.claims()).isSameAs(second.claims());
            release(third);

            release(second);
            assertThat(factory.retains("payments")).isFalse();
        }

        @Test
        void close_isHarmless_whenSameInstanceReleasedTwice() {
            var first = provision();
            var second = provision();

            release(first);
            release(first);

            assertThat(factory.retains("payments")).isTrue();
            release(second);
            assertThat(factory.retains("payments")).isFalse();
        }

        @Test
        void close_tracksEqualInstancesAsDistinctHolders() {
            var first = provision();
            var second = provision();
            assertThat(first).isEqualTo(second)
                             .isNotSameAs(second);

            release(first);

            assertThat(factory.retains("payments")).isTrue();
            release(second);
            assertThat(factory.retains("payments")).isFalse();
        }

        @Test
        void provision_createsFreshStoreAndClaims_afterNamePruned() {
            var before = provision();
            release(before);

            var after = provision();

            assertThat(after.store()).isNotSameAs(before.store());
            assertThat(after.claims()).isNotSameAs(before.claims());
        }
    }

    @Nested
    class Concurrency {
        private record Tag(String value) {}

        private static final int THREADS = 8;
        private static final int ROUNDS = 2_000;

        @Test
        void acquireRelease_concurrentOnOneName_neverPrunesALiveHoldAndPrunesAfterLast() throws Exception {
            var registry = SharedByName.<String>sharedByName();
            var anchor = registry.acquire("hot", "anchor", Tag::new);
            var shared = anchor.value();
            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            List<Future<Integer>> results = new ArrayList<>();

            try {
                for (int t = 0; t < THREADS; t++) {
                    results.add(pool.submit(() -> churn(registry, start, shared)));
                }
                start.countDown();

                for (var result : results) {
                    assertThat(result.get(60, TimeUnit.SECONDS)).isZero();
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(registry.contains("hot")).isTrue();
            registry.release(anchor);
            assertThat(registry.contains("hot")).isFalse();
        }

        /// Acquires and releases (twice) under a live anchor hold; counts every acquire that did NOT
        /// see the anchor's value, i.e. every time the entry was pruned while still held.
        private static int churn(SharedByName<String> registry, CountDownLatch start, String shared) throws InterruptedException {
            start.await();
            var splits = 0;

            for (int i = 0; i < ROUNDS; i++) {
                var tag = registry.acquire("hot", "candidate", Tag::new);

                if (!tag.value().equals(shared)) {
                    splits++;
                }
                registry.release(tag);
                registry.release(tag);
            }
            return splits;
        }
    }
}
