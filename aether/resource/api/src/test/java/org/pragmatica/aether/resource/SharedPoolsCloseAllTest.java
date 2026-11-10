// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConnector;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.aether.resource.SpiResourceProvider.Provisioned;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SharedPoolFixtures.TIMEOUT;

/// Node stop reports done only when every pool is closed: the drain follows key presence rather than the map's count,
/// and it also carries the closes a release started before the drain.
class SharedPoolsCloseAllTest {
    /// `isEmpty()` lies once, as the map's striped count can while another key's insert and remove race: it says empty
    /// although a key is present.
    private static final class LyingMap extends ConcurrentHashMap<Object, Object> {
        private final AtomicBoolean lied = new AtomicBoolean();

        @Override
        public boolean isEmpty() {
            return lied.compareAndSet(false, true) || super.isEmpty();
        }
    }

    /// Parks the thread named in `parked` right after its `compute` returned: the entry is gone from the map and the thread
    /// has not yet reached its close.
    private static final class ParkingMap extends ConcurrentHashMap<Object, Object> {
        private volatile Thread parked;
        private final CountDownLatch removed = new CountDownLatch(1);
        private final CountDownLatch go = new CountDownLatch(1);

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object compute(Object key, BiFunction f) {
            var result = super.compute(key, f);

            if (Thread.currentThread() == parked) {
                removed.countDown();
                await(go);
            }

            return result;
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final FakeShareableFactory factory = new FakeShareableFactory();
    private final AtomicInteger closes = new AtomicInteger();
    private final Promise<Unit> deferred = Promise.promise();

    private Promise<Unit> closePool(Provisioned<?> provisioned) {
        closes.incrementAndGet();
        ((FakeConnector) provisioned.resource()).underlying().close();

        return Promise.unitPromise();
    }

    private Promise<Unit> deferClose(Provisioned<?> provisioned) {
        closes.incrementAndGet();

        return deferred;
    }

    private Promise<Provisioned<?>> provision() {
        return Promise.<Provisioned<?>> success(Provisioned.provisioned((FakeConnector) factory.newPool(), factory));
    }

    private Promise<FakeConnector> acquire(SharedPools pools, String scope) {
        return pools.acquire("k", scope, factory, this::provision, "label");
    }

    @Test
    void closeAll_whenIsEmptyLiesOnce_stillClosesTheHeldPool() throws Exception {
        var pools = SharedPools.sharedPools(this::closePool);
        var entries = SharedPools.class.getDeclaredField("entries");

        acquire(pools, "slice-a").await(TIMEOUT);
        entries.setAccessible(true);
        var held = new LyingMap();

        held.putAll((ConcurrentHashMap<?, ?>) entries.get(pools));
        entries.set(pools, held);
        pools.closeAll();

        assertThat(closes.get()).as("the held pool must be closed although isEmpty() lied").isEqualTo(1);
        assertThat(pools.size()).isZero();
    }

    @Test
    void releaseScope_whenIsEmptyLiesOnce_stillReleasesTheScopesPool() throws Exception {
        var pools = SharedPools.sharedPools(this::closePool);
        var entries = SharedPools.class.getDeclaredField("entries");

        acquire(pools, "slice-a").await(TIMEOUT);
        entries.setAccessible(true);
        var held = new LyingMap();

        held.putAll((ConcurrentHashMap<?, ?>) entries.get(pools));
        entries.set(pools, held);
        pools.releaseScope("slice-a");

        assertThat(closes.get()).as("the scope's pool must be released although isEmpty() lied").isEqualTo(1);
        assertThat(pools.size()).isZero();
    }

    @Test
    void closeAll_includesAReleaseCloseStillPending() {
        var pools = SharedPools.sharedPools(this::deferClose);

        acquire(pools, "slice-a").await(TIMEOUT);
        pools.release("k", "slice-a");

        var reported = pools.closeAll();

        assertThat(reported).as("the release's close is pending, so node stop must carry it").isNotEmpty();
        assertThat(reported).allSatisfy(pending -> assertThat(pending.isResolved()).isFalse());
        deferred.succeed(Unit.unit());
        assertThat(reported).allSatisfy(pending -> assertThat(pending.await(TIMEOUT).isSuccess()).isTrue());
    }

    /// A release has removed the last holder's entry but not yet started the close: `closeAll` running in that window
    /// must still report the release, because node stop is not done until that pool is closed.
    @Test
    void closeAll_whenReleaseRemovedTheEntryButNotYetClosed_reportsTheClose() throws Exception {
        var pools = SharedPools.sharedPools(this::closePool);
        var entries = SharedPools.class.getDeclaredField("entries");

        acquire(pools, "slice-a").await(TIMEOUT);
        entries.setAccessible(true);
        var parking = new ParkingMap();

        parking.putAll((ConcurrentHashMap<?, ?>) entries.get(pools));
        entries.set(pools, parking);
        var releaser = new Thread(() -> pools.release("k", "slice-a"));

        parking.parked = releaser;
        releaser.start();
        assertThat(parking.removed.await(10, TimeUnit.SECONDS)).isTrue();
        var reported = pools.closeAll();

        parking.go.countDown();
        releaser.join(10_000);

        assertThat(reported).as("closeAll returned with the release's pool neither reported nor started").isNotEmpty();
        assertThat(closes.get()).as("the pool is closed once, by the release").isEqualTo(1);
    }
}
