// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConfig;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConnector;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.aether.resource.SharedPoolFixtures.InheritingContextFactory;
import org.pragmatica.aether.resource.SharedPoolFixtures.SectionBinderFactory;
import org.pragmatica.aether.resource.SpiResourceProvider.Provisioned;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SharedPoolFixtures.TIMEOUT;
import static org.pragmatica.aether.resource.SharedPoolFixtures.acquire;
import static org.pragmatica.aether.resource.SharedPoolFixtures.providerOf;
import static org.pragmatica.aether.resource.SharedPoolFixtures.release;
import static org.pragmatica.aether.resource.SharedPoolFixtures.tryAcquire;

/// Containment and release-binding guarantees of the node-shared pools (#2978 fix round 1): a factory that throws or
/// fails never wedges its key, `closeAll` never strands a pool that races it, a view's release is bound to the
/// holding it was issued for, and the release/close futures wait for a close that has not finished.
class SharedPoolContainmentTest {
    private static final RuntimeException BOOM = new IllegalStateException("factory threw synchronously");

    
    /// Shared path: a NodeShareable whose provision THROWS (instead of returning a failed promise) must not wedge
    /// the share key. After the factory recovers, another slice's acquire must provision afresh.
    @Test
    void throwingProvision_sharedPath_doesNotWedgeTheKey() {
        var factory = new FakeShareableFactory();
        var provider = providerOf(Map.of("db", FakeConfig.shared("e1")), factory);

        factory.provisioner = _ -> {
            throw BOOM;
        };

        var first = tryAcquire(provider, "db", "slice-a");

        assertThat(first.isFailure()).as("first acquire fails: %s", first).isTrue();

        factory.provisioner = _ -> Promise.success(factory.newPool());

        var second = tryAcquire(provider, "db", "slice-b");

        assertThat(second.isSuccess()).as("after recovery a new acquire must succeed, got %s", second).isTrue();
    }

    /// Per-slice path (pool_scope = "slice"): the same, for the slice's own retry.
    @Test
    void throwingProvision_perSlicePath_doesNotWedgeTheSlicesRetry() {
        var factory = new FakeShareableFactory();
        var provider = providerOf(Map.of("db", FakeConfig.perSlice("e1")), factory);

        factory.provisioner = _ -> {
            throw BOOM;
        };

        var first = tryAcquire(provider, "db", "slice-a");

        assertThat(first.isFailure()).as("first acquire fails: %s", first).isTrue();

        factory.provisioner = _ -> Promise.success(factory.newPool());

        var retry = tryAcquire(provider, "db", "slice-a");

        assertThat(retry.isSuccess()).as("the slice's retry must succeed, got %s", retry).isTrue();
    }

    /// Consequence: releaseAll of the slice whose acquire hit the throw must complete.
    @Test
    void throwingProvision_releaseAllOfThatSlice_completes() {
        var factory = new FakeShareableFactory();
        var provider = providerOf(Map.of("db", FakeConfig.shared("e1")), factory);

        factory.provisioner = _ -> {
            throw BOOM;
        };
        tryAcquire(provider, "db", "slice-a");

        var released = provider.releaseAll("slice-a").await(TIMEOUT);

        assertThat(released.isSuccess()).as("releaseAll must complete, got %s", released).isTrue();
    }

    /// Same-scope: a view released AFTER its slice released and re-acquired the same key must not release the new holding.
    @Test
    void staleViewStop_afterSameScopeReacquire_doesNotCloseTheNewHoldersPool() {
        var factory = new FakeShareableFactory();
        var provider = providerOf(Map.of("db", FakeConfig.shared("e1")), factory);
        var stale = acquire(provider, "db", "slice-a");

        acquire(provider, "db", "slice-b");
        release(provider, "slice-a");

        var fresh = acquire(provider, "db", "slice-a");

        release(provider, "slice-b");
        stale.stop().await(TIMEOUT);

        assertThat(fresh.underlying()).isSameAs(stale.underlying());
        assertThat(fresh.underlying().closeCount()).as("the pool slice-a's CURRENT view uses was closed by its old view")
                                                   .isZero();
        assertThat(fresh.ping().await(TIMEOUT).isSuccess()).isTrue();
    }

