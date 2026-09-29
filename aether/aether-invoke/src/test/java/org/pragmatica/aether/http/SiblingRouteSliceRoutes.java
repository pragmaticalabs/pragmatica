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

import static org.pragmatica.http.routing.PathParameter.aLong;
import static org.pragmatica.http.routing.PathParameter.spacer;

/// #1678 fixture (adopted from v1670's P6 probe): ONE slice, two sibling routes sharing the base path `/orders/` --
/// the shape the route generator emits for `GET /orders/{id}` (PUBLIC) and `GET /orders/{id}/admin` (`role:admin`).
/// Two factories, one per declaration order, each bound to its own slice type so `ServiceLoader` selection is exact.
public final class SiblingRouteSliceRoutes {
    private SiblingRouteSliceRoutes() {}

    static Route<?> publicOrder() {
        return Route.<String>get("/orders/")
                    .withPath(aLong())
                    .to(id -> Promise.success("public-order-" + id))
                    .named("getOrder")
                    .withSecurity(SecurityPolicy.publicRoute())
                    .asJson();
    }

    static Route<?> adminOrder() {
        return Route.<String>get("/orders/")
                    .withPath(aLong(), spacer("admin"))
                    .to((id, _) -> Promise.success("ADMIN-SECRET-" + id))
                    .named("adminOrder")
                    .withSecurity(SecurityPolicy.roleRequired("admin"))
                    .asJson();
    }

    public static final class PublicFirstSlice {}

    public static final class AdminFirstSlice {}

    public static final class PublicFirst implements RouteSource, SliceRouterFactory<PublicFirstSlice> {
        @Override
        public Class<PublicFirstSlice> sliceType() {
            return PublicFirstSlice.class;
        }

        @Override
        public int routeSecurityContract() {
            return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;
        }

        @Override
        public SliceRouter create(PublicFirstSlice slice) {
            return create(slice, JsonMapper.defaultJsonMapper());
        }

        @Override
        public SliceRouter create(PublicFirstSlice slice, JsonMapper jsonMapper) {
            return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
        }

        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(publicOrder(), adminOrder());
        }
    }

    public static final class AdminFirst implements RouteSource, SliceRouterFactory<AdminFirstSlice> {
        @Override
        public Class<AdminFirstSlice> sliceType() {
            return AdminFirstSlice.class;
        }

        @Override
        public int routeSecurityContract() {
            return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;
        }

        @Override
        public SliceRouter create(AdminFirstSlice slice) {
            return create(slice, JsonMapper.defaultJsonMapper());
        }

        @Override
        public SliceRouter create(AdminFirstSlice slice, JsonMapper jsonMapper) {
            return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
        }

        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(adminOrder(), publicOrder());
        }
    }
}
