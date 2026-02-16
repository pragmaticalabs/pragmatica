package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.ClusterInfo;
import org.pragmatica.aether.api.ManagementApiResponses.HealthResponse;
import org.pragmatica.aether.api.ManagementApiResponses.MetricsSummary;
import org.pragmatica.aether.api.ManagementApiResponses.NodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.NodesResponse;
import org.pragmatica.aether.api.ManagementApiResponses.StatusResponse;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/// Routes for cluster status: health, status, nodes, events.
public final class StatusRoutes implements RouteSource {
    private final Supplier<AetherNode> nodeSupplier;

    private StatusRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static StatusRoutes statusRoutes(Supplier<AetherNode> nodeSupplier) {
        return new StatusRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<StatusResponse> get("/api/status")
                              .toJson(this::buildStatusResponse),
                         Route.<NodesResponse> get("/api/nodes")
                              .toJson(this::buildNodesResponse),
                         Route.<HealthResponse> get("/api/health")
                              .toJson(this::buildHealthResponse),
                         Route.<List<?>> get("/api/events")
                              .toJson(() -> List.of()),
                         Route.<String> get("/api/panel/chaos")
                              .toText(() -> ""));
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
}
