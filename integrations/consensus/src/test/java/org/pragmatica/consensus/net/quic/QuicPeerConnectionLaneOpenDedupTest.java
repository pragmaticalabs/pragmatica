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

import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.StreamType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/// #718 — unit coverage for the two credit leaks that live on [QuicPeerConnection].
///
/// Both are about the connection's 64 bidirectional stream credits, and both were silent:
///
///   1. **No in-flight dedup on the lazy lane open.** The heal fired per outbound message whose lane
///      was missing, and each firing created a stream. In two measured 72-second runs the FAILED
///      opens equalled the `STREAM_LIMIT_ERROR`s exactly — 857==857 and 639==639 — so one
///      transiently missing lane was consuming the whole connection's credit.
///   2. **`registerStream` overwrote without closing.** The displaced stream became unreachable (the
///      lane now resolves to the replacement) while staying OPEN, holding its credit for the life of
///      the connection.
///
/// The state deliberately lives on the CONNECTION rather than in a `(peerId, lane)` map on the
/// transport, and [#markerDiesWithTheConnection] is the test that states why: a marker leaked by a
/// callback that never fires must not be able to outlive the connection it belonged to and suppress
/// healing for a peer permanently.
@Timeout(30)
class QuicPeerConnectionLaneOpenDedupTest {

    @Nested
    class InFlightAdmission {

        /// The first write to find a missing lane owns the open. Exactly one caller per cycle gets
        /// this, and it is the only one that may create a stream.
        @Test
        void beginLaneOpen_firstCallForALane_reportsStarted() {
            var connection = connection();

            assertThat(connection.beginLaneOpen(StreamType.DHT, payload("first")))
                .isInstanceOf(QuicPeerConnection.LaneOpenAdmission.Started.class);
            assertThat(connection.inFlightLaneOpenCount())
                .describedAs("the lane now carries an in-flight marker")
                .isEqualTo(1);
        }

        /// THE pin for leak 1. Every subsequent write while the open is in flight joins it instead of
        /// starting its own — so twelve messages create ONE stream, not twelve.
        @Test
        void beginLaneOpen_whileAnOpenIsInFlight_coalescesEverySubsequentWrite() {
            var connection = connection();

            var admissions = IntStream.range(0, 12)
                                      .mapToObj(index -> connection.beginLaneOpen(StreamType.DHT, payload("m" + index)))
                                      .toList();

            assertThat(admissions.getFirst())
                .describedAs("only the first write owns the open")
                .isInstanceOf(QuicPeerConnection.LaneOpenAdmission.Started.class);
            assertThat(admissions.subList(1, admissions.size()))
                .describedAs("the other eleven ride the open already in flight — before #718 each of "
                             + "these created its own stream against a 64-stream credit")
                .hasSize(11)
                .allMatch(QuicPeerConnection.LaneOpenAdmission.Coalesced.class::isInstance);
            assertThat(connection.inFlightLaneOpenCount())
                .describedAs("twelve writes, ONE in-flight open")
                .isEqualTo(1);
        }

        /// The gate is per (peer, LANE), not per connection: a missing DHT lane must not block a
        /// missing SYNC lane from healing. The measured incident's dominant healed lane was SYNC while
        /// DHT was the registered-but-unwritable one, so conflating them would have stalled the wrong
        /// lane.
        @Test
        void beginLaneOpen_differentLanes_eachOwnsItsOwnOpen() {
            var connection = connection();

            assertThat(connection.beginLaneOpen(StreamType.DHT, payload("dht")))
                .isInstanceOf(QuicPeerConnection.LaneOpenAdmission.Started.class);
            assertThat(connection.beginLaneOpen(StreamType.SYNC, payload("sync")))
                .describedAs("a second LANE is a second open, never a coalesce")
                .isInstanceOf(QuicPeerConnection.LaneOpenAdmission.Started.class);
            assertThat(connection.inFlightLaneOpenCount()).isEqualTo(2);
        }

