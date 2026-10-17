// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


public final class CacheInterceptorFactory implements ResourceFactory<CacheMethodInterceptor, CacheConfig> {
    private final SharedByName<SharedCache> caches = SharedByName.sharedByName();

    @Override
    public Class<CacheMethodInterceptor> resourceType() {
        return CacheMethodInterceptor.class;
    }

    @Override
    public Class<CacheConfig> configType() {
        return CacheConfig.class;
    }

    @Override
    public Promise<CacheMethodInterceptor> provision(CacheConfig config) {
        return provision(config, ProvisioningContext.provisioningContext());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Promise<CacheMethodInterceptor> provision(CacheConfig config, ProvisioningContext context) {
        var keyExtractor = (Fn1<Object, ?>) context.keyExtractor().or(Fn1.id());

        return createCache(config, context).flatMap(cache -> share(cache, config, context, keyExtractor))
                          .async();
    }

    /// Releases this interceptor's hold on the backend shared under its cache name; the name is
    /// pruned from the registry when its last holder is released (#894). The backend itself owns
    /// nothing to close, so release is all unloading does.
    @Override
    public Promise<Unit> close(CacheMethodInterceptor resource) {
        return Promise.success(caches.release(resource));
    }

    /// Whether a backend is currently registered under `cacheName`.
    boolean retains(String cacheName) {
        return caches.contains(cacheName);
    }

    /// Sections sharing a `cache_name` share ONE backend, built from whichever section provisions first, so
    /// every later section must describe the same backend: a differing mode, TTL, capacity or key/value
    /// type is refused here rather than silently adopting the first section's (#697). The strategy is NOT
    /// compared: it shapes the interceptor, not the backend, and sections legitimately differ on it (a
    /// `CACHE_ASIDE` reader and `WRITE_AROUND` invalidators over one namespace).
    private Result<CacheMethodInterceptor> share(CacheBackend candidate,
                                                 CacheConfig config,
                                                 ProvisioningContext context,
                                                 Fn1<Object, ?> keyExtractor) {
        var shape = CacheShape.cacheShape(config, context);

        return caches.acquireChecked(config.cacheName(),
                                     SharedCache.sharedCache(candidate, shape),
                                     (existing, incoming) -> existing.shape()
                                                                     .conflictWith(incoming.shape(),
                                                                                   config.cacheName()),
                                     (existing, incoming) -> existing.merged(incoming),
                                     shared -> new CacheMethodInterceptor(shared.backend(),
                                                                          config.strategy(),
                                                                          keyExtractor,
                                                                          some(config.cacheName())));
    }

    private Result<? extends CacheBackend> createCache(CacheConfig config, ProvisioningContext context) {
        return switch (config.mode()) {
            case LOCAL -> success(createInMemory(config));
            case DISTRIBUTED -> createDHTBackend(config, context);
            case TIERED -> createDHTBackend(config, context).map(dhtCache -> createTiered(config, dhtCache));
        };
    }

    private static TieredCache createTiered(CacheConfig config, CacheBackend dhtCache) {
        return TieredCache.tieredCache(createInMemory(config), dhtCache);
    }

    private static InMemoryCache createInMemory(CacheConfig config) {
        return InMemoryCache.inMemoryCache(config.ttlSeconds(), config.maxEntries());
    }

    private Result<CacheBackend> createDHTBackend(CacheConfig config, ProvisioningContext context) {
        return Result.all(context.extension(CacheDhtClient.class).map(CacheDhtClient::client),
                          context.extension(Serializer.class),
                          context.extension(Deserializer.class),
                          success(config.cacheName()))
                     .map(DHTCacheBackend::dhtCacheBackend);
    }
}
