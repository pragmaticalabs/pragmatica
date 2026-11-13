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

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.net.quic.QuicTransportMetrics.DropSite;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.PEER;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.SELF;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.connectedState;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.connection;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.network;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.stream;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;

/// #2583 P0: every frame the transport reported `Sent` and then lost is counted at the site that lost it
/// (`droppedAfterSent.<lane>.<site>`, D1-D9), and a path that loses nothing moves no such counter. One case per site;
/// each asserts the exact lane and count, and that no other site moved, so a counter incremented at the wrong site, on
/// the wrong lane, or on success goes red.
@Timeout(30)
class QuicPostSentDropAccountingTest {
    private static final TimeSpan SHORT = TimeSpan.timeSpan(5).millis();

    private static LaneProbe probe(StreamType lane) {
        return LaneProbe.laneProbe(SELF, lane, "m");
    }

    private static void assertOnly(QuicClusterNetwork network, StreamType lane, DropSite site, long frames) {
        assertThat(network.quicMetrics().droppedAfterSentSnapshot())
            .as("the only post-Sent counter that moved is %s.%s", lane, site.token())
            .containsOnly(java.util.Map.entry("droppedAfterSent." + lane + "." + site.token(), frames));
    }

    /// A lane opener that holds the completion callback until the test releases it.
    private static final class HeldOpener implements QuicPeerConnection.LaneOpener {
        private final AtomicReference<Consumer<Option<io.netty.handler.codec.quic.QuicStreamChannel>>> held = new AtomicReference<>();

        @Override
        public void open(StreamType lane, Consumer<Option<io.netty.handler.codec.quic.QuicStreamChannel>> onResult) {
            held.compareAndSet(null, onResult);
        }

        void complete(Option<io.netty.handler.codec.quic.QuicStreamChannel> result) {
            held.get().accept(result);
        }
    }

    @Test
    void positiveControl_cleanDeliveryAndCleanDrain_moveNoPostSentCounter() {
        var network = network(SHORT);
        var connection = connection(PEER, QuicPeerConnection.LaneOpener.noop());
        var state = PeerState.peerState(PEER, System.nanoTime());

        connection.registerStream(StreamType.FORWARD, stream(true));
        var _ = state.offerOutbound(probe(StreamType.FORWARD));
        network.drainOfflineBufferForTests(state, connection);
        var outcome = network.writeToStreamForTests(PEER, probe(StreamType.FORWARD), connection);

        assertThat(outcome).isInstanceOf(WriteOutcome.Sent.class);
        assertThat(network.quicMetrics().droppedAfterSentSnapshot()).isEmpty();
    }

    @Test
    void d1_drain_refusedFrameOnReattach_isCountedOnItsLane() {
        var network = network(SHORT);
        var connection = connection(PEER, QuicPeerConnection.LaneOpener.noop());
        var state = PeerState.peerState(PEER, System.nanoTime());

        connection.registerStream(StreamType.FORWARD, stream(false));
        var _ = state.offerOutbound(probe(StreamType.FORWARD));
        network.drainOfflineBufferForTests(state, connection);

        assertOnly(network, StreamType.FORWARD, DropSite.DRAIN, 1);
        assertThat(network.quicMetrics().refusedCount(StreamType.FORWARD))
            .as("the drained frame's caller was told Sent, never refused")
            .isZero();
    }

    @Test
    void d1_drain_dhtFrameOnInactiveStream_isALoss_notARefusal() {
        var network = network(SHORT);
        var connection = connection(PEER, QuicPeerConnection.LaneOpener.noop());
        var state = PeerState.peerState(PEER, System.nanoTime());
        var inactive = stream(false);

        when(inactive.isActive()).thenReturn(false);
        connection.registerStream(StreamType.DHT, inactive);
        var _ = state.offerOutbound(probe(StreamType.DHT));
        network.drainOfflineBufferForTests(state, connection);

        assertOnly(network, StreamType.DHT, DropSite.DRAIN, 1);
        assertThat(network.quicMetrics().refusedCount(StreamType.DHT)).isZero();
    }

    @Test
    void callerRefusal_isCountedAsRefused_control() {
        var network = network(SHORT);
        var connection = connection(PEER, QuicPeerConnection.LaneOpener.noop());

        connection.registerStream(StreamType.FORWARD, stream(false));
        var outcome = network.writeToStreamForTests(PEER, probe(StreamType.FORWARD), connection);

        assertThat(outcome).isInstanceOf(WriteOutcome.BackpressureRefused.class);
        assertThat(network.quicMetrics().refusedCount(StreamType.FORWARD)).isEqualTo(1L);
        assertThat(network.quicMetrics().droppedAfterSentSnapshot()).as("a refusal its caller sees is not a loss").isEmpty();
    }

    @Test
    void d2_pendingLaneWrite_refusedOnceTheLaneOpens_isCountedOnItsLane() {
        var network = network(SHORT);
        var opener = new HeldOpener();
        var connection = connection(PEER, opener);
        var blocked = stream(false);

        network.seedPeerForTests(PEER, connectedState(PEER, connection));
        var _ = network.writeToStreamForTests(PEER, probe(StreamType.METRICS), connection);
        connection.registerStream(StreamType.METRICS, blocked);
        opener.complete(Option.some(blocked));

        assertOnly(network, StreamType.METRICS, DropSite.PENDING_LANE_WRITE, 1);
        assertThat(network.quicMetrics().refusedCount(StreamType.METRICS))
            .as("the pending frame's caller was told Sent, never refused")
            .isZero();
    }