        /// Completion hands back everything that waited, oldest first, INCLUDING the owner's own
        /// message — the owner queues its bytes through the same gate, so there is one drain path and
        /// not a special case.
        @Test
        void completeLaneOpen_returnsEveryPendingMessageOldestFirst() {
            var connection = connection();

            connection.beginLaneOpen(StreamType.DHT, payload("owner"));
            connection.beginLaneOpen(StreamType.DHT, payload("second"));
            connection.beginLaneOpen(StreamType.DHT, payload("third"));

            assertThat(connection.completeLaneOpen(StreamType.DHT).stream().map(QuicPeerConnectionLaneOpenDedupTest::text))
                .describedAs("the owner's message is first, then arrival order — nothing is lost")
                .containsExactly("owner", "second", "third");
        }

        /// Completion RELEASES the marker. This is the no-leak pin: a lane that healed once must be
        /// able to heal again, or a second zombie on the same lane would be permanently unhealable.
        @Test
        void completeLaneOpen_releasesTheMarkerSoTheLaneCanHealAgain() {
            var connection = connection();

            connection.beginLaneOpen(StreamType.DHT, payload("first cycle"));
            connection.completeLaneOpen(StreamType.DHT);

            assertThat(connection.inFlightLaneOpenCount())
                .describedAs("the marker is gone once the open has been accounted for")
                .isZero();
            assertThat(connection.beginLaneOpen(StreamType.DHT, payload("second cycle")))
                .describedAs("a later write starts a FRESH open rather than coalescing onto a corpse")
                .isInstanceOf(QuicPeerConnection.LaneOpenAdmission.Started.class);
        }

        /// A duplicate or unpaired completion is inert. The transport calls this on BOTH outcomes of
        /// the open, and an empty return must not be mistaken for a lane with pending work.
        @Test
        void completeLaneOpen_withNothingInFlight_returnsEmptyAndDoesNotThrow() {
            assertThat(connection().completeLaneOpen(StreamType.DHT)).isEmpty();
        }

        /// The bound, asserted at its exact edge. `PENDING_LANE_WRITES_MAX` messages are retained;
        /// the next one drops the OLDEST and says so, mirroring the offline buffer's documented
        /// drop-oldest policy rather than inventing a second one.
        @Test
        void beginLaneOpen_pastThePendingBound_dropsTheOldestAndReportsIt() {
            var connection = connection();
            var bound = QuicPeerConnection.PENDING_LANE_WRITES_MAX;

            // One Started + (bound - 1) Coalesced fills the queue to exactly the bound.
            var fillingAdmissions = IntStream.range(0, bound)
                                             .mapToObj(index -> connection.beginLaneOpen(StreamType.DHT,
                                                                                         payload("m" + index)))
                                             .toList();

            assertThat(fillingAdmissions.subList(1, fillingAdmissions.size()))
                .describedAs("filling up to the bound drops nothing")
                .allMatch(admission -> admission instanceof QuicPeerConnection.LaneOpenAdmission.Coalesced(boolean dropped)
                                       && !dropped);

            var overflow = connection.beginLaneOpen(StreamType.DHT, payload("overflow"));

            assertThat(overflow)
                .describedAs("the message past the bound reports that it cost the oldest one")
                .isEqualTo(new QuicPeerConnection.LaneOpenAdmission.Coalesced(true));

            var drained = connection.completeLaneOpen(StreamType.DHT);

            assertThat(drained)
                .describedAs("retention is capped AT the bound, never above it")
                .hasSize(bound);
            assertThat(text(drained.getFirst()))
                .describedAs("the OLDEST was the one dropped — m0 is gone and m1 is now the head")
                .isEqualTo("m1");
            assertThat(text(drained.getLast()))
                .describedAs("the newest message is retained")
                .isEqualTo("overflow");
        }

