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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.ClientAuthPolicy;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.handler.codec.quic.QuicSslContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #715 — cluster admission is gated on a client certificate from the cluster CA.
///
/// ## What was wrong
///
/// `QuicSslContextFactory` installed a trust manager on the cluster server but never called
/// `clientAuth(...)`, and Netty's `QuicSslContextBuilder` defaults to `ClientAuth.NONE` (verified in
/// the dependency bytecode). Nothing behind it re-checked either: `QuicClusterServer.handleHello`
/// answers any well-formed Hello and registers the peer, and `NetworkMessage.Hello` carries no
/// cluster identity at all. So inbound admission was reachability-only — any process that could
/// reach the port and speak `aether-cluster/1` was admitted, gossiped into SWIM, and counted by the
/// CTM, whatever secret it held.
///
/// The client half was missing too: `createClient` destructured `Mutual(_, trust)` and discarded the
/// identity, so no Aether client held key material. Mutual TLS was configured on both sides and
/// implemented on neither, which is why both halves had to ship together.
///
/// ## Why these tests are shaped this way
///
/// A handshake fails for many reasons. A test that only asserts "the certificate-less client did not
/// connect" would pass against a typo in the port, a codec mismatch, or a server that failed to
/// start — the instrument-illusion family this project keeps getting bitten by. So the rejection
/// cases are paired with a POSITIVE CONTROL on the same server, same port, same codecs: a client
/// holding a certificate from the same CA connects. Rejection only means something because
/// acceptance is demonstrated one test away under otherwise identical conditions.
///
/// Both server construction paths are covered — the initial context and the ROTATED context built
/// from a `CertificateBundle`. Fixing only the first would look correct in every test that never
/// rotates a certificate and would silently reopen the hole at the first rotation.
@Timeout(60)
class QuicClusterAdmissionTest {
    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final NodeId CLIENT_NODE = NodeId.randomNodeId();
    private static final NodeAddress SERVER_ADDRESS = new NodeAddress("127.0.0.1", 9100);
    private static final NodeAddress CLIENT_ADDRESS = new NodeAddress("127.0.0.1", 9101);
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(8).seconds();

    private static final String CLUSTER_SECRET = "admission-test-cluster-secret";
    private static final String FOREIGN_SECRET = "a-different-clusters-secret";

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

