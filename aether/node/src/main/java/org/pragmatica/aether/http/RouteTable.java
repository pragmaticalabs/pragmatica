// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;

import java.util.List;
import java.util.Set;


public record RouteTable(Set<HttpNodeRouteKey> localRoutes, List<HttpRouteRegistry.RouteInfo> remoteRoutes) {
    private static final RouteTable EMPTY = new RouteTable(Set.of(), List.of());

    public static RouteTable empty() {
        return EMPTY;
    }

    public static RouteTable routeTable(Set<HttpNodeRouteKey> localRoutes,
                                        List<HttpRouteRegistry.RouteInfo> remoteRoutes) {
        return new RouteTable(Set.copyOf(localRoutes), List.copyOf(remoteRoutes));
    }
}
