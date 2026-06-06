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
import org.pragmatica.aether.api.ManagementApiResponses.WhoamiResponse;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.lifecycle.NodeState;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<StatusResponse> route(ManagementRoute.NODE_STATUS).toJson(this::buildStatusResponse),
                         ManagementRoutes.<StatusResponse> route(ManagementRoute.NODE_STATUS_GET)
                                         .withPath(PathParameter.aString())
                                         .to(__ -> Promise.success(buildStatusResponse()))
                                         .asJson(),
                         ManagementRoutes.<NodesResponse> route(ManagementRoute.NODES_LIST).toJson(this::buildNodesResponse),
                         ManagementRoutes.<HealthResponse> route(ManagementRoute.CLUSTER_HEALTH).toJson(this::buildHealthResponse),
                         ManagementRoutes.<LivenessResponse> route(ManagementRoute.HEALTH_LIVE).toJson(this::buildLivenessResponse),
                         ManagementRoutes.<LivenessResponse> route(ManagementRoute.HEALTH_LIVE_GET)
                                         .withPath(PathParameter.aString())
                                         .to(__ -> Promise.success(buildLivenessResponse()))
                                         .asJson(),
                         ManagementRoutes.<ReadinessResponse> route(ManagementRoute.HEALTH_READY).toJson(this::buildReadinessResponse),
                         ManagementRoutes.<ReadinessResponse> route(ManagementRoute.HEALTH_READY_GET)
                                         .withPath(PathParameter.aString())
                                         .to(__ -> Promise.success(buildReadinessResponse()))
                                         .asJson(),
                         ManagementRoutes.<List<ClusterEvent>> route(ManagementRoute.EVENTS)
                                         .withQuery(QueryParameter.aLong("sinceEpoch"),
                                                    QueryParameter.aLong("sinceSeq"))
                                         .to(this::buildEventsResponse)
                                         .asJson(),
                         ManagementRoutes.<CertificateStatusResponse> route(ManagementRoute.CERTIFICATES_LIST).toJson(this::buildCertificateStatusResponse),
                         ManagementRoutes.<WhoamiResponse> route(ManagementRoute.WHOAMI).toJson(StatusRoutes::buildWhoamiResponse));
    }

    static WhoamiResponse buildWhoamiResponse() {
        var ctx = SecurityContextHolder.currentContext().or(SecurityContext::securityContext);

        return new WhoamiResponse(ctx.principal().value(),
                                  ctx.authorizationRole().name(),
                                  ctx.roles().stream().map(Role::value).sorted().toList(),
                                  ctx.isAuthenticated());
    }

    private Promise<List<ClusterEvent>> buildEventsResponse(Option<Long> sinceEpochParam, Option<Long> sinceSeqParam) {
        var aggregator = nodeSupplier.get().eventAggregator();

        if (sinceEpochParam.isEmpty() && sinceSeqParam.isEmpty()) {
            return aggregator.events();
        }
        // BEHAVIOR CHANGE (review C5): the `since` cursor is now an Instant in the namespace-stream
        // events API. The legacy `?sinceSeq=` cursor (an opaque sequence number in rc1) is remapped
        // here by reinterpreting its value as epoch-millis — so an existing caller passing a small
        // sequence number now gets events since ~the Unix epoch instead of since that sequence
        // position. rc1 callers rarely passed sinceEpoch alone; operators should migrate to ISO-8601
        // timestamps. See CHANGELOG.
        var sinceMillis = sinceSeqParam.fold(() -> 0L, seq -> seq);
        return aggregator.eventsSince(Instant.ofEpochMilli(sinceMillis));
    }

    private StatusResponse buildStatusResponse() {
        var node = nodeSupplier.get();
        var uptimeSeconds = node.uptimeSeconds();
        var leader = node.leader();
        var leaderId = leader.map(NodeId::id).or("none");
        // H.2d (spec §H): cluster.nodes derives from MembershipView (SWIM presence) ∪
        // consensus topology. SWIM admits a peer ⇒ it appears here as present without
        // requiring any KV write — the cause of the pre-H "peer alive in SWIM, UNKNOWN in
        // /api/status" stranding bug.
        var view = node.membershipView();
        var topologyNodes = node.topologyManager().topology();
        var allNodeIds = new LinkedHashSet<NodeId>();
        topologyNodes.forEach(allNodeIds::add);
        view.snapshot().keySet().forEach(allNodeIds::add);
        var selfId = node.self();
        // RC1 membership-v2: per-peer state sourced from the NTT-derived generation
        // snapshot's `coreMembers`
        // carries the equivalent per-node lifecycle enum, so the display map is built from
        // the snapshot instead of scanning the lifecycle KV table. Empty when no snapshot has
        // been published yet (cold-start transient window).
        var kvStateMap = reportedStateMap(node);
        var nodeInfos = allNodeIds.stream().map(nodeId -> toNodeInfo(view,
                                                                     nodeId,
                                                                     leader,
                                                                     kvStateMap.getOrDefault(nodeId, ""))).toList();
        var quorate = leader.isPresent() && nodeInfos.size() >= quorumOf(nodeInfos.size());
        var cluster = new ClusterInfo(nodeInfos.size(), leaderId, quorate, nodeInfos);
        var derived = node.snapshotCollector().derivedMetrics();
        var metrics = new MetricsSummary(derived.requestRate(),
                                         100.0 - derived.errorRate() * 100.0,
                                         derived.latencyP50());

        return new StatusResponse(uptimeSeconds,
                                  cluster,
                                  node.sliceStore().loaded().size(),
                                  metrics,
                                  node.self().id(),
                                  "running",

        // runtimeState — JVM/process-level state from the in-memory lifecycle
        // state machine (NodeState: STARTING/JOINING/ACTIVE/DRAINING/STOPPED).
        // Describes "is the process up and serving"; orthogonal to the FSM intent
        // captured in `lifecycleState` below.
        node.nodeLifecycle().currentState().name(),

        // lifecycleState — node-reported work-state from the metrics pong
        // (NodeReportedState: SYNCING/READY/DRAINING). Membership-v2 finale: the synthetic
        // per-node lifecycle KV atom was removed; this surfaces the real, node-authoritative state.
        // Empty string when no pong has been observed yet (cold-start transient window).
        // Mirrors `cluster.nodes[selfId].kvState` for top-level ergonomic access.
        kvStateMap.getOrDefault(selfId, ""),
                                  readClusterPhase(node),
                                  node.isLeader(),
                                  leaderId,
                                  BuildInfo.buildInfo().buildTimestamp(),
                                  BuildInfo.buildInfo().buildVersion());
    }

    private static NodeInfo toNodeInfo(MembershipView view,
                                       NodeId nodeId,
                                       Option<NodeId> leader,
                                       String kvState) {
        var isLeader = leader.map(l -> l.equals(nodeId)).or(false);
        var present = view.isPresent(nodeId);
        // kvState — node-reported work-state (NodeReportedState: SYNCING/READY/DRAINING) from the
        // metrics pong. Empty string when no pong has been observed yet (peer known only via SWIM
        // in the transient window). Despite the legacy field name, this value is NOT read from the
        // KV-Store — it is heartbeat-reported and presence-derived. See
        // aether/docs/specs/membership-architecture-v2-spec.md for the kvState vs
        // derivedStatus contract.
        // derivedStatus — operator-visible projection: present peers surface their real reported
        // work-state (READY when no pong yet); absent peers show UNKNOWN.
        var derivedStatus = present
                            ? presentDisplay(kvState)
                            : "UNKNOWN";

        return new NodeInfo(nodeId.id(), isLeader, kvState, derivedStatus);
    }

    private static String presentDisplay(String reportedState) {
        return reportedState.isEmpty()
               ? NodeReportedState.READY.name()
               : reportedState;
    }

    /// Membership-v2 finale: per-peer work-state display map sourced from the real
    /// node-authoritative `NodeReportedState` (SYNCING / READY / DRAINING) carried on the metrics
    /// pong — the synthetic per-node lifecycle enum was removed. Empty when no pong has been
    /// observed yet (cold-start transient window).
    private static Map<NodeId, String> reportedStateMap(ManageableNode node) {
        return node.metricsCollector()
                   .reportedStates()
                   .entrySet()
                   .stream()
                   .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                         entry -> entry.getValue().name()));
    }

    /// E.6 (spec §7.2): route through `ManageableNode.clusterPhaseSupplier()` so the
    /// dashboard observes the derived `ClusterPhaseView` value (post-E.8: always derived)
    /// is on, and the legacy `ClusterPhaseKey` cache when it's off — a single migration
    /// switch covers all consumers.
    private static String readClusterPhase(ManageableNode node) {
        return node.clusterPhaseSupplier()
                   .get()
                   .name();
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
        var enrichedNodes = nodeIds.stream().map(id -> toEnrichedNodeInfo(id, roleMap, leaderId)).toList();

        return new NodesResponse(enrichedNodes);
    }

    private static Map<String, String> collectNodeRoles(ManageableNode node) {
        var roleMap = new HashMap<String, String>();
        node.kvStore().forEach(ActivationDirectiveKey.class,
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
        var sliceCount = node.sliceStore().loaded().size();
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

    @SuppressWarnings("JBCT-PAT-01")
    public ReadinessResponse buildReadinessResponse() {
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
        return new ComponentHealth("lifecycle",
                                   state.isReady()
                                   ? "UP"
                                   : "DOWN",
                                   "NodeLifecycle state: " + state.name());
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
        var node = nodeSupplier.get();
        return certificateStatus(node.tlsEnabled(), node.certRenewalScheduler());
    }

    static CertificateStatusResponse certificateStatus(boolean tlsEnabled,
                                                       Option<CertificateRenewalScheduler> scheduler) {
        return scheduler.map(s -> toCertificateStatus(tlsEnabled, s))
                        .or(new CertificateStatusResponse(tlsEnabled, "N/A", 0, "N/A", "NOT_CONFIGURED"));
    }

    private static CertificateStatusResponse toCertificateStatus(boolean tlsEnabled,
                                                                 CertificateRenewalScheduler scheduler) {
        return new CertificateStatusResponse(tlsEnabled,
                                             scheduler.currentNotAfter().toString(),
                                             scheduler.secondsUntilExpiry(),
                                             scheduler.lastRenewalAt().toString(),
                                             scheduler.renewalStatus().name());
    }

    private static ComponentHealth buildQuorumHealth(ManageableNode node) {
        var connectedCount = node.connectedNodeCount();
        var hasQuorum = connectedCount + 1 >= 2;

        return new ComponentHealth("quorum",
                                   hasQuorum
                                   ? "UP"
                                   : "DOWN",
                                   "Connected peers: " + connectedCount);
    }
}
