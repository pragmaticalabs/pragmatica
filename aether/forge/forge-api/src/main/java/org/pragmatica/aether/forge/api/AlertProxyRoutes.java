// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import java.net.http.HttpRequest;
import java.time.Duration;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.Route.in;


public sealed interface AlertProxyRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    record AlertListResponse(String body) {}

    record ThresholdListResponse(String body) {}

    record ThresholdSetResponse(boolean success, String body) {}

    record ThresholdDeleteResponse(boolean success, String body) {}

    static RouteSource alertProxyRoutes(EmberCluster cluster, OperatorKey operatorKey) {
        var http = NodeHttp.nodeHttp(operatorKey);

        return in("/api/alerts").serve(activeAlertsRoute(cluster, http),
                                       alertHistoryRoute(cluster, http),
                                       thresholdsGetRoute(cluster, http),
                                       thresholdsSetRoute(cluster, http),
                                       thresholdsDeleteRoute(cluster, http));
    }

    private static Route<AlertListResponse> activeAlertsRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<AlertListResponse> get("/active")
                    .to(_ -> proxyGet(cluster, http, "/api/v1/alerts/active"))
                    .asJson();
    }

    private static Route<AlertListResponse> alertHistoryRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<AlertListResponse> get("/history")
                    .to(_ -> proxyGet(cluster, http, "/api/v1/alerts/history"))
                    .asJson();
    }

    private static Promise<AlertListResponse> proxyGet(EmberCluster cluster, NodeHttp http, String path) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, path))
                      .map(AlertListResponse::new);
    }

    private static Route<ThresholdListResponse> thresholdsGetRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<ThresholdListResponse> get("/thresholds")
                    .to(_ -> proxyGetThresholds(cluster, http))
                    .asJson();
    }

    private static Route<ThresholdSetResponse> thresholdsSetRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<ThresholdSetResponse> post("/thresholds")
                    .to(request -> proxySetThreshold(cluster,
                                                     http,
                                                     request.bodyAsString()))
                    .asJson();
    }

    private static Route<ThresholdDeleteResponse> thresholdsDeleteRoute(EmberCluster cluster, NodeHttp http) {
        return Route.<ThresholdDeleteResponse> delete("/thresholds")
                    .withPath(aString())
                    .to(metric -> proxyDeleteThreshold(cluster, http, metric))
                    .asJson();
    }

    private static Promise<ThresholdListResponse> proxyGetThresholds(EmberCluster cluster, NodeHttp http) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/v1/thresholds"))
                      .map(ThresholdListResponse::new);
    }

    private static Promise<ThresholdSetResponse> proxySetThreshold(EmberCluster cluster, NodeHttp http, String body) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendPostWithBody(http, port, "/api/v1/thresholds", body))
                      .map(resp -> new ThresholdSetResponse(true, resp));
    }

    private static Promise<ThresholdDeleteResponse> proxyDeleteThreshold(EmberCluster cluster,
                                                                         NodeHttp http,
                                                                         String metric) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendDelete(http, port, "/api/v1/thresholds/" + metric))
                      .map(resp -> new ThresholdDeleteResponse(true, resp));
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
            return "No leader node available for alert proxy";
        }
    }

    record unused() implements AlertProxyRoutes {}
}
