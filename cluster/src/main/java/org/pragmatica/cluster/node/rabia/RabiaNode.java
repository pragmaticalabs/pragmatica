package org.pragmatica.cluster.node.rabia;

import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkManagementOperation;
import org.pragmatica.consensus.net.NetworkManagementOperation.ConnectedNodesList;
import org.pragmatica.consensus.net.NetworkManagementOperation.ConnectNode;
import org.pragmatica.consensus.net.NetworkManagementOperation.ConnectionEstablished;
import org.pragmatica.consensus.net.NetworkManagementOperation.ConnectionFailed;
import org.pragmatica.consensus.net.NetworkManagementOperation.DisconnectNode;
import org.pragmatica.consensus.net.NetworkManagementOperation.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkMessage.Ping;
import org.pragmatica.consensus.net.NetworkMessage.Pong;
import org.pragmatica.consensus.net.netty.NettyClusterNetwork;
import org.pragmatica.consensus.rabia.ConsensusMetrics;
import org.pragmatica.consensus.rabia.RabiaEngine;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TcpTopologyManager;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.AllNodesReset;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDisabled;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeReactivated;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyManagementMessage.AddNode;
import org.pragmatica.consensus.topology.TopologyManagementMessage.DiscoverNodes;
import org.pragmatica.consensus.topology.TopologyManagementMessage.DiscoveredNodes;
import org.pragmatica.consensus.topology.TopologyManagementMessage.RemoveNode;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Tuple.Tuple2;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder;
import org.pragmatica.messaging.MessageRouter.ImmutableRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.netty.channel.ChannelHandler;

import static org.pragmatica.messaging.MessageRouter.Entry.route;

public interface RabiaNode<C extends Command> extends ClusterNode<C> {
    ClusterNetwork network();

    LeaderManager leaderManager();

    /**
     * Check if the consensus engine is active and ready for commands.
     */
    boolean isActive();

    /**
     * Get the route entries for RabiaNode's internal components.
     * These should be combined with other entries when building the final router.
     */
    List<Entry<?>> routeEntries();

    /**
     * Creates a RabiaNode without metrics collection.
     */
    static <C extends Command> RabiaNode<C> rabiaNode(NodeConfig config,
                                                      DelegateRouter delegateRouter,
                                                      StateMachine<C> stateMachine,
                                                      Serializer serializer,
                                                      Deserializer deserializer) {
        return rabiaNode(config,
                         delegateRouter,
                         stateMachine,
                         serializer,
                         deserializer,
                         ConsensusMetrics.noop(),
                         List.of());
    }

    /**
     * Creates a RabiaNode with metrics collection.
     */
    static <C extends Command> RabiaNode<C> rabiaNode(NodeConfig config,
                                                      DelegateRouter delegateRouter,
                                                      StateMachine<C> stateMachine,
                                                      Serializer serializer,
                                                      Deserializer deserializer,
                                                      ConsensusMetrics metrics) {
        return rabiaNode(config, delegateRouter, stateMachine, serializer, deserializer, metrics, List.of());
    }

