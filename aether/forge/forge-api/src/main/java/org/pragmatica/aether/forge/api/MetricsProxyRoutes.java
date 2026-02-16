package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.ForgeCluster;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.pragmatica.http.routing.QueryParameter.aString;
import static org.pragmatica.http.routing.Route.in;

/// Proxy routes for metrics history endpoint.
/// Forwards requests from the dashboard port to the leader's management port.
public sealed interface MetricsProxyRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    record HistoryResponse(String body) {}

    static RouteSource metricsProxyRoutes(ForgeCluster cluster) {
        var http = JdkHttpOperations.jdkHttpOperations();
        return in("/api/metrics").serve(historyRoute(cluster, http));
    }

    private static Route<HistoryResponse> historyRoute(ForgeCluster cluster,
                                                       JdkHttpOperations http) {
        return Route.<HistoryResponse> get("/history")
                    .withQuery(aString("range"))
                    .to(range -> proxyHistory(cluster, http, range))
                    .asJson();
    }

    private static Promise<HistoryResponse> proxyHistory(ForgeCluster cluster,
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
