package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.aether.api.ManagementApiResponses.ClusterTopologyStatusResponse;

/// Routes for cluster topology growth status: core count, limits, worker count.
public final class ClusterTopologyRoutes implements RouteSource {
    private final Supplier<AetherNode> nodeSupplier;

    private ClusterTopologyRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ClusterTopologyRoutes clusterTopologyRoutes(Supplier<AetherNode> nodeSupplier) {
        return new ClusterTopologyRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<ClusterTopologyStatusResponse> get("/api/cluster/topology")
                              .toJson(this::buildTopologyStatus));
    }

    private ClusterTopologyStatusResponse buildTopologyStatus() {
        var node = nodeSupplier.get();
        var topologyConfig = node.topologyConfig();
        var coreNodeIds = node.initialTopology()
                              .stream()
                              .map(NodeId::id)
                              .toList();
        var connectedCount = node.connectedNodeCount();
        var coreCount = coreNodeIds.size();
        var workerCount = Math.max(0, connectedCount - coreCount);
        return new ClusterTopologyStatusResponse(coreCount,
                                                 topologyConfig.coreMax(),
                                                 topologyConfig.coreMin(),
                                                 workerCount,
                                                 topologyConfig.clusterSize(),
                                                 coreNodeIds);
    }
}
