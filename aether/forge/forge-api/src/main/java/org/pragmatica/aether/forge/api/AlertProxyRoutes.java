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

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.Route.in;

/// Proxy routes for alert endpoints.
/// Forwards alert requests from the dashboard port to the leader's management port.
public sealed interface AlertProxyRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    record AlertListResponse(String body) {}

    record AlertClearResponse(boolean success, String body) {}

    record ThresholdListResponse(String body) {}

    record ThresholdSetResponse(boolean success, String body) {}

    record ThresholdDeleteResponse(boolean success, String body) {}

    static RouteSource alertProxyRoutes(ForgeCluster cluster) {
        var http = JdkHttpOperations.jdkHttpOperations();
        return in("/api/alerts")
        .serve(activeAlertsRoute(cluster, http),
               alertHistoryRoute(cluster, http),
               clearAlertsRoute(cluster, http),
               thresholdsGetRoute(cluster, http),
               thresholdsSetRoute(cluster, http),
               thresholdsDeleteRoute(cluster, http));
    }

    private static Route<AlertListResponse> activeAlertsRoute(ForgeCluster cluster,
                                                               JdkHttpOperations http) {
        return Route.<AlertListResponse> get("/active")
                    .to(_ -> proxyGet(cluster, http, "/api/alerts/active"))
                    .asJson();
    }

    private static Route<AlertListResponse> alertHistoryRoute(ForgeCluster cluster,
                                                               JdkHttpOperations http) {
        return Route.<AlertListResponse> get("/history")
                    .to(_ -> proxyGet(cluster, http, "/api/alerts/history"))
                    .asJson();
    }

    private static Route<AlertClearResponse> clearAlertsRoute(ForgeCluster cluster,
                                                               JdkHttpOperations http) {
        return Route.<AlertClearResponse> post("/clear")
                    .toJson(_ -> proxyClear(cluster, http));
    }

    private static Promise<AlertListResponse> proxyGet(ForgeCluster cluster,
                                                        JdkHttpOperations http,
                                                        String path) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, path))
                      .map(AlertListResponse::new);
    }

    private static Promise<AlertClearResponse> proxyClear(ForgeCluster cluster,
                                                           JdkHttpOperations http) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendPost(http, port, "/api/alerts/clear"))
                      .map(body -> new AlertClearResponse(true, body));
    }

    private static Route<ThresholdListResponse> thresholdsGetRoute(ForgeCluster cluster,
                                                                    JdkHttpOperations http) {
        return Route.<ThresholdListResponse> get("/thresholds")
                    .to(_ -> proxyGetThresholds(cluster, http))
                    .asJson();
    }

    private static Route<ThresholdSetResponse> thresholdsSetRoute(ForgeCluster cluster,
                                                                   JdkHttpOperations http) {
        return Route.<ThresholdSetResponse> post("/thresholds")
                    .to(request -> proxySetThreshold(cluster, http, request.bodyAsString()))
                    .asJson();
    }

    private static Route<ThresholdDeleteResponse> thresholdsDeleteRoute(ForgeCluster cluster,
                                                                        JdkHttpOperations http) {
        return Route.<ThresholdDeleteResponse> delete("/thresholds")
                    .withPath(aString())
                    .to(metric -> proxyDeleteThreshold(cluster, http, metric))
                    .asJson();
    }

    private static Promise<ThresholdListResponse> proxyGetThresholds(ForgeCluster cluster,
                                                                      JdkHttpOperations http) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, "/api/thresholds"))
                      .map(ThresholdListResponse::new);
    }

    private static Promise<ThresholdSetResponse> proxySetThreshold(ForgeCluster cluster,
                                                                    JdkHttpOperations http,
                                                                    String body) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendPostWithBody(http, port, "/api/thresholds", body))
                      .map(resp -> new ThresholdSetResponse(true, resp));
    }

    private static Promise<ThresholdDeleteResponse> proxyDeleteThreshold(ForgeCluster cluster,
                                                                          JdkHttpOperations http,
                                                                          String metric) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendDelete(http, port, "/api/thresholds/" + metric))
                      .map(resp -> new ThresholdDeleteResponse(true, resp));
    }

    private static Promise<String> sendGet(JdkHttpOperations http, int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(HTTP_TIMEOUT)
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> result.toResult().async());
    }

    private static Promise<String> sendPost(JdkHttpOperations http, int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.noBody())
                                 .timeout(HTTP_TIMEOUT)
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> result.toResult().async());
    }

    private static Promise<String> sendPostWithBody(JdkHttpOperations http, int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""))
                                 .timeout(HTTP_TIMEOUT)
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> result.toResult().async());
    }

    private static Promise<String> sendDelete(JdkHttpOperations http, int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(HTTP_TIMEOUT)
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> result.toResult().async());
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
