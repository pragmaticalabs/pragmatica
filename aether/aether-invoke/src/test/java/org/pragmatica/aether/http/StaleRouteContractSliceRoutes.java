// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.stream.Stream;

import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;

/// #882 fixture: the exact shape a `*Routes` class generated BEFORE the #763 fix has — no
/// contract marker (the method did not exist), and a route whose `routes.toml` had no `[security]`
/// section baked in as `SecurityPolicy.publicRoute()`. Registered through `META-INF/services` like
/// a real generated class, so the publisher reaches it through the same `ServiceLoader` path.
public final class StaleRouteContractSliceRoutes implements RouteSource, SliceRouterFactory<StaleRouteContractSlice> {
    @Override
    public Class<StaleRouteContractSlice> sliceType() {
        return StaleRouteContractSlice.class;
    }

    @Override
    public SliceRouter create(StaleRouteContractSlice slice) {
        return create(slice, JsonMapper.defaultJsonMapper());
    }

    @Override
    public SliceRouter create(StaleRouteContractSlice slice, JsonMapper jsonMapper) {
        return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<String>get("/stale/items")
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("items").withSecurity(SecurityPolicy.publicRoute())
                              .asJson(),
                         Route.<String>get("/stale/admin")
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("admin").withSecurity(SecurityPolicy.authenticated())
                              .asJson());
    }
}
