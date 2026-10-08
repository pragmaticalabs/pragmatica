package org.pragmatica.cluster.node.passive;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkMessage.DiscoverNodes;
import org.pragmatica.consensus.net.NetworkMessage.DiscoveredNodes;
import org.pragmatica.consensus.net.NetworkMessage.Hello;
import org.pragmatica.consensus.net.NetworkMessage.KeepAlive;
import org.pragmatica.consensus.net.NetworkMessage.KVSyncRequest;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteRequest;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteResponse;
import org.pragmatica.consensus.net.NetworkMessage.KVSyncResponse;
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
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyManagementMessage.SetClusterSize;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.handler.codec.quic.QuicSslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.messaging.MessageRouter.Entry.route;


/// A passive cluster node that joins the network but never participates in consensus.
/// Receives committed Decision messages and applies them to a local KVStore.
/// Used by load balancers and read-only observers.
public interface PassiveNode<K extends StructuredKey, V> {
    Logger log = LoggerFactory.getLogger(PassiveNode.class);
    DelegateRouter delegateRouter();
    ClusterNetwork network();
    KVStore<K, V> kvStore();
    TopologyObserver topologyManager();
    List<Entry<?>> routeEntries();
    Promise<Unit> start();
    Promise<Unit> stop();

    /// Create a passive node that joins the cluster network without consensus participation.
    /// Auto-generates self-signed TLS for QUIC transport.
    /// Returns Result because topology observer and TLS context creation can fail.
    static <K extends StructuredKey, V> Result<PassiveNode<K, V>> passiveNode(TopologyConfig topologyConfig,
                                                                              Serializer serializer,
                                                                              Deserializer deserializer,
                                                                              TlsConfig tlsConfig) {
        return passiveNode(topologyConfig, serializer, deserializer, tlsConfig, SnapshotSyncPolicy.snapshotSyncPolicy());
    }

