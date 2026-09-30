// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.Route.in;


public sealed interface ObservabilityProxyRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    record TraceListResponse(String body) {}

    record TraceStatsResponse(String body) {}

    record DepthListResponse(String body) {}

    record DepthSetResponse(boolean success, String body) {}

    record DepthDeleteResponse(boolean success, String body) {}

    static RouteSource observabilityProxyRoutes(EmberCluster cluster, OperatorKey operatorKey) {
        var http = NodeHttp.nodeHttp(operatorKey);

        return RouteSource.routeSource(in("/api/traces").serve(listTracesRoute(cluster, http),
                                                               traceStatsRoute(cluster, http),
                                                               traceByRequestIdRoute(cluster, http)),
                                       in("/api/observability").serve(listDepthRoute(cluster, http),
                                                                      setDepthRoute(cluster, http),
                                                                      deleteDepthRoute(cluster, http)));
    }

    private static Route<TraceListResponse> listTracesRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<TraceListResponse> get("")
                    .to(ctx -> proxyGetWithQuery(cluster,
                                                 http,
                                                 "/api/v1/traces",
                                                 ctx.queryParams().asMap()))
                    .asJson();
    }

    private static Route<TraceStatsResponse> traceStatsRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<TraceStatsResponse> get("/stats")
                    .to(_ -> proxyGetStats(cluster, http))
                    .asJson();
    }

    private static Route<TraceListResponse> traceByRequestIdRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<TraceListResponse> get("")
                    .withPath(aString())
                    .to(requestId -> proxyGetTraceById(cluster, http, requestId))
                    .asJson();
    }

    private static Route<DepthListResponse> listDepthRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<DepthListResponse> get("/depth")
                    .to(_ -> proxyGetDepth(cluster, http))
                    .asJson();
    }

    private static Route<DepthSetResponse> setDepthRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<DepthSetResponse> post("/depth")
                    .to(ctx -> proxyPostDepth(cluster,
                                              http,
                                              ctx.bodyAsString()))
                    .asJson();
    }

    private static Route<DepthDeleteResponse> deleteDepthRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<DepthDeleteResponse> delete("/depth")
                    .withPath(aString(),
                              aString())
                    .to((artifact, method) -> proxyDeleteDepth(cluster, http, artifact + "/" + method))
                    .asJson();
    }

    private static Promise<TraceListResponse> proxyGetWithQuery(EmberCluster cluster,
                                                                NodeHttp http,
                                                                String path,
                                                                Map<String, List<String>> queryParams) {
        var fullPath = buildPathWithQuery(path, queryParams);

        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, fullPath))
                      .map(TraceListResponse::new);
    }

    private static Promise<TraceStatsResponse> proxyGetStats(EmberCluster cluster, NodeHttp http) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/v1/traces/stats"))
                      .map(TraceStatsResponse::new);
    }

    private static Promise<TraceListResponse> proxyGetTraceById(EmberCluster cluster, NodeHttp http, String requestId) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/v1/traces/" + requestId))
                      .map(TraceListResponse::new);
    }

    private static Promise<DepthListResponse> proxyGetDepth(EmberCluster cluster, NodeHttp http) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/v1/observability/depth"))
                      .map(DepthListResponse::new);
    }

    private static Promise<DepthSetResponse> proxyPostDepth(EmberCluster cluster, NodeHttp http, String body) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendPostWithBody(http, port, "/api/v1/observability/depth", body))
                      .map(resp -> new DepthSetResponse(true, resp));
    }

    private static Promise<DepthDeleteResponse> proxyDeleteDepth(EmberCluster cluster, NodeHttp http, String key) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendDelete(http, port, "/api/v1/observability/depth/" + key))
                      .map(resp -> new DepthDeleteResponse(true, resp));
    }

    private static String buildPathWithQuery(String path, Map<String, List<String>> queryParams) {
        if (queryParams.isEmpty()) {
            return path;
        }

        var joiner = new StringJoiner("&", path + "?", "");

        queryParams.forEach((name, values) -> values.forEach(value -> joiner.add(name + "=" + value)));

        return joiner.toString();
    }

    private static Promise<String> sendGet(NodeHttp http, int port, String path) {
        var request = http.request(port, path).GET().timeout(HTTP_TIMEOUT).build();

        return http.sendString(request)
                   .flatMap(result -> result.toResult()
                                            .async());
    }

    private static Promise<String> sendPostWithBody(NodeHttp http, int port, String path, String body) {
        var request = http.request(port, path)
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofString(Option.option(body).or("")))
                          .timeout(HTTP_TIMEOUT)
                          .build();

        return http.sendString(request)
                   .flatMap(result -> result.toResult()
                                            .async());
    }

    private static Promise<String> sendDelete(NodeHttp http, int port, String path) {
        var request = http.request(port, path).DELETE().timeout(HTTP_TIMEOUT).build();

        return http.sendString(request)
                   .flatMap(result -> result.toResult()
                                            .async());
    }

    enum LeaderNotAvailable implements Cause {
        INSTANCE;
        @Override
        public String message() {
            return "No leader node available for observability proxy";
        }
    }

    record unused() implements ObservabilityProxyRoutes {}
}
