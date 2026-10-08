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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.StreamType;

import io.netty.handler.codec.quic.QuicChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/// #1727 — what the immediate close of a superseded connection puts at risk, observed at supersede time:
/// the per-connection write accounting, the shape classification, and the metric the close records.
/// (The log line is INFO and names the same three facts; the tests pin the numbers it is built from.)
class QuicSupersedeObservationTest {
    private static final NodeId PEER = new NodeId("supersede-peer");
    private static final long NOW = TimeUnit.SECONDS.toNanos(100);

    private static QuicPeerConnection connection(NodeId initiator) {
        var channel = mock(QuicChannel.class);

        lenient().when(channel.isActive()).thenReturn(true);

        return QuicPeerConnection.quicPeerConnection(PEER, initiator, channel);
    }

    /// A lane with a write handed to the stream and not yet completed is unflushed; completing it clears that.
    @Test
    void unflushedWrite_isCounted_untilItCompletes() {
        var connection = connection(new NodeId("a"));

        connection.laneWriteStarted(StreamType.FORWARD);

        var risk = connection.laneWritesAtRisk(System.nanoTime() + TimeUnit.HOURS.toNanos(1));

        assertThat(risk.unflushedLanes()).as("unflushed, however old").isEqualTo(1);
        assertThat(risk.lanesAtRisk()).isEqualTo(1);

        connection.laneWriteCompleted(StreamType.FORWARD);

        assertThat(connection.laneWritesAtRisk(System.nanoTime() + TimeUnit.HOURS.toNanos(1)).lanesAtRisk())
            .as("completed and long ago: nothing at risk").isZero();
    }

    /// A completed write is still possibly unacked for a while: recent lanes count, old ones do not.
    @Test
    void recentlyWrittenLane_isCounted_butNotAnOldOne() {
        var connection = connection(new NodeId("a"));

        connection.laneWriteStarted(StreamType.CONSENSUS);
        connection.laneWriteCompleted(StreamType.CONSENSUS);
        var written = System.nanoTime();

        assertThat(connection.laneWritesAtRisk(written + TimeUnit.MILLISECONDS.toNanos(500)).recentLanes()).isEqualTo(1);
        assertThat(connection.laneWritesAtRisk(written + QuicPeerConnection.SUPERSEDE_RISK_WINDOW_NANOS
                                               + TimeUnit.SECONDS.toNanos(1)).recentLanes()).isZero();
    }

    /// An idle connection (no write ever) puts nothing at risk, and a lane that is both unflushed and recent is
    /// counted once.
    @Test
    void idleConnection_hasNothingAtRisk_andALaneIsCountedOnce() {
        var connection = connection(new NodeId("a"));

        assertThat(connection.laneWritesAtRisk(NOW).lanesAtRisk()).isZero();

        connection.laneWriteStarted(StreamType.FORWARD);
        var risk = connection.laneWritesAtRisk(System.nanoTime());

        assertThat(risk.unflushedLanes()).isEqualTo(1);
        assertThat(risk.recentLanes()).isEqualTo(1);
        assertThat(risk.lanesAtRisk()).as("one lane, counted once").isEqualTo(1);
    }

    @Test
    void shape_sameDialer_isASameDirectionRedial_differentDialer_isADualDialLoser() {
        var low = new NodeId("a");
        var high = new NodeId("b");

        assertThat(QuicClusterNetwork.supersedeShape(connection(low), connection(low))).isEqualTo("same-direction re-dial");
        assertThat(QuicClusterNetwork.supersedeShape(connection(high), connection(low))).isEqualTo("dual-dial loser");
        assertThat(QuicClusterNetwork.supersedeShape(QuicPeerConnection.quicPeerConnection(PEER, mock(QuicChannel.class)), connection(low)))
            .isEqualTo("unknown direction");
    }
}
