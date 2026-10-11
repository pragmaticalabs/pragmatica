// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;
import java.util.concurrent.CopyOnWriteArrayList;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
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
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.deployment.cluster.PublishedBlueprint;
import org.pragmatica.aether.deployment.validation.StreamValidationFailure;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
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

/// #1497 — the CLI/REST deploy path (`POST /api/v1/scale`, `SliceRoutes.applyDeployCommand`) used to write a
/// slice's FIRST target with `minInstances == instances`. The #1488 drain guard then could never drain an
/// owner of that slice, so a surplus drain deferred forever. Owner ruling (2026-09-25): every writer with no
/// explicit value uses the blueprint default `ceil(instances/2)`.
///
/// Driven through the real `ManagementRouter.handle` over a real, empty `KVStore` (so the route takes its
/// first-write branch) and a stub node that records what `apply` was handed. The slice is listed by an active
/// blueprint, which the route requires before it will write a target at all.
class SliceRoutesScaleFloorTest {
    private static final Artifact SLICE = Artifact.artifact("org.example:svc-a:1.0.0").unwrap();
    private static final BlueprintId BLUEPRINT_ID = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final ExpandedBlueprint EXPANDED = ExpandedBlueprint.expandedBlueprint(BLUEPRINT_ID,
                                                                                          List.of(ResolvedSlice.resolvedSlice(SLICE,
                                                                                                                              3,
                                                                                                                              false).unwrap()));

    @Test
    void scaleRoute_firstWrite_takesTheDefaultFloor_notTheInstanceCount() {
        var target = firstWriteFor(4);

        assertThat(target.targetInstances()).isEqualTo(4);
        assertThat(target.minInstances()).as("#1497: ceil(4/2), not the instance count")
                                         .isEqualTo(2);
    }

    @Test
    void scaleRoute_firstWrite_oddCount_roundsTheFloorUp() {
        assertThat(firstWriteFor(5).minInstances()).isEqualTo(3);
    }

    private static SliceTargetValue firstWriteFor(int instances) {
        var applied = new CopyOnWriteArrayList<KVCommand<AetherKey>>();
        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var node = new ScaleManageableNode(listingBlueprintService(), kvStore, applied);
        var router = ManagementRouter.managementRouter(SliceRoutes.sliceRoutes(() -> node));
        var recorder = new RecordingResponseWriter();
        var request = "{\"artifact\":\"" + SLICE.asString() + "\",\"instances\":" + instances + "}";

        assertThat(router.handle(postRequest(ManagementRoute.SLICE_SCALE.prefix(), request), recorder)).isTrue();
        assertThat(recorder.status.get()).as(recorder.body())
                                         .isEqualTo(HttpStatus.OK);

        var targets = applied.stream()
                             .filter(KVCommand.Put.class::isInstance)
                             .map(command -> ((KVCommand.Put<?, ?>) command).value())
                             .filter(SliceTargetValue.class::isInstance)
                             .map(SliceTargetValue.class::cast)
                             .toList();

        assertThat(targets).as("the scale route must write exactly one slice target").hasSize(1);

        return targets.getFirst();
    }

    private static BlueprintService listingBlueprintService() {
        return new BlueprintService() {
            @Override
            public Promise<PublishedBlueprint> publish(String dsl) {
                return unsupported("publish");
            }

            @Override
            public Promise<PublishedBlueprint> publishFromArtifact(String artifactCoords) {
                return unsupported("publishFromArtifact");
            }

            @Override
            public Promise<PublishedBlueprint> publishFromArtifact(String artifactCoords, boolean registerOnly) {
                return unsupported("publishFromArtifact");
            }

            @Override
            public Option<ExpandedBlueprint> get(BlueprintId id) {
                return unsupported("get");
            }

            @Override
            public Option<DeploymentOutcomeValue> attributedOutcome(BlueprintId id) {
                return unsupported("attributedOutcome");
            }

            @Override
            public Option<DeploymentOutcomeValue> outcome(BlueprintId id) {
                return unsupported("outcome");
            }

            @Override
            public List<ExpandedBlueprint> list() {
                return List.of(EXPANDED);
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

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    private static HttpRequest postRequest(String path, String body) {
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "req_1497";
            }

            @Override
            public HttpMethod method() {
                return HttpMethod.POST;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public Headers headers() {
                return Headers.empty();
            }

            @Override
            public QueryParams queryParams() {
                return QueryParams.empty();
            }

            @Override
            public byte[] body() {
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static final class RecordingResponseWriter implements ResponseWriter {
        private final AtomicReference<HttpStatus> status = new AtomicReference<>();
        private final AtomicReference<byte[]> body = new AtomicReference<>(new byte[0]);

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status.set(status);
            this.body.set(body);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }

        String body() {
            return new String(body.get(), StandardCharsets.UTF_8);
        }
    }

    private static <T> T unsupported(String methodName) {
        return fail("Not touched by the scale route handler: " + methodName);
    }

    private record ScaleManageableNode(BlueprintService blueprintService,
                                       KVStore<AetherKey, AetherValue> kvStore,
                                       List<KVCommand<AetherKey>> applied) implements ManageableNode {
        @Override
        public NodeId self() { return unsupported("self"); }
        @Override
        public SliceStore sliceStore() { return unsupported("sliceStore"); }
        @Override
        public DeploymentMap deploymentMap() { return unsupported("deploymentMap"); }
        @Override
        public AppHttpServer appHttpServer() { return unsupported("appHttpServer"); }
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
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applied.addAll(commands);

            return Promise.success(List.of());
        }
        @Override
        public SchemaOrchestratorService schemaOrchestrator() { return unsupported("schemaOrchestrator"); }
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
}
