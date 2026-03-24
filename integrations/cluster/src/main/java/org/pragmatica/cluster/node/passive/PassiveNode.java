package org.pragmatica.cluster.node.passive;

import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkMessage.DiscoverNodes;
import org.pragmatica.consensus.net.NetworkMessage.DiscoveredNodes;
import org.pragmatica.consensus.net.NetworkMessage.Hello;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectedNodesList;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectionEstablished;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectionFailed;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.consensus.net.quic.QuicTlsProvider;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.topology.TcpTopologyManager;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyManagementMessage.AddNode;
import org.pragmatica.consensus.topology.TopologyManagementMessage.RemoveNode;
import org.pragmatica.consensus.topology.TopologyManagementMessage.SetClusterSize;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import io.netty.handler.codec.quic.QuicSslContext;

import static org.pragmatica.messaging.MessageRouter.Entry.route;

/// A passive cluster node that joins the network but never participates in consensus.
/// Receives committed Decision messages and applies them to a local KVStore.
/// Used by load balancers and read-only observers.
public interface PassiveNode<K extends StructuredKey, V> {

    DelegateRouter delegateRouter();

    ClusterNetwork network();

    KVStore<K, V> kvStore();

    List<Entry<?>> routeEntries();

    Promise<Unit> start();

    Promise<Unit> stop();

    /// Create a passive node that joins the cluster network without consensus participation.
    /// Auto-generates self-signed TLS for QUIC transport.
    /// Returns Result because TcpTopologyManager and TLS context creation can fail.
    static <K extends StructuredKey, V> Result<PassiveNode<K, V>> passiveNode(
        TopologyConfig topologyConfig,
        Serializer serializer,
        Deserializer deserializer) {

        var delegateRouter = DelegateRouter.delegate();
        var kvStore = new KVStore<K, V>(delegateRouter, serializer, deserializer);

        return Result.all(
            TcpTopologyManager.tcpTopologyManager(topologyConfig, delegateRouter),
            QuicTlsProvider.serverContext(Option.empty()),
            QuicTlsProvider.clientContext(Option.empty())
        ).map((topologyManager, serverSsl, clientSsl) -> assembleNode(delegateRouter,
                                                                       topologyManager,
                                                                       kvStore,
                                                                       serializer,
                                                                       deserializer,
                                                                       serverSsl,
                                                                       clientSsl));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <K extends StructuredKey, V> PassiveNode<K, V> assembleNode(
        DelegateRouter delegateRouter,
        TcpTopologyManager topologyManager,
        KVStore<K, V> kvStore,
        Serializer serializer,
        Deserializer deserializer,
        QuicSslContext serverSsl,
        QuicSslContext clientSsl) {

        var network = new QuicClusterNetwork(topologyManager, serializer, deserializer,
                                             delegateRouter, serverSsl, clientSsl);

        var topologyMgmtRoutes = SealedBuilder.from(TopologyManagementMessage.class)
                                              .route(route(AddNode.class, topologyManager::handleAddNodeMessage),
                                                     route(RemoveNode.class, topologyManager::handleRemoveNodeMessage),
                                                     route(SetClusterSize.class, topologyManager::handleSetClusterSize));

        var networkMsgRoutes = SealedBuilder.from(NetworkMessage.class)
                                            .route(route(DiscoverNodes.class, topologyManager::handleDiscoverNodes),
                                                   route(DiscoveredNodes.class, topologyManager::handleDiscoveredNodes),
                                                   route(Hello.class, _ -> {}));

        var networkServiceRoutes = SealedBuilder.from(NetworkServiceMessage.class)
                                                .route(route(ConnectedNodesList.class, topologyManager::reconcile),
                                                       route(ConnectNode.class, network::connect),
                                                       route(DisconnectNode.class, network::disconnect),
                                                       route(ListConnectedNodes.class, network::listNodes),
                                                       route(ConnectionFailed.class, topologyManager::handleConnectionFailed),
                                                       route(ConnectionEstablished.class, topologyManager::handleConnectionEstablished),
                                                       route(Send.class, network::handleSend),
                                                       route(Broadcast.class, network::handleBroadcast));

        Entry decisionRoute = route(Decision.class,
                                    (Decision decision) -> applyDecision(kvStore, decision));

        var allEntries = new ArrayList<Entry<?>>();
        allEntries.add(topologyMgmtRoutes);
        allEntries.add(networkMsgRoutes);
        allEntries.add(networkServiceRoutes);
        allEntries.add(decisionRoute);

        record passiveNode<K extends StructuredKey, V>(
            DelegateRouter delegateRouter,
            TcpTopologyManager topologyManager,
            ClusterNetwork network,
            KVStore<K, V> kvStore,
            List<Entry<?>> routeEntries
        ) implements PassiveNode<K, V> {

            @Override
            public Promise<Unit> start() {
                return network().start()
                                .onSuccessRunAsync(topologyManager()::start);
            }

            @Override
            public Promise<Unit> stop() {
                topologyManager().stop();
                return network().stop();
            }
        }

        return new passiveNode<>(delegateRouter, topologyManager, network, kvStore, List.copyOf(allEntries));
    }

    @SuppressWarnings({"rawtypes", "JBCT-RET-01"}) // void required by Consumer<Decision> contract
    private static <K extends StructuredKey, V> void applyDecision(KVStore<K, V> kvStore, Decision<?> decision) {
        for (var command : decision.value().commands()) {
            kvStore.process((KVCommand) command);
        }
    }
}
