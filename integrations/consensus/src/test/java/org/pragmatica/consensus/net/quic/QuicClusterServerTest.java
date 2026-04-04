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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class QuicClusterServerTest {

    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final NodeId CLIENT_NODE = NodeId.randomNodeId();
    private static final NodeAddress SERVER_ADDRESS = new NodeAddress("127.0.0.1", 9000);
    private static final NodeAddress CLIENT_ADDRESS = new NodeAddress("127.0.0.1", 9001);
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();

    private SliceCodec codec;
    private QuicClusterServer server;
    private QuicClusterClient client;

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(
            FrameworkCodecs.frameworkCodecs(),
            combinedCodecs()
        );
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().await(AWAIT_TIMEOUT);
        }
        if (server != null) {
            server.stop().await(AWAIT_TIMEOUT);
        }
    }

    @Nested
    class HelloHandshake {

        @Test
        void connect_helloExchanged_peerConnectionEstablished() throws Exception {
            var serverSslResult = QuicTlsProvider.serverContext(Option.empty());
            var clientSslResult = QuicTlsProvider.clientContext(Option.empty());

            serverSslResult.onFailure(_ -> fail("Server SSL context creation failed"));
            clientSslResult.onFailure(_ -> fail("Client SSL context creation failed"));

            var connections = new ArrayList<QuicPeerConnection>();
            var latch = new CountDownLatch(1);

            server = serverSslResult.fold(
                _ -> fail("unreachable"),
                ssl -> QuicClusterServer.quicClusterServer(
                    SERVER_NODE, NodeRole.ACTIVE, SERVER_ADDRESS, Map.of(), codec, codec, ssl, Option.empty(),
                    (conn, _, _, _) -> {
                        connections.add(conn);
                        latch.countDown();
                    },
                    (_, _) -> {}
                )
            );

            server.start(0).await(AWAIT_TIMEOUT)
                  .onFailure(cause -> fail("Server start failed: " + cause.message()));

            var boundPort = server.boundPort()
                                  .fold(() -> fail("Server not bound"), port -> port);

            client = clientSslResult.fold(
                _ -> fail("unreachable"),
                ssl -> QuicClusterClient.quicClusterClient(
                    CLIENT_NODE, NodeRole.ACTIVE, CLIENT_ADDRESS, Map.of(), codec, codec, ssl, Option.empty(),
                    (_, _) -> {}
                )
            );

            client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", boundPort))
                  .await(AWAIT_TIMEOUT)
                  .onFailure(cause -> fail("Client connect failed: " + cause.message()))
                  .onSuccess(conn -> {
                      assertThat(conn).isNotNull();
                      assertThat(conn.peerId()).isEqualTo(SERVER_NODE);
                      assertThat(conn.isActive()).isTrue();
                  });

            assertThat(latch.await(10, TimeUnit.SECONDS))
                .as("Server should receive peer connection callback")
                .isTrue();

            assertThat(connections).hasSize(1);
            assertThat(connections.getFirst().peerId()).isEqualTo(CLIENT_NODE);
        }
    }

    @Nested
    class ServerLifecycle {

        @Test
        void start_bindSucceeds_promiseResolves() {
            var sslResult = QuicTlsProvider.serverContext(Option.empty());

            server = sslResult.fold(
                _ -> fail("unreachable"),
                ssl -> QuicClusterServer.quicClusterServer(
                    SERVER_NODE, NodeRole.ACTIVE, SERVER_ADDRESS, Map.of(), codec, codec, ssl, Option.empty(),
                    (_, _, _, _) -> {}, (_, _) -> {}
                )
            );

            server.start(0).await(AWAIT_TIMEOUT)
                  .onFailure(cause -> fail("Server start failed: " + cause.message()));

            assertThat(server.boundPort().isPresent())
                .as("Server should be bound to a port")
                .isTrue();
        }

        @Test
        void stop_afterStart_releasesResources() {
            var sslResult = QuicTlsProvider.serverContext(Option.empty());

            server = sslResult.fold(
                _ -> fail("unreachable"),
                ssl -> QuicClusterServer.quicClusterServer(
                    SERVER_NODE, NodeRole.ACTIVE, SERVER_ADDRESS, Map.of(), codec, codec, ssl, Option.empty(),
                    (_, _, _, _) -> {}, (_, _) -> {}
                )
            );

            server.start(0).await(AWAIT_TIMEOUT)
                  .onFailure(cause -> fail("Server start failed: " + cause.message()));

            server.stop().await(AWAIT_TIMEOUT)
                  .onFailure(cause -> fail("Server stop failed: " + cause.message()));
        }
    }

    private static java.util.List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
