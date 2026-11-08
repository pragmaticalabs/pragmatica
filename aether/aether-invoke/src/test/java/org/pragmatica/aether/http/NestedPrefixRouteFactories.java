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

/// Two [SliceRouterFactory] implementations advertising NESTED prefixes, one per slice type, for
/// [HttpRoutePublisherNestedPrefixTest] (#884). Both are registered in `META-INF/services`; the publisher selects
/// the one whose slice type matches the published slice instance, so each artifact publishes exactly its own route.
public final class NestedPrefixRouteFactories {
    static final String METHOD = "GET";
    static final String OUTER_PREFIX = "/api/v1/pricing/";
    static final String INNER_PREFIX = "/api/v1/pricing/analytics/";

    private NestedPrefixRouteFactories() {}

    public static final class OuterSlice {}

    public static final class InnerSlice {}

    public static final class OuterFactory implements RouteSource, SliceRouterFactory<OuterSlice> {
        @Override
        public Class<OuterSlice> sliceType() {
            return OuterSlice.class;
        }

        @Override
        public int routeSecurityContract() {
            return ROUTE_SECURITY_CONTRACT;
        }

        @Override
        public SliceRouter create(OuterSlice slice) {
            return create(slice, JsonMapper.defaultJsonMapper());
        }

        @Override
        public SliceRouter create(OuterSlice slice, JsonMapper jsonMapper) {
            return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
        }

        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(prefixRoute(OUTER_PREFIX, "catalog"));
        }
    }

    public static final class InnerFactory implements RouteSource, SliceRouterFactory<InnerSlice> {
        @Override
        public Class<InnerSlice> sliceType() {
            return InnerSlice.class;
        }

        @Override
        public int routeSecurityContract() {
            return ROUTE_SECURITY_CONTRACT;
        }

        @Override
        public SliceRouter create(InnerSlice slice) {
            return create(slice, JsonMapper.defaultJsonMapper());
        }

        @Override
        public SliceRouter create(InnerSlice slice, JsonMapper jsonMapper) {
            return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
        }

        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(prefixRoute(INNER_PREFIX, "report"));
        }
    }

    private static Route<?> prefixRoute(String prefix, String name) {
        return Route.<String>get(prefix)
                    .withoutParameters()
                    .to(_ -> Promise.success(name))
                    .named(name).withSecurity(SecurityPolicy.publicRoute())
                    .asJson();
    }
}
