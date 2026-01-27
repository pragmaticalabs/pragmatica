package org.pragmatica.aether.node;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.controller.RollbackManager;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.BlueprintServiceImpl;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.infra.InfraStore;
import org.pragmatica.aether.infra.InfraStoreImpl;
import org.pragmatica.aether.infra.artifact.ArtifactStore;
import org.pragmatica.aether.infra.artifact.MavenProtocolHandler;
import org.pragmatica.aether.repository.RepositoryFactory;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.aether.metrics.MetricsScheduler;
import org.pragmatica.aether.metrics.MinuteAggregator;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetricsCollector;
import org.pragmatica.aether.metrics.gc.GCMetricsCollector;
import org.pragmatica.aether.metrics.consensus.RabiaMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsScheduler;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.network.NetworkMetricsHandler;
import org.pragmatica.aether.ttm.AdaptiveDecisionTree;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.ttm.TTMState;
import org.pragmatica.aether.update.RollingUpdateManager;
import org.pragmatica.aether.update.RollingUpdateManagerImpl;
import org.pragmatica.aether.slice.DeferredSliceInvokerFacade;
import org.pragmatica.aether.slice.FrameworkClassLoader;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceRuntime;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage;
import org.pragmatica.cluster.metrics.MetricsMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.rabia.NodeConfig;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreLocalIO;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.LocalDHTClient;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.serialization.fury.FuryDeserializer.furyDeserializer;
import static org.pragmatica.serialization.fury.FurySerializer.furySerializer;

/**
 * Main entry point for an Aether cluster node.
 * Assembles all components: consensus, KV-store, slice management, deployment managers.
 */
public interface AetherNode {
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

    /**
     * Get the artifact store for artifact storage operations.
     */
    ArtifactStore artifactStore();

    /**
     * Get the invocation metrics collector for method-level metrics.
     */
    InvocationMetricsCollector invocationMetrics();

    /**
     * Get the cluster controller for scaling decisions.
     */
    DecisionTreeController controller();

    /**
     * Get the rolling update manager for managing version transitions.
     */
    RollingUpdateManager rollingUpdateManager();

    /**
     * Get the endpoint registry for service discovery.
     */
    EndpointRegistry endpointRegistry();

    /**
     * Get the alert manager for threshold management.
     */
    AlertManager alertManager();

    /**
     * Get the application HTTP server for slice routes.
     */
    AppHttpServer appHttpServer();

    /**
     * Get the HTTP route registry for route lookup.
     */
    HttpRouteRegistry httpRouteRegistry();

    /**
     * Get the TTM manager for predictive scaling.
     */
    TTMManager ttmManager();

    /**
     * Get the rollback manager for automatic version rollback.
     */
    RollbackManager rollbackManager();

    /**
     * Get the comprehensive snapshot collector for detailed metrics.
     */
    ComprehensiveSnapshotCollector snapshotCollector();

    /**
     * Get the artifact metrics collector for storage and deployment metrics.
     */
    ArtifactMetricsCollector artifactMetricsCollector();

    /**
     * Get the number of currently connected peer nodes in the cluster.
     * This is a network-level count, not based on metrics exchange.
     */
    int connectedNodeCount();

    /**
     * Check if this node is the current leader.
     */
    boolean isLeader();

    /**
     * Check if this node is ready for operations (consensus active).
     */
    boolean isReady();

    /**
     * Get the current leader node ID.
     */
    Option<NodeId> leader();

