// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.http.routing.QueryParameter.aString;
import static org.pragmatica.http.routing.Route.in;


public sealed interface MetricsProxyRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    record HistoryResponse(String body) {}

    static RouteSource metricsProxyRoutes(EmberCluster cluster) {
        var http = JdkHttpOperations.jdkHttpOperations();

        return in("/api/metrics").serve(historyRoute(cluster, http));
    }

    private static Route<HistoryResponse> historyRoute(EmberCluster cluster, JdkHttpOperations http) {
        return Route.<HistoryResponse> get("/history")
                    .withQuery(aString("range"))
                    .to(range -> proxyHistory(cluster, http, range))
                    .asJson();
    }

    private static Promise<HistoryResponse> proxyHistory(EmberCluster cluster,
                                                         JdkHttpOperations http,
                                                         Option<String> range) {
        var rangeParam = range.or("1h");

        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/metrics/history?range=" + rangeParam))
                      .map(HistoryResponse::new);
    }

    private static Promise<String> sendGet(JdkHttpOperations http, int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(HTTP_TIMEOUT)
                                 .build();

        return http.sendString(request)
                   .flatMap(result -> result.toResult()
                                            .async());
    }

    enum LeaderNotAvailable implements Cause {
        INSTANCE;
        @Override
        public String message() {
            return "No leader node available for metrics proxy";
        }
    }

    record unused() implements MetricsProxyRoutes {}
}
