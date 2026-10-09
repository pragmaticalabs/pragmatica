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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamChannelConfig;
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
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/// #1996: a request frame held in the offline buffer is dropped at the reattach flush once its caller has given up,
/// and is still delivered when it is flushed before that moment.
///
/// The frame is a real, codec-registered [LaneProbe]: it must ENCODE, because a frame that cannot encode is never written
/// whether or not it expired, which would make every `never().writeAndFlush` below vacuous: the transport is path-agnostic, so every request path
/// (invocation, HTTP forward, stream forward, replication, ForwardApply) is expired by this one mechanism; the
/// per-path tests in the owning modules pin that each path hands THIS mechanism its caller's real wait.
@Timeout(30)
class QuicClusterNetworkOfflineTtlTest {
    private static final TimeSpan LONG_WAIT = TimeSpan.timeSpan(1).hours();
    private static final TimeSpan SHORT_WAIT = TimeSpan.timeSpan(40).millis();
    private static final long PAST_SHORT_WAIT_NANOS = Duration.ofMillis(250).toNanos();

    @Test
    void frameFlushedAfterItsCallerGaveUp_isDropped_andReportedWithCountAndPath() {
        var routed = new CopyOnWriteArrayList<NetworkServiceMessage.OfflineFramesExpired>();
        var network = network(routed);
        var peerId = new NodeId("peer-late");
        var state = connectingPeer(network, peerId);
        var stream = writableStream();
        var connection = connectionWith(peerId, stream);

        network.send(peerId, request(peerId), SHORT_WAIT);
        network.send(peerId, request(peerId), SHORT_WAIT);
        assertThat(network.offlineBufferSizeForTests(peerId)).as("both frames are held while the peer is down").isEqualTo(2);

        parkPast(PAST_SHORT_WAIT_NANOS);
        network.drainOfflineBufferForTests(state, connection);

        verify(stream, never()).writeAndFlush(any());
        assertThat(routed).hasSize(1);
        assertThat(routed.getFirst().nodeId()).isEqualTo(peerId);
        assertThat(routed.getFirst().count()).isEqualTo(2);
        assertThat(routed.getFirst().byPath()).containsExactly(java.util.Map.entry("LaneProbe", 2));
        assertThat(network.transportMetrics().get("quic_offline_expired_total")).isEqualTo(2L);
        assertThat(network.offlineBufferSizeForTests(peerId)).as("nothing is left to deliver later").isZero();
    }

    /// The control: the same frame, same path, flushed before its caller gives up, is delivered and nothing is reported.
    @Test
    void frameFlushedBeforeItsCallerGaveUp_isStillDelivered() {
        var routed = new CopyOnWriteArrayList<NetworkServiceMessage.OfflineFramesExpired>();
        var network = network(routed);
        var peerId = new NodeId("peer-in-time");
        var state = connectingPeer(network, peerId);
        var stream = writableStream();

        network.send(peerId, request(peerId), LONG_WAIT);
        network.drainOfflineBufferForTests(state, connectionWith(peerId, stream));

        verify(stream, times(1)).writeAndFlush(any());
        assertThat(routed).isEmpty();
        assertThat(network.transportMetrics().get("quic_offline_expired_total")).isEqualTo(0L);
    }

    /// A mixed buffer is split frame by frame: the expired one is dropped, the live one is delivered.
    @Test
    void mixedBuffer_dropsOnlyTheExpiredFrame() {
        var routed = new CopyOnWriteArrayList<NetworkServiceMessage.OfflineFramesExpired>();
        var network = network(routed);
        var peerId = new NodeId("peer-mixed");
        var state = connectingPeer(network, peerId);
        var stream = writableStream();

        network.send(peerId, request(peerId), SHORT_WAIT);
        network.send(peerId, request(peerId), LONG_WAIT);
        parkPast(PAST_SHORT_WAIT_NANOS);
        network.drainOfflineBufferForTests(state, connectionWith(peerId, stream));

        verify(stream, times(1)).writeAndFlush(any());
        assertThat(routed).hasSize(1);
        assertThat(routed.getFirst().count()).isEqualTo(1);
    }

    /// No request frame outlives the cluster-wide cap, whatever wait its caller asked for.
    @Test
    void callerWaitLongerThanTheCap_isClampedToTheCap() {
        var routed = new CopyOnWriteArrayList<NetworkServiceMessage.OfflineFramesExpired>();
        var network = network(routed);
        var peerId = new NodeId("peer-capped");
        var state = connectingPeer(network, peerId);
        var stream = writableStream();

        network.setOfflineBufferCap(SHORT_WAIT);
        network.send(peerId, request(peerId), LONG_WAIT);
        parkPast(PAST_SHORT_WAIT_NANOS);
        network.drainOfflineBufferForTests(state, connectionWith(peerId, stream));

        verify(stream, never()).writeAndFlush(any());
        assertThat(routed).hasSize(1);
    }

    /// A frame with NO caller deadline (state-convergence traffic, a response, a fire-and-forget call) goes through plain `send`,
    /// and nothing the transport buffers is held for ever: it is bounded by the cap alone.
    @Test
    void plainSend_isBoundedByTheCap_notHeldForEver() {
        var routed = new CopyOnWriteArrayList<NetworkServiceMessage.OfflineFramesExpired>();
        var network = network(routed);
        var peerId = new NodeId("peer-plain");
        var state = connectingPeer(network, peerId);
        var stream = writableStream();

        network.setOfflineBufferCap(SHORT_WAIT);
        network.send(peerId, request(peerId));
        parkPast(PAST_SHORT_WAIT_NANOS);
        network.drainOfflineBufferForTests(state, connectionWith(peerId, stream));

        verify(stream, never()).writeAndFlush(any());
        assertThat(routed).hasSize(1);
    }

