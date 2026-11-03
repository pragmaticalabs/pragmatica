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
import org.pragmatica.consensus.net.WriteOutcome;
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

/// #2583 P1: both unicast `send` overloads return the dispatch path's own [WriteOutcome], never a constant.
///
/// Every case runs on a network that was built but never started, so no frame can reach a wire: each
/// outcome below is the synchronous local verdict of the dispatch, and the `Sent` controls prove the
/// `NoPeerState` assertions are not satisfied by an overload that always refuses.
///
/// `BackpressureRefused` is pinned two ways: through a real FORWARD lane that is not writable (a connected peer, a
/// mocked stream, nothing written), and through #2574's outbound refusal filter, the seam the loopback integration tests
/// use because a real lane cannot be made to refuse on demand there.
@Timeout(30)
class QuicSendReturnsOutcomeTest {
    private static final NodeId SELF = new NodeId("self-send-outcome");
    private static final TimeSpan CALLER_WAIT = TimeSpan.timeSpan(1).seconds();

    @Test
    void send_toSelfBeforeStart_reportsNoPeerState() {
        var network = network();

        assertThat(outcome(network.send(SELF, probe())))
            .as("no loopback loop is pinned before start: the self-send is dropped, and the outcome says so")
            .isEqualTo(new WriteOutcome.NoPeerState(SELF));
    }

    @Test
    void sendWithTtl_toSelfBeforeStart_reportsNoPeerState() {
        var network = network();

        assertThat(outcome(network.send(SELF, probe(), CALLER_WAIT)))
            .isEqualTo(new WriteOutcome.NoPeerState(SELF));
    }

    @Test
    void send_toAbsentNonMemberPeer_reportsNoPeerState() {
        var network = network();
        var stranger = new NodeId("stranger");

        assertThat(outcome(network.send(stranger, probe())))
            .as("a peer with no PeerState that is not a core member is dropped, not buffered")
            .isEqualTo(new WriteOutcome.NoPeerState(stranger));
    }

    @Test
    void sendWithTtl_toAbsentNonMemberPeer_reportsNoPeerState() {
        var network = network();
        var stranger = new NodeId("stranger");

        assertThat(outcome(network.send(stranger, probe(), CALLER_WAIT)))
            .isEqualTo(new WriteOutcome.NoPeerState(stranger));
    }

    /// Control: a peer that is connecting buffers the frame, so the same dispatch reports `Sent`.
    @Test
    void send_toConnectingPeer_reportsSent() {
        var network = network();
        var peer = connectingPeer(network, new NodeId("peer-connecting"));

        assertThat(outcome(network.send(peer, probe()))).isEqualTo(new WriteOutcome.Sent(peer));
        assertThat(outcome(network.send(peer, probe(), CALLER_WAIT))).isEqualTo(new WriteOutcome.Sent(peer));
        assertThat(network.offlineBufferSizeForTests(peer)).as("both frames are held in the offline buffer").isEqualTo(2);
    }

    /// The blackholed network drops every frame silently and reports `Sent`: a silently-dead node does not
    /// tell its callers. The same send unblackholed reports `NoPeerState`, so `Sent` here comes from the
    /// blackhole branch.
    @Test
    void send_whileBlackholed_reportsSent() {
        var network = network();
        var stranger = new NodeId("stranger");

        network.blackhole(true);

        assertThat(outcome(network.send(stranger, probe()))).isEqualTo(new WriteOutcome.Sent(stranger));
        assertThat(outcome(network.send(stranger, probe(), CALLER_WAIT))).isEqualTo(new WriteOutcome.Sent(stranger));

        network.blackhole(false);

        assertThat(outcome(network.send(stranger, probe()))).isEqualTo(new WriteOutcome.NoPeerState(stranger));
    }

    /// The CTO-requested "refused" case as rc5 can produce it without a seam: a FORWARD frame to a connected peer whose
    /// FORWARD stream is not writable is refused at the watermark, never written.
    @Test
    void send_forwardFrameToConnectedPeerWithUnwritableLane_reportsBackpressureRefused() {
        var network = network();
        var peer = new NodeId("peer-unwritable");
        var stream = forwardStream(false);

        connectedPeer(network, peer, stream);

        assertThat(outcome(network.send(peer, forwardProbe("refused-plain"))))
            .isEqualTo(new WriteOutcome.BackpressureRefused(peer));
        assertThat(outcome(network.send(peer, forwardProbe("refused-ttl"), CALLER_WAIT)))
            .isEqualTo(new WriteOutcome.BackpressureRefused(peer));
        verify(stream, never()).writeAndFlush(any());
    }

