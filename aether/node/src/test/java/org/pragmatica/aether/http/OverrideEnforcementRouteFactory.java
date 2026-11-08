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

/// ServiceLoader-discoverable [`SliceRouterFactory`] used only by
/// [`AppHttpServerOverrideEnforcementTest`], so that test can drive the REAL
/// [`org.pragmatica.aether.http.HttpRoutePublisher`] implementation through its real
/// `publishRoutes(...)` path instead of standing in a hand-written policy stub.
///
/// The route is declared `UNSPECIFIED` on purpose — that is #887's headline scenario: a route that
/// never declared a `[security]` stance, given a `role:admin` override by the operator's blueprint,
/// under the shipped `security_mode = "api-key"`.
///
/// Registered via `src/test/resources/META-INF/services/...SliceRouterFactory`, and selected by the
/// publisher only for an [Slice] instance.
public final class OverrideEnforcementRouteFactory implements RouteSource, SliceRouterFactory<OverrideEnforcementRouteFactory.Slice> {
    public static final String METHOD = "GET";
    public static final String PATH_PREFIX = "/undeclared/";
    public static final String ARTIFACT_COORD = "com.example:undeclared:1.0.0";

    /// The slice type this factory serves; carries no behaviour.
    public static final class Slice {}

    @Override
    public Class<Slice> sliceType() {
        return Slice.class;
    }

    @Override
    public int routeSecurityContract() {
        return ROUTE_SECURITY_CONTRACT;
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
        return Stream.of(Route.<String>get(PATH_PREFIX)
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("list").withSecurity(SecurityPolicy.unspecified())
                              .asJson());
    }
}
