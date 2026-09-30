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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// #1578 — a dial socket must never be shareable. On Linux a reuse-enabled bind(0) can be handed the port of another
/// reuse-enabled socket (a node's QUIC server), after which EVERY datagram to that port goes to the later socket and
/// the server is silent (measured on Linux 6.8: 7 of 36,000 such binds landed on one of 5 server ports; the later
/// socket then received 5 of 5 datagrams, the server 0). A seed in `QuicDialAttemptTest` once never connected for
/// exactly this reason. The pin: a reuse-enabled socket cannot bind the dialer's own port — which it could if the dial
/// socket were reuse-enabled. The control, in the same run, shows this platform does let two reuse-enabled sockets
/// share a port. Linux does. So does macOS for Java's `setReuseAddress` (the JDK also sets SO_REUSEPORT on BSD-family
/// systems), which is why the pin runs there too; a platform that refuses the control skips it.
@Timeout(60)
class QuicDialSocketTest {
    private static final NodeId ACCEPTOR = new NodeId("ds-acceptor");
    private static final NodeId DIALER = new NodeId("ds-dialer");
    private static final NodeAddress UNUSED_ADDRESS = new NodeAddress("127.0.0.1", 9000);
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private final SliceCodec codec = LaneProbe.codec();
    private final AtomicReference<QuicPeerConnection> acceptorSide = new AtomicReference<>();
    private QuicClusterServer server;
    private QuicClusterClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().await(AWAIT);
        }
        if (server != null) {
            server.stop().await(AWAIT);
        }
    }

    @Test
    void dialSocket_isNotShareable_soNoOtherReuseSocketCanTakeItsPort() throws IOException {
        assumeTrue(platformSharesReusePorts(), "this platform never lets two SO_REUSEADDR UDP sockets share a port");

        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client")).fold(_ -> fail("client ssl"), ssl -> ssl);

        server = QuicClusterServer.quicClusterServer(ACCEPTOR, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), serverSsl, Option.empty(),
                                                     (connection, _, _) -> acceptorSide.set(connection),
                                                     (_, _) -> {});
        server.start(0).await(AWAIT).onFailure(cause -> fail("server start: " + cause.message()));
        var port = server.boundPort().fold(() -> fail("server not bound"), bound -> bound);

        client = QuicClusterClient.quicClusterClient(DIALER, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl, Option.empty(),
                                                     (_, _) -> {});
        client.connect(ACCEPTOR, new InetSocketAddress("127.0.0.1", port)).await(AWAIT)
              .onFailure(cause -> fail("dial: " + cause.message()));
        var dialPort = ((InetSocketAddress) acceptorSide.get().connection().remoteSocketAddress()).getPort();

        try (var intruder = reuseSocket()) {
            intruder.bind(new InetSocketAddress("0.0.0.0", dialPort));
            fail("a reuse-enabled socket bound the dial socket's port " + dialPort
                 + ", so the dial socket is shareable and its (or a server's) datagrams can be stolen");
        } catch (BindException expected) {
            assertThat(expected).as("the dial socket holds its port exclusively").isNotNull();
        }
    }

    /// Control: two reuse-enabled UDP sockets on one port — possible on Linux, and on macOS through Java's reuse (which
    /// also sets SO_REUSEPORT there).
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
}