    /// As [#passiveNode(TopologyConfig, Serializer, Deserializer, TlsConfig)], with an explicit
    /// snapshot-retry policy (backoff, stall bound and the operator-event observer, #2033).
    static <K extends StructuredKey, V> Result<PassiveNode<K, V>> passiveNode(TopologyConfig topologyConfig,
                                                                              Serializer serializer,
                                                                              Deserializer deserializer,
                                                                              TlsConfig tlsConfig,
                                                                              SnapshotSyncPolicy syncPolicy) {
        var delegateRouter = DelegateRouter.delegate();
        var kvStore = new KVStore<K, V>(delegateRouter, serializer, deserializer);

        return Result.all(TopologyObserver.topologyObserver(topologyConfig, delegateRouter),
                          QuicTlsProvider.serverContext(tlsConfig),
                          QuicTlsProvider.clientContext(tlsConfig))
                     .map((topologyManager, serverSsl, clientSsl) -> assembleNode(topologyConfig.self(),
                                                                                  delegateRouter,
                                                                                  topologyManager,
                                                                                  kvStore,
                                                                                  serializer,
                                                                                  deserializer,
                                                                                  serverSsl,
                                                                                  clientSsl,
                                                                                  syncPolicy));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <K extends StructuredKey, V> PassiveNode<K, V> assembleNode(NodeId selfId,
                                                                               DelegateRouter delegateRouter,
                                                                               TopologyObserver topologyManager,
                                                                               KVStore<K, V> kvStore,
                                                                               Serializer serializer,
                                                                               Deserializer deserializer,
                                                                               QuicSslContext serverSsl,
                                                                               QuicSslContext clientSsl,
                                                                               SnapshotSyncPolicy syncPolicy) {
        var snapshotSync = new SnapshotSync(selfId, delegateRouter, kvStore::restoreSnapshot, syncPolicy);
        var network = new QuicClusterNetwork(topologyManager,
                                             serializer,
                                             deserializer,
                                             delegateRouter,
                                             serverSsl,
                                             clientSsl);
        var topologyMgmtRoutes = SealedBuilder.from(TopologyManagementMessage.class).route(route(SetClusterSize.class,
                                                                                                 topologyManager::handleSetClusterSize));
        var networkMsgRoutes = SealedBuilder.from(NetworkMessage.class).route(route(DiscoverNodes.class,
                                                                                    topologyManager::handleDiscoverNodes),
                                                                              route(DiscoveredNodes.class,
                                                                                    topologyManager::handleDiscoveredNodes),
                                                                              route(Hello.class,
                                                                                    _ -> {}),

        // HelloRefused answers a refused handshake on the CONTROL stream and is consumed by the
        // dialer's Hello handler, never routed; the route satisfies sealed-hierarchy completeness.
        route(NetworkMessage.HelloRefused.class,
              _ -> {}),

        // Transport-internal liveness beacon (Wave 5): swallowed by the
        // QuicClusterNetwork inbound funnel BEFORE routing (it only refreshes
        // the per-peer receipt clock). This route exists solely to satisfy
        // sealed-hierarchy startup completeness — it must never receive traffic.
        route(KeepAlive.class,
              _ -> {}),
                                                                              route(KVSyncRequest.class,
                                                                                    _ -> {}),
                                                                              route(KVSyncResponse.class,
                                                                                    snapshotSync::onResponse),

        // Pre-vote is a voters' protocol (#1748): a passive node neither asks nor answers.
        route(LeaderPreVoteRequest.class,
              _ -> {}),
                                                                              route(LeaderPreVoteResponse.class,
                                                                                    _ -> {}));
        var networkServiceRoutes = SealedBuilder.from(NetworkServiceMessage.class).route(route(ConnectedNodesList.class,
                                                                                               topologyManager::reconcile),
                                                                                         route(ConnectNode.class,
                                                                                               network::connect),
                                                                                         route(DisconnectNode.class,
                                                                                               network::disconnect),
                                                                                         route(ListConnectedNodes.class,
                                                                                               network::listNodes),

        // R5: transport never mutates topology projection. ConnectionFailed is
        // forwarded to SWIM via QuicClusterNetwork's peer-state listener
        // (AetherNode.attachQuicPeerStateListener). The route is retained only
        // to satisfy sealed-hierarchy coverage.
        route(ConnectionFailed.class,
              _ -> {}),
                                                                                         route(ConnectionEstablished.class,
                                                                                               msg -> snapshotSync.onConnected(msg.nodeId())),
                                                                                         route(Send.class,
                                                                                               network::handleSend),
                                                                                         route(Broadcast.class,
                                                                                               network::handleBroadcast));
        Entry decisionRoute = route(Decision.class, (Decision decision) -> applyDecision(kvStore, decision));
        var allEntries = new ArrayList<Entry<?>>();

        allEntries.add(topologyMgmtRoutes);
        allEntries.add(networkMsgRoutes);
        allEntries.add(networkServiceRoutes);
        allEntries.add(decisionRoute);
        record passiveNode <K extends StructuredKey, V>(DelegateRouter delegateRouter,
                                                        TopologyObserver topologyManager,
                                                        ClusterNetwork network,
                                                        KVStore<K, V> kvStore,
                                                        List<Entry<?>> routeEntries,
                                                        SnapshotSync snapshotSync) implements PassiveNode<K, V> {
            @Override
            public Promise<Unit> start() {
                return network().start()
                              .onSuccessRunAsync(topologyManager()::start)
                              .onSuccessRun(snapshotSync()::start);
            }

            @Override
            public Promise<Unit> stop() {
                snapshotSync().stop();
                topologyManager().stop();

                return network().stop();
            }
        }

        return new passiveNode <>(delegateRouter,
                                  topologyManager,
                                  network,
                                  kvStore,
                                  List.copyOf(allEntries),
                                  snapshotSync);
    }

    @SuppressWarnings({"unchecked", "JBCT-RET-01"})  // void required by Consumer<Decision> contract
    private static <K extends StructuredKey, V> void applyDecision(KVStore<K, V> kvStore, Decision<?> decision) {
        kvStore.process((Batch<KVCommand<K>>)(Batch<?>) decision.value());
    }
}
