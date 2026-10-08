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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.DefaultQuicStreamFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1727 (M1), deterministic and through the PRODUCT finish path. quiche drops a FIN that first arrives on a
/// retransmission of data the receiver has already read, so a bare FIN could be lost for good. Recipe: ACKs from the
/// acceptor are blocked, so the dialer's data stays unacked; the acceptor reads it; the dialer RETIRES the stream
/// ([QuicPeerConnection#registerStream]) and the packet carrying the finish is dropped; the dialer's PTO re-sends. With a
/// bare FIN the acceptor never releases the lane (RED before the fix); with the lane-end marker the FIN rides on fresh
/// bytes and the marker ends the lane (GREEN). The control drops nothing.
@Timeout(120)
class QuicLaneEndMarkerFinLossTest {
    private static final NodeId ACCEPTOR = new NodeId("fd-acceptor");
    private static final NodeId DIALER = new NodeId("fd-dialer");
    private static final NodeAddress UNUSED_ADDRESS = new NodeAddress("127.0.0.1", 9000);
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(20).seconds();
    private static final StreamType LANE = StreamType.FORWARD;

    private final SliceCodec codec = LaneProbe.codec();
    private final List<Object> receivedByAcceptor = new CopyOnWriteArrayList<>();
    private final AtomicReference<QuicPeerConnection> acceptorSide = new AtomicReference<>();
    private QuicClusterServer server;
    private QuicClusterClient client;
    private Relay relay;
    private QuicPeerConnection dialerSide;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().await(AWAIT);
        }
        if (server != null) {
            server.stop().await(AWAIT);
        }
        if (relay != null) {
            relay.close();
        }
    }

    @Test
    void retiredLaneFinishLostOnTheWire_stillReleasesTheLaneAtTheAcceptor() {
        connect();
        var stream = dialerSide.stream(LANE).unwrap();
        var atAcceptor = acceptorSide.get().stream(LANE).unwrap();

        relay.blockAcceptorToDialer = true;   // the dialer never hears ACKs: its data stays unacked
        stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(DIALER, LANE, "last-before-finish"))));
        awaitTrue(() -> receivedByAcceptor.stream().anyMatch(m -> m instanceof LaneProbe p && p.marker().equals("last-before-finish")),
                  "the acceptor read the data");
        relay.blockDialerToAcceptor = true;   // the finish is lost
        retire(stream);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1500));
        relay.blockDialerToAcceptor = false;  // PTO re-sends the oldest unacked data
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1500));
        relay.blockAcceptorToDialer = false;
        awaitTrue(() -> acceptorSide.get().stream(LANE).map(current -> current != atAcceptor).or(true),
                  "the acceptor released the retired lane although the finish was first lost");
        awaitStreamClosedAtBothEnds(atAcceptor, stream);
    }

    @Test
    void control_finishNotDropped_releasesTheLane() {
        connect();
        var stream = dialerSide.stream(LANE).unwrap();
        var atAcceptor = acceptorSide.get().stream(LANE).unwrap();

        relay.blockAcceptorToDialer = true;
        stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(DIALER, LANE, "last-before-finish"))));
        awaitTrue(() -> receivedByAcceptor.stream().anyMatch(m -> m instanceof LaneProbe p && p.marker().equals("last-before-finish")),
                  "the acceptor read the data");
        retire(stream);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(3000));
        relay.blockAcceptorToDialer = false;
        awaitTrue(() -> acceptorSide.get().stream(LANE).map(current -> current != atAcceptor).or(true),
                  "control: the acceptor released the retired lane");
        awaitStreamClosedAtBothEnds(atAcceptor, stream);
    }

    /// The receiver branch alone, end to end: the marker arrives as ordinary bytes with NO FIN (the case a
    /// lost FIN produces), and the acceptor still releases the lane. Hand-feeding the handler cannot show
    /// that the real pipeline (length decoder included) delivers the empty frame.
    @Test
    void markerWithoutAnyFin_releasesTheLaneAtTheAcceptor() {
        connect();
        var stream = dialerSide.stream(LANE).unwrap();
        var atAcceptor = acceptorSide.get().stream(LANE).unwrap();

        stream.writeAndFlush(new DefaultQuicStreamFrame(Unpooled.wrappedBuffer(new byte[4]), false));

        awaitTrue(() -> acceptorSide.get().stream(LANE).map(current -> current != atAcceptor).or(true),
                  "the acceptor released the lane on the marker alone, with no FIN sent");
    }

    /// 4.2.18 behaviour the answering path relies on: a lane-end write after the FIN has gone out fails its
    /// future, and neither throws into the caller nor reaches the pipeline's exceptionCaught.
    @Test
    void markerWrittenAfterFin_failsQuietly() {
        connect();
        var stream = dialerSide.stream(LANE).unwrap();
        var caught = new AtomicInteger();

        stream.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                caught.incrementAndGet();
            }
        });
        var first = stream.writeAndFlush(QuicPeerConnection.laneEndFrame());

        first.awaitUninterruptibly(AWAIT.millis());
        assertThat(first.isSuccess()).as("control: the first marker+FIN is written").isTrue();

        var second = stream.writeAndFlush(QuicPeerConnection.laneEndFrame());

        second.awaitUninterruptibly(AWAIT.millis());
        assertThat(second.isDone()).isTrue();
        assertThat(second.isSuccess()).as("a write after the FIN is refused").isFalse();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
        assertThat(caught.get()).as("and does not surface as an exception in the pipeline").isZero();
    }

    /// An error path must never hang. On a FLOW-CONTROL-BLOCKED stream the marker would queue behind the
    /// blocked writes; the stream must still close within the bound (a bare close() does; endLaneThenClose
    /// without a guard did not). Adapted from v-2020's probe.
    @Test
    void errorPathCloseOnAFlowControlBlockedStream_closesWithinTheBound() {
        connect();
        var stream = dialerSide.stream(LANE).unwrap();
        var atAcceptor = acceptorSide.get().stream(LANE).unwrap();

        atAcceptor.config().setAutoRead(false);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
        io.netty.channel.ChannelFuture last = null;

        for (var n = 0; n < 4000; n++) {
            last = stream.writeAndFlush(Unpooled.wrappedBuffer(new byte[64 * 1024]));
            if (n % 16 == 15) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                if (!last.isDone()) {
                    break;
                }
            }
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
        assertThat(last.isDone()).as("precondition: the stream is flow-control blocked").isFalse();

        stream.pipeline().fireExceptionCaught(new IllegalStateException("injected"));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (stream.isActive() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        assertThat(stream.isActive()).as("the errored stream closed within 5 s").isFalse();
    }

    /// After the marker both halves are finished and the stream closes at BOTH ends, returning its credit.
    private void awaitStreamClosedAtBothEnds(QuicStreamChannel atAcceptor, QuicStreamChannel atDialer) {
        awaitTrue(() -> !atAcceptor.isActive() && !atDialer.isActive(), "the retired stream closed at both ends");
    }

    /// Retires `old` through the product path: a newer dialer-opened stream outranks it, so
    /// registerStream finishes `old` behind the writes already on it.
    private void retire(QuicStreamChannel old) {
        var newer = dialerSide.connection()
                              .createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                              .syncUninterruptibly()
                              .getNow();

        assertThat(dialerSide.registerStream(LANE, newer)).isSameAs(newer);
        assertThat(old.streamId()).isLessThan(newer.streamId());
    }

    private void connect() {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client")).fold(_ -> fail("client ssl"), ssl -> ssl);

        server = QuicClusterServer.quicClusterServer(ACCEPTOR, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), serverSsl, Option.empty(),
                                                     (connection, _, _) -> acceptorSide.set(connection),
                                                     (_, message) -> receivedByAcceptor.add(message));
        server.start(0).await(AWAIT).onFailure(cause -> fail("server start: " + cause.message()));
        var port = server.boundPort().fold(() -> fail("server not bound"), bound -> bound);

        relay = new Relay(port);
        client = QuicClusterClient.quicClusterClient(DIALER, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl, Option.empty(),
                                                     (_, _) -> {});
        dialerSide = client.connect(ACCEPTOR, new InetSocketAddress("127.0.0.1", relay.port())).await(AWAIT)
                           .fold(cause -> fail("dial: " + cause.message()), connection -> connection);
        awaitTrue(() -> acceptorSide.get() != null
                        && java.util.Arrays.stream(StreamType.values()).allMatch(lane -> acceptorSide.get().stream(lane).isPresent()),
                  "the acceptor registered every lane the dialer opened");
        awaitTrue(() -> dialerSide.stream(LANE).isPresent() && acceptorSide.get().stream(LANE).isPresent()
                        && dialerSide.stream(LANE).unwrap().streamId() == acceptorSide.get().stream(LANE).unwrap().streamId(),
                  "both ends carry FORWARD on the dialer's stream");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));   // let handshake-era acks settle
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        var deadline = System.nanoTime() + AWAIT.nanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        fail("Timed out waiting for: " + what);
    }

    /// Per-direction blockable UDP relay (one client).
    static final class Relay implements AutoCloseable {
        private final DatagramSocket front;
        private final DatagramSocket upstream;
        private final InetSocketAddress target;
        private volatile SocketAddress client;
        private volatile boolean closed;
        volatile boolean blockDialerToAcceptor;
        volatile boolean blockAcceptorToDialer;
        final AtomicInteger droppedC2S = new AtomicInteger();
        final AtomicInteger droppedS2C = new AtomicInteger();

        Relay(int targetPort) {
            try {
                front = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                upstream = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            target = new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort);
            Thread.ofPlatform().daemon().start(this::c2s);
            Thread.ofPlatform().daemon().start(this::s2c);
        }

        int port() {
            return front.getLocalPort();
        }

        private void c2s() {
            var buffer = new byte[65_535];
            while (!closed) {
                try {
                    var packet = new DatagramPacket(buffer, buffer.length);
                    front.receive(packet);
                    client = packet.getSocketAddress();
                    if (blockDialerToAcceptor) {
                        droppedC2S.incrementAndGet();
                    } else {
                        upstream.send(new DatagramPacket(packet.getData(), packet.getLength(), target));
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void s2c() {
            var buffer = new byte[65_535];
            while (!closed) {
                try {
                    var packet = new DatagramPacket(buffer, buffer.length);
                    upstream.receive(packet);
                    if (blockAcceptorToDialer || client == null) {
                        droppedS2C.incrementAndGet();
                    } else {
                        front.send(new DatagramPacket(packet.getData(), packet.getLength(), client));
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            front.close();
            upstream.close();
        }
    }
}
