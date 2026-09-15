// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.Objects;

import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record HttpRouteDefinition(String httpMethod,
                                  String pathPrefix,
                                  String artifactCoord,
                                  String sliceMethod,
                                  SecurityPolicy security) {
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
