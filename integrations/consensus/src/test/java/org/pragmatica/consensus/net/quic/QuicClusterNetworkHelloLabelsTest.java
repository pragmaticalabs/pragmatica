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

import org.pragmatica.net.tcp.TlsConfig;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.JoinGenerationGuard;
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
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #2981 step 6 — the per-peer Hello label map the wire-generation join guard reads: a peer is "handshaken" exactly
/// when its Hello labels are recorded; a reconnection overwrites them; terminal departure drops them.
@Timeout(30)
class QuicClusterNetworkHelloLabelsTest {
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan HELLO_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private SliceCodec codec;
    private QuicSslContext serverSsl;
    private QuicSslContext clientSsl;
    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
        serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                   .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
        clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
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
    void peerHelloLabels_absentBeforeHandshake_presentAfterWithItsLabels() {
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        var network = createNetwork(self, Set.of(self, peerId));

        assertThat(network.peerHelloLabels(peerId).isEmpty()).as("not handshaken yet").isTrue();

        network.onPeerConnectedForTests(activeConnection(peerId),
                                        addressOf("127.0.0.1", 5000),
                                        Map.of(NodeInfo.LABEL_WIRE_GENERATION, "2"));

        assertThat(network.peerHelloLabels(peerId).or(Map.of()))
            .containsEntry(NodeInfo.LABEL_WIRE_GENERATION, "2");
    }

    @Test
    void peerHelloLabels_reconnectWithNewLabels_overwritesTheEntry() {
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        var network = createNetwork(self, Set.of(self, peerId));

        network.onPeerConnectedForTests(activeConnection(peerId), addressOf("127.0.0.1", 5000), Map.of());
        assertThat(network.peerHelloLabels(peerId).or(Map.of("sentinel", "absent"))).isEmpty();

        network.onPeerConnectedForTests(activeConnection(peerId),
                                        addressOf("127.0.0.1", 5000),
                                        Map.of(NodeInfo.LABEL_WIRE_GENERATION, "2"));

        assertThat(network.peerHelloLabels(peerId).or(Map.of()))
            .as("an upgraded peer reconnects with new labels: the entry is overwritten")
            .containsEntry(NodeInfo.LABEL_WIRE_GENERATION, "2");
    }

    @Test
    void peerHelloLabels_afterTerminalDeparture_isDropped() {
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        var network = createNetwork(self, Set.of(self, peerId));

        network.onPeerConnectedForTests(activeConnection(peerId),
                                        addressOf("127.0.0.1", 5000),
                                        Map.of(NodeInfo.LABEL_WIRE_GENERATION, "2"));
        network.departurePermanent(peerId);

        assertThat(network.peerHelloLabels(peerId).isEmpty()).as("departed peer is no longer handshaken").isTrue();
    }

    @Test
    void peerHelloLabels_afterShutdown_areCleared() {
        var self = new NodeId("aaa-self");
        var peerId = new NodeId("zzz-peer");
        var network = createNetwork(self, Set.of(self, peerId));

        network.onPeerConnectedForTests(activeConnection(peerId),
                                        addressOf("127.0.0.1", 5000),
                                        Map.of(NodeInfo.LABEL_WIRE_GENERATION, "2"));
        network.stop().await(AWAIT_TIMEOUT);

        assertThat(network.peerHelloLabels(peerId).isEmpty()).as("a stopped network holds no handshake record").isTrue();
    }

    @Test
    void peerHelloLabels_realDial_dialerCapturesAcceptorLabels_acceptorCapturesDialerLabels() {
        var dialerId = new NodeId("aaa-dialer");
        var acceptorId = new NodeId("zzz-acceptor");
        var both = Set.of(dialerId, acceptorId);
        var acceptor = createNetwork(labelledSelf(acceptorId, "7"), both);
        var dialer = createNetwork(labelledSelf(dialerId, "9"), both);
        var acceptorInfo = NodeInfo.nodeInfo(acceptorId, addressOf("127.0.0.1", acceptor.boundPort().unwrap()));

        dialer.dialForTests(acceptorInfo, true);

        awaitLabels(dialer, acceptorId);
        awaitLabels(acceptor, dialerId);
        assertThat(dialer.peerHelloLabels(acceptorId).or(Map.of()))
            .as("dial side: labels come from the Hello response")
            .containsEntry(NodeInfo.LABEL_WIRE_GENERATION, "7");
        assertThat(acceptor.peerHelloLabels(dialerId).or(Map.of()))
            .as("accept side: labels come from the inbound Hello")
            .containsEntry(NodeInfo.LABEL_WIRE_GENERATION, "9");
    }

