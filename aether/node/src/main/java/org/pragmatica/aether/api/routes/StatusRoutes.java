package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ComponentHealth;
import org.pragmatica.aether.api.ManagementApiResponses.HealthResponse;
import org.pragmatica.aether.api.ManagementApiResponses.LivenessResponse;
import org.pragmatica.aether.api.ManagementApiResponses.MetricsSummary;
import org.pragmatica.aether.api.ManagementApiResponses.NodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.NodesResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ReadinessResponse;
import org.pragmatica.aether.api.ManagementApiResponses.StatusResponse;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/// Routes for cluster status: health, status, nodes, events, liveness and readiness probes.
public final class StatusRoutes implements RouteSource {
    private final Supplier<AetherNode> nodeSupplier;
    private final Supplier<AppHttpServer> appHttpServerSupplier;

    private StatusRoutes(Supplier<AetherNode> nodeSupplier, Supplier<AppHttpServer> appHttpServerSupplier) {
        this.nodeSupplier = nodeSupplier;
        this.appHttpServerSupplier = appHttpServerSupplier;
    }

    public static StatusRoutes statusRoutes(Supplier<AetherNode> nodeSupplier,
                                            Supplier<AppHttpServer> appHttpServerSupplier) {
        return new StatusRoutes(nodeSupplier, appHttpServerSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<StatusResponse> get("/api/status")
                              .toJson(this::buildStatusResponse),
                         Route.<NodesResponse> get("/api/nodes")
                              .toJson(this::buildNodesResponse),
                         Route.<HealthResponse> get("/api/health")
                              .toJson(this::buildHealthResponse),
                         Route.<LivenessResponse> get("/health/live")
                              .toJson(this::buildLivenessResponse),
                         Route.<ReadinessResponse> get("/health/ready")
                              .toJson(this::buildReadinessResponse),
                         Route.<List<ClusterEvent>> get("/api/events")
                              .<String> withQuery(QueryParameter.aString("since"))
                              .toValue(this::buildEventsResponse)
                              .asJson());
    }

    private List<ClusterEvent> buildEventsResponse(Option<String> sinceParam) {
        var aggregator = nodeSupplier.get()
                                     .eventAggregator();
        return sinceParam.map(StatusRoutes::parseInstant)
                         .map(aggregator::eventsSince)
                         .or(aggregator.events());
    }

    private static Instant parseInstant(String raw) {
        return Instant.parse(raw);
    }

    private StatusResponse buildStatusResponse() {
        var node = nodeSupplier.get();
        var uptimeSeconds = node.uptimeSeconds();
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        var connectedNodes = allMetrics.keySet();
        var leaderId = node.leader()
                           .map(NodeId::id)
                           .or("none");
        var nodeInfos = connectedNodes.stream()
                                      .map(nodeId -> new NodeInfo(nodeId.id(),
                                                                  node.leader()
                                                                      .map(l -> l.equals(nodeId))
                                                                      .or(false)))
                                      .toList();
        var cluster = new ClusterInfo(connectedNodes.size(), leaderId, nodeInfos);
        var derived = node.snapshotCollector()
                          .derivedMetrics();
        var metrics = new MetricsSummary(derived.requestRate(),
                                         100.0 - derived.errorRate() * 100.0,
                                         derived.latencyP50());
        return new StatusResponse(uptimeSeconds,
                                  cluster,
                                  node.sliceStore()
                                      .loaded()
                                      .size(),
                                  metrics,
                                  node.self()
                                      .id(),
                                  "running",
                                  node.isLeader(),
                                  leaderId);
    }

    private NodesResponse buildNodesResponse() {
        var node = nodeSupplier.get();
        var metrics = node.metricsCollector()
                          .allMetrics();
        var nodeIds = new LinkedHashSet<String>();
        // Always include self
        nodeIds.add(node.self()
                        .id());
        // Add nodes from initial topology
        node.initialTopology()
            .forEach(nid -> nodeIds.add(nid.id()));
        // Add nodes from metrics
        for (NodeId nodeId : metrics.keySet()) {
            nodeIds.add(nodeId.id());
        }
        // Add nodes from KV-Store slice entries
        node.kvStore()
            .forEach(SliceNodeKey.class,
                     SliceNodeValue.class,
                     (key, _) -> nodeIds.add(key.nodeId()
                                                .id()));
        return new NodesResponse(List.copyOf(nodeIds));
    }

    private HealthResponse buildHealthResponse() {
        var node = nodeSupplier.get();
        var metrics = node.metricsCollector()
                          .allMetrics();
        var metricsNodeCount = metrics.size();
        var connectedNodeCount = node.connectedNodeCount();
        var sliceCount = node.sliceStore()
                             .loaded()
                             .size();
        var ready = node.isReady();
        var totalNodes = connectedNodeCount + 1;
        var hasQuorum = totalNodes >= 2;
        var status = !ready || !hasQuorum
                     ? "unhealthy"
                     : "healthy";
        return new HealthResponse(status, ready, hasQuorum, totalNodes, connectedNodeCount, metricsNodeCount, sliceCount);
    }

    /// Build liveness response. Public for direct use from ManagementServer.
    public LivenessResponse buildLivenessResponse() {
        var nodeId = nodeSupplier.get()
                                 .self()
                                 .id();
        return new LivenessResponse("UP", nodeId);
    }

    /// Build readiness response. Public for direct use from ManagementServer (status code control).
    @SuppressWarnings("JBCT-PAT-01")
    public ReadinessResponse buildReadinessResponse() {
        var node = nodeSupplier.get();
        var nodeId = node.self()
                         .id();
        var components = new ArrayList<ComponentHealth>();
        components.add(buildConsensusHealth(node));
        components.add(buildRoutesHealth());
        components.add(buildQuorumHealth(node));
        var allUp = components.stream()
                              .allMatch(c -> "UP".equals(c.status()));
        var status = allUp
                     ? "UP"
                     : "DOWN";
        return new ReadinessResponse(status, nodeId, List.copyOf(components));
    }

    private static ComponentHealth buildConsensusHealth(AetherNode node) {
        var consensusReady = node.isReady();
        return new ComponentHealth("consensus",
                                   consensusReady
                                   ? "UP"
                                   : "DOWN",
                                   consensusReady
                                   ? "Cluster active"
                                   : "Consensus not established");
    }

    private ComponentHealth buildRoutesHealth() {
        var routesReady = appHttpServerSupplier.get()
                                               .isRouteReady();
        return new ComponentHealth("routes",
                                   routesReady
                                   ? "UP"
                                   : "DOWN",
                                   routesReady
                                   ? "Route sync received"
                                   : "Awaiting initial route sync");
    }

    private static ComponentHealth buildQuorumHealth(AetherNode node) {
        var connectedCount = node.connectedNodeCount();
        var hasQuorum = connectedCount + 1 >= 2;
        return new ComponentHealth("quorum", hasQuorum
                                            ? "UP"
                                            : "DOWN", "Connected peers: " + connectedCount);
    }
}
