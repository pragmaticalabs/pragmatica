// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.BuildInfo;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ManagementApiResponses.CertificateStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ComponentHealth;
import org.pragmatica.aether.api.ManagementApiResponses.EnrichedNodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.HealthResponse;
import org.pragmatica.aether.api.ManagementApiResponses.LivenessResponse;
import org.pragmatica.aether.api.ManagementApiResponses.MetricsSummary;
import org.pragmatica.aether.api.ManagementApiResponses.NodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.NodesResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ReadinessResponse;
import org.pragmatica.aether.api.ManagementApiResponses.StatusResponse;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.lifecycle.NodeState;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;


public final class StatusRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;
    private final Supplier<AppHttpServer> appHttpServerSupplier;

    private StatusRoutes(Supplier<ManageableNode> nodeSupplier, Supplier<AppHttpServer> appHttpServerSupplier) {
        this.nodeSupplier = nodeSupplier;
        this.appHttpServerSupplier = appHttpServerSupplier;
    }

    public static StatusRoutes statusRoutes(Supplier<ManageableNode> nodeSupplier,
                                            Supplier<AppHttpServer> appHttpServerSupplier) {
        return new StatusRoutes(nodeSupplier, appHttpServerSupplier);
    }

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<StatusResponse>route(ManagementRoute.CLUSTER_STATUS)
                                         .toJson(this::buildStatusResponse),
                         ManagementRoutes.<NodesResponse>route(ManagementRoute.NODES_LIST)
                                         .toJson(this::buildNodesResponse),
                         ManagementRoutes.<HealthResponse>route(ManagementRoute.CLUSTER_HEALTH)
                                         .toJson(this::buildHealthResponse),
                         ManagementRoutes.<LivenessResponse>route(ManagementRoute.HEALTH_LIVE)
                                         .toJson(this::buildLivenessResponse),
                         ManagementRoutes.<ReadinessResponse>route(ManagementRoute.HEALTH_READY)
                                         .toJson(this::buildReadinessResponse),
                         ManagementRoutes.<List<ClusterEvent>>route(ManagementRoute.EVENTS)
                                         .<String>withQuery(QueryParameter.aString("since"))
                                         .toValue(this::buildEventsResponse)
                                         .asJson(),
                         ManagementRoutes.<CertificateStatusResponse>route(ManagementRoute.CERTIFICATE)
                                         .toJson(this::buildCertificateStatusResponse));
    }

    private List<ClusterEvent> buildEventsResponse(Option<String> sinceParam) {
        var aggregator = nodeSupplier.get().eventAggregator();
        return sinceParam.map(StatusRoutes::parseInstant).map(aggregator::eventsSince)
                             .or(aggregator.events());
    }

    private static Instant parseInstant(String raw) {
        return Instant.parse(raw);
    }

    private StatusResponse buildStatusResponse() {
        var node = nodeSupplier.get();
        var uptimeSeconds = node.uptimeSeconds();
        var leader = node.leader();
        var leaderId = leader.map(NodeId::id).or("none");
        var topologyNodes = node.topologyManager().topology();
        var nodeInfos = topologyNodes.stream().map(nodeId -> toNodeInfo(node, nodeId, leader))
                                            .toList();
        var quorate = leader.isPresent() && nodeInfos.size() >= quorumOf(nodeInfos.size());
        var cluster = new ClusterInfo(nodeInfos.size(), leaderId, quorate, nodeInfos);
        var derived = node.snapshotCollector().derivedMetrics();
        var metrics = new MetricsSummary(derived.requestRate(),
                                         100.0 - derived.errorRate() * 100.0,
                                         derived.latencyP50());
        return new StatusResponse(uptimeSeconds,
                                  cluster,
                                  node.sliceStore().loaded()
                                                 .size(),
                                  metrics,
                                  node.self().id(),
                                  "running",
                                  node.nodeLifecycle().currentState()
                                                    .name(),
                                  readClusterPhase(node),
                                  node.isLeader(),
                                  leaderId,
                                  BuildInfo.buildInfo().buildTimestamp(),
                                  BuildInfo.buildInfo().buildVersion());
    }

    private static NodeInfo toNodeInfo(ManageableNode node, NodeId nodeId, Option<NodeId> leader) {
        var isLeader = leader.map(l -> l.equals(nodeId)).or(false);
        var lifecycleState = node.kvStore().get(NodeLifecycleKey.nodeLifecycleKey(nodeId))
                                         .filter(NodeLifecycleValue.class::isInstance)
                                         .map(NodeLifecycleValue.class::cast)
                                         .map(v -> v.state().name())
                                         .or("UNKNOWN");
        return new NodeInfo(nodeId.id(), isLeader, lifecycleState);
    }

    /// E.6 (spec §7.2): route through `ManageableNode.clusterPhaseSupplier()` so the
    /// dashboard observes the derived `ClusterPhaseView` value (post-E.8: always derived)
    /// is on, and the legacy `ClusterPhaseKey` cache when it's off — a single migration
    /// switch covers all consumers.
    private static String readClusterPhase(ManageableNode node) {
        return node.clusterPhaseSupplier().get().name();
    }

    private static int quorumOf(int n) {
        return n / 2 + 1;
    }

    private NodesResponse buildNodesResponse() {
        var node = nodeSupplier.get();
        var metrics = node.metricsCollector().allMetrics();
        var nodeIds = new LinkedHashSet<String>();
        nodeIds.add(node.self().id());
        node.connectedPeerIds().forEach(nid -> nodeIds.add(nid.id()));
        for (NodeId nodeId : metrics.keySet()) {nodeIds.add(nodeId.id());}
        node.kvStore().forEach(SliceNodeKey.class,
                               SliceNodeValue.class,
                               (key, _) -> nodeIds.add(key.nodeId().id()));
        var roleMap = collectNodeRoles(node);
        var leaderId = node.leader().map(NodeId::id);
        var enrichedNodes = nodeIds.stream().map(id -> toEnrichedNodeInfo(id, roleMap, leaderId))
                                          .toList();
        return new NodesResponse(enrichedNodes);
    }

    private static Map<String, String> collectNodeRoles(ManageableNode node) {
        var roleMap = new HashMap<String, String>();
        node.kvStore()
                    .forEach(ActivationDirectiveKey.class,
                             ActivationDirectiveValue.class,
                             (key, value) -> roleMap.put(key.nodeId().id(),
                                                         value.role()));
        return roleMap;
    }

    private static EnrichedNodeInfo toEnrichedNodeInfo(String id,
                                                       Map<String, String> roleMap,
                                                       Option<String> leaderId) {
        var role = roleMap.getOrDefault(id, ActivationDirectiveValue.CORE);
        var isLeader = leaderId.map(id::equals).or(false);
        return new EnrichedNodeInfo(id, role, isLeader);
    }

    private HealthResponse buildHealthResponse() {
        var node = nodeSupplier.get();
        var metrics = node.metricsCollector().allMetrics();
        var metricsNodeCount = metrics.size();
        var connectedNodeCount = node.connectedNodeCount();
        var sliceCount = node.sliceStore().loaded()
                                        .size();
        var ready = node.isReady();
        var totalNodes = connectedNodeCount + 1;
        var hasQuorum = totalNodes >= 2;
        var status = !ready || !hasQuorum
                    ? "unhealthy"
                    : "healthy";
        return new HealthResponse(status,
                                  ready,
                                  hasQuorum,
                                  totalNodes,
                                  connectedNodeCount,
                                  metricsNodeCount,
                                  sliceCount,
                                  BuildInfo.buildInfo().buildTimestamp());
    }

    public LivenessResponse buildLivenessResponse() {
        var node = nodeSupplier.get();
        var nodeId = node.self().id();
        var state = node.nodeLifecycle().currentState();
        var status = state.isLive()
                    ? "UP"
                    : "DOWN";
        return new LivenessResponse(status, nodeId, state.name(), state.isReady());
    }

    @SuppressWarnings("JBCT-PAT-01") public ReadinessResponse buildReadinessResponse() {
        var node = nodeSupplier.get();
        var nodeId = node.self().id();
        var state = node.nodeLifecycle().currentState();
        var components = new ArrayList<ComponentHealth>();
        components.add(buildLifecycleHealth(state));
        components.add(buildConsensusHealth(node));
        components.add(buildRoutesHealth());
        components.add(buildQuorumHealth(node));
        var status = state.isReady()
                    ? "UP"
                    : "DOWN";
        return new ReadinessResponse(status, nodeId, state.name(), state.isReady(), List.copyOf(components));
    }

    private static ComponentHealth buildLifecycleHealth(NodeState state) {
        return new ComponentHealth("lifecycle", state.isReady()
                                               ? "UP"
                                               : "DOWN", "NodeLifecycle state: " + state.name());
    }

    private static ComponentHealth buildConsensusHealth(ManageableNode node) {
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
        var routesReady = appHttpServerSupplier.get().isRouteReady();
        return new ComponentHealth("routes",
                                   routesReady
                                   ? "UP"
                                   : "DOWN",
                                   routesReady
                                   ? "Route sync received"
                                   : "Awaiting initial route sync");
    }

    private CertificateStatusResponse buildCertificateStatusResponse() {
        return nodeSupplier.get().certRenewalScheduler()
                               .map(StatusRoutes::toCertificateStatus)
                               .or(new CertificateStatusResponse("N/A", 0, "N/A", "NOT_CONFIGURED"));
    }

    private static CertificateStatusResponse toCertificateStatus(CertificateRenewalScheduler scheduler) {
        return new CertificateStatusResponse(scheduler.currentNotAfter().toString(),
                                             scheduler.secondsUntilExpiry(),
                                             scheduler.lastRenewalAt().toString(),
                                             scheduler.renewalStatus().name());
    }

    private static ComponentHealth buildQuorumHealth(ManageableNode node) {
        var connectedCount = node.connectedNodeCount();
        var hasQuorum = connectedCount + 1 >= 2;
        return new ComponentHealth("quorum", hasQuorum
                                            ? "UP"
                                            : "DOWN", "Connected peers: " + connectedCount);
    }
}
