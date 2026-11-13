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

import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.PEER;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.network;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.stream;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;

/// #2583 P0: the §6.2 pressure probe counts CONSENSUS backpressure retries in flight, and only those. It rises when a
/// retry starts and falls when the retry ends, delivered or given up; a DHT retry (same retry path) never moves it.
///
/// No assertion races a clock. A retry under test has an attempt budget it cannot spend within the test, so it stays in
/// flight until the test releases it (the stream turns writable, or the peer departs and the next attempt fails
/// terminally); the "in flight" assertions hold until then. The one test that lets a retry spend its whole budget asserts
/// only after the give-up.
@Timeout(30)
class QuicConsensusPressureProbeTest {
    private static final TimeSpan SLOW = TimeSpan.timeSpan(20).millis();
    private static final TimeSpan SHORT = TimeSpan.timeSpan(5).millis();
    /// More attempts than the test can spend: 100000 x 20 ms.
    private static final int UNSPENT = 100_000;
    private static final byte[] BYTES = {1};

    private static QuicStreamChannel gatedStream(AtomicBoolean writable) {
        var gated = stream(false);

        when(gated.isWritable()).thenAnswer(_ -> writable.get());

        return gated;
    }

    @Test
    void consensusRetry_countsWhileInFlight_andFallsToZeroAfterDelivering() {
        var network = network(UNSPENT, SLOW);
        var writable = new AtomicBoolean(false);

        network.writeIfWritableForTest(gatedStream(writable), BYTES, PEER, StreamType.CONSENSUS);

        assertThat(network.consensusRetriesInFlight()).as("the retry waits for the stream: it cannot end before").isEqualTo(1);
        writable.set(true);
        awaitTrue(() -> network.consensusRetriesInFlight() == 0, AWAIT, "the delivery to release the probe");
        assertThat(network.quicMetrics().droppedAfterSentSnapshot()).as("control: nothing was lost").isEmpty();
    }

    @Test
    void consensusRetry_fallsToZeroAfterGivingUpOnARemovedPeer() {
        var network = network(UNSPENT, SLOW);

        network.seedPeerForTests(PEER, PeerState.peerState(PEER, System.nanoTime()));
        network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.CONSENSUS);

        assertThat(network.consensusRetriesInFlight()).as("the retry waits for the stream: it cannot end before").isEqualTo(1);
        network.departurePermanent(PEER);
        awaitTrue(() -> network.consensusRetriesInFlight() == 0, AWAIT, "the give-up to release the probe");
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS,
                                                               QuicTransportMetrics.DropSite.RETRY_GAVE_UP))
            .as("control: the retry really gave up")
            .isEqualTo(1L);
    }

    @Test
    void consensusRetry_fallsToZeroAfterSpendingItsAttempts() {
        var network = network(SHORT);

        network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.CONSENSUS);

        awaitTrue(() -> network.consensusRetriesInFlight() == 0, AWAIT, "the give-up to release the probe");
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS,
                                                               QuicTransportMetrics.DropSite.RETRY_GAVE_UP))
            .as("control: the retry really gave up, so the probe was released by the give-up")
            .isEqualTo(1L);
    }

    @Test
    void dhtRetry_isNotCounted() {
        var network = network(UNSPENT, SLOW);

        network.seedPeerForTests(PEER, PeerState.peerState(PEER, System.nanoTime()));
        network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.DHT);

        assertThat(network.quicMetrics().backpressureRetryCount()).as("control: the DHT retry is in flight").isEqualTo(1L);
        assertThat(network.consensusRetriesInFlight()).as("a DHT retry is not consensus pressure").isZero();
        network.departurePermanent(PEER);
        awaitTrue(() -> network.quicMetrics().droppedAfterSentCount(StreamType.DHT,
                                                                    QuicTransportMetrics.DropSite.RETRY_GAVE_UP) == 1,
                  AWAIT,
                  "control: the DHT retry gave up");
        assertThat(network.consensusRetriesInFlight()).as("a DHT retry's end does not move the probe either").isZero();
    }
}