    /// Race: closeAll racing an acquire whose `admit` read `closed == false`. After both return, no provisioned pool may
    /// stay open and in the map. Spin barrier, bounded by a deadline.
    @Test
    void closeAll_racingAcquire_neverLeavesAnOpenPool() throws Exception {
        var factory = new FakeShareableFactory();
        var rounds = 300_000;
        var leaks = new AtomicInteger();
        var refusedOrClosed = new AtomicInteger();
        var deadline = System.nanoTime() + 90_000_000_000L;
        var go = new AtomicLong();
        var done = new AtomicLong();
        var holder = new SharedPools[1];
        var closes = new AtomicInteger[1];
        var provisions = new AtomicInteger[1];
        var stop = new AtomicBoolean();

        Runnable acquirer = () -> {
            for (long r = 1; !stop.get(); r++) {
                while (go.get() < r) {
                    if (stop.get()) {
                        return;
                    }
                    Thread.onSpinWait();
                }
                var pools = holder[0];
                var prov = provisions[0];

                pools.acquire("k",
                              "slice-a",
                              factory,
                              () -> {
                                  prov.incrementAndGet();
                                  return Promise.<Provisioned<?>> success(Provisioned.provisioned((FakeConnector) factory.newPool(),
                                                                                 factory));
                              },
                              "label");
                done.incrementAndGet();
            }
        };
        var thread = Thread.ofPlatform().start(acquirer);
        int executed = 0;

        try {
            for (long r = 1; r <= rounds && System.nanoTime() < deadline; r++) {
                var closeCount = new AtomicInteger();

                closes[0] = closeCount;
                provisions[0] = new AtomicInteger();
                holder[0] = SharedPools.sharedPools(p -> {
                    closeCount.incrementAndGet();
                    return Promise.unitPromise();
                });
                go.set(r);
                holder[0].closeAll();
                while (done.get() < r) {
                    Thread.onSpinWait();
                }
                executed++;
                if (provisions[0].get() > closes[0].get() && holder[0].size() > 0) {
                    leaks.incrementAndGet();
                } else {
                    refusedOrClosed.incrementAndGet();
                }
            }
        } finally {
            stop.set(true);
            thread.join(5_000);
        }

        System.out.println("closeAll race: rounds=" + executed + " leaks=" + leaks.get()
                           + " clean=" + refusedOrClosed.get());
        assertThat(leaks.get()).as("pools provisioned after closeAll returned, never closed, over %d rounds", executed)
                               .isZero();
    }

    /// Positive control for Race's instrument: an acquire that completes BEFORE closeAll is drained and closed, and
    /// one that starts AFTER is refused. If either arm misreports, the leak count above means nothing.
    @Test
    void closeAll_control_sequentialArms() {
        var factory = new FakeShareableFactory();
        var closes = new AtomicInteger();
        var pools = SharedPools.sharedPools(p -> {
            closes.incrementAndGet();
            return Promise.unitPromise();
        });

        pools.acquire("k", "s", factory, () -> Promise.<Provisioned<?>> success(Provisioned.provisioned((FakeConnector) factory.newPool(),
                                                                                       factory)), "l");
        pools.closeAll();
        assertThat(closes.get()).isEqualTo(1);
        assertThat(pools.size()).isZero();

        var late = pools.acquire("k2", "s", factory, () -> Promise.<Provisioned<?>> success(Provisioned.provisioned((FakeConnector) factory.newPool(),
                                                                                                   factory)), "l");

        assertThat(late.await(TIMEOUT).isFailure()).isTrue();
        assertThat(pools.size()).isZero();
    }


