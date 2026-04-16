// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Pluggable cache storage backend.
///
/// Implementations may be local (in-memory), distributed (DHT), or tiered (L1+L2).
/// All operations return Promise to accommodate async backends.
public interface CacheBackend {
    Promise<Option<Object>> get(Object key);
    Promise<Unit> put(Object key, Object value);
    Promise<Unit> remove(Object key);
}
