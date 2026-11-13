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

import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.PEER;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.network;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.stream;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;

/// #2583 P0: the refusal and retry give-up WARNs are limited to one per (peer, lane) per window, and a lane is heard
/// again once the window has passed. The gates keep `System.nanoTime()` stamps, so a test ages a stamp by rewriting it
/// (the production clock is not injectable); every window assertion is made against that rewritten stamp, never against
/// a sleep. The two gates are independent: a refusal on a lane does not silence its give-up.
@Timeout(30)
class QuicLossWarnWindowTest {
    private static final String LOGGER = "org.pragmatica.consensus.net.quic.QuicClusterNetwork";
    private static final String REFUSAL_WARN = "refusing message (backpressure)";
    private static final String GIVE_UP_WARN = "gave up after retries";
    /// Longer than the 30 s window.
    private static final long AGE_NANOS = TimeSpan.timeSpan(31).seconds().nanos();
    private static final TimeSpan SHORT = TimeSpan.timeSpan(5).millis();
    private static final byte[] BYTES = {1};

    @SuppressWarnings("unchecked")
    private static void ageGates(QuicClusterNetwork network) throws ReflectiveOperationException {
        for (var name : new String[]{"lastRefusalWarnNanos", "lastGiveUpWarnNanos"}) {
            var field = QuicClusterNetwork.class.getDeclaredField(name);

            field.setAccessible(true);
            ((Map<Object, Long>) field.get(network)).replaceAll((_, stamp) -> stamp - AGE_NANOS);
        }
    }

    private static long count(LogCapture capture, String fragment) {
        return capture.messages(Level.WARN).stream().filter(m -> m.contains(fragment)).count();
    }

    /// One CONSENSUS retry that spends its attempts; returns once it gave up (the WARN is logged before the probe falls).
    private static void giveUpOnce(QuicClusterNetwork network) {
        network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.CONSENSUS);
        awaitTrue(() -> network.consensusRetriesInFlight() == 0, AWAIT, "the give-up to finish");
    }

    @Test
    void refusalWarn_isGatedWithinTheWindow_andReadmittedOnceItHasPassed() throws Exception {
        var network = network(SHORT);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.FORWARD);
            network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.FORWARD);

            assertThat(count(capture, REFUSAL_WARN)).as("inside the window the refusal WARN is gated").isZero();

            ageGates(network);
            network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.FORWARD);

            assertThat(count(capture, REFUSAL_WARN)).as("after the window the lane is heard again").isEqualTo(1L);

            network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.FORWARD);

            assertThat(count(capture, REFUSAL_WARN)).as("and the new window gates it again").isEqualTo(1L);
        }
        assertThat(network.quicMetrics().refusedCount(StreamType.FORWARD)).as("every refusal is counted").isEqualTo(4L);
    }

    @Test
    void giveUpWarn_isLogged_gatedWithinTheWindow_andReadmittedOnceItHasPassed() throws Exception {
        var network = network(SHORT);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            giveUpOnce(network);

            assertThat(count(capture, GIVE_UP_WARN)).as("the first give-up on a lane logs").isEqualTo(1L);

            giveUpOnce(network);

            assertThat(count(capture, GIVE_UP_WARN)).as("inside the window the give-up WARN is gated").isEqualTo(1L);

            ageGates(network);
            giveUpOnce(network);

            assertThat(count(capture, GIVE_UP_WARN)).as("after the window the lane is heard again").isEqualTo(2L);
        }
        assertThat(network.quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS,
                                                               QuicTransportMetrics.DropSite.RETRY_GAVE_UP))
            .as("every give-up is counted")
            .isEqualTo(3L);
    }

    @Test
    void giveUpWarn_isNotSilencedByARefusalOnTheSameLane() {
        var network = network(SHORT);
        var inactive = stream(false);

        when(inactive.isActive()).thenReturn(false);
        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            network.writeIfWritableForTest(inactive, BYTES, PEER, StreamType.DHT);
            network.writeIfWritableForTest(stream(false), BYTES, PEER, StreamType.DHT);

            awaitTrue(() -> count(capture, GIVE_UP_WARN) == 1,
                      AWAIT,
                      "the DHT give-up WARN, though the lane's refusal already took its own window");
        }
    }
}
