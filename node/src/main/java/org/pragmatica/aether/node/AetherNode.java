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
        var sliceStore = SliceStore.sliceStore(sliceRegistry, repositories, sharedLibraryLoader);
        // Create Rabia cluster node with metrics
        var nodeConfig = NodeConfig.nodeConfig(config.protocol(), config.topology());
        var rabiaMetricsCollector = RabiaMetricsCollector.rabiaMetricsCollector();
        var networkMetricsHandler = NetworkMetricsHandler.networkMetricsHandler();
        var clusterNode = RabiaNode.rabiaNode(nodeConfig,
                                              delegateRouter,
                                              kvStore,
                                              serializer,
                                              deserializer,
                                              rabiaMetricsCollector,
                                              List.of(networkMetricsHandler));
        // Assemble all components and collect routes
        return assembleNode(config,
                            delegateRouter,
                            kvStore,
                            sliceRegistry,
                            sliceStore,
                            clusterNode,
                            rabiaMetricsCollector,
                            networkMetricsHandler,
                            serializer,
                            deserializer,
                            artifactStore,
                            repositories);
    }

    private static Result<AetherNode> assembleNode(AetherNodeConfig config,
                                                   MessageRouter.DelegateRouter delegateRouter,
                                                   KVStore<AetherKey, AetherValue> kvStore,
                                                   SliceRegistry sliceRegistry,
                                                   SliceStore sliceStore,
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
                controlLoop.stop();
                metricsScheduler.stop();
                deploymentMetricsScheduler.stop();
                ttmManager.stop();
                snapshotCollector.stop();
                SliceRuntime.clear();
                InfraStore.clear();
                return managementServer.map(ManagementServer::stop)
                                       .or(Promise.unitPromise())
                                       .flatMap(_ -> appHttpServer.stop())
                                       .flatMap(_ -> sliceInvoker.stop())
                                       .flatMap(_ -> clusterNode.stop())
                                       .onSuccess(_ -> log.info("Aether node {} stopped",
                                                                self()));
            }

            private Promise<Unit> startClusterAsync() {
                clusterNode.start()
                           .onSuccess(_ -> {
                                          log.info("Aether node {} cluster formation complete",
                                                   self());
                                          // Ensure NodeDeploymentManager is activated after cluster formation
                // This guarantees activation even if QuorumStateNotification was missed
                nodeDeploymentManager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
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
                return Promise.success(Unit.unit());
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

    /**
     * Collect all route entries for the Aether node.
     *
     * <p>Routes are organized by concern area using {@link org.pragmatica.aether.message.RouteGroup}
     * and compile-time validated using SealedBuilder for sealed hierarchies.
     */
    private static List<MessageRouter.Entry< ?>> collectRouteEntries(KVStore<AetherKey, AetherValue> kvStore,
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
        var entries = new ArrayList<MessageRouter.Entry< ?>>();
        // === KV-Store Notifications (fan-out to multiple handlers) ===
        entries.addAll(kvStoreRoutes(nodeDeploymentManager,
                                     clusterDeploymentManager,
                                     endpointRegistry,
                                     httpRouteRegistry,
                                     controlLoop,
                                     alertManager,
                                     rollbackManager,
                                     artifactMetricsCollector));
        // === Cluster Lifecycle Events (quorum, leader, topology) ===
        entries.addAll(clusterLifecycleRoutes(nodeDeploymentManager,
                                              clusterDeploymentManager,
                                              metricsScheduler,
                                              controlLoop,
                                              ttmManager,
                                              rabiaMetricsCollector,
                                              rollingUpdateManager,
                                              rollbackManager,
                                              deploymentMetricsCollector,
                                              deploymentMetricsScheduler));
        // === Metrics Collection (sealed hierarchies) ===
        entries.addAll(metricsRoutes(metricsCollector, deploymentMetricsCollector));
        // === Deployment Events (sealed hierarchy with SealedBuilder) ===
        entries.add(deploymentEventRoutes(deploymentMetricsCollector));
        // === Slice Failure Events ===
        entries.add(sliceFailureRoutes(rollbackManager));
        // === Invocation Messages (sealed hierarchy with SealedBuilder) ===
        entries.add(invocationRoutes(invocationHandler, sliceInvoker));
        // === KV-Store Local I/O ===
        entries.add(MessageRouter.Entry.route(KVStoreLocalIO.Request.Find.class, kvStore::find));
        return entries;
    }

    /**
     * KV-Store notification routes - fan-out pattern where multiple handlers
     * react to the same notification for different purposes.
     */
    private static List<MessageRouter.Entry< ?>> kvStoreRoutes(NodeDeploymentManager nodeDeploymentManager,
                                                               ClusterDeploymentManager clusterDeploymentManager,
                                                               EndpointRegistry endpointRegistry,
                                                               HttpRouteRegistry httpRouteRegistry,
                                                               ControlLoop controlLoop,
                                                               AlertManager alertManager,
                                                               RollbackManager rollbackManager,
                                                               ArtifactMetricsCollector artifactMetricsCollector) {
        return org.pragmatica.aether.message.RouteGroup.routeGroup("kvstore")
                  .fanOut(KVStoreNotification.ValuePut.class,
                          nodeDeploymentManager::onValuePut,
                          clusterDeploymentManager::onValuePut,
                          endpointRegistry::onValuePut,
                          httpRouteRegistry::onValuePut,
                          controlLoop::onValuePut,
                          rollbackManager::onValuePut,
                          artifactMetricsCollector::onValuePut,
                          notification -> alertManager.onKvStoreUpdate((AetherKey) notification.cause()
                                                                                              .key(),
                                                                       (AetherValue) notification.cause()
                                                                                                .value()))
                  .fanOut(KVStoreNotification.ValueRemove.class,
                          nodeDeploymentManager::onValueRemove,
                          clusterDeploymentManager::onValueRemove,
                          endpointRegistry::onValueRemove,
                          httpRouteRegistry::onValueRemove,
                          controlLoop::onValueRemove,
                          artifactMetricsCollector::onValueRemove,
                          notification -> alertManager.onKvStoreRemove((AetherKey) notification.cause()
                                                                                              .key()))
                  .build()
                  .toList();
    }

    /**
     * Cluster lifecycle routes - leader changes, topology changes, quorum state.
     */
    private static List<MessageRouter.Entry< ?>> clusterLifecycleRoutes(NodeDeploymentManager nodeDeploymentManager,
                                                                        ClusterDeploymentManager clusterDeploymentManager,
                                                                        MetricsScheduler metricsScheduler,
                                                                        ControlLoop controlLoop,
                                                                        TTMManager ttmManager,
                                                                        RabiaMetricsCollector rabiaMetricsCollector,
                                                                        RollingUpdateManagerImpl rollingUpdateManager,
                                                                        RollbackManager rollbackManager,
                                                                        DeploymentMetricsCollector deploymentMetricsCollector,
                                                                        DeploymentMetricsScheduler deploymentMetricsScheduler) {
        return org.pragmatica.aether.message.RouteGroup.routeGroup("cluster-lifecycle")
                  .route(QuorumStateNotification.class, nodeDeploymentManager::onQuorumStateChange)
                  .fanOut(LeaderNotification.LeaderChange.class,
                          clusterDeploymentManager::onLeaderChange,
                          metricsScheduler::onLeaderChange,
                          controlLoop::onLeaderChange,
                          ttmManager::onLeaderChange,
                          rollingUpdateManager::onLeaderChange,
                          rollbackManager::onLeaderChange,
                          deploymentMetricsScheduler::onLeaderChange,
                          change -> rabiaMetricsCollector.updateRole(change.localNodeIsLeader(),
                                                                     change.leaderId()
                                                                           .map(NodeId::id)))
                  .fanOut(TopologyChangeNotification.class,
                          clusterDeploymentManager::onTopologyChange,
                          metricsScheduler::onTopologyChange,
                          controlLoop::onTopologyChange,
                          deploymentMetricsCollector::onTopologyChange,
                          deploymentMetricsScheduler::onTopologyChange)
                  .build()
                  .toList();
    }

    /**
     * Metrics collection routes - uses SealedBuilder for sealed message hierarchies.
     */
    private static List<MessageRouter.Entry< ?>> metricsRoutes(MetricsCollector metricsCollector,
                                                               DeploymentMetricsCollector deploymentMetricsCollector) {
        // MetricsMessage sealed hierarchy (Ping/Pong)
        var metricsMessageRoutes = MessageRouter.Entry.SealedBuilder.from(MetricsMessage.class)
                                                .route(MessageRouter.Entry.route(MetricsMessage.MetricsPing.class,
                                                                                 metricsCollector::onMetricsPing),
                                                       MessageRouter.Entry.route(MetricsMessage.MetricsPong.class,
                                                                                 metricsCollector::onMetricsPong));
        // DeploymentMetricsMessage sealed hierarchy (Ping/Pong)
        var deploymentMetricsRoutes = MessageRouter.Entry.SealedBuilder.from(DeploymentMetricsMessage.class)
                                                   .route(MessageRouter.Entry.route(DeploymentMetricsMessage.DeploymentMetricsPing.class,
                                                                                    deploymentMetricsCollector::onDeploymentMetricsPing),
                                                          MessageRouter.Entry.route(DeploymentMetricsMessage.DeploymentMetricsPong.class,
                                                                                    deploymentMetricsCollector::onDeploymentMetricsPong));
        return List.of(metricsMessageRoutes, deploymentMetricsRoutes);
    }

    /**
     * Deployment event routes - SealedBuilder validates all DeploymentEvent variants are handled.
     */
    private static MessageRouter.Entry< ?> deploymentEventRoutes(DeploymentMetricsCollector collector) {
        return MessageRouter.Entry.SealedBuilder.from(DeploymentEvent.class)
                            .route(MessageRouter.Entry.route(DeploymentEvent.DeploymentStarted.class,
                                                             collector::onDeploymentStarted),
                                   MessageRouter.Entry.route(DeploymentEvent.StateTransition.class,
                                                             collector::onStateTransition),
                                   MessageRouter.Entry.route(DeploymentEvent.DeploymentCompleted.class,
                                                             collector::onDeploymentCompleted),
                                   MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class,
                                                             collector::onDeploymentFailed));
    }

    /**
     * Slice failure event routes - SealedBuilder validates all SliceFailureEvent variants are handled.
     */
    private static MessageRouter.Entry< ?> sliceFailureRoutes(RollbackManager rollbackManager) {
        return MessageRouter.Entry.SealedBuilder.from(SliceFailureEvent.class)
                            .route(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                                             rollbackManager::onAllInstancesFailed));
    }

    /**
     * Invocation message routes - SealedBuilder validates all InvocationMessage variants are handled.
     */
    private static MessageRouter.Entry< ?> invocationRoutes(InvocationHandler handler, SliceInvoker invoker) {
        return MessageRouter.Entry.SealedBuilder.from(InvocationMessage.class)
                            .route(MessageRouter.Entry.route(InvocationMessage.InvokeRequest.class,
                                                             handler::onInvokeRequest),
                                   MessageRouter.Entry.route(InvocationMessage.InvokeResponse.class,
                                                             invoker::onInvokeResponse));
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
