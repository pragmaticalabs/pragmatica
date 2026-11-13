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

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.net.quic.QuicTransportMetrics.DropSite;
import org.pragmatica.messaging.StreamType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2583 P0 (N7): `droppedAfterSentSnapshot` lists the counters sorted by name, is read-only, and holds no zero entry.
class QuicDroppedAfterSentSnapshotTest {
    @Test
    void snapshot_iteratesInNameOrder() {
        var metrics = QuicTransportMetrics.quicTransportMetrics();

        for (var lane : StreamType.values()) {
            metrics.onDroppedAfterSent(lane, DropSite.DRAIN, 1);
            metrics.onDroppedAfterSent(lane, DropSite.WRITE_FAILED, 1);
        }

        var keys = metrics.droppedAfterSentSnapshot().keySet().stream().toList();

        assertThat(keys).as("one counter per (lane, site) recorded").hasSize(2 * StreamType.values().length);
        assertThat(keys).as("iteration order is the name order").isSorted();
    }

    @Test
    void snapshot_isReadOnly() {
        var metrics = QuicTransportMetrics.quicTransportMetrics();

        metrics.onDroppedAfterSent(StreamType.KV, DropSite.DRAIN, 1);
        var snapshot = metrics.droppedAfterSentSnapshot();

        assertThatThrownBy(() -> snapshot.put("droppedAfterSent.KV.drain", 5L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void zeroFrames_createNoEntry() {
        var metrics = QuicTransportMetrics.quicTransportMetrics();

        metrics.onDroppedAfterSent(StreamType.METRICS, DropSite.BACKSTOP_EVICTION, 0);

        assertThat(metrics.droppedAfterSentSnapshot()).as("an empty batch is not a loss").isEmpty();
    }

    @Test
    void positiveFrames_areRecorded_control() {
        var metrics = QuicTransportMetrics.quicTransportMetrics();

        metrics.onDroppedAfterSent(StreamType.METRICS, DropSite.BACKSTOP_EVICTION, 2);

        assertThat(metrics.droppedAfterSentSnapshot()).containsOnly(Map.entry("droppedAfterSent.METRICS.backstopEviction",
                                                                              2L));
    }
}
