// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwarder;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.messaging.Message;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.api.ManagementApiResponses.BlueprintResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #759 — `POST /api/v1/blueprints/deploy` answered `{"status":"deployed", ...}` unconditionally the
/// moment `publishFromArtifact` committed the blueprint to the KV store, BEFORE any slice attempted
/// to load and with no consultation of `deploymentMap()` at all. An operator reading "deployed" could
/// not tell a verified-running blueprint from one that had not started loading, or from one already
/// sitting on a FAILED instance left by a prior attempt at the same artifact.
///
/// These tests drive the real `SliceRoutes` handler end to end (real route lookup, real handler, real
/// response mapping) over a stubbed `BlueprintService` and a real `DeploymentMap` populated through its
/// own `onNodeArtifactPut` event API, asserting on the actual `BlueprintResponse` the route returns.
class BlueprintDeployStatusTest {
    private static final String COORDS = "org.example:orders-app:1.0.0:blueprint";

    private static final BlueprintId BLUEPRINT_ID = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();

    /// #759 — every response, regardless of status, must point the caller at the terminal-outcome
    /// source rather than leaving "pending" as a dead end with no way to learn what happened next.
    private static final String STATUS_URL = "/api/blueprints/status/org.example%3Aorders-app%3A1.0.0";

    private static final Artifact SLICE_A = Artifact.artifact("org.example:svc-a:1.0.0").unwrap();
    private static final Artifact SLICE_B = Artifact.artifact("org.example:svc-b:1.0.0").unwrap();
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = NodeId.nodeId("node-3").unwrap();
    private static final NodeId NODE_4 = NodeId.nodeId("node-4").unwrap();
    private static final NodeId NODE_5 = NodeId.nodeId("node-5").unwrap();

    private static final ExpandedBlueprint EXPANDED = ExpandedBlueprint.expandedBlueprint(BLUEPRINT_ID,
                                                                                          List.of(ResolvedSlice.resolvedSlice(SLICE_A,
                                                                                                                              2,
                                                                                                                              false).unwrap(),
                                                                                                  ResolvedSlice.resolvedSlice(SLICE_B,
                                                                                                                              3,
                                                                                                                              false).unwrap()));

    /// The core regression: nothing has activated yet (`deploymentMap` is empty for both slices), so
    /// the response must say the request was accepted, not that it is deployed — nothing is verified.
    @Test
    void deployRoute_reportsPending_notDeployed_whenNothingHasActivatedYet() {
        var response = deployWith(Map.of());

        assertThat(response.status()).as("nothing is verified running yet — 'deployed' would be a lie")
                  .isEqualTo("pending");
        assertThat(response.targetInstances()).isEqualTo(5);
        assertThat(response.activeInstances()).isEqualTo(0);
        assertThat(response.failedInstances()).isEqualTo(0);
        assertThat(response.statusUrl()).as("a pending caller must be told where to poll for the terminal outcome")
                  .isEqualTo(STATUS_URL);
    }

    /// A redeploy of the same artifact set where one instance is already sitting FAILED (left over
    /// from a prior attempt, or a BEST_EFFORT deployment that tolerates partial failure). The response
    /// must name the outage rather than say "deployed".
    @Test
    void deployRoute_reportsDegraded_whenAnInstanceIsAlreadyFailed() {
        var response = deployWith(Map.of(SLICE_A,
                                         Map.of(NODE_1, SliceState.ACTIVE, NODE_2, SliceState.FAILED),
                                         SLICE_B,
                                         Map.of(NODE_3, SliceState.ACTIVE)));

        assertThat(response.status()).as("a FAILED instance must never be reported as 'deployed'").isEqualTo("degraded");
        assertThat(response.failedInstances()).isEqualTo(1);
        assertThat(response.activeInstances()).isEqualTo(2);
        assertThat(response.statusUrl()).as("a degraded response must still point at the status endpoint").isEqualTo(STATUS_URL);
    }

    /// Positive control: every target instance is already active (idempotent redeploy of an
    /// unchanged, fully healthy artifact set) and nothing failed — "deployed" is the honest word.
    @Test
    void deployRoute_reportsDeployed_whenEveryTargetInstanceIsAlreadyActive() {
        var response = deployWith(Map.of(SLICE_A,
                                         Map.of(NODE_1, SliceState.ACTIVE, NODE_2, SliceState.ACTIVE),
                                         SLICE_B,
                                         Map.of(NODE_3,
                                                SliceState.ACTIVE,
                                                NODE_4,
                                                SliceState.ACTIVE,
                                                NODE_5,
                                                SliceState.ACTIVE)));

        assertThat(response.status()).isEqualTo("deployed");
        assertThat(response.activeInstances()).isEqualTo(response.targetInstances());
        assertThat(response.failedInstances()).isEqualTo(0);
        assertThat(response.statusUrl()).as("a deployed response must still carry the status endpoint").isEqualTo(STATUS_URL);
    }

