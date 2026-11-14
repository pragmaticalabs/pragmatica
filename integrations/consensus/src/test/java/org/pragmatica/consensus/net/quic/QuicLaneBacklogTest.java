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

import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.QuicTransportMetrics.DropSite;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.PEER;

/// #3168 / #3179 fix round 1: the per-stream [LaneBacklog] seen through [QuicClusterNetwork]'s write path. Each test
/// names the checklist item it pins; each goes red with that item's fix reverted.
@Timeout(60)
class QuicLaneBacklogTest {
    private static final String LOGGER = "org.pragmatica.consensus.net.quic.QuicClusterNetwork";
    private static final long POLL_NANOS = TimeSpan.timeSpan(5).millis().nanos();

    /// A stream whose writability, liveness and write behaviour the test drives. `written` holds the first byte of
    /// every frame handed to the stream, in hand-off order.
    private static final class Lane {
        final AtomicBoolean writable = new AtomicBoolean(false);
        final AtomicBoolean active = new AtomicBoolean(true);
        final List<Integer> written = new CopyOnWriteArrayList<>();
        final QuicStreamChannel ch = mock(QuicStreamChannel.class);

        Lane(ChannelFuture future, int throwOnFrame) {
            when(ch.isWritable()).thenAnswer(_ -> writable.get());
            when(ch.isActive()).thenAnswer(_ -> active.get());
            when(ch.writeAndFlush(any())).thenAnswer(invocation -> record(invocation.getArgument(0), future, throwOnFrame));
        }

        private ChannelFuture record(ByteBuf frame, ChannelFuture future, int throwOnFrame) {
            int first = frame.getByte(0);

            if (first == throwOnFrame) {
                throw new IllegalStateException("write threw for frame " + first);
            }

            written.add(first);

            return future;
        }
    }

    // --- item 1: no entry for a stream that never holds; a closed stream's entry goes ---

    @Test
    void writeInOrder_writableStreams_neverCreateABacklogEntry() {
        var network = LossAccountingFixtures.network(10, TimeSpan.timeSpan(20).millis());
        var lanes = List.of(writableLane(), writableLane(), writableLane());

        for (int i = 0; i < 5; i++) {
            for (var lane : lanes) {
                network.writeIfWritableForTest(lane.ch, new byte[]{(byte) i}, PEER, StreamType.CONSENSUS);

                assertThat(network.laneBacklogCountForTests()).as("a write that was never held leaves no entry").isZero();
            }
        }

        lanes.forEach(lane -> assertThat(lane.written).hasSize(5));
    }

