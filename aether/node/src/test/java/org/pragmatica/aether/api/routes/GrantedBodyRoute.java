// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.stream.Stream;

import org.pragmatica.aether.api.routes.ManagementRouter.GrantedHandler;
import org.pragmatica.aether.http.security.Authenticated;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;

/// A `GET` management route at a given path whose [GrantedHandler] runs a test-supplied body, for tests outside this package.
public final class GrantedBodyRoute implements RouteSource {
    public interface Body {
        Promise<String> run(RequestContext request, Authenticated authenticated);
    }

    private final String path;
    private final Body body;

    private GrantedBodyRoute(String path, Body body) {
        this.path = path;
        this.body = body;
    }

    public static GrantedBodyRoute grantedBodyRoute(String path, Body body) {
        return new GrantedBodyRoute(path, body);
    }

    public static ManagementRouter routerWith(RouteSource[] sources, RouteSource... extra) {
        return ManagementRouter.managementRouter(Stream.concat(Stream.of(sources), Stream.of(extra))
                                                       .toArray(RouteSource[]::new));
    }

    @Override
    public Stream<Route<?>> routes() {
        GrantedHandler<String> handler = body::run;
        return Stream.of(Route.<String>get(path).toJson(handler));
    }
}
