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
import org.pragmatica.lang.utils.Causes;

/// #1573 B1 fixture: one route per execution outcome — a value, a handler that throws on the calling
/// thread (the HTTP twin of the bridge's MethodThrew), and a failure the method returns (a downstream
/// outage, which is never a defect).
public final class OutcomeRouteSliceRoutes implements RouteSource, SliceRouterFactory<OutcomeRouteSlice> {
    @Override
    public Class<OutcomeRouteSlice> sliceType() {
        return OutcomeRouteSlice.class;
    }

    @Override
    public int routeSecurityContract() {
        return 1;
    }

    @Override
    public SliceRouter create(OutcomeRouteSlice slice) {
        return create(slice, JsonMapper.defaultJsonMapper());
    }

    @Override
    public SliceRouter create(OutcomeRouteSlice slice, JsonMapper jsonMapper) {
        return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<String>get("/outcome/ok")
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("ok").withSecurity(SecurityPolicy.publicRoute())
                              .asJson(),
                         Route.<String>get("/outcome/throws")
                              .withoutParameters()
                              .to(_ -> thrower())
                              .named("throws").withSecurity(SecurityPolicy.publicRoute())
                              .asJson(),
                         Route.<String>get("/outcome/returned")
                              .withoutParameters()
                              .to(_ -> Causes.cause("downstream unavailable").<String>promise())
                              .named("returned").withSecurity(SecurityPolicy.publicRoute())
                              .asJson());
    }

    private static Promise<String> thrower() {
        throw new IllegalStateException("defective build");
    }
}
