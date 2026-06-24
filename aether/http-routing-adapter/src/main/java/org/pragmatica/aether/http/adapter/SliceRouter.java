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
import org.pragmatica.http.routing.VersionResponseHeaders;
import org.pragmatica.http.routing.VersionSelectionError;
import org.pragmatica.http.routing.VersionSelector;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface SliceRouter {
    Logger log = LoggerFactory.getLogger(SliceRouter.class);
    Promise<HttpResponseData> handle(HttpRequestContext request);

    /// The slice's API version registry (#198 §6.4), exposed for runtime introspection
    /// (`GET /api/versions`) and for the deprecation/sunset/successor header policy (§8.2).
    SliceVersionRegistry versionRegistry();

    /// Return a router that emits #198 §11.1 versioning metrics under `sliceName` to `sink`, sharing
    /// this router's composed routes and registry. The publisher calls this once per deployed slice
    /// so the metrics backend (Micrometer) stays out of the generated factory; the default no-op sink
    /// keeps unobserved routers (tests, unversioned wiring) zero-cost.
    ///
    /// @param sliceName the slice's identity used as the `slice` metric tag
    /// @param sink      the versioning metrics sink
    /// @return an observability-wired router
    SliceRouter withObservability(String sliceName, VersioningMetricsSink sink);

    static SliceRouter sliceRouter(RouteSource routes, ErrorMapper errorMapper, JsonMapper jsonMapper) {
        return sliceRouter(routes, errorMapper, jsonMapper, RouteMountMode.pathMode());
    }

    static SliceRouter sliceRouter(RouteSource routes,
                                   ErrorMapper errorMapper,
                                   JsonMapper jsonMapper,
                                   RouteMountMode mountMode) {
        var composed = RouteMounting.compose(routes, mountMode);

        record sliceRouter(RequestRouter requestRouter,
                           Map<Integer, RequestRouter> versionedRouters,
                           SliceVersionRegistry versionRegistry,
                           RouteMountMode mountMode,
                           ErrorMapper errorMapper,
                           JsonMapper jsonMapper,
                           JsonCodec jsonCodec,
                           String sliceName,
                           VersioningMetricsSink metricsSink) implements SliceRouter {
            private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type",
                                                                           "application/json; charset=UTF-8");

            private static final Map<String, String> TEXT_HEADERS = Map.of("Content-Type", "text/plain; charset=UTF-8");

            @Override
            public SliceRouter withObservability(String sliceName, VersioningMetricsSink sink) {
                return new sliceRouter(requestRouter,
                                       versionedRouters,
                                       versionRegistry,
                                       mountMode,
                                       errorMapper,
                                       jsonMapper,
                                       jsonCodec,
                                       sliceName,
                                       sink);
            }

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
                return requestRouter.findRoute(method,
                                               request.path()).map(route -> handleVersionedRoute(route, request))
                                              .or(() -> Promise.success(notFound(request)));
            }

            private Promise<HttpResponseData> handleHeaderMode(HttpMethod method, HttpRequestContext request) {
                // Unversioned slices are unaffected by header mode — route them with the full
                // spacer-aware path matcher, exactly as in path mode.
                if (!versionRegistry.isVersioned()) {
                    return handlePathMode(method, request);
                }

                return dispatchHeaderMode(method, request);
            }

            /// Header-mode dispatch (#198 §7), with version factored OUT of route matching: each
            /// declared version owns a dedicated [RequestRouter] built from that version's routes at
            /// their bare paths. The version is selected by [VersionSelector] from the set of versions
            /// that actually have a matching route for `(method, barePath)`; the chosen version's
            /// router then performs the SAME arity+spacer-aware selection used in path mode. This keeps
            /// path-shape selection (collection vs get vs nested spacer) independent of version
            /// selection, so every shape of every version is reachable.
            private Promise<HttpResponseData> dispatchHeaderMode(HttpMethod method, HttpRequestContext request) {
                var barePath = request.path();
                var available = availableVersions(method, barePath);

                if (available.isEmpty()) {
                    return Promise.success(notFound(request));
                }
                // #198 §11.1: a header-mode request to a routable bare path that omitted the version
                // header is observable here (the absent-header branch) whether the registry resolves
                // it via fallback or rejects it — emit the missing-header counter, mirroring the prior
                // dispatcher which fired it only after a matching route for the path was found.
                if (headerValue(request).isEmpty()) {
                    metricsSink.missingVersionHeader(sliceName);
                }

                return VersionSelector.select(versionRegistry,
                                              available,
                                              mountMode.headerName(),
                                              headerValue(request))
                                      .fold(cause -> Promise.success(versionError(cause, request)),
                                            version -> dispatchToVersion(version, method, request));
            }

            /// The versions whose dedicated router has a matching route for `(method, barePath)`.
            private Set<Integer> availableVersions(HttpMethod method, String barePath) {
                return versionedRouters.entrySet()
                                       .stream()
                                       .filter(entry -> entry.getValue()
                                                             .findRoute(method, barePath)
                                                             .isPresent())
                                       .map(Map.Entry::getKey)
                                       .collect(Collectors.toUnmodifiableSet());
            }

            /// Delegate to the chosen version's router for the final arity+spacer-aware route match.
            private Promise<HttpResponseData> dispatchToVersion(int version,
                                                                HttpMethod method,
                                                                HttpRequestContext request) {
                return Option.option(versionedRouters.get(version))
                             .flatMap(router -> router.findRoute(method,
                                                                 request.path()))
                             .map(route -> handleVersionedRoute(route, request))
                             .or(() -> Promise.success(notFound(request)));
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

            private Promise<HttpResponseData> handleVersionedRoute(Route<?> route, HttpRequestContext request) {
                return handleRoute(route, request).map(response -> decorateVersioned(route, request, response));
            }

            /// #198 §8.2 + §11.1: enrich a versioned route's response with deprecation/sunset/successor
            /// headers and emit the versioned + (when applicable) deprecated request counters. Bypassed
            /// for unversioned routes (`version == 0`) so unversioned slices are untouched.
            private HttpResponseData decorateVersioned(Route<?> route,
                                                       HttpRequestContext request,
                                                       HttpResponseData response) {
                if (route.version() == 0) {
                    return response;
                }

                emitVersionMetrics(route.version(), request.method(), response.statusCode());

                return withLifecycleHeaders(route.version(), request.path(), response);
            }

            @Contract
            private void emitVersionMetrics(int version, String method, int status) {
                metricsSink.versionedRequest(sliceName, version, method, status);
                if (isDeprecated(version)) {
                    metricsSink.deprecatedRequest(sliceName, version);
                }
            }

            private boolean isDeprecated(int version) {
                return versionRegistry.versions()
                                      .stream()
                                      .anyMatch(info -> info.version() == version && info.deprecated());
            }

            private HttpResponseData withLifecycleHeaders(int version, String requestPath, HttpResponseData response) {
                var lifecycleHeaders = VersionResponseHeaders.headers(versionRegistry, version, requestPath);

                return lifecycleHeaders.isEmpty()
                       ? response
                       : new HttpResponseData(response.statusCode(),
                                              mergeHeaders(response.headers(), lifecycleHeaders),
                                              response.body());
            }

            private static Map<String, String> mergeHeaders(Map<String, String> base, Map<String, String> extra) {
                var merged = new HashMap<>(base);

                merged.putAll(extra);

                return Map.copyOf(merged);
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

                return ResponseSerializer.serialize(value, contentType, jsonCodec).fold(_ -> HttpResponseData.httpResponseData(500,
                                                                                                                               "Serialization failed"),
                                                                                        body -> HttpResponseData.httpResponseData(200,
                                                                                                                                  headers,
                                                                                                                                  body));
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
                                 .fold(_ -> plainErrorResponse(status,
                                                               status.reasonPhrase()),
                                       body -> HttpResponseData.httpResponseData(status.code(),
                                                                                 JSON_HEADERS,
                                                                                 body));
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
        var composedRoutes = composed.routes().toList();
        var versionRegistry = composed.versionRegistry();
        var versionedRouters = mountMode.isHeaderMode() && versionRegistry.isVersioned()
                               ? perVersionRouters(composedRoutes)
                               : Map.<Integer, RequestRouter> of();

        return new sliceRouter(RequestRouter.with(routeSourceOf(composedRoutes)),
                               versionedRouters,
                               versionRegistry,
                               mountMode,
                               errorMapper,
                               jsonMapper,
                               JsonCodecAdapter.forMapper(jsonMapper),
                               "",
                               VersioningMetricsSink.noop());
    }

    /// Partition a slice's composed (bare-path, header-mode) routes by [Route#version()] into one
    /// [RequestRouter] per version (#198 §7). Each version's router owns only that version's routes,
    /// so version selection and path-shape (arity/spacer) selection are fully independent: the
    /// dispatcher first picks a version, then asks that version's router to match the path with the
    /// same deterministic logic used in path mode.
    private static Map<Integer, RequestRouter> perVersionRouters(List<Route<?>> routes) {
        return routes.stream()
                     .collect(Collectors.groupingBy(Route::version))
                     .entrySet()
                     .stream()
                     .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                           entry -> RequestRouter.with(routeSourceOf(entry.getValue()))));
    }

    /// Wrap an already-composed route list as a [RouteSource] for [RequestRouter#with], so a
    /// materialized partition can be fed to the router without re-running composition.
    private static RouteSource routeSourceOf(List<Route<?>> routes) {
        return routes::stream;
    }
}
