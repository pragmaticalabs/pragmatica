// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.resource.BackendRuntime;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// The one seam through which a cache or an idempotency store obtains its [CacheBackend] (#3658); neither
/// factory builds a backend itself.
///
/// One order decides every section: the backend is the one the section names, or, when it names none, the one
/// its mode implies (`memory` for `LOCAL`, `dht` for `DISTRIBUTED`/`TIERED`, today's behaviour). That name is
/// refused, in this order, when it is unknown ([CacheBackendError.UnknownBackend]), when it does not fit the mode
/// ([CacheBackendError.ModeConflict]: `LOCAL` needs a per-instance backend, the others a shared one) and when it
/// is not available on the runtime provisioned for ([CacheBackendError.BackendUnavailable]: `dht` on Terra).
/// `TIERED` puts a per-instance in-memory L1 in front of the selected shared backend.
///
/// A further backend is one more [Backend] entry here, with the runtimes it is available on; no slice changes.
final class CacheBackends {
    static final String MEMORY = "memory";
    static final String DHT = "dht";

    private static final Map<String, Backend> BACKENDS = Map.of(MEMORY,
                                                                Backend.backend(MEMORY,
                                                                                true,
                                                                                EnumSet.allOf(BackendRuntime.class),
                                                                                CacheBackends::memory),
                                                                DHT,
                                                                Backend.backend(DHT,
                                                                                false,
                                                                                EnumSet.of(BackendRuntime.AETHER),
                                                                                CacheBackends::dht));

    private CacheBackends() {}

    static Result<CacheBackend> cacheBackend(StoreSpec spec, ProvisioningContext context) {
        return selected(spec, BackendRuntime.backendRuntime(context)).flatMap(backend -> built(backend, spec, context));
    }

    private static Result<Backend> selected(StoreSpec spec, BackendRuntime runtime) {
        var name = spec.backend()
                       .or(() -> impliedBy(spec.mode()));

        return option(BACKENDS.get(name)).toResult(CacheBackendError.unknownBackend(name, spec.name()))
                     .filter(backend -> CacheBackendError.modeConflict(spec.name(), spec.mode(), backend.name()),
                             backend -> backend.fits(spec.mode()))
                     .filter(backend -> CacheBackendError.backendUnavailable(backend.name(), spec.name(), runtime),
                             backend -> backend.runtimes()
                                               .contains(runtime));
    }

    private static String impliedBy(CacheMode mode) {
        return mode == CacheMode.LOCAL
               ? MEMORY
               : DHT;
    }

    private static Result<CacheBackend> built(Backend backend, StoreSpec spec, ProvisioningContext context) {
        return switch (spec.mode()) {
            case LOCAL, DISTRIBUTED -> backend.build()
                                              .apply(spec, context);
            case TIERED -> backend.build()
                                  .apply(spec, context)
                                  .map(shared -> tiered(spec, shared));
        };
    }

    private static CacheBackend tiered(StoreSpec spec, CacheBackend shared) {
        return TieredCache.tieredCache(inMemory(spec), shared);
    }

    private static CacheBackend inMemory(StoreSpec spec) {
        return InMemoryCache.inMemoryCache(spec.ttlSeconds(), spec.maxEntries());
    }

    /// Cannot fail; returns `Result` only to share the [Backend#build] shape with backends that can.
    private static Result<CacheBackend> memory(StoreSpec spec, ProvisioningContext context) {
        return success(inMemory(spec));
    }

    private static Result<CacheBackend> dht(StoreSpec spec, ProvisioningContext context) {
        return Result.all(dhtClient(spec, context),
                          context.extension(Serializer.class),
                          context.extension(Deserializer.class),
                          success(spec.name()),
                          success(spec.ttlSeconds()))
                     .map(DHTCacheBackend::dhtCacheBackend);
    }

    /// A cache writes through the cache namespace's client ([CacheDhtClient]). An idempotency store writes through
    /// the REPLICATED DHT at the committed `[replication]` factors, never the cache namespace's (#1777 Q4): a dedup
    /// record lost with its only copy re-executes the call it guarded.
    private static Result<DHTClient> dhtClient(StoreSpec spec, ProvisioningContext context) {
        return switch (spec.kind()) {
            case CACHE -> context.extension(CacheDhtClient.class)
                                 .map(CacheDhtClient::client);
            case IDEMPOTENCY -> context.extension(DHTClient.class);
        };
    }

    /// A selectable backend: its name, whether it is per instance (only such a backend fits `LOCAL`, and only a
    /// shared one fits `DISTRIBUTED`/`TIERED`), the runtimes it is available on, and how to build it for a store.
    record Backend(String name,
                   boolean perInstance,
                   Set<BackendRuntime> runtimes,
                   Fn2<Result<CacheBackend>, StoreSpec, ProvisioningContext> build) {
        static Backend backend(String name,
                               boolean perInstance,
                               Set<BackendRuntime> runtimes,
                               Fn2<Result<CacheBackend>, StoreSpec, ProvisioningContext> build) {
            return new Backend(name, perInstance, Set.copyOf(runtimes), build);
        }

        boolean fits(CacheMode mode) {
            return perInstance == (mode == CacheMode.LOCAL);
        }
    }
}
