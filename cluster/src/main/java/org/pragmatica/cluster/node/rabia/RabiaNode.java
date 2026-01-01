package org.pragmatica.cluster.node.rabia;

import org.pragmatica.consensus.rabia.RabiaEngine;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkManagementOperation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.net.netty.NettyClusterNetwork;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.cluster.topology.ip.TcpTopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

public interface RabiaNode<C extends Command> extends ClusterNode<C> {
    @SuppressWarnings("unused")
    MessageRouter router();

    ClusterNetwork network();

    static <C extends Command> RabiaNode<C> rabiaNode(NodeConfig config,
                                                      MessageRouter.MutableRouter router,
                                                      StateMachine<C> stateMachine,
                                                      Serializer serializer,
                                                      Deserializer deserializer) {
        record rabiaNode <C extends Command>(NodeConfig config,
                                             MessageRouter router,
                                             StateMachine<C> stateMachine,
                                             ClusterNetwork network,
                                             TopologyManager topologyManager,
                                             RabiaEngine<C> consensus,
                                             LeaderManager leaderManager) implements RabiaNode<C> {
            @Override
            public NodeId self() {
                return config()
                       .topology()
                       .self();
            }

            @Override
            public Promise<Unit> start() {
                return network()
                       .start()
                       .onSuccessRunAsync(topologyManager()::start)
                       .flatMap(consensus()::start);
            }

            @Override
            public Promise<Unit> stop() {
                return consensus()
                       .stop()
                       .onResultRun(topologyManager()::stop)
                       .flatMap(network()::stop);
            }

            @Override
            public <R> Promise<List<R>> apply(List<C> commands) {
                return consensus()
                       .apply(commands);
            }
        }
        var topologyManager = TcpTopologyManager.tcpTopologyManager(config.topology(), router);
        var leaderManager = LeaderManager.leaderManager(config.topology()
                                                              .self(),
                                                        router);
        var network = new NettyClusterNetwork(topologyManager, serializer, deserializer, router);
        var consensus = new RabiaEngine<>(topologyManager, network, stateMachine, config.protocol());
        // TODO: Migrate to ImmutableRouter - all routes need centralized assembly
        router.addRoute(TopologyManagementMessage.AddNode.class, topologyManager::handleAddNodeMessage);
        router.addRoute(TopologyManagementMessage.RemoveNode.class, topologyManager::handleRemoveNodeMessage);
        router.addRoute(TopologyManagementMessage.DiscoverNodes.class, topologyManager::handleDiscoverNodesMessage);
        router.addRoute(TopologyManagementMessage.DiscoveredNodes.class, topologyManager::handleMergeNodesMessage);
        router.addRoute(NetworkManagementOperation.ConnectedNodesList.class, topologyManager::reconcile);
        router.addRoute(NodeAdded.class, leaderManager::nodeAdded);
        router.addRoute(NodeRemoved.class, leaderManager::nodeRemoved);
        router.addRoute(NodeDown.class, leaderManager::nodeDown);
        router.addRoute(QuorumStateNotification.class, leaderManager::watchQuorumState);
        network.configure(router);
        return new rabiaNode <>(config, router, stateMachine, network, topologyManager, consensus, leaderManager);
    }
}
