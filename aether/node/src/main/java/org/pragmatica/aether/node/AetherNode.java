package org.pragmatica.aether.node;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.ClusterEventAggregatorConfig;
import org.pragmatica.aether.api.LogLevelRegistry;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.controller.RollbackManager;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.loadbalancer.LoadBalancerManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
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
import org.pragmatica.aether.api.ObservabilityDepthRegistry;
import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ObservabilityInterceptor;
import org.pragmatica.aether.invoke.InvocationMessage;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
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
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.ttm.AdaptiveDecisionTree;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.RollingUpdateManager;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage;
import org.pragmatica.cluster.metrics.MetricsMessage;
import org.pragmatica.cluster.node.rabia.NodeConfig;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTAntiEntropy;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Main entry point for an Aether cluster node.
/// Assembles all components: consensus, KV-store, slice management, deployment managers.
@SuppressWarnings("JBCT-RET-01")
public interface AetherNode {
    String VERSION = "0.18.0";
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

    /// Get the invocation metrics collector for method-level metrics.
    InvocationMetricsCollector invocationMetrics();

    /// Get the cluster controller for scaling decisions.
    ClusterController controller();

    /// Get the rolling update manager for managing version transitions.
    RollingUpdateManager rollingUpdateManager();

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

    /// Get the number of currently connected peer nodes in the cluster.
    /// This is a network-level count, not based on metrics exchange.
    int connectedNodeCount();

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
        var nodeConfig = NodeConfig.nodeConfig(config.protocol(), config.topology());
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
                                   List.of(networkMetricsHandler),
                                   true)
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
        // Create distributed DHT client with quorum-based reads/writes via ClusterNetwork
        var dhtClient = DistributedDHTClient.distributedDHTClient(dhtNode, clusterNode.network(), config.artifactRepo());
        // Create scoped DHT client for cache operations (lower replication, single-replica default)
        var cacheDhtClient = dhtClient.scoped(config.cache());
        var artifactStore = ArtifactStore.artifactStore(dhtClient);
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
        var dhtRebalancer = DHTRebalancer.dhtRebalancer(dhtNode, clusterNode.network(), config.artifactRepo());
        // Create DHTTopologyListener for ring updates on topology changes
        var dhtTopologyListener = DHTTopologyListener.dhtTopologyListener(dhtNode, dhtRebalancer);
        // Create DHT anti-entropy for replica synchronization
        var dhtAntiEntropy = DHTAntiEntropy.dhtAntiEntropy(dhtNode, clusterNode.network(), config.artifactRepo());
        record aetherNode(AetherNodeConfig config,
                          MessageRouter.DelegateRouter router,
                          KVStore<AetherKey, AetherValue> kvStore,
                          SliceRegistry sliceRegistry,
                          SliceStore sliceStore,
                          RabiaNode<KVCommand<AetherKey>> clusterNode,
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
                          EventLoopMetricsCollector eventLoopMetricsCollector,
                          Option<ManagementServer> managementServer,
                          long startTimeMs) implements AetherNode {
            private static final Logger log = LoggerFactory.getLogger(aetherNode.class);

            @Override
            public NodeId self() {
                return config.self();
            }

            @Override
            public Promise<Unit> start() {
                log.info("Starting Aether node {}", self());
                // Start comprehensive snapshot collection (feeds TTM pipeline)
                snapshotCollector.start();
                SliceRuntime.setSliceInvoker(sliceInvoker);
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
                // 4. Stop servers and network
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
                                                 // TODO: Leader election timing issue workaround.
                //
                // The problem: QuorumStateNotification.ESTABLISHED fires when quorum is detected,
                // but consensus may still be syncing (nodes exchanging phase/sequence info).
                // If LeaderManager submits a proposal immediately on ESTABLISHED, it can fail
                // because other nodes aren't ready yet, causing retries and phase divergence.
                //
                // The fix: LeaderManager no longer submits proposals automatically in consensus mode.
                // Instead, we trigger election manually here after clusterNode.start() completes.
                // At this point, consensus is fully synchronized and ready for proposals.
                //
                // Future improvement: Add ConsensusReadyNotification that fires after sync completes,
                // separate from QuorumStateNotification which only indicates network connectivity.
                // LeaderManager would then listen for ConsensusReadyNotification instead of ESTABLISHED.
                clusterNode.leaderManager()
                           .triggerElection();
                                             })
                                  .onSuccess(_ -> printStartupBanner())
                                  .onFailure(cause -> log.error("Cluster formation failed: {}",
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
                return clusterNode.apply(commands);
            }

            @Override
            public int connectedNodeCount() {
                return clusterNode.network()
                                  .connectedNodeCount();
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
            public void route(Message message) {
                router.route(message);
            }
        }
        // Create HTTP route publisher for slice route publication (needed by InvocationHandler)
        var httpRoutePublisher = HttpRoutePublisher.httpRoutePublisher(config.self(), clusterNode, kvStore);
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
        var clusterDeploymentManager = ClusterDeploymentManager.clusterDeploymentManager(config.self(),
                                                                                         clusterNode,
                                                                                         kvStore,
                                                                                         delegateRouter,
                                                                                         initialTopology,
                                                                                         clusterNode.topologyManager(),
                                                                                         config.environment()
                                                                                               .flatMap(EnvironmentIntegration::compute),
                                                                                         config.autoHeal());
        // Create load balancer manager when provider is available
        var loadBalancerManager = config.environment()
                                        .flatMap(EnvironmentIntegration::loadBalancer)
                                        .map(provider -> LoadBalancerManager.loadBalancerManager(config.self(),
                                                                                                 kvStore,
                                                                                                 clusterNode.topologyManager(),
                                                                                                 provider,
                                                                                                 config.appHttp()
                                                                                                       .port()));
        // Create endpoint registry
        var endpointRegistry = EndpointRegistry.endpointRegistry();
        // Create topic subscription registry for pub/sub messaging
        var topicSubscriptionRegistry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
        // Create scheduled task registry and manager for periodic slice method invocation
        var scheduledTaskRegistry = ScheduledTaskRegistry.scheduledTaskRegistry();
        // Create HTTP route registry for application HTTP routing
        var httpRouteRegistry = HttpRouteRegistry.httpRouteRegistry();
        // Create metrics components
        var metricsCollector = MetricsCollector.metricsCollector(config.self(), clusterNode.network());
        // Wire invocation metrics and management port into MetricsCollector for cluster-wide gossip
        metricsCollector.setInvocationMetricsProvider(invocationMetrics);
        metricsCollector.recordCustom("mgmt.port", config.managementPort());
        var metricsScheduler = MetricsScheduler.metricsScheduler(config.self(), clusterNode.network(), metricsCollector);
        // Create base decision tree controller
        var controller = DecisionTreeController.decisionTreeController();
        // Create blueprint service using composite repository from configuration
        var blueprintService = BlueprintService.blueprintService(clusterNode, kvStore, compositeRepository(repositories));
        // Create Maven protocol handler from artifact store (DHT created in createNode)
        var mavenProtocolHandler = MavenProtocolHandler.mavenProtocolHandler(artifactStore);
        // Create rolling update manager
        var rollingUpdateManager = RollingUpdateManager.rollingUpdateManager(clusterNode, kvStore, invocationMetrics);
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
        var eventLoopMetricsCollector = EventLoopMetricsCollector.eventLoopMetricsCollector();
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
                                                  config.controllerConfig());
        // Create rollback manager for automatic version rollback on persistent failures
        var rollbackManager = config.rollback()
                                    .enabled()
                              ? RollbackManager.rollbackManager(config.self(), config.rollback(), clusterNode, kvStore)
                              : RollbackManager.disabled();
        // Create slice invoker (needs rollingUpdateManager for weighted routing during rolling updates)
        var sliceInvoker = SliceInvoker.sliceInvoker(config.self(),
                                                     clusterNode.network(),
                                                     endpointRegistry,
                                                     invocationHandler,
                                                     serializer,
                                                     deserializer,
                                                     rollingUpdateManager,
                                                     observabilityInterceptor);
        // Wire the deferred invoker facade to the actual SliceInvoker
        deferredInvoker.setDelegate(sliceInvoker);
        // Create scheduled task manager (needs sliceInvoker for method execution)
        var scheduledTaskManager = ScheduledTaskManager.scheduledTaskManager(scheduledTaskRegistry,
                                                                             sliceInvoker,
                                                                             config.self());
        // Register runtime extensions for Publisher provisioning via SPI
        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> registerRuntimeExtensions(spi,
                                                                         topicSubscriptionRegistry,
                                                                         sliceInvoker,
                                                                         cacheDhtClient));
        // Create node deployment manager (now created after sliceInvoker for HTTP route publishing)
        var nodeDeploymentManager = NodeDeploymentManager.nodeDeploymentManager(config.self(),
                                                                                delegateRouter,
                                                                                sliceStore,
                                                                                clusterNode,
                                                                                kvStore,
                                                                                invocationHandler,
                                                                                config.sliceAction(),
                                                                                nodeCodec,
                                                                                Option.some(httpRoutePublisher),
                                                                                Option.some(sliceInvoker));
        // Create application HTTP server for slice-provided routes (with HTTP forwarding support)
        var appHttpServer = AppHttpServer.appHttpServer(config.appHttp(),
                                                        config.self(),
                                                        httpRouteRegistry,
                                                        Option.some(httpRoutePublisher),
                                                        Option.some(clusterNode.network()),
                                                        Option.some(serializer),
                                                        Option.some(deserializer),
                                                        config.tls());
        // Collect all route entries from RabiaNode and AetherNode components
        var aetherEntries = collectRouteEntries(kvStore,
                                                nodeDeploymentManager,
                                                clusterDeploymentManager,
                                                endpointRegistry,
                                                topicSubscriptionRegistry,
                                                scheduledTaskRegistry,
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
                                                rollbackManager,
                                                artifactMetricsCollector,
                                                deploymentMap,
                                                eventAggregator,
                                                clusterNode.leaderManager(),
                                                appHttpServer,
                                                loadBalancerManager);
        // DHT message routes for distributed operations
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.GetRequest.class,
                                                    request -> dhtNode.handleGetRequest(request,
                                                                                        response -> clusterNode.network()
                                                                                                               .send(request.sender(),
                                                                                                                     response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.PutRequest.class,
                                                    request -> dhtNode.handlePutRequest(request,
                                                                                        response -> clusterNode.network()
                                                                                                               .send(request.sender(),
                                                                                                                     response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.RemoveRequest.class,
                                                    request -> dhtNode.handleRemoveRequest(request,
                                                                                           response -> clusterNode.network()
                                                                                                                  .send(request.sender(),
                                                                                                                        response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.ExistsRequest.class,
                                                    request -> dhtNode.handleExistsRequest(request,
                                                                                           response -> clusterNode.network()
                                                                                                                  .send(request.sender(),
                                                                                                                        response))));
        // DHT response routes — complete the request/response cycle for DistributedDHTClient
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.GetResponse.class, dhtClient::onGetResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.PutResponse.class, dhtClient::onPutResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.RemoveResponse.class, dhtClient::onRemoveResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.ExistsResponse.class, dhtClient::onExistsResponse));
        // DHT digest routes — anti-entropy replica synchronization
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.DigestRequest.class,
                                                    request -> dhtNode.handleDigestRequest(request,
                                                                                           response -> clusterNode.network()
                                                                                                                  .send(request.sender(),
                                                                                                                        response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.DigestResponse.class, dhtAntiEntropy::onDigestResponse));
        // DHT migration routes — data transfer for partition repair
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataRequest.class,
                                                    request -> dhtNode.handleMigrationDataRequest(request,
                                                                                                  response -> clusterNode.network()
                                                                                                                         .send(request.sender(),
                                                                                                                               response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataResponse.class,
                                                    dhtAntiEntropy::onMigrationDataResponse));
        // DHT topology listener — update consistent hash ring on node add/remove
        aetherEntries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class,
                                                    dhtTopologyListener::onNodeAdded));
        aetherEntries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                                    dhtTopologyListener::onNodeRemoved));
        var allEntries = new ArrayList<>(clusterNode.routeEntries());
        allEntries.addAll(aetherEntries);
        // Create the node first (without management server reference)
        var startTimeMs = System.currentTimeMillis();
        var node = new aetherNode(config,
                                  delegateRouter,
                                  kvStore,
                                  sliceRegistry,
                                  sliceStore,
                                  clusterNode,
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
                                  eventLoopMetricsCollector,
                                  Option.empty(),
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
                                                                                                           .apiKeyValues())
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
                                                                                              config.tls(),
                                                                                              mgmtSecurityValidator,
                                                                                              mgmtSecurityEnabled);
                                     return new aetherNode(config,
                                                           delegateRouter,
                                                           kvStore,
                                                           sliceRegistry,
                                                           sliceStore,
                                                           clusterNode,
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
                                                           eventLoopMetricsCollector,
                                                           Option.some(managementServer),
                                                           startTimeMs);
                                 }
                                 return node;
                             });
    }

    private static List<MessageRouter.Entry<?>> collectRouteEntries(KVStore<AetherKey, AetherValue> kvStore,
                                                                    NodeDeploymentManager nodeDeploymentManager,
                                                                    ClusterDeploymentManager clusterDeploymentManager,
                                                                    EndpointRegistry endpointRegistry,
                                                                    TopicSubscriptionRegistry topicSubscriptionRegistry,
                                                                    ScheduledTaskRegistry scheduledTaskRegistry,
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
                                                                    RollbackManager rollbackManager,
                                                                    ArtifactMetricsCollector artifactMetricsCollector,
                                                                    DeploymentMap deploymentMap,
                                                                    ClusterEventAggregator eventAggregator,
                                                                    LeaderManager leaderManager,
                                                                    AppHttpServer appHttpServer,
                                                                    Option<LoadBalancerManager> loadBalancerManager) {
        var entries = new ArrayList<MessageRouter.Entry<?>>();
        // KV-Store notification sub-router — type-safe key-based dispatch.
        // Replaces per-handler filterPut/filterRemove with key-type dispatch via KVNotificationRouter.
        // Registration order within each key type = dispatch order.
        var kvRouterBuilder = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                  .onPut(AetherKey.EndpointKey.class, endpointRegistry::onEndpointPut)
                                                  .onRemove(AetherKey.EndpointKey.class,
                                                            endpointRegistry::onEndpointRemove)
                                                  .onPut(AetherKey.SliceNodeKey.class,
                                                         nodeDeploymentManager::onSliceNodePut)
                                                  .onRemove(AetherKey.SliceNodeKey.class,
                                                            nodeDeploymentManager::onSliceNodeRemove)
                                                  .onPut(AetherKey.AppBlueprintKey.class,
                                                         clusterDeploymentManager::onAppBlueprintPut)
                                                  .onPut(AetherKey.SliceTargetKey.class,
                                                         clusterDeploymentManager::onSliceTargetPut)
                                                  .onPut(AetherKey.SliceNodeKey.class,
                                                         clusterDeploymentManager::onSliceNodePut)
                                                  .onPut(AetherKey.VersionRoutingKey.class,
                                                         clusterDeploymentManager::onVersionRoutingPut)
                                                  .onRemove(AetherKey.AppBlueprintKey.class,
                                                            clusterDeploymentManager::onAppBlueprintRemove)
                                                  .onRemove(AetherKey.SliceTargetKey.class,
                                                            clusterDeploymentManager::onSliceTargetRemove)
                                                  .onRemove(AetherKey.SliceNodeKey.class,
                                                            clusterDeploymentManager::onSliceNodeRemove)
                                                  .onRemove(AetherKey.VersionRoutingKey.class,
                                                            clusterDeploymentManager::onVersionRoutingRemove)
                                                  .onPut(AetherKey.HttpRouteKey.class, httpRouteRegistry::onRoutePut)
                                                  .onRemove(AetherKey.HttpRouteKey.class,
                                                            httpRouteRegistry::onRouteRemove)
                                                  .onPut(AetherKey.SliceNodeKey.class,
                                                         artifactMetricsCollector.deploymentTracker()::onSliceNodePut)
                                                  .onRemove(AetherKey.SliceNodeKey.class,
                                                            artifactMetricsCollector.deploymentTracker()::onSliceNodeRemove)
                                                  .onPut(AetherKey.SliceNodeKey.class, deploymentMap::onSliceNodePut)
                                                  .onRemove(AetherKey.SliceNodeKey.class,
                                                            deploymentMap::onSliceNodeRemove)
                                                  .onPut(AetherKey.SliceTargetKey.class, controlLoop::onSliceTargetPut)
                                                  .onPut(AetherKey.SliceNodeKey.class, controlLoop::onSliceNodePut)
                                                  .onRemove(AetherKey.SliceTargetKey.class,
                                                            controlLoop::onSliceTargetRemove)
                                                  .onRemove(AetherKey.SliceNodeKey.class, controlLoop::onSliceNodeRemove)
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
                                                  .onPut(AetherKey.HttpRouteKey.class, appHttpServer::onRoutePut)
                                                  .onRemove(AetherKey.HttpRouteKey.class, appHttpServer::onRouteRemove)
                                                  .onPut(AetherKey.TopicSubscriptionKey.class,
                                                         topicSubscriptionRegistry::onSubscriptionPut)
                                                  .onRemove(AetherKey.TopicSubscriptionKey.class,
                                                            topicSubscriptionRegistry::onSubscriptionRemove)
                                                  .onPut(AetherKey.ScheduledTaskKey.class,
                                                         scheduledTaskRegistry::onScheduledTaskPut)
                                                  .onRemove(AetherKey.ScheduledTaskKey.class,
                                                            scheduledTaskRegistry::onScheduledTaskRemove)
                                                  .onPut(AetherKey.NodeLifecycleKey.class,
                                                         nodeDeploymentManager::onNodeLifecyclePut)
                                                  .onPut(AetherKey.NodeLifecycleKey.class,
                                                         clusterDeploymentManager::onNodeLifecyclePut);
        // Dynamic config manager (optional)
        dynamicConfigManager.onPresent(dcm -> kvRouterBuilder.onPut(AetherKey.ConfigKey.class, dcm::onConfigPut)
                                                             .onRemove(AetherKey.ConfigKey.class, dcm::onConfigRemove));
        // Load balancer manager KV routes (optional)
        loadBalancerManager.onPresent(lbm -> kvRouterBuilder.onPut(AetherKey.HttpRouteKey.class, lbm::onRoutePut)
                                                            .onRemove(AetherKey.HttpRouteKey.class, lbm::onRouteRemove));
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
        // Leader change notifications - handlers may call cluster.apply(), which is safe because
        // consensus engine is already active by the time LeaderChange is emitted (see RabiaNode handler order).
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              clusterDeploymentManager::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, metricsScheduler::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, controlLoop::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> rabiaMetricsCollector.updateRole(change.localNodeIsLeader(),
                                                                                         change.leaderId()
                                                                                               .map(NodeId::id))));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, ttmManager::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              rollingUpdateManager::onLeaderChange));
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
        // Cluster event aggregator — fan-out handlers for structured event collection
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeAdded.class, eventAggregator::onNodeAdded));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeRemoved.class,
                                              eventAggregator::onNodeRemoved));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.NodeDown.class, eventAggregator::onNodeDown));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, eventAggregator::onLeaderChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, eventAggregator::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentStarted.class,
                                              eventAggregator::onDeploymentStarted));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentCompleted.class,
                                              eventAggregator::onDeploymentCompleted));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class,
                                              eventAggregator::onDeploymentFailed));
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              eventAggregator::onSliceFailure));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionEstablished.class,
                                              eventAggregator::onConnectionEstablished));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionFailed.class,
                                              eventAggregator::onConnectionFailed));
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
    @SuppressWarnings("unchecked")
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
