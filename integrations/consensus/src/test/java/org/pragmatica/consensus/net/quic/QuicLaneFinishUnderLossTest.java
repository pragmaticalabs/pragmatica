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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1727 — acknowledged writes on a stream the peer finishes, under packet LOSS. Same shape as
/// `QuicLaneOwnershipTest.peerFinishesTheStreamMidBurst_…` (the dialer bursts onto the acceptor's stand-in; at the pivot
/// it opens its own lane stream, the acceptor finishes the stand-in, and the dialer finishes its half behind its
/// queued writes), but the connection runs through a relay that drops datagrams in both directions (default 3%,
/// `-Dquic.test.dropRate`). Armed per run: loss really happened (the dialer's QUIC stack lost bytes) and the acceptor
/// really finished the stand-in, and the finish landed MID-burst: at the pivot the burst waits until the dialer's stand-in
/// has shut its output (the acceptor's FIN arrived and ours went out), so the writes after it fail visibly (failed > 0)
/// while the writes before it are still in flight under loss. Every write the dialer saw succeed must reach the acceptor.
@Timeout(120)
class QuicLaneFinishUnderLossTest {
    private static final NodeId ACCEPTOR = new NodeId("lf-acceptor");
    private static final NodeId DIALER = new NodeId("lf-dialer");
    private static final NodeAddress UNUSED_ADDRESS = new NodeAddress("127.0.0.1", 9000);
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(20).seconds();
    private static final StreamType LANE = StreamType.FORWARD;
    private static final double DROP_RATE = Double.parseDouble(System.getProperty("quic.test.dropRate", "0.03"));
    private static final int SENT = 400;

