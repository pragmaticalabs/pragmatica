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

    /// The published counter names are an interface: the expected strings are spelled out here, not derived from
    /// [DropSite#token], so renaming a site's token goes red.
    @Test
    void everySite_isPublishedUnderItsDocumentedName() {
        var expected = Map.of(DropSite.DRAIN, "drain",
                              DropSite.PENDING_LANE_WRITE, "pendingLaneWrite",
                              DropSite.RETRY_GAVE_UP, "retryGaveUp",
                              DropSite.WRITE_FAILED, "writeFailed",
                              DropSite.OFFLINE_OLDEST, "offlineOldest",
                              DropSite.LANE_OPEN_OLDEST, "laneOpenOldest",
                              DropSite.REPLACED_CONNECTION, "replacedConnection",
                              DropSite.BACKSTOP_EVICTION, "backstopEviction",
                              DropSite.REMOVED_AT_RETRY, "removedAtRetry");
        var metrics = QuicTransportMetrics.quicTransportMetrics();

        assertThat(expected).as("every site is listed").hasSize(DropSite.values().length);
        expected.keySet().forEach(site -> metrics.onDroppedAfterSent(StreamType.KV, site, 1));

        assertThat(metrics.droppedAfterSentSnapshot().keySet())
            .containsExactlyInAnyOrderElementsOf(expected.values().stream().map(token -> "droppedAfterSent.KV." + token).toList());
    }
}
