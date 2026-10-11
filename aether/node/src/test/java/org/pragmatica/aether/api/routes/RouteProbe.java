// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import io.netty.handler.codec.http.HttpHeaders;

import static org.assertj.core.api.Assertions.fail;


/// Drives a REAL registered route handler with a hand-built request and reports what the router would answer: the cause the
/// handler fails with, and the status `ProblemResponses.writeProblem` -- the exact call the router makes -- gives it.
final class RouteProbe {
    private static final String INSTANCE = "/api/v1/management";
    private static final String REQUEST_ID = "req-1";

    private RouteProbe() {}

    static Cause failureOf(Stream<Route<?>> routes, ManagementRoute which, List<String> pathParams, Map<String, List<String>> query) {
        var holder = new AtomicReference<Cause>();

        run(routes, which, pathParams, query).onSuccess(value -> fail("Route " + which.name() + " must fail, got: " + value))
                                             .onFailure(holder::set);

        return holder.get();
    }

    static Result<Object> run(Stream<Route<?>> routes, ManagementRoute which, List<String> pathParams, Map<String, List<String>> query) {
        var route = routes.filter(candidate -> candidate.name().equals(which.name())).findFirst().orElseThrow();
        var holder = new AtomicReference<Result<Object>>();

        route.handler().handle(new Request(pathParams, query, null, INSTANCE)).await().onResult(result -> holder.set(result.map(v -> (Object) v)));

        return holder.get();
    }

    static HttpStatus problemStatus(Cause cause) {
        var recorder = new Recorder();

        ProblemResponses.writeProblem(recorder, cause, INSTANCE, REQUEST_ID);

        return recorder.status.get();
    }

    private record Request(List<String> pathParams, Map<String, List<String>> query, Object requestBody, String path) implements RequestContext {
        @Override
        public QueryParams queryParams() {
            return QueryParams.queryParams(query);
        }

        @Override
        public Route<?> route() {
            throw new UnsupportedOperationException("route");
        }

        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            throw new UnsupportedOperationException("fromJson");
        }

        @Override
        public HttpHeaders responseHeaders() {
            throw new UnsupportedOperationException("responseHeaders");
        }

        @Override
        public String requestId() {
            return REQUEST_ID;
        }

        @Override
        public HttpMethod method() {
            throw new UnsupportedOperationException("method");
        }

        @Override
        public Headers headers() {
            throw new UnsupportedOperationException("headers");
        }

        @Override
        public byte[] body() {
            throw new UnsupportedOperationException("body");
        }
    }

    private static final class Recorder implements ResponseWriter {
        private final AtomicReference<HttpStatus> status = new AtomicReference<>();

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status.set(status);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }
    }
}
