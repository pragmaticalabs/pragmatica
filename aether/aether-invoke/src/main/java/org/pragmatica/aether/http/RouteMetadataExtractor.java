// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;

import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import static org.pragmatica.aether.http.handler.HttpRouteDefinition.httpRouteDefinition;


public interface RouteMetadataExtractor {
    List<HttpRouteDefinition> extract(RouteSource routes, String artifactCoord);

    static RouteMetadataExtractor routeMetadataExtractor() {
        return new RouteMetadataExtractorImpl();
    }
}

class RouteMetadataExtractorImpl implements RouteMetadataExtractor {
    @Override
    public List<HttpRouteDefinition> extract(RouteSource routes, String artifactCoord) {
        return routes.routes()
                     .map(route -> toDefinition(route, artifactCoord))
                     .toList();
    }

    private HttpRouteDefinition toDefinition(Route<?> route, String artifactCoord) {
        var security = resolveSecurityPolicy(route);

        return httpRouteDefinition(route.method().name(),
                                   extractPathPrefix(route.path()),
                                   artifactCoord,
                                   deriveSliceMethod(route),
                                   security);
    }

    private static SecurityPolicy resolveSecurityPolicy(Route<?> route) {
        return route.security() instanceof SecurityPolicy sp
               ? sp
               : SecurityPolicy.unspecified();
    }

    private String extractPathPrefix(String path) {
        int placeholderIndex = path.indexOf('{');

        return placeholderIndex > 0
               ? path.substring(0, placeholderIndex)
               : path;
    }

    private String deriveSliceMethod(Route<?> route) {
        var name = route.name();

        return name.isEmpty()
               ? route.path()
               : name;
    }
}
