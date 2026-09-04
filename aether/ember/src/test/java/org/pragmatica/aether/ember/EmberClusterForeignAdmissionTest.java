// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.quic.QuicClusterClient;
import org.pragmatica.consensus.net.quic.QuicClusterServer;
import org.pragmatica.consensus.net.quic.QuicTlsProvider;
import org.pragmatica.consensus.net.quic.QuicTransportMetrics;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.handler.codec.quic.QuicSslContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #715 — pins that each `EmberCluster` instance derives its own cluster QUIC/SWIM identity, so two
/// independently-created instances (e.g. Forge started twice on one machine, or any two test
/// harnesses that never called the sanctioned `withClusterSecret` join seam) cannot admit each
/// other's nodes — and that two instances explicitly given the SAME secret via `withClusterSecret`
/// still can (the positive control).
///
/// Written red-first against a pre-fix observability scaffold on `EmberCluster` (`currentClusterSecret`,
/// `wiredCertificateProvider`, `wiredQuicTls`): before the fix, EVERY `EmberCluster` instance mirrors
/// the one hardcoded literal secret, so the rejection/wiring tests below fail for that reason, not a
/// compile error. `wiredCertificateProvider`/`wiredQuicTls` read the `certificateProvider`/`quicTls`
/// actually present in the most recently constructed node's real `AetherNodeConfig` object — never a
/// value mirrored independently at construction time — so reverting either production wiring line
/// alone (leaving the other untouched) still flips the corresponding test red.
class EmberClusterForeignAdmissionTest {
    private static final NodeId CLIENT_NODE = NodeId.randomNodeId();
    private static final NodeAddress CLIENT_ADDRESS = new NodeAddress("127.0.0.1", 19291);
    private static final NodeAddress SERVER_ADDRESS = new NodeAddress("127.0.0.1", 19290);
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(8).seconds();

