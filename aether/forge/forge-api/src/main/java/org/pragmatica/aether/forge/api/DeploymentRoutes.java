package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.ForgeCluster;
import org.pragmatica.aether.forge.ForgeCluster.EventLogEntry;
import org.pragmatica.aether.forge.api.ForgeApiResponses.RepositoryPutResponse;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.function.Consumer;

import static org.pragmatica.http.routing.Route.get;
import static org.pragmatica.http.routing.Route.post;
import static org.pragmatica.http.routing.Route.put;

/**
 * REST API routes for deployment-related operations.
 * These endpoints proxy requests to the cluster leader's ManagementServer.
 * <p>
 * Provides endpoints for:
 * <ul>
 *   <li>Slice deployment (POST /api/deploy)</li>
 *   <li>Slice undeployment (POST /api/undeploy)</li>
 *   <li>Blueprint application (POST /api/blueprint)</li>
 *   <li>Slice status (GET /api/slices/status)</li>
 *   <li>Cluster metrics (GET /api/cluster/metrics)</li>
 *   <li>Artifact repository (PUT /api/repository)</li>
 * </ul>
 */
public sealed interface DeploymentRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    // ========== Request Records ==========
    /**
     * Request body for deploy operation.
     */
    record DeployRequest(String artifact, int instances) {}

    /**
     * Request body for undeploy operation.
     */
    record UndeployRequest(String artifact) {}

    /**
     * Request body for repository PUT operation.
     */
    record RepositoryPutRequest(String groupId, String artifactId, String version, byte[] content) {}

    // ========== Response Records ==========
    /**
     * Generic proxy response wrapper.
     * The actual response body comes from the leader's ManagementServer.
     */
    record ProxyResponse(boolean success, String body) {}

    /**
     * Slices status response.
     * Contains slice data directly from ForgeCluster's KV store query.
     */
    record SlicesStatusResponse(java.util.List<ForgeCluster.SliceStatus> slices) {}

    /**
     * Cluster metrics response from leader.
     */
    record ClusterMetricsResponse(String body) {}

    // ========== Route Factory ==========
    /**
     * Create route source for all deployment-related endpoints.
     *
     * @param cluster     the ForgeCluster for finding the leader node
     * @param eventLogger callback to log events for the dashboard
     * @return RouteSource containing all deployment routes
     */
    static RouteSource deploymentRoutes(ForgeCluster cluster,
                                        Consumer<EventLogEntry> eventLogger) {
        var http = JdkHttpOperations.jdkHttpOperations();
        return RouteSource.of(deployRoute(cluster, http, eventLogger),
                              undeployRoute(cluster, http, eventLogger),
                              blueprintRoute(cluster, http, eventLogger),
                              slicesStatusRoute(cluster, http),
                              clusterMetricsRoute(cluster, http),
                              repositoryPutRoute(cluster, eventLogger));
    }

    // ========== Route Definitions ==========
    private static Route<ProxyResponse> deployRoute(ForgeCluster cluster,
                                                    JdkHttpOperations http,
                                                    Consumer<EventLogEntry> eventLogger) {
        return Route.<ProxyResponse> post("/api/deploy")
                    .withBody(DeployRequest.class)
                    .toJson(req -> proxyDeploy(cluster, http, eventLogger, req));
    }

    private static Route<ProxyResponse> undeployRoute(ForgeCluster cluster,
                                                      JdkHttpOperations http,
                                                      Consumer<EventLogEntry> eventLogger) {
        return Route.<ProxyResponse> post("/api/undeploy")
                    .withBody(UndeployRequest.class)
                    .toJson(req -> proxyUndeploy(cluster, http, eventLogger, req));
    }

    private static Route<ProxyResponse> blueprintRoute(ForgeCluster cluster,
                                                       JdkHttpOperations http,
                                                       Consumer<EventLogEntry> eventLogger) {
        return Route.<ProxyResponse> post("/api/blueprint")
                    .withBody(TypeToken.of(String.class))
                    .toJson(body -> proxyBlueprint(cluster, http, eventLogger, body));
    }

    private static Route<SlicesStatusResponse> slicesStatusRoute(ForgeCluster cluster,
                                                                 JdkHttpOperations http) {
        return Route.<SlicesStatusResponse> get("/api/slices/status")
                    .toJson(() -> getSlicesStatus(cluster));
    }

    private static Route<ClusterMetricsResponse> clusterMetricsRoute(ForgeCluster cluster,
                                                                     JdkHttpOperations http) {
        return Route.<ClusterMetricsResponse> get("/api/cluster/metrics")
                    .to(_ -> proxyClusterMetrics(cluster, http))
                    .asJson();
    }

    private static Route<RepositoryPutResponse> repositoryPutRoute(ForgeCluster cluster,
                                                                   Consumer<EventLogEntry> eventLogger) {
        return Route.<RepositoryPutResponse> put("/api/repository")
                    .withBody(RepositoryPutRequest.class)
                    .toJson(req -> storeArtifact(cluster, eventLogger, req));
    }

    // ========== Handler Methods ==========
    private static Promise<ProxyResponse> proxyDeploy(ForgeCluster cluster,
                                                      JdkHttpOperations http,
                                                      Consumer<EventLogEntry> eventLogger,
                                                      DeployRequest request) {
        return findLeaderPort(cluster).async(LeaderNotAvailable.INSTANCE)
                             .flatMap(port -> proxyPost(http,
                                                        port,
                                                        "/api/deploy",
                                                        buildDeployJson(request)))
                             .map(body -> logAndBuildDeployResponse(eventLogger, request, body));
    }

    private static ProxyResponse logAndBuildDeployResponse(Consumer<EventLogEntry> eventLogger,
                                                           DeployRequest request,
                                                           String body) {
        eventLogger.accept(new EventLogEntry("DEPLOY",
                                             "Deployed " + request.artifact() + " x" + request.instances()));
        return new ProxyResponse(true, body);
    }

    private static String buildDeployJson(DeployRequest request) {
        return "{\"artifact\":\"" + escapeJson(request.artifact()) + "\",\"instances\":" + request.instances() + "}";
    }

    private static Promise<ProxyResponse> proxyUndeploy(ForgeCluster cluster,
                                                        JdkHttpOperations http,
                                                        Consumer<EventLogEntry> eventLogger,
                                                        UndeployRequest request) {
        return findLeaderPort(cluster).async(LeaderNotAvailable.INSTANCE)
                             .flatMap(port -> proxyPost(http,
                                                        port,
                                                        "/api/undeploy",
                                                        "{\"artifact\":\"" + escapeJson(request.artifact()) + "\"}"))
                             .map(body -> logAndBuildUndeployResponse(eventLogger, request, body));
    }

    private static ProxyResponse logAndBuildUndeployResponse(Consumer<EventLogEntry> eventLogger,
                                                             UndeployRequest request,
                                                             String body) {
        eventLogger.accept(new EventLogEntry("UNDEPLOY", "Undeployed " + request.artifact()));
        return new ProxyResponse(true, body);
    }

    private static Promise<ProxyResponse> proxyBlueprint(ForgeCluster cluster,
                                                         JdkHttpOperations http,
                                                         Consumer<EventLogEntry> eventLogger,
                                                         String blueprintJson) {
        return findLeaderPort(cluster).async(LeaderNotAvailable.INSTANCE)
                             .flatMap(port -> proxyPost(http, port, "/api/blueprint", blueprintJson))
                             .map(body -> logAndBuildBlueprintResponse(eventLogger, body));
    }

    private static ProxyResponse logAndBuildBlueprintResponse(Consumer<EventLogEntry> eventLogger,
                                                              String body) {
        eventLogger.accept(new EventLogEntry("BLUEPRINT", "Applied blueprint"));
        return new ProxyResponse(true, body);
    }

    private static SlicesStatusResponse getSlicesStatus(ForgeCluster cluster) {
        return new SlicesStatusResponse(cluster.slicesStatus());
    }

    private static Promise<ClusterMetricsResponse> proxyClusterMetrics(ForgeCluster cluster,
                                                                       JdkHttpOperations http) {
        return findLeaderPort(cluster).async(LeaderNotAvailable.INSTANCE)
                             .flatMap(port -> proxyGet(http, port, "/api/metrics"))
                             .map(ClusterMetricsResponse::new);
    }

    private static Promise<RepositoryPutResponse> storeArtifact(ForgeCluster cluster,
                                                                Consumer<EventLogEntry> eventLogger,
                                                                RepositoryPutRequest request) {
        return findAnyNode(cluster).async(NoNodesAvailable.INSTANCE)
                          .flatMap(node -> storeArtifactOnNode(node, eventLogger, request));
    }

    private static Promise<RepositoryPutResponse> storeArtifactOnNode(AetherNode node,
                                                                      Consumer<EventLogEntry> eventLogger,
                                                                      RepositoryPutRequest request) {
        var path = buildRepositoryPath(request.groupId(), request.artifactId(), request.version());
        return node.mavenProtocolHandler()
                   .handlePut(path,
                              request.content())
                   .map(_ -> createRepositoryResponse(eventLogger, request, path));
    }

    private static RepositoryPutResponse createRepositoryResponse(Consumer<EventLogEntry> eventLogger,
                                                                  RepositoryPutRequest request,
                                                                  String path) {
        var artifact = request.groupId() + ":" + request.artifactId() + ":" + request.version();
        eventLogger.accept(new EventLogEntry("ARTIFACT_DEPLOYED",
                                             "Deployed " + artifact + " (" + request.content().length + " bytes)"));
        return new RepositoryPutResponse(true, path, request.content().length);
    }

    // ========== Helper Methods ==========
    private static Option<Integer> findLeaderPort(ForgeCluster cluster) {
        return cluster.getLeaderManagementPort();
    }

    private static Option<AetherNode> findAnyNode(ForgeCluster cluster) {
        var nodes = cluster.allNodes();
        return nodes.isEmpty()
               ? Option.none()
               : Option.some(nodes.getFirst());
    }

    private static String buildRepositoryPath(String groupId, String artifactId, String version) {
        return "/repository/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version
               + ".jar";
    }

    private static Promise<String> proxyPost(JdkHttpOperations http, int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(HTTP_TIMEOUT)
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> result.toResult()
                                            .async());
    }

    private static Promise<String> proxyGet(JdkHttpOperations http, int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(HTTP_TIMEOUT)
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> result.toResult()
                                            .async());
    }

    // ========== Error Types ==========
    /**
     * Error when no leader node is available.
     */
    enum LeaderNotAvailable implements org.pragmatica.lang.Cause {
        INSTANCE;
        @Override
        public String message() {
            return "No leader node available";
        }
    }

    /**
     * Error when no nodes are available in the cluster.
     */
    enum NoNodesAvailable implements org.pragmatica.lang.Cause {
        INSTANCE;
        @Override
        public String message() {
            return "No nodes available in cluster";
        }
    }

    private static String escapeJson(String s) {
        return Option.option(s)
                     .map(str -> str.replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\n", "\\n")
                                    .replace("\r", "\\r")
                                    .replace("\t", "\\t"))
                     .or("");
    }

    record unused() implements DeploymentRoutes {}
}
