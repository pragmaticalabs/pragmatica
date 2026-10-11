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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.quic.QuicheStall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1727 (M2), deterministic. netty-quic can strand lost stream data: when its retransmission timer is
/// already due while the event loop is inside `connectionSend()`, it runs the timer inline, quiche
/// declares the last in-flight packet lost, the nested send is a re-entrant no-op, and neither quiche nor
/// netty has a timer left. The lost bytes wait for an unrelated event.
///
/// Recipe (no statistics): a relay with a long one-way delay (RTT 600 ms, so quiche's loss timer for A
/// is far from netty's own wake-up), dropping data packet A and delivering B. When B's ACK reaches the
/// dialer, A is the only packet in flight and quiche's timer is the loss timer for A. In one loop task
/// [QuicheStall#induceLateTimer] makes netty's timer elapsed, waits until quiche's is due, and calls
/// `connectionSend()`. The positive control is read in the same run: quiche's timer must read -1 afterwards.
/// A never arrives on its own; with the activity kick it arrives within a few hundred ms.
///
/// The kick window is opened AFTER the stall is induced: a kick packet in flight during the induction
/// would be the very traffic that masks it. In production the kick runs from the data write onward.
@Timeout(180)
class QuicActivityKickStrandedLossTest {
    private static final NodeId ACCEPTOR = new NodeId("sk-acceptor");
    private static final NodeId DIALER = new NodeId("sk-dialer");
    private static final NodeAddress UNUSED_ADDRESS = new NodeAddress("127.0.0.1", 9000);
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(30).seconds();
    private static final StreamType LANE = StreamType.FORWARD;
    private static final long ONE_WAY_DELAY_MS = 300;
    private static final TimeSpan RECOVERY_BOUND = TimeSpan.timeSpan(3).seconds();
    /// Padding makes each data packet far larger than any ACK-only packet, so the relay can tell them apart.
    private static final String PAD = "x".repeat(400);
    private static final int DATA_PACKET_MIN_BYTES = 300;
    /// Observed loss timer after B's ACK: 48-81 ms. The PTO is ~1 s and counts down through any larger bound.
    private static final long LOSS_TIMER_MAX_NANOS = TimeUnit.MILLISECONDS.toNanos(150);

    private final SliceCodec codec = LaneProbe.codec();
    private final List<Object> receivedByAcceptor = new CopyOnWriteArrayList<>();
    private final AtomicReference<QuicPeerConnection> acceptorSide = new AtomicReference<>();
    private QuicClusterServer server;
    private QuicClusterClient client;
    private DelayRelay relay;
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
    void strandedLoss_isRecoveredByTheActivityKick() {
        var reading = strand();

        dialerSide.noteLaneWrite();

        awaitTrue(() -> received("A" + PAD), RECOVERY_BOUND, "the kick re-queued and delivered the stranded data A");
        assertThat(reading.nettyTimerArmedAfter()).as("positive control: netty had no timer after the induced stall").isFalse();
        assertThat(reading.quicheTimerAfter()).as("positive control: the stall state was reached in this run").isEqualTo(-1);
    }

