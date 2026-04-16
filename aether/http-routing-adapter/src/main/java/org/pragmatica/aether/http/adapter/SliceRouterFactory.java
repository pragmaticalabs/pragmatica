// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.pragmatica.json.JsonMapper;


/// Factory interface for creating {@link SliceRouter} instances for a specific slice type.
///
/// Implementations are discovered via {@link java.util.ServiceLoader} mechanism.
/// Each slice that exposes HTTP routes generates an implementation of this interface.
///
/// Usage:
/// ```{@code
/// ServiceLoader<SliceRouterFactory> loader = ServiceLoader.load(SliceRouterFactory.class);
/// var factory = loader.stream()
///     .map(ServiceLoader.Provider::get)
///     .filter(f -> f.sliceType() == UserService.class)
///     .findFirst()
///     .orElseThrow();
///
/// var router = factory.create(userServiceImpl);
/// }```
///
/// @param <T> the slice interface type this factory creates routers for
public interface SliceRouterFactory<T> {
    Class<T> sliceType();
    SliceRouter create(T slice);
    SliceRouter create(T slice, JsonMapper jsonMapper);
}
