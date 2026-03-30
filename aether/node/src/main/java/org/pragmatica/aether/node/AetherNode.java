package org.pragmatica.aether.node;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.ClusterEventAggregatorConfig;
import org.pragmatica.aether.api.LogLevelRegistry;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.controller.RollbackManager;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.deployment.schema.AetherSchemaManager;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.deployment.schema.SchemaPolicy;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.deployment.loadbalancer.LoadBalancerManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.dht.AetherMaps;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.storage.MemoryTier;
import org.pragmatica.aether.storage.StorageInstance;
import org.pragmatica.aether.api.ObservabilityDepthRegistry;
import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ObservabilityInterceptor;
import org.pragmatica.aether.invoke.InvocationMessage;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.aether.metrics.MetricsScheduler;
import org.pragmatica.aether.metrics.MinuteAggregator;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.consensus.RabiaMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsScheduler;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetricsCollector;
import org.pragmatica.aether.metrics.gc.GCMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.network.NetworkMetricsHandler;
import org.pragmatica.aether.repository.RepositoryFactory;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.ttm.AdaptiveDecisionTree;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.BlueGreenDeploymentManager;
import org.pragmatica.aether.update.CanaryDeploymentManager;
import org.pragmatica.aether.update.DeploymentStrategyCoordinator;
import org.pragmatica.aether.update.RollingUpdateManager;
import org.pragmatica.aether.worker.bootstrap.WorkerBootstrap;
import org.pragmatica.aether.worker.deployment.WorkerDeploymentManager;
import org.pragmatica.aether.worker.governor.DecisionRelay;
import org.pragmatica.aether.worker.governor.GovernorCleanup;
import org.pragmatica.aether.worker.governor.GovernorMesh;
import org.pragmatica.aether.worker.group.GroupMembershipTracker;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.CommunityScalingRequest;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage;
import org.pragmatica.cluster.metrics.MetricsMessage;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.node.forward.ForwardApplyRequest;
import org.pragmatica.cluster.node.forward.ForwardApplyResponse;
import org.pragmatica.cluster.node.rabia.NodeConfig;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTAntiEntropy;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNetwork;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.consensus.net.quic.QuicTlsProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Main entry point for an Aether cluster node.
/// Assembles all components: consensus, KV-store, slice management, deployment managers.
@SuppressWarnings("JBCT-RET-01")
public interface AetherNode {
    String VERSION = "0.25.0";
    NodeId self();

    Promise<Unit> start();

    Promise<Unit> stop();

    KVStore<AetherKey, AetherValue> kvStore();

    SliceStore sliceStore();

    MetricsCollector metricsCollector();

    DeploymentMetricsCollector deploymentMetricsCollector();

    ControlLoop controlLoop();

    SliceInvoker sliceInvoker();

    InvocationHandler invocationHandler();

    BlueprintService blueprintService();

    MavenProtocolHandler mavenProtocolHandler();

    /// Get the artifact store for artifact storage operations.
    ArtifactStore artifactStore();

    /// Get the topology manager for cluster membership visibility.
    TopologyManager topologyManager();

    /// Get the invocation metrics collector for method-level metrics.
    InvocationMetricsCollector invocationMetrics();

    /// Get the cluster controller for scaling decisions.
    ClusterController controller();

    /// Get the rolling update manager for managing version transitions.
    RollingUpdateManager rollingUpdateManager();

    /// Get the canary deployment manager for multi-stage progressive traffic shifting.
    CanaryDeploymentManager canaryDeploymentManager();

    /// Get the blue-green deployment manager for atomic traffic switching.
    BlueGreenDeploymentManager blueGreenDeploymentManager();

    /// Get the A/B test manager for variant testing deployments.
    AbTestManager abTestManager();

    /// Get the endpoint registry for service discovery.
    EndpointRegistry endpointRegistry();

    /// Get the alert manager for threshold management.
    AlertManager alertManager();

    /// Get the observability depth registry for per-method depth configuration.
    ObservabilityDepthRegistry observabilityDepthRegistry();

    /// Get the invocation trace store for distributed tracing queries.
    InvocationTraceStore traceStore();

    /// Get the log level registry for runtime log level management.
    LogLevelRegistry logLevelRegistry();

    /// Get the dynamic config manager for runtime configuration updates.
    Option<DynamicConfigManager> dynamicConfigManager();

    /// Get the application HTTP server for slice routes.
    AppHttpServer appHttpServer();

    /// Get the HTTP route registry for route lookup.
    HttpRouteRegistry httpRouteRegistry();

    /// Get the TTM manager for predictive scaling.
    TTMManager ttmManager();

    /// Get the rollback manager for automatic version rollback.
    RollbackManager rollbackManager();

    /// Get the comprehensive snapshot collector for detailed metrics.
    ComprehensiveSnapshotCollector snapshotCollector();

    /// Get the artifact metrics collector for storage and deployment metrics.
    ArtifactMetricsCollector artifactMetricsCollector();

    /// Get the deployment map for event-driven slice-node indexing.
    DeploymentMap deploymentMap();

    /// Get the cluster event aggregator for structured event collection.
    ClusterEventAggregator eventAggregator();

    /// Get the backup service for this node.
    BackupService backupService();

    /// Get the stream partition manager for local stream operations.
    StreamPartitionManager streamPartitionManager();

    /// Get the certificate renewal scheduler for observability.
    Option<CertificateRenewalScheduler> certRenewalScheduler();

    /// Get the number of currently connected peer nodes in the cluster.
    /// This is a network-level count, not based on metrics exchange.
    int connectedNodeCount();

    /// Get transport-level metrics (QUIC connection stats, handshakes, message throughput).
    /// Returns an empty map if the transport does not support metrics.
    Map<String, Number> transportMetrics();

    /// Get the IDs of currently connected peer nodes.
    /// This is a live view — reflects actual TCP connections, not static config.
    Set<NodeId> connectedPeerIds();

    /// Check if this node is the current leader.
    boolean isLeader();

    /// Check if this node is ready for operations (consensus active).
    boolean isReady();

    /// Get the current leader node ID.
    Option<NodeId> leader();

