// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.pragmatica.aether.http.adapter.impl.SliceRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.http.ProblemDetail;
import org.pragmatica.http.ResponseSerializer;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.RequestRouter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.RouteMounting;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersionSelectionError;
import org.pragmatica.http.routing.VersionSelector;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface SliceRouter {
    Logger log = LoggerFactory.getLogger(SliceRouter.class);
    Promise<HttpResponseData> handle(HttpRequestContext request);

    static SliceRouter sliceRouter(RouteSource routes, ErrorMapper errorMapper, JsonMapper jsonMapper) {
        return sliceRouter(routes, errorMapper, jsonMapper, RouteMountMode.pathMode());
    }

    static SliceRouter sliceRouter(RouteSource routes,
                                   ErrorMapper errorMapper,
                                   JsonMapper jsonMapper,
                                   RouteMountMode mountMode) {
        var composed = RouteMounting.compose(routes, mountMode);
        record sliceRouter(RequestRouter requestRouter,
                           SliceVersionRegistry versionRegistry,
                           RouteMountMode mountMode,
                           ErrorMapper errorMapper,
                           JsonMapper jsonMapper,
                           JsonCodec jsonCodec) implements SliceRouter {
            private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type",
                                                                           "application/json; charset=UTF-8");

            private static final Map<String, String> TEXT_HEADERS = Map.of("Content-Type", "text/plain; charset=UTF-8");

            @Override
            public Promise<HttpResponseData> handle(HttpRequestContext request) {
                return parseMethod(request.method()).map(method -> findAndHandleRoute(method, request))
                                  .or(() -> Promise.success(methodNotAllowed(request)));
            }

            private Promise<HttpResponseData> findAndHandleRoute(HttpMethod method, HttpRequestContext request) {
                return mountMode.isHeaderMode()
                       ? handleHeaderMode(method, request)
                       : handlePathMode(method, request);
            }

            private Promise<HttpResponseData> handlePathMode(HttpMethod method, HttpRequestContext request) {
                return requestRouter.findRoute(method, request.path())
                                    .map(route -> handleRoute(route, request))
                                    .or(() -> Promise.success(notFound(request)));
            }

            private Promise<HttpResponseData> handleHeaderMode(HttpMethod method, HttpRequestContext request) {
                // Unversioned slices are unaffected by header mode — route them with the full
                // spacer-aware path matcher, exactly as in path mode.
                if (!versionRegistry.isVersioned()) {
                    return handlePathMode(method, request);
                }

                var candidates = requestRouter.findCandidates(method, request.path());

                if (candidates.isEmpty()) {
                    return Promise.success(notFound(request));
                }

                return selectVersionedRoute(candidates, request).fold(cause -> Promise.success(versionError(cause,
                                                                                                            request)),
                                                                      route -> handleRoute(route, request));
            }

            private Result<Route<?>> selectVersionedRoute(List<Route<?>> candidates, HttpRequestContext request) {
                var byVersion = candidates.stream()
                                          .collect(Collectors.toMap(Route::version,
                                                                    route -> route,
                                                                    (first, _) -> first));
                var available = Set.copyOf(byVersion.keySet());
                var headerValue = headerValue(request);

                return VersionSelector.select(versionRegistry, available, mountMode.headerName(), headerValue)
                                      .map(byVersion::get);
            }

            private Option<String> headerValue(HttpRequestContext request) {
                return Option.option(request.headers().get(mountMode.headerName().toLowerCase()))
                             .filter(values -> !values.isEmpty())
                             .map(List::getFirst);
            }

            private HttpResponseData versionError(Cause cause, HttpRequestContext request) {
                var status = cause instanceof VersionSelectionError.MissingVersionHeader
                             ? HttpStatus.BAD_REQUEST
                             : HttpStatus.NOT_FOUND;

                return problemResponse(status, cause.message(), request);
            }

            private Promise<HttpResponseData> handleRoute(Route<?> route, HttpRequestContext request) {
                var context = SliceRequestContext.sliceRequestContext(request, route, jsonMapper);

                return invokeHandler(route, context).map(result -> resultToResponse(result,
                                                                                    route.contentType(),
                                                                                    request))
                                    .recover(cause -> errorToResponse(cause, request));
            }

            private HttpResponseData resultToResponse(Object result,
                                                      ContentType contentType,
                                                      HttpRequestContext request) {
                return switch (result) {
                    case Result.Success<?> success -> successToResponse(success.value(), contentType);
                    case Result.Failure<?> failure -> errorToResponse(failure.cause(), request);
                    default -> successToResponse(result, contentType);
                };
            }

            private <T> Promise<T> invokeHandler(Route<T> route, SliceRequestContext context) {
                return route.handler()
                            .handle(context);
            }

            private HttpResponseData successToResponse(Object value, ContentType contentType) {
                if (value == null) {
                    return HttpResponseData.httpResponseData(204);
                }

                var headers = headersForContentType(contentType);

                return ResponseSerializer.serialize(value, contentType, jsonCodec)
                                         .fold(_ -> HttpResponseData.httpResponseData(500, "Serialization failed"),
                                               body -> HttpResponseData.httpResponseData(200, headers, body));
            }

            private HttpResponseData errorToResponse(Cause cause, HttpRequestContext request) {
                var httpError = errorMapper.map(cause);

                log.warn("[requestId={}] SliceRouter error: {} {} -> {} {}",
                         request.requestId(),
                         request.method(),
                         request.path(),
                         httpError.status().code(),
                         cause.message());
                var problemDetail = ProblemDetail.fromHttpError(httpError, request.path(), request.requestId());

                return jsonMapper.writeAsBytes(problemDetail)
                                 .fold(_ -> plainErrorResponse(httpError.status(),
                                                               httpError.message()),
                                       body -> HttpResponseData.httpResponseData(httpError.status().code(),
                                                                                 JSON_HEADERS,
                                                                                 body));
            }

            private HttpResponseData notFound(HttpRequestContext request) {
                return problemResponse(HttpStatus.NOT_FOUND,
                                       "No route found for " + request.method() + " " + request.path(),
                                       request);
            }

            private HttpResponseData methodNotAllowed(HttpRequestContext request) {
                return problemResponse(HttpStatus.METHOD_NOT_ALLOWED,
                                       "Invalid HTTP method: " + request.method(),
                                       request);
            }

            private HttpResponseData problemResponse(HttpStatus status, String detail, HttpRequestContext request) {
                var problemDetail = ProblemDetail.problemDetail(status, detail, request.path(), request.requestId());

                return jsonMapper.writeAsBytes(problemDetail)
                                 .fold(_ -> plainErrorResponse(status, status.reasonPhrase()),
                                       body -> HttpResponseData.httpResponseData(status.code(), JSON_HEADERS, body));
            }

            private HttpResponseData plainErrorResponse(HttpStatus status, String message) {
                return HttpResponseData.httpResponseData(status.code(),
                                                         TEXT_HEADERS,
                                                         message.getBytes(StandardCharsets.UTF_8));
            }

            private static Option<HttpMethod> parseMethod(String method) {
                return HttpMethod.fromString(method);
            }

            private static Map<String, String> headersForContentType(ContentType contentType) {
                return Map.of("Content-Type", contentType.headerText());
            }
        }

        return new sliceRouter(RequestRouter.with(composed),
                               composed.versionRegistry(),
                               mountMode,
                               errorMapper,
                               jsonMapper,
                               JsonCodecAdapter.forMapper(jsonMapper));
    }
}
