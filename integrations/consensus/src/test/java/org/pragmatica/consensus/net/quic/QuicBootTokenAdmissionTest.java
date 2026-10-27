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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.IdentityFacts;
import org.pragmatica.consensus.net.IdentityLedger;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
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

    /// #2429: a registry that cannot answer yet (its identity ledger is not loaded) is not a verdict. The acceptor closes the
    /// connection WITHOUT a HelloRefused, which would make the dialer believe its identity is retired and exit.
    @Test
    void acceptor_unavailableRegistry_closesWithoutTellingTheProcessItsIdentityIsRetired(@TempDir Path directory) {
        var serverTokens = BootTokens.bootTokens(SERVER_TOKEN);

        serverTokens.attachLedger(IdentityLedger.identityLedger(directory.resolve("ledger"), IdentityLedger.DurableWriter.fileOps()));
        var port = startServer(serverTokens);
        var dialerTokens = BootTokens.bootTokens(CLIENT_TOKEN);
        var told = new CopyOnWriteArrayList<String>();

        dialerTokens.onSelfRefused(told::add);

        var outcome = connectResult(dialerTokens, port);

        assertThat(outcome.isFailure()).as("not admitted while the registry cannot answer").isTrue();
        outcome.onFailure(cause -> assertThat(cause.message()).as("the acceptor CLOSED the connection (a dial left open would only time out)")
                                                              .contains("closed the connection"));
        assertThat(told).as("and not told its identity is retired").isEmpty();
        assertThat(registered.get()).isZero();
        assertThat(serverTokens.refusals()).as("not a refusal").isZero();
        assertThat(serverTokens.isRetired(CLIENT_NODE)).isFalse();
    }

    /// #2429 write-ahead: a conflicting process is refused only once its retirement is durable in the acceptor's ledger. While
    /// the write is held the dial stays undecided and nothing is registered; when it completes the process is refused AND told.
    @Test
    void acceptor_conflictWaitsForTheLedger_thenRefusesAndTellsTheProcess(@TempDir Path directory) throws Exception {
        var writer = new HeldDirectoryForce();
        var serverTokens = BootTokens.bootTokens(SERVER_TOKEN);
        var ledger = IdentityLedger.identityLedger(directory.resolve("ledger"), writer);

        serverTokens.attachLedger(ledger);
        serverTokens.preload(ledger.open(true).facts(), false);
        serverTokens.admit(CLIENT_NODE, CLIENT_TOKEN);
        writer.hold();
        var port = startServer(serverTokens);
        var refusedTokens = BootTokens.bootTokens(OTHER_TOKEN);
        var told = new CopyOnWriteArrayList<String>();

        refusedTokens.onSelfRefused(told::add);
        var dial = CompletableFuture.supplyAsync(() -> connects(refusedTokens, port));

        Thread.sleep(1500);
        assertThat(dial.isDone()).as("undecided while the retirement is not durable").isFalse();
        assertThat(told).isEmpty();
        assertThat(registered.get()).isZero();

        writer.release();

        assertThat(dial.get(15, TimeUnit.SECONDS)).isFalse();
        assertThat(told).singleElement().asString().contains("fresh identity");
        assertThat(serverTokens.isRetired(CLIENT_NODE)).isTrue();
    }

    /// #2429, the dialer's side: a registry that cannot answer yet fails the dial on the ordinary connect-failure path. It does
    /// not retire the server's NodeId and counts no refusal; once the ledger is loaded the same dial connects.
    @Test
    void dialer_unavailableRegistry_failsTheDialWithoutRetiringTheServer_andConnectsOnceLoaded(@TempDir Path directory) {
        var port = startServer(BootTokens.bootTokens(SERVER_TOKEN));
        var clientTokens = BootTokens.bootTokens(CLIENT_TOKEN);

        clientTokens.attachLedger(IdentityLedger.identityLedger(directory.resolve("ledger"), IdentityLedger.DurableWriter.fileOps()));

        assertThat(connects(clientTokens, port)).as("the dial fails while the registry cannot answer").isFalse();
        assertThat(clientTokens.isRetired(SERVER_NODE)).as("and the server's NodeId is not retired").isFalse();
        assertThat(clientTokens.refusals()).isZero();

        clientTokens.preload(IdentityFacts.identityFacts(), false);

        assertThat(connects(clientTokens, port)).as("control: the same dial connects once the ledger is loaded").isTrue();
    }

    /// #2429 write-ahead, the dialer's side: a different process answering as a known NodeId is refused only once its
    /// retirement is durable in the DIALER's ledger. While the write is held the dial stays undecided; afterwards it fails
    /// and the NodeId is retired.
    @Test
    void dialer_conflictWaitsForTheLedger_thenRefusesAndRetires(@TempDir Path directory) throws Exception {
        var writer = new HeldDirectoryForce();
        var clientTokens = BootTokens.bootTokens(CLIENT_TOKEN);
        var ledger = IdentityLedger.identityLedger(directory.resolve("ledger"), writer);

        clientTokens.attachLedger(ledger);
        clientTokens.preload(ledger.open(true).facts(), false);
        clientTokens.admit(SERVER_NODE, SERVER_TOKEN);
        writer.hold();
        var port = startServer(BootTokens.bootTokens(OTHER_TOKEN));
        var dial = CompletableFuture.supplyAsync(() -> connects(clientTokens, port));

        Thread.sleep(1500);
        assertThat(dial.isDone()).as("undecided while the retirement is not durable").isFalse();

        writer.release();

        assertThat(dial.get(15, TimeUnit.SECONDS)).isFalse();
        assertThat(clientTokens.isRetired(SERVER_NODE)).isTrue();
    }

    /// Forces and renames as usual, but holds the directory force while the gate is closed.
    private static final class HeldDirectoryForce implements IdentityLedger.DurableWriter {
        private final IdentityLedger.DurableWriter real = IdentityLedger.DurableWriter.fileOps();
        private volatile CountDownLatch gate = new CountDownLatch(0);

        void hold() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }

        @Override
        public Result<Unit> writeForced(Path file, byte[] content) {
            return real.writeForced(file, content);
        }

        @Override
        public Result<Unit> moveAtomic(Path source, Path target) {
            return real.moveAtomic(source, target);
        }

        @Override
        public Result<Unit> forceDirectory(Path directory) {
            awaitGate();

            return real.forceDirectory(directory);
        }

        private void awaitGate() {
            try {
                gate.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
        return connectResult(tokens, port).isSuccess();
    }

    private Result<?> connectResult(BootTokens tokens, int port) {
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
                     .await(AWAIT_TIMEOUT);
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
