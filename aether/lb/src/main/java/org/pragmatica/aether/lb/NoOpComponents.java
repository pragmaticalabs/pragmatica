package org.pragmatica.aether.lb;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetrics;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestDeployment;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.AbTestMetrics;
import org.pragmatica.aether.update.CleanupPolicy;
import org.pragmatica.aether.update.Deployment;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.update.DeploymentStrategy;
import org.pragmatica.aether.update.HealthThresholds;
import org.pragmatica.aether.update.SplitRule;
import org.pragmatica.aether.update.StrategyConfig;
import org.pragmatica.cluster.metrics.MetricsMessage;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.CommunityScalingRequest;
import org.pragmatica.net.tcp.security.CertificateBundle;

import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.utils.Causes.cause;


/// No-op component instances for passive LB node.
/// Passive nodes do not host slices, run the control plane, or collect metrics.
/// These stubs return empty/default values for management API observability.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03", "JBCT-EX-01"}) sealed interface NoOpComponents {
    Cause NOT_AVAILABLE = cause("Not available on passive LB node");

    SliceStore SLICE_STORE = new NoOpSliceStore();

    MetricsCollector METRICS_COLLECTOR = new NoOpMetricsCollector();

    DeploymentMetricsCollector DEPLOYMENT_METRICS = new NoOpDeploymentMetrics();

    ControlLoop CONTROL_LOOP = new NoOpControlLoop();

    BlueprintService BLUEPRINT_SERVICE = new NoOpBlueprintService();

    MavenProtocolHandler MAVEN_HANDLER = new NoOpMavenHandler();

    ArtifactStore ARTIFACT_STORE = new NoOpArtifactStore();

    DeploymentManager DEPLOYMENT_MANAGER = new NoOpDeploymentManager();

    AbTestManager AB_TEST_MANAGER = new NoOpAbTestManager();

    AppHttpServer APP_HTTP_SERVER = new NoOpAppHttpServer();

    TTMManager TTM_MANAGER = TTMManager.noOp(TtmConfig.ttmConfig());

    ArtifactMetricsCollector ARTIFACT_METRICS = new NoOpArtifactMetrics();

    BackupService BACKUP_SERVICE = new NoOpBackupService();

    record unused() implements NoOpComponents{}

    final class NoOpSliceStore implements SliceStore {
        @Override public List<LoadedSlice> loaded() {
            return List.of();
        }

        @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<Unit> unloadSlice(Artifact artifact) {
            return NOT_AVAILABLE.promise();
        }
    }

    final class NoOpMetricsCollector implements MetricsCollector {
        @Override public Map<String, Double> collectLocal() {
            return Map.of();
        }

        @Override public void recordCall(MethodName method, long durationMs) {}

        @Override public void recordCustom(String name, double value) {}

        @Override public void setInvocationMetricsProvider(InvocationMetricsCollector provider) {}

        @Override public Map<NodeId, Map<String, Double>> allMetrics() {
            return Map.of();
        }

        @Override public Map<String, Double> metricsFor(NodeId nodeId) {
            return Map.of();
        }

        @Override public Map<NodeId, List<MetricsSnapshot>> historicalMetrics() {
            return Map.of();
        }

        @Override public void removeNode(NodeId nodeId) {}

        @Override public void onTopologyChange(TopologyChangeNotification topologyChange) {}

        @Override public void onMetricsPing(MetricsMessage.MetricsPing ping) {}

        @Override public void onMetricsPong(MetricsMessage.MetricsPong pong) {}
    }

    final class NoOpDeploymentMetrics implements DeploymentMetricsCollector {
        @Override public void onDeploymentStarted(DeploymentEvent.DeploymentStarted event) {}

        @Override public void onStateTransition(DeploymentEvent.StateTransition event) {}

        @Override public void onDeploymentCompleted(DeploymentEvent.DeploymentCompleted event) {}

        @Override public void onDeploymentFailed(DeploymentEvent.DeploymentFailed event) {}

        @Override public Map<Artifact, List<DeploymentMetrics>> allDeploymentMetrics() {
            return Map.of();
        }

        @Override public List<DeploymentMetrics> metricsFor(Artifact artifact) {
            return List.of();
        }

        @Override public Map<DeploymentMetricsCollector.DeploymentKey, DeploymentMetrics> inProgressDeployments() {
            return Map.of();
        }

        @Override public void onDeploymentMetricsPing(org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPing ping) {}

        @Override public void onDeploymentMetricsPong(org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPong pong) {}

        @Override public void onTopologyChange(TopologyChangeNotification topologyChange) {}

        @Override public Map<String, List<org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsEntry>> collectLocalEntries() {
            return Map.of();
        }
    }

    final class NoOpControlLoop implements ControlLoop {
        @Override public Promise<Unit> activate() {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> deactivate() {
            return Promise.unitPromise();
        }

        @Override public TaskGroup taskGroup() {
            return TaskGroup.SCALING;
        }

        @Override public boolean isActive() {
            return false;
        }

        @Override public void onTopologyChange(TopologyChangeNotification topologyChange) {}

        @Override public void onSliceTargetPut(ValuePut valuePut) {}

        @Override public void onSliceTargetRemove(ValueRemove valueRemove) {}

        @Override public void onNodeArtifactPut(ValuePut valuePut) {}

        @Override public void onNodeArtifactRemove(ValueRemove valueRemove) {}

        @Override public void onQuorumStateChange(QuorumStateNotification notification) {}

        @Override public void registerBlueprint(Artifact artifact, int instances, int minInstances) {}

        @Override public void unregisterBlueprint(Artifact artifact) {}

        @Override public ControllerConfig configuration() {
            return ControllerConfig.DEFAULT;
        }

        @Override public void updateConfiguration(ControllerConfig config) {}

        @Override public void stop() {}

        @Override public void onCommunityScalingRequest(CommunityScalingRequest request) {}

        @Override public void onCommunityMetricsSnapshot(CommunityMetricsSnapshot snapshot) {}

        @Override public Map<String, CommunityMetricsSnapshot> communitySnapshots() {
            return Map.of();
        }
    }

    final class NoOpBlueprintService implements BlueprintService {
        @Override public Promise<ExpandedBlueprint> publish(String dsl) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Option<ExpandedBlueprint> get(BlueprintId id) {
            return Option.empty();
        }

        @Override public List<ExpandedBlueprint> list() {
            return List.of();
        }

        @Override public Promise<Unit> delete(BlueprintId id) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Result<Blueprint> validate(String dsl) {
            return NOT_AVAILABLE.result();
        }
    }

    final class NoOpMavenHandler implements MavenProtocolHandler {
        @Override public Promise<MavenResponse> handleGet(String path) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<MavenResponse> handlePut(String path, byte[] content) {
            return NOT_AVAILABLE.promise();
        }
    }

    final class NoOpArtifactStore implements ArtifactStore {
        @Override public Promise<DeployResult> deploy(Artifact artifact, byte[] content) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<byte[]> resolve(Artifact artifact) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<Boolean> exists(Artifact artifact) {
            return Promise.success(false);
        }

        @Override public Promise<List<Version>> versions(org.pragmatica.aether.artifact.GroupId groupId,
                                                         org.pragmatica.aether.artifact.ArtifactId artifactId) {
            return Promise.success(List.of());
        }

        @Override public Promise<Unit> delete(Artifact artifact) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Metrics metrics() {
            return new Metrics(0, 0, 0);
        }
    }

    final class NoOpDeploymentManager implements DeploymentManager {
        @Override public Promise<Unit> activate() {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> deactivate() {
            return Promise.unitPromise();
        }

        @Override public TaskGroup taskGroup() {
            return TaskGroup.DEPLOYMENT;
        }

        @Override public boolean isActive() {
            return false;
        }

        @Override public Result<Deployment> start(String blueprintId,
                                                  Version newVersion,
                                                  DeploymentStrategy strategy,
                                                  StrategyConfig config,
                                                  HealthThresholds thresholds,
                                                  CleanupPolicy cleanupPolicy,
                                                  int instances) {
            return NOT_AVAILABLE.result();
        }

        @Override public Result<Deployment> promote(String deploymentId) {
            return NOT_AVAILABLE.result();
        }

        @Override public Result<Deployment> rollback(String deploymentId) {
            return NOT_AVAILABLE.result();
        }

        @Override public Result<Deployment> complete(String deploymentId) {
            return NOT_AVAILABLE.result();
        }

        @Override public Option<Deployment> status(String deploymentId) {
            return Option.empty();
        }

        @Override public List<Deployment> list() {
            return List.of();
        }

        @Override public Option<DeploymentManager.ActiveRouting> activeRouting(ArtifactBase artifactBase) {
            return Option.empty();
        }
    }

    final class NoOpAbTestManager implements AbTestManager {
        @Override public Promise<Unit> activate() {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> deactivate() {
            return Promise.unitPromise();
        }

        @Override public TaskGroup taskGroup() {
            return TaskGroup.STRATEGIES;
        }

        @Override public boolean isActive() {
            return false;
        }

        @Override public Promise<AbTestDeployment> createTest(ArtifactBase artifactBase,
                                                              Map<String, Version> variantVersions,
                                                              SplitRule splitRule) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<AbTestDeployment> concludeTest(String testId, String winningVariant) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Promise<AbTestDeployment> rollbackTest(String testId) {
            return NOT_AVAILABLE.promise();
        }

        @Override public Option<AbTestDeployment> getTest(String testId) {
            return Option.empty();
        }

        @Override public Option<AbTestDeployment> getActiveTest(ArtifactBase artifactBase) {
            return Option.empty();
        }

        @Override public List<AbTestDeployment> activeTests() {
            return List.of();
        }

        @Override public List<AbTestDeployment> allTests() {
            return List.of();
        }

        @Override public AbTestMetrics getMetrics(String testId) {
            return new AbTestMetrics(testId, Map.of(), 0);
        }

        @Override public void onDeploymentFailed(DeploymentEvent.DeploymentFailed event) {}
    }

    final class NoOpAppHttpServer implements AppHttpServer {
        @Override public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> rotateCertificate(CertificateBundle newBundle) {
            return Promise.unitPromise();
        }

        @Override public Option<Integer> boundPort() {
            return Option.empty();
        }

        @Override public void onRoutePut(ValuePut valuePut) {}

        @Override public void onRouteRemove(ValueRemove valueRemove) {}

        @Override public void onNodeRoutesPut(ValuePut valuePut) {}

        @Override public void onNodeRoutesRemove(ValueRemove valueRemove) {}

        @Override public void onHttpForwardRequest(org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest request) {}

        @Override public void onHttpForwardResponse(org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse response) {}

        @Override public void rebuildRouter() {}

        @Override public boolean isRouteReady() {
            return false;
        }

        @Override public void onQuorumStateChange(org.pragmatica.consensus.topology.QuorumStateNotification notification) {}

        @Override public void onNodeRemoved(TopologyChangeNotification.NodeRemoved nodeRemoved) {}

        @Override public void onNodeDown(TopologyChangeNotification.NodeDown nodeDown) {}

        @Override public Option<org.pragmatica.aether.http.forward.HttpForwarder> httpForwarder() {
            return Option.empty();
        }

        @Override public Option<org.pragmatica.aether.http.HttpRoutePublisher> httpRoutePublisher() {
            return Option.empty();
        }
    }

    final class NoOpArtifactMetrics implements ArtifactMetricsCollector {
        @Override public Map<String, Double> collectMetrics() {
            return Map.of();
        }

        @Override public boolean isDeployed(Artifact artifact) {
            return false;
        }

        @Override public java.util.Set<Artifact> deployedArtifacts() {
            return java.util.Set.of();
        }

        @Override public ArtifactStore.Metrics storeMetrics() {
            return new ArtifactStore.Metrics(0, 0, 0);
        }

        @Override public org.pragmatica.aether.metrics.artifact.ArtifactDeploymentTracker deploymentTracker() {
            return org.pragmatica.aether.metrics.artifact.ArtifactDeploymentTracker.artifactDeploymentTracker();
        }
    }

    final class NoOpBackupService implements BackupService {
        @Override public Result<Unit> backupNow() {
            return NOT_AVAILABLE.result();
        }

        @Override public Result<List<BackupInfo>> listBackups() {
            return Result.success(List.of());
        }

        @Override public Result<Unit> restore(String commitId) {
            return NOT_AVAILABLE.result();
        }
    }
}
