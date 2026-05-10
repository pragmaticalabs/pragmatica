// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.CircuitBreakerResetResponse;
import org.pragmatica.aether.api.ManagementApiResponses.CircuitBreakerStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.GovernorInfo;
import org.pragmatica.aether.api.ManagementApiResponses.GovernorsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.TopologyNodeDetail;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeHealth;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.http.routing.Handler;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.aether.api.ManagementApiResponses.ClusterTopologyStatusResponse;


public final class ClusterTopologyRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private ClusterTopologyRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ClusterTopologyRoutes clusterTopologyRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ClusterTopologyRoutes(nodeSupplier);
    }

    @Override public Stream<Route<?>> routes() {
        Handler<ClusterTopologyStatusResponse> topologyHandler = _ -> buildTopologyStatus();
        return Stream.of(ManagementRoutes.<ClusterTopologyStatusResponse>route(ManagementRoute.CLUSTER_TOPOLOGY)
                                         .toJson(topologyHandler),
                         ManagementRoutes.<GovernorsResponse>route(ManagementRoute.CLUSTER_GOVERNORS)
                                         .toJson(this::buildGovernorsResponse),
                         ManagementRoutes.<CircuitBreakerStatusResponse>route(ManagementRoute.CLUSTER_CIRCUIT_BREAKER_STATUS)
                                         .toJson(_ -> buildCircuitBreakerStatus()),
                         ManagementRoutes.<CircuitBreakerResetResponse>route(ManagementRoute.CLUSTER_CIRCUIT_BREAKER_RESET)
                                         .toJson(_ -> resetCircuitBreaker()));
    }

    private Promise<CircuitBreakerStatusResponse> buildCircuitBreakerStatus() {
        return ctmOpt().map(ctm -> {
            var state = ctm.circuitBreakerState();
            return new CircuitBreakerStatusResponse(state.consecutiveFailures(),
                                                    state.trippedAt(),
                                                    state.nextAllowedMs(),
                                                    state.tripped());
        }).async(CTM_UNAVAILABLE);
    }

    private Promise<CircuitBreakerResetResponse> resetCircuitBreaker() {
        return ctmOpt().map(ctm -> {
            var prior = ctm.resetCircuitBreaker("/api/cluster/topology/circuit-breaker/reset");
            return new CircuitBreakerResetResponse("reset", prior);
        }).async(CTM_UNAVAILABLE);
    }

    private Option<ClusterTopologyManager> ctmOpt() {
        return nodeSupplier.get().clusterTopologyManager();
    }

    private static final Cause CTM_UNAVAILABLE = Causes.cause("Cluster topology manager not available on this node (not the leader, or node not yet activated)");

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

    private Promise<ClusterTopologyStatusResponse> buildTopologyStatus() {
        var node = nodeSupplier.get();
        return Promise.success(node.currentGenerationSnapshot().map(snapshot -> assembleTopologyStatus(node, snapshot))
                                                             .or(() -> assembleFromTopologyManager(node)));
    }

    private static ClusterTopologyStatusResponse assembleFromTopologyManager(ManageableNode node) {
        var topologyConfig = node.topologyConfig();
        var topologyManager = node.topologyManager();
        var connectedPeers = node.connectedPeerIds();
        var allNodeIds = topologyManager.topology();
        var coreNodeIds = allNodeIds.stream().filter(id -> !topologyManager.isPassive(id))
                                           .filter(id -> isHealthy(topologyManager, id))
                                           .map(NodeId::id)
                                           .toList();
        var coreCount = coreNodeIds.size();
        var workerCount = Math.max(0, connectedPeers.size() - coreCount);
        var nodeDetails = allNodeIds.stream().map(id -> buildNodeDetail(topologyManager,
                                                                        id,
                                                                        connectedPeers.contains(id)))
                                           .toList();
        return new ClusterTopologyStatusResponse(coreCount,
                                                 topologyConfig.coreMax(),
                                                 topologyConfig.coreMin(),
                                                 workerCount,
                                                 topologyConfig.clusterSize(),
                                                 coreNodeIds,
                                                 connectedPeers.size(),
                                                 nodeDetails,
                                                 Option.<String>none(),
                                                 topologyMode(topologyManager));
    }

    private static ClusterTopologyStatusResponse assembleTopologyStatus(ManageableNode node,
                                                                        ClusterGenerationSnapshot snapshot) {
        var topologyConfig = node.topologyConfig();
        var topologyManager = node.topologyManager();
        var connectedPeers = node.connectedPeerIds();
        var allNodeIds = topologyManager.topology();
        var coreNodeIds = allNodeIds.stream().filter(id -> !topologyManager.isPassive(id))
                                           .filter(id -> isHealthy(topologyManager, id))
                                           .map(NodeId::id)
                                           .toList();
        var coreCount = snapshotCoreCount(snapshot);
        var epoch = Option.some(snapshot.epoch().toString());
        var workerCount = Math.max(0, connectedPeers.size() - coreCount);
        var nodeDetails = allNodeIds.stream().map(id -> buildNodeDetail(topologyManager,
                                                                        id,
                                                                        connectedPeers.contains(id)))
                                           .toList();
        return new ClusterTopologyStatusResponse(coreCount,
                                                 topologyConfig.coreMax(),
                                                 topologyConfig.coreMin(),
                                                 workerCount,
                                                 topologyConfig.clusterSize(),
                                                 coreNodeIds,
                                                 connectedPeers.size(),
                                                 nodeDetails,
                                                 epoch,
                                                 topologyMode(topologyManager));
    }

    /// `TopologyManager` is the public interface; mode lives on `TopologyObserver`.
    /// Manager instances in production are observers, but defensive isinstance keeps
    /// test stubs (which may not extend `TopologyObserver`) safe.
    private static String topologyMode(TopologyManager tm) {
        return (tm instanceof TopologyObserver observer)
               ? observer.topologyMode().name()
               : TopologyObserver.TopologyMode.NORMAL.name();
    }

    private static int snapshotCoreCount(ClusterGenerationSnapshot snapshot) {
        return (int) snapshot.coreMembers().values()
                                         .stream()
                                         .filter(member -> member.lifecycle() == NodeLifecycleState.ON_DUTY)
                                         .filter(member -> member.healthHint() == HealthHint.HEALTHY)
                                         .count();
    }

    private static boolean isHealthy(TopologyManager tm, NodeId id) {
        return tm.getState(id).map(state -> state.health() == NodeHealth.HEALTHY)
                          .or(false);
    }

    private static TopologyNodeDetail buildNodeDetail(TopologyManager tm, NodeId nodeId, boolean connected) {
        var info = tm.get(nodeId);
        var state = tm.getState(nodeId);
        var role = info.map(NodeInfo::role).map(Enum::name)
                           .or("UNKNOWN");
        var health = state.map(NodeState::health).map(Enum::name)
                              .or(connected
                                  ? "CONNECTED"
                                  : "UNKNOWN");
        var hostname = info.flatMap(i -> Option.option(i.labels().get(NodeInfo.LABEL_HOSTNAME))).or("");
        var zone = info.flatMap(i -> Option.option(i.labels().get(NodeInfo.LABEL_ZONE))).or("");
        var address = info.map(i -> i.address().asString()).or("");
        return new TopologyNodeDetail(nodeId.id(), role, health, hostname, zone, address);
    }
}
