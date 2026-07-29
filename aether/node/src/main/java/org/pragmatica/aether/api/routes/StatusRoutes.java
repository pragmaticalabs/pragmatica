// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.net.InetSocketAddress;
import java.net.Socket;
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

import org.pragmatica.aether.api.BuildInfo;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ManagementApiResponses.CertificateStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterEventView;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ComponentHealth;
import org.pragmatica.aether.api.ManagementApiResponses.EnrichedNodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.HealthResponse;
import org.pragmatica.aether.api.ManagementApiResponses.LivenessResponse;
import org.pragmatica.aether.api.ManagementApiResponses.LiveNodeEntry;
import org.pragmatica.aether.api.ManagementApiResponses.LiveNodesResponse;
import org.pragmatica.aether.api.ManagementApiResponses.MetricsSummary;
import org.pragmatica.aether.api.ManagementApiResponses.NodeEndpointResponse;
import org.pragmatica.aether.api.ManagementApiResponses.NodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.NodesResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ReadinessResponse;
import org.pragmatica.aether.api.ManagementApiResponses.StatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.WhoamiResponse;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
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
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


public final class StatusRoutes implements RouteSource {
    /// A1 TCP reachability probe timeout (spec §8 Q2: A1 is scoped to single-node point queries,
    /// so a short blocking probe per call is acceptable). Matches the harness `_resolve_live_endpoint`
    /// connect budget.
    private static final int PROBE_TIMEOUT_MS = 2000;
    /// Standard label key carrying a node's CORE/WORKER/SPOT role in the consensus `NodeInfo`
    /// (mirrors `org.pragmatica.consensus.net.NodeInfo.LABEL_ROLE`; referenced by literal to avoid
    /// a type-name clash with the `ManagementApiResponses.NodeInfo` wire record imported here).
    private static final String ROLE_LABEL = "role";

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
                         ManagementRoutes.<NodeEndpointResponse> route(ManagementRoute.NODE_ENDPOINT_GET)
                                         .withPath(PathParameter.aString())
                                         .to(id -> Promise.success(buildNodeEndpointResponse(id)))
                                         .asJson(),
                         ManagementRoutes.<LiveNodesResponse> route(ManagementRoute.NODES_LIVE).toJson(this::buildLiveNodesResponse),
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
                         ManagementRoutes.<List<ClusterEventView>> route(ManagementRoute.EVENTS)
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

    private Promise<List<ClusterEventView>> buildEventsResponse(Option<Long> sinceEpochParam,
                                                                Option<Long> sinceSeqParam) {
        var aggregator = nodeSupplier.get().eventAggregator();

        if (sinceEpochParam.isEmpty() && sinceSeqParam.isEmpty()) {
            return aggregator.events()
                             .map(StatusRoutes::toEventViews);
        }
        // BEHAVIOR CHANGE (review C5): the `since` cursor is now an Instant in the namespace-stream
        // events API. The legacy `?sinceSeq=` cursor (an opaque sequence number in rc1) is remapped
        // here by reinterpreting its value as epoch-millis — so an existing caller passing a small
        // sequence number now gets events since ~the Unix epoch instead of since that sequence
        // position. rc1 callers rarely passed sinceEpoch alone; operators should migrate to ISO-8601
        // timestamps. See CHANGELOG.
        var sinceMillis = sinceSeqParam.fold(() -> 0L, seq -> seq);

        return aggregator.eventsSince(Instant.ofEpochMilli(sinceMillis))
                         .map(StatusRoutes::toEventViews);
    }

    /// Project the sealed {@link ClusterEvent} list onto the self-describing
    /// {@link ClusterEventView} wire shape, surfacing each variant's `type()` discriminator
    /// alongside the existing at/severity/summary/details fields.
    private static List<ClusterEventView> toEventViews(List<ClusterEvent> events) {
        return events.stream()
                     .map(StatusRoutes::toEventView)
                     .toList();
    }

