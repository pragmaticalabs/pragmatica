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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;

import io.netty.buffer.Unpooled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;

/// #2030 — the loser of a dual-dial race is closed AFTER what is already written on it has flushed. A burst is written
/// on a lane of a live connection and the connection is drained-closed at once; every frame must still arrive, and the
/// connection must then be closed (the drain is bounded, not a leak).
@Timeout(60)
class QuicLoserDrainTest {
    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final NodeId CLIENT_NODE = NodeId.randomNodeId();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(15).seconds();
    private static final int BURST = 4_000;

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
        var loser = client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port), 1L)
                          .await(AWAIT)
                          .fold(cause -> fail("connect: " + cause.message()), conn -> conn);
        var lane = loser.stream(StreamType.KV).fold(() -> fail("no KV lane"), stream -> stream);

        for (int i = 0; i < BURST; i++) {
            var bytes = codec.encode(LaneProbe.laneProbe(CLIENT_NODE, StreamType.KV, "burst-" + i + "-" + "x".repeat(160)));

            lane.write(Unpooled.wrappedBuffer(bytes));
        }
        lane.flush();
        loser.closeAfterDrain(QuicPeerConnection.DRAIN_BOUND_MS);

        awaitTrue(() -> received.get() == BURST, AWAIT, "every frame of the burst delivered (got " + received.get() + ")");
        awaitTrue(() -> !loser.isActive(), AWAIT, "the drained connection closed");
        assertThat(received.get()).isEqualTo(BURST);
    }

    private static void countProbe(AtomicInteger received, Object message) {
        if (message instanceof LaneProbe) {
            received.incrementAndGet();
        }
    }
}
