// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.json.JsonMapper;


public interface SliceRouterFactory<T> {
    Class<T> sliceType();
    SliceRouter create(T slice);
    SliceRouter create(T slice, JsonMapper jsonMapper);

    /// Create a slice router that mounts this slice's routes in the given #198 detection mode.
    /// Path mode (the default) composes `{apiPrefix}/v{N}/{path}`; header mode mounts the bare
    /// `{apiPrefix}/{path}` and selects the version from a request header at dispatch time. The
    /// default delegates to [#create(Object, JsonMapper)] so existing factories stay path-mode.
    ///
    /// @param slice      the slice instance to route to
    /// @param jsonMapper the JSON mapper for body binding and serialization
    /// @param mountMode  the deploy-time API-version detection mode
    /// @return a slice router mounting routes per the mode
    default SliceRouter create(T slice, JsonMapper jsonMapper, RouteMountMode mountMode) {
        return create(slice, jsonMapper);
    }
}