    private static ClusterEventView toEventView(ClusterEvent event) {
        return new ClusterEventView(event.type(), event.at(), event.severity(), event.summary(), event.details());
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
        var nodeInfos = allNodeIds.stream()
                                  .map(nodeId -> toNodeInfo(view,
                                                            nodeId,
                                                            leader,
                                                            kvStateMap.getOrDefault(nodeId, "")))
                                  .toList();
        var quorate = leader.isPresent() && quorumStatus(node).held();
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

    private static NodeInfo toNodeInfo(MembershipView view, NodeId nodeId, Option<NodeId> leader, String kvState) {
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
                                                         entry -> entry.getValue()
                                                                       .name()));
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

    /// Single source of truth for the node's quorum posture, shared by `/api/status`
    /// (`cluster.quorate`), `/api/health` (`quorum`) and `/health/ready` (the `quorum`
    /// component) so the three surfaces never disagree. `held` is true iff the counted strict
    /// core-member set meets the consensus simple-majority threshold (`coreCount / 2 + 1`) — a
    /// minority partition (e.g. 2 of 5) correctly reports NOT held, unlike the retired
    /// "connected to at least one peer" (`>= 2`) check that let a minority claim quorum.
    record QuorumStatus(boolean held, int countedMembers, int requiredThreshold) {
        static QuorumStatus from(QuorumLossSnapshot snapshot) {
            return new QuorumStatus(!snapshot.belowThreshold(),
                                    snapshot.strictMemberCount(),
                                    snapshot.requiredThreshold());
        }

        String detail() {
            return "Counted core members: " + countedMembers + " / required: " + requiredThreshold;
        }
    }

    /// Prefer the per-node `QuorumLossDetector` snapshot — the SAME signal the minority
    /// self-drain latches on, so the management surfaces cannot disagree with the consensus
    /// layer. Before the detector is wired (cold-start window) or on a `ManageableNode` test
    /// proxy that omits it, fall back to the configured-core simple-majority over the FSM's
    /// counted core members (never raw connection counts — a worker must not inflate the numerator).
    static QuorumStatus quorumStatus(ManageableNode node) {
        return node.quorumLossSnapshot()
                   .map(QuorumStatus::from)
                   .or(() -> fallbackQuorumStatus(node));
    }

    private static QuorumStatus fallbackQuorumStatus(ManageableNode node) {
        var counted = node.membershipFsm().coreCountedMembers().size();
        var required = quorumOf(node.initialTopology().size());

        return new QuorumStatus(counted >= required, counted, required);
    }

    /// A1 (harness-resilience spec §5) — resolves the requested nodeId to its cluster-transport
    /// `host:port` and a best-effort TCP reachability probe. The `NODE_ENDPOINT_GET` route is
    /// `nodeIdParam`-targeted, so the forwarder has already delivered the request to the named
    /// node — `topologyManager().get(nodeId)` resolves the local node's own `NodeInfo`, falling
    /// back to `self()` (covers the cold-start window before the node registers itself in its own
    /// topology view).
    private NodeEndpointResponse buildNodeEndpointResponse(String nodeId) {
        var node = nodeSupplier.get();
        var topology = node.topologyManager();
        var address = NodeId.nodeId(nodeId)
                            .option()
                            .flatMap(topology::get)
                            .map(info -> info.address())
                            .or(topology.self().address());

        return new NodeEndpointResponse(nodeId,
                                        address.asString(),
                                        probeReachable(address.host(), address.port()));
    }

    /// Best-effort TCP connect probe (spec A1: "same probe `_resolve_live_endpoint` already
    /// performs"). A connect failure collapses to `reachable=false` rather than an error channel —
    /// the endpoint is reported regardless so the caller learns where to dial.
    private static boolean probeReachable(String host, int port) {
        return tcpConnect(host, port).isSuccess();
    }

    /// Adapter leaf: opens a short-lived TCP socket to `host:port` and returns `Unit` on a
    /// successful connect. A connect failure is lifted into a `Result` failure (inspected by
    /// {@link #probeReachable}). The socket is always closed (try-with-resources).
    private static Result<Unit> tcpConnect(String host, int port) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               try (var socket = new Socket()) {
                               socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);

