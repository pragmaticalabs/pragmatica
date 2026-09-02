// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Consumer;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberCluster.EventLogEntry;
import org.pragmatica.aether.forge.api.ForgeApiResponses.RepositoryPutResponse;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import static org.pragmatica.http.routing.Route.get;
import static org.pragmatica.http.routing.Route.post;
import static org.pragmatica.http.routing.Route.put;


public sealed interface DeploymentRoutes {
    Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    record RepositoryPutRequest(String groupId, String artifactId, String version, byte[] content) {
        public RepositoryPutRequest {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RepositoryPutRequest other
                   && groupId.equals(other.groupId)
                   && artifactId.equals(other.artifactId)
                   && version.equals(other.version)
                   && Arrays.equals(content, other.content);
        }

        @Override
        public int hashCode() {
            int result = groupId.hashCode();

            result = 31 * result + artifactId.hashCode();
            result = 31 * result + version.hashCode();
            result = 31 * result + Arrays.hashCode(content);

            return result;
        }
    }

    record ProxyResponse(boolean success, String body) {}

    record SlicesStatusResponse(java.util.List<EmberCluster.SliceStatus> slices) {}

    record ClusterMetricsResponse(String body) {}

    static RouteSource deploymentRoutes(EmberCluster cluster, Consumer<EventLogEntry> eventLogger) {
        var http = JdkHttpOperations.jdkHttpOperations();

        return RouteSource.of(blueprintRoute(cluster, http, eventLogger),
                              slicesStatusRoute(cluster, http),
                              clusterMetricsRoute(cluster, http),
                              repositoryPutRoute(cluster, eventLogger));
    }

    private static Route<ProxyResponse> blueprintRoute(EmberCluster cluster,
                                                       JdkHttpOperations http,
                                                       Consumer<EventLogEntry> eventLogger) {
        return Route.<ProxyResponse> post("/api/blueprints")
                    .withBody(TypeToken.typeToken(String.class))
                    .toJson(body -> proxyBlueprint(cluster, http, eventLogger, body));
    }

    private static Route<SlicesStatusResponse> slicesStatusRoute(EmberCluster cluster, JdkHttpOperations http) {
        return Route.<SlicesStatusResponse> get("/api/slices/status").toJson(() -> getSlicesStatus(cluster));
    }

    private static Route<ClusterMetricsResponse> clusterMetricsRoute(EmberCluster cluster, JdkHttpOperations http) {
        return Route.<ClusterMetricsResponse> get("/api/cluster/metrics")
                    .to(_ -> proxyClusterMetrics(cluster, http))
                    .asJson();
    }

    private static Route<RepositoryPutResponse> repositoryPutRoute(EmberCluster cluster,
                                                                   Consumer<EventLogEntry> eventLogger) {
        return Route.<RepositoryPutResponse> put("/api/repository")
                    .withBody(RepositoryPutRequest.class)
                    .toJson(req -> storeArtifact(cluster, eventLogger, req));
    }

    private static Promise<ProxyResponse> proxyBlueprint(EmberCluster cluster,
                                                         JdkHttpOperations http,
                                                         Consumer<EventLogEntry> eventLogger,
                                                         String blueprintJson) {
        return findLeaderPort(cluster).async(LeaderNotAvailable.INSTANCE)
                             .flatMap(port -> proxyPost(http, port, "/api/v1/blueprints", blueprintJson))
                             .map(body -> logAndBuildBlueprintResponse(eventLogger, body));
    }

    private static ProxyResponse logAndBuildBlueprintResponse(Consumer<EventLogEntry> eventLogger, String body) {
        eventLogger.accept(new EventLogEntry("BLUEPRINT", "Applied blueprint"));

        return new ProxyResponse(true, body);
    }

    private static SlicesStatusResponse getSlicesStatus(EmberCluster cluster) {
        return new SlicesStatusResponse(cluster.slicesStatus());
    }

    private static Promise<ClusterMetricsResponse> proxyClusterMetrics(EmberCluster cluster, JdkHttpOperations http) {
        return findLeaderPort(cluster).async(LeaderNotAvailable.INSTANCE)
                             .flatMap(port -> proxyGet(http, port, "/api/v1/metrics"))
                             .map(ClusterMetricsResponse::new);
    }

    private static Promise<RepositoryPutResponse> storeArtifact(EmberCluster cluster,
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

    private static Option<Integer> findLeaderPort(EmberCluster cluster) {
        return cluster.getLeaderManagementPort();
    }

    private static Option<AetherNode> findAnyNode(EmberCluster cluster) {
        var nodes = cluster.allNodes();

        return nodes.isEmpty()
               ? Option.none()
               : Option.some(nodes.getFirst());
    }

    private static String buildRepositoryPath(String groupId, String artifactId, String version) {
        return "/repository/" + groupId.replace('.', '/')
             + "/" + artifactId
             + "/" + version
             + "/" + artifactId
             + "-" + version
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

    enum LeaderNotAvailable implements org.pragmatica.lang.Cause {
        INSTANCE;
        @Override
        public String message() {
            return "No leader node available";
        }
    }

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
