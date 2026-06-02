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

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Covers the application-layer QUIC keep-alive in [QuicClusterNetwork]: the periodic tick
/// pings connected peers on the dedicated KEEPALIVE stream, an inbound Ping is answered with a
/// Pong, an inbound Pong resets the miss count, and a peer whose pings go unacked past the
/// threshold has its half-open link evicted.
@Timeout(30)
class QuicClusterNetworkKeepAliveTest {
    private static final NodeId PEER = new NodeId("peer-1");

    @Test
    void keepAliveTick_connectedPeer_pingsOnKeepAliveStream() {
        var network = network();
        var stream = writableStream();
        var conn = connectedPeer(network, stream);
        when(conn.isActive()).thenReturn(true);

        network.keepAliveTickForTest();

        // First tick mints seq=1 and writes a Ping on the KEEPALIVE stream.
        verify(stream, times(1)).writeAndFlush(any());
    }

    @Test
    void keepAliveTick_inactiveConnection_evictsImmediately() {
        var network = network();
        var stream = writableStream();
        var conn = connectedPeer(network, stream);
        when(conn.isActive()).thenReturn(false);

        network.keepAliveTickForTest();

        // Dead channel is evicted before any ping is written.
        verify(stream, never()).writeAndFlush(any());
        assertThat(network.peerPhaseForTest(PEER)).isEqualTo(PeerState.Phase.EVICTED);
    }

    @Test
    void keepAliveTick_unackedPastThreshold_evictsHalfOpenLink() {
        var network = network();
        var stream = writableStream();
        var conn = connectedPeer(network, stream);
        when(conn.isActive()).thenReturn(true);

        // Three ticks with no Pong → missCount reaches 3 → eviction on the third tick.
        network.keepAliveTickForTest();
        assertThat(network.peerPhaseForTest(PEER)).isEqualTo(PeerState.Phase.CONNECTED);
        network.keepAliveTickForTest();
        assertThat(network.peerPhaseForTest(PEER)).isEqualTo(PeerState.Phase.CONNECTED);
        network.keepAliveTickForTest();

        assertThat(network.peerPhaseForTest(PEER))
            .as("unacked pings past the threshold evict the half-open link")
            .isEqualTo(PeerState.Phase.EVICTED);
    }

    @Test
    void keepAliveTick_pongResetsMissCount_noEviction() {
        var network = network();
        var stream = writableStream();
        var conn = connectedPeer(network, stream);
        when(conn.isActive()).thenReturn(true);

        // Tick → seq=1 outstanding. Ack it. Tick → seq=2 outstanding. Ack it. Never crosses 3.
        network.keepAliveTickForTest();
        network.onMessageReceivedForTest(PEER, new KeepAliveMessage.Pong(1L));
        network.keepAliveTickForTest();
        network.onMessageReceivedForTest(PEER, new KeepAliveMessage.Pong(2L));
        network.keepAliveTickForTest();

        assertThat(network.peerPhaseForTest(PEER))
            .as("a peer that keeps acking pings is never evicted")
            .isEqualTo(PeerState.Phase.CONNECTED);
    }

    @Test
    void onMessageReceived_ping_repliesPongOnKeepAliveStream() {
        var network = network();
        var stream = writableStream();
        connectedPeer(network, stream);

        network.onMessageReceivedForTest(PEER, new KeepAliveMessage.Ping(99L));

        // The reply Pong is written back on the peer's KEEPALIVE stream.
        verify(stream, atLeastOnce()).writeAndFlush(any());
    }

    @Test
    void keepAliveTick_keepAliveStreamAbsent_neverEvictsHealthyLink() {
        var network = network();
        var quicChannel = mock(QuicChannel.class);
        when(quicChannel.isActive()).thenReturn(true);
        var conn = QuicPeerConnection.quicPeerConnection(PEER, quicChannel);
        // Deliberately register NO KEEPALIVE stream — models the window before the dedicated
        // stream has opened, or a best-effort open that failed.
        var state = PeerState.peerState(PEER, System.nanoTime());
        state.beginConnecting(System.nanoTime());
        var _ = state.attach(conn, PEER, System.nanoTime());
        network.seedPeerForTests(PEER, state);

        // Five ticks well past the miss threshold: with no KEEPALIVE stream the loop must NOT
        // advance the sequence or accumulate misses, so a healthy CONSENSUS link is never evicted.
        network.keepAliveTickForTest();
        network.keepAliveTickForTest();
        network.keepAliveTickForTest();
        network.keepAliveTickForTest();
        network.keepAliveTickForTest();

        assertThat(network.peerPhaseForTest(PEER))
            .as("a connected peer whose keep-alive stream never opened must not be evicted")
            .isEqualTo(PeerState.Phase.CONNECTED);
    }

    // --- Helpers ---

    private QuicStreamChannel writableStream() {
        var ch = mock(QuicStreamChannel.class);
        var future = mock(ChannelFuture.class);
        when(future.addListener(any())).thenReturn(future);
        when(ch.writeAndFlush(any())).thenReturn(future);
        when(ch.isActive()).thenReturn(true);
        when(ch.isWritable()).thenReturn(true);
        return ch;
    }

    /// Seed PEER as CONNECTED with the given mock as its KEEPALIVE stream. Returns the mock
    /// QuicChannel backing the connection so the test can toggle `isActive()`.
    private QuicChannel connectedPeer(QuicClusterNetwork network, QuicStreamChannel keepAliveStream) {
        var quicChannel = mock(QuicChannel.class);
        when(quicChannel.isActive()).thenReturn(true);
        var conn = QuicPeerConnection.quicPeerConnection(PEER, quicChannel);
        conn.registerStream(StreamType.KEEPALIVE, keepAliveStream);
        var state = PeerState.peerState(PEER, System.nanoTime());
        state.beginConnecting(System.nanoTime());
        state.attach(conn, PEER, System.nanoTime());
        network.seedPeerForTests(PEER, state);
        return quicChannel;
    }

    private QuicClusterNetwork network() {
        var codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), QuicCodecs.CODECS);
        var nodeAddress = NodeAddress.nodeAddress("127.0.0.1", 19996)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
        var selfInfo = NodeInfo.nodeInfo(new NodeId("self"), nodeAddress);
        return new QuicClusterNetwork(stubTopology(selfInfo), codec, codec, MessageRouter.mutable(),
                                      serverSsl(), clientSsl());
    }

    private static QuicSslContext serverSsl() {
        return QuicTlsProvider.serverContext(TlsConfig.selfSignedServer())
                              .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
    }

    private static QuicSslContext clientSsl() {
        return QuicTlsProvider.clientContext(TlsConfig.insecureClient())
                              .fold(_ -> fail("Client SSL failed"), ssl -> ssl);
    }

    private static TopologyObserver stubTopology(NodeInfo self) {
        return new TopologyObserver() {
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 1;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return TimeSpan.timeSpan(1).seconds();}
            @Override public TimeSpan helloTimeout() {return TimeSpan.timeSpan(5).seconds();}
            @Override public Option<TlsConfig> tls() {return Option.empty();}
            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
            @Override public List<NodeId> topology() {var r = new ArrayList<NodeId>(); r.add(self.id()); return r;}
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }
}
