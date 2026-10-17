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

/// #882 fixture: a `*Routes` class generated BEFORE the #763 fix — no contract marker — whose
/// every route is NON-public. The old default only ever produced `publicRoute()`, so a non-public
/// policy in a stale JAR was necessarily declared in `routes.toml`; nothing is hidden and the
/// factory must publish. Registered through `META-INF/services` like a real generated class.
public final class StaleNonPublicRouteContractSliceRoutes implements RouteSource, SliceRouterFactory<StaleNonPublicRouteContractSlice> {
    @Override
    public Class<StaleNonPublicRouteContractSlice> sliceType() {
        return StaleNonPublicRouteContractSlice.class;
    }

    @Override
    public SliceRouter create(StaleNonPublicRouteContractSlice slice) {
        return create(slice, JsonMapper.defaultJsonMapper());
    }

    @Override
    public SliceRouter create(StaleNonPublicRouteContractSlice slice, JsonMapper jsonMapper) {
        return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<String>get("/stale-nonpublic/items")
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("items").withSecurity(SecurityPolicy.authenticated())
                              .asJson(),
                         Route.<String>get("/stale-nonpublic/admin")
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("admin").withSecurity(SecurityPolicy.authenticated())
                              .asJson());
    }
}
