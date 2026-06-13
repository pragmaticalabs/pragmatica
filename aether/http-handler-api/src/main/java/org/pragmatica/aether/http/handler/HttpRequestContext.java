// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.pragmatica.lang.Result.success;


@Codec
public record HttpRequestContext(String path,
                                 String method,
                                 Map<String, List<String>> queryParams,
                                 Map<String, List<String>> headers,
                                 byte[] body,
                                 String requestId,
                                 SecurityContext security) {
    private static final byte[] EMPTY_BODY = new byte[0];

    public HttpRequestContext {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(queryParams, "queryParams");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(security, "security");
        if (body == null || body.length == 0) {
            body = EMPTY_BODY;
        }
    }

    public static Result<HttpRequestContext> httpRequestContext(Result<String> path,
                                                                Result<String> method,
                                                                Result<Map<String, List<String>>> queryParams,
                                                                Result<Map<String, List<String>>> headers,
                                                                Result<byte[]> body,
                                                                Result<String> requestId,
                                                                Result<SecurityContext> security) {
        return Result.all(path, method, queryParams, headers, body, requestId, security).map(HttpRequestContext::new);
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
        return Result.all(success(path),
                          success(method),
                          success(queryParams),
                          success(headers),
                          success(body),
                          success(requestId),
                          success(security)).map(HttpRequestContext::new)
                         .unwrap();
    }

    public HttpRequestContext withSecurity(SecurityContext newSecurity) {
        return httpRequestContext(path, method, queryParams, headers, body, requestId, newSecurity);
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
