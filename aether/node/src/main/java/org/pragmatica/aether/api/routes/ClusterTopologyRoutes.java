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
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberStatus;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
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
        var node = nodeSupplier.get();

        return Promise.success(node.currentGenerationSnapshot()
                                   .map(snapshot -> assembleTopologyStatus(node, snapshot))
                                   .or(() -> assembleFromTopologyManager(node)));
    }

    private static ClusterTopologyStatusResponse assembleFromTopologyManager(ManageableNode node) {
        var topologyConfig = node.topologyConfig();
        var topologyManager = node.topologyManager();
        var connectedPeers = node.connectedPeerIds();
        var selfId = node.self();
        var allNodeIds = topologyManager.topology();
        var membershipView = node.membershipView();
        // Filter out STOPPED / UNTRACKED entries — these are peers
        // whose containers no longer exist but whose KV topology entry hasn't been GC'd
        // yet. Without this filter, killed-then-replaced peers leak into the topology
        // response as ACTIVE/HEALTHY, breaking `pick_non_leader` and any consumer that
        // counts on-duty cores from the topology projection (12-network, 02-chaos).
        var coreNodeIds = allNodeIds.stream().filter(id -> !topologyManager.isPassive(id)).filter(id -> isHealthy(topologyManager,
                                                                                                                  id)).filter(id -> isLiveLifecycle(membershipView,
                                                                                                                                                    id)).map(NodeId::id).toList();
        // H.2 (spec §H): coreCount derives from MembershipView (SWIM ∪ KV-overrides).
        // RC1 reachability-aggregator landing: replace per-reader local QUIC view
        // (network.connectedPeers()) with the leader-broadcast cluster-canonical
        // snapshot. Cold-start fallback: when no snapshot is cached yet, use the
        // KV-only ON_DUTY count (no transport filter). See
        // aether/docs/specs/reachability-aggregator-spec.md Layer 5.
        var coreCount = reachableOnDutyCount(node.membershipView(),
                                             node.metricsCollector().lastReachabilitySnapshot(),
                                             selfId);
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
                                                 Option.<String> none(),
                                                 topologyMode(topologyManager));
    }

    private static ClusterTopologyStatusResponse assembleTopologyStatus(ManageableNode node,
                                                                        ClusterGenerationSnapshot snapshot) {
        var topologyConfig = node.topologyConfig();
        var topologyManager = node.topologyManager();
        var connectedPeers = node.connectedPeerIds();
        var selfId = node.self();
        var allNodeIds = topologyManager.topology();
        var membershipView = node.membershipView();
        var coreNodeIds = allNodeIds.stream().filter(id -> !topologyManager.isPassive(id)).filter(id -> isHealthy(topologyManager,
                                                                                                                  id)).filter(id -> isLiveLifecycle(membershipView,
                                                                                                                                                    id)).map(NodeId::id).toList();
        // H.2 (spec §H): prefer the MembershipView-derived count; fall back to the snapshot
        // helper if the view is empty (e.g., during very-early bootstrap before SWIM has
        // admitted self). The snapshot helper also now honours SWIM-derived ON_DUTY via the
        // healthHint check (no KV ON_DUTY required) so both paths converge.
        // RC1 reachability-aggregator landing: see assembleFromTopologyManager.
        var viewCount = reachableOnDutyCount(node.membershipView(),
                                             node.metricsCollector().lastReachabilitySnapshot(),
                                             selfId);
        var coreCount = viewCount > 0
                        ? viewCount
                        : snapshotCoreCount(snapshot);
        var epoch = Option.some(snapshot.epoch().toString());
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

    /// Cluster-canonical reachable-and-on-duty count.
    ///
    /// Reads `MembershipView.onDutyPeers()` (KV-canonical) intersected with the
    /// leader-broadcast `AggregatedReachabilitySnapshot` (cluster-canonical
    /// transport reachability). When the snapshot is `Option.none()` (cold-start
    /// window or pre-extension peers), falls back to KV-only counting — accepting
    /// that a recently-killed peer may briefly count until SWIM detection +
    /// HealthReconciler write catch up (~10-30s worst case during cluster
    /// formation; bounded by ping-pong cadence at steady state).
    ///
    /// Self is always counted (SWIM does not observe self locally).
    private static int reachableOnDutyCount(MembershipView view,
                                            Option<AggregatedReachabilitySnapshot> snapshot,
                                            NodeId selfId) {
        int count = 0;

        for (NodeId peer : view.onDutyPeers()) {
            if (peer.equals(selfId) || isReachable(snapshot, peer)) {
                count++;
            }
        }

        return count;
    }

    private static boolean isReachable(Option<AggregatedReachabilitySnapshot> snapshot, NodeId peer) {
        return snapshot.fold(() -> true, s -> s.isReachable(peer));
    }

    private static String topologyMode(TopologyManager tm) {
        return (tm instanceof TopologyObserver observer)
               ? observer.topologyMode()
                         .name()
               : TopologyObserver.TopologyMode.NORMAL.name();
    }

    private static int snapshotCoreCount(ClusterGenerationSnapshot snapshot) {
        // H.2 callers should use `node.membershipView().onDutyPeers().size()`. This helper
        // remains for the snapshot path; counts members whose lifecycle is `ON_DUTY` OR
        // whose `healthHint` is `HEALTHY` (i.e. SWIM-derived: lifecycle may be absent or
        // any non-terminal state). Falls back to the legacy strict-`ON_DUTY` predicate
        // when neither is present.
        return (int) snapshot.coreMembers()
                             .values()
                             .stream()
                             .filter(member -> isEffectiveOnDuty(member))
                             .count();
    }

    private static boolean isEffectiveOnDuty(org.pragmatica.aether.slice.generation.CoreMember member) {
        if (member.lifecycle() == NodeLifecycleState.DRAINING || member.lifecycle() == NodeLifecycleState.STOPPED) {
            return false;
        }
        return member.healthHint() == HealthHint.HEALTHY || member.lifecycle() == NodeLifecycleState.ON_DUTY;
    }

    private static boolean isHealthy(TopologyManager tm, NodeId id) {
        return tm.getState(id)
                 .map(state -> state.health() == NodeHealth.HEALTHY)
                 .or(false);
    }

    /// True when the peer's effective lifecycle keeps it in the operational topology.
    /// Excludes STOPPED / UNTRACKED — KV entries for peers whose
    /// containers no longer exist but whose topology row hasn't yet been GC'd.
    /// JOINING / ON_DUTY / DRAINING all count as "live" — they remain valid `pick_non_leader`
    /// targets, valid CTM provisioning slots, and valid `/api/cluster/topology` rows.
    private static boolean isLiveLifecycle(MembershipView membershipView, NodeId id) {
        var status = membershipView.statusOf(id);

        return status == MemberStatus.JOINING || status == MemberStatus.ON_DUTY || status == MemberStatus.DRAINING;
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