    /// A per-slice pool whose provision FAILS (as a failed promise) is evicted, so the slice's retry provisions afresh.
    @Test
    void acquire_failedSlicePoolProvision_isEvictedAndRetried() {
        var factory = new FakeShareableFactory();
        var provider = providerOf(Map.of("db", FakeConfig.perSlice("e1")), factory);

        factory.provisioner = _ -> Causes.cause("refused").promise();

        assertThat(tryAcquire(provider, "db", "slice-a").isFailure()).isTrue();

        factory.provisioner = _ -> Promise.success(factory.newPool());

        assertThat(tryAcquire(provider, "db", "slice-a").isSuccess()).isTrue();
    }

    /// A release by a scope that no longer holds the key changes nothing: slice-a's OLD view (never stopped, because
    /// `releaseAll` does not mark views) must not close the pool slice-b now holds alone.
    @Test
    void viewStop_afterScopeReleasedAll_doesNotCloseTheRemainingHoldersPool() {
        var factory = new FakeShareableFactory();
        var provider = providerOf(Map.of("db", FakeConfig.shared("e1")), factory);
        var old = acquire(provider, "db", "slice-a");
        var other = acquire(provider, "db", "slice-b");

        release(provider, "slice-a");
        old.stop().await(TIMEOUT);

        assertThat(other.underlying().closeCount()).isZero();
        assertThat(other.ping().await(TIMEOUT).isSuccess()).isTrue();
    }

    /// `releaseAll` completes only after the shared pool's close has: the fake's close stays unresolved until released.
    @Test
    void releaseAll_lastHolder_waitsForTheDeferredClose() {
        var factory = new FakeShareableFactory();
        var provider = providerOf(Map.of("db", FakeConfig.shared("e1")), factory);
        var deferred = Promise.<Unit> promise();

        acquire(provider, "db", "slice-a");
        factory.deferredClose = Option.some(deferred);

        var released = provider.releaseAll("slice-a");

        assertThat(factory.closed).hasSize(1);
        assertThat(released.isResolved()).as("releaseAll resolved before the pool's close finished").isFalse();

        deferred.succeed(Unit.unit());

        assertThat(released.await(TIMEOUT).isSuccess()).isTrue();
    }

    /// `closeShared` (node stop) waits for the deferred close of every shared pool the same way.
    @Test
    void closeShared_waitsForTheDeferredClose() {
        var factory = new FakeShareableFactory();
        var provider = providerOf(Map.of("db", FakeConfig.shared("e1")), factory);
        var deferred = Promise.<Unit> promise();

        acquire(provider, "db", "slice-a");
        factory.deferredClose = Option.some(deferred);

        var closing = provider.closeShared();

        assertThat(factory.closed).hasSize(1);
        assertThat(closing.isResolved()).as("closeShared resolved before the pool's close finished").isFalse();

        deferred.succeed(Unit.unit());

        assertThat(closing.await(TIMEOUT).isSuccess()).isTrue();
    }

    /// D3.3: a marker declaring `sectionBinder()` is demoted, so two slices get two pools.
    @Test
    void register_markerDeclaringSectionBinder_isDemoted() {
        var factory = new SectionBinderFactory();
        var provider = providerOf(Map.of("db", FakeConfig.shared("e1")), factory);

        acquire(provider, "db", "slice-a");
        acquire(provider, "db", "slice-b");

        assertThat(factory.provisioned).hasSize(2);
        assertThat(provider.demotedFactories()).containsExactly(SectionBinderFactory.class);
    }

    /// D3.3: the declaration is found on a superclass too (the marker itself declares nothing).
    @Test
    void register_markerInheritingContextProvision_isDemoted() {
        var factory = new InheritingContextFactory();
        var provider = providerOf(Map.of("db", FakeConfig.shared("e1")), factory);

        acquire(provider, "db", "slice-a");
        acquire(provider, "db", "slice-b");

        assertThat(factory.provisioned).hasSize(2);
        assertThat(provider.demotedFactories()).containsExactly(InheritingContextFactory.class);
    }
}
