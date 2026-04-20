/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.net.quic;

import io.netty.handler.codec.quic.QuicSslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Verifies that `QuicClusterNetwork` invokes the injected `QuicDisconnectListener`
/// on every peer-removal view-change, alongside the existing
/// `TopologyChangeNotification.nodeRemoved` emission. Higher layers adapt the
/// callback into `HealthSignal.QuicDisconnect` for the leader's HealthReconciler.
@Timeout(10)
class QuicClusterNetworkHintEmissionTest {
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan HELLO_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private SliceCodec codec;
    private QuicSslContext serverSsl;
    private QuicSslContext clientSsl;
    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
        serverSsl = QuicTlsProvider.serverContext(TlsConfig.selfSignedServer())
                                    .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
        clientSsl = QuicTlsProvider.clientContext(TlsConfig.insecureClient())
                                    .fold(_ -> fail("Client SSL failed"), ssl -> ssl);
    }

    @AfterEach
    void tearDown() {
        for (var network : networks) {
            network.stop().await(AWAIT_TIMEOUT);
        }
        networks.clear();
    }

    @Test
    void disconnect_unknownPeer_propagatesListenerForTopologyRemoval() {
        // SWIM-driven DisconnectNode is the authoritative "this peer is gone" signal —
        // even if we never had a live QUIC link, the REMOVE view-change must fire so
        // topology and HealthReconciler see the departure. Otherwise peers whose
        // connection tore down before lifecycle promotion stay in coreNodes forever.
        var captured = new CopyOnWriteArrayList<NodeId>();
        QuicDisconnectListener listener = captured::add;
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), MessageRouter.mutable(), listener);

        var missing = new NodeId("missing");
        network.disconnect(new NetworkServiceMessage.DisconnectNode(missing));

        assertThat(captured).as("REMOVE view-change fires listener even without a prior QUIC link").containsExactly(missing);
    }

    @Test
    void disconnect_followerPath_buffersConnectivityObservation_skipsDisconnectListener() {
        // Commit 2 (ClusterSync refactor): on a follower node, REMOVE view-changes
        // must NOT invoke the disconnect listener (which would feed the local
        // HealthReconciler). Instead, a PeerConnectivityObservation is pushed to the
        // upstream buffer via the PeerConnectivityReporter so the leader folds it.
        var listenerInvocations = new CopyOnWriteArrayList<NodeId>();
        QuicDisconnectListener listener = listenerInvocations::add;
        var reported = new CopyOnWriteArrayList<ReportedDisconnect>();
        PeerConnectivityReporter reporter = (peerId, term, counter) ->
            reported.add(new ReportedDisconnect(peerId, term, counter));
        QuicClusterNetwork.ObservedEpochSupplier epoch = new QuicClusterNetwork.ObservedEpochSupplier() {
            @Override public long term() {return 11L;}
            @Override public long counter() {return 4L;}
        };
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), MessageRouter.mutable(), listener);
        network.setFollowerObservationWiring(() -> false, reporter, epoch);

        var missing = new NodeId("missing");
        network.disconnect(new NetworkServiceMessage.DisconnectNode(missing));

        assertThat(listenerInvocations).as("follower must NOT invoke local disconnect listener").isEmpty();
        assertThat(reported).as("follower pushes PeerConnectivityObservation upstream")
                            .containsExactly(new ReportedDisconnect(missing, 11L, 4L));
    }

    @Test
    void disconnect_leaderPath_stillInvokesDisconnectListener_noUpstreamReport() {
        var listenerInvocations = new CopyOnWriteArrayList<NodeId>();
        QuicDisconnectListener listener = listenerInvocations::add;
        var reported = new CopyOnWriteArrayList<ReportedDisconnect>();
        PeerConnectivityReporter reporter = (peerId, term, counter) ->
            reported.add(new ReportedDisconnect(peerId, term, counter));
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), MessageRouter.mutable(), listener);
        network.setFollowerObservationWiring(() -> true, reporter, QuicClusterNetwork.ObservedEpochSupplier.zero());

        var missing = new NodeId("missing");
        network.disconnect(new NetworkServiceMessage.DisconnectNode(missing));

        assertThat(listenerInvocations).containsExactly(missing);
        assertThat(reported).as("leader does not duplicate into the upstream buffer").isEmpty();
    }

    private record ReportedDisconnect(NodeId peerId, long term, long counter) {}

    @Test
    void defaultConstructor_usesNoopListener_withoutCrashing() {
        var nodeId = NodeId.randomNodeId();
        var address = NodeAddress.nodeAddress("127.0.0.1", 19999).fold(_ -> fail("bad address"), a -> a);
        var selfInfo = NodeInfo.nodeInfo(nodeId, address);
        var topology = stubTopologyManager(selfInfo, List.of());
        var network = new QuicClusterNetwork(topology, codec, codec, MessageRouter.mutable(),
                                              serverSsl, clientSsl);
        networks.add(network);

        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        network.disconnect(new NetworkServiceMessage.DisconnectNode(new NodeId("missing")));
    }

    private QuicClusterNetwork createNetworkWithListener(NodeId nodeId,
                                                          List<NodeInfo> peers,
                                                          MessageRouter router,
                                                          QuicDisconnectListener listener) {
        var address = NodeAddress.nodeAddress("127.0.0.1", 19999).fold(_ -> fail("bad address"), a -> a);
        var selfInfo = NodeInfo.nodeInfo(nodeId, address);
        var topology = stubTopologyManager(selfInfo, peers);
        var network = new QuicClusterNetwork(topology, codec, codec, router, serverSsl, clientSsl,
                                              ClusterFormationConfig.defaults(), listener);
        networks.add(network);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    private TopologyObserver stubTopologyManager(NodeInfo self, List<NodeInfo> peers) {
        return new TopologyObserver() {
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {
                if (id.equals(self.id())) {return Option.some(self);}
                return peers.stream().filter(p -> p.id().equals(id)).findFirst().map(Option::some).orElse(Option.empty());
            }
            @Override public int clusterSize() {return Math.max(peers.size() + 1, 1);}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
            @Override public TimeSpan helloTimeout() {return HELLO_TIMEOUT;}
            @Override public Option<TlsConfig> tls() {return Option.empty();}
            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
            @Override public List<NodeId> topology() {
                var result = new ArrayList<NodeId>();
                result.add(self.id());
                peers.forEach(p -> result.add(p.id()));
                return result;
            }
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void registerPeer(NodeInfo peerInfo) {}
            @Override public void unregisterPeer(NodeId peerId) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleConnectionFailed(NetworkServiceMessage.ConnectionFailed connectionFailed) {}
            @Override public void handleConnectionEstablished(NetworkServiceMessage.ConnectionEstablished connectionEstablished) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