    /// Control for the case above: the same connected peer with a writable FORWARD lane reports `Sent` and writes.
    @Test
    void send_forwardFrameToConnectedPeerWithWritableLane_reportsSent_andWrites() {
        var network = network();
        var peer = new NodeId("peer-writable");
        var stream = forwardStream(true);

        connectedPeer(network, peer, stream);

        assertThat(outcome(network.send(peer, forwardProbe("sent-plain")))).isEqualTo(new WriteOutcome.Sent(peer));
        assertThat(outcome(network.send(peer, forwardProbe("sent-ttl"), CALLER_WAIT)))
            .isEqualTo(new WriteOutcome.Sent(peer));
        verify(stream, times(2)).writeAndFlush(any());
    }

    /// #2574's test-only filter refuses a matching unicast send before any write, on both overloads; an unmatched send to
    /// the same peer is buffered and reports `Sent`.
    @Test
    void send_matchedByTheOutboundRefusalFilter_reportsBackpressureRefused_unmatchedReportsSent() {
        var network = network();
        var peer = connectingPeer(network, new NodeId("peer-filtered"));

        network.setOutboundRefusalFilter((target, message) -> target.equals(peer)
                                                              && message instanceof LaneProbe lane
                                                              && lane.marker().startsWith("refused"));

        assertThat(outcome(network.send(peer, forwardProbe("refused-a")))).isEqualTo(new WriteOutcome.BackpressureRefused(peer));
        assertThat(outcome(network.send(peer, forwardProbe("refused-b"), CALLER_WAIT)))
            .isEqualTo(new WriteOutcome.BackpressureRefused(peer));
        assertThat(network.offlineBufferSizeForTests(peer)).as("a refused frame is not buffered").isZero();
        assertThat(outcome(network.send(peer, forwardProbe("passed")))).isEqualTo(new WriteOutcome.Sent(peer));
        assertThat(network.offlineBufferSizeForTests(peer)).isEqualTo(1);
    }

    private static LaneProbe forwardProbe(String marker) {
        return LaneProbe.laneProbe(SELF, StreamType.FORWARD, marker);
    }

    private static QuicStreamChannel forwardStream(boolean writable) {
        var stream = mock(QuicStreamChannel.class);
        var future = mock(ChannelFuture.class);

        lenient().when(future.addListener(any())).thenReturn(future);
        lenient().when(stream.writeAndFlush(any())).thenReturn(future);
        lenient().when(stream.isActive()).thenReturn(true);
        lenient().when(stream.isWritable()).thenReturn(writable);

        return stream;
    }

    private static void connectedPeer(QuicClusterNetwork network, NodeId peerId, QuicStreamChannel forwardStream) {
        var channel = mock(QuicChannel.class);

        lenient().when(channel.isActive()).thenReturn(true);

        var connection = QuicPeerConnection.quicPeerConnection(peerId, channel);
        var past = System.nanoTime() - Duration.ofMinutes(1).toNanos();
        var state = PeerState.peerState(peerId, past);

        connection.registerStream(StreamType.FORWARD, forwardStream);
        state.beginConnecting(past);
        state.attach(connection, past);
        state.markInbound(System.nanoTime());
        network.seedPeerForTests(peerId, state);
    }

    private static WriteOutcome outcome(Promise<WriteOutcome> sent) {
        return sent.await(CALLER_WAIT)
                   .fold(cause -> fail("send must resolve with an outcome, never fail: " + cause.message()),
                         outcome -> outcome);
    }

    private static LaneProbe probe() {
        return LaneProbe.laneProbe(SELF, StreamType.CONSENSUS, "outcome");
    }

    private static NodeId connectingPeer(QuicClusterNetwork network, NodeId peerId) {
        var state = PeerState.peerState(peerId, System.nanoTime());

        state.beginConnecting(System.nanoTime());
        network.seedPeerForTests(peerId, state);

        return peerId;
    }

    private static QuicClusterNetwork network() {
        var codec = LaneProbe.codec();
        var address = NodeAddress.nodeAddress("127.0.0.1", 19993).fold(_ -> fail("bad address"), a -> a);
        var selfInfo = NodeInfo.nodeInfo(SELF, address);

        return new QuicClusterNetwork(stubTopology(selfInfo), codec, codec, MessageRouter.mutable(), serverSsl(), clientSsl());
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
