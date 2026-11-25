// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


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

    /// The backend comes from the selection seam only (#3658): this factory never builds one itself. A `dht` store
    /// is the REPLICATED DHT, never the cache namespace's (#1777 Q4; [CacheBackends]).
    private static Result<CacheBackend> createStore(IdempotencyConfig config, ProvisioningContext context) {
        return CacheBackends.cacheBackend(StoreSpec.storeSpec(config), context);
    }

    private record StoreAndClaims(CacheBackend store, ConcurrentHashMap<Object, Promise<Object>> claims) {
        static StoreAndClaims storeAndClaims(CacheBackend store) {
            return new StoreAndClaims(store, new ConcurrentHashMap<>());
        }
    }
}