    // --- helpers ---
    private static BlueprintResponse deployWith(Map<Artifact, Map<NodeId, SliceState>> deployed) {
        var holder = new AtomicReference<BlueprintResponse>();

        deployRoute(deployed).handler()
                   .handle(new StubRequestContext())
                   .await()
                   .onSuccess(value -> holder.set((BlueprintResponse) value))
                   .onFailure(cause -> fail("Deploy must succeed, got: " + cause.message()));

        return holder.get();
    }

    /// #759 — `.toList()` + a ternary replaces `.findFirst().orElseThrow()`: a missing route is
    /// still a hard test-setup failure (via `fail`, not a `throw` statement), so JBCT-EX-02 no
    /// longer fires without weakening the diagnostic.
    private static Route<?> deployRoute(Map<Artifact, Map<NodeId, SliceState>> deployed) {
        var routes = SliceRoutes.sliceRoutes(() -> nodeOver(deployed))
                                .routes()
                                .filter(candidate -> candidate.name()
                                                              .equals(ManagementRoute.BLUEPRINT_DEPLOY.name()))
                                .toList();

        return routes.isEmpty() ? fail("BLUEPRINT_DEPLOY route not registered") : routes.getFirst();
    }

    private static ManageableNode nodeOver(Map<Artifact, Map<NodeId, SliceState>> deployed) {
        return new DeployManageableNode(fixedBlueprintService(), deploymentMapOver(deployed), noopAppHttpServer());
    }

    /// The sealed `DeploymentMap` interface refuses `Proxy.newProxyInstance` (the JDK rejects dynamic
    /// proxies over sealed interfaces outright), so this builds a real one through its own event API —
    /// the same `onNodeArtifactPut` path the cluster's KV-store listener drives it with in production.
    private static DeploymentMap deploymentMapOver(Map<Artifact, Map<NodeId, SliceState>> deployed) {
        var map = DeploymentMap.deploymentMap();

        deployed.forEach((artifact, byNode) -> byNode.forEach((nodeId, state) -> map.onNodeArtifactPut(nodeArtifactPut(nodeId,
                                                                                                                       artifact,
                                                                                                                       state))));

        return map;
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> nodeArtifactPut(NodeId nodeId,
                                                                                Artifact artifact,
                                                                                SliceState state) {
        var key = new NodeArtifactKey(nodeId, artifact);
        var value = NodeArtifactValue.nodeArtifactValue(state);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    /// `onBlueprintActivated` pushes security overrides through `appHttpServer().httpRoutePublisher()`
    /// on every activation — an empty publisher makes that a no-op, which is all this route's tests need.
    private static AppHttpServer noopAppHttpServer() {
        return new NoopAppHttpServer();
    }

    private static BlueprintService fixedBlueprintService() {
        return new BlueprintService() {
            @Override
            public Promise<ExpandedBlueprint> publish(String dsl) {
                return unsupported("publish");
            }

            @Override
            public Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords) {
                return Promise.success(EXPANDED);
            }

            @Override
            public Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords, boolean registerOnly) {
                return unsupported("publishFromArtifact(registerOnly)");
            }

            @Override
            public Option<ExpandedBlueprint> get(BlueprintId id) {
                return Option.none();
            }

            @Override
            public List<ExpandedBlueprint> list() {
                return List.of();
            }

            @Override
            public Promise<Unit> delete(BlueprintId id) {
                return unsupported("delete");
            }

            @Override
            public Result<Blueprint> validate(String dsl) {
                return unsupported("validate");
            }
        };
    }

    /// #759 — routes through JUnit's generically-typed `fail` rather than a `throw` statement, so
    /// an unexpected call to any collaborator method still fails the test immediately (identical
    /// diagnostic to before) without tripping JBCT-EX-01.
    private static <T> T unsupported(String methodName) {
        return fail("Not touched by the deploy route handler: " + methodName);
    }

