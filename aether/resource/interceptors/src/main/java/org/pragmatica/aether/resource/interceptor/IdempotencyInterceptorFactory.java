// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Result.success;


public final class IdempotencyInterceptorFactory implements ResourceFactory<IdempotencyMethodInterceptor, IdempotencyConfig> {
    private final Map<String, CacheBackend> storeRegistry = new ConcurrentHashMap<>();

    private final Map<String, ConcurrentHashMap<Object, Promise<Object>>> claimRegistry = new ConcurrentHashMap<>();

    @Override
    public Class<IdempotencyMethodInterceptor> resourceType() {
        return IdempotencyMethodInterceptor.class;
    }

    @Override
    public Class<IdempotencyConfig> configType() {
        return IdempotencyConfig.class;
    }

    @Override
    public Promise<IdempotencyMethodInterceptor> provision(IdempotencyConfig config) {
        return provision(config, ProvisioningContext.provisioningContext());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Promise<IdempotencyMethodInterceptor> provision(IdempotencyConfig config, ProvisioningContext context) {
        var keyExtractor = (Fn1<Object, ?>) context.keyExtractor().or(Fn1.id());
        var claims = claimRegistry.computeIfAbsent(config.storeName(), _ -> new ConcurrentHashMap<>());

        return createStore(config, context).map(store -> storeRegistry.computeIfAbsent(config.storeName(),
                                                                                       _ -> store))
                          .map(store -> new IdempotencyMethodInterceptor(store, claims, keyExtractor))
                          .async();
    }

    private Result<? extends CacheBackend> createStore(IdempotencyConfig config, ProvisioningContext context) {
        return switch (config.mode()) {
            case LOCAL -> success(createInMemory(config));
            case DISTRIBUTED -> createDHTBackend(config, context);
            case TIERED -> createDHTBackend(config, context).map(dhtStore -> createTiered(config, dhtStore));
        };
    }

    private static TieredCache createTiered(IdempotencyConfig config, CacheBackend dhtStore) {
        return TieredCache.tieredCache(createInMemory(config), dhtStore);
    }

    private static InMemoryCache createInMemory(IdempotencyConfig config) {
        return InMemoryCache.inMemoryCache(config.retentionSeconds(), config.maxEntries());
    }

    private Result<CacheBackend> createDHTBackend(IdempotencyConfig config, ProvisioningContext context) {
        return Result.all(context.extension(DHTClient.class),
                          context.extension(Serializer.class),
                          context.extension(Deserializer.class),
                          success(config.storeName()))
                     .map(DHTCacheBackend::dhtCacheBackend);
    }
}
