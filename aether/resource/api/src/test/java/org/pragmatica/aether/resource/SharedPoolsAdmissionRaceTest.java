// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConnector;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.aether.resource.SpiResourceProvider.Provisioned;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SharedPoolFixtures.TIMEOUT;

/// An acquire that sees the node-stop flag AFTER its own admission fails with `ProviderClosed`, never a view of a pool
/// that is closed at once. The interleaving is forced through a map seam: a hook runs right after the acquirer's first
/// `compute` returned, before its closed re-check.
class SharedPoolsAdmissionRaceTest {
    private static final class SeamMap extends ConcurrentHashMap<Object, Object> {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile int fireAt = 1;
        private volatile Runnable hook = () -> {};

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object compute(Object key, BiFunction f) {
            var result = super.compute(key, f);

            if (calls.incrementAndGet() == fireAt) {
                hook.run();
            }

            return result;
        }
    }

    private final FakeShareableFactory factory = new FakeShareableFactory();
    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicInteger provisions = new AtomicInteger();
    private final SharedPools pools = SharedPools.sharedPools(this::closePool);
    private final SeamMap seam = new SeamMap();

    private Promise<Unit> closePool(Provisioned<?> provisioned) {
        closes.incrementAndGet();
        ((FakeConnector) provisioned.resource()).underlying().close();

        return Promise.unitPromise();
    }

    private Promise<Provisioned<?>> provision() {
        provisions.incrementAndGet();

        return Promise.<Provisioned<?>> success(Provisioned.provisioned((FakeConnector) factory.newPool(), factory));
    }

    private Promise<FakeConnector> acquire(String scope) {
        return pools.acquire("k", scope, factory, this::provision, "label");
    }

    private void installSeam(Runnable hook) throws Exception {
        var entries = SharedPools.class.getDeclaredField("entries");

        entries.setAccessible(true);
        entries.set(pools, seam);
        seam.hook = hook;
    }

    private void raiseFlag() {
        try {
            Field closed = SharedPools.class.getDeclaredField("closed");

            closed.setAccessible(true);
            ((AtomicBoolean) closed.get(pools)).set(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertRefusedAndNothingLeaked(Promise<FakeConnector> acquired) {
        var result = acquired.await(TIMEOUT);

        assertThat(result.isFailure()).as("the caller must fail, got %s", result).isTrue();
        result.onFailure(cause -> assertThat(cause).isSameAs(ResourceProvisioningError.ProviderClosed.INSTANCE));
        assertThat(provisions.get()).as("a closed node provisions nothing").isZero();
        assertThat(closes.get()).as("nothing was opened, so nothing is closed").isZero();
        assertThat(pools.size()).isZero();
    }

    @Test
    void flagSetAfterAdmission_acquirerFailsWithProviderClosed() throws Exception {
        installSeam(this::raiseFlag);

        assertRefusedAndNothingLeaked(acquire("slice-a"));
    }

    @Test
    void closeAllAfterAdmission_acquirerFailsWithProviderClosed() throws Exception {
        installSeam(pools::closeAll);

        assertRefusedAndNothingLeaked(acquire("slice-a"));
    }

    /// The joiner drains the entry its creator opened and is then the only closer: `closeAll` finds nothing left, so the
    /// joiner's own close of the departed pool is the one that releases it, exactly once.
    @Test
    void joinerThatDrainsTheCreatorsEntry_closesThePoolExactlyOnce() throws Exception {
        seam.fireAt = 2;
        installSeam(this::raiseFlag);

        var created = acquire("slice-a").await(TIMEOUT);
        var joined = acquire("slice-b").await(TIMEOUT);

        pools.closeAll();
        assertThat(created.isSuccess()).as("the creator was admitted before the flag, got %s", created).isTrue();
        assertThat(joined.isFailure()).as("the joiner must fail, got %s", joined).isTrue();
        assertThat(closes.get()).as("the pool the creator opened must be closed exactly once").isEqualTo(1);
    }

    @Test
    void joinerOfAnEntryDrainedAfterAdmission_alsoFailsWithProviderClosed() throws Exception {
        var joiner = new AtomicReference<Promise<FakeConnector>>();

        installSeam(() -> {
            joiner.set(acquire("slice-b"));
            raiseFlag();
        });

        assertRefusedAndNothingLeaked(acquire("slice-a"));

        var joined = joiner.get().await(TIMEOUT);

        assertThat(joined.isFailure()).as("the joiner must fail, got %s", joined).isTrue();
        joined.onFailure(cause -> assertThat(cause).isSameAs(ResourceProvisioningError.ProviderClosed.INSTANCE));
    }
}