    /// MF1: the consensus layer stamps this binary's generation into both Hellos, so an assembly that sets no label (Ember, forge, a
    /// test) still advertises it; a dialer and an acceptor with NO labels each see the other at [JoinGenerationGuard#WIRE_GENERATION].
    @Test
    void peerHelloLabels_realDialWithoutLabels_bothSidesSeeTheStampedGeneration() {
        var dialerId = new NodeId("aaa-plain-dialer");
        var acceptorId = new NodeId("zzz-plain-acceptor");
        var both = Set.of(dialerId, acceptorId);
        var acceptor = createNetwork(plainSelf(acceptorId), both);
        var dialer = createNetwork(plainSelf(dialerId), both);
        var acceptorInfo = NodeInfo.nodeInfo(acceptorId, addressOf("127.0.0.1", acceptor.boundPort().unwrap()));
        var stamped = Integer.toString(JoinGenerationGuard.WIRE_GENERATION);

        dialer.dialForTests(acceptorInfo, true);

        awaitLabels(dialer, acceptorId);
        awaitLabels(acceptor, dialerId);
        assertThat(dialer.peerHelloLabels(acceptorId).or(Map.of()))
            .as("the acceptor's Hello response is stamped")
            .containsEntry(NodeInfo.LABEL_WIRE_GENERATION, stamped);
        assertThat(acceptor.peerHelloLabels(dialerId).or(Map.of()))
            .as("the dialer's Hello is stamped")
            .containsEntry(NodeInfo.LABEL_WIRE_GENERATION, stamped);
    }

    private static NodeInfo plainSelf(NodeId id) {
        return NodeInfo.nodeInfo(id, addressOf("127.0.0.1", 19998));
    }

    private static NodeInfo labelledSelf(NodeId id, String generation) {
        return NodeInfo.nodeInfo(id,
                                 addressOf("127.0.0.1", 19998),
                                 Map.of(NodeInfo.LABEL_WIRE_GENERATION, generation));
    }

    private static void awaitLabels(QuicClusterNetwork network, NodeId peer) {
        var deadline = System.nanoTime() + AWAIT_TIMEOUT.nanos();

        while (network.peerHelloLabels(peer).isEmpty() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(20_000_000L);
        }
        assertThat(network.peerHelloLabels(peer).isPresent()).as("handshake completed with " + peer).isTrue();
    }

    private QuicPeerConnection activeConnection(NodeId peerId) {
        var chan = mock(QuicChannel.class);
        when(chan.isActive()).thenReturn(true);
        // The successful-attach path registers a close listener on the channel's closeFuture;
        // stub it so the mock returns a real (never-completing) promise instead of null.
        when(chan.closeFuture()).thenReturn(new DefaultChannelPromise(chan, ImmediateEventExecutor.INSTANCE));
        return QuicPeerConnection.quicPeerConnection(peerId, chan);
    }

    private static NodeAddress addressOf(String host, int port) {
        return NodeAddress.nodeAddress(host, port).fold(_ -> fail("bad address"), a -> a);
    }

    private QuicClusterNetwork createNetwork(NodeId selfId, Set<NodeId> coreNodes) {
        return createNetwork(selfId, coreNodes, MessageRouter.mutable());
    }

    private QuicClusterNetwork createNetwork(NodeId selfId, Set<NodeId> coreNodes, MessageRouter router) {
        return createNetwork(NodeInfo.nodeInfo(selfId, addressOf("127.0.0.1", 19998)), coreNodes, router);
    }

    private QuicClusterNetwork createNetwork(NodeInfo selfInfo, Set<NodeId> coreNodes) {
        return createNetwork(selfInfo, coreNodes, MessageRouter.mutable());
    }

    private QuicClusterNetwork createNetwork(NodeInfo selfInfo, Set<NodeId> coreNodes, MessageRouter router) {
        var topology = new OverridableTopology(selfInfo, coreNodes);
        var network = new QuicClusterNetwork(topology, codec, codec, router,
                                             serverSsl, clientSsl);
        networks.add(network);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    /// Topology stub with an explicit `coreNodes()` override so a tombstoned peer can be made
    /// absent from the FSM-membership set (forcing the readmit to ride the raw-SWIM liveness gate).
    private static final class OverridableTopology implements TopologyObserver {
            @Override
            public org.pragmatica.lang.Unit setConsensusMembership(java.util.function.Predicate<NodeId> membership) { return org.pragmatica.lang.Unit.unit(); }

        private final NodeInfo selfInfo;
        private final Set<NodeId> coreNodes;

        OverridableTopology(NodeInfo selfInfo, Set<NodeId> coreNodes) {
            this.selfInfo = selfInfo;
            this.coreNodes = coreNodes;
        }

        @Override public NodeInfo self() {return selfInfo;}
        @Override public Option<NodeInfo> get(NodeId id) {
            return id.equals(selfInfo.id()) ? Option.some(selfInfo) : Option.empty();
        }
        @Override public int clusterSize() {return Math.max(coreNodes.size(), 1);}
        @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
        @Override public TimeSpan helloTimeout() {return HELLO_TIMEOUT;}
        @Override public Option<TlsConfig> tls() {return Option.empty();}
        @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
        @Override public List<NodeId> topology() {return List.copyOf(coreNodes);}
        @Override public Set<NodeId> coreNodes() {return coreNodes;}
        @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
        @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
        @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
        @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
