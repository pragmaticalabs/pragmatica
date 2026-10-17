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

/// #884 fixture: a generated-shape `SliceRouterFactory` whose single route path the test chooses
/// immediately before each `publishRoutes` call.
///
/// It exists to put the agreement tests on the PATH PRODUCTION USES. `HttpRequestHandlerFactory`
/// -- what [NestedPrefixRouteFactories] drives -- has no production implementation, and its branch
/// of `publishRoutes` never populates `sliceRouters`, so `findLocalRouter` returns nothing for
/// anything published through it and the policy-pick/router-pick agreement cannot be observed
/// there at all. The 4-arg `publishRoutes` -> `publishViaSliceRouterFactory` branch below is the
/// one `RouteSourceGenerator` emits for.
///
/// The route path is a static handoff rather than a constructor argument because `ServiceLoader`
/// owns the instantiation. Set it, publish, and the definition stored in `publishedRoutes` is
/// snapshotted at that moment by `RouteMetadataExtractor`; the router instance keeps reading this
/// field, so the routers built here are used for IDENTITY only, never dispatched through.
public final class RouteAgreementSliceRoutes implements RouteSource, SliceRouterFactory<RouteAgreementSliceRoutes.Slice> {
    /// The slice type this factory claims. One type published under several artifacts is what the
    /// nested-prefix and duplicate-prefix cases both need.
    public static final class Slice {}

    private static volatile String nextRoutePath = "/api/agree/";

    static void nextRoutePath(String path) {
        nextRoutePath = path;
    }

    @Override
    public Class<Slice> sliceType() {
        return Slice.class;
    }

    /// The current contract, so `staleContractRefusal` does not intercept a fixture declaring a
    /// public route (#882). Nothing here tests the stamp.
    @Override
    public int routeSecurityContract() {
        return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;
    }

    @Override
    public SliceRouter create(Slice slice) {
        return create(slice, JsonMapper.defaultJsonMapper());
    }

    @Override
    public SliceRouter create(Slice slice, JsonMapper jsonMapper) {
        return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<String>get(nextRoutePath)
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("handle")
                              .withSecurity(SecurityPolicy.publicRoute())
                              .asJson());
    }
}
