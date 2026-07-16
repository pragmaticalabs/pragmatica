// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.MatchedRoute;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.http.ResponseSerializer;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.PathUtils;
import org.pragmatica.http.routing.RequestRouter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class ManagementRouter {
    private static final Logger log = LoggerFactory.getLogger(ManagementRouter.class);

    private final RequestRouter requestRouter;
    private final JsonCodec jsonCodec;
    private final Map<String, Route<?>> routesByName;

    private ManagementRouter(RequestRouter requestRouter, JsonCodec jsonCodec, Map<String, Route<?>> routesByName) {
        this.requestRouter = requestRouter;
        this.jsonCodec = jsonCodec;
        this.routesByName = routesByName;
    }

    public static ManagementRouter managementRouter(RouteSource... sources) {
        var allRoutes = java.util.stream.Stream.of(sources).flatMap(RouteSource::routes).toList();
        var byName = allRoutes.stream()
                              .filter(r -> !r.name()
                                             .isEmpty())
                              .collect(Collectors.toMap(Route::name,
                                                        r -> r,
                                                        (a, _) -> a));

        return new ManagementRouter(RequestRouter.with(sources), JsonCodecAdapter.defaultCodec(), Map.copyOf(byName));
    }

    public boolean handle(HttpRequest ctx, ResponseWriter response) {
        return parseMethod(ctx.method().name()).flatMap(method -> dispatch(method, ctx, response))
                          .or(false);
    }

    private Option<Boolean> dispatch(HttpMethod method, HttpRequest ctx, ResponseWriter response) {
        var matchResult = ManagementRoute.match(method, ctx.path());

        if (matchResult.isSuccess()) {
            var matched = matchResult.unwrap();
            var route = routesByName.get(matched.route().name());

            if (route != null) {
                log.trace("ManagementRoute matched: {} target={}",
                          matched.route().name(),
                          matched.route().target());
                handleRoute(route, ctx, response);

                return Option.some(true);
            }

            log.debug("ManagementRoute {} matched but no Route handler registered under that name",
                      matched.route().name());
        }

        return requestRouter.findRoute(method,
                                       ctx.path())
                            .map(route -> {
                                handleRoute(route, ctx, response);

                                return true;
                            });
    }

    private void handleRoute(Route<?> route, HttpRequest serverCtx, ResponseWriter response) {
        var routingCtx = adaptContext(serverCtx, route);

        route.handler()
             .handle(routingCtx)
             .onFailure(cause -> writeError(response, cause, serverCtx))
             .onSuccess(value -> writeSuccess(value,
                                              route.contentType(),
                                              response,
                                              serverCtx));
    }

    private void writeError(ResponseWriter response, org.pragmatica.lang.Cause cause, HttpRequest serverCtx) {
        ProblemResponses.writeProblem(response, cause, serverCtx.path(), serverCtx.requestId());
    }

    private void writeSuccess(Object value, ContentType contentType, ResponseWriter response, HttpRequest serverCtx) {
        if (value instanceof Option<?> opt && opt.isEmpty()) {
            response.noContent();

            return;
        }

        if (value instanceof String json && contentType.category() == ContentCategory.JSON) {
            response.write(HttpStatus.OK, json.getBytes(StandardCharsets.UTF_8), contentType);

            return;
        }

        ResponseSerializer.serialize(value, contentType, jsonCodec)
                          .onFailure(cause -> writeError(response, cause, serverCtx))
                          .onSuccess(bytes -> response.write(HttpStatus.OK, bytes, contentType));
    }

    private org.pragmatica.http.routing.RequestContext adaptContext(HttpRequest serverCtx, Route<?> route) {
        return ServerRequestContextAdapter.serverRequestContextAdapter(serverCtx, route, jsonCodec);
    }

    private static Option<HttpMethod> parseMethod(String method) {
        return Result.lift(org.pragmatica.lang.utils.Causes::fromThrowable,
                           () -> HttpMethod.valueOf(method.toUpperCase()))
                     .option();
    }

    private record ServerRequestContextAdapter(HttpRequest serverCtx,
                                               Route<?> route,
                                               JsonCodec jsonCodec,
                                               HttpHeaders responseHeaders,
                                               AtomicReference<List<String>> pathParamsRef) implements org.pragmatica.http.routing.RequestContext {
        static ServerRequestContextAdapter serverRequestContextAdapter(HttpRequest serverCtx,
                                                                       Route<?> route,
                                                                       JsonCodec jsonCodec) {
            return new ServerRequestContextAdapter(serverCtx,
                                                   route,
                                                   jsonCodec,
                                                   DefaultHttpHeadersFactory.headersFactory()
                                                                            .withCombiningHeaders(true)
                                                                            .newHeaders(),
                                                   new AtomicReference<>());
        }

        @Override
        public org.pragmatica.http.HttpMethod method() {
            return serverCtx.method();
        }

        @Override
        public String path() {
            return serverCtx.path();
        }

        @Override
        public String requestId() {
            return serverCtx.requestId();
        }

        @Override
        public byte[] body() {
            return serverCtx.body();
        }

        @Override
        public String bodyAsString() {
            return serverCtx.bodyAsString();
        }

        @Override
        public org.pragmatica.http.QueryParams queryParams() {
            return serverCtx.queryParams();
        }

        @Override
        public org.pragmatica.http.Headers headers() {
            return serverCtx.headers();
        }

        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            return jsonCodec.deserialize(serverCtx.body(), literal);
        }

        @Override
        public List<String> pathParams() {
            var params = pathParamsRef.get();

            if (params == null) {
                params = initPathParams();
                pathParamsRef.set(params);
            }

            return params;
        }

        @Override
        public HttpHeaders responseHeaders() {
            return responseHeaders;
        }

        private List<String> initPathParams() {
            var normalizedPath = PathUtils.normalize(serverCtx.path());
            var routePath = route.path();

            if (normalizedPath.length() <= routePath.length()) {
                return List.of();
            }

            var remainder = normalizedPath.substring(routePath.length());

            if (remainder.startsWith("/")) {
                remainder = remainder.substring(1);
            }

            if (remainder.isEmpty()) {
                return List.of();
            }

            var elements = remainder.split("/", 1024);

            if (elements[elements.length - 1].isEmpty()) {
                return List.of(elements).subList(0, elements.length - 1);
            }

            return List.of(elements);
        }
    }
}
