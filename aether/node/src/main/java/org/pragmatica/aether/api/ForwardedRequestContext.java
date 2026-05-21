// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.RequestContext;


record ForwardedRequestContext(HttpRequestContext source,
                               HttpMethod httpMethod,
                               Headers headers,
                               QueryParams queryParams) implements RequestContext {
    static ForwardedRequestContext forwardedRequestContext(HttpRequestContext source) {
        return new ForwardedRequestContext(source,
                                           HttpMethod.valueOf(source.method().toUpperCase()),
                                           Headers.headers(source.headers()),
                                           QueryParams.queryParams(source.queryParams()));
    }

    @Override
    public String requestId() {
        return source.requestId();
    }

    @Override
    public HttpMethod method() {
        return httpMethod;
    }

    @Override
    public String path() {
        return source.path();
    }

    @Override
    public Headers headers() {
        return headers;
    }

    @Override
    public QueryParams queryParams() {
        return queryParams;
    }

    @Override
    public byte[] body() {
        return source.body();
    }
}