    /**
     * Apply commands to the cluster via consensus.
     */
    <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands);

    /**
     * Get the management server port for this node.
     * Returns 0 if management server is disabled.
     */
    int managementPort();

    /**
     * Get the node uptime in seconds since start.
     */
    long uptimeSeconds();

    /**
     * Get the initial topology node IDs from configuration.
     * This includes all core nodes that were configured at startup.
     */
    List<NodeId> initialTopology();

    /**
     * Route a message to registered handlers via the internal MessageRouter.
     */
    void route(Message message);

    static Result<AetherNode> aetherNode(AetherNodeConfig config) {
        var delegateRouter = MessageRouter.DelegateRouter.delegate();
        var serializer = furySerializer(AetherCustomClasses::configure);
        var deserializer = furyDeserializer(AetherCustomClasses::configure);
        return aetherNode(config, delegateRouter, serializer, deserializer);
    }

    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         Serializer serializer,
                                         Deserializer deserializer) {
        return config.validate()
                     .flatMap(_ -> createNode(config, delegateRouter, serializer, deserializer));
    }

    private static Result<AetherNode> createNode(AetherNodeConfig config,
                                                 MessageRouter.DelegateRouter delegateRouter,
                                                 Serializer serializer,
                                                 Deserializer deserializer) {
        // Create KVStore (state machine for consensus)
        var kvStore = new KVStore<AetherKey, AetherValue>(delegateRouter, serializer, deserializer);
        // Create DHT and artifact store (needed for BuiltinRepository)
        var dhtStorage = MemoryStorageEngine.memoryStorageEngine();
        var dhtRing = ConsistentHashRing.<String>consistentHashRing();
        dhtRing.addNode(config.self()
                              .id());
        var dhtNode = DHTNode.dhtNode(config.self()
                                            .id(),
                                      dhtStorage,
                                      dhtRing,
                                      config.artifactRepo());
        var dhtClient = LocalDHTClient.localDHTClient(dhtNode);
        var artifactStore = ArtifactStore.artifactStore(dhtClient);
        // Create repositories from SliceConfig using RepositoryFactory
        var repositoryFactory = RepositoryFactory.repositoryFactory(artifactStore);
        var repositories = repositoryFactory.createAll(config.sliceConfig());
        // Create slice management components
        var sliceRegistry = SliceRegistry.sliceRegistry();
        var sharedLibraryLoader = createSharedLibraryLoader(config);
        var deferredInvoker = DeferredSliceInvokerFacade.deferredSliceInvokerFacade();
        var sliceStore = SliceStore.sliceStore(sliceRegistry,
                                               repositories,
                                               sharedLibraryLoader,
                                               deferredInvoker,
                                               config.sliceAction());
        // Create Rabia cluster node with metrics
        var nodeConfig = NodeConfig.nodeConfig(config.protocol(), config.topology());
        var rabiaMetricsCollector = RabiaMetricsCollector.rabiaMetricsCollector();
        var networkMetricsHandler = NetworkMetricsHandler.networkMetricsHandler();
        // Assemble all components and collect routes
        return RabiaNode.rabiaNode(nodeConfig,
                                   delegateRouter,
                                   kvStore,
                                   serializer,
                                   deserializer,
                                   rabiaMetricsCollector,
                                   List.of(networkMetricsHandler))
                        .flatMap(clusterNode -> assembleNode(config,
                                                             delegateRouter,
                                                             kvStore,
                                                             sliceRegistry,
                                                             sliceStore,
                                                             deferredInvoker,
                                                             clusterNode,
                                                             rabiaMetricsCollector,
                                                             networkMetricsHandler,
                                                             serializer,
                                                             deserializer,
                                                             artifactStore,
                                                             repositories));
    }

    private static Result<AetherNode> assembleNode(AetherNodeConfig config,
                                                   MessageRouter.DelegateRouter delegateRouter,
                                                   KVStore<AetherKey, AetherValue> kvStore,
                                                   SliceRegistry sliceRegistry,
                                                   SliceStore sliceStore,
                                                   DeferredSliceInvokerFacade deferredInvoker,
                                                   RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                   RabiaMetricsCollector rabiaMetricsCollector,
                                                   NetworkMetricsHandler networkMetricsHandler,
                                                   Serializer serializer,
                                                   Deserializer deserializer,
                                                   ArtifactStore artifactStore,
                                                   List<Repository> repositories) {
        record aetherNodeImpl(AetherNodeConfig config,
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
                              InfraStoreImpl infraStore,
                              InvocationMetricsCollector invocationMetrics,
                              DecisionTreeController controller,
                              RollingUpdateManager rollingUpdateManager,
                              AlertManager alertManager,
                              AppHttpServer appHttpServer,
                              TTMManager ttmManager,
                              RollbackManager rollbackManager,
                              ComprehensiveSnapshotCollector snapshotCollector,
                              ArtifactMetricsCollector artifactMetricsCollector,
                              EventLoopMetricsCollector eventLoopMetricsCollector,
                              Option<ManagementServer> managementServer,
                              long startTimeMs) implements AetherNode {
            private static final Logger log = LoggerFactory.getLogger(aetherNodeImpl.class);

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
                InfraStore.setInstance(infraStore);
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
                router.route(QuorumStateNotification.DISAPPEARED);
                // 2. Stop message delivery (no new messages will be routed)
                router.quiesce();
                // 3. Stop components (they've already stopped their activities via quorum notification)
                controlLoop.stop();
                metricsScheduler.stop();
                deploymentMetricsScheduler.stop();
                ttmManager.stop();
                snapshotCollector.stop();
                SliceRuntime.clear();
                InfraStore.clear();
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
                                             })
                                  .onFailure(cause -> log.error("Cluster formation failed: {}",
                                                                cause.message()));
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
                             .map(org.pragmatica.consensus.net.NodeInfo::id)
                             .toList();
            }

            @Override
            public void route(Message message) {
                router.route(message);
            }
        }
        // Create invocation handler BEFORE deployment manager (needed for slice registration)
        var invocationHandler = InvocationHandler.invocationHandler(config.self(), clusterNode.network());
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
                                    .map(org.pragmatica.consensus.net.NodeInfo::id)
                                    .toList();
        var clusterDeploymentManager = ClusterDeploymentManager.clusterDeploymentManager(config.self(),
                                                                                         clusterNode,
                                                                                         kvStore,
                                                                                         delegateRouter,
                                                                                         initialTopology);
        // Create endpoint registry
        var endpointRegistry = EndpointRegistry.endpointRegistry();
        // Create HTTP route registry for application HTTP routing
        var httpRouteRegistry = HttpRouteRegistry.httpRouteRegistry();
        // Create HTTP route publisher for slice route publication
        var httpRoutePublisher = HttpRoutePublisher.httpRoutePublisher(clusterNode);
        // Create metrics components
        var metricsCollector = MetricsCollector.metricsCollector(config.self(), clusterNode.network());
        var metricsScheduler = MetricsScheduler.metricsScheduler(config.self(), clusterNode.network(), metricsCollector);
        // Create base decision tree controller
        var controller = DecisionTreeController.decisionTreeController();
        // Create blueprint service using composite repository from configuration
        var blueprintService = BlueprintServiceImpl.blueprintService(clusterNode,
                                                                     kvStore,
                                                                     compositeRepository(repositories));
        // Create Maven protocol handler from artifact store (DHT created in createNode)
        var mavenProtocolHandler = MavenProtocolHandler.mavenProtocolHandler(artifactStore);
        // Create invocation metrics collector
        var invocationMetrics = InvocationMetricsCollector.invocationMetricsCollector();
        // Create rolling update manager
        var rollingUpdateManager = RollingUpdateManagerImpl.rollingUpdateManager(clusterNode, kvStore, invocationMetrics);
        // Create alert manager with KV-Store persistence
        var alertManager = AlertManager.alertManager(clusterNode, kvStore);
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
        // Create infrastructure store for infra service instance sharing
        var infraStore = InfraStoreImpl.infraStoreImpl();
        // Create TTM manager (returns no-op if disabled in config)
        var ttmManager = TTMManager.ttmManager(config.ttm(),
                                               minuteAggregator,
                                               controller::configuration)
                                   .or(TTMManager.noOp(config.ttm()));
        // Create control loop with adaptive controller when TTM is actually enabled and functional
        ClusterController effectiveController = ttmManager.isEnabled()
                                                ? AdaptiveDecisionTree.adaptiveDecisionTree(controller, ttmManager)
                                                : controller;
        var controlLoop = ControlLoop.controlLoop(config.self(), effectiveController, metricsCollector, clusterNode);
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
                                                     rollingUpdateManager);
        // Wire the deferred invoker facade to the actual SliceInvoker
        deferredInvoker.setDelegate(sliceInvoker);
        // Create node deployment manager (now created after sliceInvoker for HTTP route publishing)
        var nodeDeploymentManager = NodeDeploymentManager.nodeDeploymentManager(config.self(),
                                                                                delegateRouter,
                                                                                sliceStore,
                                                                                clusterNode,
                                                                                kvStore,
                                                                                invocationHandler,
                                                                                config.sliceAction(),
                                                                                Option.some(httpRoutePublisher),
                                                                                Option.some(sliceInvoker));
        // Create application HTTP server for slice-provided routes
        var appHttpServer = AppHttpServer.appHttpServer(config.appHttp(),
                                                        httpRouteRegistry,
                                                        Option.some(sliceInvoker),
                                                        Option.some(httpRoutePublisher),
                                                        config.tls());
        // Collect all route entries from RabiaNode and AetherNode components
        var aetherEntries = collectRouteEntries(kvStore,
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
                                                alertManager,
                                                ttmManager,
                                                rabiaMetricsCollector,
                                                rollingUpdateManager,
                                                rollbackManager,
                                                artifactMetricsCollector);
        var allEntries = new ArrayList<>(clusterNode.routeEntries());
        allEntries.addAll(aetherEntries);
        // Create the node first (without management server reference)
        var startTimeMs = System.currentTimeMillis();
        var node = new aetherNodeImpl(config,
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
                                      infraStore,
                                      invocationMetrics,
                                      controller,
                                      rollingUpdateManager,
                                      alertManager,
                                      appHttpServer,
                                      ttmManager,
                                      rollbackManager,
                                      snapshotCollector,
                                      artifactMetricsCollector,
                                      eventLoopMetricsCollector,
                                      Option.empty(),
                                      startTimeMs);
        // Build and wire ImmutableRouter, then create final node
        return RabiaNode.buildAndWireRouter(delegateRouter, allEntries)
                        .map(_ -> {
                                 // Create management server if enabled
        if (config.managementPort() > 0) {
                                     var managementServer = ManagementServer.managementServer(config.managementPort(),
                                                                                              () -> node,
                                                                                              alertManager,
                                                                                              config.tls());
                                     return new aetherNodeImpl(config,
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
                                                               infraStore,
                                                               invocationMetrics,
                                                               controller,
                                                               rollingUpdateManager,
                                                               alertManager,
                                                               appHttpServer,
                                                               ttmManager,
                                                               rollbackManager,
                                                               snapshotCollector,
                                                               artifactMetricsCollector,
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
                                                                    HttpRouteRegistry httpRouteRegistry,
                                                                    MetricsCollector metricsCollector,
                                                                    MetricsScheduler metricsScheduler,
                                                                    DeploymentMetricsCollector deploymentMetricsCollector,
                                                                    DeploymentMetricsScheduler deploymentMetricsScheduler,
                                                                    ControlLoop controlLoop,
                                                                    SliceInvoker sliceInvoker,
                                                                    InvocationHandler invocationHandler,
                                                                    AlertManager alertManager,
                                                                    TTMManager ttmManager,
                                                                    RabiaMetricsCollector rabiaMetricsCollector,
                                                                    RollingUpdateManagerImpl rollingUpdateManager,
                                                                    RollbackManager rollbackManager,
                                                                    ArtifactMetricsCollector artifactMetricsCollector) {
        var entries = new ArrayList<MessageRouter.Entry<?>>();
        // KVStore notifications to deployment managers
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, nodeDeploymentManager::onValuePut));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, clusterDeploymentManager::onValuePut));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, endpointRegistry::onValuePut));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class,
                                              nodeDeploymentManager::onValueRemove));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class,
                                              clusterDeploymentManager::onValueRemove));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class, endpointRegistry::onValueRemove));
        // HTTP route registry for application HTTP routing
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, httpRouteRegistry::onValuePut));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class, httpRouteRegistry::onValueRemove));
        // Artifact metrics tracking via KV-Store
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, artifactMetricsCollector::onValuePut));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class,
                                              artifactMetricsCollector::onValueRemove));
        // ControlLoop blueprint sync via KV-Store
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, controlLoop::onValuePut));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class, controlLoop::onValueRemove));
        // Alert threshold sync via KV-Store
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class,
                                              notification -> alertManager.onKvStoreUpdate((AetherKey) notification.cause()
                                                                                                                  .key(),
                                                                                           (AetherValue) notification.cause()
                                                                                                                    .value())));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class,
                                              notification -> alertManager.onKvStoreRemove((AetherKey) notification.cause()
                                                                                                                  .key())));
        // Quorum state notifications - these handlers activate/deactivate components.
        // NOTE: RabiaNode's handlers run first (consensus activates before LeaderManager emits LeaderChange).
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, nodeDeploymentManager::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, controlLoop::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, metricsScheduler::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class,
                                              deploymentMetricsScheduler::onQuorumStateChange));
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
        // RollbackManager KV-Store notifications and slice failure events
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, rollbackManager::onValuePut));
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              rollbackManager::onAllInstancesFailed));
        // Topology change notifications
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.class,
                                              clusterDeploymentManager::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.class, metricsScheduler::onTopologyChange));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.class, controlLoop::onTopologyChange));
        // Metrics messages
        entries.add(MessageRouter.Entry.route(MetricsMessage.MetricsPing.class, metricsCollector::onMetricsPing));
        entries.add(MessageRouter.Entry.route(MetricsMessage.MetricsPong.class, metricsCollector::onMetricsPong));
        // Deployment metrics messages
        entries.add(MessageRouter.Entry.route(DeploymentMetricsMessage.DeploymentMetricsPing.class,
                                              deploymentMetricsCollector::onDeploymentMetricsPing));
        entries.add(MessageRouter.Entry.route(DeploymentMetricsMessage.DeploymentMetricsPong.class,
                                              deploymentMetricsCollector::onDeploymentMetricsPong));
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.class,
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
        entries.add(MessageRouter.Entry.route(TopologyChangeNotification.class,
                                              deploymentMetricsScheduler::onTopologyChange));
        // Invocation messages
        entries.add(MessageRouter.Entry.route(InvocationMessage.InvokeRequest.class, invocationHandler::onInvokeRequest));
        entries.add(MessageRouter.Entry.route(InvocationMessage.InvokeResponse.class, sliceInvoker::onInvokeResponse));
        // KVStore local operations
        entries.add(MessageRouter.Entry.route(KVStoreLocalIO.Request.Find.class, kvStore::find));
        return entries;
    }

    /**
     * Create SharedLibraryClassLoader with appropriate parent based on configuration.
     * <p>
     * If frameworkJarsPath is configured, creates a FrameworkClassLoader with isolated
     * framework classes. Otherwise, falls back to Application ClassLoader (no isolation).
     */
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

    /**
     * Create a composite repository that tries each repository in order until one succeeds.
     * <p>
     * Note: For simplicity, currently uses the first repository only.
     * Multi-repository fallback can be added if needed.
     */
    private static Repository compositeRepository(java.util.List<Repository> repositories) {
        if (repositories.isEmpty()) {
            return artifact -> org.pragmatica.lang.utils.Causes.cause("No repositories configured")
                                  .promise();
        }
        // Use first repository (most configurations use a single repository)
        return repositories.getFirst();
    }
}
