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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
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

/// #1578 — lane ownership on ONE real QUIC connection (dialer = [QuicClusterClient], acceptor =
/// [QuicClusterServer]), with the streams driven directly so each ordering is forced rather than hoped for.
///
/// The defect: the acceptor lazily opens a lane that is missing on its side, and the dialer had no handler
/// for streams the acceptor opens. When that lazy registration landed after the dialer's own preamble for
/// the lane, it displaced (and closed) the dialer's stream, so the acceptor wrote the lane into a stream
/// nobody read while every write reported success. These tests pin both halves of the fix: every
/// peer-opened stream has a reader, and both ends keep the SAME stream for a lane.
@Timeout(60)
class QuicLaneOwnershipTest {
    private static final NodeId ACCEPTOR = new NodeId("lane-acceptor");
    private static final NodeId DIALER = new NodeId("lane-dialer");
    private static final NodeAddress UNUSED_ADDRESS = new NodeAddress("127.0.0.1", 9000);
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final StreamType LANE = StreamType.FORWARD;

    private final SliceCodec codec = LaneProbe.codec();
    private final List<Object> receivedByAcceptor = new CopyOnWriteArrayList<>();
    private final List<Object> receivedByDialer = new CopyOnWriteArrayList<>();
    private final AtomicReference<QuicPeerConnection> acceptorSide = new AtomicReference<>();
    private QuicClusterServer server;
    private QuicClusterClient client;
    private QuicPeerConnection dialerSide;

    @BeforeEach
    void setUp() {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                       .fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                       .fold(_ -> fail("client ssl"), ssl -> ssl);

        server = QuicClusterServer.quicClusterServer(ACCEPTOR, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), serverSsl,
                                                     Option.empty(),
                                                     (connection, _, _) -> acceptorSide.set(connection),
                                                     (_, message) -> receivedByAcceptor.add(message));
        server.start(0).await(AWAIT).onFailure(cause -> fail("server start: " + cause.message()));
        var port = server.boundPort().fold(() -> fail("server not bound"), bound -> bound);

