// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter.impl;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.MultipartParser;
import org.pragmatica.http.routing.MultipartRequest;
import org.pragmatica.http.routing.PathUtils;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;
import java.util.function.Supplier;

import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.HttpHeaders;

import static org.pragmatica.http.routing.Utils.lazy;
import static org.pragmatica.http.routing.Utils.value;


public final class SliceRequestContext implements RequestContext {
    private static final int PATH_PARAM_LIMIT = 1024;

    private final HttpRequestContext httpContext;
    private final Route<?> route;
    private final JsonMapper jsonMapper;
    private final HttpHeaders responseHeaders;

    private Supplier<List<String>> pathParamsSupplier = lazy(() -> pathParamsSupplier = value(initPathParams()));

    private Supplier<Result<MultipartRequest>> multipartSupplier = lazy(() -> multipartSupplier = value(initMultipart()));

    private SliceRequestContext(HttpRequestContext httpContext, Route<?> route, JsonMapper jsonMapper) {
        this.httpContext = httpContext;
        this.route = route;
        this.jsonMapper = jsonMapper;
        this.responseHeaders = DefaultHttpHeadersFactory.headersFactory().withCombiningHeaders(true).newHeaders();
    }

    public static SliceRequestContext sliceRequestContext(HttpRequestContext httpContext,
                                                          Route<?> route,
                                                          JsonMapper jsonMapper) {
        return new SliceRequestContext(httpContext, route, jsonMapper);
    }

    public HttpRequestContext original() {
        return httpContext;
    }

    public SecurityContext security() {
        return httpContext.security();
    }

    @Override
    public Route<?> route() {
        return route;
    }

    @Override
    public HttpMethod method() {
        return HttpMethod.httpMethod(httpContext.method()).or(HttpMethod.GET);
    }

    @Override
    public String path() {
        return httpContext.path();
    }

    @Override
    public String requestId() {
        return httpContext.requestId();
    }

    @Override
    public byte[] body() {
        return httpContext.body();
    }

    @Override
    public <T> Result<T> fromJson(TypeToken<T> literal) {
        return jsonMapper.readBytes(httpContext.body(), literal);
    }

    @Override
    public List<String> pathParams() {
        return pathParamsSupplier.get();
    }

    @Override
    public QueryParams queryParams() {
        return QueryParams.queryParams(httpContext.queryParams());
    }

    @Override
    public Headers headers() {
        return Headers.headers(httpContext.headers());
    }

    @Override
    public HttpHeaders responseHeaders() {
        return responseHeaders;
    }

    @Override
    public Result<MultipartRequest> multipartRequest() {
        return multipartSupplier.get();
    }

    @Override
    public boolean isMultipart() {
        return MultipartParser.isMultipart(headers().get("content-type"));
    }

    private Result<MultipartRequest> initMultipart() {
        return MultipartParser.parse(httpContext.body(), httpContext.headers(), httpContext.path());
    }

    private List<String> initPathParams() {
        var normalizedPath = PathUtils.normalize(httpContext.path());
        var routePath = route.path();

        if (normalizedPath.length() <= routePath.length()) {
            return List.of();
        }

        var remainder = normalizedPath.substring(routePath.length());
        var elements = remainder.split("/", PATH_PARAM_LIMIT);

        if (elements.length == 0) {
            return List.of();
        }

        if (elements[elements.length - 1].isEmpty()) {
            return List.of(elements).subList(0, elements.length - 1);
        }

        return List.of(elements);
    }
}
