// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.util.List;
import java.util.stream.Stream;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.terra.TerraContext;
import org.pragmatica.terra.TerraFactory;

/// A slice serving `GET /actuator/x`, a route inside the default management base path.
public final class ActuatorSliceFixture implements TerraFactory<ActuatorSliceFixture.Slice> {
    public record Slice() {}

    public static final class Routes implements SliceRouterFactory<Slice> {
        public Class<Slice> sliceType() { return Slice.class; }
        public int routeSecurityContract() { return SliceRouterFactory.ROUTE_SECURITY_CONTRACT; }
        public SliceRouter create(Slice slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(Slice slice, JsonMapper mapper) { return create(slice, mapper, RouteMountMode.pathMode()); }
        public SliceRouter create(Slice slice, JsonMapper mapper, RouteMountMode mode) {
            var route = Route.<String>get("/actuator/x").withoutParameters().to(_ -> Promise.success("x"))
                             .withSecurity(SecurityPolicy.publicRoute()).asText();
            var source = new RouteSource() {
                public Stream<Route<?>> routes() { return Stream.of(route); }
                public SliceVersionRegistry versionRegistry() { return SliceVersionRegistry.UNVERSIONED; }
            };
            return SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), mapper, mode);
        }
    }

    public String artifact() { return "test:actuator:1"; }
    public Class<Slice> sliceType() { return Slice.class; }
    public List<Class<?>> dependencies() { return List.of(); }
    public Promise<Slice> create(TerraContext context) { return Promise.success(new Slice()); }
}