    private final SliceCodec codec = LaneProbe.codec();
    private final List<Object> receivedByAcceptor = new CopyOnWriteArrayList<>();
    private final List<Object> receivedByDialer = new CopyOnWriteArrayList<>();
    private final AtomicReference<QuicPeerConnection> acceptorSide = new AtomicReference<>();
    private QuicClusterServer server;
    private QuicClusterClient client;
    private UdpGate relay;
    private QuicPeerConnection dialerSide;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().await(AWAIT);
        }
        if (server != null) {
            server.stop().await(AWAIT);
        }
        if (relay != null) {
            relay.close();
        }
    }

    @RepeatedTest(10)
    void peerFinishesTheStreamMidBurst_underLoss_everyAcknowledgedWriteIsDelivered(RepetitionInfo repetition) {
        connectThroughLossyRelay(repetition.getCurrentRepetition());
        adoptAcceptorStandIn();
        var standIn = dialerSide.stream(LANE).unwrap();
        var acceptorStandIn = acceptorSide.get().stream(LANE).unwrap();
        var closes = new CopyOnWriteArrayList<String>();
        var t0 = System.nanoTime();

        standIn.closeFuture().addListener(_ -> closes.add("dialer@" + (System.nanoTime() - t0) / 1_000_000 + "ms"));
        acceptorStandIn.closeFuture().addListener(_ -> closes.add("acceptor@" + (System.nanoTime() - t0) / 1_000_000 + "ms"));
        var padding = "x".repeat(8 * 1024);
        // By marker, not by count: a failed write that is delivered anyway must not stand in for an acknowledged one lost.
        var acked = ConcurrentHashMap.<String>newKeySet();
        var failed = new AtomicInteger();

        IntStream.range(0, SENT)
                 .forEach(i -> burstWrite(standIn, i, padding, SENT / 2, acked, failed));
        awaitTrue(() -> acked.size() + failed.get() == SENT, "every write resolves");

        var deadline = System.nanoTime() + AWAIT.nanos();

        while (System.nanoTime() < deadline && !finMarkers().containsAll(acked)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
        if (!finMarkers().containsAll(acked)) {
            var at = finMarkers().size();
            var statsAt = stats();
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));
            fail("LOST delivered@20s=" + at + " delivered@35s=" + finMarkers().size() + " succeeded=" + acked.size()
                 + " failed=" + failed.get() + " missing=" + missing(acked) + " closes=" + closes
                 + " dialerStandIn.active=" + standIn.isActive() + " acceptorStandIn.active=" + acceptorStandIn.isActive()
                 + " statsAt20s=" + statsAt + " statsAt35s=" + stats());
        }
        assertThat(lostBytes(dialerSide.connection())).as("arming: the relay made the dialer's QUIC stack lose bytes").isPositive();
        assertThat(failed.get()).as("arming: the finish landed mid-burst, so the writes after it failed visibly").isPositive();
        assertThat(acceptorSide.get().stream(LANE).map(QuicStreamChannel::streamId))
            .as("arming: the acceptor moved the lane off the stand-in, so it finished it")
            .isNotEqualTo(Option.some(standIn.streamId()));
    }

    private void connectThroughLossyRelay(long seed) {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client")).fold(_ -> fail("client ssl"), ssl -> ssl);

        server = QuicClusterServer.quicClusterServer(ACCEPTOR, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), serverSsl, Option.empty(),
                                                     (connection, _, _) -> acceptorSide.set(connection),
                                                     (_, message) -> receivedByAcceptor.add(message));
        server.start(0).await(AWAIT).onFailure(cause -> fail("server start: " + cause.message()));
        var port = server.boundPort().fold(() -> fail("server not bound"), bound -> bound);

        relay = UdpGate.lossyRelay(port, DROP_RATE, seed);
        client = QuicClusterClient.quicClusterClient(DIALER, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl, Option.empty(),
                                                     (_, message) -> receivedByDialer.add(message));
        dialerSide = client.connect(ACCEPTOR, new InetSocketAddress("127.0.0.1", relay.port())).await(AWAIT)
                           .fold(cause -> fail("dial: " + cause.message()), connection -> connection);
        awaitTrue(() -> acceptorSide.get() != null
                        && java.util.Arrays.stream(StreamType.values()).allMatch(lane -> acceptorSide.get().stream(lane).isPresent()),
                  "the acceptor registered every lane the dialer opened");
    }

    private String stats() {
        return "dialer{" + stats(dialerSide.connection()) + "} acceptor{" + stats(acceptorSide.get().connection()) + "}";
    }

    private static String stats(QuicChannel channel) {
        try {
            var st = channel.collectStats().get(5, TimeUnit.SECONDS);

            return "active=" + channel.isActive() + " sentB=" + st.sentBytes() + " recvB=" + st.recvBytes() + " lostB=" + st.lostBytes()
                   + " retransB=" + st.streamRetransBytes();
        } catch (Exception e) {
            return "active=" + channel.isActive() + " stats-unavailable:" + e;
        }
    }

    private static long lostBytes(QuicChannel channel) {
        try {
            return channel.collectStats().get(5, TimeUnit.SECONDS).lostBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    private String missing(Set<String> acked) {
        var got = finMarkers();
        var gaps = IntStream.range(0, SENT)
                            .mapToObj(i -> "fin-" + i)
                            .filter(marker -> acked.contains(marker) && !got.contains(marker))
                            .toList();

        return gaps.size() > 20
               ? gaps.subList(0, 10) + "…" + gaps.subList(gaps.size() - 5, gaps.size()) + " (" + gaps.size() + ")"
               : gaps.toString();
    }

    private void adoptAcceptorStandIn() {
        var lost = dialerSide.stream(LANE).unwrap();
        var lostAtAcceptor = acceptorSide.get().stream(LANE).unwrap();

        lost.close().awaitUninterruptibly(AWAIT.millis());
        awaitTrue(() -> acceptorSide.get().stream(LANE).map(current -> current != lostAtAcceptor).or(true),
                  "the acceptor releases FORWARD once the dialer's stream for it ends");
        openLane(acceptorSide.get());
        acceptorSide.get().stream(LANE).unwrap()
                    .writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(ACCEPTOR, LANE, "adopt-stand-in"))));
        awaitTrue(this::sameStreamAtBothEnds, "the dialer adopts the acceptor's stand-in for FORWARD");
    }

    private void burstWrite(QuicStreamChannel stream, int index, String padding, int pivot, Set<String> acked, AtomicInteger failed) {
        if (index == pivot) {
            var _ = openLaneAsync(dialerSide);
            // Hold the burst until the finish has really happened, so it lands mid-burst at any drop rate.
            awaitTrue(stream::isOutputShutdown, "the peer finished the stand-in and the dialer answered with its FIN (mid-burst)");
        }
        stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(DIALER, LANE, "fin-" + index + "|" + padding))))
              .addListener(future -> {
                  if (future.isSuccess()) {
                      acked.add("fin-" + index);
                  } else {
                      failed.incrementAndGet();
                  }
              });
    }

    private Set<String> finMarkers() {
        return receivedByAcceptor.stream()
                                 .filter(LaneProbe.class::isInstance)
                                 .map(LaneProbe.class::cast)
                                 .map(LaneProbe::marker)
                                 .map(marker -> marker.split("\\|")[0])
                                 .filter(marker -> marker.startsWith("fin-"))
                                 .collect(Collectors.toSet());
    }

    private boolean sameStreamAtBothEnds() {
        var atDialer = dialerSide.stream(LANE);
        var atAcceptor = acceptorSide.get().stream(LANE);

        return atDialer.isPresent() && atAcceptor.isPresent() && atDialer.unwrap().isActive()
               && atAcceptor.unwrap().isActive() && atDialer.unwrap().streamId() == atAcceptor.unwrap().streamId();
    }

    private static void openLane(QuicPeerConnection connection) {
        openLaneAsync(connection).orTimeout(AWAIT.millis(), TimeUnit.MILLISECONDS).join();
    }

    private static CompletableFuture<Option<QuicStreamChannel>> openLaneAsync(QuicPeerConnection connection) {
        var opened = new CompletableFuture<Option<QuicStreamChannel>>();

        connection.openLane(LANE, opened::complete);
        return opened;
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
