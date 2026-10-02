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
    private static final int SILENCE_PROBES = 100;

    private final SliceCodec codec = LaneProbe.codec();
    private final List<Object> receivedByAcceptor = new CopyOnWriteArrayList<>();
    private final List<Object> receivedByDialer = new CopyOnWriteArrayList<>();
    private final AtomicReference<QuicPeerConnection> acceptorSide = new AtomicReference<>();
    private final AtomicReference<Option<DeliveryTrace>> runningBurst = new AtomicReference<>(Option.empty());
    private Option<LaneDiagnosis.Sockets> sockets = Option.empty();
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
        sockets = Option.some(LaneDiagnosis.Sockets.attach(dialerSide.connection(), acceptorSide.get().connection()));
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

    /// Condition 2 on the FIN route: every write queued or in flight on a stream at the moment the PEER finishes
    /// it is delivered, or its write fails where the writer sees it — never acknowledged and then lost. The
    /// dialer bursts onto the acceptor's stand-in; mid-burst it opens a dialer stream that outranks the
    /// stand-in, so the acceptor (its opener) finishes it. The dialer then finishes its own half BEHIND its
    /// queued writes, so those reach the acceptor, and a write after that fails visibly.
    ///
    /// Accounting: every write resolves (succeeded + failed == sent), and every succeeded write is delivered
    /// (delivered == succeeded), so sent − delivered − failed == 0 — "0 unaccounted".
    /// Mutation it must catch: [QuicPeerConnection#streamEnded] closes (resets) the stream instead of finishing
    /// it — writes whose futures already succeeded are discarded, so delivered < succeeded.
    @Test
    void peerFinishesTheStreamMidBurst_everyWriteIsDeliveredOrFailsVisibly_zeroUnaccounted() {
        adoptAcceptorStandIn();
        var standIn = dialerSide.stream(LANE).unwrap();
        var sent = 400;
        var padding = "x".repeat(8 * 1024);
        var trace = new DeliveryTrace("fin-", sent, padding, standIn, acceptorSide.get().stream(LANE));

        assertThat(standIn.streamId() & 0x1L).as("arming: the burst rides the ACCEPTOR-opened stand-in").isEqualTo(1L);
        IntStream.range(0, sent)
                 .forEach(i -> burstWrite(standIn, i, padding, sent / 2, trace));

        trace.awaitEveryAcknowledgedWriteDelivered();
        assertThat(acceptorSide.get().stream(LANE).map(QuicStreamChannel::streamId))
            .as("arming: the acceptor moved FORWARD off the stand-in, so it finished it")
            .isNotEqualTo(Option.some(standIn.streamId()));
        assertThat(sent - finMarkers().size() - trace.failed.get()).as("0 unaccounted").isZero();
    }

    /// #1578 / i-genesis-stall (s29/i-genesis-stall-report.md): the CI order that stalled genesis. The acceptor's lazy
    /// CONSENSUS open completes AFTER the dialer registered all its lanes (2-7 ms later on #1653's CI run; the
    /// forced reproduction opened it 150 ms after the Hello). On rc4 the stand-in displaced the dialer's stream at the
    /// acceptor, the dialer had no reader for it, and half-closure kept every write "successful": both directions of
    /// CONSENSUS died with no write failure. Here both ends keep the dialer's stream and every write the writer saw
    /// succeed is delivered, in both directions.
    @Test
    void acceptorOpensConsensusAfterTheDialersLanes_bothEndsKeepOneStream_everyAcknowledgedWriteIsDelivered() {
        var lane = StreamType.CONSENSUS;
        var dialerStream = dialerSide.stream(lane).unwrap();

        openLane(acceptorSide.get(), lane);

        awaitTrue(() -> sameStreamAtBothEnds(lane), "both ends resolve CONSENSUS to the same stream");
        assertThat(acceptorSide.get().stream(lane).unwrap().streamId())
            .as("the dialer's CONSENSUS stream outranks the acceptor's late stand-in, at both ends")
            .isEqualTo(dialerStream.streamId());
        assertEveryAcknowledgedWriteIsRead(acceptorSide.get().stream(lane).unwrap(), ACCEPTOR, lane, "igs-a2d-", receivedByDialer);
        assertEveryAcknowledgedWriteIsRead(dialerSide.stream(lane).unwrap(), DIALER, lane, "igs-d2a-", receivedByAcceptor);
    }

    /// #1578 — the silence itself. A stream the acceptor opened must have a reader at the dialer: every write the
    /// acceptor saw succeed on its stand-in is read. Without the dialer's stream handler the writes still succeed
    /// (half-closure; nothing fails, nothing backs up) and none is read — delivered 0 of N acknowledged.
    @Test
    void acceptorStandIn_everyWriteTheAcceptorSawSucceed_isReadByTheDialer_noSilentAcknowledgement() {
        var lost = dialerSide.stream(LANE).unwrap();
        var lostAtAcceptor = acceptorSide.get().stream(LANE).unwrap();

        lost.close().awaitUninterruptibly(AWAIT.millis());
        awaitTrue(() -> acceptorSide.get().stream(LANE).map(current -> current != lostAtAcceptor).or(true),
                  "the acceptor releases FORWARD once the dialer's stream for it ends");
        openLane(acceptorSide.get());
        var standIn = acceptorSide.get().stream(LANE).unwrap();

        assertThat(standIn.streamId() & 0x1L).as("arming: the writes ride an ACCEPTOR-opened stream").isEqualTo(1L);
        assertEveryAcknowledgedWriteIsRead(standIn, ACCEPTOR, LANE, "silent-", receivedByDialer);
    }

    /// Writes `SILENCE_PROBES` markers on `stream` and requires: every write resolves, at least one succeeded
    /// (arming), and every succeeded write is read at the other end — so a write can fail visibly but never be
    /// acknowledged and lost.
    private void assertEveryAcknowledgedWriteIsRead(QuicStreamChannel stream,
                                                    NodeId sender,
                                                    StreamType lane,
                                                    String prefix,
                                                    List<Object> readBy) {
        var succeeded = new AtomicInteger();
        var failed = new AtomicInteger();

        IntStream.range(0, SILENCE_PROBES)
                 .forEach(i -> stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(sender, lane, prefix + i))))
                                     .addListener(future -> countWrite(future.isSuccess(), succeeded, failed)));

        awaitTrue(() -> succeeded.get() + failed.get() == SILENCE_PROBES, "every write resolves");
        assertThat(succeeded.get()).as("arming: writes on the lane succeeded").isPositive();
        awaitTrue(() -> prefixed(readBy, prefix) == succeeded.get(),
                  "every acknowledged write is read (read=" + prefixed(readBy, prefix) + " succeeded=" + succeeded.get()
                  + " failed=" + failed.get() + ")");
    }

    private static long prefixed(List<Object> received, String prefix) {
        return markers(received).stream()
                                .filter(marker -> marker.startsWith(prefix))
                                .distinct()
                                .count();
    }

    /// The dialer's FORWARD stream is lost, the acceptor opens a stand-in, and both ends adopt it.
    private void adoptAcceptorStandIn() {
        var lost = dialerSide.stream(LANE).unwrap();
        var lostAtAcceptor = acceptorSide.get().stream(LANE).unwrap();

        lost.close().awaitUninterruptibly(AWAIT.millis());
        awaitTrue(() -> acceptorSide.get().stream(LANE).map(current -> current != lostAtAcceptor).or(true),
                  "the acceptor releases FORWARD once the dialer's stream for it ends");
        openLane(acceptorSide.get());
        writeProbe(acceptorSide.get(), ACCEPTOR, "adopt-stand-in");
        awaitTrue(this::sameStreamAtBothEnds, "the dialer adopts the acceptor's stand-in for FORWARD");
    }

    /// One burst write; at `pivot` the dialer opens its own FORWARD stream, which makes the acceptor finish
    /// the stand-in while the burst is still being written.
    private void burstWrite(QuicStreamChannel stream, int index, String padding, int pivot, DeliveryTrace trace) {
        if (index == pivot) {
            var _ = openLaneAsync(dialerSide);
        }

        stream.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(DIALER, LANE, "fin-" + index + "|" + padding))))
              .addListener(future -> trace.record(index, future.isSuccess()));
    }

    private static void countWrite(boolean success, AtomicInteger succeeded, AtomicInteger failed) {
        var counter = success
                      ? succeeded
                      : failed;

        counter.incrementAndGet();
    }

    private Set<String> finMarkers() {
        return markers(receivedByAcceptor).stream()
                                          .filter(marker -> marker.startsWith("fin-"))
                                          .collect(Collectors.toSet());
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
        var trace = new DeliveryTrace("burst-", burst, padding, retiring, acceptorSide.get().stream(LANE));

        IntStream.range(0, burst)
                 .forEach(i -> retiring.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(LaneProbe.laneProbe(DIALER, LANE, "burst-" + i + "|" + padding))))
                                       .addListener(future -> trace.record(i, future.isSuccess())));
        openLane(dialerSide);

        trace.awaitEveryAcknowledgedWriteDelivered();
        assertThat(burstMarkers(burst)).as("every write queued before the retirement reaches the acceptor (none may fail: "
                                           + trace.failed.get() + " failed)")
                                       .hasSize(burst);
        assertThat(dialerSide.stream(LANE).unwrap()).as("the lane moved to the newer stream").isNotSameAs(retiring);
    }

    /// #1727 — the accounting of one write burst, and on a delivery timeout a SELF-ATTRIBUTING failure: a CI red says where
    /// the acknowledged writes stopped without a rerun. A write future succeeds when netty hands the bytes to quiche, not when
    /// the peer reads them, so an acknowledged write can stop in either QUIC stack or above the acceptor's. Delivery is judged
    /// BY INDEX: every write whose future succeeded must be read. The verdict compares what the acceptor's QUIC stack received
    /// since the burst began with the bytes the undelivered acknowledged writes carry:
    /// - CONNECTION-CLOSED: the connection is gone, and quiche discarded what it held;
    /// - RECEIVER-SIDE: the acceptor's QUIC stack received at least that, and the app did not read it;
    /// - SENDER-SIDE: it received less, so the bytes stopped at or before the dialer's QUIC stack.
    /// The received count also includes retransmitted duplicates and the other lanes, so the verdict leans RECEIVER-SIDE when
    /// close; the raw numbers are in the message. Writes that never resolve get the same diagnosis (their count is PENDING).
    private final class DeliveryTrace {
        private static final TimeSpan DELIVERY = TimeSpan.timeSpan(10).seconds();
        private static final TimeSpan GRACE = TimeSpan.timeSpan(15).seconds();

        private final String prefix;
        private final int sent;
        private final long bytesPerWrite;
        private final QuicStreamChannel dialerStream;
        private final Option<QuicStreamChannel> acceptorStream;
        private final long acceptorReceivedAtStart;
        private final AtomicInteger succeeded = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final Set<Integer> acknowledged = ConcurrentHashMap.newKeySet();
        private final List<String> closes = new CopyOnWriteArrayList<>();
        private final long startNanos = System.nanoTime();

        DeliveryTrace(String prefix, int sent, String padding, QuicStreamChannel dialerStream, Option<QuicStreamChannel> acceptorStream) {
            this.prefix = prefix;
            this.sent = sent;
            this.bytesPerWrite = codec.encode(LaneProbe.laneProbe(DIALER, LANE, prefix + "0|" + padding)).length + 4L;
            this.dialerStream = dialerStream;
            this.acceptorStream = acceptorStream;
            this.acceptorReceivedAtStart = LaneDiagnosis.receivedBytes(acceptorSide.get().connection());
            runningBurst.set(Option.some(this));
            dialerStream.closeFuture().addListener(_ -> closes.add("dialer@" + elapsedMillis() + "ms"));
            acceptorStream.onPresent(stream -> stream.closeFuture().addListener(_ -> closes.add("acceptor@" + elapsedMillis() + "ms")));
        }

        void record(int index, boolean success) {
            if (success) {
                acknowledged.add(index);
                succeeded.incrementAndGet();
            } else {
                failed.incrementAndGet();
            }
        }

        void awaitEveryAcknowledgedWriteDelivered() {
            if (waitFor(this::settled, DELIVERY)) {
                return;
            }

            var undeliveredAtTimeout = undelivered().size();
            var pendingAtTimeout = pending();
            var statsAtTimeout = stats();
            var caughtUp = waitFor(this::settled, GRACE);

            fail((pending() > 0 ? "writes never resolved" : "acknowledged writes not delivered") + " — " + verdict()
                 + " | at 10s: undelivered=" + undeliveredAtTimeout + " pending=" + pendingAtTimeout + "; at +15s: undelivered="
                 + undelivered().size() + " pending=" + pending() + (caughtUp ? " (caught up: SLOW, not lost)" : "")
                 + " sent=" + sent + " succeeded=" + succeeded.get() + " failed=" + failed.get() + " undelivered-indices="
                 + indices(undelivered()) + " closes=" + closes + " dialerStream.active=" + dialerStream.isActive()
                 + " acceptorStream.active=" + acceptorStream.map(QuicStreamChannel::isActive).or(false)
                 + " stats@10s=" + statsAtTimeout + " stats@+15s=" + stats() + " acceptorPortSharers="
                 + server.boundPort().map(LaneDiagnosis::portSharers).or("n/a") + " " + belowQuic());
        }

        /// Every write resolved and every acknowledged one was read.
        private boolean settled() {
            return pending() == 0 && undelivered().isEmpty();
        }

        private int pending() {
            return sent - succeeded.get() - failed.get();
        }

        private boolean waitFor(BooleanSupplier condition, TimeSpan window) {
            var deadline = System.nanoTime() + window.nanos();

            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return true;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
            }
            return condition.getAsBoolean();
        }

        private String verdict() {
            var receivedSinceStart = LaneDiagnosis.receivedBytes(acceptorSide.get().connection()) - acceptorReceivedAtStart;

            return LaneDiagnosis.verdict(dialerSide.isActive() && acceptorSide.get().isActive(),
                                         receivedSinceStart - delivered().size() * bytesPerWrite,
                                         undelivered().size() * bytesPerWrite);
        }

        /// The burst's state for a timed-out wait elsewhere in the test (the delivery check prints the same fields).
        private String summary() {
            return "burst{" + verdict() + " sent=" + sent + " succeeded=" + succeeded.get() + " failed=" + failed.get()
                   + " pending=" + pending() + " undelivered-indices=" + indices(undelivered()) + " closes=" + closes + "}";
        }

        private Set<Integer> delivered() {
            return markers(receivedByAcceptor).stream()
                                              .filter(marker -> marker.startsWith(prefix))
                                              .map(marker -> Integer.parseInt(marker.substring(prefix.length())))
                                              .collect(Collectors.toSet());
        }

        private List<Integer> undelivered() {
            var got = delivered();

            return acknowledged.stream()
                               .filter(index -> !got.contains(index))
                               .sorted()
                               .toList();
        }

        private static String indices(List<Integer> gaps) {
            return gaps.size() > 20
                   ? gaps.subList(0, 10) + "…" + gaps.subList(gaps.size() - 5, gaps.size()) + " (" + gaps.size() + ")"
                   : gaps.toString();
        }

        private String stats() {
            return LaneDiagnosis.stats(Option.some(dialerSide), Option.option(acceptorSide.get()));
        }

        private long elapsedMillis() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
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
        return sameStreamAtBothEnds(LANE);
    }

    private boolean sameStreamAtBothEnds(StreamType lane) {
        var atDialer = dialerSide.stream(lane);
        var atAcceptor = acceptorSide.get().stream(lane);

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
        openLane(connection, LANE);
    }

    private static void openLane(QuicPeerConnection connection, StreamType lane) {
        openLaneAsync(connection, lane).orTimeout(AWAIT.millis(), TimeUnit.MILLISECONDS).join();
    }

    private static CompletableFuture<Option<QuicStreamChannel>> openLaneAsync(QuicPeerConnection connection) {
        return openLaneAsync(connection, LANE);
    }

    private static CompletableFuture<Option<QuicStreamChannel>> openLaneAsync(QuicPeerConnection connection, StreamType lane) {
        var opened = new CompletableFuture<Option<QuicStreamChannel>>();

        connection.openLane(lane, opened::complete);

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

    private void awaitTrue(BooleanSupplier condition, String what) {
        var deadline = System.nanoTime() + AWAIT.nanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        fail("Timed out waiting for: " + what + " — " + diagnosis());
    }

    /// #1727 — what a timed-out wait reports: the running burst's verdict, if any, then the stall dump, so an early wait
    /// is as attributable as the delivery check.
    private String diagnosis() {
        return runningBurst.get().map(trace -> trace.summary() + " ").or("")
               + LaneDiagnosis.stall(Option.option(dialerSide), Option.option(acceptorSide.get()),
                                     Option.option(server).flatMap(QuicClusterServer::boundPort))
               + " " + belowQuic();
    }

    private String belowQuic() {
        return sockets.map(LaneDiagnosis.Sockets::describe).or("sockets{not attached}");
    }
}
