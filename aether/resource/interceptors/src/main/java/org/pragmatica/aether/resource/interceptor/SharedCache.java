// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

/// What [CacheInterceptorFactory] registers under a `cache_name`: the backend and the [CacheShape] it was
/// built for, so a later section over the same name can be checked against what is actually shared.
record SharedCache(CacheBackend backend, CacheShape shape) {
    static SharedCache sharedCache(CacheBackend backend, CacheShape shape) {
        return new SharedCache(backend, shape);
    }

    /// The same backend, with the shape taking in what `incoming` knows (see [CacheShape#merged]).
    SharedCache merged(SharedCache incoming) {
        return sharedCache(backend,
                           shape.merged(incoming.shape()));
    }
}