    /// Item 1 (closed stream) and item 19: a stream that closes with frames held loses its entry on the next drain pass,
    /// far inside the head-age budget, and every discarded frame is counted once.
    @Test
    void writeInOrder_streamClosesWithFramesHeld_entryGoesAndEveryFrameIsCountedLost() {
        var network = LossAccountingFixtures.network(100, TimeSpan.timeSpan(100).millis());
        var lane = new Lane(succeeded(), -1);

        holdFrames(network, lane, StreamType.CONSENSUS, 1, 2, 3);
        assertThat(network.heldFrameCountForTests(lane.ch)).isEqualTo(3);
        assertThat(network.consensusRetriesInFlight()).isEqualTo(3);

        lane.active.set(false);

        awaitWithin("the closed stream's entry is removed within a few passes, not after the 10 s budget",
                    TimeSpan.timeSpan(2).seconds(),
                    () -> network.laneBacklogCountForTests() == 0);
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP))
            .as("one RETRY_GAVE_UP per discarded frame")
            .isEqualTo(3L);
        assertThat(network.consensusRetriesInFlight()).as("every discarded frame leaves the pressure count").isZero();
        assertThat(lane.written).isEmpty();
    }

    // --- item 2: the backlog is bounded by head age, however slowly the peer drains ---

    /// The peer takes one frame per 40 ms against a 100 ms budget while the sender sends one per 5 ms. Progress used to
    /// renew the whole budget, so the backlog grew with every frame sent (~260 here). Now a frame waits at most one budget,
    /// so the stream holds at most the frames sent within it.
    @Test
    void drain_peerSlowerThanTheSender_backlogStaysBoundedByHeadAge() throws InterruptedException {
        var network = LossAccountingFixtures.network(10, TimeSpan.timeSpan(10).millis());
        var tokens = new AtomicInteger();
        var written = new AtomicInteger();
        var ch = tokenStream(tokens, written);
        var feeding = new AtomicBoolean(true);
        var feeder = Thread.ofPlatform().start(() -> feed(tokens, feeding));
        var maxHeld = 0;
        var frames = 300;

        for (int i = 0; i < frames; i++) {
            network.writeIfWritableForTest(ch, new byte[]{1}, PEER, StreamType.CONSENSUS);
            maxHeld = Math.max(maxHeld, network.heldFrameCountForTests(ch));
            LockSupport.parkNanos(POLL_NANOS);
        }

        feeding.set(false);
        feeder.join();
        tokens.set(Integer.MAX_VALUE);
        awaitWithin("the backlog drains once the stream is writable", AWAIT, () -> network.laneBacklogCountForTests() == 0);

        var gaveUp = network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP);

        assertThat(maxHeld).as("frames held at once never exceed those sent within ~one budget").isLessThanOrEqualTo(60);
        assertThat(gaveUp).as("frames older than the budget were given up").isPositive();
        assertThat(written.get() + gaveUp).as("every frame is written or counted lost").isEqualTo(frames);
        assertThat(network.consensusRetriesInFlight()).isZero();
    }

    // --- item 3 (+13): the pressure count is per frame, by the frame's own lane ---

    @Test
    void pressureCount_consensusThenDhtOnOneStream_countsOnlyTheConsensusFrame_andReturnsToZero() {
        assertPressureCountOfMixedBacklog(StreamType.CONSENSUS, StreamType.DHT);
    }

    @Test
    void pressureCount_dhtThenConsensusOnOneStream_countsOnlyTheConsensusFrame_andReturnsToZero() {
        assertPressureCountOfMixedBacklog(StreamType.DHT, StreamType.CONSENSUS);
    }

    @Test
    void pressureCount_directWrite_isNeverCounted() {
        var network = LossAccountingFixtures.network(10, TimeSpan.timeSpan(20).millis());
        var lane = writableLane();

        network.writeIfWritableForTest(lane.ch, new byte[]{1}, PEER, StreamType.CONSENSUS);

        assertThat(lane.written).containsExactly(1);
        assertThat(network.consensusRetriesInFlight()).isZero();
    }

    private static void assertPressureCountOfMixedBacklog(StreamType firstLane, StreamType secondLane) {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        network.writeIfWritableForTest(lane.ch, new byte[]{1}, PEER, firstLane);
        network.writeIfWritableForTest(lane.ch, new byte[]{2}, PEER, secondLane);

        assertThat(network.heldFrameCountForTests(lane.ch)).isEqualTo(2);
        assertThat(network.consensusRetriesInFlight()).as("only the CONSENSUS frame is pressure").isEqualTo(1);

        lane.writable.set(true);
        awaitWithin("both frames are written", AWAIT, () -> lane.written.size() == 2);
        awaitWithin("the entry is removed", AWAIT, () -> network.laneBacklogCountForTests() == 0);

        assertThat(lane.written).containsExactly(1, 2);
        assertThat(network.consensusRetriesInFlight()).as("exactly zero after the mixed backlog drained").isZero();
    }

    // --- item 5: a held frame keeps the #1578 resend-once ---

    @Test
    void heldFrame_writeFailsOnARetiredStream_isResentOnceOnTheKeptStream() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var connection = LossAccountingFixtures.connection(PEER, QuicPeerConnection.LaneOpener.noop());
        var kept = LossAccountingFixtures.stream(true);
        var lane = new Lane(failed(), -1);

        connection.registerStream(StreamType.CONSENSUS, kept);
        network.writeIfWritableForTest(lane.ch, new byte[]{1}, PEER, StreamType.CONSENSUS, connection);
        verify(kept, never()).writeAndFlush(any());

        lane.writable.set(true);
        awaitWithin("the held frame is written", AWAIT, () -> lane.written.size() == 1);

        verify(kept, times(1)).writeAndFlush(any());
        assertThat(network.quicMetrics().retiredStreamResendCount()).as("re-sent exactly once").isEqualTo(1L);
        assertThat(network.quicMetrics().writeFailureCount()).as("a delivered resend is not a failure").isZero();
    }

    // --- item 22: a held write's failure is surfaced ---

    @Test
    void heldFrame_writeFailsWithNoResendTarget_isCountedAsAFailedWrite() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(failed(), -1);

        network.writeIfWritableForTest(lane.ch, new byte[]{1}, PEER, StreamType.CONSENSUS);
        lane.writable.set(true);
        awaitWithin("the held frame is written", AWAIT, () -> lane.written.size() == 1);
        awaitWithin("the failed held write is counted", AWAIT, () -> network.quicMetrics().writeFailureCount() == 1);

        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.WRITE_FAILED)).isEqualTo(1L);
    }

    // --- item 7: a write that throws inside the drain does not wedge the stream ---

    @Test
    void drain_heldWriteThrows_frameIsCountedLost_andTheDrainWritesTheRest() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), 1);

        holdFrames(network, lane, StreamType.CONSENSUS, 1, 2, 3);
        lane.writable.set(true);

        awaitWithin("the frames behind the throwing one are written", AWAIT, () -> lane.written.size() == 2);
        awaitWithin("the entry is removed", AWAIT, () -> network.laneBacklogCountForTests() == 0);

        assertThat(lane.written).containsExactly(2, 3);
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.WRITE_FAILED)).isEqualTo(1L);
        assertThat(network.quicMetrics().writeFailureCount()).isEqualTo(1L);
        assertThat(network.consensusRetriesInFlight()).isZero();
    }

    // --- item 8: a frame held only behind others on a writable stream is not a backpressured write ---

    @Test
    void episode_frameHeldBehindOnAWritableStream_isNotCountedAsBackpressured() {
        var network = LossAccountingFixtures.network(50, TimeSpan.timeSpan(300).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            network.writeIfWritableForTest(lane.ch, new byte[]{1}, PEER, StreamType.CONSENSUS);
            lane.writable.set(true);
            network.writeIfWritableForTest(lane.ch, new byte[]{2}, PEER, StreamType.CONSENSUS);

            awaitWithin("both frames are written by the drain", AWAIT, () -> lane.written.size() == 2);

            assertThat(lane.written).containsExactly(1, 2);
            assertThat(capture.messages(Level.WARN)).filteredOn(message -> message.startsWith("Backpressure on peer"))
                                                    .as("one episode entry")
                                                    .hasSize(1);
            assertThat(capture.messages(Level.INFO)).filteredOn(message -> message.contains("cleared"))
                                                    .singleElement()
                                                    .as("only the frame that found the stream unwritable counts")
                                                    .satisfies(message -> assertThat(message).contains("cleared: 1 write(s)"));
        }
    }

    // --- item 16: a REMOVED peer's frame is written while the stream is writable, dropped otherwise ---

    @Test
    void removedPeer_writableStream_isWritten_unwritableStream_isDroppedNotHeld() {
        var network = LossAccountingFixtures.network(10, TimeSpan.timeSpan(20).millis());
        var dead = new NodeId("dead-peer");
        var state = PeerState.peerState(dead, System.nanoTime());
        var _ = state.authoritativeRemove(System.nanoTime());
        var writable = writableLane();
        var blocked = new Lane(succeeded(), -1);

        network.seedPeerForTests(dead, state);

        network.writeIfWritableForTest(writable.ch, new byte[]{1}, dead, StreamType.CONSENSUS);
        assertThat(writable.written).as("a writable stream takes a REMOVED peer's frame, as before #3168").containsExactly(1);
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.REMOVED_AT_RETRY)).isZero();

        network.writeIfWritableForTest(blocked.ch, new byte[]{2}, dead, StreamType.CONSENSUS);
        assertThat(blocked.written).isEmpty();
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.REMOVED_AT_RETRY))
            .isEqualTo(1L);
        assertThat(network.laneBacklogCountForTests()).as("a REMOVED peer's frame is never held").isZero();
        assertThat(network.consensusRetriesInFlight()).isZero();
    }

    // --- item 11 (+18 success path): a drain that wrote every frame gave nothing up ---

    @Test
    void drain_writesEveryHeldFrame_countsNoGiveUp_warnsNothing_andTheEntryGoes() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.WARN, false)) {
            holdFrames(network, lane, StreamType.CONSENSUS, 1, 2, 3);
            lane.writable.set(true);

            awaitWithin("the entry is removed once the drain wrote the frames", AWAIT, () -> network.laneBacklogCountForTests() == 0);

            assertThat(lane.written).containsExactly(1, 2, 3);
            assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP))
                .as("a backlog that drained empty gave nothing up")
                .isZero();
            assertThat(capture.messages(Level.WARN)).filteredOn(message -> message.contains("gave up")).isEmpty();
            assertThat(network.consensusRetriesInFlight()).isZero();
        }
    }

    // --- item 18 (expiry path): a backlog whose every frame expired is retired and removed ---

    @Test
    void drain_everyHeldFrameExpires_entryGoes_andEachFrameIsCountedLostOnce() {
        var network = LossAccountingFixtures.network(5, TimeSpan.timeSpan(10).millis());
        var lane = new Lane(succeeded(), -1);

        holdFrames(network, lane, StreamType.CONSENSUS, 1, 2, 3);

        awaitWithin("the expired backlog's entry is removed", AWAIT, () -> network.laneBacklogCountForTests() == 0);

        assertThat(lane.written).isEmpty();
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP)).isEqualTo(3L);
        assertThat(network.consensusRetriesInFlight()).isZero();
    }

    // --- item 14: exactly one drainer per backlog ---

    /// A drain pass asks the stream `isWritable()` once, so those calls over a window count the passes. One drainer runs
    /// at most window/interval + 1 of them; a drainer started per held frame (here 8) runs about eight times that.
    @Test
    void drain_framesHeldBehindStartNoDrainOfTheirOwn_onePassPerIntervalPerBacklog() {
        var network = LossAccountingFixtures.network(100, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        holdFrames(network, lane, StreamType.CONSENSUS, 1, 2, 3, 4, 5, 6, 7, 8);

        var before = writableChecks(lane);

        LockSupport.parkNanos(TimeSpan.timeSpan(500).millis().nanos());

        var passes = writableChecks(lane) - before;

        assertThat(passes).as("control: the drain is running").isPositive();
        assertThat(passes).as("passes in 500 ms at a 20 ms interval, one drainer").isLessThanOrEqualTo(30);
        assertThat(network.heldFrameCountForTests(lane.ch)).isEqualTo(8);
    }

    private static long writableChecks(Lane lane) {
        return mockingDetails(lane.ch).getInvocations()
                                      .stream()
                                      .filter(invocation -> invocation.getMethod().getName().equals("isWritable"))
                                      .count();
    }

    // --- item 17: a stream that closes inside a pass is not written to ---

    /// The pass's liveness check passes, then the stream closes before the write loop asks it: the write loop's own
    /// `isActive()` guard keeps the frames off the dead stream, and the next pass gives them up.
    @Test
    void drain_streamClosesBetweenTheLivenessCheckAndTheWrites_framesAreNotWritten_butGivenUp() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);
        var activeAnswers = new AtomicInteger(1);

        when(lane.ch.isActive()).thenAnswer(_ -> !lane.writable.get() || activeAnswers.getAndDecrement() > 0);
        holdFrames(network, lane, StreamType.CONSENSUS, 1, 2, 3);
        lane.writable.set(true);

        awaitWithin("the dead stream's entry goes", AWAIT, () -> network.laneBacklogCountForTests() == 0);

        assertThat(lane.written).as("nothing is written to a stream that closed").isEmpty();
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP)).isEqualTo(3L);
    }

    // --- item 20: a held write ends the backpressure episode its frame opened ---

    @Test
    void heldWrite_endsTheBackpressureEpisode_theUnwritableFrameOpened() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        holdFrames(network, lane, StreamType.CONSENSUS, 1);
        assertThat(network.openBackpressureEpisodesForTests()).as("the unwritable frame opened an episode").isEqualTo(1);

        lane.writable.set(true);
        awaitWithin("the held frame is written", AWAIT, () -> lane.written.size() == 1);

        assertThat(network.openBackpressureEpisodesForTests()).as("the held write ended the episode").isZero();
    }

    // --- item 21: held AND direct writes are counted as sent ---

    @Test
    void sentCounters_directWrite_countsOneMessageAndItsBytes() {
        var network = LossAccountingFixtures.network(10, TimeSpan.timeSpan(20).millis());
        var lane = writableLane();

        network.writeIfWritableForTest(lane.ch, new byte[3], PEER, StreamType.CONSENSUS);

        assertThat(network.quicMetrics().messagesSentCount()).isEqualTo(1L);
        assertThat(network.quicMetrics().bytesSentCount()).isEqualTo(3L);
    }

    @Test
    void sentCounters_heldWrite_countsEachMessageAndItsBytes_onceTheDrainWroteIt() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        network.writeIfWritableForTest(lane.ch, new byte[5], PEER, StreamType.CONSENSUS);
        network.writeIfWritableForTest(lane.ch, new byte[7], PEER, StreamType.CONSENSUS);
        assertThat(network.quicMetrics().messagesSentCount()).as("held frames are not sent yet").isZero();

        lane.writable.set(true);
        awaitWithin("both held frames are written", AWAIT, () -> lane.written.size() == 2);

        assertThat(network.quicMetrics().messagesSentCount()).isEqualTo(2L);
        assertThat(network.quicMetrics().bytesSentCount()).isEqualTo(12L);
    }

    // --- helpers ---

    private static void holdFrames(QuicClusterNetwork network, Lane lane, StreamType streamType, int... frames) {
        for (var frame : frames) {
            network.writeIfWritableForTest(lane.ch, new byte[]{(byte) frame}, PEER, streamType);
        }
    }

    private static Lane writableLane() {
        var lane = new Lane(succeeded(), -1);

        lane.writable.set(true);

        return lane;
    }

    /// Writable while a token is available; every write takes one.
    private static QuicStreamChannel tokenStream(AtomicInteger tokens, AtomicInteger written) {
        var ch = mock(QuicStreamChannel.class);
        var future = succeeded();

        when(ch.isActive()).thenReturn(true);
        when(ch.isWritable()).thenAnswer(_ -> tokens.get() > 0);
        when(ch.writeAndFlush(any())).thenAnswer(_ -> takeToken(tokens, written, future));

        return ch;
    }

    private static ChannelFuture takeToken(AtomicInteger tokens, AtomicInteger written, ChannelFuture future) {
        tokens.decrementAndGet();
        written.incrementAndGet();

        return future;
    }

    private static void feed(AtomicInteger tokens, AtomicBoolean feeding) {
        while (feeding.get()) {
            LockSupport.parkNanos(TimeSpan.timeSpan(40).millis().nanos());
            tokens.set(1);
        }
    }

    private static ChannelFuture succeeded() {
        var future = mock(ChannelFuture.class);

        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);

        return future;
    }

    /// A future whose listeners run at once and see a failed write.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChannelFuture failed() {
        var future = mock(ChannelFuture.class);

        when(future.isSuccess()).thenReturn(false);
        when(future.cause()).thenReturn(new ClosedChannelException());
        when(future.addListener(any())).thenAnswer(invocation -> {
            ((GenericFutureListener) invocation.getArgument(0)).operationComplete(future);
            return future;
        });

        return future;
    }

    private static void awaitWithin(String what, TimeSpan within, BooleanSupplier condition) {
        var deadline = System.nanoTime() + within.nanos();

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }

        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
