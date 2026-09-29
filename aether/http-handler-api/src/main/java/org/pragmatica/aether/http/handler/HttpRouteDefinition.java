// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.List;
import java.util.Objects;

import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.RouteShape;
import org.pragmatica.http.routing.RouteShapeSelector;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// `pathArity` and `spacers` are the route's SHAPE beyond its base path (#1678): sibling routes of one slice share
/// `pathPrefix` (`GET /orders/{id}` and `GET /orders/{id}/admin` are both `/orders/`), and only the shape tells them
/// apart -- [RouteShapeSelector] picks among them exactly as the slice's router does. A definition built without a
/// shape (arity 0, no spacers) is a prefix route, which is what every definition was before.
public record HttpRouteDefinition(String httpMethod,
                                  String pathPrefix,
                                  String artifactCoord,
                                  String sliceMethod,
                                  SecurityPolicy security,
                                  int pathArity,
                                  List<String> spacers) implements RouteShape {
    /// #884: the prefix is normalized HERE, not in one of the factories, because every local route
    /// lookup compares a normalized request path against this field with `startsWith`. That is a
    /// segment-boundary comparison only while the stored prefix ends in a slash -- without it
    /// `/api/pricing` swallows `/api/pricing-admin/report`. Both production producers happened to
    /// reach a normalizing factory (`RouteMetadataExtractor`, `SecurityOverrideApplier`), but
    /// nothing REFUSED a definition built any other way: the canonical constructor and the
    /// `Result`-returning factory stored whatever they were handed. Normalizing in the compact
    /// constructor makes the boundary property hold for every construction path, so the single
    /// selection rule in `HttpRoutePublisher` can rely on it locally instead of on a call-site
    /// convention held elsewhere.
    public HttpRouteDefinition {
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(artifactCoord, "artifactCoord");
        Objects.requireNonNull(sliceMethod, "sliceMethod");
        Objects.requireNonNull(security, "security");
        pathPrefix = normalizePrefix(pathPrefix);
        spacers = List.copyOf(Objects.requireNonNull(spacers, "spacers"));
    }

    public HttpRouteDefinition(String httpMethod,
                               String pathPrefix,
                               String artifactCoord,
                               String sliceMethod,
                               SecurityPolicy security) {
        this(httpMethod, pathPrefix, artifactCoord, sliceMethod, security, 0, List.of());
    }

    public static HttpRouteDefinition httpRouteDefinition(String httpMethod,
                                                          String pathPrefix,
                                                          String artifactCoord,
                                                          String sliceMethod,
                                                          SecurityPolicy security,
                                                          int pathArity,
                                                          List<String> spacers) {
        return new HttpRouteDefinition(httpMethod, pathPrefix, artifactCoord, sliceMethod, security, pathArity, spacers);
    }

    /// The same route with another policy -- an override applied, or the policy of the sibling actually served.
    public HttpRouteDefinition withSecurity(SecurityPolicy newSecurity) {
        return new HttpRouteDefinition(httpMethod, pathPrefix, artifactCoord, sliceMethod, newSecurity, pathArity, spacers);
    }

    /// [RouteShape]: the base path is the prefix.
    @Override
    public String path() {
        return pathPrefix;
    }

    @Override
    public int pathParamCount() {
        return pathArity;
    }

    public static Result<HttpRouteDefinition> httpRouteDefinition(Result<String> httpMethod,
                                                                  Result<String> pathPrefix,
                                                                  Result<String> artifactCoord,
                                                                  Result<String> sliceMethod,
                                                                  Result<SecurityPolicy> security) {
        return Result.all(httpMethod, pathPrefix, artifactCoord, sliceMethod, security).map(HttpRouteDefinition::new);
    }

    public static HttpRouteDefinition httpRouteDefinition(String httpMethod,
                                                          String pathPrefix,
                                                          String artifactCoord,
                                                          String sliceMethod) {
        return httpRouteDefinition(httpMethod, pathPrefix, artifactCoord, sliceMethod, SecurityPolicy.publicRoute());
    }

    public static HttpRouteDefinition httpRouteDefinition(String httpMethod,
                                                          String pathPrefix,
                                                          String artifactCoord,
                                                          String sliceMethod,
                                                          SecurityPolicy security) {
        return Result.all(success(httpMethod),
                          success(pathPrefix),
                          success(artifactCoord),
                          success(sliceMethod),
                          success(security))
                     .map(HttpRouteDefinition::new)
                     .unwrap();
    }

    private static String normalizePrefix(String path) {
        Objects.requireNonNull(path, "path");
        var normalized = path.isBlank()
                         ? "/"
                         : path.strip();

        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }

        return normalized;
    }
}
