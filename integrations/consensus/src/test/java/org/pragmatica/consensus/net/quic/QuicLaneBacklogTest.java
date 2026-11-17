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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.QuicTransportMetrics.DropSite;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.PEER;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.fireWritabilityEvent;

/// #3168 / #3179 fix round 1: the per-stream [LaneBacklog] seen through [QuicClusterNetwork]'s write path. Each test
/// names the checklist item it pins; each goes red with that item's fix reverted.
@Timeout(60)
class QuicLaneBacklogTest {
    private static final String LOGGER = "org.pragmatica.consensus.net.quic.QuicClusterNetwork";
    private static final long POLL_NANOS = TimeSpan.timeSpan(5).millis().nanos();
    /// Quiet window after a wait, long enough for a duplicate asynchronous effect (a second resend) to show.
    private static final long SETTLE_NANOS = TimeSpan.timeSpan(200).millis().nanos();

    /// A stream whose writability, liveness and write behaviour the test drives. `written` holds the first byte of
    /// every frame handed to the stream, in hand-off order.
    private static final class Lane {
        final AtomicBoolean writable = new AtomicBoolean(false);
        final AtomicBoolean active = new AtomicBoolean(true);
        /// Armed: the next writability check throws once, as a fault inside a drain pass would.
        final AtomicBoolean faultOnNextWritableCheck = new AtomicBoolean(false);
        /// Armed: the next writability check turns the stream writable, then throws once, so the pass that faults is the
        /// first to see the stream writable whichever drain tick it is.
        final AtomicBoolean faultThenWritable = new AtomicBoolean(false);
        /// Armed: the next writability check still reads unwritable and turns the stream writable behind it, with no
        /// writability event, as a stream that drains between a write's refusal and its count.
        final AtomicBoolean writableAfterNextCheck = new AtomicBoolean(false);
        /// The stream also reads writable while this holds, with no writability event (a stream that drained behind a
        /// read, at a point the test names).
        final AtomicReference<BooleanSupplier> writableOnceThis = new AtomicReference<>(() -> false);
        /// The stream turns unwritable right after it takes the frame whose first byte is this (-1: never), so a drain
        /// writes exactly up to that frame.
        final AtomicInteger unwritableAfterWriteOf = new AtomicInteger(-1);
        /// Runs on the writing thread after every frame the stream takes, as an inline writability event would.
        final AtomicReference<Runnable> afterWrite = new AtomicReference<>(() -> {});
        final List<Integer> written = new CopyOnWriteArrayList<>();
        /// Every thread that read the stream's liveness: a drain pass reads it before it takes the backlog lock.
        final Set<Thread> livenessReaders = ConcurrentHashMap.newKeySet();
        final QuicStreamChannel ch = mock(QuicStreamChannel.class);

        Lane(ChannelFuture future, int throwOnFrame) {
            when(ch.isWritable()).thenAnswer(_ -> writableOrFault());
            when(ch.isActive()).thenAnswer(_ -> readLiveness());
            when(ch.writeAndFlush(any())).thenAnswer(invocation -> record(invocation.getArgument(0), future, throwOnFrame));
        }

        private boolean readLiveness() {
            livenessReaders.add(Thread.currentThread());

            return active.get();
        }

        private boolean writableOrFault() {
            if (faultOnNextWritableCheck.compareAndSet(true, false)) {
                throw new IllegalStateException("fault inside the drain pass");
            }

            if (faultThenWritable.compareAndSet(true, false)) {
                writable.set(true);

                throw new IllegalStateException("fault inside the drain pass, stream now writable");
            }

            if (writableAfterNextCheck.compareAndSet(true, false)) {
                writable.set(true);

                return false;
            }

            return writable.get() || writableOnceThis.get().getAsBoolean();
        }