    private EmberCluster clusterA;
    private EmberCluster clusterB;
    private QuicClusterServer server;
    private QuicClusterClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().await(AWAIT_TIMEOUT);
        }
        if (server != null) {
            server.stop().await(AWAIT_TIMEOUT);
        }
        if (clusterA != null) {
            clusterA.stop().await();
        }
        if (clusterB != null) {
            clusterB.stop().await();
        }
    }

    private static SliceCodec codec() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);

        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), all);
    }

    /// Test 1 — secret uniqueness: two independently-created `EmberCluster` instances must derive
    /// DISTINCT cluster identity secrets. Neither instance is started — the secret is fixed at
    /// construction, so this is a cheap, non-networked check.
    @Test
    @Timeout(10)
    void distinctInstances_deriveDistinctClusterSecrets() {
        clusterA = emberCluster(1, 25300, 25400, 25500, "foreign-a");
        clusterB = emberCluster(1, 25310, 25410, 25510, "foreign-b");

        assertThat(clusterA.currentClusterSecret())
            .as("#715: two independently-created EmberCluster instances must not share cluster "
                + "identity, or one instance's nodes can admit the other's")
            .isNotEqualTo(clusterB.currentClusterSecret());
    }

    /// Test 2 — the `certificateProvider` actually wired into a constructed node's
    /// `AetherNodeConfig` must be present, so SWIM gossip encryption is live rather than silently
    /// falling back to `GossipEncryptor.none()`. Requires a real `start()` so `createNode` runs.
    @Test
    @Timeout(120)
    void constructedNode_hasCertificateProviderWired() {
        clusterA = emberCluster(3, 25320, 25420, 25520, "foreign-c");

        clusterA.start()
                .await()
                .onFailure(cause -> fail("cluster start: " + cause.message()));

        assertThat(clusterA.wiredCertificateProvider().isPresent())
            .as("#715: a constructed node's certificateProvider must be present, or SWIM gossip "
                + "encryption silently falls back to GossipEncryptor.none()")
            .isTrue();
    }

    /// Test 3 — the actual admission mechanism, through the production QUIC/TLS wiring: a QUIC
    /// client built from a DIFFERENT `EmberCluster` instance's wired TLS must be rejected by a
    /// server built from this instance's wired TLS. Both clusters are started for real, so
    /// `createNode` runs and [EmberCluster#wiredQuicTls] reflects the exact `TlsConfig` object
    /// `buildForgeQuicTls` produced and threaded into the constructed node's real `AetherNodeConfig`
    /// — reverting `buildForgeQuicTls` back to the shared literal makes both clusters present the
    /// same TLS identity and this test goes red.
    @Test
    @Timeout(120)
    void clientFromDifferentEmberInstance_isRejected() {
        clusterA = emberCluster(3, 25330, 25430, 25530, "foreign-d");
        clusterB = emberCluster(3, 25340, 25440, 25540, "foreign-e");

        clusterA.start()
                .await()
                .onFailure(cause -> fail("cluster A start: " + cause.message()));
        clusterB.start()
                .await()
                .onFailure(cause -> fail("cluster B start: " + cause.message()));

        var codec = codec();
        var serverNode = NodeId.randomNodeId();

        var serverTls = clusterA.wiredQuicTls()
                                .fold(() -> fail("cluster A: no wired QUIC TLS"), tls -> tls);
        var serverSsl = serverSsl(serverTls);

        server = QuicClusterServer.quicClusterServer(serverNode, SERVER_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), serverSsl, Option.empty(), (_, _, _) -> {}, (_, _) -> {});
        server.start(0)
              .await(AWAIT_TIMEOUT)
              .onFailure(cause -> fail("server start: " + cause.message()));

        var port = server.boundPort().fold(() -> fail("server not bound"), p -> p);

        var clientTls = clusterB.wiredQuicTls()
                                .fold(() -> fail("cluster B: no wired QUIC TLS"), tls -> tls);
        var clientSsl = clientSsl(clientTls);

        client = QuicClusterClient.quicClusterClient(CLIENT_NODE, CLIENT_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl, Option.empty(), (_, _) -> {});

        var admitted = client.connect(serverNode, new InetSocketAddress("127.0.0.1", port))
                             .await(AWAIT_TIMEOUT)
                             .fold(_ -> false, _ -> true);

        assertThat(admitted)
            .as("#715: a QUIC client built from a DIFFERENT EmberCluster instance's wired TLS must "
                + "not be admitted to this instance's cluster")
            .isFalse();
    }

    /// Test 4 — positive control: two instances explicitly given the SAME cluster secret via the
    /// sanctioned [EmberCluster#withClusterSecret] override must be able to admit each other's real
    /// QUIC nodes, so test 3 above is pinning a real rejection of DIFFERENT secrets rather than QUIC
    /// simply never admitting anyone. Without this test, deleting the entire admission check would
    /// make test 3 pass for the wrong reason (every connection rejected) and go undetected.
    @Test
    @Timeout(120)
    void clientFromSameClusterSecret_isAdmitted() {
        var sharedSecret = new byte[32];
        new SecureRandom().nextBytes(sharedSecret);

        clusterA = emberCluster(3, 25350, 25450, 25550, "foreign-f");
        clusterB = emberCluster(3, 25360, 25460, 25560, "foreign-g");
        clusterA.withClusterSecret(sharedSecret);
        clusterB.withClusterSecret(sharedSecret);

        clusterA.start()
                .await()
                .onFailure(cause -> fail("cluster A start: " + cause.message()));
        clusterB.start()
                .await()
                .onFailure(cause -> fail("cluster B start: " + cause.message()));

        var codec = codec();
        var serverNode = NodeId.randomNodeId();

        var serverTls = clusterA.wiredQuicTls()
                                .fold(() -> fail("cluster A: no wired QUIC TLS"), tls -> tls);
        var serverSsl = serverSsl(serverTls);

        server = QuicClusterServer.quicClusterServer(serverNode, SERVER_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), serverSsl, Option.empty(), (_, _, _) -> {}, (_, _) -> {});
        server.start(0)
              .await(AWAIT_TIMEOUT)
              .onFailure(cause -> fail("server start: " + cause.message()));

        var port = server.boundPort().fold(() -> fail("server not bound"), p -> p);

        var clientTls = clusterB.wiredQuicTls()
                                .fold(() -> fail("cluster B: no wired QUIC TLS"), tls -> tls);
        var clientSsl = clientSsl(clientTls);

        client = QuicClusterClient.quicClusterClient(CLIENT_NODE, CLIENT_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl, Option.empty(), (_, _) -> {});

        var admitted = client.connect(serverNode, new InetSocketAddress("127.0.0.1", port))
                             .await(AWAIT_TIMEOUT)
                             .fold(_ -> false, _ -> true);

        assertThat(admitted)
            .as("#715: two EmberCluster instances given the SAME secret via withClusterSecret must "
                + "admit each other's real QUIC nodes (positive control)")
            .isTrue();
    }

    private static QuicSslContext serverSsl(TlsConfig config) {
        return QuicTlsProvider.serverContext(config)
                              .fold(cause -> fail("server context: " + cause.message()), ssl -> ssl);
    }

    private static QuicSslContext clientSsl(TlsConfig config) {
        return QuicTlsProvider.clientContext(config)
                              .fold(cause -> fail("client context: " + cause.message()), ssl -> ssl);
    }
}