    private static java.util.List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new java.util.ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);

        return all;
    }

    // ----- context builders -----

    private static TlsConfig clusterTls(String nodeId, String secret) {
        var provider = SelfSignedCertificateProvider.selfSignedCertificateProvider(secret.getBytes(StandardCharsets.UTF_8))
                                                    .unwrap();

        return TlsConfig.fromProvider(provider, nodeId, "localhost").unwrap();
    }

    /// The cluster server context as production builds it: REQUIRED plus the cluster CA.
    private static QuicSslContext clusterServerSsl() {
        return QuicTlsProvider.serverContext(clusterTls("admission-server", CLUSTER_SECRET))
                              .fold(cause -> fail("server context: " + cause.message()), ssl -> ssl);
    }

    /// The ROTATED server context — the second construction path (`createServerFromBundle`), which a
    /// certificate rotation takes. It must enforce the same policy as the initial one.
    private static QuicSslContext rotatedClusterServerSsl() {
        var provider = SelfSignedCertificateProvider.selfSignedCertificateProvider(CLUSTER_SECRET.getBytes(StandardCharsets.UTF_8))
                                                    .unwrap();
        var bundle = provider.issueCertificate("admission-server-rotated", "localhost").unwrap();

        return QuicSslContextFactory.createServerFromBundle(bundle,
                                                            ClientAuthPolicy.REQUIRED,
                                                            QuicTlsProvider.CLUSTER_PROTOCOL)
                                    .fold(cause -> fail("rotated server context: " + cause.message()), ssl -> ssl);
    }

    /// An operator-facing server: same key material, but NOT_REQUESTED. This is the app / management
    /// HTTP3 shape, whose clients hold no cluster certificate.
    private static QuicSslContext operatorServerSsl() {
        return QuicSslContextFactory.createServer(clusterTls("operator-server", CLUSTER_SECRET),
                                                   ClientAuthPolicy.NOT_REQUESTED,
                                                   QuicTlsProvider.CLUSTER_PROTOCOL)
                                    .fold(cause -> fail("operator context: " + cause.message()), ssl -> ssl);
    }

    private static QuicSslContext clientSsl(TlsConfig config) {
        return QuicTlsProvider.clientContext(config)
                              .fold(cause -> fail("client context: " + cause.message()), ssl -> ssl);
    }

    /// A client that trusts the cluster CA but presents NO certificate — the #715 attacker shape.
    private static QuicSslContext certificatelessClientSsl() {
        var provider = SelfSignedCertificateProvider.selfSignedCertificateProvider(CLUSTER_SECRET.getBytes(StandardCharsets.UTF_8))
                                                    .unwrap();
        var ca = provider.caCertificate().unwrap();

        return clientSsl(new TlsConfig.Client(new TlsConfig.Trust.FromCaBytes(ca.caCertificatePem()), Option.empty()));
    }

    // ----- harness -----

    private int startServer(QuicSslContext serverSsl) {
        server = QuicClusterServer.quicClusterServer(SERVER_NODE, SERVER_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), serverSsl, Option.empty(), (_, _, _) -> {}, (_, _) -> {});
        server.start(0)
              .await(AWAIT_TIMEOUT)
              .onFailure(cause -> fail("server start: " + cause.message()));

        return server.boundPort().fold(() -> fail("server not bound"), port -> port);
    }

    private boolean connects(QuicSslContext clientSsl, int port) {
        client = QuicClusterClient.quicClusterClient(CLIENT_NODE, CLIENT_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl, Option.empty(), (_, _) -> {});

        return client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port))
                     .await(AWAIT_TIMEOUT)
                     .fold(_ -> false, _ -> true);
    }

    @Nested
    class ClusterAdmission {
        /// THE POSITIVE CONTROL. Without this, every rejection below could be a broken harness.
        @Test
        void clientWithClusterCertificate_isAdmitted() {
            var port = startServer(clusterServerSsl());

            assertThat(connects(clientSsl(clusterTls("admission-client", CLUSTER_SECRET)), port))
                .as("a peer holding a certificate from the cluster CA must be admitted — this is the "
                    + "control that makes the rejections below meaningful")
                .isTrue();
        }

        /// The defect itself: this connection SUCCEEDED before #715.
        @Test
        void certificatelessClient_isRejected() {
            var port = startServer(clusterServerSsl());

            assertThat(connects(certificatelessClientSsl(), port))
                .as("#715: a peer presenting no certificate must not be admitted to the cluster — "
                    + "before the fix this succeeded, and the peer was then counted by the CTM")
                .isFalse();
        }

        /// The cross-cluster case that #715's incident actually was: a real, well-formed node whose
        /// certificate is signed by a DIFFERENT cluster's CA.
        @Test
        void clientFromForeignCluster_isRejected() {
            var port = startServer(clusterServerSsl());

            assertThat(connects(clientSsl(clusterTls("foreign-node", FOREIGN_SECRET)), port))
                .as("a node from another cluster must not be admitted, however well-formed it is")
                .isFalse();
        }
    }

    @Nested
    class RotatedContext {
        /// Finding 3 of the design pass: fixing only the initial path would look correct in every
        /// test that never rotates, then reopen the hole at the first certificate rotation.
        @Test
        void certificatelessClient_isRejected_againstRotatedContext() {
            var port = startServer(rotatedClusterServerSsl());

            assertThat(connects(certificatelessClientSsl(), port))
                .as("the rotated context must enforce the same admission policy as the initial one")
                .isFalse();
        }

        @Test
        void clientWithClusterCertificate_isAdmitted_againstRotatedContext() {
            var port = startServer(rotatedClusterServerSsl());

            assertThat(connects(clientSsl(clusterTls("admission-client", CLUSTER_SECRET)), port))
                .as("control for the rotated path — rejection above must not be a broken context")
                .isTrue();
        }
    }

    @Nested
    class OperatorSurfacesUnaffected {
        /// The regression the design pass predicted and this pins. Inferring REQUIRED from the
        /// `Mutual` config variant would have rejected every browser, CLI and dashboard client,
        /// because `Main` hands the SAME `TlsConfig` instance to the cluster transport and to both
        /// HTTP/3 servers. This is the test that stops a later "simplification" toward that shape.
        @Test
        void certificatelessClient_isAccepted_whenPolicyIsNotRequested() {
            var port = startServer(operatorServerSsl());

            assertThat(connects(certificatelessClientSsl(), port))
                .as("operator-facing surfaces must still accept clients that hold no cluster "
                    + "certificate — they authenticate by API key, not by mTLS")
                .isTrue();
        }
    }
}
