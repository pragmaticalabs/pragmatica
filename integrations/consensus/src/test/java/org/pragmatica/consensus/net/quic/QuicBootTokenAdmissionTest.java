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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.handler.codec.quic.QuicSslContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1528 — the QUIC Hello handshake admits peers through the shared boot-token registry.
///
/// A different process (different token) for a NodeId the registry already knows is refused on BOTH
/// sides of the handshake — by the acceptor before it answers or registers the peer, and by the dialer
/// before it attaches — and the NodeId is retired. Each refusal is paired with a positive control on
/// the same server, port and codecs: the same-token process connects.
@Timeout(60)
class QuicBootTokenAdmissionTest {
    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final NodeId CLIENT_NODE = NodeId.randomNodeId();
    private static final NodeAddress SERVER_ADDRESS = new NodeAddress("127.0.0.1", 9200);
    private static final NodeAddress CLIENT_ADDRESS = new NodeAddress("127.0.0.1", 9201);
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(8).seconds();
    private static final String CLUSTER_SECRET = "boot-token-test-cluster-secret";
    private static final long SERVER_TOKEN = 0x5E7L;
    private static final long CLIENT_TOKEN = 0xC11L;
    private static final long OTHER_TOKEN = 0x0DDL;

    private SliceCodec codec;
    private QuicClusterServer server;
    private QuicClusterClient client;
    private final AtomicInteger registered = new AtomicInteger();

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
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

    @Test
    void acceptor_sameTokenProcess_isAdmitted() {
        var serverTokens = serverRegistryKnowingClientAs(CLIENT_TOKEN);
        var port = startServer(serverTokens);

        assertThat(connects(BootTokens.bootTokens(CLIENT_TOKEN), port)).as("control: the known process reconnects").isTrue();
        assertThat(registered.get()).isEqualTo(1);
        assertThat(serverTokens.isRetired(CLIENT_NODE)).isFalse();
    }

    @Test
    void acceptor_differentTokenProcess_isRefusedAndRetired() {
        var serverTokens = serverRegistryKnowingClientAs(CLIENT_TOKEN);
        var port = startServer(serverTokens);

        assertThat(connects(BootTokens.bootTokens(OTHER_TOKEN), port))
            .as("a new process reusing a known NodeId must not complete the handshake")
            .isFalse();
        assertThat(registered.get()).as("the refused peer is never registered — no ADD, no RECONNECT").isZero();
        assertThat(serverTokens.isRetired(CLIENT_NODE)).isTrue();
        assertThat(connects(BootTokens.bootTokens(CLIENT_TOKEN), port))
            .as("terminal: once retired, even the original token is refused")
            .isFalse();
    }

    /// The refused process LEARNS it was refused: the acceptor answers with an explicit HelloRefused,
    /// and the dialer's registry notifies its self-refusal listener (the node then exits). Control:
    /// an admitted dial notifies nothing.
    @Test
    void acceptor_refusal_isReportedToTheRefusedProcess() {
        var port = startServer(serverRegistryKnowingClientAs(CLIENT_TOKEN));
        var admittedTokens = BootTokens.bootTokens(CLIENT_TOKEN);
        var admittedReasons = new java.util.concurrent.CopyOnWriteArrayList<String>();

        admittedTokens.onSelfRefused(admittedReasons::add);
        assertThat(connects(admittedTokens, port)).isTrue();
        assertThat(admittedReasons).as("control: an admitted process is told nothing").isEmpty();

        var refusedTokens = BootTokens.bootTokens(OTHER_TOKEN);
        var refusedReasons = new java.util.concurrent.CopyOnWriteArrayList<String>();

        refusedTokens.onSelfRefused(refusedReasons::add);
        assertThat(connects(refusedTokens, port)).isFalse();
        assertThat(refusedReasons).as("the refused process is told why, exactly once")
                                  .singleElement()
                                  .asString()
                                  .contains(CLIENT_NODE.id())
                                  .contains("fresh identity");
    }

    @Test
    void dialer_differentTokenServer_isRefusedAndRetired() {
        var port = startServer(BootTokens.bootTokens(OTHER_TOKEN));
        var clientTokens = BootTokens.bootTokens(CLIENT_TOKEN);

        clientTokens.admit(SERVER_NODE, SERVER_TOKEN);

        assertThat(connects(clientTokens, port)).as("the dialer refuses a different process answering as a known NodeId")
                                                .isFalse();
        assertThat(clientTokens.isRetired(SERVER_NODE)).isTrue();
    }

    @Test
    void dialer_sameTokenServer_isAdmitted() {
        var port = startServer(BootTokens.bootTokens(SERVER_TOKEN));
        var clientTokens = BootTokens.bootTokens(CLIENT_TOKEN);

        clientTokens.admit(SERVER_NODE, SERVER_TOKEN);

        assertThat(connects(clientTokens, port)).as("control: the known process answers").isTrue();
        assertThat(clientTokens.isRetired(SERVER_NODE)).isFalse();
    }

    private static BootTokens serverRegistryKnowingClientAs(long clientToken) {
        var tokens = BootTokens.bootTokens(SERVER_TOKEN);

        tokens.admit(CLIENT_NODE, clientToken);

        return tokens;
    }

    private int startServer(BootTokens tokens) {
        server = QuicClusterServer.quicClusterServer(SERVER_NODE,
                                                     SERVER_ADDRESS,
                                                     Map.of(),
                                                     codec,
                                                     codec,
                                                     QuicTransportMetrics.quicTransportMetrics(),
                                                     serverSsl(),
                                                     Option.empty(),
                                                     (_, _, _) -> registered.incrementAndGet(),
                                                     (_, _) -> {},
                                                     tokens);
        server.start(0)
              .await(AWAIT_TIMEOUT)
              .onFailure(cause -> fail("server start: " + cause.message()));

        return server.boundPort().fold(() -> fail("server not bound"), port -> port);
    }

    private boolean connects(BootTokens tokens, int port) {
        if (client != null) {
            client.close().await(AWAIT_TIMEOUT);
        }

        client = QuicClusterClient.quicClusterClient(CLIENT_NODE,
                                                     CLIENT_ADDRESS,
                                                     Map.of(),
                                                     codec,
                                                     codec,
                                                     QuicTransportMetrics.quicTransportMetrics(),
                                                     clientSsl(),
                                                     Option.empty(),
                                                     (_, _) -> {},
                                                     tokens);

        return client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port))
                     .await(AWAIT_TIMEOUT)
                     .fold(_ -> false, _ -> true);
    }

    private static java.util.List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new java.util.ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);

        return all;
    }

    private static TlsConfig clusterTls(String nodeId) {
        var provider = SelfSignedCertificateProvider.selfSignedCertificateProvider(CLUSTER_SECRET.getBytes(StandardCharsets.UTF_8))
                                                    .unwrap();

        return TlsConfig.fromProvider(provider, nodeId, "localhost").unwrap();
    }

    private static QuicSslContext serverSsl() {
        return QuicTlsProvider.serverContext(clusterTls("boot-token-server"))
                              .fold(cause -> fail("server context: " + cause.message()), ssl -> ssl);
    }

    private static QuicSslContext clientSsl() {
        return QuicTlsProvider.clientContext(clusterTls("boot-token-client"))
                              .fold(cause -> fail("client context: " + cause.message()), ssl -> ssl);
    }
}
