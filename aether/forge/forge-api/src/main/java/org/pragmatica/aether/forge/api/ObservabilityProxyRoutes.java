package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.ForgeCluster;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.Route.in;

/// Proxy routes for observability endpoints.
/// Forwards trace and depth configuration requests from the dashboard port to the leader's management port.
public sealed interface ObservabilityProxyRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    record TraceListResponse(String body) {}

    record TraceStatsResponse(String body) {}

    record DepthListResponse(String body) {}

    record DepthSetResponse(boolean success, String body) {}

    record DepthDeleteResponse(boolean success, String body) {}

    static RouteSource observabilityProxyRoutes(ForgeCluster cluster) {
        var http = JdkHttpOperations.jdkHttpOperations();
        return RouteSource.routeSource(in("/api/traces").serve(listTracesRoute(cluster, http),
                                                               traceStatsRoute(cluster, http),
                                                               traceByRequestIdRoute(cluster, http)),
                                       in("/api/observability").serve(listDepthRoute(cluster, http),
                                                                      setDepthRoute(cluster, http),
                                                                      deleteDepthRoute(cluster, http)));
    }

    // --- Trace routes ---
    private static Route<TraceListResponse> listTracesRoute(ForgeCluster cluster,
                                                            JdkHttpOperations http) {
        return Route.<TraceListResponse> get("")
                    .to(ctx -> proxyGetWithQuery(cluster,
                                                 http,
                                                 "/api/traces",
                                                 ctx.queryParams()))
                    .asJson();
    }

    private static Route<TraceStatsResponse> traceStatsRoute(ForgeCluster cluster,
                                                             JdkHttpOperations http) {
        return Route.<TraceStatsResponse> get("/stats")
                    .to(_ -> proxyGetStats(cluster, http))
                    .asJson();
    }

    private static Route<TraceListResponse> traceByRequestIdRoute(ForgeCluster cluster,
                                                                  JdkHttpOperations http) {
        return Route.<TraceListResponse> get("")
                    .withPath(aString())
                    .to(requestId -> proxyGetTraceById(cluster, http, requestId))
                    .asJson();
    }

    // --- Depth routes ---
    private static Route<DepthListResponse> listDepthRoute(ForgeCluster cluster,
                                                           JdkHttpOperations http) {
        return Route.<DepthListResponse> get("/depth")
                    .to(_ -> proxyGetDepth(cluster, http))
                    .asJson();
    }

    private static Route<DepthSetResponse> setDepthRoute(ForgeCluster cluster,
                                                         JdkHttpOperations http) {
        return Route.<DepthSetResponse> post("/depth")
                    .to(ctx -> proxyPostDepth(cluster,
                                              http,
                                              ctx.bodyAsString()))
                    .asJson();
    }

    private static Route<DepthDeleteResponse> deleteDepthRoute(ForgeCluster cluster,
                                                               JdkHttpOperations http) {
        return Route.<DepthDeleteResponse> delete("/depth")
                    .withPath(aString(),
                              aString())
                    .to((artifact, method) -> proxyDeleteDepth(cluster, http, artifact + "/" + method))
                    .asJson();
    }

    // --- Proxy helpers ---
    private static Promise<TraceListResponse> proxyGetWithQuery(ForgeCluster cluster,
                                                                JdkHttpOperations http,
                                                                String path,
                                                                Map<String, List<String>> queryParams) {
        var fullPath = buildPathWithQuery(path, queryParams);
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, fullPath))
                      .map(TraceListResponse::new);
    }

    private static Promise<TraceStatsResponse> proxyGetStats(ForgeCluster cluster,
                                                             JdkHttpOperations http) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/traces/stats"))
                      .map(TraceStatsResponse::new);
    }

    private static Promise<TraceListResponse> proxyGetTraceById(ForgeCluster cluster,
                                                                JdkHttpOperations http,
                                                                String requestId) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/traces/" + requestId))
                      .map(TraceListResponse::new);
    }

    private static Promise<DepthListResponse> proxyGetDepth(ForgeCluster cluster,
                                                            JdkHttpOperations http) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/observability/depth"))
                      .map(DepthListResponse::new);
    }

    private static Promise<DepthSetResponse> proxyPostDepth(ForgeCluster cluster,
                                                            JdkHttpOperations http,
                                                            String body) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendPostWithBody(http, port, "/api/observability/depth", body))
                      .map(resp -> new DepthSetResponse(true, resp));
    }

    private static Promise<DepthDeleteResponse> proxyDeleteDepth(ForgeCluster cluster,
                                                                 JdkHttpOperations http,
                                                                 String key) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendDelete(http, port, "/api/observability/depth/" + key))
                      .map(resp -> new DepthDeleteResponse(true, resp));
    }

    // --- Query string builder ---
    private static String buildPathWithQuery(String path, Map<String, List<String>> queryParams) {
        if (queryParams.isEmpty()) {
            return path;
        }
        var joiner = new StringJoiner("&", path + "?", "");
        queryParams.forEach((name, values) -> values.forEach(value -> joiner.add(name + "=" + value)));
        return joiner.toString();
    }

    // --- HTTP helpers ---
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

    private static Promise<String> sendPostWithBody(JdkHttpOperations http, int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body != null
                                                                           ? body
                                                                           : ""))
                                 .timeout(HTTP_TIMEOUT)
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> result.toResult()
                                            .async());
    }

    private static Promise<String> sendDelete(JdkHttpOperations http, int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
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
            return "No leader node available for observability proxy";
        }
    }

    record unused() implements ObservabilityProxyRoutes {}
}
