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

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.handler.codec.quic.QuicSslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class QuicClusterNetworkTest {

    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan HELLO_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private SliceCodec codec;
    private QuicSslContext serverSsl;
    private QuicSslContext clientSsl;
    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(
            FrameworkCodecs.frameworkCodecs(),
            combinedCodecs()
        );
        serverSsl = QuicTlsProvider.serverContext(Option.empty())
                                    .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
        clientSsl = QuicTlsProvider.clientContext(Option.empty())
                                    .fold(_ -> fail("Client SSL failed"), ssl -> ssl);
    }

    @AfterEach
    void tearDown() {
        for (var network : networks) {
            network.stop().await(AWAIT_TIMEOUT);
        }
        networks.clear();
    }

    @Nested
    class Lifecycle {

        @Test
        void start_succeeds_serverBound() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            assertThat(network.connectedNodeCount()).isZero();
            assertThat(network.connectedPeers()).isEmpty();
            assertThat(network.boundPort().isPresent())
                .as("Server should be bound to a port")
                .isTrue();
        }

        @Test
        void stop_afterStart_cleansUp() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            network.stop().await(AWAIT_TIMEOUT)
                   .onFailure(cause -> fail("Stop failed: " + cause.message()));

            assertThat(network.connectedNodeCount()).isZero();
        }

        @Test
        void server_returnsEmpty_quicDoesNotUseTcpServer() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            assertThat(network.server().isEmpty())
                .as("QUIC transport should not expose a TCP Server")
                .isTrue();
        }
    }

    @Nested
    class NodeIdOrdering {

        @Test
        void shouldInitiate_lowerIdInitiates_higherIdWaits() {
            var lower = new NodeId("aaa");
            var higher = new NodeId("zzz");

            assertThat(ConnectionDirection.shouldInitiate(lower, higher))
                .as("Lower NodeId should initiate")
                .isTrue();
            assertThat(ConnectionDirection.shouldInitiate(higher, lower))
                .as("Higher NodeId should not initiate")
                .isFalse();
        }

        @Test
        void shouldInitiate_equalIds_doesNotInitiate() {
            var id = new NodeId("same");

            assertThat(ConnectionDirection.shouldInitiate(id, id))
                .as("Equal NodeIds should not initiate")
                .isFalse();
        }
    }

    @Nested
    class ListNodes {

        @Test
        void listNodes_noConnections_returnsEmptyList() {
            var messages = new CopyOnWriteArrayList<>();
            var router = MessageRouter.mutable();
            router.addRoute(NetworkServiceMessage.ConnectedNodesList.class, messages::add);

            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), router);

            network.listNodes(new NetworkServiceMessage.ListConnectedNodes());

            assertThat(messages).hasSize(1);
            var list = (NetworkServiceMessage.ConnectedNodesList) messages.getFirst();
            assertThat(list.connected()).isEmpty();
        }
    }

    @Nested
    class BroadcastAndSend {

        @Test
        void broadcast_noConnections_doesNotFail() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            // Should not throw even with no connected peers
            network.broadcast(stubProtocolMessage(NodeId.randomNodeId()));
        }

        @Test
        void send_unknownPeer_logsWarning() {
            var network = createAndStartNetwork(NodeId.randomNodeId(), List.of(), MessageRouter.mutable());

            // Should not throw for unknown peer
            network.send(NodeId.randomNodeId(), stubProtocolMessage(NodeId.randomNodeId()));
        }
    }

    // --- Helper methods ---

    private QuicClusterNetwork createAndStartNetwork(NodeId nodeId, List<NodeInfo> peers, MessageRouter router) {
        var nodeAddress = NodeAddress.nodeAddress("127.0.0.1", 19999)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
        var selfInfo = NodeInfo.nodeInfo(nodeId, nodeAddress);
        TopologyManager topology = stubTopologyManager(selfInfo, peers);

        var network = new QuicClusterNetwork(topology, codec, codec, router, serverSsl, clientSsl);
        networks.add(network);

        network.startOnPort(0).await(AWAIT_TIMEOUT)
               .onFailure(cause -> fail("Start failed: " + cause.message()));

        return network;
    }

    @SuppressWarnings("unused")
    private MessageRouter trackingRouter() {
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.ConnectionEstablished.class, _ -> {});
        router.addRoute(NetworkServiceMessage.ConnectionFailed.class, _ -> {});
        router.addRoute(TopologyChangeNotification.NodeAdded.class, _ -> {});
        router.addRoute(TopologyChangeNotification.NodeRemoved.class, _ -> {});
        router.addRoute(TopologyChangeNotification.NodeDown.class, _ -> {});
        router.addRoute(QuorumStateNotification.class, _ -> {});
        router.addRoute(TopologyManagementMessage.AddNode.class, _ -> {});
        router.addRoute(NetworkServiceMessage.Send.class, _ -> {});
        router.addRoute(NetworkServiceMessage.ConnectedNodesList.class, _ -> {});
        return router;
    }

    private static StubProtocolMessage stubProtocolMessage(NodeId sender) {
        return new StubProtocolMessage(sender);
    }

    private record StubProtocolMessage(NodeId sender) implements org.pragmatica.consensus.ProtocolMessage {}

    private TopologyManager stubTopologyManager(NodeInfo self, List<NodeInfo> peers) {
        return new TopologyManager() {
            @Override
            public NodeInfo self() {
                return self;
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                if (id.equals(self.id())) {
                    return Option.some(self);
                }
                return peers.stream()
                            .filter(p -> p.id().equals(id))
                            .findFirst()
                            .map(Option::some)
                            .orElse(Option.empty());
            }

            @Override
            public int clusterSize() {
                return Math.max(peers.size() + 1, 1);
            }

            @Override
            public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public TimeSpan pingInterval() {
                return PING_INTERVAL;
            }

            @Override
            public TimeSpan helloTimeout() {
                return HELLO_TIMEOUT;
            }

            @Override
            public Option<TlsConfig> tls() {
                return Option.empty();
            }

            @Override
            public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override
            public List<NodeId> topology() {
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
