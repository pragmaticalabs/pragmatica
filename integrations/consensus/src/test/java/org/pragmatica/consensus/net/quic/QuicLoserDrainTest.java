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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;

import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.DefaultEventLoop;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// #2030 — the loser of a dual-dial race is closed AFTER what is already written on it has flushed. A burst is written
/// on a lane of a live connection and the connection is drained-closed at once; every frame must still arrive, and the
/// connection must then be closed (the drain is bounded, not a leak).
@Timeout(60)
class QuicLoserDrainTest {
    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final NodeId CLIENT_NODE = NodeId.randomNodeId();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(15).seconds();
    private static final int BURST = 4_000;

    private static final NodeId LOW = new NodeId("aaa-low");
    private static final NodeId HIGH = new NodeId("zzz-high");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private QuicClusterServer server;
    private QuicClusterClient client;

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
        if (client != null) {
            client.close().await(AWAIT);
        }
        if (server != null) {
            server.stop().await(AWAIT);
        }
    }

    @Test
    void closeAfterDrain_burstWrittenJustBefore_isDeliveredInFullAndTheConnectionThenCloses() {
        var received = new AtomicInteger();
        var codec = LaneProbe.codec();
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                       .fold(cause -> fail("server ssl: " + cause.message()), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                       .fold(cause -> fail("client ssl: " + cause.message()), ssl -> ssl);

        server = QuicClusterServer.quicClusterServer(SERVER_NODE,
                                                     new NodeAddress("127.0.0.1", 9400),
                                                     Map.of(),
                                                     codec,
                                                     codec,
                                                     QuicTransportMetrics.quicTransportMetrics(),
                                                     serverSsl,
                                                     Option.empty(),
                                                     (_, _, _) -> {},
                                                     (_, message) -> countProbe(received, message));
        server.start(0).await(AWAIT).onFailure(cause -> fail("server start: " + cause.message()));
        client = QuicClusterClient.quicClusterClient(CLIENT_NODE,
                                                     new NodeAddress("127.0.0.1", 9401),
                                                     Map.of(),
                                                     codec,
                                                     codec,
                                                     QuicTransportMetrics.quicTransportMetrics(),
                                                     clientSsl,
                                                     Option.empty(),
                                                     (_, _) -> {});

        var port = server.boundPort().fold(() -> fail("server not bound"), bound -> bound);
        var loser = client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port))
                          .await(AWAIT)
                          .fold(cause -> fail("connect: " + cause.message()), conn -> conn);
        var lane = loser.stream(StreamType.KV).fold(() -> fail("no KV lane"), stream -> stream);

        for (int i = 0; i < BURST; i++) {
            var bytes = codec.encode(LaneProbe.laneProbe(CLIENT_NODE, StreamType.KV, "burst-" + i + "-" + "x".repeat(160)));

            lane.write(Unpooled.wrappedBuffer(bytes));
        }
        lane.flush();
        loser.closeAfterDrain(QuicPeerConnection.DRAIN_BOUND_MS, end -> {});

        awaitTrue(() -> received.get() == BURST, AWAIT, "every frame of the burst delivered (got " + received.get() + ")");
        awaitTrue(() -> !loser.isActive(), AWAIT, "the drained connection closed");
        assertThat(received.get()).isEqualTo(BURST);
    }

    /// Routing: the dual-dial loser (different dialers) is drained, a same-direction re-dial keeps the immediate close.
    @Test
    void supersede_dualDialLoser_isDrained_andASameDirectionRedial_isClosedAtOnce() {
        var network = startedNetwork(new TripwireTopology(LOW, false, TimeSpan.timeSpan(30).seconds()),
                                     MessageRouter.mutable(),
                                     networks);
        var dualLoser = spy(connectionFrom(HIGH));
        var redialLoser = spy(connectionFrom(HIGH));

        network.closeSupersededConnectionForTests(dualLoser, connectionFrom(LOW));
        network.closeSupersededConnectionForTests(redialLoser, connectionFrom(HIGH));

        verify(dualLoser).closeAfterDrain(eq(QuicPeerConnection.DRAIN_BOUND_MS), any());
        verify(dualLoser, never()).close();
        verify(redialLoser).close();
        verify(redialLoser, never()).closeAfterDrain(anyLong(), any());
    }

    /// Routing of the other loser: a new connection that lost to an incumbent dialed by the other end is drained too.
    @Test
    void duplicate_newConnectionDialedByTheOtherEnd_isDrained() {
        var network = startedNetwork(new TripwireTopology(LOW, false, TimeSpan.timeSpan(30).seconds()),
                                     MessageRouter.mutable(),
                                     networks);
        var state = PeerState.peerState(HIGH, 1L);

        state.beginConnecting(2L);
        state.attach(connectionFrom(LOW), System.nanoTime());
        network.seedPeerForTests(HIGH, state);

        var loser = spy(connectionFrom(HIGH));

        network.onPeerConnectedForTests(loser, new NodeAddress("127.0.0.1", 1), Map.of());

        var ended = org.mockito.ArgumentCaptor.<java.util.function.Consumer<QuicPeerConnection.DrainEnd>>captor();

        verify(loser).closeAfterDrain(eq(QuicPeerConnection.DRAIN_BOUND_MS), ended.capture());
        verify(loser, never()).close();
        assertThat(network.quicMetrics().supersededCloseCount()).as("the duplicate loser is observed like a superseded one")
                                                                 .isEqualTo(1);
        ended.getValue().accept(QuicPeerConnection.DrainEnd.FLUSHED);
        ended.getValue().accept(QuicPeerConnection.DrainEnd.BOUND_HIT);
        ended.getValue().accept(QuicPeerConnection.DrainEnd.BOUND_HIT);
        assertThat(network.quicMetrics().loserDrainsFlushedCount()).isEqualTo(1);
        assertThat(network.quicMetrics().loserDrainsBoundHitCount()).isEqualTo(2);
    }

    /// The drain is bounded: a peer that never finishes its half cannot keep the loser open past the bound, and the
    /// loser is not closed before it.
    @Test
    void closeAfterDrain_peerNeverFinishes_closesAtTheBound_notBefore() {
        var loop = new DefaultEventLoop();
        var ended = new java.util.concurrent.CopyOnWriteArrayList<QuicPeerConnection.DrainEnd>();
        var channel = mock(QuicChannel.class);
        var stream = mock(QuicStreamChannel.class);

        try {
            when(channel.isActive()).thenReturn(true);
            when(channel.eventLoop()).thenReturn(loop);
            when(stream.isActive()).thenReturn(true);
            when(stream.closeFuture()).thenReturn(new DefaultChannelPromise(stream, loop));

            var connection = QuicPeerConnection.quicPeerConnection(HIGH, LOW, channel);
            var _ = connection.registerStream(StreamType.KV, stream);

            connection.closeAfterDrain(400, ended::add);

            verify(channel, never()).close();
            verify(stream).writeAndFlush(any());
            verify(channel, timeout(5_000)).close();
            assertThat(ended).containsExactly(QuicPeerConnection.DrainEnd.BOUND_HIT);
        } finally {
            loop.shutdownGracefully();
        }
    }

    /// The drain ends as soon as the last lane stream has closed, long before the bound.
    @Test
    void closeAfterDrain_everyLaneClosed_closesWithoutWaitingForTheBound() {
        var loop = new DefaultEventLoop();
        var ended = new java.util.concurrent.CopyOnWriteArrayList<QuicPeerConnection.DrainEnd>();
        var channel = mock(QuicChannel.class);
        var stream = mock(QuicStreamChannel.class);

        try {
            var closing = new DefaultChannelPromise(stream, loop);

            when(channel.isActive()).thenReturn(true);
            when(channel.eventLoop()).thenReturn(loop);
            when(stream.isActive()).thenReturn(true);
            when(stream.closeFuture()).thenReturn(closing);

            var connection = QuicPeerConnection.quicPeerConnection(HIGH, LOW, channel);
            var _ = connection.registerStream(StreamType.KV, stream);

            connection.closeAfterDrain(60_000, ended::add);
            verify(channel, never()).close();
            closing.setSuccess();

            verify(channel, timeout(5_000)).close();
            assertThat(ended).containsExactly(QuicPeerConnection.DrainEnd.FLUSHED);
        } finally {
            loop.shutdownGracefully();
        }
    }

    /// F1: a loop that no longer accepts tasks (the group is shutting down mid-supersede) must not let the rejection
    /// escape into the arbitration; the connection is closed at once.
    @Test
    void closeAfterDrain_loopRejectsSchedule_closesAtOnceAndThrowsNothing() throws Exception {
        var loop = new DefaultEventLoop();
        var channel = mock(QuicChannel.class);
        var stream = mock(QuicStreamChannel.class);

        when(channel.isActive()).thenReturn(true);
        when(channel.eventLoop()).thenReturn(loop);
        when(stream.isActive()).thenReturn(true);
        when(stream.closeFuture()).thenReturn(new DefaultChannelPromise(stream, loop));
        loop.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS).sync();

        var connection = QuicPeerConnection.quicPeerConnection(HIGH, LOW, channel);
        var _ = connection.registerStream(StreamType.KV, stream);

        connection.closeAfterDrain(400, end -> fail("no drain ran"));

        verify(channel).close();
        verify(stream, never()).writeAndFlush(any());
    }

    /// The bound the documentation and the changelog state.
    @Test
    void drainBound_isTwoSeconds() {
        assertThat(QuicPeerConnection.DRAIN_BOUND_MS).isEqualTo(2_000L);
    }

    /// A duplicate whose incumbent was dialed by the SAME end (a re-dial), or whose direction is unknown, is not the
    /// dual-dial race: it closes at once instead of burning the bound.
    @Test
    void duplicate_sameDialerOrUnknownDirection_closesAtOnce() {
        for (var loserOf : List.<java.util.function.Supplier<QuicPeerConnection>>of(() -> connectionFrom(LOW), QuicLoserDrainTest::connectionWithoutDirection)) {
            var network = startedNetwork(new TripwireTopology(LOW, false, TimeSpan.timeSpan(30).seconds()),
                                         MessageRouter.mutable(),
                                         networks);
            var state = PeerState.peerState(HIGH, 1L);

            state.beginConnecting(2L);
            state.attach(connectionFrom(LOW), System.nanoTime());
            network.seedPeerForTests(HIGH, state);

            var loser = spy(loserOf.get());

            network.onPeerConnectedForTests(loser, new NodeAddress("127.0.0.1", 1), Map.of());

            verify(loser).close();
            verify(loser, never()).closeAfterDrain(anyLong(), any());
        }
    }

    @Test
    void isDualDialLoser_unknownDirectionOnEitherSide_isNotTheRace() {
        assertThat(QuicClusterNetwork.isDualDialLoser(connectionWithoutDirection(), connectionFrom(LOW))).isFalse();
        assertThat(QuicClusterNetwork.isDualDialLoser(connectionFrom(LOW), connectionWithoutDirection())).isFalse();
        assertThat(QuicClusterNetwork.isDualDialLoser(connectionFrom(LOW), connectionFrom(LOW))).isFalse();
        assertThat(QuicClusterNetwork.isDualDialLoser(connectionFrom(LOW), connectionFrom(HIGH))).isTrue();
    }

    /// Each guard of closeAfterDrain closes at once, finishing no lane: no event loop, no live lane stream, a dead
    /// connection.
    @Test
    void closeAfterDrain_noLoop_closesAtOnce() {
        var channel = activeChannel();
        var stream = liveStream();
        var connection = QuicPeerConnection.quicPeerConnection(HIGH, LOW, channel);
        var _ = connection.registerStream(StreamType.KV, stream);

        connection.closeAfterDrain(60_000, end -> fail("no drain ran"));

        verify(channel).close();
        verify(stream, never()).writeAndFlush(any());
    }

    @Test
    void closeAfterDrain_noLiveLaneStream_closesAtOnce() {
        var loop = new DefaultEventLoop();

        try {
            var channel = activeChannel();

            when(channel.eventLoop()).thenReturn(loop);

            QuicPeerConnection.quicPeerConnection(HIGH, LOW, channel).closeAfterDrain(60_000, end -> fail("no drain ran"));

            verify(channel).close();
        } finally {
            loop.shutdownGracefully();
        }
    }

    @Test
    void closeAfterDrain_deadConnection_closesAtOnceWithoutFinishingLanes() {
        var loop = new DefaultEventLoop();

        try {
            var channel = mock(QuicChannel.class);
            var stream = liveStream();

            when(channel.isActive()).thenReturn(false);
            when(channel.eventLoop()).thenReturn(loop);

            var connection = QuicPeerConnection.quicPeerConnection(HIGH, LOW, channel);
            var _ = connection.registerStream(StreamType.KV, stream);

            connection.closeAfterDrain(60_000, end -> fail("no drain ran"));

            verify(channel).close();
            verify(stream, never()).writeAndFlush(any());
        } finally {
            loop.shutdownGracefully();
        }
    }

    /// The activity kick is stopped when the drain starts, not only when the connection finally closes: it would
    /// otherwise write a KeepAlive behind the end marker of the control lane.
    @Test
    void closeAfterDrain_stopsTheActivityKickAtOnce() {
        var loop = new DefaultEventLoop();
        var channel = activeChannel();
        var stream = liveStream();

        try {
            when(channel.eventLoop()).thenReturn(loop);
            when(stream.closeFuture()).thenReturn(new DefaultChannelPromise(stream, loop));

            var connection = QuicPeerConnection.quicPeerConnection(HIGH, LOW, channel);
            var _ = connection.registerStream(StreamType.CONTROL, stream);

            connection.activityKick(new byte[]{1}, () -> false);
            assertThat(connection.activityKickStopped()).isFalse();

            connection.closeAfterDrain(60_000, end -> {});

            assertThat(connection.activityKickStopped()).isTrue();
        } finally {
            loop.shutdownGracefully();
        }
    }

    private static QuicChannel activeChannel() {
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);

        return channel;
    }

    private static QuicStreamChannel liveStream() {
        var stream = mock(QuicStreamChannel.class);

        when(stream.isActive()).thenReturn(true);

        return stream;
    }

    private static QuicPeerConnection connectionWithoutDirection() {
        return QuicPeerConnection.quicPeerConnection(HIGH, activeChannel());
    }

    private static QuicPeerConnection connectionFrom(NodeId dialer) {
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);

        return QuicPeerConnection.quicPeerConnection(HIGH, dialer, channel);
    }

    private static void countProbe(AtomicInteger received, Object message) {
        if (message instanceof LaneProbe) {
            received.incrementAndGet();
        }
    }
}