        private ChannelFuture record(ByteBuf frame, ChannelFuture future, int throwOnFrame) {
            int first = frame.getByte(0);

            if (first == throwOnFrame) {
                throw new IllegalStateException("write threw for frame " + first);
            }

            written.add(first);

            if (first == unwritableAfterWriteOf.get()) {
                writable.set(false);
            }

            afterWrite.get().run();

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

        // #3212: `lane.written` fills inside the failing stream's write, BEFORE the failed write's listener runs the resend,
        // so the resend is still pending here. Wait for the resend itself, then pin "exactly once" after the drain settled.
        verify(kept, timeout(AWAIT.nanos() / 1_000_000L).times(1)).writeAndFlush(any());
        awaitWithin("the backlog entry is removed", AWAIT, () -> network.laneBacklogCountForTests() == 0);
        LockSupport.parkNanos(SETTLE_NANOS);

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

    // --- D1: a throw inside a drain pass abandons the backlog instead of leaving it without a drainer ---

    @Test
    void drain_passThrows_backlogIsAbandoned_framesCountedLost_andNothingStaysInFlight() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        holdFrames(network, lane, StreamType.CONSENSUS, 1, 2);
        assertThat(network.consensusRetriesInFlight()).isEqualTo(2);

        lane.faultOnNextWritableCheck.set(true);
        lane.writable.set(true);

        awaitWithin("the faulted backlog's entry is removed", AWAIT, () -> network.laneBacklogCountForTests() == 0);

        assertThat(network.consensusRetriesInFlight()).as("both held frames left the pressure count").isZero();
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP))
            .as("each abandoned frame is counted lost once")
            .isEqualTo(2L);
        assertThat(lane.faultOnNextWritableCheck).as("the fault was injected and consumed").isFalse();
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

    // --- #3313: an episode ends when its last held frame is given up or abandoned, with no later write on the lane ---

