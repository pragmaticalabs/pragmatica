package org.pragmatica.aether.http.handler;

import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.lang.Result;

import java.util.Objects;

import static org.pragmatica.lang.Result.success;

/// Route metadata for KV-Store registration.
///
/// Maps HTTP method + path prefix to artifact + slice method.
/// Used by HttpRouteRegistry to route incoming requests.
///
/// @param httpMethod    HTTP method (GET, POST, PUT, DELETE, etc.)
/// @param pathPrefix    path prefix for TreeMap matching (e.g., "/users/", "/api/orders/")
/// @param artifactCoord full artifact coordinate (e.g., "org.example:user-service:1.0.0")
/// @param sliceMethod   slice method name to invoke
/// @param security      security policy for this route
public record HttpRouteDefinition(String httpMethod,
                                  String pathPrefix,
                                  String artifactCoord,
                                  String sliceMethod,
                                  RouteSecurityPolicy security) {
    /// Canonical constructor with validation.
    public HttpRouteDefinition {
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(artifactCoord, "artifactCoord");
        Objects.requireNonNull(sliceMethod, "sliceMethod");
        Objects.requireNonNull(security, "security");
    }

    /// Validated factory for constructing route definition.
    public static Result<HttpRouteDefinition> httpRouteDefinition(Result<String> httpMethod,
                                                                  Result<String> pathPrefix,
                                                                  Result<String> artifactCoord,
                                                                  Result<String> sliceMethod,
                                                                  Result<RouteSecurityPolicy> security) {
        return Result.all(httpMethod, pathPrefix, artifactCoord, sliceMethod, security)
                     .map(HttpRouteDefinition::new);
    }

    /// Create public route definition with path normalization.
    public static HttpRouteDefinition httpRouteDefinition(String httpMethod,
                                                          String pathPrefix,
                                                          String artifactCoord,
                                                          String sliceMethod) {
        return httpRouteDefinition(httpMethod, pathPrefix, artifactCoord, sliceMethod, RouteSecurityPolicy.publicRoute());
    }

    /// Create route definition with path normalization and security policy.
    public static HttpRouteDefinition httpRouteDefinition(String httpMethod,
                                                          String pathPrefix,
                                                          String artifactCoord,
                                                          String sliceMethod,
                                                          RouteSecurityPolicy security) {
        return Result.all(success(httpMethod),
                          success(normalizePrefix(pathPrefix)),
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
