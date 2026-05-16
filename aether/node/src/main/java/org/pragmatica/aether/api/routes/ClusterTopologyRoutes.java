// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.AutoHealStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.AutoHealToggleResponse;
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
                                         .toJson(_ -> resetCircuitBreaker()),
                         ManagementRoutes.<AutoHealStatusResponse>route(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS)
                                         .toJson(_ -> buildAutoHealStatus()),
                         ManagementRoutes.<AutoHealToggleResponse>route(ManagementRoute.CLUSTER_AUTO_HEAL_ENABLE)
                                         .toJson(_ -> setAutoHeal(true)),
                         ManagementRoutes.<AutoHealToggleResponse>route(ManagementRoute.CLUSTER_AUTO_HEAL_DISABLE)
                                         .toJson(_ -> setAutoHeal(false)));
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

    private Promise<AutoHealStatusResponse> buildAutoHealStatus() {
        return ctmOpt().map(ctm -> new AutoHealStatusResponse(ctm.isAutoHealEnabled()))
                              .async(CTM_UNAVAILABLE);
    }

    private Promise<AutoHealToggleResponse> setAutoHeal(boolean enabled) {
        var reason = "/api/cluster/topology/auto-heal/" + (enabled ? "enable" : "disable");
        return ctmOpt().map(ctm -> new AutoHealToggleResponse(enabled, ctm.setAutoHealEnabled(enabled, reason)))
                              .async(CTM_UNAVAILABLE);
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
        // H.2 (spec §H): coreCount derives from MembershipView (SWIM ∪ KV-overrides). The
        // legacy `coreNodeIds.size()` counted topology-observer entries with HEALTHY status
        // — which is similar but the view is the canonical source post-H.
        //
        // Note: this aggregate count INTENTIONALLY uses the SWIM-cache-backed
        // `MembershipView.onDutyPeers().size()`, NOT the transport-honest variant.
        // SWIM is a gossip protocol — its cache is approximately consistent across
        // all nodes, so the aggregate count is stable cluster-wide. The transport-honest
        // downgrade (peer not in `connectedPeerIds()` → UNKNOWN) is applied only to the
        // per-peer view in `StatusRoutes.toNodeInfo` because there it's a useful
        // diagnostic (operator sees "this specific peer can't reach me right now").
        // Applying transport-honesty to the aggregate count would make `coreCount` vary
        // per reader's local QUIC mesh state, breaking test ordering invariants and
        // operator dashboards that assume aggregate-count stability.
        var coreCount = node.membershipView().onDutyPeers().size();
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
        // H.2 (spec §H): prefer the MembershipView-derived count; fall back to the snapshot
        // helper if the view is empty (e.g., during very-early bootstrap before SWIM has
        // admitted self). The snapshot helper also now honours SWIM-derived ON_DUTY via the
        // healthHint check (no KV ON_DUTY required) so both paths converge.
        // See assembleFromTopologyManager for why this aggregate uses the SWIM-based count
        // rather than the transport-honest variant from StatusRoutes.
        var viewCount = node.membershipView().onDutyPeers().size();
        var coreCount = viewCount > 0 ? viewCount : snapshotCoreCount(snapshot);
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

    private static String topologyMode(TopologyManager tm) {
        return (tm instanceof TopologyObserver observer)
              ? observer.topologyMode().name()
              : TopologyObserver.TopologyMode.NORMAL.name();
    }

    private static int snapshotCoreCount(ClusterGenerationSnapshot snapshot) {
        // H.2 callers should use `node.membershipView().onDutyPeers().size()`. This helper
        // remains for the snapshot path; counts members whose lifecycle is `ON_DUTY` OR
        // whose `healthHint` is `HEALTHY` (i.e. SWIM-derived: lifecycle may be absent or
        // any non-terminal state). Falls back to the legacy strict-`ON_DUTY` predicate
        // when neither is present.
        return (int) snapshot.coreMembers().values()
                                         .stream()
                                         .filter(member -> isEffectiveOnDuty(member))
                                         .count();
    }

    private static boolean isEffectiveOnDuty(org.pragmatica.aether.slice.generation.CoreMember member) {
        if (member.lifecycle() == NodeLifecycleState.DRAINING
            || member.lifecycle() == NodeLifecycleState.DECOMMISSIONED
            || member.lifecycle() == NodeLifecycleState.FAILED_DRAIN
            || member.lifecycle() == NodeLifecycleState.SHUTTING_DOWN) {
            return false;
        }
        return member.healthHint() == HealthHint.HEALTHY || member.lifecycle() == NodeLifecycleState.ON_DUTY;
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