    /// Apply commands to the cluster via consensus.
    <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands);

    /// Get the management server port for this node.
    /// Returns 0 if management server is disabled.
    int managementPort();

    /// Get the node uptime in seconds since start.
    long uptimeSeconds();

    /// Get the initial topology node IDs from configuration.
    /// This includes all core nodes that were configured at startup.
    List<NodeId> initialTopology();

    /// Get the topology configuration for this node.
    TopologyConfig topologyConfig();

    /// Route a message to registered handlers via the internal MessageRouter.
    void route(Message message);

    static Result<AetherNode> aetherNode(AetherNodeConfig config) {
        var delegateRouter = MessageRouter.DelegateRouter.delegate();
        var nodeCodec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        return aetherNode(config, delegateRouter, nodeCodec);
    }

    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         SliceCodec nodeCodec) {
        return config.validate()
                     .flatMap(_ -> createNode(config, delegateRouter, nodeCodec));
    }

    private static Result<AetherNode> createNode(AetherNodeConfig config,
                                                 MessageRouter.DelegateRouter delegateRouter,
                                                 SliceCodec nodeCodec) {
        // SliceCodec implements both Serializer and Deserializer
        Serializer serializer = nodeCodec;
        Deserializer deserializer = nodeCodec;
        // Create KVStore (state machine for consensus)
        var kvStore = new KVStore<AetherKey, AetherValue>(delegateRouter, serializer, deserializer);
        // Create DHT node (local storage engine + hash ring)
        // Note: DistributedDHTClient is created in assembleNode() where ClusterNetwork is available
        var dhtStorage = MemoryStorageEngine.memoryStorageEngine();
        var dhtRing = ConsistentHashRing.<NodeId>consistentHashRing();
        dhtRing.addNode(config.self());
        // Pre-populate ring with all known peers so DHT is ready before topology events fire
        config.topology()
              .coreNodes()
              .forEach(peer -> dhtRing.addNode(peer.id()));
        var dhtNode = DHTNode.dhtNode(config.self(), dhtStorage, dhtRing, config.artifactRepo());
        // Create slice management components (deferred — artifact store needs ClusterNetwork)
        var sliceRegistry = SliceRegistry.sliceRegistry();
        var deferredInvoker = DeferredSliceInvokerFacade.deferredSliceInvokerFacade();
        // Create Rabia cluster node with metrics
        var nodeConfig = NodeConfig.nodeConfig(config.protocol(), config.topology(), config.activationGated());
        var rabiaMetricsCollector = RabiaMetricsCollector.rabiaMetricsCollector();
        var networkMetricsHandler = NetworkMetricsHandler.networkMetricsHandler();
        // Assemble all components and collect routes
        // Use consensus-based leader election to prevent flapping when nodes see different topologies
        return RabiaNode.rabiaNode(nodeConfig,
                                   delegateRouter,
                                   kvStore,
                                   serializer,
                                   deserializer,
                                   rabiaMetricsCollector,
                                   true,
                                   RabiaPersistence.inMemory(),
                                   config.tls())
                        .flatMap(clusterNode -> assembleNode(config,
                                                             delegateRouter,
                                                             kvStore,
                                                             sliceRegistry,
                                                             deferredInvoker,
                                                             clusterNode,
                                                             rabiaMetricsCollector,
                                                             networkMetricsHandler,
                                                             serializer,
                                                             deserializer,
                                                             nodeCodec,
                                                             dhtNode));
    }

    private static Result<AetherNode> assembleNode(AetherNodeConfig config,
                                                   MessageRouter.DelegateRouter delegateRouter,
                                                   KVStore<AetherKey, AetherValue> kvStore,
                                                   SliceRegistry sliceRegistry,
                                                   DeferredSliceInvokerFacade deferredInvoker,
                                                   RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                   RabiaMetricsCollector rabiaMetricsCollector,
                                                   NetworkMetricsHandler networkMetricsHandler,
                                                   Serializer serializer,
                                                   Deserializer deserializer,
                                                   SliceCodec nodeCodec,
                                                   DHTNode dhtNode) {
        // Create DHTNetwork adapter from ClusterNetwork
        DHTNetwork dhtNetwork = (target, msg) -> clusterNode.network()
                                                            .send(target, msg);
        // Create distributed DHT client with quorum-based reads/writes via DHTNetwork
        var dhtClient = DistributedDHTClient.distributedDHTClient(dhtNode, dhtNetwork, config.artifactRepo());
        // Create AetherMaps with full replication — control plane data (slice-nodes, endpoints, routes)
        // must be visible on ALL nodes for subscription notifications to reach CDM/NDM/routing.
        var aetherMaps = AetherMaps.aetherMaps(dhtClient.scoped(DHTConfig.FULL));
        // Create scoped DHT client for cache operations (lower replication, single-replica default)
        var cacheDhtClient = dhtClient.scoped(config.cache());
        var artifactStorage = StorageInstance.storageInstance("artifacts",
                                                              List.of(MemoryTier.memoryTier(256 * 1024 * 1024)));
        var artifactStore = ArtifactStore.artifactStore(dhtClient, artifactStorage);
        // Create repositories from SliceConfig using RepositoryFactory
        var repositoryFactory = RepositoryFactory.repositoryFactory(artifactStore);
        var repositories = repositoryFactory.createAll(config.sliceConfig());
        // Create remaining slice management components
        var sharedLibraryLoader = createSharedLibraryLoader(config);
        var resourceProviderSetup = createResourceProviderFacade(config);
        var sliceStore = SliceStore.sliceStore(sliceRegistry,
                                               repositories,
                                               sharedLibraryLoader,
                                               deferredInvoker,
                                               resourceProviderSetup.facade(),
                                               config.sliceAction());
        // Create DHTRebalancer for re-replication on node departure
        var dhtRebalancer = DHTRebalancer.dhtRebalancer(dhtNode, dhtNetwork, config.artifactRepo());
        // Create DHTTopologyListener for ring updates on topology changes
        var dhtTopologyListener = DHTTopologyListener.dhtTopologyListener(dhtNode, dhtRebalancer);
        // Create DHT anti-entropy for replica synchronization
        var dhtAntiEntropy = DHTAntiEntropy.dhtAntiEntropy(dhtNode, dhtNetwork, config.artifactRepo());
        // Create SwitchableClusterNode wrapping clusterNode — allows runtime switch to forwarding mode
        var switchableCluster = SwitchableClusterNode.switchableClusterNode(clusterNode);
        // Create ForwardingClusterNode for worker mode — forwards apply() to core peers
        var corePeerIds = config.topology()
                                .coreNodes()
                                .stream()
                                .map(NodeInfo::id)
                                .filter(id -> !id.equals(config.self()))
                                .collect(Collectors.toSet());
        var forwardingClusterNode = ForwardingClusterNode.forwardingClusterNode(clusterNode,
                                                                                clusterNode.network(),
                                                                                corePeerIds);
        record aetherNode(AetherNodeConfig config,
                          MessageRouter.DelegateRouter router,
                          KVStore<AetherKey, AetherValue> kvStore,
                          SliceRegistry sliceRegistry,
                          SliceStore sliceStore,
                          RabiaNode<KVCommand<AetherKey>> clusterNode,
                          SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                          NodeDeploymentManager nodeDeploymentManager,
                          ClusterDeploymentManager clusterDeploymentManager,
                          EndpointRegistry endpointRegistry,
                          HttpRouteRegistry httpRouteRegistry,
                          MetricsCollector metricsCollector,
                          MetricsScheduler metricsScheduler,
                          DeploymentMetricsCollector deploymentMetricsCollector,
                          DeploymentMetricsScheduler deploymentMetricsScheduler,
                          ControlLoop controlLoop,
                          SliceInvoker sliceInvoker,
                          InvocationHandler invocationHandler,
                          BlueprintService blueprintService,
                          MavenProtocolHandler mavenProtocolHandler,
                          ArtifactStore artifactStore,
                          InvocationMetricsCollector invocationMetrics,
                          DecisionTreeController controller,
                          RollingUpdateManager rollingUpdateManager,
                          CanaryDeploymentManager canaryDeploymentManager,
                          BlueGreenDeploymentManager blueGreenDeploymentManager,
                          AbTestManager abTestManager,
                          AlertManager alertManager,
                          ObservabilityDepthRegistry observabilityDepthRegistry,
                          InvocationTraceStore traceStore,
                          LogLevelRegistry logLevelRegistry,
                          Option<DynamicConfigManager> dynamicConfigManager,
                          AppHttpServer appHttpServer,
                          TTMManager ttmManager,
                          RollbackManager rollbackManager,
                          ScheduledTaskManager scheduledTaskManager,
                          ComprehensiveSnapshotCollector snapshotCollector,
                          ArtifactMetricsCollector artifactMetricsCollector,
                          DeploymentMap deploymentMap,
                          ClusterEventAggregator eventAggregator,
                          BackupService backupService,
                          StreamPartitionManager streamPartitionManager,
                          EventLoopMetricsCollector eventLoopMetricsCollector,
                          CoreSwimHealthDetector swimHealthDetector,
                          Option<ManagementServer> managementServer,
                          Option<DiscoveryProvider> discoveryProvider,
                          Option<CertificateRenewalScheduler> certRenewalScheduler,
                          long startTimeMs) implements AetherNode {
            private static final Logger log = LoggerFactory.getLogger(aetherNode.class);

            @Override
            public NodeId self() {
                return config.self();
            }

            @Override
            public TopologyManager topologyManager() {
                return clusterNode.topologyManager();
            }

            @Override
            public Promise<Unit> start() {
                log.info("Starting Aether node {}", self());
                // Start comprehensive snapshot collection (feeds TTM pipeline)
                snapshotCollector.start();
                SliceRuntime.setSliceInvoker(sliceInvoker);
                // Start certificate renewal scheduler if configured
                certRenewalScheduler.onPresent(CertificateRenewalScheduler::start);
                return managementServer.map(ManagementServer::start)
                                       .or(Promise.unitPromise())
                                       .flatMap(_ -> appHttpServer.start())
                                       .flatMap(_ -> startClusterAsync())
                                       .onSuccess(_ -> log.info("Aether node {} started, cluster forming...",
                                                                self()));
            }

            @Override
            public Promise<Unit> stop() {
                log.info("Stopping Aether node {}", self());
                // 1. Notify components that quorum is gone (stops scheduled tasks via SharedScheduler)
                router.route(QuorumStateNotification.disappeared());
                // 2. Stop message delivery (no new messages will be routed)
                router.quiesce();
                // 3. Stop components (they've already stopped their activities via quorum notification)
                controlLoop.stop();
                metricsScheduler.stop();
                deploymentMetricsScheduler.stop();
                ttmManager.stop();
                scheduledTaskManager.stop();
                snapshotCollector.stop();
                SliceRuntime.clear();
                streamPartitionManager.close();
                // 4. Stop certificate renewal scheduler
                certRenewalScheduler.onPresent(CertificateRenewalScheduler::stop);
                // 5. Stop SWIM health detector
                swimHealthDetector.stop();
                // 6. Deregister from discovery provider
                discoveryProvider.onPresent(this::deregisterFromDiscovery);
                // 7. Stop servers and network
                return managementServer.map(ManagementServer::stop)
                                       .or(Promise.unitPromise())
                                       .flatMap(_ -> appHttpServer.stop())
                                       .flatMap(_ -> sliceInvoker.stop())
                                       .flatMap(_ -> clusterNode.stop())
                                       .onSuccess(_ -> log.info("Aether node {} stopped",
                                                                self()));
            }

            private Promise<Unit> startClusterAsync() {
                return clusterNode.start()
                                  .onSuccess(_ -> {
                                                 log.info("Aether node {} cluster formation complete",
                                                          self());
                                                 // Register Netty EventLoopGroups for metrics collection
                clusterNode.network()
                           .server()
                           .onPresent(server -> {
                                          eventLoopMetricsCollector.register(server.bossGroup());
                                          eventLoopMetricsCollector.register(server.workerGroup());
                                          log.info("Registered EventLoopGroups for metrics collection");
                                      });
                                                 // Trigger leader election after consensus sync completes.
                // This is the correct trigger point because consensus is
                // guaranteed ready (startPromise resolved). LeaderManager.start()
                // does NOT auto-trigger in consensus mode — ESTABLISHED fires
                // before sync, so proposals would be rejected by dormant engines.
                // LeaderManager applies rank-based staggered delay internally
                // to prevent livelock from simultaneous proposals.
                clusterNode.leaderManager()
                           .triggerElection();
                                                 // Register with discovery provider after cluster is ready
                discoveryProvider.onPresent(this::registerWithDiscovery);
                                                 // Apply aether-node-id tag to self instance for cloud scale-down lookup
                applyNodeIdTag();
                                             })
                                  .onSuccess(_ -> printStartupBanner())
                                  .onFailure(cause -> log.error("Cluster formation failed: {}",
                                                                cause.message()));
            }

            private void registerWithDiscovery(DiscoveryProvider dp) {
                Option.from(config.topology()
                                  .coreNodes()
                                  .stream()
                                  .filter(n -> n.id()
                                                .equals(self()))
                                  .findFirst())
                      .map(n -> new PeerInfo(n.address()
                                              .host(),
                                             n.address()
                                              .port(),
                                             Map.of("role",
                                                    "core",
                                                    "nodeId",
                                                    self().id())))
                      .onPresent(peerInfo -> dp.registerSelf(peerInfo)
                                               .await()
                                               .onSuccess(_ -> log.info("Registered self with discovery provider"))
                                               .onFailure(cause -> log.warn("Failed to register with discovery: {}",
                                                                            cause.message())));
            }

            /// Apply the aether-node-id tag to the self instance via ComputeProvider.
            /// This enables tag-based instance lookup during cloud scale-down termination.
            private void applyNodeIdTag() {
                config.environment()
                      .flatMap(EnvironmentIntegration::compute)
                      .onPresent(this::tagSelfInstance);
            }

            private void tagSelfInstance(ComputeProvider provider) {
                provider.listInstances()
                        .onSuccess(instances -> tagMatchingInstance(provider, instances))
                        .onFailure(cause -> log.warn("Failed to list instances for self-tagging: {}",
                                                     cause.message()));
            }

            private void tagMatchingInstance(ComputeProvider provider, List<InstanceInfo> instances) {
                findSelfInstance(instances).onPresent(instance -> provider.applyTags(instance.id(),
                                                                                     Map.of("aether-node-id",
                                                                                            self().id()))
                                                                          .onSuccess(_ -> log.info("Applied aether-node-id tag to instance {}",
                                                                                                   instance.id()
                                                                                                           .value()))
                                                                          .onFailure(cause -> log.warn("Failed to apply aether-node-id tag: {}",
                                                                                                       cause.message())));
            }

            private Option<InstanceInfo> findSelfInstance(List<InstanceInfo> instances) {
                return selfAddress()
                .flatMap(selfIp -> Option.from(instances.stream()
                                                        .filter(i -> i.addresses()
                                                                      .contains(selfIp))
                                                        .findFirst()));
            }

            private Option<String> selfAddress() {
                return Option.from(config.topology()
                                         .coreNodes()
                                         .stream()
                                         .filter(n -> n.id()
                                                       .equals(self()))
                                         .map(n -> n.address()
                                                    .host())
                                         .findFirst());
            }

            private void deregisterFromDiscovery(DiscoveryProvider dp) {
                dp.stopWatching()
                  .await()
                  .onFailure(cause -> log.warn("Failed to stop discovery watching: {}",
                                               cause.message()));
                dp.deregisterSelf()
                  .await()
                  .onFailure(cause -> log.warn("Failed to deregister from discovery: {}",
                                               cause.message()));
            }

            private void printStartupBanner() {
                var nodeId = self().id();
                var clusterPort = Option.from(config.topology()
                                                    .coreNodes()
                                                    .stream()
                                                    .filter(n -> n.id()
                                                                  .equals(self()))
                                                    .findFirst())
                                        .map(n -> n.address()
                                                   .port())
                                        .or(0);
                var mgmtPort = config.managementPort();
                var appHttpPort = config.appHttp()
                                        .enabled()
                                  ? config.appHttp()
                                          .port()
                                  : 0;
                var peerCount = config.topology()
                                      .coreNodes()
                                      .size();
                var ttmEnabled = ttmManager.isEnabled();
                var tlsEnabled = config.tls()
                                       .isPresent();
                log.info("{}", "+-----------------------------------------------------------------+");
                log.info("{}", "|                     AETHER NODE v" + VERSION + "                       |");
                log.info("{}", "+-----------------------------------------------------------------+");
                log.info("|  Node ID:        {}", pad(nodeId, 46) + "|");
                log.info("|  Cluster Port:   {}", pad(String.valueOf(clusterPort), 46) + "|");
                if (mgmtPort > 0) {
                    log.info("|  Management:     {}", pad("http://localhost:" + mgmtPort, 46) + "|");
                } else {
                    log.info("|  Management:     {}", pad("disabled", 46) + "|");
                }
                if (appHttpPort > 0) {
                    log.info("|  App HTTP:       {}", pad("http://localhost:" + appHttpPort, 46) + "|");
                } else {
                    log.info("|  App HTTP:       {}", pad("disabled", 46) + "|");
                }
                log.info("|  Peers:          {}", pad(peerCount + " configured", 46) + "|");
                log.info("|  TTM:            {}", pad(ttmEnabled
                                                      ? "enabled"
                                                      : "disabled", 46) + "|");
                log.info("|  TLS:            {}", pad(tlsEnabled
                                                      ? "enabled"
                                                      : "disabled", 46) + "|");
                var discoveryEnabled = discoveryProvider.isPresent();
                log.info("|  Discovery:      {}", pad(discoveryEnabled
                                                      ? "enabled"
                                                      : "disabled", 46) + "|");
                log.info("{}", "+-----------------------------------------------------------------+");
            }

            private static String pad(String value, int width) {
                if (value.length() >= width) {
                    return value.substring(0, width);
                }
                return value + " ".repeat(width - value.length());
            }

            @Override
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return switchableCluster.apply(commands);
            }

            @Override
            public int connectedNodeCount() {
                return clusterNode.network()
                                  .connectedNodeCount();
            }

            @Override
            public Map<String, Number> transportMetrics() {
                return clusterNode.network()
                                  .transportMetrics();
            }

            @Override
            public Set<NodeId> connectedPeerIds() {
                return clusterNode.network()
                                  .connectedPeers();
            }

            @Override
            public boolean isLeader() {
                return clusterNode.leaderManager()
                                  .isLeader();
            }

            @Override
            public boolean isReady() {
                return clusterNode.isActive();
            }

            @Override
            public Option<NodeId> leader() {
                return clusterNode.leaderManager()
                                  .leader();
            }

            @Override
            public int managementPort() {
                return config.managementPort();
            }

            @Override
            public long uptimeSeconds() {
                return (System.currentTimeMillis() - startTimeMs) / 1000;
            }

            @Override
            public List<NodeId> initialTopology() {
                return config.topology()
                             .coreNodes()
                             .stream()
                             .map(NodeInfo::id)
                             .toList();
            }

            @Override
            public TopologyConfig topologyConfig() {
                return config.topology();
            }

            @Override
            public void route(Message message) {
                router.route(message);
            }
        }
        // Create HTTP route publisher for slice route publication (needed by InvocationHandler)
        var httpRoutePublisher = HttpRoutePublisher.httpRoutePublisher(config.self(), clusterNode);
        // Create invocation metrics collector
        var invocationMetrics = InvocationMetricsCollector.invocationMetricsCollector();
        // Create log level registry with KV-Store persistence
        var logLevelRegistry = LogLevelRegistry.logLevelRegistry(clusterNode, kvStore);
        // Create observability depth registry with KV-Store persistence
        var depthRegistry = ObservabilityDepthRegistry.observabilityDepthRegistry(clusterNode,
                                                                                  kvStore,
                                                                                  config.observability());
        // Create unified observability components — use no-op interceptor when depth < 0 (disabled)
        var traceStore = InvocationTraceStore.invocationTraceStore();
        var observabilityInterceptor = config.observability()
                                             .depthThreshold() < 0
                                       ? ObservabilityInterceptor.noOp()
                                       : createObservabilityInterceptor(config, traceStore, depthRegistry);
        // Create invocation handler BEFORE deployment manager (needed for slice registration)
        // Pass serializer/deserializer, httpRoutePublisher, and shared observability interceptor for HTTP request routing
        var invocationHandler = InvocationHandler.invocationHandler(config.self(),
                                                                    clusterNode.network(),
                                                                    invocationMetrics,
                                                                    config.timeouts()
                                                                          .invocation()
                                                                          .timeout(),
                                                                    serializer,
                                                                    deserializer,
                                                                    httpRoutePublisher,
                                                                    observabilityInterceptor);
        // Create deployment metrics components
        var deploymentMetricsCollector = DeploymentMetricsCollector.deploymentMetricsCollector(config.self(),
                                                                                               clusterNode.network());
        var deploymentMetricsScheduler = DeploymentMetricsScheduler.deploymentMetricsScheduler(config.self(),
                                                                                               clusterNode.network(),
                                                                                               deploymentMetricsCollector);
        // Extract initial topology from config (node IDs from core nodes)
        var initialTopology = config.topology()
                                    .coreNodes()
                                    .stream()
                                    .map(NodeInfo::id)
                                    .toList();
        // Create schema migration engine and orchestrator (uses composite repository for local Maven fallback)
        var schemaPolicy = SchemaPolicy.schemaPolicy();
        var schemaManager = AetherSchemaManager.aetherSchemaManager(schemaPolicy);
        var repository = compositeRepository(repositories);
        var connectionProvider = DatasourceConnectionProvider.datasourceConnectionProvider();
        var schemaOrchestrator = SchemaOrchestratorService.schemaOrchestratorService(clusterNode,
                                                                                     kvStore,
                                                                                     artifactStore,
                                                                                     repository,
                                                                                     schemaManager,
                                                                                     connectionProvider,
                                                                                     config.self(),
                                                                                     delegateRouter);
        // Create NodeLifecycleManager for cloud operations (shared by CTM and CDM drain completion)
        var computeProvider = config.environment()
                                    .flatMap(EnvironmentIntegration::compute);
        var lifecycleManager = NodeLifecycleManager.nodeLifecycleManager(computeProvider);
        // Create ClusterTopologyManager wrapping the observer — manages cluster size via reconciliation state machine
        var clusterTopologyManager = ClusterTopologyManager.clusterTopologyManager((org.pragmatica.consensus.topology.TopologyObserver) clusterNode.topologyManager(),
                                                                                   lifecycleManager,
                                                                                   config.autoHeal());
        var clusterDeploymentManager = ClusterDeploymentManager.clusterDeploymentManager(config.self(),
                                                                                         clusterNode,
                                                                                         kvStore,
                                                                                         delegateRouter,
                                                                                         initialTopology,
                                                                                         clusterNode.topologyManager(),
                                                                                         config.atomicity(),
                                                                                         config.topology()
                                                                                               .coreMax(),
                                                                                         config.timeouts()
                                                                                               .deployment()
                                                                                               .reconciliationInterval(),
                                                                                         schemaOrchestrator);
        // Create load balancer manager when provider is available
        var loadBalancerManager = config.environment()
                                        .flatMap(EnvironmentIntegration::loadBalancer)
                                        .map(provider -> LoadBalancerManager.loadBalancerManager(config.self(),
                                                                                                 kvStore,
                                                                                                 clusterNode.topologyManager(),
                                                                                                 provider,
                                                                                                 config.appHttp()
                                                                                                       .port()));
        // Extract discovery provider when available
        var discoveryProvider = config.environment()
                                      .flatMap(EnvironmentIntegration::discovery);
        // Create endpoint registry
        var endpointRegistry = EndpointRegistry.endpointRegistry();
        // Create topic subscription registry for pub/sub messaging
        var topicSubscriptionRegistry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
        // Create scheduled task registry and manager for periodic slice method invocation
        var scheduledTaskRegistry = ScheduledTaskRegistry.scheduledTaskRegistry();
        var scheduledTaskStateRegistry = ScheduledTaskStateRegistry.scheduledTaskStateRegistry();
        // Create HTTP route registry for application HTTP routing
        var httpRouteRegistry = HttpRouteRegistry.httpRouteRegistry();
        // Create metrics components
        var metricsCollector = MetricsCollector.metricsCollector(config.self(),
                                                                 clusterNode.network(),
                                                                 config.timeouts()
                                                                       .observability()
                                                                       .metricsSlidingWindow()
                                                                       .millis());
        // Wire invocation metrics and management port into MetricsCollector for cluster-wide gossip
        metricsCollector.setInvocationMetricsProvider(invocationMetrics);
        metricsCollector.recordCustom("mgmt.port", config.managementPort());
        var metricsScheduler = MetricsScheduler.metricsScheduler(config.self(), clusterNode.network(), metricsCollector);
        // Create base decision tree controller (uses node config — Forge disables CPU-based scaling)
        var controller = DecisionTreeController.decisionTreeController(config.controllerConfig());
        // Create blueprint service using composite repository from configuration
        var blueprintService = BlueprintService.blueprintService(clusterNode, kvStore, repository, artifactStore);
        // Create Maven protocol handler from artifact store (DHT created in createNode)
        var mavenProtocolHandler = MavenProtocolHandler.mavenProtocolHandler(artifactStore);
        // Create rolling update manager
        var rollingUpdateManager = RollingUpdateManager.rollingUpdateManager(clusterNode,
                                                                             kvStore,
                                                                             invocationMetrics,
                                                                             config.timeouts()
                                                                                   .rollingUpdate()
                                                                                   .kvOperation(),
                                                                             config.timeouts()
                                                                                   .rollingUpdate()
                                                                                   .terminalRetention()
                                                                                   .millis());
        // Create alert manager with KV-Store persistence
        var alertManager = AlertManager.alertManager(clusterNode, kvStore);
        // Create dynamic config manager if dynamic provider is available
        var dynamicConfigManager = resourceProviderSetup.dynamicProvider()
                                                        .map(dp -> DynamicConfigManager.dynamicConfigManager(clusterNode,
                                                                                                             kvStore,
                                                                                                             dp,
                                                                                                             config.self()));
        // Create minute aggregator for TTM and metrics collection
        var minuteAggregator = MinuteAggregator.minuteAggregator();
        // Create subsystem collectors for comprehensive snapshots
        var gcMetricsCollector = GCMetricsCollector.gcMetricsCollector();
        var eventLoopMetricsCollector = EventLoopMetricsCollector.eventLoopMetricsCollector(config.timeouts()
                                                                                                  .observability()
                                                                                                  .eventLoopProbe()
                                                                                                  .millis());
        // EventLoopGroups are registered in startClusterAsync() when Server becomes available
        // Create comprehensive snapshot collector (feeds TTM pipeline)
        var snapshotCollector = ComprehensiveSnapshotCollector.comprehensiveSnapshotCollector(gcMetricsCollector,
                                                                                              eventLoopMetricsCollector,
                                                                                              networkMetricsHandler,
                                                                                              rabiaMetricsCollector,
                                                                                              invocationMetrics,
                                                                                              minuteAggregator);
        // Create artifact metrics collector for storage and deployment tracking
        var artifactMetricsCollector = ArtifactMetricsCollector.artifactMetricsCollector(artifactStore);
        // Create deployment map for event-driven slice-node indexing
        var deploymentMap = DeploymentMap.deploymentMap();
        // Create cluster event aggregator for structured event collection
        var eventAggregator = ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig());
        // Create TTM manager (returns no-op if disabled in config)
        var ttmManager = TTMManager.ttmManager(config.ttm(),
                                               minuteAggregator,
                                               controller::configuration)
                                   .or(TTMManager.noOp(config.ttm()));
        // Create control loop with adaptive controller when TTM is actually enabled and functional
        ClusterController effectiveController = ttmManager.isEnabled()
                                                ? AdaptiveDecisionTree.adaptiveDecisionTree(controller, ttmManager)
                                                : controller;
        var controlLoop = ControlLoop.controlLoop(config.self(),
                                                  effectiveController,
                                                  metricsCollector,
                                                  Option.some(invocationMetrics),
                                                  clusterNode,
                                                  TimeSpan.timeSpan(config.controllerConfig()
                                                                          .scalingConfig()
                                                                          .evaluationIntervalMs())
                                                          .millis(),
                                                  config.controllerConfig(),
                                                  delegateRouter::route);
        // Create rollback manager for automatic version rollback on persistent failures
        var rollbackManager = config.rollback()
                                    .enabled()
                              ? RollbackManager.rollbackManager(config.self(), config.rollback(), clusterNode, kvStore)
                              : RollbackManager.disabled();
        // Create canary deployment manager
        var canaryDeploymentManager = CanaryDeploymentManager.canaryDeploymentManager(clusterNode,
                                                                                      kvStore,
                                                                                      invocationMetrics);
        // Create blue-green deployment manager
        var blueGreenDeploymentManager = BlueGreenDeploymentManager.blueGreenDeploymentManager(clusterNode,
                                                                                               kvStore,
                                                                                               invocationMetrics);
        // Create A/B test manager
        var abTestManager = AbTestManager.abTestManager(clusterNode, kvStore, invocationMetrics);
        // Create strategy coordinator for deployment routing decisions
        var strategyCoordinator = DeploymentStrategyCoordinator.deploymentStrategyCoordinator(rollingUpdateManager,
                                                                                              Option.some(canaryDeploymentManager),
                                                                                              Option.some(blueGreenDeploymentManager),
                                                                                              Option.some(abTestManager));
        // Create slice invoker (needs strategyCoordinator for weighted routing during deployments)
        var sliceInvoker = SliceInvoker.sliceInvoker(config.self(),
                                                     clusterNode.network(),
                                                     endpointRegistry,
                                                     invocationHandler,
                                                     serializer,
                                                     deserializer,
                                                     config.timeouts()
                                                           .invocation()
                                                           .invokerTimeout()
                                                           .millis(),
                                                     config.timeouts()
                                                           .observability()
                                                           .invocationCleanup()
                                                           .millis(),
                                                     strategyCoordinator,
                                                     observabilityInterceptor);
        // Wire the deferred invoker facade to the actual SliceInvoker
        deferredInvoker.setDelegate(sliceInvoker);
        // Create scheduled task manager (needs sliceInvoker for method execution)
        var scheduledTaskManager = ScheduledTaskManager.scheduledTaskManager(scheduledTaskRegistry,
                                                                             sliceInvoker,
                                                                             config.self(),
                                                                             command -> clusterNode.apply(List.of(command)));
        // Register runtime extensions for Publisher provisioning via SPI
        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> registerRuntimeExtensions(spi,
                                                                         topicSubscriptionRegistry,
                                                                         sliceInvoker,
                                                                         cacheDhtClient));
        // Create node deployment manager (now created after sliceInvoker for HTTP route publishing)
        var selfAddress = findSelfAddress(config);
        var nodeDeploymentManager = NodeDeploymentManager.nodeDeploymentManager(config.self(),
                                                                                selfAddress,
                                                                                delegateRouter,
                                                                                sliceStore,
                                                                                clusterNode,
                                                                                kvStore,
                                                                                invocationHandler,
                                                                                config.sliceAction(),
                                                                                nodeCodec,
                                                                                Option.some(httpRoutePublisher),
                                                                                Option.some(sliceInvoker),
                                                                                config.timeouts()
                                                                                      .deployment()
                                                                                      .activationChain(),
                                                                                config.timeouts()
                                                                                      .deployment()
                                                                                      .transitionRetryDelay());
        // Extract shared event loop groups from the cluster network server (if available)
        var serverBossGroup = clusterNode.network()
                                         .server()
                                         .map(org.pragmatica.net.tcp.Server::bossGroup);
        var serverWorkerGroup = clusterNode.network()
                                           .server()
                                           .map(org.pragmatica.net.tcp.Server::workerGroup);
        // Create application HTTP server for slice-provided routes (with HTTP forwarding support)
        var appHttpServer = AppHttpServer.appHttpServer(config.appHttp(),
                                                        config.self(),
                                                        httpRouteRegistry,
                                                        Option.some(httpRoutePublisher),
                                                        Option.some(clusterNode.network()),
                                                        Option.some(serializer),
                                                        Option.some(deserializer),
                                                        config.tls(),
                                                        Option.some(invocationMetrics),
                                                        serverBossGroup,
                                                        serverWorkerGroup,
                                                        Option.some(strategyCoordinator));
        // DHT subscriptions removed — all control plane state flows through KV-Store notifications
        // Collect all route entries from RabiaNode and AetherNode components
        var aetherEntries = collectRouteEntries(kvStore,
                                                nodeDeploymentManager,
                                                clusterDeploymentManager,
                                                endpointRegistry,
                                                topicSubscriptionRegistry,
                                                scheduledTaskRegistry,
                                                scheduledTaskStateRegistry,
                                                scheduledTaskManager,
                                                httpRouteRegistry,
                                                metricsCollector,
                                                metricsScheduler,
                                                deploymentMetricsCollector,
                                                deploymentMetricsScheduler,
                                                controlLoop,
                                                sliceInvoker,
                                                invocationHandler,
                                                alertManager,
                                                depthRegistry,
                                                logLevelRegistry,
                                                dynamicConfigManager,
                                                ttmManager,
                                                rabiaMetricsCollector,
                                                rollingUpdateManager,
                                                canaryDeploymentManager,
                                                blueGreenDeploymentManager,
                                                abTestManager,
                                                rollbackManager,
                                                artifactMetricsCollector,
                                                deploymentMap,
                                                eventAggregator,
                                                clusterNode.leaderManager(),
                                                appHttpServer,
                                                loadBalancerManager,
                                                (TopologyObserver) clusterNode.topologyManager(),
                                                clusterTopologyManager);
        // DHT message routes for distributed operations
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.GetRequest.class,
                                                    request -> dhtNode.handleGetRequest(request,
                                                                                        response -> dhtNetwork.send(request.sender(),
                                                                                                                    response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.PutRequest.class,
                                                    request -> dhtNode.handlePutRequest(request,
                                                                                        response -> handleRemotePutResponse(dhtNetwork,
                                                                                                                            aetherMaps,
                                                                                                                            request,
                                                                                                                            response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.RemoveRequest.class,
                                                    request -> dhtNode.handleRemoveRequest(request,
                                                                                           response -> handleRemoteRemoveResponse(dhtNetwork,
                                                                                                                                  aetherMaps,
                                                                                                                                  request,
                                                                                                                                  response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.ExistsRequest.class,
                                                    request -> dhtNode.handleExistsRequest(request,
                                                                                           response -> dhtNetwork.send(request.sender(),
                                                                                                                       response))));
        // DHT response routes — complete the request/response cycle for DistributedDHTClient
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.GetResponse.class, dhtClient::onGetResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.PutResponse.class, dhtClient::onPutResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.RemoveResponse.class, dhtClient::onRemoveResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.ExistsResponse.class, dhtClient::onExistsResponse));
        // DHT digest routes — anti-entropy replica synchronization
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.DigestRequest.class,
                                                    request -> dhtNode.handleDigestRequest(request,
                                                                                           response -> dhtNetwork.send(request.sender(),
                                                                                                                       response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.DigestResponse.class, dhtAntiEntropy::onDigestResponse));
        // DHT migration routes — data transfer for partition repair
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataRequest.class,
                                                    request -> dhtNode.handleMigrationDataRequest(request,
                                                                                                  response -> dhtNetwork.send(request.sender(),
                                                                                                                              response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataResponse.class,
                                                    dhtAntiEntropy::onMigrationDataResponse));
        // DHT topology listener — update consistent hash ring on node add/remove
        aetherEntries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class,
                                                    dhtTopologyListener::onNodeAdded));
        aetherEntries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                                    dhtTopologyListener::onNodeRemoved));
        // DHTNotification routes removed — passive LB uses KV-Store notifications directly
        // ForwardApply message handlers — core nodes handle forwarded commands from workers,
        // workers receive responses from core nodes.
        @SuppressWarnings({"unchecked", "rawtypes"})
        MessageRouter.Entry forwardRequestRoute = MessageRouter.Entry.route(ForwardApplyRequest.class,
                                                                            (ForwardApplyRequest request) -> handleForwardApplyRequest(request,
                                                                                                                                       clusterNode));
        aetherEntries.add(forwardRequestRoute);
        @SuppressWarnings({"unchecked", "rawtypes"})
        MessageRouter.Entry forwardResponseRoute = MessageRouter.Entry.route(ForwardApplyResponse.class,
                                                                             forwardingClusterNode::onForwardApplyResponse);
        aetherEntries.add(forwardResponseRoute);
        // Activation directive KV handler — CDM submits activation through consensus,
        // so all nodes see committed directives. Each node checks if the directive targets itself.
        var growthLog = LoggerFactory.getLogger(AetherNode.class);
        var selfId = config.self();
        // Create rotating encryptor once — shared between SWIM transport and rotation handler
        var rotatingEncryptor = createGossipEncryptor(config);
        var gossipKeyRotationHandler = GossipKeyRotationHandler.gossipKeyRotationHandler(rotatingEncryptor);
        var activationKvRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                     .onPut(AetherKey.ActivationDirectiveKey.class,
                                                            (ValuePut<AetherKey.ActivationDirectiveKey, AetherValue.ActivationDirectiveValue> put) -> handleActivationDirective(put,
                                                                                                                                                                                selfId,
                                                                                                                                                                                clusterNode,
                                                                                                                                                                                switchableCluster,
                                                                                                                                                                                forwardingClusterNode,
                                                                                                                                                                                config,
                                                                                                                                                                                delegateRouter,
                                                                                                                                                                                kvStore,
                                                                                                                                                                                sliceStore,
                                                                                                                                                                                sliceInvoker,
                                                                                                                                                                                growthLog))
                                                     .onPut(AetherKey.GossipKeyRotationKey.class,
                                                            gossipKeyRotationHandler::onGossipKeyRotationPut)
                                                     .build();
        var allEntries = new ArrayList<>(clusterNode.routeEntries());
        allEntries.addAll(aetherEntries);
        allEntries.addAll(activationKvRouter.asRouteEntries());
        // Create SWIM health detector — encryptor resolved at quorum time, not here
        var swimHealthDetector = CoreSwimHealthDetector.coreSwimHealthDetector(delegateRouter,
                                                                               config.topology(),
                                                                               serializer,
                                                                               deserializer);
        // Defer SWIM start until quorum — pass pre-built encryptor (certificate provider guaranteed ready)
        allEntries.add(MessageRouter.Entry.route(QuorumStateNotification.class,
                                                 notification -> startSwimOnQuorum(notification,
                                                                                   swimHealthDetector,
                                                                                   clusterNode.network(),
                                                                                   rotatingEncryptor)));
        // Wire TCP connection events to SWIM: reset FAULTY members when TCP proves they're alive
        allEntries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionEstablished.class,
                                                 connection -> swimHealthDetector.onNodeConnected(connection.nodeId())));
        // Create stream partition manager for local stream operations
        var streamPartitionManager = StreamPartitionManager.streamPartitionManager();
        // Create certificate renewal scheduler if a provider is configured
        // Management server is created later; use AtomicReference for deferred access
        var managementServerRef = new java.util.concurrent.atomic.AtomicReference<Option<ManagementServer>>(Option.empty());
        var certRenewalScheduler = createCertRenewalScheduler(config,
                                                              clusterNode,
                                                              appHttpServer,
                                                              managementServerRef::get);
        // Create the node first (without management server reference)
        var startTimeMs = System.currentTimeMillis();
        var node = new aetherNode(config,
                                  delegateRouter,
                                  kvStore,
                                  sliceRegistry,
                                  sliceStore,
                                  clusterNode,
                                  switchableCluster,
                                  nodeDeploymentManager,
                                  clusterDeploymentManager,
                                  endpointRegistry,
                                  httpRouteRegistry,
                                  metricsCollector,
                                  metricsScheduler,
                                  deploymentMetricsCollector,
                                  deploymentMetricsScheduler,
                                  controlLoop,
                                  sliceInvoker,
                                  invocationHandler,
                                  blueprintService,
                                  mavenProtocolHandler,
                                  artifactStore,
                                  invocationMetrics,
                                  controller,
                                  rollingUpdateManager,
                                  canaryDeploymentManager,
                                  blueGreenDeploymentManager,
                                  abTestManager,
                                  alertManager,
                                  depthRegistry,
                                  traceStore,
                                  logLevelRegistry,
                                  dynamicConfigManager,
                                  appHttpServer,
                                  ttmManager,
                                  rollbackManager,
                                  scheduledTaskManager,
                                  snapshotCollector,
                                  artifactMetricsCollector,
                                  deploymentMap,
                                  eventAggregator,
                                  BackupService.disabled(),
                                  streamPartitionManager,
                                  eventLoopMetricsCollector,
                                  swimHealthDetector,
                                  Option.empty(),
                                  discoveryProvider,
                                  certRenewalScheduler,
                                  startTimeMs);
        // Wire remote shutdown: when SHUTTING_DOWN lifecycle is received, stop the node
        nodeDeploymentManager.setShutdownCallback(node::stop);
        // Build and wire ImmutableRouter, then create final node
        return RabiaNode.buildAndWireRouter(delegateRouter, allEntries)
                        .map(_ -> {
                                 // Create management server if enabled
        if (config.managementPort() > 0) {
                                     var mgmtSecurityEnabled = config.appHttp()
                                                                     .securityEnabled();
                                     var mgmtSecurityValidator = mgmtSecurityEnabled
                                                                 ? SecurityValidator.apiKeyValidator(config.appHttp()
                                                                                                           .apiKeys())
                                                                 : SecurityValidator.noOpValidator();
                                     var managementServer = ManagementServer.managementServer(config.managementPort(),
                                                                                              () -> node,
                                                                                              alertManager,
                                                                                              depthRegistry,
                                                                                              traceStore,
                                                                                              logLevelRegistry,
                                                                                              dynamicConfigManager,
                                                                                              scheduledTaskRegistry,
                                                                                              scheduledTaskManager,
                                                                                              sliceInvoker,
                                                                                              scheduledTaskStateRegistry,
                                                                                              config.tls(),
                                                                                              mgmtSecurityValidator,
                                                                                              mgmtSecurityEnabled,
                                                                                              serverBossGroup,
                                                                                              serverWorkerGroup,
                                                                                              config.managementHttpProtocol());
                                     // Wire management server into certificate renewal callback
        managementServerRef.set(Option.some(managementServer));
                                     return new aetherNode(config,
                                                           delegateRouter,
                                                           kvStore,
                                                           sliceRegistry,
                                                           sliceStore,
                                                           clusterNode,
                                                           switchableCluster,
                                                           nodeDeploymentManager,
                                                           clusterDeploymentManager,
                                                           endpointRegistry,
                                                           httpRouteRegistry,
                                                           metricsCollector,
                                                           metricsScheduler,
                                                           deploymentMetricsCollector,
                                                           deploymentMetricsScheduler,
                                                           controlLoop,
                                                           sliceInvoker,
                                                           invocationHandler,
                                                           blueprintService,
                                                           mavenProtocolHandler,
                                                           artifactStore,
                                                           invocationMetrics,
                                                           controller,
                                                           rollingUpdateManager,
                                                           canaryDeploymentManager,
                                                           blueGreenDeploymentManager,
                                                           abTestManager,
                                                           alertManager,
                                                           depthRegistry,
                                                           traceStore,
                                                           logLevelRegistry,
                                                           dynamicConfigManager,
                                                           appHttpServer,
                                                           ttmManager,
                                                           rollbackManager,
                                                           scheduledTaskManager,
                                                           snapshotCollector,
                                                           artifactMetricsCollector,
                                                           deploymentMap,
                                                           eventAggregator,
                                                           BackupService.disabled(),
                                                           streamPartitionManager,
                                                           eventLoopMetricsCollector,
                                                           swimHealthDetector,
                                                           Option.some(managementServer),
                                                           discoveryProvider,
                                                           certRenewalScheduler,
                                                           startTimeMs);
                                 }
                                 return node;
                             });
    }

    @SuppressWarnings({"JBCT-RET-01", "unchecked"})
    private static void notifyCtmOnDuty(ValuePut<AetherKey.NodeLifecycleKey, AetherValue> put,
                                        ClusterTopologyManager ctm) {
        if (put.cause()
               .value() instanceof AetherValue.NodeLifecycleValue lifecycleValue && lifecycleValue.state() == AetherValue.NodeLifecycleState.ON_DUTY) {
            var nodeId = put.cause()
                            .key()
                            .nodeId();
            markReadyWithAddress(ctm, nodeId, lifecycleValue);
            ctm.onNodeReady(nodeId);
        }
    }

    private static void markReadyWithAddress(ClusterTopologyManager ctm,
                                             NodeId nodeId,
                                             AetherValue.NodeLifecycleValue lifecycleValue) {
        if (lifecycleValue.hasAddress()) {
            ctm.observer()
               .markReady(nodeId,
                          new NodeAddress(lifecycleValue.host(),
                                          lifecycleValue.port()));
        } else {
            ctm.observer()
               .markReady(nodeId);
        }
    }

    private static NodeAddress findSelfAddress(AetherNodeConfig config) {
        return config.topology()
                     .coreNodes()
                     .stream()
                     .filter(info -> info.id()
                                         .equals(config.self()))
                     .map(NodeInfo::address)
                     .findFirst()
                     .orElse(new NodeAddress("", 0));
    }

    @SuppressWarnings("JBCT-RET-01")
    private static void handleCtmLeaderChange(LeaderNotification.LeaderChange change,
                                              ClusterTopologyManager ctm) {
        if (change.localNodeIsLeader()) {
            ctm.activate();
        } else {
            ctm.deactivate();
        }
    }

    private static void startSwimOnQuorum(QuorumStateNotification notification,
                                          CoreSwimHealthDetector swimHealthDetector,
                                          ClusterNetwork network,
                                          RotatingGossipEncryptor encryptor) {
        if (notification.state() == QuorumStateNotification.State.ESTABLISHED) {
            var workerGroup = network.server()
                                     .map(org.pragmatica.net.tcp.Server::workerGroup);
            swimHealthDetector.start(workerGroup, encryptor);
        }
    }

    @SuppressWarnings({"JBCT-RET-01"})
    private static void handleActivationDirective(ValuePut<AetherKey.ActivationDirectiveKey, AetherValue.ActivationDirectiveValue> put,
                                                  NodeId selfId,
                                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                  SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                                                  ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                                                  AetherNodeConfig config,
                                                  MessageRouter.DelegateRouter delegateRouter,
                                                  KVStore<AetherKey, AetherValue> kvStore,
                                                  SliceStore sliceStore,
                                                  SliceInvoker sliceInvoker,
                                                  Logger growthLog) {
        if (!put.cause()
                .key()
                .nodeId()
                .equals(selfId)) {
            return;
        }
        var role = put.cause()
                      .value()
                      .role();
        if (AetherValue.ActivationDirectiveValue.CORE.equals(role)) {
            growthLog.info("Received core activation directive from CDM");
            clusterNode.authorizeActivation();
        } else if (AetherValue.ActivationDirectiveValue.WORKER.equals(role)) {
            growthLog.info("Received worker activation directive from CDM");
            activateWorkerMode(selfId,
                               clusterNode,
                               switchableCluster,
                               forwardingClusterNode,
                               config,
                               delegateRouter,
                               kvStore,
                               sliceStore,
                               sliceInvoker,
                               growthLog);
        }
    }

    @SuppressWarnings({"JBCT-RET-01"})
    private static void activateWorkerMode(NodeId selfId,
                                           RabiaNode<KVCommand<AetherKey>> clusterNode,
                                           SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                                           ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                                           AetherNodeConfig config,
                                           MessageRouter.DelegateRouter delegateRouter,
                                           KVStore<AetherKey, AetherValue> kvStore,
                                           SliceStore sliceStore,
                                           SliceInvoker sliceInvoker,
                                           Logger log) {
        // 1. Enter observer mode (receive Decisions, don't vote)
        clusterNode.authorizeObservation();
        // 2. Switch to forwarding apply() calls to core peers
        switchableCluster.switchTo(forwardingClusterNode);
        log.info("Worker {} switched to forwarding mode", selfId.id());
        // 3. Create worker subsystems
        var decisionRelay = DecisionRelay.decisionRelay(selfId, delegateRouter);
        var mutationForwarder = MutationForwarder.mutationForwarder(selfId, delegateRouter);
        var workerBootstrap = WorkerBootstrap.workerBootstrap(selfId, delegateRouter, kvStore);
        var governorMesh = GovernorMesh.governorMesh(delegateRouter);
        var groupMembershipTracker = GroupMembershipTracker.groupMembershipTracker(selfId,
                                                                                   config.workerConfig()
                                                                                         .map(WorkerConfig::groupName)
                                                                                         .or(WorkerConfig.DEFAULT_GROUP_NAME),
                                                                                   config.workerConfig()
                                                                                         .map(WorkerConfig::maxGroupSize)
                                                                                         .or(WorkerConfig.DEFAULT_MAX_GROUP_SIZE));
        var governorCleanup = GovernorCleanup.governorCleanup(mutationForwarder);
        var workerDeploymentManager = WorkerDeploymentManager.workerDeploymentManager(selfId,
                                                                                      sliceStore,
                                                                                      mutationForwarder,
                                                                                      List.of(),
                                                                                      () -> groupMembershipTracker.myGroup()
                                                                                                                  .communityId());
        log.info("Worker {} subsystems created, ready for SWIM-based community formation", selfId.id());
    }

    @SuppressWarnings({"unchecked", "rawtypes", "JBCT-RET-01"})
    private static void handleForwardApplyRequest(ForwardApplyRequest request,
                                                  RabiaNode<KVCommand<AetherKey>> clusterNode) {
        clusterNode.apply((List) request.commands())
                   .onSuccess(results -> sendSuccessResponse(clusterNode,
                                                             request,
                                                             (List) results))
                   .onFailure(cause -> sendFailureResponse(clusterNode,
                                                           request,
                                                           (Cause) cause));
    }

    @SuppressWarnings({"rawtypes"})
    private static void sendSuccessResponse(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            ForwardApplyRequest request,
                                            List<?> results) {
        var response = new ForwardApplyResponse<>(clusterNode.self(), request.correlationId(), results, Option.empty());
        clusterNode.network()
                   .send(request.sender(),
                         response);
    }

    @SuppressWarnings({"rawtypes"})
    private static void sendFailureResponse(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            ForwardApplyRequest request,
                                            Cause cause) {
        var response = new ForwardApplyResponse<>(clusterNode.self(),
                                                  request.correlationId(),
                                                  List.of(),
                                                  Option.some(cause.message()));
        clusterNode.network()
                   .send(request.sender(),
                         response);
    }

    @SuppressWarnings("JBCT-RET-01")
    private static RotatingGossipEncryptor createGossipEncryptor(AetherNodeConfig config) {
        var initial = config.certificateProvider()
                            .flatMap(provider -> buildDualKeyEncryptor(provider))
                            .or(GossipEncryptor.none());
        return RotatingGossipEncryptor.rotatingGossipEncryptor(initial);
    }

    @SuppressWarnings("JBCT-RET-01")
    private static Option<GossipEncryptor> buildDualKeyEncryptor(org.pragmatica.net.tcp.security.CertificateProvider provider) {
        return provider.currentGossipKey()
                       .option()
                       .flatMap(current -> buildEncryptorFromKeys(current,
                                                                  provider.previousGossipKey()));
    }

    @SuppressWarnings("JBCT-RET-01")
    private static Option<GossipEncryptor> buildEncryptorFromKeys(org.pragmatica.net.tcp.security.GossipKey current,
                                                                  Option<org.pragmatica.net.tcp.security.GossipKey> previous) {
        return previous.flatMap(prev -> buildDualKeyAesEncryptor(current, prev))
                       .orElse(() -> buildSingleKeyAesEncryptor(current));
    }

    private static Option<GossipEncryptor> buildDualKeyAesEncryptor(org.pragmatica.net.tcp.security.GossipKey current,
                                                                    org.pragmatica.net.tcp.security.GossipKey prev) {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(current.key(),
                                                           current.keyId(),
                                                           prev.key(),
                                                           prev.keyId())
                                    .option();
    }

    private static Option<GossipEncryptor> buildSingleKeyAesEncryptor(org.pragmatica.net.tcp.security.GossipKey current) {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(current.key(),
                                                           current.keyId())
                                    .option();
    }

    @SuppressWarnings("JBCT-PAT-01") // Conditional scheduler creation based on config
    private static Option<CertificateRenewalScheduler> createCertRenewalScheduler(AetherNodeConfig config,
                                                                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                                  AppHttpServer appHttpServer,
                                                                                  java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        return config.certificateProvider()
                     .flatMap(provider -> buildCertRenewalScheduler(config,
                                                                    provider,
                                                                    clusterNode,
                                                                    appHttpServer,
                                                                    managementServerSupplier));
    }

    @SuppressWarnings("JBCT-PAT-01") // Certificate rotation wiring: issue cert, build scheduler
    private static Option<CertificateRenewalScheduler> buildCertRenewalScheduler(AetherNodeConfig config,
                                                                                 org.pragmatica.net.tcp.security.CertificateProvider provider,
                                                                                 RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                                 AppHttpServer appHttpServer,
                                                                                 java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        var nodeId = config.self()
                           .id();
        var hostname = resolveHostname(config);
        return provider.issueCertificate(nodeId, hostname)
                       .map(bundle -> createSchedulerFromBundle(provider,
                                                                nodeId,
                                                                hostname,
                                                                bundle,
                                                                clusterNode,
                                                                appHttpServer,
                                                                managementServerSupplier))
                       .option();
    }

    private static CertificateRenewalScheduler createSchedulerFromBundle(org.pragmatica.net.tcp.security.CertificateProvider provider,
                                                                         String nodeId,
                                                                         String hostname,
                                                                         CertificateBundle bundle,
                                                                         RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                         AppHttpServer appHttpServer,
                                                                         java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        return CertificateRenewalScheduler.certificateRenewalScheduler(provider,
                                                                       nodeId,
                                                                       hostname,
                                                                       newBundle -> onCertificateRenewed(newBundle,
                                                                                                         clusterNode,
                                                                                                         appHttpServer,
                                                                                                         managementServerSupplier),
                                                                       bundle.notAfter());
    }

    @SuppressWarnings("JBCT-PAT-01") // Certificate rotation: QUIC + HTTP servers
    private static void onCertificateRenewed(CertificateBundle newBundle,
                                             RabiaNode<KVCommand<AetherKey>> clusterNode,
                                             AppHttpServer appHttpServer,
                                             java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        var log = LoggerFactory.getLogger(AetherNode.class);
        log.info("Certificate renewed, valid until {}", newBundle.notAfter());
        Result.all(QuicSslContextFactory.createServerFromBundle(newBundle),
                   QuicSslContextFactory.createClientFromBundle(newBundle))
              .id()
              .onSuccess(tuple -> triggerCertRotation(clusterNode,
                                                      tuple.first(),
                                                      tuple.last(),
                                                      newBundle,
                                                      appHttpServer,
                                                      managementServerSupplier))
              .onFailure(cause -> log.error("Failed to build SSL contexts from renewed certificate: {}",
                                            cause.message()));
    }

    @SuppressWarnings("JBCT-PAT-01") // Fork-join: rotate QUIC + management server + app HTTP server
    private static void triggerCertRotation(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            io.netty.handler.codec.quic.QuicSslContext serverSsl,
                                            io.netty.handler.codec.quic.QuicSslContext clientSsl,
                                            CertificateBundle newBundle,
                                            AppHttpServer appHttpServer,
                                            java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        var log = LoggerFactory.getLogger(AetherNode.class);
        // 1. Rotate QUIC network
        rotateQuicNetwork(clusterNode, serverSsl, clientSsl, log);
        // 2. Rotate management server
        managementServerSupplier.get()
                                .onPresent(mgmt -> rotateManagementServer(mgmt, newBundle, log));
        // 3. Rotate app HTTP server
        rotateAppHttpServer(appHttpServer, newBundle, log);
    }

    private static void rotateQuicNetwork(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                          io.netty.handler.codec.quic.QuicSslContext serverSsl,
                                          io.netty.handler.codec.quic.QuicSslContext clientSsl,
                                          Logger log) {
        if (clusterNode.network() instanceof QuicClusterNetwork quicNetwork) {
            quicNetwork.rotateCertificate(serverSsl, clientSsl)
                       .onSuccess(_ -> log.info("QUIC certificate rotation complete"))
                       .onFailure(cause -> log.error("QUIC certificate rotation failed: {}",
                                                     cause.message()));
        } else {
            log.warn("QUIC certificate rotation skipped: network is not QUIC-based");
        }
    }

    private static void rotateManagementServer(ManagementServer mgmt,
                                               CertificateBundle newBundle,
                                               Logger log) {
        mgmt.rotateCertificate(newBundle)
            .onSuccess(_ -> log.info("Management server certificate rotation complete"))
            .onFailure(cause -> log.error("Management server certificate rotation failed: {}",
                                          cause.message()));
    }

    private static void rotateAppHttpServer(AppHttpServer appHttpServer,
                                            CertificateBundle newBundle,
                                            Logger log) {
        appHttpServer.rotateCertificate(newBundle)
                     .onSuccess(_ -> log.info("App HTTP server certificate rotation complete"))
                     .onFailure(cause -> log.error("App HTTP server certificate rotation failed: {}",
                                                   cause.message()));
    }

    private static String resolveHostname(AetherNodeConfig config) {
        return config.topology()
                     .coreNodes()
                     .stream()
                     .filter(n -> n.id()
                                   .equals(config.self()))
                     .findFirst()
                     .map(n -> n.address()
                                .host())
                     .orElse("localhost");
    }

    @SuppressWarnings("JBCT-RET-01") // Side-effect: send response + dispatch notification
    private static void handleRemotePutResponse(DHTNetwork dhtNetwork,
                                                AetherMaps aetherMaps,
                                                DHTMessage.PutRequest request,
                                                DHTMessage.PutResponse response) {
        dhtNetwork.send(request.sender(), response);
        if (response.success() && !response.superseded()) {
            aetherMaps.dispatchRemotePut(request.key(), request.value());
        }
    }

    @SuppressWarnings("JBCT-RET-01") // Side-effect: send response + dispatch notification
    private static void handleRemoteRemoveResponse(DHTNetwork dhtNetwork,
                                                   AetherMaps aetherMaps,
                                                   DHTMessage.RemoveRequest request,
                                                   DHTMessage.RemoveResponse response) {
        dhtNetwork.send(request.sender(), response);
        if (response.found()) {
            aetherMaps.dispatchRemoteRemove(request.key());
        }
    }

    private static List<MessageRouter.Entry<?>> collectRouteEntries(KVStore<AetherKey, AetherValue> kvStore,
                                                                    NodeDeploymentManager nodeDeploymentManager,
                                                                    ClusterDeploymentManager clusterDeploymentManager,
                                                                    EndpointRegistry endpointRegistry,
                                                                    TopicSubscriptionRegistry topicSubscriptionRegistry,
                                                                    ScheduledTaskRegistry scheduledTaskRegistry,
                                                                    ScheduledTaskStateRegistry scheduledTaskStateRegistry,
                                                                    ScheduledTaskManager scheduledTaskManager,
                                                                    HttpRouteRegistry httpRouteRegistry,
                                                                    MetricsCollector metricsCollector,
                                                                    MetricsScheduler metricsScheduler,
                                                                    DeploymentMetricsCollector deploymentMetricsCollector,
                                                                    DeploymentMetricsScheduler deploymentMetricsScheduler,
                                                                    ControlLoop controlLoop,
                                                                    SliceInvoker sliceInvoker,
                                                                    InvocationHandler invocationHandler,
                                                                    AlertManager alertManager,
                                                                    ObservabilityDepthRegistry depthRegistry,
                                                                    LogLevelRegistry logLevelRegistry,
                                                                    Option<DynamicConfigManager> dynamicConfigManager,
                                                                    TTMManager ttmManager,
                                                                    RabiaMetricsCollector rabiaMetricsCollector,
                                                                    RollingUpdateManager rollingUpdateManager,
                                                                    CanaryDeploymentManager canaryDeploymentManager,
                                                                    BlueGreenDeploymentManager blueGreenDeploymentManager,
                                                                    AbTestManager abTestManager,
                                                                    RollbackManager rollbackManager,
                                                                    ArtifactMetricsCollector artifactMetricsCollector,
                                                                    DeploymentMap deploymentMap,
                                                                    ClusterEventAggregator eventAggregator,
                                                                    LeaderManager leaderManager,
                                                                    AppHttpServer appHttpServer,
                                                                    Option<LoadBalancerManager> loadBalancerManager,
                                                                    TopologyObserver topologyManager,
                                                                    ClusterTopologyManager clusterTopologyManager) {
        var entries = new ArrayList<MessageRouter.Entry<?>>();
        // KV-Store notification sub-router — type-safe key-based dispatch.
        // Replaces per-handler filterPut/filterRemove with key-type dispatch via KVNotificationRouter.
        // Registration order within each key type = dispatch order.
        var kvRouterBuilder = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                  .onPut(AetherKey.AppBlueprintKey.class,
                                                         clusterDeploymentManager::onAppBlueprintPut)
                                                  .onPut(AetherKey.SliceTargetKey.class,
                                                         clusterDeploymentManager::onSliceTargetPut)
                                                  .onPut(AetherKey.VersionRoutingKey.class,
                                                         clusterDeploymentManager::onVersionRoutingPut)
                                                  .onRemove(AetherKey.AppBlueprintKey.class,
                                                            clusterDeploymentManager::onAppBlueprintRemove)
                                                  .onRemove(AetherKey.SliceTargetKey.class,
                                                            clusterDeploymentManager::onSliceTargetRemove)
                                                  .onRemove(AetherKey.VersionRoutingKey.class,
                                                            clusterDeploymentManager::onVersionRoutingRemove)
                                                  .onPut(AetherKey.SliceTargetKey.class, controlLoop::onSliceTargetPut)
                                                  .onRemove(AetherKey.SliceTargetKey.class,
                                                            controlLoop::onSliceTargetRemove)
                                                  .onPut(AetherKey.AlertThresholdKey.class,
                                                         alertManager::onAlertThresholdPut)
                                                  .onRemove(AetherKey.AlertThresholdKey.class,
                                                            alertManager::onAlertThresholdRemove)
                                                  .onPut(AetherKey.ObservabilityDepthKey.class,
                                                         depthRegistry::onDepthPut)
                                                  .onRemove(AetherKey.ObservabilityDepthKey.class,
                                                            depthRegistry::onDepthRemove)
                                                  .onPut(AetherKey.LogLevelKey.class, logLevelRegistry::onLogLevelPut)
                                                  .onRemove(AetherKey.LogLevelKey.class,
                                                            logLevelRegistry::onLogLevelRemove)
                                                  .onPut(AetherKey.SliceTargetKey.class,
                                                         rollbackManager::onSliceTargetPut)
                                                  .onPut(AetherKey.PreviousVersionKey.class,
                                                         rollbackManager::onPreviousVersionPut)
                                                  .onPut(AetherKey.TopicSubscriptionKey.class,
                                                         topicSubscriptionRegistry::onSubscriptionPut)
                                                  .onRemove(AetherKey.TopicSubscriptionKey.class,
                                                            topicSubscriptionRegistry::onSubscriptionRemove)
                                                  .onPut(AetherKey.ScheduledTaskKey.class,
                                                         scheduledTaskRegistry::onScheduledTaskPut)
                                                  .onRemove(AetherKey.ScheduledTaskKey.class,
                                                            scheduledTaskRegistry::onScheduledTaskRemove)
                                                  .onPut(AetherKey.ScheduledTaskStateKey.class,
                                                         scheduledTaskStateRegistry::onStatePut)
                                                  .onRemove(AetherKey.ScheduledTaskStateKey.class,
                                                            scheduledTaskStateRegistry::onStateRemove)
                                                  .onPut(AetherKey.NodeLifecycleKey.class,
                                                         nodeDeploymentManager::onNodeLifecyclePut)
                                                  .onRemove(AetherKey.NodeLifecycleKey.class,
                                                            nodeDeploymentManager::onNodeLifecycleRemove)
                                                  .onPut(AetherKey.NodeLifecycleKey.class,
                                                         clusterDeploymentManager::onNodeLifecyclePut)
                                                  .onPut(AetherKey.NodeLifecycleKey.class,
                                                         put -> notifyCtmOnDuty(put, clusterTopologyManager))
                                                  .onRemove(AetherKey.NodeLifecycleKey.class,
                                                            remove -> clusterTopologyManager.observer()
                                                                                            .markDeparted(remove.cause()
                                                                                                                .key()
                                                                                                                .nodeId()))
                                                  .onPut(AetherKey.ActivationDirectiveKey.class,
                                                         clusterDeploymentManager::onActivationDirectivePut)
                                                  .onRemove(AetherKey.ActivationDirectiveKey.class,
                                                            clusterDeploymentManager::onActivationDirectiveRemove)
                                                  .onPut(AetherKey.GovernorAnnouncementKey.class,
                                                         clusterDeploymentManager::onGovernorAnnouncementPut)
                                                  .onRemove(AetherKey.GovernorAnnouncementKey.class,
                                                            clusterDeploymentManager::onGovernorAnnouncementRemove)
                                                  .onPut(AetherKey.SchemaVersionKey.class,
                                                         clusterDeploymentManager::onSchemaVersionPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         nodeDeploymentManager::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         clusterDeploymentManager::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         endpointRegistry::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         artifactMetricsCollector.deploymentTracker()::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         deploymentMap::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class, controlLoop::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         eventAggregator::onNodeArtifactPut)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            nodeDeploymentManager::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            clusterDeploymentManager::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            endpointRegistry::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            artifactMetricsCollector.deploymentTracker()::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            deploymentMap::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            controlLoop::onNodeArtifactRemove)
                                                  .onPut(AetherKey.NodeRoutesKey.class,
                                                         httpRouteRegistry::onNodeRoutesPut)
                                                  .onPut(AetherKey.NodeRoutesKey.class, appHttpServer::onNodeRoutesPut)
                                                  .onRemove(AetherKey.NodeRoutesKey.class,
                                                            httpRouteRegistry::onNodeRoutesRemove)
                                                  .onRemove(AetherKey.NodeRoutesKey.class,
                                                            appHttpServer::onNodeRoutesRemove);
        // Load balancer manager NodeRoutesKey events
        loadBalancerManager.onPresent(lbm -> kvRouterBuilder.onPut(AetherKey.NodeRoutesKey.class, lbm::onNodeRoutesPut)
                                                            .onRemove(AetherKey.NodeRoutesKey.class,
                                                                      lbm::onNodeRoutesRemove));
        // Dynamic config manager (optional)
        dynamicConfigManager.onPresent(dcm -> kvRouterBuilder.onPut(AetherKey.ConfigKey.class, dcm::onConfigPut)
                                                             .onRemove(AetherKey.ConfigKey.class, dcm::onConfigRemove)
                                                             .onPut(AetherKey.BlueprintResourcesKey.class,
                                                                    dcm::onBlueprintResourcesPut));
        // Load balancer manager HTTP route events now come from DHT subscription (wired above)
        entries.addAll(kvRouterBuilder.build()
                                      .asRouteEntries());
        // Quorum state notifications - these handlers activate/deactivate components.
        // NOTE: RabiaNode's handlers run first (consensus activates before LeaderManager emits LeaderChange).
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, nodeDeploymentManager::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, controlLoop::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, metricsScheduler::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class,
                                              deploymentMetricsScheduler::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, scheduledTaskManager::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, appHttpServer::onQuorumStateChange));
        // Leader change notifications - handlers may call cluster.apply(), which is safe because
        // consensus engine is already active by the time LeaderChange is emitted (see RabiaNode handler order).
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              clusterDeploymentManager::onLeaderChange));
        // ClusterTopologyManager leader change — activate/deactivate reconciliation state machine
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> handleCtmLeaderChange(change, clusterTopologyManager)));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, metricsScheduler::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, controlLoop::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> rabiaMetricsCollector.updateRole(change.localNodeIsLeader(),
                                                                                         change.leaderId()
                                                                                               .map(NodeId::id))));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, ttmManager::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              rollingUpdateManager::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              canaryDeploymentManager::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              blueGreenDeploymentManager::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, abTestManager::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, rollbackManager::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              scheduledTaskManager::onLeaderChange));
        // RollbackManager slice failure events (KV-Store notifications handled by sub-router above)
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              rollbackManager::onAllInstancesFailed));
        // Topology change notifications - must register for each subtype since router uses exact class matching
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class,
                                              clusterDeploymentManager::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              clusterDeploymentManager::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class,
                                              clusterDeploymentManager::onTopologyChange));
        // ClusterTopologyManager topology change — triggers node reconciliation state machine
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class,
                                              clusterTopologyManager::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              clusterTopologyManager::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class,
                                              clusterTopologyManager::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class,
                                              metricsScheduler::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              metricsScheduler::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class,
                                              metricsScheduler::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class, controlLoop::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              controlLoop::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class, controlLoop::onTopologyChange));
        // MetricsCollector topology change — remove dead nodes from metrics
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              metricsCollector::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class,
                                              metricsCollector::onTopologyChange));
        // Metrics messages
        entries.add(MessageRouter.Entry.route(MetricsMessage.MetricsPing.class, metricsCollector::onMetricsPing));
        entries.add(MessageRouter.Entry.route(MetricsMessage.MetricsPong.class, metricsCollector::onMetricsPong));
        // Deployment metrics messages
        entries.add(MessageRouter.Entry.route(DeploymentMetricsMessage.DeploymentMetricsPing.class,
                                              deploymentMetricsCollector::onDeploymentMetricsPing));
        entries.add(MessageRouter.Entry.route(DeploymentMetricsMessage.DeploymentMetricsPong.class,
                                              deploymentMetricsCollector::onDeploymentMetricsPong));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class,
                                              deploymentMetricsCollector::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              deploymentMetricsCollector::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class,
                                              deploymentMetricsCollector::onTopologyChange));
        // Deployment events (dispatched locally via MessageRouter)
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentStarted.class,
                                              deploymentMetricsCollector::onDeploymentStarted));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.StateTransition.class,
                                              deploymentMetricsCollector::onStateTransition));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentCompleted.class,
                                              deploymentMetricsCollector::onDeploymentCompleted));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class,
                                              deploymentMetricsCollector::onDeploymentFailed));
        // Deployment metrics scheduler leader/topology notifications
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              deploymentMetricsScheduler::onLeaderChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class,
                                              deploymentMetricsScheduler::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              deploymentMetricsScheduler::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class,
                                              deploymentMetricsScheduler::onTopologyChange));
        // AppHttpServer topology change notifications (for immediate retry on node departure)
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class, appHttpServer::onNodeRemoved));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class, appHttpServer::onNodeDown));
        // Fast-path route eviction — immediately remove dead node from local route cache
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              msg -> httpRouteRegistry.evictNode(msg.nodeId())));
        // Cluster event aggregator — fan-out handlers for structured event collection
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class, eventAggregator::onNodeAdded));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              eventAggregator::onNodeRemoved));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class, eventAggregator::onNodeDown));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, eventAggregator::onLeaderChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, eventAggregator::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class,
                                              rollingUpdateManager::onDeploymentFailed));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class,
                                              canaryDeploymentManager::onDeploymentFailed));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class,
                                              blueGreenDeploymentManager::onDeploymentFailed));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class, abTestManager::onDeploymentFailed));
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              eventAggregator::onSliceFailure));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaledUp.class, eventAggregator::onScaledUp));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaledDown.class, eventAggregator::onScaledDown));
        entries.add(MessageRouter.Entry.route(ClusterDeploymentManager.ReconciliationAdjustment.class,
                                              eventAggregator::onReconciliationAdjustment));
        // Community scaling messages from worker governors
        entries.add(MessageRouter.Entry.route(CommunityScalingRequest.class, controlLoop::onCommunityScalingRequest));
        entries.add(MessageRouter.Entry.route(CommunityMetricsSnapshot.class, controlLoop::onCommunityMetricsSnapshot));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionEstablished.class,
                                              eventAggregator::onConnectionEstablished));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionFailed.class,
                                              eventAggregator::onConnectionFailed));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionFailed.class,
                                              topologyManager::handleConnectionFailed));
        entries.add(MessageRouter.Entry.route(TopologyManagementMessage.RemoveNode.class,
                                              topologyManager::handleRemoveNodeMessage));
        // Operational audit events — routed to event aggregator for dashboard/API consumption
        entries.add(MessageRouter.Entry.route(OperationalEvent.AccessDenied.class, eventAggregator::onAccessDenied));
        entries.add(MessageRouter.Entry.route(OperationalEvent.NodeLifecycleChanged.class,
                                              eventAggregator::onNodeLifecycleChanged));
        entries.add(MessageRouter.Entry.route(OperationalEvent.ConfigChanged.class, eventAggregator::onConfigChanged));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BackupCreated.class, eventAggregator::onBackupCreated));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BackupRestored.class, eventAggregator::onBackupRestored));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BlueprintDeployed.class,
                                              eventAggregator::onBlueprintDeployed));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BlueprintDeleted.class,
                                              eventAggregator::onBlueprintDeleted));
        // Invocation messages
        entries.add(MessageRouter.Entry.route(InvocationMessage.InvokeRequest.class, invocationHandler::onInvokeRequest));
        entries.add(MessageRouter.Entry.route(InvocationMessage.InvokeResponse.class, sliceInvoker::onInvokeResponse));
        // HTTP forwarding messages for AppHttpServer
        entries.add(MessageRouter.Entry.route(org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest.class,
                                              appHttpServer::onHttpForwardRequest));
        entries.add(MessageRouter.Entry.route(org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse.class,
                                              appHttpServer::onHttpForwardResponse));
        // KVStore local operations
        entries.add(MessageRouter.Entry.route(KVStoreLocalIO.Request.Find.class, kvStore::find));
        // Leader election commit listener - notifies LeaderManager when leader is committed through consensus
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class,
                                              notification -> handleLeaderCommit(notification, leaderManager)));
        // Load balancer manager routes (KV routes handled by sub-router above)
        loadBalancerManager.onPresent(lbm -> {
                                          entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                                                lbm::onLeaderChange));
                                          entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class,
                                                                                lbm::onTopologyChange));
                                          entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                                                                lbm::onTopologyChange));
                                          entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class,
                                                                                lbm::onTopologyChange));
                                      });
        return entries;
    }

    /// Handle leader election commits from KV-Store.
    /// When a LeaderKey is committed, notify the LeaderManager.
    private static void handleLeaderCommit(KVStoreNotification.ValuePut<?, ?> notification,
                                           LeaderManager leaderManager) {
        if (notification.cause()
                        .key() instanceof LeaderKey) {
            var value = (LeaderValue) notification.cause()
                                                 .value();
            leaderManager.onLeaderCommitted(value.leader());
        }
    }

    /// Create SharedLibraryClassLoader with appropriate parent based on configuration.
    ///
    /// If frameworkJarsPath is configured, creates a FrameworkClassLoader with isolated
    /// framework classes. Otherwise, falls back to Application ClassLoader (no isolation).
    private static ObservabilityInterceptor createObservabilityInterceptor(AetherNodeConfig config,
                                                                           InvocationTraceStore traceStore,
                                                                           ObservabilityDepthRegistry depthRegistry) {
        var sampler = AdaptiveSampler.adaptiveSampler(config.observability()
                                                            .targetTracesPerSec());
        return ObservabilityInterceptor.observabilityInterceptor(sampler,
                                                                 traceStore,
                                                                 config.self()
                                                                       .id(),
                                                                 (artifact, method) -> depthRegistry.getConfig(artifact,
                                                                                                               method)
                                                                                                    .depthThreshold());
    }

    private static SharedLibraryClassLoader createSharedLibraryLoader(AetherNodeConfig config) {
        var log = LoggerFactory.getLogger(AetherNode.class);
        return config.sliceAction()
                     .frameworkJarsPath()
                     .fold(() -> {
                               log.debug("No framework JARs path configured, using Application ClassLoader as parent");
                               return new SharedLibraryClassLoader(AetherNode.class.getClassLoader());
                           },
                           // Framework path configured - try to create FrameworkClassLoader
        path -> FrameworkClassLoader.fromDirectory(path)
                                    .onFailure(cause -> log.warn("Failed to create FrameworkClassLoader from {}: {}. "
                                                                 + "Falling back to Application ClassLoader.",
                                                                 path,
                                                                 cause.message()))
                                    .map(loader -> {
                                             log.info("Using FrameworkClassLoader with {} JARs as parent",
                                                      loader.getLoadedJars()
                                                            .size());
                                             return new SharedLibraryClassLoader(loader);
                                         })
                                    .or(new SharedLibraryClassLoader(AetherNode.class.getClassLoader())));
    }

    record ResourceProviderSetup(ResourceProviderFacade facade,
                                 Option<DynamicConfigurationProvider> dynamicProvider,
                                 Option<SpiResourceProvider> spiProvider) {}

    /// Create ResourceProviderFacade from config.
    /// If ConfigurationProvider is configured, creates ConfigService and ResourceProvider
    /// with a DynamicConfigurationProvider overlay for runtime config updates.
    /// Otherwise, returns a no-op facade that fails with an informative message.
    private static ResourceProviderSetup createResourceProviderFacade(AetherNodeConfig config) {
        var log = LoggerFactory.getLogger(AetherNode.class);
        return config.configProvider()
                     .fold(() -> {
                               log.debug("No configuration provider configured, resource provisioning disabled");
                               return new ResourceProviderSetup(noOpResourceProviderFacade(),
                                                                Option.empty(),
                                                                Option.empty());
                           },
                           configProvider -> {
                               log.info("Creating ConfigService and ResourceProvider from configuration provider");
                               var resolvedProvider = config.environment()
                                                            .flatMap(EnvironmentIntegration::secrets)
                                                            .fold(() -> Result.success(configProvider),
                                                                  sp -> ConfigurationProvider.withSecretResolution(configProvider,
                                                                                                                   sp::resolveSecret));
                               return resolvedProvider.fold(cause -> {
                                                                log.error("Failed to resolve secrets in configuration: {}",
                                                                          cause.message());
                                                                return new ResourceProviderSetup(noOpResourceProviderFacade(),
                                                                                                 Option.empty(),
                                                                                                 Option.empty());
                                                            },
                                                            provider -> {
                                                                var dynamicProvider = DynamicConfigurationProvider.dynamicConfigurationProvider(provider);
                                                                var configService = ProviderBasedConfigService.providerBasedConfigService(dynamicProvider);
                                                                ConfigService.setInstance(configService);
                                                                var resourceProvider = SpiResourceProvider.spiResourceProvider();
                                                                ResourceProvider.setInstance(resourceProvider);
                                                                log.info("ConfigService and ResourceProvider initialized with dynamic overlay");
                                                                return new ResourceProviderSetup(new ResourceProviderFacade() {
            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                                                                                                     return resourceProvider.provide(resourceType,
                                                                                                                                     configSection);
                                                                                                 }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                                                                                                     return resourceProvider.provide(resourceType,
                                                                                                                                     configSection,
                                                                                                                                     context);
                                                                                                 }
        },
                                                                                                 Option.some(dynamicProvider),
                                                                                                 Option.some(resourceProvider));
                                                            });
                           });
    }

    private static ResourceProviderFacade noOpResourceProviderFacade() {
        return new ResourceProviderFacade() {
            private static final Cause NOT_CONFIGURED = Causes.cause("Resource provisioning not configured. Use AetherNodeConfig.withConfigProvider() to enable.");

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return NOT_CONFIGURED.promise();
            }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                return NOT_CONFIGURED.promise();
            }
        };
    }

    /// Create a composite repository that tries each repository in order until one succeeds.
    ///
    /// Note: For simplicity, currently uses the first repository only.
    /// Multi-repository fallback can be added if needed.
    private static Repository compositeRepository(List<Repository> repositories) {
        if (repositories.isEmpty()) {
            return artifact -> Causes.cause("No repositories configured")
                                     .promise();
        }
        // Use first repository (most configurations use a single repository)
        return repositories.getFirst();
    }

    /// Register runtime extensions into SpiResourceProvider for Publisher provisioning.
    private static void registerRuntimeExtensions(SpiResourceProvider spi,
                                                  TopicSubscriptionRegistry topicSubscriptionRegistry,
                                                  SliceInvoker sliceInvoker,
                                                  DHTClient cacheDhtClient) {
        spi.registerExtension(TopicSubscriptionRegistry.class, topicSubscriptionRegistry);
        spi.registerExtension(SliceInvoker.class, sliceInvoker);
        spi.registerExtension(DHTClient.class, cacheDhtClient);
    }
}
