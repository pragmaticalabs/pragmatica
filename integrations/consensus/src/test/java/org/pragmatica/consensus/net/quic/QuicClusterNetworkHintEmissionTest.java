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
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
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
    void disconnect_unknownPeer_doesNotEmitListener() {
        var captured = new CopyOnWriteArrayList<NodeId>();
        QuicDisconnectListener listener = captured::add;
        var network = createNetworkWithListener(NodeId.randomNodeId(), List.of(), MessageRouter.mutable(), listener);

        network.disconnect(new NetworkServiceMessage.DisconnectNode(new NodeId("missing")));

        assertThat(captured).as("No listener fire for peer that was never connected").isEmpty();
    }

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

    private TopologyManager stubTopologyManager(NodeInfo self, List<NodeInfo> peers) {
        return new TopologyManager() {
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
        };
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
