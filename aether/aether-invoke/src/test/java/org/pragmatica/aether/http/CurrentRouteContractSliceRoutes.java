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

/// #882 fixture: what the CURRENT generator emits — the contract stamp, and a route that is
/// PUBLIC because `routes.toml` said so. Its public route must publish; the stamp is what tells it
/// apart from [StaleRouteContractSliceRoutes], whose routes are byte-identical.
public final class CurrentRouteContractSliceRoutes implements RouteSource, SliceRouterFactory<CurrentRouteContractSlice> {
    @Override
    public Class<CurrentRouteContractSlice> sliceType() {
        return CurrentRouteContractSlice.class;
    }

    @Override
    public int routeSecurityContract() {
        return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;
    }

    @Override
    public SliceRouter create(CurrentRouteContractSlice slice) {
        return create(slice, JsonMapper.defaultJsonMapper());
    }

    @Override
    public SliceRouter create(CurrentRouteContractSlice slice, JsonMapper jsonMapper) {
        return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<String>get("/current/items")
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("items").withSecurity(SecurityPolicy.publicRoute())
                              .asJson());
    }
}
