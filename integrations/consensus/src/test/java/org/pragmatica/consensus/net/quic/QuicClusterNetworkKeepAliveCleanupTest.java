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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Resource-cleanup coverage for the keep-alive feature: the periodic loop must be cancelled on
/// network stop (no leak / no firing against a torn-down transport), and the dedicated KEEPALIVE
/// stream must be torn down with the peer connection.
@Timeout(30)
class QuicClusterNetworkKeepAliveCleanupTest {
    private static final NodeId PEER = new NodeId("peer-1");
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();

    @Test
    void stop_cancelsKeepAliveLoop_noLongerScheduled() {
        var network = network();
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));

        awaitScheduled(network);
        assertThat(network.keepAliveScheduledForTest())
            .as("keep-alive loop is scheduled once the transport is ready")
            .isTrue();

        network.stop().await(AWAIT_TIMEOUT);

        assertThat(network.keepAliveScheduledForTest())
            .as("keep-alive loop must be cancelled on stop — otherwise it leaks and keeps firing")
            .isFalse();
    }

    /// The start-success callbacks (which schedule the keep-alive loop) resolve asynchronously
    /// relative to the start promise; poll briefly for the scheduled state.
    private static void awaitScheduled(QuicClusterNetwork network) {
        var deadline = System.nanoTime() + AWAIT_TIMEOUT.nanos();
        while (System.nanoTime() < deadline && !network.keepAliveScheduledForTest()) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeSpan.timeSpan(5).millis().nanos());
        }
    }

    @Test
    void peerConnectionClose_closesKeepAliveStreamSlot() {
        var quicChannel = mock(QuicChannel.class);
        when(quicChannel.isActive()).thenReturn(true);
        when(quicChannel.close()).thenReturn(mock(ChannelFuture.class));
        var conn = QuicPeerConnection.quicPeerConnection(PEER, quicChannel);

        var keepAliveStream = mock(QuicStreamChannel.class);
        when(keepAliveStream.isActive()).thenReturn(true);
        when(keepAliveStream.close()).thenReturn(mock(ChannelFuture.class));
        conn.registerStream(StreamType.KEEPALIVE, keepAliveStream);
        assertThat(conn.stream(StreamType.KEEPALIVE).isPresent()).isTrue();

        conn.close().await(AWAIT_TIMEOUT);

        verify(keepAliveStream, times(1)).close();
        assertThat(conn.stream(StreamType.KEEPALIVE).isEmpty())
            .as("the KEEPALIVE stream slot must be nulled after close")
            .isTrue();
    }

    private QuicClusterNetwork network() {
        var codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), QuicCodecs.CODECS);
        var nodeAddress = NodeAddress.nodeAddress("127.0.0.1", 19995)
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
