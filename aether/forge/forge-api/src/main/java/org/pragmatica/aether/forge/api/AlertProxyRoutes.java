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

import static org.pragmatica.http.routing.Route.in;

/// Proxy routes for alert endpoints.
/// Forwards alert requests from the dashboard port to the leader's management port.
public sealed interface AlertProxyRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    record AlertListResponse(String body) {}

    record AlertClearResponse(boolean success, String body) {}

    static RouteSource alertProxyRoutes(ForgeCluster cluster) {
        var http = JdkHttpOperations.jdkHttpOperations();
        return in("/api/alerts")
        .serve(activeAlertsRoute(cluster, http),
               alertHistoryRoute(cluster, http),
               clearAlertsRoute(cluster, http));
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

    enum LeaderNotAvailable implements Cause {
        INSTANCE;
        @Override
        public String message() {
            return "No leader node available for alert proxy";
        }
    }

    record unused() implements AlertProxyRoutes {}
}
