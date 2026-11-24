// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Option;

/// What a cache or an idempotency store asks of the backend-selection seam ([CacheBackends], #3658): its name
/// (the backend's namespace), entry lifetime and capacity, topology and the backend its section names.
/// `kind` exists for the one place the two differ: which DHT client a `dht` backend writes through.
record StoreSpec(Kind kind, String name, int ttlSeconds, int maxEntries, CacheMode mode, Option<String> backend) {
    enum Kind {
        CACHE,
        IDEMPOTENCY
    }

    static StoreSpec storeSpec(CacheConfig config) {
        return new StoreSpec(Kind.CACHE,
                             config.cacheName(),
                             config.ttlSeconds(),
                             config.maxEntries(),
                             config.mode(),
                             config.backend());
    }

    static StoreSpec storeSpec(IdempotencyConfig config) {
        return new StoreSpec(Kind.IDEMPOTENCY,
                             config.storeName(),
                             config.retentionSeconds(),
                             config.maxEntries(),
                             config.mode(),
                             config.backend());
    }
}
