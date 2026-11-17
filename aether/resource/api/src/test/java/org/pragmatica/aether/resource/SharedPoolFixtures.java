// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.pragmatica.aether.resource.NodeShareable.PoolDemand;
import org.pragmatica.aether.resource.NodeShareable.ServerId;
import org.pragmatica.aether.resource.NodeShareable.ServerProbe;
import org.pragmatica.aether.resource.db.PoolScope;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Fakes for the #2973 shared-pool tests. Every close is recorded BY IDENTITY of the underlying pool, and a pool knows
/// which holders currently use it, so a close while a holder still holds it is observable at the moment it happens.
final class SharedPoolFixtures {
    static final TimeSpan TIMEOUT = timeSpan(5).seconds();

    private SharedPoolFixtures() {}

    /// What a slice receives: the pool itself (per-slice) or a view (shared).
    interface FakeConnector extends AsyncCloseable {
        FakePool underlying();
        Promise<String> ping();
        Promise<Unit> stop();
    }

    /// A pool's configuration. `endpoint` stands for every connection input; `scope` for `pool_scope`.
    record FakeConfig(String endpoint, int warm, int max, Option<PoolScope> scope, String host, int port) {
        static FakeConfig shared(String endpoint) {
            return new FakeConfig(endpoint, 1, 4, Option.none(), "localhost", 5432);
        }

        static FakeConfig perSlice(String endpoint) {
            return new FakeConfig(endpoint, 1, 4, Option.some(PoolScope.SLICE), "localhost", 5432);
        }

        FakeConfig withDemand(int warmValue, int maxValue) {
            return new FakeConfig(endpoint, warmValue, maxValue, scope, host, port);
        }

        FakeConfig withServer(String hostValue, int portValue) {
            return new FakeConfig(endpoint, warm, max, scope, hostValue, portValue);
        }
    }

    record FakeKey(Class<?> factory, String endpoint, int max) {}

    /// The underlying pool. `close()` is the real close; it records itself and whether a holder was still using it.
    static final class FakePool implements FakeConnector {
        private final FakeShareableFactory factory;
        private final AtomicInteger closes = new AtomicInteger();
        private final Map<String, Boolean> heldBy = new ConcurrentHashMap<>();

        FakePool(FakeShareableFactory factory) {
            this.factory = factory;
        }

        @Override
        public FakePool underlying() {
            return this;
        }

        @Override
        public Promise<String> ping() {
            return closes.get() == 0
                   ? Promise.success("pong")
                   : Causes.cause("pool closed").promise();
        }

        @Override
        public Promise<Unit> stop() {
            return close();
        }

        @Override
        public Promise<Unit> close() {
            if (heldBy.values()
                      .stream()
                      .anyMatch(Boolean::booleanValue)) {
                factory.closedWhileHeld.incrementAndGet();
            }

            closes.incrementAndGet();
            factory.closed.add(this);

            return factory.deferredClose.or(Promise.unitPromise());
        }

        int closeCount() {
            return closes.get();
        }

        void hold(String holder, boolean held) {
            heldBy.put(holder, held);
        }
    }

    /// A holder's view: releases through `release` once, then refuses.
    static final class FakeView implements FakeConnector {
        private final FakePool pool;
        private final Fn0<Promise<Unit>> release;
        private final AtomicBoolean released = new AtomicBoolean();
        /// Whether the pool was already closed when this view was made: a view handed out with this set is a dead one.
        final boolean poolClosedAtIssue;

        FakeView(FakePool pool, Fn0<Promise<Unit>> release) {
            this.pool = pool;
            this.release = release;
            this.poolClosedAtIssue = pool.closeCount() > 0;
        }

        @Override
        public FakePool underlying() {
            return pool;
        }

        @Override
        public Promise<String> ping() {
            return released.get()
                   ? Causes.cause("handle released").promise()
                   : pool.ping();
        }

        @Override
        public Promise<Unit> stop() {
            return released.compareAndSet(false, true)
                   ? release.apply()
                   : Promise.unitPromise();
        }

        @Override
        public Promise<Unit> close() {
            return stop();
        }
    }