    @Test
    void d3_retryGaveUp_isCountedOnTheConsensusLane() {
        var network = network(SHORT);

        var outcome = network.writeIfWritableForTest(stream(false), new byte[]{1}, PEER, StreamType.CONSENSUS);

        assertThat(outcome).isInstanceOf(WriteOutcome.Sent.class);
        awaitTrue(() -> network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP) == 1,
                  AWAIT,
                  "the retry to give up");
        assertOnly(network, StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP, 1);
    }

    @Test
    void d4_writeFailed_isCountedOnItsLane() {
        var network = network(SHORT);
        @SuppressWarnings("unchecked")
        Future<Void> failed = mock(Future.class);

        when(failed.isSuccess()).thenReturn(false);
        when(failed.cause()).thenReturn(new IllegalStateException("simulated write failure"));
        network.handleWriteResultForTest(failed, PEER, StreamType.KV);

        assertOnly(network, StreamType.KV, DropSite.WRITE_FAILED, 1);
    }

    @Test
    void d5_offlineBufferEvictsOldest_isCountedOnTheEvictedFramesLane() {
        var network = network(SHORT);
        var state = PeerState.peerState(PEER, System.nanoTime());

        for (var i = 0; i < PeerState.OFFLINE_BUFFER_MAX; i++) {
            var _ = state.offerOutbound(probe(StreamType.CONSENSUS));
        }

        assertThat(network.quicMetrics().droppedAfterSentSnapshot()).as("control: filling the buffer drops nothing").isEmpty();

        var outcome = network.offerOutboundForTest(state, probe(StreamType.FORWARD));

        assertThat(outcome).as("a queued frame is reported Sent").isInstanceOf(WriteOutcome.Sent.class);
        assertOnly(network, StreamType.CONSENSUS, DropSite.OFFLINE_OLDEST, 1);
    }

    @Test
    void d6_laneOpenPendingQueueEvictsOldest_isCountedOnItsLane() {
        var network = network(SHORT);
        var connection = connection(PEER, new HeldOpener());

        network.seedPeerForTests(PEER, connectedState(PEER, connection));
        for (var i = 0; i < QuicPeerConnection.PENDING_LANE_WRITES_MAX + 2; i++) {
            var _ = network.writeToStreamForTests(PEER, probe(StreamType.METRICS), connection);
        }

        assertOnly(network, StreamType.METRICS, DropSite.LANE_OPEN_OLDEST, 2);
    }

    @Test
    void d7_laneOpenFailedOnReplacedConnection_boundHasNoStream_countsEveryPendingFrame() {
        var network = network(SHORT);
        var survivor = connection(PEER, QuicPeerConnection.LaneOpener.noop());
        var loser = connection(PEER, QuicPeerConnection.LaneOpener.noop());

        network.seedPeerForTests(PEER, connectedState(PEER, survivor));
        network.laneOpenFailedForTest(PEER, StreamType.METRICS, loser, List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}));

        assertOnly(network, StreamType.METRICS, DropSite.REPLACED_CONNECTION, 3);
    }

    @Test
    void d8_laneOpenFailedOnBoundConnection_backstopCountsEveryPendingFrame() {
        var network = network(SHORT);
        var bound = connection(PEER, QuicPeerConnection.LaneOpener.noop());

        network.seedPeerForTests(PEER, connectedState(PEER, bound));
        network.laneOpenFailedForTest(PEER, StreamType.METRICS, bound, List.of(new byte[]{1}, new byte[]{2}));

        assertOnly(network, StreamType.METRICS, DropSite.BACKSTOP_EVICTION, 2);
    }

    @Test
    void d9_retryEnteredForRemovedPeer_isCountedOnItsLane() {
        var network = network(SHORT);
        var removed = PeerState.peerState(PEER, System.nanoTime());
        var _ = removed.authoritativeRemove(System.nanoTime());

        network.seedPeerForTests(PEER, removed);
        var outcome = network.writeIfWritableForTest(stream(false), new byte[]{1}, PEER, StreamType.CONSENSUS);

        assertThat(outcome).isInstanceOf(WriteOutcome.Sent.class);
        assertOnly(network, StreamType.CONSENSUS, DropSite.REMOVED_AT_RETRY, 1);
    }

    @Test
    void dropAfterSentMode_reportsSent_notARefusal_andMovesNoCounter() {
        var network = network(SHORT);

        network.setOutboundRefusalFilter((_, _) -> true, QuicClusterNetwork.OutboundFaultMode.DROP_AFTER_SENT);
        var dropped = network.send(PEER, probe(StreamType.FORWARD)).await(AWAIT);
        network.setOutboundRefusalFilter((_, _) -> true, QuicClusterNetwork.OutboundFaultMode.REFUSE);
        var refused = network.send(PEER, probe(StreamType.FORWARD)).await(AWAIT);

        dropped.onSuccess(outcome -> assertThat(outcome).isInstanceOf(WriteOutcome.Sent.class))
               .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        refused.onSuccess(outcome -> assertThat(outcome).as("control: REFUSE still refuses")
                                                        .isInstanceOf(WriteOutcome.BackpressureRefused.class))
               .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        assertThat(network.quicMetrics().refusedCount(StreamType.FORWARD)).isZero();
        assertThat(network.quicMetrics().droppedAfterSentSnapshot()).isEmpty();
    }
}
