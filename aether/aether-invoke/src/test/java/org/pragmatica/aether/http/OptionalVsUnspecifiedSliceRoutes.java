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

/// #2946 fixture: ONE slice publishing the SAME route shape (`GET /things/{id}`) twice -- once `OPTIONAL`, once with no
/// declared policy (`UNSPECIFIED`, "inherit the global mode") -- in both declaration orders. The node takes the
/// strictest of the shape, and an `OPTIONAL` declaration must never weaken the inherited policy.
public final class OptionalVsUnspecifiedSliceRoutes {
    private OptionalVsUnspecifiedSliceRoutes() {}

    static Route<?> optionalThing() {
        return Route.<String>get("/things/")
                    .withPath(aLong())
                    .to(id -> Promise.success("optional-" + id))
                    .named("optionalThing")
                    .withSecurity(SecurityPolicy.optional())
                    .asJson();
    }

    static Route<?> inheritedThing() {
        return Route.<String>get("/things/")
                    .withPath(aLong())
                    .to(id -> Promise.success("inherited-" + id))
                    .named("inheritedThing")
                    .asJson();
    }

    static Route<?> authenticatedThing() {
        return Route.<String>get("/things/")
                    .withPath(aLong())
                    .to(id -> Promise.success("authenticated-" + id))
                    .named("authenticatedThing")
                    .withSecurity(SecurityPolicy.authenticated())
                    .asJson();
    }

    public static final class OptionalFirstSlice {}

    public static final class OptionalThenAuthenticatedSlice {}

    public static final class AuthenticatedThenOptionalSlice {}

    public static final class InheritedFirstSlice {}

    public static final class OptionalFirst implements RouteSource, SliceRouterFactory<OptionalFirstSlice> {
        @Override
        public Class<OptionalFirstSlice> sliceType() {
            return OptionalFirstSlice.class;
        }

        @Override
        public int routeSecurityContract() {
            return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;
        }

        @Override
        public SliceRouter create(OptionalFirstSlice slice) {
            return create(slice, JsonMapper.defaultJsonMapper());
        }

        @Override
        public SliceRouter create(OptionalFirstSlice slice, JsonMapper jsonMapper) {
            return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
        }

        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(optionalThing(), inheritedThing());
        }
    }

    public static final class InheritedFirst implements RouteSource, SliceRouterFactory<InheritedFirstSlice> {
        @Override
        public Class<InheritedFirstSlice> sliceType() {
            return InheritedFirstSlice.class;
        }

        @Override
        public int routeSecurityContract() {
            return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;
        }

        @Override
        public SliceRouter create(InheritedFirstSlice slice) {
            return create(slice, JsonMapper.defaultJsonMapper());
        }

        @Override
        public SliceRouter create(InheritedFirstSlice slice, JsonMapper jsonMapper) {
            return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
        }

        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(inheritedThing(), optionalThing());
        }
    }

    public static final class OptionalThenAuthenticated implements RouteSource, SliceRouterFactory<OptionalThenAuthenticatedSlice> {
        @Override
        public Class<OptionalThenAuthenticatedSlice> sliceType() {
            return OptionalThenAuthenticatedSlice.class;
        }

        @Override
        public int routeSecurityContract() {
            return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;
        }

        @Override
        public SliceRouter create(OptionalThenAuthenticatedSlice slice) {
            return create(slice, JsonMapper.defaultJsonMapper());
        }

        @Override
        public SliceRouter create(OptionalThenAuthenticatedSlice slice, JsonMapper jsonMapper) {
            return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
        }

        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(optionalThing(), authenticatedThing());
        }
    }

    public static final class AuthenticatedThenOptional implements RouteSource, SliceRouterFactory<AuthenticatedThenOptionalSlice> {
        @Override
        public Class<AuthenticatedThenOptionalSlice> sliceType() {
            return AuthenticatedThenOptionalSlice.class;
        }

        @Override
        public int routeSecurityContract() {
            return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;
        }

        @Override
        public SliceRouter create(AuthenticatedThenOptionalSlice slice) {
            return create(slice, JsonMapper.defaultJsonMapper());
        }

        @Override
        public SliceRouter create(AuthenticatedThenOptionalSlice slice, JsonMapper jsonMapper) {
            return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
        }

        @Override
        public Stream<Route<?>> routes() {
            return Stream.of(authenticatedThing(), optionalThing());
        }
    }
}
