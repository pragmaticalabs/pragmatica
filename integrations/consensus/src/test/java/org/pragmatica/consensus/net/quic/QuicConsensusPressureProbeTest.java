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
@Timeout(30)
class QuicConsensusPressureProbeTest {
    /// Three attempts 100 ms apart: a retry that never succeeds stays in flight for about 200 ms.
    private static final TimeSpan SLOW = TimeSpan.timeSpan(100).millis();
    private static final byte[] BYTES = {1};

    @Test
    void consensusRetry_countsWhileInFlight_andFallsToZeroAfterGivingUp() {
        var network = network(SLOW);

        network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.CONSENSUS);

        assertThat(network.consensusRetriesInFlight()).as("a retry is waiting for its next attempt").isEqualTo(1);
        awaitTrue(() -> network.consensusRetriesInFlight() == 0, AWAIT, "the give-up to release the probe");
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS,
                                                               QuicTransportMetrics.DropSite.RETRY_GAVE_UP))
            .as("control: the retry really gave up")
            .isEqualTo(1L);
    }

    @Test
    void consensusRetry_fallsToZeroAfterDelivering() {
        var network = network(SLOW);
        var recovering = stream(false);

        when(recovering.isWritable()).thenReturn(false, false, true);
        network.writeIfWritableForTest(recovering, BYTES, PEER, StreamType.CONSENSUS);

        assertThat(network.consensusRetriesInFlight()).isEqualTo(1);
        awaitTrue(() -> network.consensusRetriesInFlight() == 0, AWAIT, "the delivery to release the probe");
        assertThat(network.quicMetrics().droppedAfterSentSnapshot()).as("control: nothing was lost").isEmpty();
    }

    @Test
    void dhtRetry_isNotCounted() {
        var network = network(SLOW);

        network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.DHT);

        assertThat(network.consensusRetriesInFlight()).as("a DHT retry is not consensus pressure").isZero();
        awaitTrue(() -> network.quicMetrics().droppedAfterSentCount(StreamType.DHT,
                                                                    QuicTransportMetrics.DropSite.RETRY_GAVE_UP) == 1,
                  AWAIT,
                  "control: the DHT retry ran and gave up");
        assertThat(network.consensusRetriesInFlight()).isZero();
    }
}