    /// TRIPWIRE, enabled on purpose. It asserts today's netty behaviour: without a write nothing ever
    /// re-sends A. It is the in-run control for the test above (same recipe, only the kick differs). When
    /// netty is bumped to a release that defers the elapsed timer, THIS test goes red: delete it and the
    /// kick (QuicActivityKick), and keep the test above as the pin that the stall no longer occurs.
    @Test
    void tripwire_withoutTheKick_theStrandedLossStaysStranded_untilNettyIsFixed() {
        var reading = strand();

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));

        assertThat(reading.quicheTimerAfter()).as("positive control: the stall state was reached in this run").isEqualTo(-1);
        assertThat(received("A" + PAD)).as("netty now re-sends stranded data by itself - delete this tripwire and the activity kick")
                                 .isFalse();
    }

    /// Returns the reading taken right after the induced stall; A has been dropped, B delivered and acked.
    private QuicheStall.Reading strand() {
        connect();
        var stream = dialerSide.stream(LANE).unwrap();

        relay.dropNextDialerPacket();
        stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(DIALER, LANE, "A" + PAD))));
        awaitTrue(() -> relay.sentByDialerSinceArm.get() >= 1, "data packet A reached the relay");
        stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(DIALER, LANE, "B" + PAD))));
        awaitTrue(() -> relay.sentByDialerSinceArm.get() >= 2, "data packet B reached the relay");
        assertThat(relay.droppedByDialer.get()).as("exactly A was dropped").isEqualTo(1);

        // B's ACK reaching the dialer arms quiche's loss timer for A (tens of ms away; the PTO is ~1 s).
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var timer = -1L;

        while (System.nanoTime() < deadline) {
            timer = timer();
            if (timer > 0 && timer < LOSS_TIMER_MAX_NANOS) {
                break;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(timer).as("quiche armed the loss timer for A once B was acked").isBetween(1L, LOSS_TIMER_MAX_NANOS);

        var reading = induce();

        assertThat(reading.quicheTimerBefore()).as("quiche's timer was due when connectionSend ran").isLessThanOrEqualTo(0);
        assertThat(received("A" + PAD)).as("A is not delivered by anything else").isFalse();

        return reading;
    }

    private boolean received(String marker) {
        return receivedByAcceptor.stream().anyMatch(m -> m instanceof LaneProbe p && p.marker().equals(marker));
    }

    private long timer() {
        try {
            return QuicheStall.quicheTimerNanos(dialerSide.connection());
        } catch (Exception e) {
            return fail("timer read failed: " + e);
        }
    }

    private QuicheStall.Reading induce() {
        try {
            return QuicheStall.induceLateTimer(dialerSide.connection());
        } catch (Exception e) {
            return fail("stall induction failed: " + e);
        }
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

        relay = new DelayRelay(port, ONE_WAY_DELAY_MS);
        client = QuicClusterClient.quicClusterClient(DIALER, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl, Option.empty(),
                                                     (_, _) -> {});
        dialerSide = client.connect(ACCEPTOR, new InetSocketAddress("127.0.0.1", relay.port())).await(AWAIT)
                           .fold(cause -> fail("dial: " + cause.message()), connection -> connection);
        BooleanSupplier notSuppressed = () -> false;

        dialerSide.activityKick(codec.encode(new NetworkMessage.KeepAlive(DIALER)), notSuppressed);
        awaitTrue(() -> acceptorSide.get() != null
                        && java.util.Arrays.stream(StreamType.values()).allMatch(lane -> acceptorSide.get().stream(lane).isPresent()),
                  "the acceptor registered every lane the dialer opened");
        awaitTrue(() -> dialerSide.stream(LANE).isPresent() && acceptorSide.get().stream(LANE).isPresent()
                        && dialerSide.stream(LANE).unwrap().streamId() == acceptorSide.get().stream(LANE).unwrap().streamId(),
                  "both ends carry FORWARD on the dialer's stream");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(4 * ONE_WAY_DELAY_MS));   // handshake-era acks settle: nothing in flight
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        awaitTrue(condition, AWAIT, what);
    }

    private static void awaitTrue(BooleanSupplier condition, TimeSpan bound, String what) {
        var deadline = System.nanoTime() + bound.nanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        fail("Timed out waiting for: " + what);
    }

    /// One-client UDP relay with a fixed one-way delay per direction (order preserved) that can drop the
    /// next dialer-to-acceptor datagram.
    static final class DelayRelay implements AutoCloseable {
        private final DatagramSocket front;
        private final DatagramSocket upstream;
        private final InetSocketAddress target;
        private final long delayMs;
        private final ScheduledExecutorService toAcceptor = Executors.newSingleThreadScheduledExecutor();
        private final ScheduledExecutorService toDialer = Executors.newSingleThreadScheduledExecutor();
        private volatile SocketAddress client;
        private volatile boolean closed;
        private volatile boolean dropNext;
        private volatile boolean armed;
        final AtomicInteger sentByDialerSinceArm = new AtomicInteger();
        final AtomicInteger droppedByDialer = new AtomicInteger();

        DelayRelay(int targetPort, long delayMs) {
            this.delayMs = delayMs;
            try {
                front = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                upstream = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            target = new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort);
            Thread.ofPlatform().daemon().start(this::fromDialer);
            Thread.ofPlatform().daemon().start(this::fromAcceptor);
        }

        int port() {
            return front.getLocalPort();
        }

        void dropNextDialerPacket() {
            sentByDialerSinceArm.set(0);
            dropNext = true;
            armed = true;
        }

        private void fromDialer() {
            var buffer = new byte[65_535];

            while (!closed) {
                try {
                    var packet = new DatagramPacket(buffer, buffer.length);

                    front.receive(packet);
                    client = packet.getSocketAddress();
                    var copy = java.util.Arrays.copyOf(packet.getData(), packet.getLength());

                    var isData = copy.length >= DATA_PACKET_MIN_BYTES;

                    if (armed && isData) {
                        sentByDialerSinceArm.incrementAndGet();
                    }
                    if (dropNext && isData) {
                        dropNext = false;
                        droppedByDialer.incrementAndGet();
                    } else {
                        toAcceptor.schedule(() -> send(upstream, copy, target), delayMs, TimeUnit.MILLISECONDS);
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void fromAcceptor() {
            var buffer = new byte[65_535];

            while (!closed) {
                try {
                    var packet = new DatagramPacket(buffer, buffer.length);

                    upstream.receive(packet);
                    var copy = java.util.Arrays.copyOf(packet.getData(), packet.getLength());

                    if (client != null) {
                        toDialer.schedule(() -> send(front, copy, client), delayMs, TimeUnit.MILLISECONDS);
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        private static void send(DatagramSocket socket, byte[] data, SocketAddress to) {
            try {
                socket.send(new DatagramPacket(data, data.length, to));
            } catch (IOException e) {
                // relay closing
            }
        }

        @Override
        public void close() {
            closed = true;
            front.close();
            upstream.close();
            toAcceptor.shutdownNow();
            toDialer.shutdownNow();
        }
    }
}
