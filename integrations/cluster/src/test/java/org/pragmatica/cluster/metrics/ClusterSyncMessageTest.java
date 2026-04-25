// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.SnapshotPayload;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterSyncMessageTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER = NodeId.nodeId("peer").unwrap();

    @Nested
    class PingConstruction {
        @Test
        void clusterSyncPing_fullFields_populatesRecord() {
            var payload = SnapshotPayload.snapshotPayload(new byte[]{1, 2, 3});
            var ping = new ClusterSyncPing(SELF,
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
        void clusterSyncPing_backwardCompatFactory_populatesZeroEpoch() {
            var ping = ClusterSyncPing.clusterSyncPing(SELF, Map.of(SELF, Map.of("cpu", 0.5)));

            assertThat(ping.rabiaTerm()).isZero();
            assertThat(ping.epochTerm()).isZero();
            assertThat(ping.epochCounter()).isZero();
            assertThat(ping.snapshot()).isEqualTo(Option.none());
        }

        @Test
        void construct_nullSnapshot_normalizesToNone() {
            var ping = new ClusterSyncPing(SELF, Map.of(), 0L, 0L, 0L, null);

            assertThat(ping.snapshot()).isEqualTo(Option.none());
        }
    }

    @Nested
    class PongConstruction {
        @Test
        void clusterSyncPong_fullFields_populatesRecord() {
            var governor = NodeId.nodeId("gov-1").unwrap();
            var report = CommunityReport.communityReport("pool-a", 1L, 1L, 2L, governor, 3, 3, 0, 0, Set.of("p"), 10L);

            var pong = new ClusterSyncPong(PEER,
                                         Map.of("heap", 0.7),
                                         5L,
                                         5L,
                                         12L,
                                         "ON_DUTY",
                                         List.of(report),
                                         List.of(),
                                         List.of());

            assertThat(pong.sender()).isEqualTo(PEER);
            assertThat(pong.observedRabiaTerm()).isEqualTo(5L);
            assertThat(pong.observedEpochTerm()).isEqualTo(5L);
            assertThat(pong.observedEpochCounter()).isEqualTo(12L);
            assertThat(pong.lifecycleState()).isEqualTo("ON_DUTY");
            assertThat(pong.communityReports()).containsExactly(report);
            assertThat(pong.peerHealth()).isEmpty();
            assertThat(pong.peerConnectivity()).isEmpty();
        }

        @Test
        void clusterSyncPong_backwardCompatFactory_populatesDefaults() {
            var pong = ClusterSyncPong.clusterSyncPong(PEER, Map.of("cpu", 0.2));

            assertThat(pong.observedRabiaTerm()).isZero();
            assertThat(pong.observedEpochTerm()).isZero();
            assertThat(pong.lifecycleState()).isEmpty();
            assertThat(pong.communityReports()).isEmpty();
            assertThat(pong.peerHealth()).isEmpty();
            assertThat(pong.peerConnectivity()).isEmpty();
        }

        @Test
        void construct_nullReports_normalizesToEmpty() {
            var pong = new ClusterSyncPong(PEER, Map.of(), 0L, 0L, 0L, "ON_DUTY", null, List.of(), List.of());

            assertThat(pong.communityReports()).isEmpty();
        }

        @Test
        void construct_nullLifecycleState_normalizesToEmpty() {
            var pong = new ClusterSyncPong(PEER, Map.of(), 0L, 0L, 0L, null, List.of(), List.of(), List.of());

            assertThat(pong.lifecycleState()).isEmpty();
        }

        @Test
        void construct_nullPeerObservations_normalizeToEmpty() {
            var pong = new ClusterSyncPong(PEER, Map.of(), 0L, 0L, 0L, "ON_DUTY", List.of(), null, null);

            assertThat(pong.peerHealth()).isEmpty();
            assertThat(pong.peerConnectivity()).isEmpty();
        }

        @Test
        void clusterSyncPong_carriesPeerObservations_roundTripsFields() {
            var peerA = NodeId.nodeId("peer-a").unwrap();
            var peerB = NodeId.nodeId("peer-b").unwrap();
            var healthObs = new PeerHealthObservation(peerA,
                                                      HealthHintWire.SUSPECTED,
                                                      7L,
                                                      42L,
                                                      0L);
            var connObs = new PeerConnectivityObservation(peerB,
                                                          ConnectivityState.DISCONNECTED,
                                                          7L,
                                                          43L,
                                                          0L);

            var pong = new ClusterSyncPong(PEER,
                                           Map.of(),
                                           7L,
                                           7L,
                                           44L,
                                           "ON_DUTY",
                                           List.of(),
                                           List.of(healthObs),
                                           List.of(connObs));

            assertThat(pong.peerHealth()).containsExactly(healthObs);
            assertThat(pong.peerConnectivity()).containsExactly(connObs);
            assertThat(pong.peerHealth().getFirst().peerId()).isEqualTo(peerA);
            assertThat(pong.peerHealth().getFirst().hint()).isEqualTo(HealthHintWire.SUSPECTED);
            assertThat(pong.peerConnectivity().getFirst().peerId()).isEqualTo(peerB);
            assertThat(pong.peerConnectivity().getFirst().state()).isEqualTo(ConnectivityState.DISCONNECTED);
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
