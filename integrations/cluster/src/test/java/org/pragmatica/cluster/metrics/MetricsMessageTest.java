// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPong;
import org.pragmatica.cluster.metrics.MetricsMessage.SnapshotPayload;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


class MetricsMessageTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER = NodeId.nodeId("peer").unwrap();

    @Nested
    class PingConstruction {
        @Test
        void metricsPing_fullFields_populatesRecord() {
            var payload = SnapshotPayload.snapshotPayload(new byte[]{1, 2, 3});
            var ping = new MetricsPing(SELF,
                                         Map.of(SELF, Map.of("cpu", 0.5)),
                                         7L,
                                         7L,
                                         42L,
                                         Option.some(payload));

            assertThat(ping.sender()).isEqualTo(SELF);
            assertThat(ping.rabiaTerm()).isEqualTo(7L);
            assertThat(ping.epochTerm()).isEqualTo(7L);
            assertThat(ping.epochCounter()).isEqualTo(42L);
            assertThat(ping.snapshot()).isEqualTo(Option.some(payload));
        }

        @Test
        void metricsPing_backwardCompatFactory_populatesZeroEpoch() {
            var ping = MetricsPing.metricsPing(SELF, Map.of(SELF, Map.of("cpu", 0.5)));

            assertThat(ping.rabiaTerm()).isZero();
            assertThat(ping.epochTerm()).isZero();
            assertThat(ping.epochCounter()).isZero();
            assertThat(ping.snapshot()).isEqualTo(Option.none());
        }

        @Test
        void construct_nullSnapshot_normalizesToNone() {
            var ping = new MetricsPing(SELF, Map.of(), 0L, 0L, 0L, null);

            assertThat(ping.snapshot()).isEqualTo(Option.none());
        }
    }

    @Nested
    class PongConstruction {
        @Test
        void metricsPong_fullFields_populatesRecord() {
            var governor = NodeId.nodeId("gov-1").unwrap();
            var report = CommunityReport.communityReport("pool-a", 1L, 1L, 2L, governor, 3, 3, 0, 0, Set.of("p"), 10L);

            var pong = new MetricsPong(PEER,
                                         Map.of("heap", 0.7),
                                         5L,
                                         5L,
                                         12L,
                                         "ON_DUTY",
                                         List.of(report));

            assertThat(pong.sender()).isEqualTo(PEER);
            assertThat(pong.observedRabiaTerm()).isEqualTo(5L);
            assertThat(pong.observedEpochTerm()).isEqualTo(5L);
            assertThat(pong.observedEpochCounter()).isEqualTo(12L);
            assertThat(pong.lifecycleState()).isEqualTo("ON_DUTY");
            assertThat(pong.communityReports()).containsExactly(report);
        }

        @Test
        void metricsPong_backwardCompatFactory_populatesDefaults() {
            var pong = MetricsPong.metricsPong(PEER, Map.of("cpu", 0.2));

            assertThat(pong.observedRabiaTerm()).isZero();
            assertThat(pong.observedEpochTerm()).isZero();
            assertThat(pong.lifecycleState()).isEmpty();
            assertThat(pong.communityReports()).isEmpty();
        }

        @Test
        void construct_nullReports_normalizesToEmpty() {
            var pong = new MetricsPong(PEER, Map.of(), 0L, 0L, 0L, "ON_DUTY", null);

            assertThat(pong.communityReports()).isEmpty();
        }

        @Test
        void construct_nullLifecycleState_normalizesToEmpty() {
            var pong = new MetricsPong(PEER, Map.of(), 0L, 0L, 0L, null, List.of());

            assertThat(pong.lifecycleState()).isEmpty();
        }
    }

    @Nested
    class SnapshotPayloadShape {
        @Test
        void snapshotPayload_roundTripsBytes() {
            var payload = SnapshotPayload.snapshotPayload(new byte[]{10, 20, 30});

            assertThat(payload.bytes()).containsExactly(10, 20, 30);
        }

        @Test
        void construct_nullBytes_normalizesToEmpty() {
            var payload = new SnapshotPayload(null);

            assertThat(payload.bytes()).isEmpty();
        }
    }
}