    /**
     * Creates a RabiaNode with metrics collection and custom network handlers.
     * <p>
     * The node collects its route entries which should be combined with other routes
     * when building the final ImmutableRouter. Use {@link #routeEntries()} to retrieve them.
     *
     * @param config             Node configuration
     * @param delegateRouter     DelegateRouter for message routing (caller must wire after collecting all routes)
     * @param stateMachine       State machine for consensus
     * @param serializer         Message serializer
     * @param deserializer       Message deserializer
     * @param metrics            Consensus metrics collector
     * @param additionalHandlers Additional Netty handlers (e.g., NetworkMetricsHandler)
     * @return RabiaNode instance (always succeeds - route validation is caller's responsibility)
     */
    static <C extends Command> RabiaNode<C> rabiaNode(NodeConfig config,
                                                      DelegateRouter delegateRouter,
                                                      StateMachine<C> stateMachine,
                                                      Serializer serializer,
                                                      Deserializer deserializer,
                                                      ConsensusMetrics metrics,
                                                      List<ChannelHandler> additionalHandlers) {
        // Create components with delegate router (routes configured after construction)
        var topologyManager = TcpTopologyManager.tcpTopologyManager(config.topology(), delegateRouter);
        var leaderManager = LeaderManager.leaderManager(config.topology()
                                                              .self(),
                                                        delegateRouter);
        var network = new NettyClusterNetwork(topologyManager,
                                              serializer,
                                              deserializer,
                                              delegateRouter,
                                              additionalHandlers);
        var consensus = new RabiaEngine<>(topologyManager, network, stateMachine, config.protocol(), metrics);
        // Collect sealed hierarchy entries
        var topologyMgmtRoutes = SealedBuilder.from(TopologyManagementMessage.class)
                                              .route(route(AddNode.class, topologyManager::handleAddNodeMessage),
                                                     route(RemoveNode.class, topologyManager::handleRemoveNodeMessage),
                                                     route(DiscoverNodes.class,
                                                           topologyManager::handleDiscoverNodesMessage),
                                                     route(DiscoveredNodes.class,
                                                           topologyManager::handleMergeNodesMessage));
        var networkMgmtRoutes = SealedBuilder.from(NetworkManagementOperation.class)
                                             .route(route(ConnectedNodesList.class, topologyManager::reconcile),
                                                    route(ConnectNode.class, network::connect),
                                                    route(DisconnectNode.class, network::disconnect),
                                                    route(ListConnectedNodes.class, network::listNodes),
                                                    route(ConnectionFailed.class,
                                                          topologyManager::handleConnectionFailed),
                                                    route(ConnectionEstablished.class,
                                                          topologyManager::handleConnectionEstablished));
        var topologyChangeRoutes = SealedBuilder.from(TopologyChangeNotification.class)
                                                .route(route(NodeAdded.class, leaderManager::nodeAdded),
                                                       route(NodeRemoved.class, leaderManager::nodeRemoved),
                                                       route(NodeDown.class, leaderManager::nodeDown),
                                                       route(NodeDisabled.class,
                                                             _ -> {}),
                                                       route(NodeReactivated.class,
                                                             _ -> {}),
                                                       route(AllNodesReset.class,
                                                             _ -> {}));
        var syncRoutes = SealedBuilder.from(Synchronous.class)
                                      .route(route(Propose.class, consensus::processPropose),
                                             route(VoteRound1.class, consensus::processVoteRound1),
                                             route(VoteRound2.class, consensus::processVoteRound2),
                                             route(Decision.class, consensus::processDecision),
                                             route(SyncResponse.class,
                                                   (SyncResponse r) -> consensus.processSyncResponse(r)));
        var asyncRoutes = SealedBuilder.from(Asynchronous.class)
                                       .route(route(SyncRequest.class, consensus::handleSyncRequest),
                                              route(NewBatch.class,
                                                    (NewBatch b) -> consensus.handleNewBatch(b)));
        // Collect all entries
        var allEntries = new ArrayList<Entry<?>>();
        allEntries.add(topologyMgmtRoutes);
        allEntries.add(networkMgmtRoutes);
        allEntries.add(topologyChangeRoutes);
        allEntries.add(syncRoutes);
        allEntries.add(asyncRoutes);
        allEntries.add(route(QuorumStateNotification.class, leaderManager::watchQuorumState));
        allEntries.add(route(QuorumStateNotification.class, consensus::quorumState));
        allEntries.add(route(Ping.class, network::handlePing));
        allEntries.add(route(Pong.class, network::handlePong));
        record rabiaNode<C extends Command>(NodeConfig config,
                                            StateMachine<C> stateMachine,
                                            ClusterNetwork network,
                                            TopologyManager topologyManager,
                                            RabiaEngine<C> consensus,
                                            LeaderManager leaderManager,
                                            List<Entry<?>> routeEntries) implements RabiaNode<C> {
            @Override
            public NodeId self() {
                return config().topology()
                             .self();
            }

            @Override
            public Promise<Unit> start() {
                return network().start()
                              .onSuccessRunAsync(topologyManager()::start)
                              .flatMap(consensus()::start);
            }

            @Override
            public Promise<Unit> stop() {
                return consensus().stop()
                                .onResultRun(topologyManager()::stop)
                                .flatMap(network()::stop);
            }

            @Override
            public boolean isActive() {
                return consensus().isActive();
            }

            @Override
            public <R> Promise<List<R>> apply(List<C> commands) {
                return consensus().apply(commands);
            }
        }
        return new rabiaNode<>(config,
                               stateMachine,
                               network,
                               topologyManager,
                               consensus,
                               leaderManager,
                               List.copyOf(allEntries));
    }

    /**
     * Builds an ImmutableRouter from route entries and wires it to a DelegateRouter.
     * Validates all sealed hierarchies and merges entries into a single routing table.
     *
     * @param delegateRouter Router to wire
     * @param entries        All route entries to include
     * @return Result containing the ImmutableRouter, or failure if validation fails
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Result<MessageRouter> buildAndWireRouter(DelegateRouter delegateRouter, List<Entry<?>> entries) {
        // Validate all sealed hierarchies
        Set<Class<?>> validationErrors = new HashSet<>();
        for (var entry : entries) {
            validationErrors.addAll(entry.validate());
        }
        if (!validationErrors.isEmpty()) {
            var missing = validationErrors.stream()
                                          .map(Class::getSimpleName)
                                          .collect(Collectors.joining(", "));
            return new MessageRouter.InvalidMessageRouterConfiguration("Missing routes: " + missing).result();
        }
        // Collect all entries into routing table
        Map<Class, List<Consumer>> routingTable = new HashMap<>();
        for (var entry : entries) {
            entry.entries()
                 .forEach(tuple -> addToTable(routingTable, tuple));
        }
        // Create ImmutableRouter
        record immutableRouter(Map<Class, List<Consumer>> routingTable) implements ImmutableRouter {
            @Override
            public void route(Message message) {
                var handlers = routingTable.get(message.getClass());
                if (handlers != null) {
                    handlers.forEach(h -> h.accept(message));
                }
            }
        }
        var router = new immutableRouter(Map.copyOf(routingTable));
        delegateRouter.replaceDelegate(router);
        return Result.success(router);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addToTable(Map<Class, List<Consumer>> table, Tuple2 tuple) {
        table.computeIfAbsent((Class) tuple.first(),
                              _ -> new ArrayList<>())
             .add((Consumer) tuple.last());
    }
}
