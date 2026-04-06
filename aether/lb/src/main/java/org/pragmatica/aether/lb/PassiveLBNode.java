package org.pragmatica.aether.lb;

import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.ClusterEventAggregatorConfig;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.delegation.TaskAssignmentCoordinator;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.aether.metrics.MinuteAggregator;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.consensus.RabiaMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetricsCollector;
import org.pragmatica.aether.metrics.gc.GCMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.network.NetworkMetricsHandler;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.Message;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;

import java.util.List;
import java.util.Map;
import java.util.Set;


/// ManageableNode adapter for the passive load balancer.
/// Provides real cluster access (KV-Store, topology, apply) from PassiveNode
/// and no-op stubs for slice hosting, control plane, and metrics collection
/// that a passive LB does not participate in.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"}) public final class PassiveLBNode implements ManageableNode {
    private final PassiveLBConfig config;
    private final PassiveNode<AetherKey, AetherValue> passiveNode;
    private final TopologyConfig topologyConfig;
    private final long startTimeMs;
    private final ComprehensiveSnapshotCollector snapshotCollector;
    private final InvocationMetricsCollector invocationMetrics;
    private final ClusterEventAggregator eventAggregator;
    private final StreamPartitionManager streamPartitionManager;
    private final DeploymentMap deploymentMap;
    private final HttpRouteRegistry httpRouteRegistry;

    private PassiveLBNode(PassiveLBConfig config,
                          PassiveNode<AetherKey, AetherValue> passiveNode,
                          TopologyConfig topologyConfig) {
        this.config = config;
        this.passiveNode = passiveNode;
        this.topologyConfig = topologyConfig;
        this.startTimeMs = System.currentTimeMillis();
        this.invocationMetrics = InvocationMetricsCollector.invocationMetricsCollector();
        this.snapshotCollector = ComprehensiveSnapshotCollector.comprehensiveSnapshotCollector(GCMetricsCollector.gcMetricsCollector(),
                                                                                               EventLoopMetricsCollector.eventLoopMetricsCollector(),
                                                                                               NetworkMetricsHandler.networkMetricsHandler(),
                                                                                               RabiaMetricsCollector.rabiaMetricsCollector(),
                                                                                               invocationMetrics,
                                                                                               MinuteAggregator.minuteAggregator());
        this.eventAggregator = ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig());
        this.streamPartitionManager = StreamPartitionManager.streamPartitionManager();
        this.deploymentMap = DeploymentMap.deploymentMap();
        this.httpRouteRegistry = HttpRouteRegistry.httpRouteRegistry();
    }

    static PassiveLBNode passiveLBNode(PassiveLBConfig config,
                                       PassiveNode<AetherKey, AetherValue> passiveNode,
                                       TopologyConfig topologyConfig) {
        return new PassiveLBNode(config, passiveNode, topologyConfig);
    }

    @Override public NodeId self() {
        return config.selfInfo().id();
    }

    @Override public KVStore<AetherKey, AetherValue> kvStore() {
        return passiveNode.kvStore();
    }

    @Override public TopologyManager topologyManager() {
        return passiveNode.topologyManager();
    }

    @Override public TopologyConfig topologyConfig() {
        return topologyConfig;
    }

    @Override public Set<NodeId> connectedPeerIds() {
        return passiveNode.network().connectedPeers();
    }

    @Override public int connectedNodeCount() {
        return passiveNode.network().connectedNodeCount();
    }

    @Override public Map<String, Number> transportMetrics() {
        return passiveNode.network().transportMetrics();
    }

    @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
        return passiveNode.apply(commands);
    }

    @Override public void route(Message message) {
        passiveNode.delegateRouter().route(message);
    }

    @Override public List<NodeId> initialTopology() {
        return config.clusterNodes().stream()
                                  .map(NodeInfo::id)
                                  .toList();
    }

    @Override public int managementPort() {
        return config.managementPort();
    }

    @Override public long uptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMs) / 1000;
    }

    @Override public boolean isReady() {
        return true;
    }

    @Override public boolean isLeader() {
        return false;
    }

    @Override public Option<NodeId> leader() {
        return Option.empty();
    }

    @Override public ComprehensiveSnapshotCollector snapshotCollector() {
        return snapshotCollector;
    }

    @Override public InvocationMetricsCollector invocationMetrics() {
        return invocationMetrics;
    }

    @Override public ClusterEventAggregator eventAggregator() {
        return eventAggregator;
    }

    @Override public StreamPartitionManager streamPartitionManager() {
        return streamPartitionManager;
    }

    @Override public DeploymentMap deploymentMap() {
        return deploymentMap;
    }

    @Override public HttpRouteRegistry httpRouteRegistry() {
        return httpRouteRegistry;
    }

    @Override public Map<String, StorageFactory.StorageSetup> storageSetups() {
        return Map.of();
    }

    @Override public Option<CertificateRenewalScheduler> certRenewalScheduler() {
        return Option.empty();
    }

    @Override public TaskAssignmentCoordinator taskAssignmentCoordinator() {
        return TaskAssignmentCoordinator.noOp();
    }

    @Override public SliceStore sliceStore() {
        return NoOpComponents.SLICE_STORE;
    }

    @Override public MetricsCollector metricsCollector() {
        return NoOpComponents.METRICS_COLLECTOR;
    }

    @Override public DeploymentMetricsCollector deploymentMetricsCollector() {
        return NoOpComponents.DEPLOYMENT_METRICS;
    }

    @Override public ControlLoop controlLoop() {
        return NoOpComponents.CONTROL_LOOP;
    }

    @Override public BlueprintService blueprintService() {
        return NoOpComponents.BLUEPRINT_SERVICE;
    }

    @Override public MavenProtocolHandler mavenProtocolHandler() {
        return NoOpComponents.MAVEN_HANDLER;
    }

    @Override public ArtifactStore artifactStore() {
        return NoOpComponents.ARTIFACT_STORE;
    }

    @Override public DeploymentManager deploymentManager() {
        return NoOpComponents.DEPLOYMENT_MANAGER;
    }

    @Override public AbTestManager abTestManager() {
        return NoOpComponents.AB_TEST_MANAGER;
    }

    @Override public AppHttpServer appHttpServer() {
        return NoOpComponents.APP_HTTP_SERVER;
    }

    @Override public TTMManager ttmManager() {
        return NoOpComponents.TTM_MANAGER;
    }

    @Override public ArtifactMetricsCollector artifactMetricsCollector() {
        return NoOpComponents.ARTIFACT_METRICS;
    }

    @Override public BackupService backupService() {
        return NoOpComponents.BACKUP_SERVICE;
    }
}
