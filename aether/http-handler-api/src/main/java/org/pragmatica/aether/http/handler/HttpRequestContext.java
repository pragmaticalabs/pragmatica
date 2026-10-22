// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Result.success;


/// The request payload handed to a slice, and the one a forwarded request crosses the wire in.
///
/// #2310: `trailers` carries the request's trailer fields (RFC 9110 §6.5), as the receiving transport delivered them
/// with the forbidden names already discarded, so a forwarded request reaches its slice with them. Empty when the
/// client sent none.
@Codec
public record HttpRequestContext(String path,
                                 String method,
                                 Map<String, List<String>> queryParams,
                                 Map<String, List<String>> headers,
                                 byte[] body,
                                 Map<String, List<String>> trailers,
                                 String requestId,
                                 SecurityContext security) {
    private static final byte[] EMPTY_BODY = new byte[0];

    public HttpRequestContext {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(queryParams, "queryParams");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(trailers, "trailers");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(security, "security");
        if (body == null || body.length == 0) {
            body = EMPTY_BODY;
        }
    }

    public static HttpRequestContext httpRequestContext(String path,
                                                        String method,
                                                        Map<String, List<String>> queryParams,
                                                        Map<String, List<String>> headers,
                                                        String requestId) {
        return httpRequestContext(path,
                                  method,
                                  queryParams,
                                  headers,
                                  EMPTY_BODY,
                                  requestId,
                                  SecurityContext.securityContext());
    }

    public static HttpRequestContext httpRequestContext(String path,
                                                        String method,
                                                        Map<String, List<String>> queryParams,
                                                        Map<String, List<String>> headers,
                                                        byte[] body,
                                                        String requestId) {
        return httpRequestContext(path, method, queryParams, headers, body, requestId, SecurityContext.securityContext());
    }

    public static HttpRequestContext httpRequestContext(String path,
                                                        String method,
                                                        Map<String, List<String>> queryParams,
                                                        Map<String, List<String>> headers,
                                                        byte[] body,
                                                        String requestId,
                                                        SecurityContext security) {
        return httpRequestContext(path, method, queryParams, headers, body, Map.of(), requestId, security);
    }

    /// #2310: a request with trailer fields.
    public static HttpRequestContext httpRequestContext(String path,
                                                        String method,
                                                        Map<String, List<String>> queryParams,
                                                        Map<String, List<String>> headers,
                                                        byte[] body,
                                                        Map<String, List<String>> trailers,
                                                        String requestId,
                                                        SecurityContext security) {
        return Result.all(success(path),
                          success(method),
                          success(queryParams),
                          success(headers),
                          success(body),
                          success(trailers),
                          success(requestId),
                          success(security))
                     .map(HttpRequestContext::new)
                     .unwrap();
    }

    public HttpRequestContext withSecurity(SecurityContext newSecurity) {
        return httpRequestContext(path, method, queryParams, headers, body, trailers, requestId, newSecurity);
    }

    public boolean isAuthenticated() {
        return security.isAuthenticated();
    }

    public boolean hasRole(Role role) {
        return security.hasRole(role);
    }

    public boolean hasRole(String roleName) {
        return security.hasRole(roleName);
    }
}