    /// The outcome-tracking variant (the DHT quorum path) buffers through the same dispatch, so it is bounded the same way.
    @Test
    void sendOutcome_isBoundedByTheCap() {
        var routed = new CopyOnWriteArrayList<NetworkServiceMessage.OfflineFramesExpired>();
        var network = network(routed);
        var peerId = new NodeId("peer-outcome");
        var state = connectingPeer(network, peerId);
        var stream = writableStream();

        network.setOfflineBufferCap(SHORT_WAIT);
        network.sendOutcome(peerId, request(peerId));
        parkPast(PAST_SHORT_WAIT_NANOS);
        network.drainOfflineBufferForTests(state, connectionWith(peerId, stream));

        verify(stream, never()).writeAndFlush(any());
        assertThat(routed).hasSize(1);
    }

    /// The control for the two above: a plain frame flushed inside the cap is still delivered.
    @Test
    void plainSend_flushedInsideTheCap_isDelivered() {
        var routed = new CopyOnWriteArrayList<NetworkServiceMessage.OfflineFramesExpired>();
        var network = network(routed);
        var peerId = new NodeId("peer-plain-in-time");
        var state = connectingPeer(network, peerId);
        var stream = writableStream();

        network.send(peerId, request(peerId));
        network.drainOfflineBufferForTests(state, connectionWith(peerId, stream));

        verify(stream, times(1)).writeAndFlush(any());
        assertThat(routed).isEmpty();
    }

    /// A frame whose connection dies mid-write goes back into the buffer with its ORIGINAL instant, not a fresh one.
    @Test
    void frameRebufferedAfterDeadConnection_keepsItsOriginalExpiry() {
        var routed = new CopyOnWriteArrayList<NetworkServiceMessage.OfflineFramesExpired>();
        var network = network(routed);
        var peerId = new NodeId("peer-dead-write");
        var deadConnection = deadConnection(peerId);
        var state = PeerState.peerState(peerId, System.nanoTime());

        state.beginConnecting(System.nanoTime());
        state.attach(deadConnection, System.nanoTime());
        network.seedPeerForTests(peerId, state);

        network.send(peerId, request(peerId), SHORT_WAIT);
        assertThat(network.offlineBufferSizeForTests(peerId)).as("the dead write re-buffered the frame").isEqualTo(1);

        parkPast(PAST_SHORT_WAIT_NANOS);
        var stream = writableStream();

        network.drainOfflineBufferForTests(state, connectionWith(peerId, stream));

        verify(stream, never()).writeAndFlush(any());
        assertThat(routed).hasSize(1);
    }

    private static LaneProbe request(NodeId peerId) {
        return LaneProbe.laneProbe(peerId, StreamType.CONSENSUS, "request");
    }

    private static PeerState connectingPeer(QuicClusterNetwork network, NodeId peerId) {
        var state = PeerState.peerState(peerId, System.nanoTime());

        state.beginConnecting(System.nanoTime());
        network.seedPeerForTests(peerId, state);

        return state;
    }

    private static void parkPast(long nanos) {
        var deadline = System.nanoTime() + nanos;

        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
    }

    private static QuicPeerConnection connectionWith(NodeId peerId, QuicStreamChannel stream) {
        var channel = mock(QuicChannel.class);

        lenient().when(channel.isActive()).thenReturn(true);
        var connection = QuicPeerConnection.quicPeerConnection(peerId, channel);

        connection.registerStream(StreamType.CONSENSUS, stream);

        return connection;
    }

    private static QuicPeerConnection deadConnection(NodeId peerId) {
        var channel = mock(QuicChannel.class);

        lenient().when(channel.isActive()).thenReturn(false);

        return QuicPeerConnection.quicPeerConnection(peerId, channel);
    }

    private static QuicStreamChannel writableStream() {
        var stream = mock(QuicStreamChannel.class);
        var future = mock(ChannelFuture.class);

        lenient().when(future.addListener(any())).thenReturn(future);
        lenient().when(stream.writeAndFlush(any())).thenReturn(future);
        lenient().when(stream.config()).thenReturn(mock(QuicStreamChannelConfig.class));
        lenient().when(stream.isActive()).thenReturn(true);
        lenient().when(stream.isWritable()).thenReturn(true);

        return stream;
    }

    private static QuicClusterNetwork network(List<NetworkServiceMessage.OfflineFramesExpired> routed) {
        var codec = LaneProbe.codec();
        var address = NodeAddress.nodeAddress("127.0.0.1", 19994).fold(_ -> fail("bad address"), a -> a);
        var selfInfo = NodeInfo.nodeInfo(new NodeId("self-offline-ttl"), address);
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.OfflineFramesExpired.class, routed::add);

        return new QuicClusterNetwork(stubTopology(selfInfo), codec, codec, router, serverSsl(), clientSsl());
    }

    private static QuicSslContext serverSsl() {
        return QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).fold(_ -> fail("Server SSL failed"), ssl -> ssl);
    }

    private static QuicSslContext clientSsl() {
        return QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client")).fold(_ -> fail("Client SSL failed"), ssl -> ssl);
    }

    private static TopologyObserver stubTopology(NodeInfo self) {
        return new TopologyObserver() {
            @Override
            public Unit setConsensusMembership(java.util.function.Predicate<NodeId> membership) { return Unit.unit(); }

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
            @Override public List<NodeId> topology() {return List.of(self.id());}
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }
}
