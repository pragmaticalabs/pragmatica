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

/// Proxy routes for dynamic aspect endpoints.
/// Forwards aspect requests from the dashboard port to the leader's management port.
public sealed interface AspectProxyRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    record AspectListResponse(String body) {}

    record AspectSetResponse(boolean success, String body) {}

    record AspectDeleteResponse(boolean success, String body) {}

    static RouteSource aspectProxyRoutes(ForgeCluster cluster) {
        var http = JdkHttpOperations.jdkHttpOperations();
        return in("/api/aspects")
        .serve(listAspectsRoute(cluster, http),
               setAspectRoute(cluster, http),
               deleteAspectRoute(cluster, http));
    }

    private static Route<AspectListResponse> listAspectsRoute(ForgeCluster cluster,
                                                               JdkHttpOperations http) {
        return Route.<AspectListResponse> get("")
                    .to(_ -> proxyGet(cluster, http, "/api/aspects"))
                    .asJson();
    }

    private static Route<AspectSetResponse> setAspectRoute(ForgeCluster cluster,
                                                             JdkHttpOperations http) {
        return Route.<AspectSetResponse> post("")
                    .to(ctx -> proxyPost(cluster, http, "/api/aspects", ctx.bodyAsString()))
                    .asJson();
    }

    private static Route<AspectDeleteResponse> deleteAspectRoute(ForgeCluster cluster,
                                                                   JdkHttpOperations http) {
        return Route.<AspectDeleteResponse> delete("")
                    .withPath(aString())
                    .to(key -> proxyDelete(cluster, http, "/api/aspects/" + key))
                    .asJson();
    }

    private static Promise<AspectListResponse> proxyGet(ForgeCluster cluster,
                                                         JdkHttpOperations http,
                                                         String path) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendGet(http, port, path))
                      .map(AspectListResponse::new);
    }

    private static Promise<AspectSetResponse> proxyPost(ForgeCluster cluster,
                                                         JdkHttpOperations http,
                                                         String path,
                                                         String body) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendPost(http, port, path, body))
                      .map(resp -> new AspectSetResponse(true, resp));
    }

    private static Promise<AspectDeleteResponse> proxyDelete(ForgeCluster cluster,
                                                               JdkHttpOperations http,
                                                               String path) {
        return cluster.getLeaderManagementPort()
                      .async(LeaderNotAvailable.INSTANCE)
                      .flatMap(port -> sendDelete(http, port, path))
                      .map(resp -> new AspectDeleteResponse(true, resp));
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

    private static Promise<String> sendPost(JdkHttpOperations http, int port, String path, String body) {
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
            return "No leader node available for aspect proxy";
        }
    }

    record unused() implements AspectProxyRoutes {}
}
