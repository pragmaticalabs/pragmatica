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

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.PEER;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.SELF;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.connection;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.countingSerializer;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.network;
import static org.pragmatica.consensus.net.quic.LossAccountingFixtures.stream;

/// #2583 P0: a refusal is cheap. A frame bound for a fast-fail lane whose stream is not writable is refused BEFORE it is
/// encoded, and a refusal storm logs one WARN per (peer, lane) per window while every refusal is still counted.
@Timeout(30)
class QuicRefusalCostTest {
    private static final String LOGGER = "org.pragmatica.consensus.net.quic.QuicClusterNetwork";
    private static final String REFUSAL_WARN = "refusing message (backpressure)";
    private static final int REFUSALS = 50;
    private static final TimeSpan SHORT = TimeSpan.timeSpan(5).millis();

    private static LaneProbe probe(StreamType lane) {
        return LaneProbe.laneProbe(SELF, lane, "m");
    }

    private static QuicPeerConnection connectionWith(StreamType lane, boolean writable) {
        var connection = connection(PEER, QuicPeerConnection.LaneOpener.noop());

        connection.registerStream(lane, stream(writable));

        return connection;
    }

    @Test
    void refusedForwardFrame_isNotEncoded() {
        var encodes = new AtomicInteger();
        var network = network(countingSerializer(encodes), SHORT);
        var connection = connectionWith(StreamType.FORWARD, false);

        var outcome = network.writeToStreamForTests(PEER, probe(StreamType.FORWARD), connection);

        assertThat(outcome).isInstanceOf(WriteOutcome.BackpressureRefused.class);
        assertThat(encodes.get()).as("a refused frame costs no encode").isZero();
        assertThat(network.quicMetrics().refusedCount(StreamType.FORWARD)).isEqualTo(1L);
    }

    @Test
    void writableForwardFrame_isEncoded_positiveControl() {
        var encodes = new AtomicInteger();
        var network = network(countingSerializer(encodes), SHORT);
        var connection = connectionWith(StreamType.FORWARD, true);

        var outcome = network.writeToStreamForTests(PEER, probe(StreamType.FORWARD), connection);

        assertThat(outcome).isInstanceOf(WriteOutcome.Sent.class);
        assertThat(encodes.get()).as("the counting serializer is on the encode path").isEqualTo(1);
        assertThat(network.quicMetrics().refusedCount(StreamType.FORWARD)).isZero();
    }

    @Test
    void consensusFrame_onUnwritableStream_isStillEncodedBeforeItsRetry() {
        var encodes = new AtomicInteger();
        var network = network(countingSerializer(encodes), SHORT);
        var connection = connectionWith(StreamType.CONSENSUS, false);

        var outcome = network.writeToStreamForTests(PEER, probe(StreamType.CONSENSUS), connection);

        assertThat(outcome).as("CONSENSUS retries instead of refusing").isInstanceOf(WriteOutcome.Sent.class);
        assertThat(encodes.get()).isEqualTo(1);
        assertThat(network.quicMetrics().refusedCount(StreamType.CONSENSUS)).isZero();
    }

    @Test
    void refusalStorm_logsOneWarnPerPeerAndLane_butCountsEveryRefusal() {
        var network = network(SHORT);
        var forward = connectionWith(StreamType.FORWARD, false);
        var metrics = connectionWith(StreamType.METRICS, false);

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, false)) {
            for (var i = 0; i < REFUSALS; i++) {
                network.writeToStreamForTests(PEER, probe(StreamType.FORWARD), forward);
            }

            assertThat(capture.messages(Level.WARN).stream().filter(m -> m.contains(REFUSAL_WARN)).toList())
                .as("%d refusals on one (peer, lane): one WARN", REFUSALS)
                .hasSize(1);

            network.writeToStreamForTests(PEER, probe(StreamType.METRICS), metrics);

            assertThat(capture.messages(Level.WARN).stream().filter(m -> m.contains(REFUSAL_WARN)).toList())
                .as("another lane of the same peer has its own window")
                .hasSize(2);
        }

        assertThat(network.quicMetrics().refusedCount(StreamType.FORWARD)).as("every refusal is counted").isEqualTo(REFUSALS);
        assertThat(network.quicMetrics().refusedCount(StreamType.METRICS)).isEqualTo(1L);
    }
}
