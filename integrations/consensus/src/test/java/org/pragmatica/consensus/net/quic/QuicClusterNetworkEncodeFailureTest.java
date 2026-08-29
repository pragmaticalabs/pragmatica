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
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/// The #492 orphaned-codec class, made LOUD at the transport.
///
/// An encode throw used to ESCAPE the send path: the caller's promise died unresolved on synchronous
/// sends and periodic broadcast tasks were silently cancelled, producing ZERO log lines across four
/// cloud runs while every entity forward vanished. The fix wraps both encode sites and returns a typed
/// [WriteOutcome.EncodeFailed], which every existing `isSent()` check already fails fast on.
///
/// Both tests here drive the SAME seam (`writeToStream`) over an already-open lane, so the only
/// variable between them is whether the message's type has a registered codec. That is what makes the
/// pair evidence rather than two independent assertions.
@Timeout(30)
class QuicClusterNetworkEncodeFailureTest {

    /// A wired message whose type is in NO codec registry — the orphaned-codec shape. Rides CONTROL so
    /// it takes the same lane the registered arming message does.
    record UnregisteredWire(String payload) implements Message.Wired {
        @Override
        public StreamType streamType() {
            return StreamType.CONTROL;
        }
    }

    /// The failure half: nothing is written, and the outcome NAMES the type whose codec was missing —
    /// which is the single piece of information the four silent cloud runs lacked.
    @Test
    void writeToStream_messageWithNoRegisteredCodec_reportsEncodeFailedNamingTheType() {
        var network = network();
        var peerId = new NodeId("orphaned-codec-peer");
        var laneStream = writableStream();
        var connection = connectionWithLane(peerId, laneStream);

        network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));

        var outcome = network.writeToStreamForTests(peerId, new UnregisteredWire("payload"), connection);

        assertThat(outcome).as("an unencodable message must produce a TYPED refusal, not an escaping throw")
                           .isInstanceOf(WriteOutcome.EncodeFailed.class);
        assertThat(((WriteOutcome.EncodeFailed) outcome).messageType())
            .as("the outcome must name the class whose codec is missing")
            .isEqualTo(UnregisteredWire.class.getName());
        assertThat(outcome.isSent()).as("callers gate on isSent() — an encode failure must not read as sent")
                                    .isFalse();
        verify(laneStream, never()).writeAndFlush(any());
    }

    /// The arming half. Same network, same lane, same seam — only the codec registration differs. A
    /// registered type must still take the normal path, or the test above would pass against a
    /// transport that had simply stopped writing altogether.
    @Test
    void writeToStream_messageWithARegisteredCodec_stillSendsNormally() {
        var network = network();
        var peerId = new NodeId("registered-codec-peer");
        var laneStream = writableStream();
        var connection = connectionWithLane(peerId, laneStream);

        network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));

        var outcome = network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);

        assertThat(outcome).as("KeepAlive is registered in NetCodecs — the healthy path is untouched")
                           .isInstanceOf(WriteOutcome.Sent.class);
        verify(laneStream, times(1)).writeAndFlush(any());
    }

    // --- Helpers ---

    /// A mock QUIC lane stream that is active + writable and returns a self-listening future.
    private static QuicStreamChannel writableStream() {
        var stream = mock(QuicStreamChannel.class);
        var future = mock(ChannelFuture.class);

        lenient().when(future.addListener(any())).thenReturn(future);
        lenient().when(stream.writeAndFlush(any())).thenReturn(future);
        lenient().when(stream.isActive()).thenReturn(true);
        lenient().when(stream.isWritable()).thenReturn(true);

        return stream;
    }

    /// An active connection with the CONTROL lane ALREADY registered, so both tests reach the encode
    /// site directly — no lazy-open, no eviction, nothing else in the way of the property under test.
    private static QuicPeerConnection connectionWithLane(NodeId peerId, QuicStreamChannel laneStream) {
        var chan = mock(QuicChannel.class);

        lenient().when(chan.isActive()).thenReturn(true);

        var connection = QuicPeerConnection.quicPeerConnection(peerId, chan);

        connection.laneOpener(QuicPeerConnection.LaneOpener.noop());
        connection.registerStream(StreamType.CONTROL, laneStream);

        return connection;
    }

    private static PeerState connectedPeerState(NodeId peerId, QuicPeerConnection connection) {
        var past = System.nanoTime() - Duration.ofMinutes(1).toNanos();
        var state = PeerState.peerState(peerId, past);

        state.beginConnecting(past);
        state.attach(connection, past);
        state.markInbound(System.nanoTime());

        return state;
    }

    private QuicClusterNetwork network() {
        var codec = combinedCodec();
        var nodeAddress = NodeAddress.nodeAddress("127.0.0.1", 19994)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
        var selfInfo = NodeInfo.nodeInfo(new NodeId("self-encode"), nodeAddress);

        return new QuicClusterNetwork(stubTopology(selfInfo), codec, codec,
                                      MessageRouter.mutable(), serverSsl(), clientSsl());
    }

    private static SliceCodec combinedCodec() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);

        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), all);
    }

    private static QuicSslContext serverSsl() {
        return QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                              .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
    }

    private static QuicSslContext clientSsl() {
        return QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
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
            @Override public List<NodeId> topology() {return List.of(self.id());}
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }
}