    /// #759 review (M1) — hand-written rather than a `Proxy`, matching the rule
    /// `BlueprintStatusAggregationTest.StatusManageableNode` already follows for this exact interface.
    /// The deploy route touches exactly three `ManageableNode` accessors: `blueprintService()` (to
    /// publish the artifact), `deploymentMap()` (to count active/failed instances per slice), and
    /// `appHttpServer()` (to push security overrides on activation) — plus a genuine no-op override
    /// of `route(Message)`, which the route also calls to emit an audit `BlueprintDeployed` event.
    /// All three accessors are record components; every one of the other 51 abstract methods is
    /// hand-written below, routed through `unsupported`, rather than intercepted by a `Proxy`.
    private record DeployManageableNode(BlueprintService blueprintService,
                                        DeploymentMap deploymentMap,
                                        AppHttpServer appHttpServer) implements ManageableNode {
        @Override
        public NodeId self() { return unsupported("self"); }
        @Override
        public KVStore<AetherKey, AetherValue> kvStore() { return unsupported("kvStore"); }
        @Override
        public SliceStore sliceStore() { return unsupported("sliceStore"); }
        @Override
        public ClusterSyncCollector metricsCollector() { return unsupported("metricsCollector"); }
        @Override
        public DeploymentMetricsCollector deploymentMetricsCollector() { return unsupported("deploymentMetricsCollector"); }
        @Override
        public ControlLoop controlLoop() { return unsupported("controlLoop"); }
        @Override
        public MavenProtocolHandler mavenProtocolHandler() { return unsupported("mavenProtocolHandler"); }
        @Override
        public ArtifactStore artifactStore() { return unsupported("artifactStore"); }
        @Override
        public TopologyManager topologyManager() { return unsupported("topologyManager"); }
        @Override
        public MembershipFsm membershipFsm() { return unsupported("membershipFsm"); }
        @Override
        public Epoch currentGenerationEpoch() { return unsupported("currentGenerationEpoch"); }
        @Override
        public InvocationMetricsCollector invocationMetrics() { return unsupported("invocationMetrics"); }
        @Override
        public DeploymentManager deploymentManager() { return unsupported("deploymentManager"); }
        @Override
        public AbTestManager abTestManager() { return unsupported("abTestManager"); }
        @Override
        public HttpRouteRegistry httpRouteRegistry() { return unsupported("httpRouteRegistry"); }
        @Override
        public TTMManager ttmManager() { return unsupported("ttmManager"); }
        @Override
        public ComprehensiveSnapshotCollector snapshotCollector() { return unsupported("snapshotCollector"); }
        @Override
        public ArtifactMetricsCollector artifactMetricsCollector() { return unsupported("artifactMetricsCollector"); }
        @Override
        public ClusterEventAggregator eventAggregator() { return unsupported("eventAggregator"); }
        @Override
        public BackupService backupService() { return unsupported("backupService"); }
        @Override
        public StreamPartitionManager streamPartitionManager() { return unsupported("streamPartitionManager"); }
        @Override
        public StreamReadRouter streamReadRouter() { return unsupported("streamReadRouter"); }
        @Override
        public ConsumerGroupCoordinator consumerGroupCoordinator() { return unsupported("consumerGroupCoordinator"); }
        @Override
        public ConsumerGroupRegistry consumerGroupRegistry() { return unsupported("consumerGroupRegistry"); }
        @Override
        public StreamNamespacesService streamNamespacesService() { return unsupported("streamNamespacesService"); }
        @Override
        public Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver() { return unsupported("taskGroupOwnerResolver"); }
        @Override
        public Map<String, StorageFactory.StorageSetup> storageSetups() { return unsupported("storageSetups"); }
        @Override
        public Option<ClusterTopologyManager> clusterTopologyManager() { return unsupported("clusterTopologyManager"); }
        @Override
        public int observedPeakMembership() { return unsupported("observedPeakMembership"); }
        @Override
        public Option<CertificateRenewalScheduler> certRenewalScheduler() { return unsupported("certRenewalScheduler"); }
        @Override
        public boolean tlsEnabled() { return unsupported("tlsEnabled"); }
        @Override
        public int connectedNodeCount() { return unsupported("connectedNodeCount"); }
        @Override
        public Map<String, Number> transportMetrics() { return unsupported("transportMetrics"); }
        @Override
        public Set<NodeId> connectedPeerIds() { return unsupported("connectedPeerIds"); }
        @Override
        public boolean isLeader() { return unsupported("isLeader"); }
        @Override
        public boolean isReady() { return unsupported("isReady"); }
        @Override
        public Option<NodeId> leader() { return unsupported("leader"); }
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) { return unsupported("apply"); }
        @Override
        public int managementPort() { return unsupported("managementPort"); }
        @Override
        public int appHttpPort() { return unsupported("appHttpPort"); }
        @Override
        public long uptimeSeconds() { return unsupported("uptimeSeconds"); }
        @Override
        public List<NodeId> initialTopology() { return unsupported("initialTopology"); }
        @Override
        public TopologyConfig topologyConfig() { return unsupported("topologyConfig"); }
        @Override
        public InFlightRequestTracker inFlightRequestTracker() { return unsupported("inFlightRequestTracker"); }
        @Override
        public NodeLifecycle nodeLifecycle() { return unsupported("nodeLifecycle"); }
        @Override
        public HlcClock hlcClock() { return unsupported("hlcClock"); }
        @Override
        public Option<DHTClient> dhtClient() { return unsupported("dhtClient"); }
        @Override
        public Option<DHTNode> dhtNode() { return unsupported("dhtNode"); }
        @Override
        public MembershipView membershipView() { return unsupported("membershipView"); }
        @Override
        public Supplier<AetherValue.ClusterPhase> clusterPhaseSupplier() { return unsupported("clusterPhaseSupplier"); }
        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void route(Message message) {}
    }

    /// #759 review (M1) — hand-written rather than a `Proxy`. `onBlueprintActivated` pushes security
    /// overrides through `appHttpServer().httpRoutePublisher()` on every activation; `httpRoutePublisher()`
    /// is the one real accessor this route's tests need — an empty publisher makes the push a no-op.
    /// Every other abstract method is routed through `unsupported`.
    private record NoopAppHttpServer() implements AppHttpServer {
        @Override
        public Option<HttpRoutePublisher> httpRoutePublisher() {
            return Option.none();
        }

        @Override
        public Promise<Unit> start() {
            return unsupported("start");
        }

        @Override
        public Promise<Unit> stop() {
            return unsupported("stop");
        }

        @Override
        public Promise<Unit> rotateCertificate(CertificateBundle newBundle) {
            return unsupported("rotateCertificate");
        }

        @Override
        public Option<Integer> boundPort() {
            return unsupported("boundPort");
        }

        @Override
        @Contract
        public void onRoutePut(ValuePut<HttpNodeRouteKey, HttpNodeRouteValue> valuePut) {
            unsupported("onRoutePut");
        }

        @Override
        @Contract
        public void onRouteRemove(ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue> valueRemove) {
            unsupported("onRouteRemove");
        }

        @Override
        @Contract
        public void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
            unsupported("onNodeRoutesPut");
        }

        @Override
        @Contract
        public void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove) {
            unsupported("onNodeRoutesRemove");
        }

        @Override
        @Contract
        public void onHttpForwardRequest(HttpForwardRequest request) {
            unsupported("onHttpForwardRequest");
        }

        @Override
        @Contract
        public void onHttpForwardResponse(HttpForwardResponse response) {
            unsupported("onHttpForwardResponse");
        }

        @Override
        @Contract
        public void rebuildRouter() {
            unsupported("rebuildRouter");
        }

        @Override
        public boolean isRouteReady() {
            return unsupported("isRouteReady");
        }

        @Override
        @Contract
        public void onQuorumStateChange(ClusterStateNotification notification) {
            unsupported("onQuorumStateChange");
        }

        @Override
        @Contract
        public void onNodeRemoved(MembershipDecision.NodeRemoved nodeRemoved) {
            unsupported("onNodeRemoved");
        }

        @Override
        @Contract
        public void onNodeDecommissioned(MembershipDecision.NodeDecommissioned nodeDecommissioned) {
            unsupported("onNodeDecommissioned");
        }

        @Override
        @Contract
        public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
            unsupported("onSelfShutdown");
        }

        @Override
        public Option<HttpForwarder> httpForwarder() {
            return unsupported("httpForwarder");
        }
    }

    /// #759 review (M1) — hand-written rather than a `Proxy`: `RequestContext` is small and
    /// well-defined, and the body route reads its payload through `fromJson` only; nothing else on
    /// the context is touched before the handler runs.
    private record StubRequestContext() implements RequestContext {
        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            return asRequested(new SliceRoutes.BlueprintDeployRequest(COORDS));
        }

        @SuppressWarnings("unchecked")
        private static <T> Result<T> asRequested(SliceRoutes.BlueprintDeployRequest request) {
            return Result.success((T) request);
        }

        @Override
        public Route<?> route() {
            return unsupported("route");
        }

        @Override
        public List<String> pathParams() {
            return unsupported("pathParams");
        }

        @Override
        public HttpHeaders responseHeaders() {
            return unsupported("responseHeaders");
        }

        @Override
        public String requestId() {
            return unsupported("requestId");
        }

        @Override
        public HttpMethod method() {
            return unsupported("method");
        }

        @Override
        public String path() {
            return unsupported("path");
        }

        @Override
        public Headers headers() {
            return unsupported("headers");
        }

        @Override
        public QueryParams queryParams() {
            return unsupported("queryParams");
        }

        @Override
        public byte[] body() {
            return unsupported("body");
        }
    }
}