    /// A counting [NodeShareable] factory. `probe` answers through `prober`; provision through `provisioner` (default: a
    /// fresh pool, synchronously).
    static class FakeShareableFactory implements ResourceFactory<FakeConnector, FakeConfig>,
                                                  NodeShareable<FakeConnector, FakeConfig> {
        final List<FakePool> provisioned = new CopyOnWriteArrayList<>();
        final List<FakePool> closed = new CopyOnWriteArrayList<>();
        final List<FakeView> views = new CopyOnWriteArrayList<>();
        final AtomicInteger closedWhileHeld = new AtomicInteger();
        volatile Function<FakePool, Option<Promise<ServerProbe>>> prober = _ -> Option.none();
        volatile Function<FakeConfig, Promise<FakeConnector>> provisioner = _ -> Promise.success(newPool());
        /// When present, every pool close returns this promise (unresolved = a close that has not finished).
        volatile Option<Promise<Unit>> deferredClose = Option.none();
        volatile Function<FakePool, FakeConnector> viewHook = pool -> pool;

        FakePool newPool() {
            var pool = new FakePool(this);

            provisioned.add(pool);

            return pool;
        }

        @Override
        public Class<FakeConnector> resourceType() {
            return FakeConnector.class;
        }

        @Override
        public Class<FakeConfig> configType() {
            return FakeConfig.class;
        }

        @Override
        public Promise<FakeConnector> provision(FakeConfig config) {
            return provisioner.apply(config);
        }

        @Override
        public Option<Object> shareKey(FakeConfig config) {
            return config.scope()
                         .filter(PoolScope.SLICE::equals)
                         .fold(() -> Option.some(new FakeKey(getClass(), config.endpoint(), config.max())),
                               _ -> Option.none());
        }

        @Override
        public FakeConnector view(FakeConnector pool, Fn0<Promise<Unit>> release) {
            viewHook.apply(pool.underlying());

            var view = new FakeView(pool.underlying(), release);

            views.add(view);

            return view;
        }

        @Override
        public PoolDemand demand(FakeConfig config) {
            return PoolDemand.poolDemand(config.warm(),
                                         config.max(),
                                         ServerId.serverId("POSTGRESQL", config.host(), config.port()),
                                         TIMEOUT,
                                         config.host() + ":" + config.port() + "/" + config.endpoint() + " (u)");
        }

        @Override
        public Option<Promise<ServerProbe>> probe(FakeConnector pool) {
            return prober.apply(pool.underlying());
        }
    }

    /// The same factory without the marker: the per-slice path must be untouched for it.
    static final class FakePlainFactory implements ResourceFactory<FakeConnector, FakeConfig> {
        final FakeShareableFactory inner = new FakeShareableFactory();

        @Override
        public Class<FakeConnector> resourceType() {
            return FakeConnector.class;
        }

        @Override
        public Class<FakeConfig> configType() {
            return FakeConfig.class;
        }

        @Override
        public Promise<FakeConnector> provision(FakeConfig config) {
            return Promise.success(inner.newPool());
        }
    }

    /// A marker that declares the forbidden context overload: it must be demoted (D3.3).
    static final class ContextReadingFactory extends FakeShareableFactory {
        @Override
        public Promise<FakeConnector> provision(FakeConfig config, ProvisioningContext context) {
            return provision(config);
        }
    }

    /// A marker that declares `sectionBinder()` (D3.3: demoted).
    static final class SectionBinderFactory extends FakeShareableFactory {
        @Override
        public Option<SectionBinder<FakeConfig>> sectionBinder() {
            return Option.none();
        }
    }

    /// A marker whose SUPERCLASS declares the context overload and which declares nothing itself (D3.3: demoted).
    static final class InheritingContextFactory extends ContextReadingBase {}

    static class ContextReadingBase extends FakeShareableFactory {
        @Override
        public Promise<FakeConnector> provision(FakeConfig config, ProvisioningContext context) {
            return provision(config);
        }
    }

    /// A provider whose sections resolve through `configs`; an unknown section fails.
    static SpiResourceProvider providerOf(Map<String, FakeConfig> configs, ResourceFactory<?, ?>... factories) {
        return SpiResourceProvider.spiResourceProvider(List.of(factories),
                                                       (section, _) -> Option.option(configs.get(section))
                                                                             .toResult(Causes.cause("no section "
                                                                                                                              + section)));
    }

    static ProvisioningContext slice(String sliceId) {
        return ProvisioningContext.provisioningContext()
                                  .withExtension(String.class, sliceId);
    }

    static FakeConnector acquire(SpiResourceProvider provider, String section, String sliceId) {
        return provider.provide(FakeConnector.class, section, slice(sliceId))
                       .await(TIMEOUT)
                       .unwrap();
    }

    static Result<FakeConnector> tryAcquire(SpiResourceProvider provider, String section, String sliceId) {
        return provider.provide(FakeConnector.class, section, slice(sliceId))
                       .await(TIMEOUT);
    }

    static Unit release(SpiResourceProvider provider, String sliceId) {
        provider.releaseAll(sliceId)
                .await(TIMEOUT);

        return Unit.unit();
    }
}
