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

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.net.quic.QuicTransportMetrics.DropSite;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.PEER;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.network;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.stream;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;

/// #2583 P0 (F1): a departed peer leaves no entry in the refusal or retry give-up WARN gates. Worker NodeIds are new per
/// incarnation, so an entry left behind by each departure grows the gates without bound.
@Timeout(30)
class QuicLossWarnGateDepartureTest {
    /// Three attempts 100 ms apart: the retry is still waiting for its next attempt when the peer departs.
    private static final TimeSpan SLOW = TimeSpan.timeSpan(100).millis();
    private static final byte[] BYTES = {1};

    @Test
    void departedPeer_withRetryInFlightAndALateRefusal_leavesNoGateEntry() {
        var network = network(SLOW);

        network.seedPeerForTests(PEER, PeerState.peerState(PEER, System.nanoTime()));
        var retried = network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.CONSENSUS);

        assertThat(retried).isInstanceOf(WriteOutcome.Sent.class);
        assertThat(network.consensusRetriesInFlight()).as("control: the retry is in flight at the departure").isEqualTo(1);

        network.departurePermanent(PEER);
        var refused = network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.FORWARD);

        assertThat(refused).as("control: the late write was refused").isInstanceOf(WriteOutcome.BackpressureRefused.class);
        awaitTrue(() -> network.consensusRetriesInFlight() == 0, AWAIT, "the in-flight retry to give up");
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP))
            .as("control: the retry gave up after the departure")
            .isEqualTo(1L);
        assertThat(network.lossWarnGateEntriesForTests()).as("both gates are empty for the departed peer").isZero();
    }

    @Test
    void refusalBeforeDeparture_hasItsGateEntryForgottenByTheDeparture() {
        var network = network(SLOW);

        network.seedPeerForTests(PEER, PeerState.peerState(PEER, System.nanoTime()));
        var _ = network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.FORWARD);

        assertThat(network.lossWarnGateEntriesForTests()).as("control: a live peer's refusal is gated").isEqualTo(1);

        network.departurePermanent(PEER);

        assertThat(network.lossWarnGateEntriesForTests()).isZero();
    }

    @Test
    void retryGivingUpAfterTheDeparture_logsNoWarnForTheDepartedPeer() {
        var network = network(SLOW);

        network.seedPeerForTests(PEER, PeerState.peerState(PEER, System.nanoTime()));
        try (var capture = LogCapture.capture("org.pragmatica.consensus.net.quic.QuicClusterNetwork", Level.INFO, false)) {
            var _ = network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.CONSENSUS);

            network.departurePermanent(PEER);
            awaitTrue(() -> network.consensusRetriesInFlight() == 0, AWAIT, "the in-flight retry to give up");

            assertThat(capture.messages(Level.WARN))
                .as("a give-up for a REMOVED peer logs no loss WARN")
                .noneMatch(m -> m.contains("gave up after retries"));
        }
    }

    @Test
    void departureOfAnAbsentPeer_forgetsItsGateEntry() {
        var network = network(SLOW);

        var _ = network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.FORWARD);

        assertThat(network.lossWarnGateEntriesForTests()).as("control: a refusal for a peer with no state is gated").isEqualTo(1);

        network.departurePermanent(PEER);

        assertThat(network.lossWarnGateEntriesForTests()).isZero();
    }
}
