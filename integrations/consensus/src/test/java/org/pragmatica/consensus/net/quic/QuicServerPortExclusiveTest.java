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
 */package org.pragmatica.consensus.net.quic;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// #1719 / #1015 / #1727 — a node's QUIC cluster port is held EXCLUSIVELY. The server used to bind with SO_REUSEADDR, so any
/// other reuse-enabled socket on the host (another node, another process) bound the same port without error; on Linux the
/// later socket then received every datagram for the port, and the server went deaf mid-stream with nothing failing. That
/// is #1727's signature, reproduced by v1677 3/3 on Linux (a reuse bind on the acceptor's port mid-burst: delivery stops,
/// the acceptor's QUIC receive count stops, both streams stay open). It also meant a second node on a taken port started
/// happily and `BindFailed` could never fire (#1015).
@Timeout(90)
class QuicServerPortExclusiveTest {
    private static final NodeId ACCEPTOR = new NodeId("px-acceptor");
    private static final NodeId DIALER = new NodeId("px-dialer");
    private static final NodeAddress UNUSED_ADDRESS = new NodeAddress("127.0.0.1", 9000);
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(20).seconds();
    private static final StreamType LANE = StreamType.FORWARD;
    private static final int BURST = 300;
    private static final int INTRUDE_AT = 150;

    private final SliceCodec codec = LaneProbe.codec();
    private final List<Object> receivedByAcceptor = new CopyOnWriteArrayList<>();
    private final AtomicReference<QuicPeerConnection> acceptorSide = new AtomicReference<>();
    private final List<QuicClusterServer> servers = new CopyOnWriteArrayList<>();
    private QuicClusterClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().await(AWAIT);
        }
        servers.forEach(server -> server.stop().await(AWAIT));
    }

    @Test
    void serverPort_isNotShareable_aReuseSocketCannotBindIt() throws IOException {
        assumeTrue(platformSharesReusePorts(), "this platform never lets two SO_REUSEADDR UDP sockets share a port");
        var port = startServer(0);

        try (var intruder = reuseSocket()) {
            intruder.bind(new InetSocketAddress(port));
            fail("a reuse-enabled socket bound the server's port " + port + ": the port is shareable, so its datagrams can be stolen");
        } catch (BindException expected) {
            assertThat(expected).as("the server holds its port exclusively").isNotNull();
        }
    }

    @Test
    void secondServer_onATakenPort_failsWithBindFailedNamingThePort() {
        var port = startServer(0);
        var second = server();
        var outcome = second.start(port).await(AWAIT);

        assertThat(outcome.isFailure()).as("a second server on a taken port must not start").isTrue();
        outcome.onFailure(cause -> assertThat(cause).as("the failure is the typed BindFailed naming the port")
                                                   .isInstanceOf(QuicTransportError.BindFailed.class)
                                                   .extracting(Object::toString)
                                                   .asString()
                                                   .contains(String.valueOf(port)));
    }

    /// #1727's reproduction as a regression: mid-burst, a reuse-enabled socket tries to bind the acceptor's port — as a
    /// concurrent server's bind on the same host would. It must be refused, and every write must still be delivered.
    @Test
    void intruderBindMidBurst_isRefused_andEveryWriteIsDelivered() throws IOException, InterruptedException {
        assumeTrue(platformSharesReusePorts(), "this platform never lets two SO_REUSEADDR UDP sockets share a port");
        var port = startServer(0);

        client = QuicClusterClient.quicClusterClient(DIALER, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl(), Option.empty(),
                                                     (_, _) -> {});
        var dialerSide = client.connect(ACCEPTOR, new InetSocketAddress("127.0.0.1", port))
                               .await(AWAIT)
                               .fold(cause -> fail("dial: " + cause.message()), connection -> connection);

        awaitTrue(() -> acceptorSide.get() != null
                        && Arrays.stream(StreamType.values()).allMatch(lane -> acceptorSide.get().stream(lane).isPresent()),
                  "the acceptor registered every lane the dialer opened");
        var stream = dialerSide.stream(LANE).unwrap();
        var padding = "x".repeat(8 * 1024);
        var succeeded = new AtomicInteger();
        var intrusion = new AtomicReference<String>("not attempted");
        var intruderThread = Thread.ofPlatform().daemon().start(() -> intrudeOnceHalfDelivered(port, intrusion));

        IntStream.range(0, BURST)
                 .forEach(i -> stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(DIALER, LANE, "px-" + i + "|" + padding))))
                                     .addListener(future -> {
                                         if (future.isSuccess()) {
                                             succeeded.incrementAndGet();
                                         }
                                     }));
        awaitTrue(() -> !"not attempted".equals(intrusion.get()), "arming: the intruder tried to bind mid-burst");
        assertThat(intrusion.get()).as("the intruder's bind on the acceptor's port is refused").startsWith("refused");
        awaitTrue(() -> delivered() == BURST, "every write is delivered despite the intrusion attempt (delivered=" + delivered()
                                              + " succeeded=" + succeeded.get() + ")");
        // The thread exits after intrusion.set, which delivered()==BURST does not order: join, then assert.
        intruderThread.join(Duration.ofSeconds(10));
        assertThat(intruderThread.isAlive()).isFalse();
    }

    private void intrudeOnceHalfDelivered(int port, AtomicReference<String> intrusion) {
        var deadline = System.nanoTime() + AWAIT.nanos();

        while (System.nanoTime() < deadline && delivered() < INTRUDE_AT) {
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
        }
        try (var intruder = reuseSocket()) {
            intruder.bind(new InetSocketAddress(port));
            // Hold the stolen port for a moment, as a concurrent server would, before reporting.
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
            intrusion.set("BOUND at delivered=" + delivered());
        } catch (IOException e) {
            intrusion.set("refused at delivered=" + delivered() + ": " + e);
        }
    }

    private long delivered() {
        return receivedByAcceptor.stream()
                                 .filter(LaneProbe.class::isInstance)
                                 .map(LaneProbe.class::cast)
                                 .map(LaneProbe::marker)
                                 .filter(marker -> marker.startsWith("px-"))
                                 .count();
    }

    private int startServer(int port) {
        var server = server();

        server.start(port).await(AWAIT).onFailure(cause -> fail("server start: " + cause.message()));
        return server.boundPort().fold(() -> fail("server not bound"), bound -> bound);
    }

    private QuicClusterServer server() {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).fold(_ -> fail("server ssl"), ssl -> ssl);
        var server = QuicClusterServer.quicClusterServer(ACCEPTOR, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                         QuicTransportMetrics.quicTransportMetrics(), serverSsl, Option.empty(),
                                                         (connection, _, _) -> acceptorSide.set(connection),
                                                         (_, message) -> receivedByAcceptor.add(message));

        servers.add(server);
        return server;
    }

    private static io.netty.handler.codec.quic.QuicSslContext clientSsl() {
        return QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client")).fold(_ -> fail("client ssl"), ssl -> ssl);
    }

    /// Control: two reuse-enabled UDP sockets on one port — possible on Linux, and on macOS through Java's reuse.
    private static boolean platformSharesReusePorts() throws IOException {
        try (var first = reuseSocket(); var second = reuseSocket()) {
            first.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            second.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), first.getLocalPort()));
            return true;
        } catch (BindException e) {
            return false;
        }
    }

    private static DatagramSocket reuseSocket() throws IOException {
        var socket = new DatagramSocket(null);

        socket.setReuseAddress(true);
        return socket;
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
}
