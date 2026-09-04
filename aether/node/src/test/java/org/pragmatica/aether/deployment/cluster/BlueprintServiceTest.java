// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.routes.SliceRoutes;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
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
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.Message;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;

import io.netty.handler.codec.http.HttpHeaders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.pragmatica.aether.api.ManagementApiResponses.BlueprintStatusResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class BlueprintServiceTest {

    private BlueprintService service;
    private TestClusterNode cluster;
    private TestKVStore store;
    private Repository repository;

    @BeforeEach
    void setup() {
        cluster = new TestClusterNode();
        store = new TestKVStore();
        cluster.setStore(store);
        // Repository is not used in tests since we test get/list/delete directly
        repository = artifact -> Causes.cause("Repository not used in tests").promise();

        service = BlueprintService.blueprintService(cluster, store, repository);
    }

    @Nested
    class PublishTests {
        // Note: publish() requires actual JAR files for dependency resolution.
        // These tests verify parser validation only.

        @Test
        void publish_fails_forInvalidDsl() {
            var dsl = "invalid blueprint";

            service.publish(dsl)
                   .await()
                   .onSuccessRun(() -> fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
        }

        @Test
        void publish_fails_forMissingHeader() {
            var dsl = """
                    [slices]
                    org.example:slice-a:1.0.0 = 2
                    """;

            service.publish(dsl)
                   .await()
                   .onSuccessRun(() -> fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
        }
    }

    @Nested
    class GetTests {
        @Test
        void get_returnsNone_forNonExistentBlueprint() {
            var id = BlueprintId.blueprintId("org.example:missing:1.0.0").unwrap();

            var result = service.get(id);

            assertThat(result.isPresent()).isFalse();
        }

        @Test
        void get_returnsSome_forExistingBlueprint() {
            var blueprintId = BlueprintId.blueprintId("org.example:existing:1.0.0").unwrap();
            var artifact = Artifact.artifact("org.example:slice:1.0.0").unwrap();
            var expanded = ExpandedBlueprint.expandedBlueprint(
                    blueprintId,
                    List.of(ResolvedSlice.resolvedSlice(artifact, 1, false).unwrap())
                                                              );

            var key = AppBlueprintKey.appBlueprintKey(blueprintId);
            var value = new AppBlueprintValue(expanded);
            store.processCommand(new KVCommand.Put<>(key, value));

            var result = service.get(blueprintId);

            assertThat(result.isPresent()).isTrue();
            result.onPresent(retrieved ->
                                     assertThat(retrieved.id().artifact().artifactId().id()).isEqualTo("existing")
                            );
        }
    }

    @Nested
    class ListTests {
        @Test
        void list_returnsEmpty_forEmptyStore() {
            var result = service.list();

            assertThat(result).isEmpty();
        }

        @Test
        void list_returnsAll_forMultipleBlueprints() {
            var id1 = BlueprintId.blueprintId("org.example:app1:1.0.0").unwrap();
            var id2 = BlueprintId.blueprintId("org.example:app2:2.0.0").unwrap();
            var artifact = Artifact.artifact("org.example:slice:1.0.0").unwrap();

            var expanded1 = ExpandedBlueprint.expandedBlueprint(
                    id1,
                    List.of(ResolvedSlice.resolvedSlice(artifact, 1, false).unwrap())
                                                               );
            var expanded2 = ExpandedBlueprint.expandedBlueprint(
                    id2,
                    List.of(ResolvedSlice.resolvedSlice(artifact, 2, false).unwrap())
                                                               );

            store.processCommand(new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(id1), new AppBlueprintValue(expanded1)));
            store.processCommand(new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(id2), new AppBlueprintValue(expanded2)));

            var result = service.list();

            assertThat(result).hasSize(2);
            assertThat(result.stream().map(e -> e.id().artifact().artifactId().id())).containsExactlyInAnyOrder("app1", "app2");
        }
    }

    @Nested
    class DeleteTests {
        @Test
        void delete_succeeds_forExistingBlueprint() {
            var blueprintId = BlueprintId.blueprintId("org.example:to-delete:1.0.0").unwrap();
            var artifact = Artifact.artifact("org.example:slice:1.0.0").unwrap();
            var expanded = ExpandedBlueprint.expandedBlueprint(
                    blueprintId,
                    List.of(ResolvedSlice.resolvedSlice(artifact, 1, false).unwrap())
                                                              );

            var key = AppBlueprintKey.appBlueprintKey(blueprintId);
            store.processCommand(new KVCommand.Put<>(key, new AppBlueprintValue(expanded)));

            service.delete(blueprintId)
                   .await()
                   .onFailureRun(() -> fail("Expected success"));

            var result = service.get(blueprintId);
            assertThat(result.isPresent()).isFalse();
        }

        @Test
        void delete_succeeds_forNonExistentBlueprint() {
            var id = BlueprintId.blueprintId("org.example:non-existent:1.0.0").unwrap();

            service.delete(id)
                   .await()
                   .onFailureRun(() -> fail("Expected success"));
        }
    }

    @Nested
    class ValidateTests {
        @Test
        void validate_succeeds_forValidDsl() {
            var dsl = """
                    id = "org.example:my-app:1.0.0"

                    [[slices]]
                    artifact = "org.example:user-service:1.0.0"
                    instances = 2

                    [[slices]]
                    artifact = "org.example:order-service:1.0.0"
                    instances = 3
                    """;

            service.validate(dsl)
                   .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                   .onSuccess(blueprint -> {
                       assertThat(blueprint.id().asString()).isEqualTo("org.example:my-app:1.0.0");
                       assertThat(blueprint.slices()).hasSize(2);
                   });
        }

        @Test
        void validate_fails_forInvalidDsl() {
            var dsl = "invalid blueprint";

            service.validate(dsl)
                   .onSuccessRun(() -> fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
        }

        @Test
        void validate_fails_forMissingId() {
            var dsl = """
                    [[slices]]
                    artifact = "org.example:slice:1.0.0"
                    instances = 1
                    """;

            service.validate(dsl)
                   .onSuccessRun(() -> fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause.message()).contains("Missing"));
        }

        @Test
        void validate_fails_forInvalidArtifact() {
            var dsl = """
                    id = "org.example:my-app:1.0.0"

                    [[slices]]
                    artifact = "invalid-artifact"
                    instances = 1
                    """;

            service.validate(dsl)
                   .onSuccessRun(() -> fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
        }
    }

    /// #759 review round 3, BLOCKING 1: drives the REAL live path end to end over the harness above —
    /// real `BlueprintService.publish(dsl)`/`get`/`outcome` over [TestClusterNode]/[TestKVStore], and
    /// the real `SliceRoutes` status handler over the same service — instead of a stubbed
    /// `BlueprintService`. `BlueprintStatusAggregationTest#statusRoute_redeployAfterPriorFailure_outcomeCleared_reportsInProgressNotFailed`
    /// hardcodes `outcome()` to `Option.none()` from the start, so it is insensitive to reverting
    /// #818's clearing Remove — only a real `publish()` against a live store can pin that. Needs its
    /// own [Repository] resolving a real on-disk slice jar, because `publish(String)` — unlike the
    /// `get`/`list`/`delete` tests above — runs `BlueprintExpander.expand` against the outer class's
    /// always-failing fixture.
    @Nested
    class RedeployAfterPriorFailureTests {
        private static final BlueprintId REDEPLOY_ID = BlueprintId.blueprintId("org.example:redeploy-app:1.0.0").unwrap();
        private static final Artifact REDEPLOY_SLICE = Artifact.artifact("org.example:redeploy-slice:1.0.0").unwrap();
        private static final String REDEPLOY_SLICE_CLASS = "org.example.redeploy.RedeploySlice";
        private static final NodeId NODE_A = NodeId.nodeId("redeploy-node-a").unwrap();

        @TempDir
        Path tempDir;

        private TestClusterNode liveCluster;
        private TestKVStore liveStore;
        private BlueprintService liveService;

        @BeforeEach
        void setUpLivePublish() throws IOException {
            liveStore = new TestKVStore();
            liveCluster = new TestClusterNode();
            liveCluster.setStore(liveStore);

            var sliceJar = writeRedeploySliceJar();
            Repository liveRepository = artifact -> REDEPLOY_SLICE.equals(artifact)
                                                    ? sliceLocation(sliceJar, artifact)
                                                    : Causes.cause("Artifact not present in local repository").promise();

            liveService = BlueprintService.blueprintService(liveCluster, liveStore, liveRepository);
        }

        /// Leg (a): must go RED if #818's `Remove` of the stale outcome (`BlueprintService.java`'s
        /// `storeBlueprintWithKey`/`buildAllCommands`) is reverted — without it, the FAILED outcome
        /// seeded below survives the republish and the route reports PARTIAL instead of IN_PROGRESS.
        @Test
        void statusRoute_publishAfterPriorFailure_outcomeCleared_reportsInProgressNotFailed() {
            seedFailedOutcome(liveStore, REDEPLOY_ID);

            var dsl = """
                    id = "org.example:redeploy-app:1.0.0"

                    [[slices]]
                    artifact = "org.example:redeploy-slice:1.0.0"
                    instances = 2
                    """;

            liveService.publish(dsl)
                       .await()
                       .onFailure(cause -> fail("Expected DSL publish to succeed, got: " + cause.message()));

            assertThat(liveService.outcome(REDEPLOY_ID).isPresent())
                    .as("#818: republish of a previously FAILED id must clear the stale outcome in the "
                        + "same consensus batch as the republish")
                    .isFalse();

            var deploymentMap = DeploymentMap.deploymentMap();
            deploymentMap.onNodeArtifactPut(nodeArtifactPut(NODE_A, REDEPLOY_SLICE, SliceState.ACTIVE));

            var response = liveStatusResponse(deploymentMap);

            assertThat(response.overallStatus())
                    .as("a live in-flight redeploy must report its own progress, never the prior "
                        + "attempt's cleared terminal outcome")
                    .isEqualTo("IN_PROGRESS");
        }

        /// Leg (b): same seeded stale FAILED outcome, no republish. Must go RED if
        /// `SliceRoutes.routeBlueprintStatusByOutcome`'s outcome-first check is reverted.
        @Test
        void statusRoute_noRepublishAfterPriorFailure_reportsFailed() {
            seedFailedOutcome(liveStore, REDEPLOY_ID);

            var response = liveStatusResponse(DeploymentMap.deploymentMap());

            assertThat(response.overallStatus())
                    .as("with no republish, the route must surface the terminal failure rather than a "
                        + "bare 404 or a fabricated live status")
                    .isEqualTo("FAILED");
        }

        private void seedFailedOutcome(TestKVStore targetStore, BlueprintId id) {
            targetStore.processCommand(new KVCommand.Put<>(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(id),
                                                            AetherValue.DeploymentOutcomeValue.failed(List.of(REDEPLOY_SLICE.asString()),
                                                                                                      "prior attempt failed",
                                                                                                      1L)));
        }

        private BlueprintStatusResponse liveStatusResponse(DeploymentMap deploymentMap) {
            var holder = new AtomicReference<BlueprintStatusResponse>();
            liveStatusRoute(deploymentMap).handler()
                                          .handle(new LiveStatusRequestContext(List.of(REDEPLOY_ID.asString())))
                                          .await()
                                          .onSuccess(value -> holder.set((BlueprintStatusResponse) value))
                                          .onFailure(cause -> fail("Status lookup must succeed, got: " + cause.message()));
            return holder.get();
        }

        private Route<?> liveStatusRoute(DeploymentMap deploymentMap) {
            var routes = SliceRoutes.sliceRoutes(() -> new LiveManageableNode(liveService, deploymentMap))
                                    .routes()
                                    .filter(candidate -> candidate.name().equals(ManagementRoute.BLUEPRINT_STATUS.name()))
                                    .toList();
            return routes.isEmpty() ? fail("BLUEPRINT_STATUS route not registered") : routes.getFirst();
        }

        private ValuePut<AetherKey.NodeArtifactKey, AetherValue.NodeArtifactValue> nodeArtifactPut(NodeId nodeId, Artifact artifact, SliceState state) {
            var key = new AetherKey.NodeArtifactKey(nodeId, artifact);
            var value = AetherValue.NodeArtifactValue.nodeArtifactValue(state);
            return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
        }

        private Promise<Location> sliceLocation(Path jar, Artifact artifact) {
            return Result.lift(Causes::fromThrowable, () -> jar.toUri().toURL())
                         .flatMap(url -> Location.location(artifact, url))
                         .async();
        }

        private Path writeRedeploySliceJar() throws IOException {
            var manifest = new Manifest();
            var attributes = manifest.getMainAttributes();

            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, REDEPLOY_SLICE.asString());
            attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, REDEPLOY_SLICE_CLASS);
            attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

            var target = tempDir.resolve("redeploy-slice-1.0.0.jar");

            try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                out.putNextEntry(new ZipEntry("org/example/redeploy/"));
                out.closeEntry();
            }

            return target;
        }

        /// The status route touches exactly two `ManageableNode` accessors: `blueprintService()` and
        /// `deploymentMap()`. Every other abstract method is routed through `unsupported`, per the #759
        /// follow-up rule requiring new tests to be Proxy-free — same pattern as
        /// `BlueprintStatusAggregationTest`'s `StatusManageableNode`, duplicated here because this test
        /// lives in a different module/package and no shared test utility exists for this interface.
        private record LiveManageableNode(BlueprintService blueprintService, DeploymentMap deploymentMap) implements ManageableNode {
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
            public AppHttpServer appHttpServer() { return unsupported("appHttpServer"); }
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
            public void route(Message message) { unsupported("route"); }
        }

        private record LiveStatusRequestContext(List<String> pathParams) implements RequestContext {
            @Override
            public <T> Result<T> fromJson(TypeToken<T> literal) { return unsupported("fromJson"); }
            @Override
            public Route<?> route() { return unsupported("route"); }
            @Override
            public HttpHeaders responseHeaders() { return unsupported("responseHeaders"); }
            @Override
            public String requestId() { return unsupported("requestId"); }
            @Override
            public HttpMethod method() { return unsupported("method"); }
            @Override
            public String path() { return unsupported("path"); }
            @Override
            public Headers headers() { return unsupported("headers"); }
            @Override
            public QueryParams queryParams() { return unsupported("queryParams"); }
            @Override
            public byte[] body() { return unsupported("body"); }
        }

        private static <T> T unsupported(String methodName) {
            return fail("Not touched by the status route handler: " + methodName);
        }
    }

    // Test implementation of ClusterNode that delegates to TestKVStore
    private static class TestClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private TestKVStore store;

        void setStore(TestKVStore store) {
            this.store = store;
        }

        @Override
        public NodeId self() {
            return NodeId.nodeId("test-node").unwrap();
        }

        @Override
        public TopologyManager topologyManager() {
            return null;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            // Process commands through the store
            return Promise.success(commands.stream()
                                           .map(cmd -> (R) store.processCommand(cmd))
                                           .toList());
        }
    }

    // Test implementation of KVStore
    private static class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        public TestKVStore() {
            super(null, null, null);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return new HashMap<>(storage);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, java.util.function.BiConsumer<KK, VV> consumer) {
            storage.forEach((key, value) -> {
                if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                    consumer.accept((KK) key, (VV) value);
                }
            });
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <R> List<R> process(Batch<KVCommand<AetherKey>> batch) {
            return batch.commands()
                        .stream()
                        .map(command -> (R) processCommand(command))
                        .toList();
        }

        // Per-command application used directly by tests and by the batch override above.
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Option<AetherValue> processCommand(KVCommand command) {
            return switch (command) {
                case KVCommand.Put<?, ?> put -> {
                    storage.put((AetherKey) put.key(), (AetherValue) put.value());
                    yield Option.none();
                }
                case KVCommand.Remove<?> remove -> {
                    storage.remove((AetherKey) remove.key());
                    yield Option.none();
                }
                case KVCommand.Get<?> get -> Option.option(storage.get((AetherKey) get.key()));
                default -> Option.none();
            };
        }
    }
}