    /// Every held frame expires while the stream is unwritable: the episode stays open (the last write attempt found the
    /// stream unwritable), and the closer run by the stream's writability event ends it once the stream turns writable.
    /// Nothing is written to the lane after the holds: the end must not depend on later traffic. The stream's writability
    /// event, delivered through a production-built data handler, is the only closer run after the holds.
    @Test
    void episode_everyHeldFrameGivenUp_endsWhenTheStreamTurnsWritable_withoutALaterWrite() throws Exception {
        var network = LossAccountingFixtures.network(5, TimeSpan.timeSpan(10).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            holdFrames(network, lane, StreamType.CONSENSUS, 1, 2, 3);

            awaitWithin("every held frame is given up",
                        AWAIT,
                        () -> network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP) == 3L);
            assertThat(network.openBackpressureEpisodesForTests()).as("still unwritable: the episode stays open").isEqualTo(1);

            lane.writable.set(true);
            fireWritabilityEvent(network, lane.ch);

            awaitWithin("the episode ends once the stream is writable", AWAIT, () -> network.openBackpressureEpisodesForTests() == 0);
            awaitWithin("the backlog entry goes", AWAIT, () -> network.laneBacklogCountForTests() == 0);

            assertThat(lane.written).as("no frame was written after the holds").isEmpty();
            assertThat(cleared(capture)).as("one cleared line for the one episode").hasSize(1);
        }
    }

    /// T-a, last frame given up (#3313): the frame expires on an unwritable stream, and the stream reads writable from the
    /// moment the give-up is counted lost (the give-up counts the loss before it settles), firing no event. The give-up's
    /// closer run finds no frame held and the stream writable, so the episode ends. Nothing else can end it: the frame is
    /// not written, the backlog retires with it (no later pass), and no writability event, close or later write follows.
    @Test
    void episode_lastHeldFrameGivenUp_endsTheEpisode_withNoEventOrLaterWrite() {
        var network = LossAccountingFixtures.network(5, TimeSpan.timeSpan(10).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            lane.writableOnceThis.set(() -> network.quicMetrics()
                                                   .droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP) > 0L);
            holdFrames(network, lane, StreamType.CONSENSUS, 1);
            assertThat(network.openBackpressureEpisodesForTests()).as("the held frame opened an episode").isEqualTo(1);

            awaitWithin("the held frame is given up",
                        AWAIT,
                        () -> network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP) == 1L);
            awaitWithin("the backlog entry goes", AWAIT, () -> network.laneBacklogCountForTests() == 0);

            assertThat(lane.written).as("control: the frame was given up, not written").isEmpty();
            assertThat(network.openBackpressureEpisodesForTests()).as("the give-up's closer run ended the episode").isZero();
            assertThat(cleared(capture)).as("one cleared line for the one episode").hasSize(1);
        }
    }

    /// A drain fault abandons the backlog while the stream is open and writable: the abandon removes the last held frame
    /// on a writable stream, so it ends the episode itself.
    @Test
    void episode_backlogAbandonedOnAnOpenWritableStream_endsWithoutALaterWrite() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            holdFrames(network, lane, StreamType.CONSENSUS, 1, 2);
            assertThat(network.openBackpressureEpisodesForTests()).isEqualTo(1);

            lane.faultThenWritable.set(true);

            awaitWithin("the faulted backlog's entry is removed", AWAIT, () -> network.laneBacklogCountForTests() == 0);

            assertThat(lane.faultThenWritable).as("the fault was injected and consumed").isFalse();
            assertThat(lane.active).as("the channel is still open").isTrue();
            assertThat(lane.written).as("the abandoned frames were not written").isEmpty();
            assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP)).isEqualTo(2L);
            assertThat(network.openBackpressureEpisodesForTests()).as("the abandon ended the episode").isZero();
            assertThat(cleared(capture)).as("one cleared line for the one episode").hasSize(1);
        }
    }

    /// A held DHT frame is given up: the episode it ends is keyed by its OWN lane, (peer, DHT), whichever stream held it
    /// (a DHT frame can share the CONSENSUS stream). The stream's writability event alone ends it.
    @Test
    void episode_dhtFrameGivenUp_endsTheEpisodeOfItsOwnLane() throws Exception {
        var network = LossAccountingFixtures.network(5, TimeSpan.timeSpan(10).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            holdFrames(network, lane, StreamType.DHT, 1);

            awaitWithin("the held frame is given up",
                        AWAIT,
                        () -> network.quicMetrics().droppedAfterSentCount(StreamType.DHT, DropSite.RETRY_GAVE_UP) == 1L);
            lane.writable.set(true);
            fireWritabilityEvent(network, lane.ch);

            awaitWithin("the DHT episode ends once the stream is writable", AWAIT, () -> network.openBackpressureEpisodesForTests() == 0);

            assertThat(cleared(capture)).singleElement()
                                        .satisfies(message -> assertThat(message).contains("lane DHT cleared"));
        }
    }

    /// T-a gate, two lanes (#3313 memo3314 §7): a DHT frame held on the CONSENSUS stream behind a CONSENSUS frame. The
    /// drain writes the CONSENSUS frame and the stream turns unwritable right after it takes it, so the DHT frame stays
    /// held: the CONSENSUS episode ends (its lane's slot emptied), the DHT episode stays open (its lane's slot is not
    /// empty). Once the stream takes the DHT frame, the DHT episode ends. No writability event, no later write and no
    /// close: each episode ends by its own lane's frame leaving the backlog, and by nothing else.
    @Test
    void episode_framesOfTwoLanesHeldOnOneStream_eachLanesLastFrameLeavingEndsOnlyItsOwnEpisode() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            holdFrames(network, lane, StreamType.CONSENSUS, 1);
            holdFrames(network, lane, StreamType.DHT, 2);
            assertThat(network.openBackpressureEpisodesForTests()).as("control: each lane opened its episode").isEqualTo(2);

            lane.unwritableAfterWriteOf.set(1);
            lane.writable.set(true);
            awaitWithin("the CONSENSUS frame is written", AWAIT, () -> lane.written.size() == 1);

            assertThat(network.laneBacklogCountForTests()).as("control: the DHT frame is still held").isEqualTo(1);
            assertThat(network.openBackpressureEpisodesForTests()).as("the DHT episode stays open while its frame is held")
                                                                  .isEqualTo(1);
            assertThat(cleared(capture)).singleElement()
                                        .satisfies(message -> assertThat(message).contains("lane CONSENSUS cleared"));

            lane.writable.set(true);
            awaitWithin("the DHT frame is written", AWAIT, () -> lane.written.size() == 2);

            assertThat(network.openBackpressureEpisodesForTests()).as("the DHT frame leaving ended its episode").isZero();
            assertThat(cleared(capture)).as("one cleared line per episode").hasSize(2);
        }
    }

    /// F1 (#3313): a drain writes the held frame between its hold and its episode's opening. A CONSENSUS frame starts the
    /// backlog; a DHT frame joins it on the unwritable stream and opens its own lane's episode. At that opening the hook
    /// turns the stream writable and waits until the drain either wrote both frames (the opening runs outside the backlog
    /// lock, so nothing holds the drain off) or is blocked on the lock the holding thread holds. Once the drain wrote
    /// both frames, no episode may stay open: the opening and the closer exclude each other.
    @Test
    void episode_drainWritesTheFrameBetweenItsHoldAndItsEpisodeOpening_noEpisodeStaysOpen() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);
        var holder = Thread.currentThread();

        holdFrames(network, lane, StreamType.CONSENSUS, 1);
        network.beforeHeldEpisodeCountForTests(_ -> letTheDrainRace(lane, holder));
        holdFrames(network, lane, StreamType.DHT, 2);
        network.beforeHeldEpisodeCountForTests(null);

        awaitWithin("the drain wrote both frames and the backlog retired",
                    AWAIT,
                    () -> lane.written.size() == 2 && network.laneBacklogCountForTests() == 0);

        assertThat(lane.written).as("the drain wrote both frames, in send order").containsExactly(1, 2);
        assertThat(network.openBackpressureEpisodesForTests()).as("no episode stays open once its frame is written").isZero();
    }

    /// M13 (#3313): a drain fault abandons the backlog while the stream is open and still UNWRITABLE. The abandon removes
    /// the last held frame, but the stream takes no writes, so the episode stays open; the stream's writability event
    /// ends it.
    @Test
    void episode_backlogAbandonedOnAnUnwritableStream_staysOpen_untilTheWritabilityEvent() throws Exception {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            holdFrames(network, lane, StreamType.CONSENSUS, 1, 2);
            assertThat(network.openBackpressureEpisodesForTests()).as("the unwritable frames opened an episode").isEqualTo(1);

            lane.faultOnNextWritableCheck.set(true);

            awaitWithin("the faulted backlog's entry is removed", AWAIT, () -> network.laneBacklogCountForTests() == 0);

            assertThat(lane.faultOnNextWritableCheck).as("the fault was injected and consumed").isFalse();
            assertThat(lane.active).as("the channel is still open").isTrue();
            assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP)).isEqualTo(2L);
            assertThat(network.openBackpressureEpisodesForTests()).as("the abandon on an unwritable stream leaves the episode open")
                                                                  .isEqualTo(1);
            assertThat(cleared(capture)).as("no cleared line while the stream takes no writes").isEmpty();

            lane.writable.set(true);
            fireWritabilityEvent(network, lane.ch);

            assertThat(network.openBackpressureEpisodesForTests()).as("the writability event ended the episode").isZero();
            assertThat(lane.written).as("nothing was written after the holds").isEmpty();
            assertThat(cleared(capture)).as("one cleared line for the one episode").hasSize(1);
        }
    }

    /// M13 (#3313): the stream closes with frames held. The drain abandons the backlog, and the closer run by that abandon
    /// ends the episode at once: a closed stream is gone, so no writability event is waited for.
    @Test
    void episode_backlogAbandonedOnAClosedStream_endsAtOnce() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            holdFrames(network, lane, StreamType.CONSENSUS, 1, 2);
            assertThat(network.openBackpressureEpisodesForTests()).as("the unwritable frames opened an episode").isEqualTo(1);

            lane.active.set(false);

            // The entry is removed AFTER discardAll's callbacks ended the episode (abandonBacklog), so the await is on the
            // removal: awaiting the episode end instead raced the removal (seen once at f3313q's WIP).
            awaitWithin("the closed stream's entry is gone", AWAIT, () -> network.laneBacklogCountForTests() == 0);

            assertThat(network.openBackpressureEpisodesForTests()).as("the abandon ended the closed stream's episode").isZero();
            assertThat(lane.writable).as("the stream never turned writable").isFalse();
            assertThat(lane.written).as("nothing is written to a closed stream").isEmpty();
            assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP)).isEqualTo(2L);
            assertThat(cleared(capture)).as("one cleared line for the one episode").hasSize(1);
        }
    }

    /// C2 (#3313): the lane's episode is judged per (peer, lane), across every backlog of the peer. CONSENSUS frames are
    /// held on an old stream A (unwritable, live: a reconnect replaced it); a CONSENSUS write then finds the new stream B
    /// unwritable and is counted there, so B is the stream of the lane's latest counted write. B turns writable and its
    /// drain writes its frame, which runs the closer: the frames still held on A keep the episode open, with no "cleared"
    /// line. Only when A's frames are written does the episode end, with one line.
    @Test
    void episode_framesHeldOnAnOldStream_keepTheLanesEpisodeOpen_acrossACountedWriteOnTheNewOne() throws Exception {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var old = new Lane(succeeded(), -1);
        var replacement = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            holdFrames(network, old, StreamType.CONSENSUS, 1, 2);
            holdFrames(network, replacement, StreamType.CONSENSUS, 3);
            assertThat(network.openBackpressureEpisodesForTests()).as("one episode for the (peer, lane)").isEqualTo(1);

            replacement.writable.set(true);
            awaitWithin("the new stream's drain wrote its frame", AWAIT, () -> replacement.written.size() == 1);
            awaitWithin("the new stream's backlog retired", AWAIT, () -> network.laneBacklogCountForTests() == 1);
            fireWritabilityEvent(network, replacement.ch);

            assertThat(network.openBackpressureEpisodesForTests()).as("frames of the lane are still held on the old stream")
                                                                  .isEqualTo(1);
            assertThat(cleared(capture)).as("no cleared line while the old stream holds frames of the lane").isEmpty();

            old.writable.set(true);
            awaitWithin("the old stream's drain wrote its frames", AWAIT, () -> old.written.size() == 2);

            awaitWithin("the episode ends once no backlog holds a frame of the lane",
                        AWAIT,
                        () -> network.openBackpressureEpisodesForTests() == 0);
            assertThat(cleared(capture)).as("one cleared line for the one episode").hasSize(1);
        }
    }

    /// M15 (#3313): the closer's writability check throws. Nothing is ended on a throw: the episode stays open and one
    /// WARN says so; the closer's next run (the stream's writability event) ends it. A refused KV write opens the
    /// episode, so no drain runs the closer behind the test's back.
    @Test
    void episode_writabilityCheckThrowsInTheCloser_staysOpen_oneWarn_andTheNextRunEndsIt() throws Exception {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            holdFrames(network, lane, StreamType.KV, 1);
            assertThat(network.openBackpressureEpisodesForTests()).as("the refused write opened an episode").isEqualTo(1);
            assertThat(network.laneBacklogCountForTests()).as("a refused write holds nothing").isZero();

            lane.writable.set(true);
            lane.faultOnNextWritableCheck.set(true);
            network.onLaneStreamEvent(PEER, lane.ch);

            assertThat(lane.faultOnNextWritableCheck).as("the closer's writability check threw").isFalse();
            assertThat(network.openBackpressureEpisodesForTests()).as("nothing is ended on a throw").isEqualTo(1);
            assertThat(leftOpenWarnings(capture)).as("exactly one WARN for the failed check").hasSize(1);
            assertThat(cleared(capture)).as("no cleared line on a throw").isEmpty();

            fireWritabilityEvent(network, lane.ch);

            assertThat(network.openBackpressureEpisodesForTests()).as("the closer's next run ended the episode").isZero();
            assertThat(cleared(capture)).as("one cleared line for the one episode").hasSize(1);
            assertThat(leftOpenWarnings(capture)).as("the next run warns nothing").hasSize(1);
        }
    }

    /// A19 (#3313): a throw while evaluating ONE episode is contained to it. Two refused writes open two episodes of the
    /// peer (KV and FORWARD, two streams; refusals hold nothing, so no drain runs the closer); both streams turn writable
    /// and one closer run happens, in which the KV stream's writability check throws. The FORWARD episode is still
    /// ended, whichever of the two the run reaches first; the KV episode stays open with exactly one WARN.
    @Test
    void episode_writabilityCheckThrowsForOneEpisode_thePeersOtherEpisodeIsStillEnded_oneWarn() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var throwing = new Lane(succeeded(), -1);
        var other = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            holdFrames(network, throwing, StreamType.KV, 1);
            holdFrames(network, other, StreamType.FORWARD, 2);
            assertThat(network.openBackpressureEpisodesForTests()).as("the two refused writes opened two episodes")
                                                                  .isEqualTo(2);
            assertThat(network.laneBacklogCountForTests()).as("a refused write holds nothing").isZero();

            throwing.writable.set(true);
            other.writable.set(true);
            throwing.faultOnNextWritableCheck.set(true);
            network.onLaneStreamEvent(PEER, other.ch);

            assertThat(throwing.faultOnNextWritableCheck).as("the KV episode's writability check threw").isFalse();
            assertThat(network.openBackpressureEpisodesForTests()).as("only the episode whose check threw stays open")
                                                                  .isEqualTo(1);
            assertThat(cleared(capture)).as("one cleared line, for the FORWARD episode").hasSize(1);
            assertThat(cleared(capture).getFirst()).as("the cleared episode is the FORWARD one").contains("FORWARD");
            assertThat(leftOpenWarnings(capture)).as("exactly one WARN, for the KV episode").hasSize(1);
        }
    }

    /// T-f (#3313): the stream turns writable between a write's refusal and its count, and fires no event. The refused
    /// write counts, opening the episode, then runs the closer under the same monitor: it reads the stream writable and
    /// ends the episode. Nothing else can: a refusal holds no frame (no drain), no writability event and no close
    /// follow, and no later write is made.
    @Test
    void episode_streamTurnsWritableBetweenTheRefusalAndItsCount_theRefusedWriteEndsIt() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            lane.writableAfterNextCheck.set(true);
            holdFrames(network, lane, StreamType.KV, 1);

            assertThat(lane.writableAfterNextCheck).as("control: the refusal read the stream unwritable").isFalse();
            assertThat(lane.written).as("control: the write was refused, not written").isEmpty();
            assertThat(network.laneBacklogCountForTests()).as("a refused write holds nothing").isZero();
            assertThat(capture.messages(Level.WARN)
                              .stream()
                              .filter(message -> message.contains("entered"))
                              .toList()).as("the refused write opened an episode").hasSize(1);
            assertThat(network.openBackpressureEpisodesForTests()).as("the refused write's own closer run ended it")
                                                                  .isZero();
            assertThat(cleared(capture)).as("one cleared line for the one episode").hasSize(1);
        }
    }

    /// Lock order (#3313 memo3314 §7): the closer reads a backlog's lane slot without its lock. A drain writes held
    /// frames under the backlog lock and each write fires an inline writability event (the closer waits for the peer's
    /// episode monitor), while refused writes on another lane of the peer run the closer under that monitor (it reads the
    /// slot). A slot read that takes the backlog lock deadlocks the two: the drain never writes the next frame.
    @Test
    void episode_inlineWritabilityEventsInADrain_raceRefusedWritesOfThePeer_withoutDeadlock() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(2).millis());
        var lane = new Lane(succeeded(), -1);
        var refusing = new Lane(succeeded(), -1);
        var stop = new AtomicBoolean(false);

        lane.afterWrite.set(() -> network.onLaneStreamEvent(PEER, lane.ch));
        Thread.ofPlatform().daemon().start(() -> refuseUntil(network, refusing, stop));

        for (var round = 0; round < 50; round++) {
            var frames = round + 1;

            lane.writable.set(false);
            holdFrames(network, lane, StreamType.CONSENSUS, round);
            lane.writable.set(true);
            awaitWithin("the drain writes the held frame (a deadlock stops it)", AWAIT, () -> lane.written.size() == frames);
        }

        stop.set(true);
        assertThat(lane.written).as("every held frame was written, in order").hasSize(50);
    }

    private static void refuseUntil(QuicClusterNetwork network, Lane refusing, AtomicBoolean stop) {
        while (!stop.get()) {
            holdFrames(network, refusing, StreamType.KV, 0);
        }
    }

    // --- #3313 memo3314 §4/§7: a throwing metric or logger cannot leave a fact of the episode model wrong ---

    /// F1, metric (#3313): the drop metric throws when the held frame is given up. The frame left its backlog before the
    /// metric ran, so nothing of the lane is held: once the stream is writable its event ends the episode, the pressure
    /// count is back at zero, and a later write that meets backpressure opens a NEW episode with its own entry WARN (#2580
    /// still works on the lane). A transport-side copy of "a frame is held", decremented after the metric, stays at +1 for
    /// good and keeps the episode open.
    @Test
    void episode_dropMetricThrowsOnAGiveUp_theEpisodeStillEnds_andTheLaneOpensANewOne() throws Exception {
        var metrics = spy(QuicTransportMetrics.quicTransportMetrics());

        doThrow(new IllegalStateException("drop metric fault")).doCallRealMethod()
                                                                .when(metrics)
                                                                .onDroppedAfterSent(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP, 1L);
        var network = networkWith(metrics);

        assertEpisodeSurvivesAGiveUpFault(network, metrics, new LogFault(""));
    }

    /// F1, logger (#3313): as above, with the give-up WARN throwing instead of the metric.
    @Test
    void episode_giveUpWarnThrows_theEpisodeStillEnds_andTheLaneOpensANewOne() throws Exception {
        var metrics = spy(QuicTransportMetrics.quicTransportMetrics());
        var network = networkWith(metrics);

        assertEpisodeSurvivesAGiveUpFault(network, metrics, new LogFault("gave up after retries"));
    }

    /// F2 (#3313): the entry WARN of the first held frame throws inside the backlog's admit callback. The callback cannot
    /// let it out: the holding write returns, starts the drain, and the frame is written once the stream takes it; the
    /// pressure count returns to zero and no episode stays open. A throw escaping the callback strands the frame in a
    /// backlog nobody drains.
    @Test
    void episode_entryWarnThrowsOnTheFirstHeldFrame_theFrameIsStillDrained_andNoEpisodeStaysOpen() {
        var metrics = spy(QuicTransportMetrics.quicTransportMetrics());
        var network = networkWith(metrics);

        assertFirstHeldFrameSurvivesACountFault(network, new LogFault("entered"));
    }

    /// C1 (#3313): the backpressure-retry metric throws on the first held frame, inside the backlog's admit callback. As
    /// for the entry WARN: the frame is still drained, the pressure count returns to zero and no episode stays open.
    @Test
    void episode_retryMetricThrowsOnTheFirstHeldFrame_theFrameIsStillDrained_andNoEpisodeStaysOpen() {
        var metrics = spy(QuicTransportMetrics.quicTransportMetrics());

        doThrow(new IllegalStateException("retry metric fault")).doCallRealMethod()
                                                                 .when(metrics)
                                                                 .onBackpressureRetry();
        var network = networkWith(metrics);

        assertFirstHeldFrameSurvivesACountFault(network, new LogFault(""));
        verify(metrics).onBackpressureRetry();
    }

    /// INFO throws (#3313 memo3314 §7, decision: the episode is still removed): the cleared INFO of the episode a held
    /// write ends throws. The episode is removed all the same, so the open count is 0. Nothing else ends it: no
    /// writability event, no later write, no close.
    @Test
    void episode_clearedInfoThrows_theEpisodeIsStillRemoved() {
        var network = LossAccountingFixtures.network(500, TimeSpan.timeSpan(20).millis());
        var lane = new Lane(succeeded(), -1);
        var faulting = new LogFault("lane CONSENSUS cleared");

        try (var _ = faulting.capture()) {
            holdFrames(network, lane, StreamType.CONSENSUS, 1);
            assertThat(network.openBackpressureEpisodesForTests()).as("control: the frame opened an episode").isEqualTo(1);

            lane.writable.set(true);
            awaitWithin("the held frame is written", AWAIT, () -> lane.written.size() == 1);
            awaitWithin("the cleared INFO threw", AWAIT, () -> faulting.faults() == 1);

            assertThat(network.openBackpressureEpisodesForTests()).as("the throwing INFO did not keep the episode")
                                                                  .isZero();
        }
    }

    private static void assertEpisodeSurvivesAGiveUpFault(QuicClusterNetwork network,
                                                          QuicTransportMetrics metrics,
                                                          LogFault faulting) throws Exception {
        var lane = new Lane(succeeded(), -1);

        try (var capture = faulting.capture()) {
            holdFrames(network, lane, StreamType.CONSENSUS, 1);
            assertThat(network.openBackpressureEpisodesForTests()).as("the held frame opened an episode").isEqualTo(1);

            verify(metrics, timeout(AWAIT.millis())).onDroppedAfterSent(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP, 1L);
            awaitWithin("the backlog entry goes", AWAIT, () -> network.laneBacklogCountForTests() == 0);
            assertThat(lane.written).as("control: the frame was given up, not written").isEmpty();
            assertThat(faulting.faults()).as("control: the armed fault fired, if one was armed").isEqualTo(faulting.armed());

            lane.writable.set(true);
            fireWritabilityEvent(network, lane.ch);

            awaitWithin("the episode ends once the stream is writable", AWAIT, () -> network.openBackpressureEpisodesForTests() == 0);
            assertThat(network.consensusRetriesInFlight()).as("the pressure count returns to zero").isZero();

            lane.writable.set(false);
            holdFrames(network, lane, StreamType.CONSENSUS, 2);

            assertThat(entered(capture)).as("a new episode on the lane logs its own entry WARN").hasSize(2);
            assertThat(network.openBackpressureEpisodesForTests()).as("the new episode is open").isEqualTo(1);
        }
    }

    private static void assertFirstHeldFrameSurvivesACountFault(QuicClusterNetwork network, LogFault faulting) {
        var lane = new Lane(succeeded(), -1);

        try (var _ = faulting.capture()) {
            var escaped = catchThrowable(() -> holdFrames(network, lane, StreamType.CONSENSUS, 1));

            assertThat(network.heldFrameCountForTests(lane.ch)).as("control: the frame is held").isEqualTo(1);
            assertThat(faulting.faults()).as("control: the armed fault fired, if one was armed").isEqualTo(faulting.armed());

            lane.writable.set(true);
            awaitWithin("the held frame is drained", AWAIT, () -> lane.written.size() == 1);
            awaitWithin("the backlog entry goes", AWAIT, () -> network.laneBacklogCountForTests() == 0);

            assertThat(network.consensusRetriesInFlight()).as("the pressure count returns to zero").isZero();
            assertThat(network.openBackpressureEpisodesForTests()).as("no episode stays open").isZero();
            assertThat(escaped).as("the fault did not escape the holding write").isNull();
        }
    }

    /// C6 (#3313): a stream's backlog belongs to one peer, and the evaluation reads (a) only from the backlogs of the
    /// episode's peer. A frame of ANOTHER peer reaching a backlog would let its episode end while it is held, so it is
    /// refused, loudly where assertions are enabled, and never joins the backlog.
    @Test
    void hold_aFrameOfAnotherPeerReachesTheStreamsBacklog_isRefusedLoudly_andNotHeld() {
        var network = LossAccountingFixtures.network(5, TimeSpan.timeSpan(10).millis());
        var lane = new Lane(succeeded(), -1);
        var other = NodeId.randomNodeId("zzz-other");

        holdFrames(network, lane, StreamType.CONSENSUS, 1);
        assertThat(network.heldFrameCountForTests(lane.ch)).as("control: PEER's frame is held").isEqualTo(1);

        var refused = catchThrowable(() -> network.writeIfWritableForTest(lane.ch, new byte[]{2}, other, StreamType.CONSENSUS));

        assertThat(refused).as("the foreign frame fails loudly").isInstanceOf(AssertionError.class);
        assertThat(network.heldFrameCountForTests(lane.ch)).as("the foreign frame is not held").isEqualTo(1);
    }

    /// A network whose transport metrics are `metrics`: the network builds its own through
    /// [QuicTransportMetrics#quicTransportMetrics], which answers `metrics` while the network is constructed.
    private static QuicClusterNetwork networkWith(QuicTransportMetrics metrics) {
        try (var factory = mockStatic(QuicTransportMetrics.class, CALLS_REAL_METHODS)) {
            factory.when(QuicTransportMetrics::quicTransportMetrics).thenReturn(metrics);

            return LossAccountingFixtures.network(5, TimeSpan.timeSpan(10).millis());
        }
    }

    private static List<String> entered(LogCapture capture) {
        return capture.messages(Level.WARN)
                      .stream()
                      .filter(message -> message.contains("entered"))
                      .toList();
    }

    /// A logger fault: captures the network's logger, and throws out of the logging call, once, for the first event
    /// whose message contains `marker` (an empty marker arms nothing). [LogCapture#captureRethrowing] makes the throw (an
    /// [AppenderLoggingException], which log4j rethrows to the caller as is) reach the code that logged.
    private static final class LogFault {
        private final String marker;
        private final AtomicInteger faults = new AtomicInteger();

        private LogFault(String marker) {
            this.marker = marker;
        }

        LogCapture capture() {
            return LogCapture.captureRethrowing(LOGGER, Level.INFO, false, this::observe);
        }

        int armed() {
            return marker.isEmpty()
                   ? 0
                   : 1;
        }

        int faults() {
            return faults.get();
        }

        private void observe(LogEvent event) {
            if (armed() == 1 && event.getMessage()
                                     .getFormattedMessage()
                                     .contains(marker) && faults.compareAndSet(0, 1)) {
                throw new AppenderLoggingException("logger fault on: " + marker);
            }
        }
    }

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

    private static List<String> cleared(LogCapture capture) {
        return capture.messages(Level.INFO)
                      .stream()
                      .filter(message -> message.startsWith("Backpressure on peer") && message.contains("cleared"))
                      .toList();
    }

    private static List<String> leftOpenWarnings(LogCapture capture) {
        return capture.messages(Level.WARN)
                      .stream()
                      .filter(message -> !message.contains("entered"))
                      .toList();
    }

    /// The F1 race at the episode's opening: the stream turns writable, then the holding thread waits until the drain
    /// either wrote both frames or is blocked on a lock (the backlog's, which the holding thread holds). The drain runs on
    /// a virtual thread of the shared scheduler; it is found as a reader of the stream's liveness other than the holder.
    private static void letTheDrainRace(Lane lane, Thread holder) {
        lane.writable.set(true);
        awaitWithin("the drain wrote both frames or waits for the backlog lock",
                    AWAIT,
                    () -> lane.written.size() == 2 || drainBlocked(lane, holder));
    }

    private static boolean drainBlocked(Lane lane, Thread holder) {
        return lane.livenessReaders.stream()
                                   .filter(reader -> reader != holder)
                                   .anyMatch(reader -> reader.getState() == Thread.State.BLOCKED);
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
