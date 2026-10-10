// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.pragmatica.lang.Result.success;


public final class IdempotencyInterceptorFactory implements ResourceFactory<IdempotencyMethodInterceptor, IdempotencyConfig> {
    private final SharedByName<StoreAndClaims> stores = SharedByName.sharedByName();

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

        return createStore(config, context).map(store -> share(config.storeName(),
                                                               store,
                                                               keyExtractor))
                          .async();
    }

    /// Releases this interceptor's hold on the store and claims shared under its store name; the
    /// name is pruned from the registry when its last holder is released (#894). Neither the store
    /// nor the claim map owns anything to close, so release is all unloading does.
    @Override
    public Promise<Unit> close(IdempotencyMethodInterceptor resource) {
        return Promise.success(stores.release(resource));
    }

    /// Whether a store is currently registered under `storeName`.
    boolean retains(String storeName) {
        return stores.contains(storeName);
    }

    /// Store and in-flight claims live and die together under one name: interceptors sharing a
    /// store must share its claims, or two of them could both run the same key.
    private IdempotencyMethodInterceptor share(String storeName, CacheBackend candidate, Fn1<Object, ?> keyExtractor) {
        return stores.acquire(storeName,
                              StoreAndClaims.storeAndClaims(candidate),
                              shared -> new IdempotencyMethodInterceptor(shared.store(), shared.claims(), keyExtractor));
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

    /// The REPLICATED DHT at the committed `[replication]` factors, never the cache namespace's (#1777 Q4): a dedup
    /// record lost with its only copy re-executes the call it guarded.
    private Result<CacheBackend> createDHTBackend(IdempotencyConfig config, ProvisioningContext context) {
        return Result.all(context.extension(DHTClient.class),
                          context.extension(Serializer.class),
                          context.extension(Deserializer.class),
                          success(config.storeName()))
                     .map(DHTCacheBackend::dhtCacheBackend);
    }

    private record StoreAndClaims(CacheBackend store, ConcurrentHashMap<Object, Promise<Object>> claims) {
        static StoreAndClaims storeAndClaims(CacheBackend store) {
            return new StoreAndClaims(store, new ConcurrentHashMap<>());
        }
    }
}
