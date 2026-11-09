// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

        assertThat(reported).as("the release's close is pending, so node stop must carry it").hasSize(1);
        assertThat(reported.getFirst().isResolved()).isFalse();
        deferred.succeed(Unit.unit());
        assertThat(reported.getFirst().await(TIMEOUT).isSuccess()).isTrue();
    }
}
