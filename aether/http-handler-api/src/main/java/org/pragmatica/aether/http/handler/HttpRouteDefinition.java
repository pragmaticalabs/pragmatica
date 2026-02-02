package org.pragmatica.aether.http.handler;

import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;

import java.util.Objects;

/**
 * Route metadata for KV-Store registration.
 * <p>
 * Maps HTTP method + path prefix to artifact + slice method.
 * Used by HttpRouteRegistry to route incoming requests.
 *
 * @param httpMethod    HTTP method (GET, POST, PUT, DELETE, etc.)
 * @param pathPrefix    path prefix for TreeMap matching (e.g., "/users/", "/api/orders/")
 * @param artifactCoord full artifact coordinate (e.g., "org.example:user-service:1.0.0")
 * @param sliceMethod   slice method name to invoke
 * @param security      security policy for this route
 */
public record HttpRouteDefinition(String httpMethod,
                                  String pathPrefix,
                                  String artifactCoord,
                                  String sliceMethod,
                                  RouteSecurityPolicy security) {
    /**
     * Canonical constructor with validation.
     */
    public HttpRouteDefinition {
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(artifactCoord, "artifactCoord");
        Objects.requireNonNull(sliceMethod, "sliceMethod");
        Objects.requireNonNull(security, "security");
    }

    /**
     * Create public route definition with path normalization.
     */
    public static HttpRouteDefinition httpRouteDefinition(String httpMethod,
                                                          String pathPrefix,
                                                          String artifactCoord,
                                                          String sliceMethod) {
        return new HttpRouteDefinition(httpMethod,
                                       normalizePrefix(pathPrefix),
                                       artifactCoord,
                                       sliceMethod,
                                       RouteSecurityPolicy.publicRoute());
    }

    /**
     * Create route definition with path normalization and security policy.
     */
    public static HttpRouteDefinition httpRouteDefinition(String httpMethod,
                                                          String pathPrefix,
                                                          String artifactCoord,
                                                          String sliceMethod,
                                                          RouteSecurityPolicy security) {
        return new HttpRouteDefinition(httpMethod, normalizePrefix(pathPrefix), artifactCoord, sliceMethod, security);
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
