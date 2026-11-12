// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.SharedPoolFixtures.ContextReadingFactory;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConfig;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConnector;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakePlainFactory;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SharedPoolFixtures.TIMEOUT;
import static org.pragmatica.aether.resource.SharedPoolFixtures.acquire;
import static org.pragmatica.aether.resource.SharedPoolFixtures.providerOf;
import static org.pragmatica.aether.resource.SharedPoolFixtures.release;
import static org.pragmatica.aether.resource.SharedPoolFixtures.tryAcquire;

/// #2973 §2 and §8: node-shared pools through the real [SpiResourceProvider]. Every close assertion is on the
/// underlying instance BY IDENTITY, never a bare count.
class SharedPoolLifecycleTest {
    private static final String DB = "database";
    private static final String OTHER_DB = "database.other";

    private static Map<String, FakeConfig> sameValues() {
        return Map.of(DB, FakeConfig.shared("pg://h/db"), OTHER_DB, FakeConfig.shared("pg://h/db"));
    }

    @Nested
    class Sharing {
        @Test
        void twoSlices_sameConfig_shareOnePool() { // T1
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);

            var a = acquire(provider, DB, "slice-a");
            var b = acquire(provider, DB, "slice-b");

            assertThat(factory.provisioned).hasSize(1);
            assertThat(a.underlying()).isSameAs(b.underlying());
        }

        @Test
        void differentSectionNames_identicalValues_shareOnePool() { // T2
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);

            acquire(provider, DB, "slice-a");
            acquire(provider, OTHER_DB, "slice-b");

