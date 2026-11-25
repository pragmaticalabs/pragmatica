// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.lang.Option;


record ForwardedRequestContext(HttpRequestContext source,
                               HttpMethod httpMethod,
                               Headers headers,
                               QueryParams queryParams,
                               Headers trailers) implements HttpRequest {
    /// Empty when the forwarded method names no [HttpMethod] (a mixed-version peer or a malformed frame): the receiver
    /// refuses it with an error reply instead of throwing out of the dispatch (#3550).
    static Option<ForwardedRequestContext> forwardedRequestContext(HttpRequestContext source) {
        return HttpMethod.fromString(source.method()).map(method -> forwardedRequestContext(source, method));
    }

    private static ForwardedRequestContext forwardedRequestContext(HttpRequestContext source, HttpMethod method) {
        return new ForwardedRequestContext(source,
                                           method,
                                           Headers.headers(source.headers()),
                                           QueryParams.queryParams(source.queryParams()),
                                           Headers.trailers(source.trailers()));
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

    /// #2310: the trailer fields the forwarded request carried.
    @Override
    public Headers trailers() {
        return trailers;
    }

    @Override
    public byte[] body() {
        return source.body();
    }
}