        client = QuicClusterClient.quicClusterClient(DIALER, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl,
                                                     Option.empty(),
                                                     (_, message) -> receivedByDialer.add(message));
        dialerSide = client.connect(ACCEPTOR, new InetSocketAddress("127.0.0.1", port))
                           .await(AWAIT)
                           .fold(cause -> fail("dial: " + cause.message()), connection -> connection);
        awaitTrue(() -> acceptorSide.get() != null && everyLanePresent(acceptorSide.get()),
                  "the acceptor registered every lane the dialer opened");
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().await(AWAIT);
        }
        if (server != null) {
            server.stop().await(AWAIT);
        }
    }

    /// THE forced defect ordering: the acceptor opens a lane AFTER the dialer's stream for it arrived.
    /// Before #1578 the acceptor's stream displaced the dialer's and the dialer never read it — the two
    /// ends then held different streams for FORWARD and neither direction was delivered.
    @Test
    void acceptorOpensALaneTheDialerAlreadyHolds_bothEndsKeepTheDialerStream_andItCarriesBothWays() {
        var dialerStream = dialerSide.stream(LANE).unwrap();

        openLane(acceptorSide.get());

        awaitTrue(this::sameStreamAtBothEnds, "both ends resolve FORWARD to the same stream");
        assertThat(acceptorSide.get().stream(LANE).unwrap().streamId())
            .as("the dialer-opened stream outranks the acceptor's stand-in, at both ends")
            .isEqualTo(dialerStream.streamId());
        assertLaneCarriesBothWays("forced-order");
    }

    /// The stand-in case: the dialer's stream for a lane is gone, the acceptor opens its own, and the
    /// dialer must READ it (there was no handler) and adopt it, so both directions flow on it.
    @Test
    void dialerLaneLost_acceptorStandIn_isReadByTheDialer_andBothEndsAdoptIt() {
        var lost = dialerSide.stream(LANE).unwrap();
        var lostAtAcceptor = acceptorSide.get().stream(LANE).unwrap();

        lost.close().awaitUninterruptibly(AWAIT.millis());
        awaitTrue(() -> acceptorSide.get().stream(LANE).map(current -> current != lostAtAcceptor).or(true),
                  "the acceptor releases FORWARD once the dialer's stream for it ends (it stays active under half-closure)");

        openLane(acceptorSide.get());
        writeProbe(acceptorSide.get(), ACCEPTOR, "stand-in-to-dialer");
        awaitTrue(() -> markers(receivedByDialer).contains("stand-in-to-dialer"),
                  "the dialer reads the stream the acceptor opened");
        awaitTrue(this::sameStreamAtBothEnds, "the dialer adopts the acceptor's stand-in for FORWARD");
        assertLaneCarriesBothWays("stand-in");
    }

    /// Condition 1: both ends open the same lane at once. Each sees its own stream first and the other's
    /// second, so an arrival-order rule leaves them on DIFFERENT streams; the id rule keeps the dialer's
    /// newer stream at both ends, and the streams that lost are finished by their openers.
    @Test
    void bothEndsOpenTheSameLaneAtOnce_bothKeepTheSameDialerOpenedStream() {
        var original = dialerSide.stream(LANE).unwrap();
        var originalAtAcceptor = acceptorSide.get().stream(LANE).unwrap();
        var dialerOpen = openLaneAsync(dialerSide);
        var acceptorOpen = openLaneAsync(acceptorSide.get());

        CompletableFuture.allOf(dialerOpen, acceptorOpen).orTimeout(AWAIT.millis(), TimeUnit.MILLISECONDS).join();

        awaitTrue(this::sameStreamAtBothEnds, "both ends keep the same FORWARD stream");
        var kept = dialerSide.stream(LANE).unwrap();

        assertThat(kept.streamId() & 0x1L).as("the kept stream is dialer-opened").isZero();
        assertThat(kept.streamId()).as("the dialer's NEW stream outranks its original").isGreaterThan(original.streamId());
        awaitTrue(() -> !original.isActive() && !originalAtAcceptor.isActive(),
                  "the displaced original is finished by its opener and ends at both sides");
        assertLaneCarriesBothWays("collision");
    }

    /// Condition 2: writes already queued on a stream when it is retired are DELIVERED, not discarded.
    /// The dialer queues a burst far larger than the initial congestion window on FORWARD and, before it
    /// can drain, opens a newer FORWARD stream that retires the first. A FIN queued behind the burst
    /// delivers all of it; closing the retired stream would drop whatever was still unsent.
    @Test
    void writesQueuedOnARetiredStream_areAllDelivered() {
        var retiring = dialerSide.stream(LANE).unwrap();
        var burst = 300;
        var padding = "x".repeat(8 * 1024);

        IntStream.range(0, burst)
                 .forEach(i -> write(retiring, LaneProbe.laneProbe(DIALER, LANE, "burst-" + i + "|" + padding)));
        openLane(dialerSide);

        awaitTrue(() -> burstMarkers(burst).size() == burst,
                  "every write queued before the retirement reaches the acceptor");
        assertThat(dialerSide.stream(LANE).unwrap()).as("the lane moved to the newer stream").isNotSameAs(retiring);
    }

    private Set<String> burstMarkers(int burst) {
        return markers(receivedByAcceptor).stream()
                                          .filter(marker -> marker.startsWith("burst-"))
                                          .collect(Collectors.toSet());
    }

    private void assertLaneCarriesBothWays(String tag) {
        writeProbe(acceptorSide.get(), ACCEPTOR, tag + "-to-dialer");
        writeProbe(dialerSide, DIALER, tag + "-to-acceptor");
        awaitTrue(() -> markers(receivedByDialer).contains(tag + "-to-dialer"), "acceptor -> dialer on FORWARD");
        awaitTrue(() -> markers(receivedByAcceptor).contains(tag + "-to-acceptor"), "dialer -> acceptor on FORWARD");
    }

    private boolean sameStreamAtBothEnds() {
        var atDialer = dialerSide.stream(LANE);
        var atAcceptor = acceptorSide.get().stream(LANE);

        return atDialer.isPresent() && atAcceptor.isPresent() && atDialer.unwrap().isActive()
               && atAcceptor.unwrap().isActive() && atDialer.unwrap().streamId() == atAcceptor.unwrap().streamId();
    }

    private void writeProbe(QuicPeerConnection connection, NodeId sender, String marker) {
        write(connection.stream(LANE).unwrap(), LaneProbe.laneProbe(sender, LANE, marker));
    }

    private void write(QuicStreamChannel stream, LaneProbe probe) {
        stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(probe)));
    }

    private static void openLane(QuicPeerConnection connection) {
        openLaneAsync(connection).orTimeout(AWAIT.millis(), TimeUnit.MILLISECONDS).join();
    }

    private static CompletableFuture<Option<QuicStreamChannel>> openLaneAsync(QuicPeerConnection connection) {
        var opened = new CompletableFuture<Option<QuicStreamChannel>>();

        connection.openLane(LANE, opened::complete);

        return opened;
    }

    private static boolean everyLanePresent(QuicPeerConnection connection) {
        return Arrays.stream(StreamType.values()).allMatch(lane -> connection.stream(lane).isPresent());
    }

    private static List<String> markers(List<Object> received) {
        return received.stream()
                       .filter(LaneProbe.class::isInstance)
                       .map(LaneProbe.class::cast)
                       .map(LaneProbe::marker)
                       .map(marker -> marker.split("\\|")[0])
                       .toList();
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
