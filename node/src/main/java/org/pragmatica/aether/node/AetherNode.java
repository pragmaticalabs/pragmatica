package org.pragmatica.aether.node;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.leader.LeaderNotification;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.node.rabia.NodeConfig;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.topology.QuorumStateNotification;
import org.pragmatica.cluster.topology.TopologyChangeNotification;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.message.MessageRouter;
import org.pragmatica.net.serialization.Deserializer;
import org.pragmatica.net.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
                EndpointRegistry endpointRegistry
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
                                  .onSuccess(_ -> log.info("Aether node {} started successfully", self()));
            }

            @Override
            public Promise<Unit> stop() {
                log.info("Stopping Aether node {}", self());
                return clusterNode.stop()
                                  .onSuccess(_ -> log.info("Aether node {} stopped", self()));
            }
        }

        // Create KVStore (state machine for consensus)
        var kvStore = new KVStore<AetherKey, AetherValue>(router, serializer, deserializer);

        // Create slice management components
        var sliceRegistry = SliceRegistry.create();
        var sliceStore = SliceStore.sliceStore(sliceRegistry, config.sliceAction().repositories());

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

        // Wire up message routing
        configureRoutes(router, kvStore, nodeDeploymentManager, clusterDeploymentManager, endpointRegistry);

        return new aetherNode(
                config, router, kvStore, sliceRegistry, sliceStore,
                clusterNode, nodeDeploymentManager, clusterDeploymentManager, endpointRegistry
        );
    }

    private static void configureRoutes(MessageRouter.MutableRouter router,
                                        KVStore<AetherKey, AetherValue> kvStore,
                                        NodeDeploymentManager nodeDeploymentManager,
                                        ClusterDeploymentManager clusterDeploymentManager,
                                        EndpointRegistry endpointRegistry) {
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

        // Topology change notifications
        router.addRoute(TopologyChangeNotification.class, clusterDeploymentManager::onTopologyChange);

        // KVStore local operations
        kvStore.configure(router);
    }
}
