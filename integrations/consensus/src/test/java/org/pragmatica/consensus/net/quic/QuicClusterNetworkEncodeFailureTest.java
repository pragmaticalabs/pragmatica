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

    /// #1727 (M2) idle pin, found by v-2023: the transport's own KeepAlive rides the CONTROL lane through the
    /// same write path, so noting CONTROL writes re-opened the kick window every second and the kick never
    /// went idle (9.8 kicks/s on an idle link). The KeepAlive goes through the real send path here; the
    /// DATA-lane write below is the control showing the same fixture does kick.
    @Test
    void keepAliveOnTheControlLane_doesNotOpenTheKickWindow_butADataLaneWriteDoes() throws Exception {
        var loop = new io.netty.channel.DefaultEventLoop();

        try {
            var network = network();
            var peerId = new NodeId("idle-kick-peer");
            var laneStream = writableStream();
            var channel = mock(QuicChannel.class);
            @SuppressWarnings("unchecked")
            var attribute = (io.netty.util.Attribute<QuicPeerConnection>) mock(io.netty.util.Attribute.class);

            lenient().when(channel.isActive()).thenReturn(true);
            lenient().when(channel.eventLoop()).thenReturn(loop);
            var connection = QuicPeerConnection.quicPeerConnection(peerId, channel);

            lenient().when(attribute.get()).thenReturn(connection);
            lenient().when(channel.attr(PeerOpenedLaneRouter.PEER_CONNECTION)).thenReturn(attribute);
            lenient().when(laneStream.parent()).thenReturn(channel);
            connection.laneOpener(QuicPeerConnection.LaneOpener.noop());
            connection.registerStream(StreamType.CONTROL, laneStream);
            connection.activityKick(new byte[] {1}, () -> false, 20, 300);
            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));

            var outcome = network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);
            Thread.sleep(200);

            assertThat(outcome).isInstanceOf(WriteOutcome.Sent.class);
            assertThat(connection.activityKicksSent()).as("a KeepAlive must not open the kick window").isZero();

            var dataStream = writableStream();

            lenient().when(dataStream.parent()).thenReturn(channel);
            connection.registerStream(StreamType.FORWARD, dataStream);
            network.writeIfWritableForTest(dataStream, new byte[] {1, 2, 3}, peerId, StreamType.FORWARD);
            Thread.sleep(200);

            assertThat(connection.activityKicksSent()).as("control: a data-lane write opens it").isPositive();
        } finally {
            loop.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /// #1727 — the immediate close of a superseded connection records itself and the lanes it put at risk
    /// (unflushed or written in the last 2 s) in the transport metrics. The accounting is pinned in
    /// [QuicSupersedeObservationTest].
    @Test
    void closingASupersededConnection_recordsTheCloseAndTheLanesAtRisk() {
        var network = network();
        var channel = mock(QuicChannel.class);

        lenient().when(channel.isActive()).thenReturn(true);
        var superseded = QuicPeerConnection.quicPeerConnection(new NodeId("sup-peer"), new NodeId("a"), channel);

        superseded.laneWriteStarted(StreamType.FORWARD);
        superseded.laneWriteStarted(StreamType.CONSENSUS);
        superseded.laneWriteCompleted(StreamType.CONSENSUS);

        network.closeSupersededConnectionForTests(superseded, QuicPeerConnection.quicPeerConnection(new NodeId("sup-peer"), new NodeId("a"), channel));

        assertThat(network.quicMetrics().supersededCloseCount()).isEqualTo(1);
        assertThat(network.quicMetrics().supersededLaneStreamsAtRiskCount()).as("FORWARD unflushed + CONSENSUS recent").isEqualTo(2);
        assertThat(network.quicMetrics().snapshot()).containsKeys("quic_superseded_closes_total", "quic_superseded_lane_streams_at_risk_total");
    }

    /// #1727 — the production write path accounts the write on the stream's owning connection: a lane write
    /// whose future has not completed is an unflushed lane at supersede time.
    @Test
    void aLaneWriteThroughTheProductionPath_isAccountedOnTheOwningConnection() {
        var network = network();
        var peerId = new NodeId("accounting-peer");
        var laneStream = writableStream();
        var channel = mock(QuicChannel.class);
        @SuppressWarnings("unchecked")
        var attribute = (io.netty.util.Attribute<QuicPeerConnection>) mock(io.netty.util.Attribute.class);
        var connection = QuicPeerConnection.quicPeerConnection(peerId, channel);

        lenient().when(attribute.get()).thenReturn(connection);
        lenient().when(channel.attr(PeerOpenedLaneRouter.PEER_CONNECTION)).thenReturn(attribute);
        lenient().when(laneStream.parent()).thenReturn(channel);

        network.writeIfWritableForTest(laneStream, new byte[] {1, 2, 3}, peerId, StreamType.FORWARD);

        assertThat(connection.laneWritesAtRisk(System.nanoTime()).unflushedLanes()).isEqualTo(1);
    }

    /// #1727 — accounting is keyed by the stream actually written. A CONTROL message that fell back to the
    /// CONSENSUS stream is a CONSENSUS write; a data-lane message written on the CONTROL stream is not counted.
    @Test
    void accountingFollowsTheStreamActuallyWritten_notTheMessagesLane() {
        var network = network();
        var peerId = new NodeId("keyed-peer");
        var channel = mock(QuicChannel.class);
        @SuppressWarnings("unchecked")
        var attribute = (io.netty.util.Attribute<QuicPeerConnection>) mock(io.netty.util.Attribute.class);
        var connection = QuicPeerConnection.quicPeerConnection(peerId, channel);
        var consensusStream = writableStream();
        var controlStream = writableStream();

        lenient().when(attribute.get()).thenReturn(connection);
        lenient().when(channel.attr(PeerOpenedLaneRouter.PEER_CONNECTION)).thenReturn(attribute);
        lenient().when(consensusStream.parent()).thenReturn(channel);
        lenient().when(controlStream.parent()).thenReturn(channel);
        lenient().when(consensusStream.config()).thenReturn(mock(io.netty.handler.codec.quic.QuicStreamChannelConfig.class));
        connection.registerStream(StreamType.CONSENSUS, consensusStream);
        connection.registerStream(StreamType.CONTROL, controlStream);

        network.writeIfWritableForTest(controlStream, new byte[] {1}, peerId, StreamType.FORWARD);

        assertThat(connection.laneWritesAtRisk(System.nanoTime()).lanesAtRisk())
            .as("a FORWARD message written on the CONTROL stream is a CONTROL write: not counted").isZero();

        network.writeIfWritableForTest(consensusStream, new byte[] {1}, peerId, StreamType.CONTROL);

        var risk = connection.laneWritesAtRisk(System.nanoTime());

        assertThat(risk.lanesAtRisk()).as("a CONTROL message on the CONSENSUS fallback stream is a CONSENSUS write").isEqualTo(1);
    }

    /// #1727 — two NON-CONTROL message lanes that both fell back to the CONSENSUS stream are one lane. (With
    /// CONTROL as one of them the count is 1 under either keying, so this uses two data lanes: keyed by the
    /// message's lane it would count 2.)
    @Test
    void twoDataLanesFallingBackToTheConsensusStream_countAsOneLane() {
        var network = network();
        var peerId = new NodeId("fallback-peer");
        var channel = mock(QuicChannel.class);
        @SuppressWarnings("unchecked")
        var attribute = (io.netty.util.Attribute<QuicPeerConnection>) mock(io.netty.util.Attribute.class);
        var connection = QuicPeerConnection.quicPeerConnection(peerId, channel);
        var consensusStream = writableStream();

        lenient().when(attribute.get()).thenReturn(connection);
        lenient().when(channel.attr(PeerOpenedLaneRouter.PEER_CONNECTION)).thenReturn(attribute);
        lenient().when(consensusStream.parent()).thenReturn(channel);
        lenient().when(consensusStream.config()).thenReturn(mock(io.netty.handler.codec.quic.QuicStreamChannelConfig.class));
        connection.registerStream(StreamType.CONSENSUS, consensusStream);

        network.writeIfWritableForTest(consensusStream, new byte[] {1}, peerId, StreamType.FORWARD);
        network.writeIfWritableForTest(consensusStream, new byte[] {1}, peerId, StreamType.DHT);

        assertThat(connection.laneWritesAtRisk(System.nanoTime()).lanesAtRisk()).isEqualTo(1);
    }

    /// #1727 (M1) pin (c) — a zero-length frame ends the lane at the receiver, so the normal writer must
    /// never produce one. Same seam and lane as the pair above; only the serializer differs (it encodes
    /// to zero bytes). The registered-codec test above is the control that the path otherwise writes.
    @Test
    void writeToStream_messageEncodingToZeroBytes_reportsEncodeFailed_andWritesNothing() {
        var codec = combinedCodec();
        var emptying = new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> byte[] encode(T object) {
                return new byte[0];
            }

            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
                codec.write(byteBuf, object);
            }
        };
        var network = network(emptying);
        var peerId = new NodeId("empty-encode-peer");
        var laneStream = writableStream();
        var connection = connectionWithLane(peerId, laneStream);

        network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));

        var outcome = network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);

        assertThat(outcome).as("an empty frame would end the lane mid-stream — it must be refused, not written")
                           .isInstanceOf(WriteOutcome.EncodeFailed.class);
        verify(laneStream, never()).writeAndFlush(any());
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
        return network(combinedCodec());
    }

    private QuicClusterNetwork network(org.pragmatica.serialization.Serializer serializer) {
        var codec = combinedCodec();
        var nodeAddress = NodeAddress.nodeAddress("127.0.0.1", 19994)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
        var selfInfo = NodeInfo.nodeInfo(new NodeId("self-encode"), nodeAddress);

        return new QuicClusterNetwork(stubTopology(selfInfo), serializer, codec,
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
            @Override
            public org.pragmatica.lang.Unit setConsensusMembership(java.util.function.Predicate<NodeId> membership) { return org.pragmatica.lang.Unit.unit(); }

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
