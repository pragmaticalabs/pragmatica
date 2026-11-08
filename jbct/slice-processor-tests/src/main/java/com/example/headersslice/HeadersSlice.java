// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.headersslice;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.http.routing.MultipartRequest;
import org.pragmatica.lang.Promise;


/// Test slice for #2879: a slice method takes [RequestHeaders] beside every route shape's bound values (none, path,
/// query, body, and their merges, multipart) and beside a Principal; the parameter may stand anywhere in the list.
/// Every method answers with an [Echo] of what reached it, so a runtime test can tell the request's headers from the
/// route-bound value they arrived with.
@Slice
public interface HeadersSlice {
    Promise<Echo> none(RequestHeaders headers);
    Promise<Echo> byPath(RequestHeaders headers, ByPath request);
    Promise<Echo> byQuery(RequestHeaders headers, ByQuery request);
    Promise<Echo> byBody(RequestHeaders headers, ByBody request);
    Promise<Echo> pathBody(RequestHeaders headers, PathBody request);
    Promise<Echo> queryBody(RequestHeaders headers, QueryBody request);
    Promise<Echo> pathQuery(RequestHeaders headers, PathQuery request);
    Promise<Echo> pathQueryBody(RequestHeaders headers, PathQueryBody request);
    Promise<Echo> upload(RequestHeaders headers, MultipartRequest request);
    // Headers declared AFTER the request record: position in the list is not significant.
    Promise<Echo> ordered(ByBody request, RequestHeaders headers);
    // Beside a Principal, which is still read from the security context.
    Promise<Echo> secured(Principal principal, RequestHeaders headers, ByBody request);
    // Headers and the Principal only: no request record at all.
    Promise<Echo> whoami(Principal principal, RequestHeaders headers);

    static HeadersSlice headersSlice() {
        return new HeadersSlice() {
            @Override
            public Promise<Echo> none(RequestHeaders headers) {
                return Promise.success(Echo.echo(headers, "none"));
            }

            @Override
            public Promise<Echo> byPath(RequestHeaders headers, ByPath request) {
                return Promise.success(Echo.echo(headers, "path=" + request.id()));
            }

            @Override
            public Promise<Echo> byQuery(RequestHeaders headers, ByQuery request) {
                return Promise.success(Echo.echo(headers, "name=" + request.name().or("absent")));
            }

            @Override
            public Promise<Echo> byBody(RequestHeaders headers, ByBody request) {
                return Promise.success(Echo.echo(headers, "text=" + request.text()));
            }

            @Override
            public Promise<Echo> pathBody(RequestHeaders headers, PathBody request) {
                return Promise.success(Echo.echo(headers, "path=" + request.id() + ",text=" + request.text()));
            }

            @Override
            public Promise<Echo> queryBody(RequestHeaders headers, QueryBody request) {
                return Promise.success(Echo.echo(headers, "tag=" + request.tag().or("absent") + ",text=" + request.text()));
            }

            @Override
            public Promise<Echo> pathQuery(RequestHeaders headers, PathQuery request) {
                return Promise.success(Echo.echo(headers, "path=" + request.id() + ",tag=" + request.tag().or("absent")));
            }

            @Override
            public Promise<Echo> pathQueryBody(RequestHeaders headers, PathQueryBody request) {
                return Promise.success(Echo.echo(headers,
                                                 "path=" + request.id() + ",tag=" + request.tag().or("absent")
                                                 + ",text=" + request.text()));
            }

            @Override
            public Promise<Echo> upload(RequestHeaders headers, MultipartRequest request) {
                return Promise.success(Echo.echo(headers, "fields=" + request.fields().size()));
            }

            @Override
            public Promise<Echo> ordered(ByBody request, RequestHeaders headers) {
                return Promise.success(Echo.echo(headers, "text=" + request.text()));
            }

            @Override
            public Promise<Echo> secured(Principal principal, RequestHeaders headers, ByBody request) {
                return Promise.success(Echo.echo(headers, "principal=" + principal.value() + ",text=" + request.text()));
            }

            @Override
            public Promise<Echo> whoami(Principal principal, RequestHeaders headers) {
                return Promise.success(Echo.echo(headers, "principal=" + principal.value()));
            }
        };
    }
}
