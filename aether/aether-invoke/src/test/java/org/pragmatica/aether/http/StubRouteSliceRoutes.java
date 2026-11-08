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

/// ServiceLoader-discoverable [SliceRouterFactory] advertising a single PUBLIC prefix route `GET /stub/`, the minimum
/// needed to drive `publishRoutes(...)` into route publication for [HttpRoutePublisherConsensusRetryTest] and
/// [HttpRoutePublisherOverrideResolutionTest]. Registered in
/// `src/test/resources/META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory`.
public final class StubRouteSliceRoutes implements RouteSource, SliceRouterFactory<StubRouteSlice> {
    @Override
    public Class<StubRouteSlice> sliceType() {
        return StubRouteSlice.class;
    }

    @Override
    public int routeSecurityContract() {
        return ROUTE_SECURITY_CONTRACT;
    }

    @Override
    public SliceRouter create(StubRouteSlice slice) {
        return create(slice, JsonMapper.defaultJsonMapper());
    }

    @Override
    public SliceRouter create(StubRouteSlice slice, JsonMapper jsonMapper) {
        return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<String>get("/stub/")
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("list").withSecurity(SecurityPolicy.publicRoute())
                              .asJson());
    }
}
