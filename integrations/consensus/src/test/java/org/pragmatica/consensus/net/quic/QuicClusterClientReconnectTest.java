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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies the per-peer datagram channel tracking added to fix the QUIC client UDP-socket
/// leak. Each `connect(peerId, ...)` allocates a fresh ephemeral UDP socket via
/// `bootstrap.bind(0)` — without per-peer tracking the previous reference was overwritten
/// by `volatile Channel datagramChannel` and the socket leaked to JVM exit.
@Timeout(30)
class QuicClusterClientReconnectTest {

    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final NodeId CLIENT_NODE = NodeId.randomNodeId();
    private static final NodeAddress SERVER_ADDRESS = new NodeAddress("127.0.0.1", 9100);
    private static final NodeAddress CLIENT_ADDRESS = new NodeAddress("127.0.0.1", 9101);
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();

    private SliceCodec codec;
    private QuicClusterServer server;
    private QuicClusterClient client;

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
    void reconnect_closesPriorDatagramChannel_noLeak() {
        startServer();
        var port = server.boundPort().fold(() -> fail("server not bound"), p -> p);
        client = createClient();

        // First connect: one datagram channel allocated.
        client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port))
              .await(AWAIT_TIMEOUT)
              .onFailure(cause -> fail("first connect failed: " + cause.message()));
        assertThat(client.datagramChannelCount())
            .as("one datagram channel after the first connect")
            .isEqualTo(1);

        // Reconnect to the SAME peer: prior channel must be closed before new bind.
        // If the leak persisted, count would climb to 2.
        client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port))
              .await(AWAIT_TIMEOUT)
              .onFailure(cause -> fail("second connect failed: " + cause.message()));
        assertThat(client.datagramChannelCount())
            .as("reconnect to the same peer must not leak a datagram channel")
            .isEqualTo(1);

        // Explicit close drops the entry.
        client.closeDatagramChannel(SERVER_NODE).await(AWAIT_TIMEOUT);
        assertThat(client.datagramChannelCount())
            .as("closeDatagramChannel removes the per-peer entry")
            .isEqualTo(0);
    }

    @Test
    void closeDatagramChannel_unknownPeer_resolvesImmediately() {
        client = createClient();
        // No connect was ever performed — calling close on an unknown peer is a no-op.
        client.closeDatagramChannel(NodeId.randomNodeId())
              .await(AWAIT_TIMEOUT)
              .onFailure(cause -> fail("unexpected failure: " + cause.message()));
        assertThat(client.datagramChannelCount()).isEqualTo(0);
    }

    @Test
    void connect_unresolvedAddress_failsCleanlyWithoutNpe() {
        client = createClient();
        // An unresolved DNS name (e.g. a stale/unknown peer host) must NOT crash the node with
        // an NPE inside Netty's QUIC SockaddrIn — it must fail the dial down the normal
        // connection-failure path so the caller can retry on a later tick.
        var unresolved = InetSocketAddress.createUnresolved("no-such-host.invalid", 9999);
        assertThat(unresolved.isUnresolved()).isTrue();

        client.connect(SERVER_NODE, unresolved)
              .await(AWAIT_TIMEOUT)
              .onSuccess(_ -> fail("connect to unresolved address must fail"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(QuicTransportError.UnresolvedAddress.class));

        // No datagram channel/UDP socket was allocated for the failed dial.
        assertThat(client.datagramChannelCount()).isEqualTo(0);
    }

    /// Wave-3 dialer-side Hello identity verification (cluster-topology-overhaul spec): dialing
    /// a live server under a WRONG expected NodeId must fail the connect promise with
    /// [QuicTransportError.IdentityMismatch] — the connection is closed un-attached, down the
    /// same failure path as any failed dial (so the caller's backoff machinery engages).
    @Test
    void connect_helloSenderMismatch_failsWithIdentityMismatch() {
        startServer();
        var port = server.boundPort().fold(() -> fail("server not bound"), p -> p);
        client = createClient();
        var wrongExpectedId = NodeId.randomNodeId();

        client.connect(wrongExpectedId, new InetSocketAddress("127.0.0.1", port))
              .await(AWAIT_TIMEOUT)
              .onSuccess(_ -> fail("connect under a mismatched expected identity must fail"))
              .onFailure(cause -> assertIdentityMismatch(cause, wrongExpectedId));
    }

    /// Wave-3 counterpart: a matching Hello sender attaches exactly as before — the connect
    /// promise succeeds and the connection is registered under the verified (dialed) identity.
    @Test
    void connect_helloSenderMatches_succeedsWithVerifiedIdentity() {
        startServer();
        var port = server.boundPort().fold(() -> fail("server not bound"), p -> p);
        client = createClient();

        client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port))
              .await(AWAIT_TIMEOUT)
              .onFailure(cause -> fail("connect with matching identity failed: " + cause.message()))
              .onSuccess(connection -> assertThat(connection.peerId()).isEqualTo(SERVER_NODE));
    }

    private static void assertIdentityMismatch(Cause cause, NodeId expected) {
        assertThat(cause).isInstanceOf(QuicTransportError.IdentityMismatch.class);
        var mismatch = (QuicTransportError.IdentityMismatch) cause;
        assertThat(mismatch.expected()).isEqualTo(expected);
        assertThat(mismatch.actual()).isEqualTo(SERVER_NODE);
    }

    private void startServer() {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                       .fold(_ -> fail("server SSL failed"), ssl -> ssl);
        server = QuicClusterServer.quicClusterServer(
            SERVER_NODE, SERVER_ADDRESS, Map.of(), codec, codec, QuicTransportMetrics.quicTransportMetrics(), serverSsl, Option.empty(),
            (_, _, _) -> {}, (_, _) -> {}
        );
        server.start(0).await(AWAIT_TIMEOUT)
              .onFailure(cause -> fail("server start failed: " + cause.message()));
    }

    private QuicClusterClient createClient() {
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                       .fold(_ -> fail("client SSL failed"), ssl -> ssl);
        return QuicClusterClient.quicClusterClient(
            CLIENT_NODE, CLIENT_ADDRESS, Map.of(), codec, codec, QuicTransportMetrics.quicTransportMetrics(), clientSsl, Option.empty(),
            (_, _) -> {}
        );
    }

    private static java.util.List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
