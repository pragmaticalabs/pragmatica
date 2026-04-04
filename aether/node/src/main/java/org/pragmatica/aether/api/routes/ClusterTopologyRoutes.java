package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.GovernorInfo;
import org.pragmatica.aether.api.ManagementApiResponses.GovernorsResponse;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import java.util.ArrayList;
import java.util.List;
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

    @Override public Stream<Route<?>> routes() {
        return Stream.of(Route.<ClusterTopologyStatusResponse>get("/api/cluster/topology")
                              .toJson(this::buildTopologyStatus),
                         Route.<GovernorsResponse>get("/api/cluster/governors").toJson(this::buildGovernorsResponse));
    }

    private GovernorsResponse buildGovernorsResponse() {
        var node = nodeSupplier.get();
        var governors = new ArrayList<GovernorInfo>();
        node.kvStore()
                    .forEach(GovernorAnnouncementKey.class,
                             GovernorAnnouncementValue.class,
                             (key, value) -> governors.add(toGovernorInfo(key, value)));
        return new GovernorsResponse(List.copyOf(governors));
    }

    private static GovernorInfo toGovernorInfo(GovernorAnnouncementKey key, GovernorAnnouncementValue value) {
        var memberIds = value.members().stream()
                                     .map(NodeId::id)
                                     .toList();
        return new GovernorInfo(value.governorId().id(),
                                key.communityId(),
                                value.memberCount(),
                                memberIds);
    }

    private ClusterTopologyStatusResponse buildTopologyStatus() {
        var node = nodeSupplier.get();
        var topologyConfig = node.topologyConfig();
        var coreNodeIds = node.initialTopology().stream()
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
