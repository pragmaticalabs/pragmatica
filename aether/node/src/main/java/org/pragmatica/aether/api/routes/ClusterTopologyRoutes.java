// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.GovernorInfo;
import org.pragmatica.aether.api.ManagementApiResponses.GovernorsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.TopologyNodeDetail;
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
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.aether.api.ManagementApiResponses.ClusterTopologyStatusResponse;


/// Routes for cluster topology growth status: core count, limits, worker count.
public final class ClusterTopologyRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private ClusterTopologyRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ClusterTopologyRoutes clusterTopologyRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ClusterTopologyRoutes(nodeSupplier);
    }

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ClusterTopologyStatusResponse>route(ManagementRoute.CLUSTER_TOPOLOGY)
                                         .toJson(this::buildTopologyStatus),
                         ManagementRoutes.<GovernorsResponse>route(ManagementRoute.CLUSTER_GOVERNORS)
                                         .toJson(this::buildGovernorsResponse));
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
        var topologyManager = node.topologyManager();
        var connectedPeers = node.connectedPeerIds();
        var allNodeIds = topologyManager.topology();
        var coreNodeIds = allNodeIds.stream().filter(id -> !topologyManager.isPassive(id))
                                           .filter(id -> isHealthy(topologyManager, id))
                                           .map(NodeId::id)
                                           .toList();
        var snapshot = node.currentGenerationSnapshot();
        var coreCount = snapshot.map(ClusterTopologyRoutes::snapshotCoreCount)
                                    .or(() -> topologyManager.healthyActiveNodeCount());
        var epoch = snapshot.map(s -> s.epoch().toString());
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
                                                 epoch);
    }

    private static int snapshotCoreCount(ClusterGenerationSnapshot snapshot) {
        // Strict ON_DUTY + HEALTHY. Commits 1–6 of the ClusterSync refactor (single-source-
        // of-truth membership via leader-driven snapshot + sensor-only followers) ensure
        // transient SWIM SUSPECTED hints no longer pull the published snapshot's healthHint
        // down — only the leader's multi-observer reducer decides the authoritative hint.
        // The previous `|| JOINING` workaround is no longer needed and masks legitimate
        // degraded-state reporting.
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
