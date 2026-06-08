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
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
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
import java.util.Set;
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

    @Override
    public Stream<Route<?>> routes() {
        Handler<ClusterTopologyStatusResponse> topologyHandler = _ -> buildTopologyStatus();

        return Stream.of(ManagementRoutes.<ClusterTopologyStatusResponse> route(ManagementRoute.CLUSTER_TOPOLOGY).toJson(topologyHandler),
                         ManagementRoutes.<GovernorsResponse> route(ManagementRoute.CLUSTER_GOVERNORS).toJson(this::buildGovernorsResponse),
                         ManagementRoutes.<CircuitBreakerStatusResponse> route(ManagementRoute.CLUSTER_CIRCUIT_BREAKER_STATUS).toJson(_ -> buildCircuitBreakerStatus()),
                         ManagementRoutes.<CircuitBreakerResetResponse> route(ManagementRoute.CLUSTER_CIRCUIT_BREAKER_RESET).toJson(_ -> resetCircuitBreaker()),
                         ManagementRoutes.<AutoHealStatusResponse> route(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS).toJson(_ -> buildAutoHealStatus()),
                         ManagementRoutes.<AutoHealToggleResponse> route(ManagementRoute.CLUSTER_AUTO_HEAL_ENABLE).toJson(_ -> setAutoHeal(true)),
                         ManagementRoutes.<AutoHealToggleResponse> route(ManagementRoute.CLUSTER_AUTO_HEAL_DISABLE).toJson(_ -> setAutoHeal(false)));
    }

    private Promise<CircuitBreakerStatusResponse> buildCircuitBreakerStatus() {
        return ctmOpt().map(ctm -> {
                                var state = ctm.circuitBreakerState();
                                return new CircuitBreakerStatusResponse(state.consecutiveFailures(),
                                                                        state.trippedAt(),
                                                                        state.nextAllowedMs(),
                                                                        state.tripped());
                            })
                     .async(CTM_UNAVAILABLE);
    }

    private Promise<CircuitBreakerResetResponse> resetCircuitBreaker() {
        return ctmOpt().map(ctm -> {
                                var prior = ctm.resetCircuitBreaker("/api/cluster/topology/circuit-breaker/reset");
                                return new CircuitBreakerResetResponse("reset", prior);
                            })
                     .async(CTM_UNAVAILABLE);
    }

    private Promise<AutoHealStatusResponse> buildAutoHealStatus() {
        return ctmOpt().map(ctm -> new AutoHealStatusResponse(ctm.isAutoHealEnabled()))
                     .async(CTM_UNAVAILABLE);
    }

    private Promise<AutoHealToggleResponse> setAutoHeal(boolean enabled) {
        var reason = "/api/cluster/topology/auto-heal/" + (enabled
                                                           ? "enable"
                                                           : "disable");

        return ctmOpt().map(ctm -> new AutoHealToggleResponse(enabled,
                                                              ctm.setAutoHealEnabled(enabled, reason)))
                     .async(CTM_UNAVAILABLE);
    }

    private Option<ClusterTopologyManager> ctmOpt() {
        return nodeSupplier.get()
                           .clusterTopologyManager();
    }

    private static final Cause CTM_UNAVAILABLE = Causes.cause("Cluster topology manager not available on this node (not the leader, or node not yet activated)");

    private GovernorsResponse buildGovernorsResponse() {
        var node = nodeSupplier.get();
        var governors = new ArrayList<GovernorInfo>();
        node.kvStore().forEach(GovernorAnnouncementKey.class,
                               GovernorAnnouncementValue.class,
                               (key, value) -> governors.add(toGovernorInfo(key, value)));

        return new GovernorsResponse(List.copyOf(governors));
    }

    private static GovernorInfo toGovernorInfo(GovernorAnnouncementKey key, GovernorAnnouncementValue value) {
        var memberIds = value.members().stream().map(NodeId::id).toList();
        return new GovernorInfo(value.governorId().id(),
                                key.communityId(),
                                value.memberCount(),
                                memberIds);
    }

    private Promise<ClusterTopologyStatusResponse> buildTopologyStatus() {
        return Promise.success(assembleTopologyStatus(nodeSupplier.get()));
    }

    private static ClusterTopologyStatusResponse assembleTopologyStatus(ManageableNode node) {
        var topologyConfig = node.topologyConfig();
        var topologyManager = node.topologyManager();
        var connectedPeers = node.connectedPeerIds();
        var allNodeIds = topologyManager.topology();
        var membershipView = node.membershipView();
        var coreNodeIds = allNodeIds.stream().filter(id -> !topologyManager.isPassive(id)).filter(id -> isHealthy(topologyManager,
                                                                                                                  id)).filter(id -> isLiveLifecycle(membershipView,
                                                                                                                                                    id)).map(NodeId::id).toList();
        // §6 D1: slot-derived headcount, capped at clusterSize. Cold-start (no slots yet) →
        // SWIM-derived count. The FSM `countedMembers` count is the post-#110 source the snapshot
        // `coreMembers` was derived from; used as the final fallback when no slots/view present.
        var viewCount = slotDerivedCoreCount(node, membershipView);
        var coreCount = viewCount > 0
                        ? viewCount
                        : node.membershipFsm().countedMembers().size();
        var epoch = Option.some(node.metricsCollector().observedEpoch().toString());
        var workerCount = Math.max(0, connectedPeers.size() - coreCount);
        var nodeDetails = allNodeIds.stream().filter(id -> isLiveLifecycle(membershipView, id)).map(id -> buildNodeDetail(topologyManager,
                                                                                                                          id,
                                                                                                                          connectedPeers.contains(id))).toList();

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

    /// §6 D1 slot-derived headcount. Counts provisioning slots whose occupant is
    /// present. Because the cluster owns exactly `clusterSize` slots and each slot has at
    /// most one occupant, the result is capped at `clusterSize` by construction — a stale
    /// corpse and its fresh replacement that briefly share a slot's identity contribute at
    /// most one (the slot is read once; only its current `assignedNodeId` is classified).
    /// `MembershipView` supplies the per-occupant lifecycle+health classification input.
    ///
    /// **Cold-start fallback.** Before CTM seeds the durable slot map (very-early bootstrap,
    /// self-only formation), there are no slots in KV. Counting zero would under-report the
    /// freshly-bootstrapped leader; fall back to the SWIM-derived `reachableOnDutyCount` so
    /// self-bootstrap still converges. Once slots exist, the slot map is authoritative.
    static int slotDerivedCoreCount(ManageableNode node,
                                    MembershipView view) {
        var occupants = new ArrayList<NodeId>();
        node.kvStore().forEach(ProvisioningSlotKey.class,
                               ProvisioningSlotValue.class,
                               (_, value) -> value.assignedNodeId().onPresent(occupants::add));

        if (occupants.isEmpty()) {
            return reachableOnDutyCount(view);
        }

        int count = 0;
        for (var occupant : occupants) {
            if (isHealthyOccupant(view, occupant)) {
                count++;
            }
        }

        return count;
    }

    /// Per-slot occupancy classification (§5.1 HEALTHY): the slot's occupant counts iff it is
    /// present in the membership view (MembershipView input).
    private static boolean isHealthyOccupant(MembershipView view, NodeId occupant) {
        return view.isPresent(occupant);
    }

    /// Cluster-canonical reachable-and-present count. Reads `MembershipView.presentMembers()`
    /// (KV-canonical); self is always included (SWIM does not observe self locally).
    private static int reachableOnDutyCount(MembershipView view) {
        return view.presentMembers().size();
    }

    private static String topologyMode(TopologyManager tm) {
        return (tm instanceof TopologyObserver observer)
               ? observer.topologyMode()
                         .name()
               : TopologyObserver.TopologyMode.NORMAL.name();
    }

    private static boolean isHealthy(TopologyManager tm, NodeId id) {
        return tm.getState(id)
                 .map(state -> state.health() == NodeHealth.HEALTHY)
                 .or(false);
    }

    /// True when the peer is present in the membership view and keeps a place in the operational
    /// topology. Membership-v2 finale: presence IS being on duty — present peers remain valid
    /// `pick_non_leader` targets, valid CTM provisioning slots, and valid
    /// `/api/cluster/topology` rows.
    private static boolean isLiveLifecycle(MembershipView membershipView, NodeId id) {
        return membershipView.isPresent(id);
    }

    private static TopologyNodeDetail buildNodeDetail(TopologyManager tm, NodeId nodeId, boolean connected) {
        var info = tm.get(nodeId);
        var state = tm.getState(nodeId);
        var role = info.map(NodeInfo::role).map(Enum::name).or("UNKNOWN");
        var health = state.map(NodeState::health).map(Enum::name).or(connected
                                                                     ? "CONNECTED"
                                                                     : "UNKNOWN");
        var hostname = info.flatMap(i -> Option.option(i.labels().get(NodeInfo.LABEL_HOSTNAME))).or("");
        var zone = info.flatMap(i -> Option.option(i.labels().get(NodeInfo.LABEL_ZONE))).or("");
        var address = info.map(i -> i.address()
                                     .asString()).or("");

        return new TopologyNodeDetail(nodeId.id(), role, health, hostname, zone, address);
    }
}
