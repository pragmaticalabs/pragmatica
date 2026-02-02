package org.pragmatica.aether.http.handler;

import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Raw HTTP request data passed through SliceInvoker.
 * <p>
 * Contains all information needed to reconstruct HTTP request at destination node
 * where parameter extraction occurs.
 * <p>
 * Note: The body byte array is defensively copied to ensure immutability.
 *
 * @param path        request path (e.g., "/users/123")
 * @param method      HTTP method (e.g., "GET", "POST")
 * @param queryParams query parameters as multi-value map
 * @param headers     HTTP headers as multi-value map
 * @param body        request body bytes (empty for GET)
 * @param requestId   unique request identifier for tracing
 * @param security    security context with authentication info
 */
public record HttpRequestContext(String path,
                                 String method,
                                 Map<String, List<String>> queryParams,
                                 Map<String, List<String>> headers,
                                 byte[] body,
                                 String requestId,
                                 SecurityContext security) {
    private static final byte[] EMPTY_BODY = new byte[0];

    /**
     * Canonical constructor with defensive copy of body.
     */
    public HttpRequestContext {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(queryParams, "queryParams");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(security, "security");
        body = (body == null || body.length == 0)
               ? EMPTY_BODY
               : body.clone();
    }

    /**
     * Defensive copy on access.
     */
    @Override
    public byte[] body() {
        return body.length == 0
               ? EMPTY_BODY
               : body.clone();
    }

    /**
     * Create context with empty body and anonymous security.
     */
    public static HttpRequestContext httpRequestContext(String path,
                                                        String method,
                                                        Map<String, List<String>> queryParams,
                                                        Map<String, List<String>> headers,
                                                        String requestId) {
        return new HttpRequestContext(path,
                                      method,
                                      queryParams,
                                      headers,
                                      EMPTY_BODY,
                                      requestId,
                                      SecurityContext.anonymous());
    }

    /**
     * Create context with body and anonymous security.
     */
    public static HttpRequestContext httpRequestContext(String path,
                                                        String method,
                                                        Map<String, List<String>> queryParams,
                                                        Map<String, List<String>> headers,
                                                        byte[] body,
                                                        String requestId) {
        return new HttpRequestContext(path, method, queryParams, headers, body, requestId, SecurityContext.anonymous());
    }

    /**
     * Create context with body and security context.
     */
    public static HttpRequestContext httpRequestContext(String path,
                                                        String method,
                                                        Map<String, List<String>> queryParams,
                                                        Map<String, List<String>> headers,
                                                        byte[] body,
                                                        String requestId,
                                                        SecurityContext security) {
        return new HttpRequestContext(path, method, queryParams, headers, body, requestId, security);
    }

    /**
     * Create new context with updated security (immutable copy).
     */
    public HttpRequestContext withSecurity(SecurityContext newSecurity) {
        return new HttpRequestContext(path, method, queryParams, headers, body, requestId, newSecurity);
    }

    /**
     * Check if request is authenticated (not anonymous).
     */
    public boolean isAuthenticated() {
        return security.isAuthenticated();
    }

    /**
     * Check if request has specific role.
     */
    public boolean hasRole(Role role) {
        return security.hasRole(role);
    }

    /**
     * Check if request has role by name.
     */
    public boolean hasRole(String roleName) {
        return security.hasRole(roleName);
    }
}
