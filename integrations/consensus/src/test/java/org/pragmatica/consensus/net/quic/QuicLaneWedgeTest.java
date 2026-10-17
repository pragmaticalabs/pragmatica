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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.quic.QuicStreamChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1578 — the CI wedge, stated as behaviour only (i-genesis-stall's forced-timing hook, in-JVM). After the dialer
/// registered all its lanes, the acceptor opens a CONSENSUS stand-in (the hook: 150 ms after the Hello). Each end then
/// writes on whatever stream ITS OWN lane table resolves CONSENSUS to — what `QuicClusterNetwork.writeToStream` would
/// pick — and every acknowledged write must be read by the other end. No stream-id assertions: this pins the outcome,
/// not the rule. It holds with either half of the fix (reader or ownership) and fails only when both are gone, as on
/// rc4: acknowledged writes, none read, none failed. Adopted from the v1677 verifier's probe.
@Timeout(60)
class QuicLaneWedgeTest {
    private static final NodeId ACCEPTOR = new NodeId("lw-acceptor");
    private static final NodeId DIALER = new NodeId("lw-dialer");
    private static final NodeAddress UNUSED_ADDRESS = new NodeAddress("127.0.0.1", 9000);
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final int N = 100;

    private final SliceCodec codec = LaneProbe.codec();
    private final List<Object> receivedByAcceptor = new CopyOnWriteArrayList<>();
    private final List<Object> receivedByDialer = new CopyOnWriteArrayList<>();
    private final AtomicReference<QuicPeerConnection> acceptorSide = new AtomicReference<>();
    private QuicClusterServer server;
    private QuicClusterClient client;
    private QuicPeerConnection dialerSide;

    @BeforeEach
    void setUp() {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client")).fold(_ -> fail("client ssl"), ssl -> ssl);
        server = QuicClusterServer.quicClusterServer(ACCEPTOR, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), serverSsl, Option.empty(),
                                                     (connection, _, _) -> acceptorSide.set(connection),
                                                     (_, message) -> receivedByAcceptor.add(message));
        server.start(0).await(AWAIT).onFailure(cause -> fail("server start: " + cause.message()));
        var port = server.boundPort().fold(() -> fail("server not bound"), bound -> bound);
        client = QuicClusterClient.quicClusterClient(DIALER, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl, Option.empty(),
                                                     (_, message) -> receivedByDialer.add(message));
        dialerSide = client.connect(ACCEPTOR, new InetSocketAddress("127.0.0.1", port)).await(AWAIT)
                           .fold(cause -> fail("dial: " + cause.message()), connection -> connection);
        awaitTrue(() -> acceptorSide.get() != null
                        && Arrays.stream(StreamType.values()).allMatch(l -> acceptorSide.get().stream(l).isPresent()),
                  "the acceptor registered every lane the dialer opened");
    }

    @AfterEach
    void tearDown() {
        if (client != null) { client.close().await(AWAIT); }
        if (server != null) { server.stop().await(AWAIT); }
    }

    @Test
    void igsHook_acceptorStandInAfterDialerLanes_consensusCarriesEveryAcknowledgedWriteBothWays() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(150));
        var opened = new CompletableFuture<Option<QuicStreamChannel>>();
        acceptorSide.get().openLane(StreamType.CONSENSUS, opened::complete);
        opened.orTimeout(AWAIT.millis(), TimeUnit.MILLISECONDS).join();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));

        burst(acceptorSide.get(), ACCEPTOR, "a2d-", receivedByDialer);
        burst(dialerSide, DIALER, "d2a-", receivedByAcceptor);
    }

    private void burst(QuicPeerConnection from, NodeId sender, String prefix, List<Object> readBy) {
        var stream = from.stream(StreamType.CONSENSUS).fold(() -> fail(sender + " has no CONSENSUS stream"), s -> s);
        var ok = new AtomicInteger();
        var bad = new AtomicInteger();
        IntStream.range(0, N).forEach(i -> stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(sender, StreamType.CONSENSUS, prefix + i))))
                                                 .addListener(f -> (f.isSuccess() ? ok : bad).incrementAndGet()));
        awaitTrue(() -> ok.get() + bad.get() == N, "every write resolves");
        assertThat(ok.get()).as(prefix + " arming: some writes succeeded (failed=" + bad.get() + ")").isPositive();
        try {
            awaitTrue(() -> read(readBy, prefix) == ok.get(), "x");
        } catch (AssertionError e) {
            fail(prefix + " silent loss: read=" + read(readBy, prefix) + " succeeded=" + ok.get() + " failed=" + bad.get()
                 + " streamId=" + stream.streamId());
        }
    }

    private static long read(List<Object> received, String prefix) {
        return received.stream().filter(LaneProbe.class::isInstance).map(LaneProbe.class::cast).map(LaneProbe::marker)
                       .filter(m -> m.startsWith(prefix)).distinct().count();
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        var deadline = System.nanoTime() + AWAIT.nanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) { return; }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        fail("Timed out waiting for: " + what);
    }
}