            assertThat(factory.provisioned).hasSize(1);
        }

        @Test
        void differentMaxConnections_giveTwoPools() { // T3
            var factory = new FakeShareableFactory();
            var provider = providerOf(Map.of(DB,
                                             FakeConfig.shared("pg://h/db"),
                                             OTHER_DB,
                                             FakeConfig.shared("pg://h/db")
                                                       .withDemand(1, 32)),
                                      factory);

            acquire(provider, DB, "slice-a");
            acquire(provider, OTHER_DB, "slice-b");

            assertThat(factory.provisioned).hasSize(2);
        }

        @Test
        void poolScopeSlice_givesEachSliceItsOwnRawPool_closedByItsOwnSlice() { // T6
            var factory = new FakeShareableFactory();
            var provider = providerOf(Map.of(DB, FakeConfig.perSlice("pg://h/db")), factory);

            var a = acquire(provider, DB, "slice-a");
            var b = acquire(provider, DB, "slice-b");

            assertThat(factory.provisioned).hasSize(2);
            assertThat(a).isSameAs(a.underlying());
            assertThat(b).isSameAs(b.underlying());

            release(provider, "slice-a");

            assertThat(factory.closed).containsExactly(a.underlying());

            release(provider, "slice-b");

            assertThat(factory.closed).containsExactly(a.underlying(), b.underlying());
        }

        @Test
        void unmarkedFactory_identicalConfig_keepsPerSlicePools() { // T7
            var factory = new FakePlainFactory();
            var provider = providerOf(sameValues(), factory);

            acquire(provider, DB, "slice-a");
            acquire(provider, DB, "slice-b");

            assertThat(factory.inner.provisioned).hasSize(2);
        }

        @Test
        void markerDeclaringContextProvision_isDemoted() { // T7b
            var factory = new ContextReadingFactory();
            var provider = providerOf(sameValues(), factory);

            acquire(provider, DB, "slice-a");
            acquire(provider, DB, "slice-b");

            assertThat(factory.provisioned).hasSize(2);
            assertThat(provider.demotedFactories()).containsExactly(ContextReadingFactory.class);
        }

        @Test
        void contextFreeCaller_takesTheUnattributedPath() { // T15
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);

            var first = provider.provide(FakeConnector.class, DB)
                                .await(TIMEOUT)
                                .unwrap();

            acquire(provider, DB, "slice-a");

            assertThat(first).isSameAs(first.underlying());
            assertThat(factory.provisioned).hasSize(2);
        }
    }

    @Nested
    class Release {
        @Test
        void releaseOfOneHolder_closesNothing_lastHolderClosesTheUnderlyingOnce() { // T4
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);
            var a = acquire(provider, DB, "slice-a");

            acquire(provider, DB, "slice-b");
            release(provider, "slice-a");

            assertThat(factory.closed).isEmpty();
            assertThat(a).isNotSameAs(a.underlying());

            release(provider, "slice-b");

            assertThat(factory.closed).containsExactly(a.underlying());
            assertThat(a.underlying()
                        .closeCount()).isEqualTo(1);
        }

        @Test
        void sameSliceTwice_receivesTheSameView_andOneReleaseCloses() { // T5
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);
            var first = acquire(provider, DB, "slice-a");
            var second = acquire(provider, DB, "slice-a");

            assertThat(second).isSameAs(first);

            release(provider, "slice-a");

            assertThat(factory.closed).containsExactly(first.underlying());
            assertThat(first.underlying()
                            .closeCount()).isEqualTo(1);
            assertThat(factory.views).hasSize(1);
        }

        @Test
        void releaseOfAbsentOrEvictedKey_closesNothing() { // T12c
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);

            release(provider, "slice-never");
            factory.provisioner = _ -> Causes.cause("boom")
                                             .promise();
            assertThat(tryAcquire(provider, DB, "slice-a").isFailure()).isTrue();
            release(provider, "slice-a");

            assertThat(factory.closed).isEmpty();
        }

        @Test
        void failedProvision_isEvictedBeforeTheCallersContinuation_retryProvisionsFresh() { // T9
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);
            var attempts = new AtomicInteger();

            factory.provisioner = _ -> attempts.incrementAndGet() == 1
                                       ? Causes.cause("first attempt fails")
                                               .promise()
                                       : Promise.success(factory.newPool());

            var retried = provider.provide(FakeConnector.class, DB, SharedPoolFixtures.slice("slice-a"))
                                  .orElse(() -> provider.provide(FakeConnector.class,
                                                                 DB,
                                                                 SharedPoolFixtures.slice("slice-a")))
                                  .await(TIMEOUT);

            assertThat(retried.isSuccess()).isTrue();
            assertThat(attempts.get()).isEqualTo(2);
        }

        /// T9, the ordering itself: the caller's own continuation observes the failed entry already evicted. The
        /// eviction is a dependent registered ahead of the holder's slot, so this holds on every round; as an
        /// event it would race the continuation.
        @Test
        void failedProvision_isAlreadyEvicted_whenTheCallersContinuationRuns() {
            for (var round = 0; round < 200; round++) {
                var factory = new FakeShareableFactory();
                var provider = providerOf(sameValues(), factory);
                var seen = new AtomicInteger(-1);

                factory.provisioner = _ -> Causes.cause("fails")
                                                 .promise();
                provider.provide(FakeConnector.class, DB, SharedPoolFixtures.slice("slice-a"))
                        .withFailure(_ -> seen.set(provider.sharedPoolCount()))
                        .await(TIMEOUT);

                assertThat(seen.get()).as("round %d: shared entries visible to the continuation", round)
                                      .isZero();
            }
        }
    }

    @Nested
    class HolderViews {
        @Test
        void viewStop_releasesOnlyThatHolder() { // T14
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);
            var a = acquire(provider, DB, "slice-a");
            var b = acquire(provider, DB, "slice-b");

            a.stop()
             .await(TIMEOUT);

            assertThat(factory.closed).isEmpty();
            assertThat(b.ping()
                        .await(TIMEOUT)
                        .isSuccess()).isTrue();
            assertThat(a.ping()
                        .await(TIMEOUT)
                        .isFailure()).isTrue();

            a.stop()
             .await(TIMEOUT);
            release(provider, "slice-a");

            assertThat(factory.closed).isEmpty();

            b.stop()
             .await(TIMEOUT);

            assertThat(factory.closed).containsExactly(a.underlying());
            assertThat(a.underlying()
                        .closeCount()).isEqualTo(1);
        }

        @Test
        void viewClose_releasesOnlyThatHolder() { // T14b
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);
            var a = acquire(provider, DB, "slice-a");
            var b = acquire(provider, DB, "slice-b");

            a.close()
             .await(TIMEOUT);

            assertThat(factory.closed).isEmpty();
            assertThat(b.ping()
                        .await(TIMEOUT)
                        .isSuccess()).isTrue();
            assertThat(a.ping()
                        .await(TIMEOUT)
                        .isFailure()).isTrue();

            a.close()
             .await(TIMEOUT);
            a.stop()
             .await(TIMEOUT);

            assertThat(factory.closed).isEmpty();

            b.close()
             .await(TIMEOUT);

            assertThat(factory.closed).containsExactly(a.underlying());
            assertThat(a.underlying()
                        .closeCount()).isEqualTo(1);
        }
    }

    @Nested
    class NodeStop {
        @Test
        void closeShared_closesHeldPoolsOnce_andRefusesLaterAcquires() { // T12
            var factory = new FakeShareableFactory();
            var provider = providerOf(Map.of(DB,
                                             FakeConfig.shared("pg://h/one"),
                                             OTHER_DB,
                                             FakeConfig.shared("pg://h/two")),
                                      factory);
            var one = acquire(provider, DB, "slice-a");
            var two = acquire(provider, OTHER_DB, "slice-b");

            provider.closeShared()
                    .await(TIMEOUT);

            assertThat(factory.closed).containsExactlyInAnyOrder(one.underlying(), two.underlying());
            assertThat(one.underlying()
                          .closeCount()).isEqualTo(1);
            assertThat(two.underlying()
                          .closeCount()).isEqualTo(1);

            var refused = tryAcquire(provider, DB, "slice-c");

            assertThat(refused.isFailure()).isTrue();
            refused.onFailure(cause -> assertThat(cause).isEqualTo(ResourceProvisioningError.ProviderClosed.INSTANCE));

            release(provider, "slice-a");
            provider.closeShared()
                    .await(TIMEOUT);

            assertThat(one.underlying()
                          .closeCount()).isEqualTo(1);
        }

        @Test
        void closeShared_racingTheLastHolderRelease_closesEachInstanceOnce() throws Exception { // T12b
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                for (var round = 0; round < 500; round++) {
                    var factory = new FakeShareableFactory();
                    var provider = providerOf(sameValues(), factory);
                    var a = acquire(provider, DB, "slice-a");
                    var gate = new java.util.concurrent.CyclicBarrier(2);
                    var released = executor.submit(() -> {
                        gate.await();
                        return provider.releaseAll("slice-a").await(TIMEOUT);
                    });
                    var closed = executor.submit(() -> {
                        gate.await();
                        return provider.closeShared().await(TIMEOUT);
                    });

                    released.get();
                    closed.get();

                    assertThat(a.underlying()
                                .closeCount()).as("round %d", round)
                                              .isEqualTo(1);
                }
            }
        }
    }

    @Nested
    class Races {
        private static final int ROUNDS = 3000;

        /// T8: B acquires while A's `releaseAll` removes the last holder. Whatever the order, the pool B holds is never
        /// closed while B holds it, no instance is closed twice, and after B's release every instance is closed once.
        @Test
        void acquireRacingTheLastHolderRelease_neverClosesAHeldPool() throws Exception {
            raceRounds((provider, a) -> provider.releaseAll("slice-a")
                                                .await(TIMEOUT));
        }

        /// T16: the same race with A's view `stop()` as the release.
        @Test
        void acquireRacingTheLastHolderViewStop_neverClosesAHeldPool() throws Exception {
            raceRounds((_, a) -> a.stop()
                                  .await(TIMEOUT));
        }

        private void raceRounds(java.util.function.BiFunction<SpiResourceProvider, FakeConnector, ?> releaseA)
        throws Exception {
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                for (var round = 0; round < ROUNDS; round++) {
                    var factory = new FakeShareableFactory();
                    var provider = providerOf(sameValues(), factory);
                    var a = acquire(provider, DB, "slice-a");
                    var barrier = new java.util.concurrent.CyclicBarrier(2);
                    var released = executor.submit(() -> {
                        barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        return releaseA.apply(provider, a);
                    });
                    var acquired = executor.submit(() -> {
                        barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        return acquire(provider, DB, "slice-b");
                    });

                    released.get(5, java.util.concurrent.TimeUnit.SECONDS);

                    var b = acquired.get(5, java.util.concurrent.TimeUnit.SECONDS);

                    assertThat(b.underlying()
                                .closeCount()).as("round %d: the pool B holds was closed", round)
                                              .isZero();
                    assertThat(b.ping()
                                .await(TIMEOUT)
                                .isSuccess()).as("round %d", round)
                                             .isTrue();

                    release(provider, "slice-b");

                    assertThat(factory.provisioned).as("round %d", round)
                                                   .allMatch(pool -> pool.closeCount() == 1);
                }
            }
        }
    }

    @Nested
    class Reentrance {
        @Test
        void provisionReenteringProvide_forTheSameKey_completes() { // T17
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);
            var nested = new AtomicReference<Promise<FakeConnector>>();

            factory.provisioner = _ -> {
                nested.compareAndSet(null,
                                     provider.provide(FakeConnector.class, DB, SharedPoolFixtures.slice("slice-b")));
                return Promise.success(factory.newPool());
            };

            var a = acquire(provider, DB, "slice-a");
            var b = nested.get()
                          .await(TIMEOUT);

            assertThat(b.isSuccess()).isTrue();
            assertThat(b.unwrap()
                        .underlying()).isSameAs(a.underlying());
        }

        @Test
        void viewReenteringProvide_withThePoolResolved_completes() { // T17b
            var factory = new FakeShareableFactory();
            var provider = providerOf(sameValues(), factory);
            var nested = new AtomicReference<Promise<FakeConnector>>();

            acquire(provider, DB, "slice-a");
            factory.viewHook = pool -> {
                nested.compareAndSet(null,
                                     provider.provide(FakeConnector.class, DB, SharedPoolFixtures.slice("slice-c")));
                return pool;
            };

            var b = tryAcquire(provider, DB, "slice-b");

            assertThat(b.isSuccess()).isTrue();
            assertThat(nested.get()
                             .await(TIMEOUT)
                             .isSuccess()).isTrue();
            assertThat(factory.provisioned).hasSize(1);
        }
    }

    @Nested
    class Tripwire {
        /// T-TRIP, ENABLED tripwire asserting CURRENT behaviour: a `releaseAll(A)` issued between `provide` and the
        /// asynchronous bind misses A, so A becomes a holder afterwards and pins the pool. When this fails, enable the
        /// tombstone test: releaseAll records a per-scope tombstone that a late acquire honours.
        @Test
        void releaseBetweenProvideAndBind_leavesTheSliceAHolder() throws Exception {
            var factory = new FakeShareableFactory();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var gate = new java.util.concurrent.CountDownLatch(1);
            var provider = SpiResourceProvider.spiResourceProvider(java.util.List.of(factory),
                                                                   (section, _) -> boundAfter(entered, gate));

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                var inFlight = executor.submit(() -> provider.provide(FakeConnector.class,
                                                                      DB,
                                                                      SharedPoolFixtures.slice("slice-a"))
                                                             .await(TIMEOUT));

                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                release(provider, "slice-a");
                gate.countDown();

                var held = inFlight.get()
                                   .unwrap();

                assertThat(factory.closed).as("enable the tombstone test when releaseAll records a per-scope tombstone"
                                              + " that a late acquire honours")
                                          .isEmpty();
                assertThat(held.ping()
                               .await(TIMEOUT)
                               .isSuccess()).isTrue();
            }
        }

        private static org.pragmatica.lang.Result<?> boundAfter(java.util.concurrent.CountDownLatch entered,
                                                                java.util.concurrent.CountDownLatch gate) {
            entered.countDown();

            try {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return org.pragmatica.lang.Result.success(FakeConfig.shared("pg://h/db"));
        }
    }
}