                               return Unit.unit();
                           }
                           });
    }

    /// A2 (harness-resilience spec §5) — unified live-node document. Joins three sources:
    /// consensus topology (`address` + `role`), the SWIM-derived `MembershipView` (`swimAlive`),
    /// and the node-reported work-state map (`reportedState`). The node universe is the union of
    /// topology and reported-state keys, so a peer present in either appears. Zombies — present in
    /// reported-state but absent from both topology and the SWIM view — surface with
    /// `swimAlive=false` and `address=null`.
    private LiveNodesResponse buildLiveNodesResponse() {
        var node = nodeSupplier.get();
        var view = node.membershipView();
        var reportedStates = reportedStateMap(node);
        var topology = node.topologyManager();
        var universe = new LinkedHashSet<NodeId>();

        topology.topology().forEach(universe::add);
        reportedStates.keySet().forEach(universe::add);
        var entries = universe.stream()
                              .map(id -> toLiveNodeEntry(view,
                                                         topology,
                                                         id,
                                                         reportedStates.getOrDefault(id, "")))
                              .toList();
        var liveCount = (int) entries.stream().filter(LiveNodeEntry::swimAlive).count();

        return new LiveNodesResponse(entries, liveCount, entries.size() - liveCount);
    }

    static LiveNodeEntry toLiveNodeEntry(MembershipView view,
                                         TopologyManager topology,
                                         NodeId nodeId,
                                         String reportedState) {
        var info = topology.get(nodeId);
        var swimAlive = view.isPresent(nodeId);
        var address = info.map(i -> i.address()
                                     .asString());
        var role = info.map(i -> i.labels()
                                  .getOrDefault(ROLE_LABEL, ActivationDirectiveValue.CORE))
                       .or(ActivationDirectiveValue.CORE);

        return new LiveNodeEntry(nodeId.id(), address, role, swimAlive, reportedState);
    }

    private NodesResponse buildNodesResponse() {
        var node = nodeSupplier.get();
        var metrics = node.metricsCollector().allMetrics();
        var nodeIds = new LinkedHashSet<String>();

        nodeIds.add(node.self().id());
        node.connectedPeerIds().forEach(nid -> nodeIds.add(nid.id()));
        for (NodeId nodeId : metrics.keySet()) {
            nodeIds.add(nodeId.id());
        }

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
        var sliceCount = node.sliceStore().loaded().size();
        var ready = node.isReady();
        var totalNodes = connectedNodeCount + 1;
        var hasQuorum = quorumStatus(node).held();
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
                        .or(new CertificateStatusResponse(tlsEnabled, "", 0, "", "NOT_CONFIGURED"));
    }

    private static CertificateStatusResponse toCertificateStatus(boolean tlsEnabled,
                                                                 CertificateRenewalScheduler scheduler) {
        // app-TLS NOT_CONFIGURED is a presentation-layer fact keyed on tlsEnabled;
        // the scheduler legitimately renews the cluster-transport cert regardless.
        // expiresAt/lastRenewalAt MUST be empty for NOT_CONFIGURED (management-api §Certificate
        // Status / observability C15): a not-configured cert has no expiry to report.
        if (!tlsEnabled) {
            return new CertificateStatusResponse(false, "", 0, "", "NOT_CONFIGURED");
        }

        return new CertificateStatusResponse(tlsEnabled,
                                             scheduler.currentNotAfter().toString(),
                                             scheduler.secondsUntilExpiry(),
                                             scheduler.lastRenewalAt().toString(),
                                             scheduler.renewalStatus().name());
    }

    private static ComponentHealth buildQuorumHealth(ManageableNode node) {
        var quorum = quorumStatus(node);

        return new ComponentHealth("quorum",
                                   quorum.held()
                                   ? "UP"
                                   : "DOWN",
                                   quorum.detail());
    }
}
