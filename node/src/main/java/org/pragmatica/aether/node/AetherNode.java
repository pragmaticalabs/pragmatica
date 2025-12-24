package org.pragmatica.aether.node;

import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.http.HttpRouter;
import org.pragmatica.aether.http.SliceDispatcher;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.aether.metrics.MetricsScheduler;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.leader.LeaderNotification;
import org.pragmatica.cluster.metrics.MetricsMessage;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.node.rabia.NodeConfig;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.topology.QuorumStateNotification;
import org.pragmatica.cluster.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.message.MessageRouter;
import org.pragmatica.net.serialization.Deserializer;
import org.pragmatica.net.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.pragmatica.net.serialization.binary.fury.FuryDeserializer.furyDeserializer;
import static org.pragmatica.net.serialization.binary.fury.FurySerializer.furySerializer;

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

    ControlLoop controlLoop();

    SliceInvoker sliceInvoker();

    InvocationHandler invocationHandler();

    Option<HttpRouter> httpRouter();

    /**
     * Apply commands to the cluster via consensus.
     */
    <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands);

    static AetherNode aetherNode(AetherNodeConfig config) {
        var router = MessageRouter.mutable();
        var serializer = furySerializer(AetherCustomClasses::configure);
        var deserializer = furyDeserializer(AetherCustomClasses::configure);

        return aetherNode(config, router, serializer, deserializer);
    }

    static AetherNode aetherNode(AetherNodeConfig config,
                                 MessageRouter.MutableRouter router,
                                 Serializer serializer,
                                 Deserializer deserializer) {
        record aetherNode(
                AetherNodeConfig config,
                MessageRouter.MutableRouter router,
                KVStore<AetherKey, AetherValue> kvStore,
                SliceRegistry sliceRegistry,
                SliceStore sliceStore,
                RabiaNode<KVCommand<AetherKey>> clusterNode,
                NodeDeploymentManager nodeDeploymentManager,
                ClusterDeploymentManager clusterDeploymentManager,
                EndpointRegistry endpointRegistry,
                MetricsCollector metricsCollector,
                MetricsScheduler metricsScheduler,
                ControlLoop controlLoop,
                SliceInvoker sliceInvoker,
                InvocationHandler invocationHandler,
                Option<ManagementServer> managementServer,
                Option<HttpRouter> httpRouter
        ) implements AetherNode {
            private static final Logger log = LoggerFactory.getLogger(aetherNode.class);

            @Override
            public NodeId self() {
                return config.self();
            }

            @Override
            public Promise<Unit> start() {
                log.info("Starting Aether node {}", self());
                return clusterNode.start()
                                  .flatMap(_ -> managementServer.fold(
                                          () -> Promise.success(Unit.unit()),
                                          ManagementServer::start
                                  ))
                                  .flatMap(_ -> httpRouter.fold(
                                          () -> Promise.success(Unit.unit()),
                                          HttpRouter::start
                                  ))
                                  .onSuccess(_ -> log.info("Aether node {} started successfully", self()));
            }

            @Override
            public Promise<Unit> stop() {
                log.info("Stopping Aether node {}", self());
                controlLoop.stop();
                metricsScheduler.stop();
                return httpRouter.fold(
                               () -> Promise.success(Unit.unit()),
                               HttpRouter::stop
                       )
                       .flatMap(_ -> managementServer.fold(
                               () -> Promise.success(Unit.unit()),
                               ManagementServer::stop
                       ))
                       .flatMap(_ -> clusterNode.stop())
                       .onSuccess(_ -> log.info("Aether node {} stopped", self()));
            }

            @Override
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return clusterNode.apply(commands);
            }
        }

        // Create KVStore (state machine for consensus)
        var kvStore = new KVStore<AetherKey, AetherValue>(router, serializer, deserializer);

        // Create slice management components
        var sliceRegistry = SliceRegistry.create();
        var sharedLibraryLoader = new SharedLibraryClassLoader(AetherNode.class.getClassLoader());
        var sliceStore = SliceStore.sliceStore(sliceRegistry, config.sliceAction().repositories(), sharedLibraryLoader);

        // Create Rabia cluster node
        var nodeConfig = NodeConfig.nodeConfig(config.protocol(), config.topology());
        var clusterNode = RabiaNode.rabiaNode(nodeConfig, router, kvStore, serializer, deserializer);

        // Create deployment managers
        var nodeDeploymentManager = NodeDeploymentManager.nodeDeploymentManager(
                config.self(), router, sliceStore, clusterNode, config.sliceAction()
        );

        var clusterDeploymentManager = ClusterDeploymentManager.clusterDeploymentManager(
                config.self(), clusterNode
        );

        // Create endpoint registry
        var endpointRegistry = EndpointRegistry.endpointRegistry();

        // Create metrics components
        var metricsCollector = MetricsCollector.metricsCollector(config.self(), clusterNode.network());
        var metricsScheduler = MetricsScheduler.metricsScheduler(
                config.self(), clusterNode.network(), metricsCollector
        );

        // Create controller and control loop
        var controller = DecisionTreeController.decisionTreeController();
        var controlLoop = ControlLoop.controlLoop(
                config.self(), controller, metricsCollector, clusterNode
        );

        // Create invocation components
        var invocationHandler = InvocationHandler.invocationHandler(config.self(), clusterNode.network());
        var sliceInvoker = SliceInvoker.sliceInvoker(
                config.self(), clusterNode.network(), endpointRegistry, invocationHandler, serializer, deserializer
        );

        // Wire up message routing
        configureRoutes(router, kvStore, nodeDeploymentManager, clusterDeploymentManager,
                        endpointRegistry, metricsCollector, metricsScheduler, controlLoop,
                        sliceInvoker, invocationHandler);

        // Create HTTP router if configured
        Option<HttpRouter> httpRouter = config.httpRouter().map(setup -> {
            // Create artifact resolver that parses slice ID strings to Artifact
            SliceDispatcher.ArtifactResolver artifactResolver = sliceId ->
                    Artifact.artifact(sliceId)
                            .fold(cause -> null, artifact -> artifact);

            return HttpRouter.httpRouter(
                    setup.config(),
                    setup.routingSections(),
                    sliceInvoker,
                    artifactResolver,
                    serializer,
                    deserializer
            );
        });

        // Create the node first (without management server reference)
        var node = new aetherNode(
                config, router, kvStore, sliceRegistry, sliceStore,
                clusterNode, nodeDeploymentManager, clusterDeploymentManager, endpointRegistry,
                metricsCollector, metricsScheduler, controlLoop, sliceInvoker, invocationHandler,
                Option.empty(), httpRouter
        );

        // Create management server if enabled
        if (config.managementPort() > 0) {
            var managementServer = ManagementServer.managementServer(config.managementPort(), () -> node);
            return new aetherNode(
                    config, router, kvStore, sliceRegistry, sliceStore,
                    clusterNode, nodeDeploymentManager, clusterDeploymentManager, endpointRegistry,
                    metricsCollector, metricsScheduler, controlLoop, sliceInvoker, invocationHandler,
                    Option.some(managementServer), httpRouter
            );
        }

        return node;
    }

    private static void configureRoutes(MessageRouter.MutableRouter router,
                                        KVStore<AetherKey, AetherValue> kvStore,
                                        NodeDeploymentManager nodeDeploymentManager,
                                        ClusterDeploymentManager clusterDeploymentManager,
                                        EndpointRegistry endpointRegistry,
                                        MetricsCollector metricsCollector,
                                        MetricsScheduler metricsScheduler,
                                        ControlLoop controlLoop,
                                        SliceInvoker sliceInvoker,
                                        InvocationHandler invocationHandler) {
        // KVStore notifications to deployment managers
        router.addRoute(KVStoreNotification.ValuePut.class, nodeDeploymentManager::onValuePut);
        router.addRoute(KVStoreNotification.ValuePut.class, clusterDeploymentManager::onValuePut);
        router.addRoute(KVStoreNotification.ValuePut.class, endpointRegistry::onValuePut);

        router.addRoute(KVStoreNotification.ValueRemove.class, nodeDeploymentManager::onValueRemove);
        router.addRoute(KVStoreNotification.ValueRemove.class, clusterDeploymentManager::onValueRemove);
        router.addRoute(KVStoreNotification.ValueRemove.class, endpointRegistry::onValueRemove);

        // Quorum state notifications
        router.addRoute(QuorumStateNotification.class, nodeDeploymentManager::onQuorumStateChange);

        // Leader change notifications
        router.addRoute(LeaderNotification.LeaderChange.class, clusterDeploymentManager::onLeaderChange);
        router.addRoute(LeaderNotification.LeaderChange.class, metricsScheduler::onLeaderChange);
        router.addRoute(LeaderNotification.LeaderChange.class, controlLoop::onLeaderChange);

        // Topology change notifications
        router.addRoute(TopologyChangeNotification.class, clusterDeploymentManager::onTopologyChange);
        router.addRoute(TopologyChangeNotification.class, metricsScheduler::onTopologyChange);
        router.addRoute(TopologyChangeNotification.class, controlLoop::onTopologyChange);

        // Metrics messages
        router.addRoute(MetricsMessage.MetricsPing.class, metricsCollector::onMetricsPing);
        router.addRoute(MetricsMessage.MetricsPong.class, metricsCollector::onMetricsPong);

        // Invocation messages
        router.addRoute(InvocationMessage.InvokeRequest.class, invocationHandler::onInvokeRequest);
        router.addRoute(InvocationMessage.InvokeResponse.class, sliceInvoker::onInvokeResponse);

        // KVStore local operations
        kvStore.configure(router);
    }
}