        /// Why the marker lives on the connection and not in a transport-level map keyed by peer id.
        /// An eviction and re-dial builds a fresh [QuicPeerConnection]; a marker leaked by an opener
        /// whose callback never fires therefore dies with the connection that owned it. Keyed by peer,
        /// the same leak would suppress that peer's lane healing for the process lifetime.
        @Test
        void markerDiesWithTheConnection() {
            var leaked = connection();

            leaked.beginLaneOpen(StreamType.DHT, payload("never completed"));

            assertThat(leaked.inFlightLaneOpenCount())
                .describedAs("precondition: this connection really is holding a leaked marker")
                .isEqualTo(1);

            var afterRedial = connection();

            assertThat(afterRedial.inFlightLaneOpenCount())
                .describedAs("the re-dialled connection starts clean — the leak cannot cross it")
                .isZero();
            assertThat(afterRedial.beginLaneOpen(StreamType.DHT, payload("after re-dial")))
                .describedAs("and the lane heals normally on the new connection")
                .isInstanceOf(QuicPeerConnection.LaneOpenAdmission.Started.class);
        }
    }

    @Nested
    class SupersededStreamClose {

        /// THE pin for leak 2. Re-registering a lane displaces the previous stream; that stream is now
        /// unreachable for writes, so leaving it open holds one of 64 stream credits forever.
        @Test
        void registerStream_replacingALiveStream_closesTheSupersededOne() {
            var connection = connection();
            var first = liveStream();
            var second = liveStream();

            connection.registerStream(StreamType.DHT, first);
            connection.registerStream(StreamType.DHT, second);

            verify(first, times(1)).close();
            verify(second, never()).close();
            assertThat(connection.stream(StreamType.DHT))
                .describedAs("the lane resolves to the replacement")
                .isEqualTo(Option.some(second));
        }

        /// Re-registering the SAME channel is idempotent, not a self-close. The acceptor path can
        /// register a lane it already holds; closing it there would tear down a healthy lane.
        @Test
        void registerStream_sameChannelAgain_doesNotCloseIt() {
            var connection = connection();
            var stream = liveStream();

            connection.registerStream(StreamType.DHT, stream);
            connection.registerStream(StreamType.DHT, stream);

            verify(stream, never()).close();
            assertThat(connection.stream(StreamType.DHT)).isEqualTo(Option.some(stream));
        }

        /// An already-dead displaced stream needs no close — there is no credit left to return, and
        /// calling close on it would be noise on the reconnect path, which is exactly where this fires.
        @Test
        void registerStream_supersedingADeadStream_doesNotCloseIt() {
            var connection = connection();
            var dead = liveStream();

            lenient().when(dead.isActive()).thenReturn(false);
            connection.registerStream(StreamType.DHT, dead);
            connection.registerStream(StreamType.DHT, liveStream());

            verify(dead, never()).close();
        }

        /// The first registration of a lane has nothing to supersede — the common case, and it must
        /// not touch the stream it is installing.
        @Test
        void registerStream_firstRegistrationOfALane_closesNothing() {
            var connection = connection();
            var only = liveStream();

            connection.registerStream(StreamType.DHT, only);

            verify(only, never()).close();
            assertThat(connection.stream(StreamType.DHT)).isEqualTo(Option.some(only));
        }
    }

    // --- Helpers ---

    private static QuicPeerConnection connection() {
        var channel = mock(QuicChannel.class);

        lenient().when(channel.isActive()).thenReturn(true);

        return QuicPeerConnection.quicPeerConnection(new NodeId("dedup-peer"), channel);
    }

    private static QuicStreamChannel liveStream() {
        var stream = mock(QuicStreamChannel.class);

        lenient().when(stream.isActive()).thenReturn(true);
        lenient().when(stream.isWritable()).thenReturn(true);

        return stream;
    }

    private static byte[] payload(String marker) {
        return marker.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
