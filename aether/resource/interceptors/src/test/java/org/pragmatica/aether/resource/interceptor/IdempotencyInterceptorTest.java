// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.dht.DHTError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.interceptor.IdempotencyMethodInterceptor.idempotencyMethodInterceptor;

class IdempotencyInterceptorTest {

    private static final Fn1<Object, ?> IDENTITY_KEY_EXTRACTOR = Fn1.id();

    private record TestError(String message) implements Cause {}

    @Nested
    class SequentialDedup {
        @Test
        void intercept_sameKeyTwice_runsOnceAndReturnsRecordedOutcome() {
            var store = InMemoryCache.inMemoryCache(60, 100);
            var interceptor = idempotencyMethodInterceptor(store, IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var intercepted = interceptor.intercept(method);

            var value1 = intercepted.apply("key1").await().fold(_ -> null, v -> v);
            assertThat(value1).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);

            var value2 = intercepted.apply("key1").await().fold(_ -> null, v -> v);
            assertThat(value2).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        void intercept_differentKeys_runEachOnce() {
            var store = InMemoryCache.inMemoryCache(60, 100);
            var interceptor = idempotencyMethodInterceptor(store, IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var intercepted = interceptor.intercept(method);

            var value1 = intercepted.apply("key1").await().fold(_ -> null, v -> v);
            var value2 = intercepted.apply("key2").await().fold(_ -> null, v -> v);
            assertThat(value1).isEqualTo("result-key1");
            assertThat(value2).isEqualTo("result-key2");
            assertThat(callCount.get()).isEqualTo(2);
        }
    }

    /// #1777 track 2: a store read that meets DHT replicas still catching up fails `NotCaughtUp`. That is not
    /// "no outcome recorded" — treating it as absent would re-run a request that may already have run — so
    /// the request fails retryably and the method does not run.
    @Nested
    class StoreUnreadable {
        @Test
        void intercept_storeReadNotCaughtUp_failsRetryably_andNeverRunsTheMethod() {
            var interceptor = idempotencyMethodInterceptor(notCaughtUpStore(), IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var outcome = interceptor.intercept(method).apply("key1").await();

            assertThat(outcome.isFailure()).isTrue();
            outcome.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.NotCaughtUp.class));
            assertThat(callCount.get()).as("the method must not run on an unreadable record").isZero();
        }

        private CacheBackend notCaughtUpStore() {
            return new CacheBackend() {
                @Override
                public Promise<Option<Object>> get(Object key) {
                    return DHTError.notCaughtUp(2, 0).promise();
                }

                @Override
                public Promise<Unit> put(Object key, Object value) {
                    return Promise.success(Unit.unit());
                }

                @Override
                public Promise<Unit> remove(Object key) {
                    return Promise.success(Unit.unit());
                }
            };
        }
    }

    @Nested
    class FailureRelease {
        @Test
        void intercept_firstAttemptFails_releasesClaimSoRetryReRuns() {
            var store = InMemoryCache.inMemoryCache(60, 100);
            var interceptor = idempotencyMethodInterceptor(store, IDENTITY_KEY_EXTRACTOR);
            var attempts = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                var attempt = attempts.incrementAndGet();
                return attempt == 1
                       ? new TestError("transient failure").promise()
                       : Promise.success("ok-" + request);
            };

            var intercepted = interceptor.intercept(method);

            var first = intercepted.apply("key1").await().fold(_ -> "failed", v -> v);
            assertThat(first).isEqualTo("failed");
            assertThat(attempts.get()).isEqualTo(1);

            var retry = intercepted.apply("key1").await().fold(_ -> "failed", v -> v);
            assertThat(retry).isEqualTo("ok-key1");
            assertThat(attempts.get()).isEqualTo(2);

            var third = intercepted.apply("key1").await().fold(_ -> "failed", v -> v);
            assertThat(third).isEqualTo("ok-key1");
            assertThat(attempts.get()).isEqualTo(2);
        }
    }

    @Nested
    class ConcurrentDedup {
        @Test
        void intercept_concurrentDuplicates_runUnderlyingLogicOnce() throws InterruptedException {
            var store = InMemoryCache.inMemoryCache(60, 1_000);
            var interceptor = idempotencyMethodInterceptor(store, IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            var methodEntered = new CountDownLatch(1);
            var inFlight = Promise.<String>promise();
            Fn1<Promise<String>, String> method = _ -> {
                callCount.incrementAndGet();
                methodEntered.countDown();
                return inFlight;
            };

            var intercepted = interceptor.intercept(method);
            var threadCount = 8;
            var barrier = new CyclicBarrier(threadCount);
            var done = new CountDownLatch(threadCount);
            var results = new ConcurrentLinkedQueue<String>();

            try (var executor = Executors.newFixedThreadPool(threadCount)) {
                for (int i = 0; i < threadCount; i++) {
                    executor.submit(() -> runDuplicate(intercepted, barrier, results, done));
                }

                methodEntered.await();
                Thread.sleep(100);
                inFlight.succeed("result");
                done.await();
            }

            assertThat(callCount.get()).isEqualTo(1);
            assertThat(results).hasSize(threadCount);
            assertThat(results).allMatch("result"::equals);
        }

        private void runDuplicate(Fn1<Promise<String>, String> intercepted,
                                  CyclicBarrier barrier,
                                  ConcurrentLinkedQueue<String> results,
                                  CountDownLatch done) {
            try {
                barrier.await();
                results.add(intercepted.apply("key1").await().fold(_ -> "FAIL", v -> v));
            } catch (Exception e) {
                results.add("ERROR");
            } finally {
                done.countDown();
            }
        }
    }

    @Nested
    class Provisioning {
        @Test
        void provision_sameStoreName_sharesStoreAndClaims() {
            var factory = new IdempotencyInterceptorFactory();
            var config = IdempotencyConfig.idempotencyConfig("shared-store")
                                          .fold(_ -> null, v -> v);

            var interceptor1 = factory.provision(config).await().fold(_ -> null, v -> v);
            var interceptor2 = factory.provision(config).await().fold(_ -> null, v -> v);

            assertThat(interceptor1).isNotNull();
            assertThat(interceptor2).isNotNull();
            assertThat(interceptor1.store()).isSameAs(interceptor2.store());
            assertThat(interceptor1.claims()).isSameAs(interceptor2.claims());
        }

        @Test
        void provision_defaultConfig_dedupsThroughProvisionedInterceptor() {
            var factory = new IdempotencyInterceptorFactory();
            var interceptor = factory.provision(IdempotencyConfig.idempotencyConfig())
                                     .await()
                                     .fold(_ -> null, v -> v);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var intercepted = interceptor.intercept(method);

            var value1 = intercepted.apply("key1").await().fold(_ -> null, v -> v);
            var value2 = intercepted.apply("key1").await().fold(_ -> null, v -> v);
            assertThat(value1).isEqualTo("result-key1");
            assertThat(value2).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);
        }
    }
}
