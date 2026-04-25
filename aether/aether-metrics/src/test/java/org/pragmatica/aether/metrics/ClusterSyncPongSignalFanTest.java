// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.ConnectivityReport;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterSyncPongSignalFanTest {
    private static final NodeId OBSERVER = NodeId.nodeId("observer").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    private final List<HealthSignal> emitted = new ArrayList<>();
    private final HealthSignalSink recordingSink = emitted::add;

    private ClusterSyncPong pongWithObservations() {
        return new ClusterSyncPong(OBSERVER,
                                   java.util.Map.of(),
                                   3L,
                                   3L,
                                   17L,
                                   "ON_DUTY",
                                   List.of(),
                                   List.of(new PeerHealthObservation(PEER_A, HealthHintWire.FAULTY, 3L, 17L, 0L),
                                           new PeerHealthObservation(PEER_B, HealthHintWire.SUSPECTED, 3L, 18L, 0L)),
                                   List.of(new PeerConnectivityObservation(PEER_A,
                                                                           ConnectivityState.DISCONNECTED,
                                                                           3L,
                                                                           17L,
                                                                           0L)));
    }

    @Nested
    class FollowerGate {
        @Test
        void fan_whenNotLeader_doesNothing() {
            var notLeader = new AtomicBoolean(false);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, notLeader::get);

            fan.fan(pongWithObservations());

            assertThat(emitted).isEmpty();
        }
    }

    @Nested
    class LeaderFanOut {
        @Test
        void fan_whenLeader_emitsRemoteSwimHintPerObservation() {
            var isLeader = new AtomicBoolean(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, isLeader::get);

            fan.fan(pongWithObservations());

            assertThat(emitted).filteredOn(HealthSignal.RemoteSwimHint.class::isInstance)
                             .extracting(HealthSignal.RemoteSwimHint.class::cast)
                             .extracting(HealthSignal.RemoteSwimHint::observer,
                                         HealthSignal.RemoteSwimHint::peer,
                                         HealthSignal.RemoteSwimHint::hint,
                                         HealthSignal.RemoteSwimHint::observedAtEpoch)
                             .containsExactly(org.assertj.core.groups.Tuple.tuple(OBSERVER, PEER_A, HealthHint.FAULTY, Epoch.epoch(3L, 17L)),
                                              org.assertj.core.groups.Tuple.tuple(OBSERVER, PEER_B, HealthHint.SUSPECTED, Epoch.epoch(3L, 18L)));
        }

        @Test
        void fan_whenLeader_emitsRemoteConnectivityPerObservation() {
            var isLeader = new AtomicBoolean(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, isLeader::get);

            fan.fan(pongWithObservations());

            assertThat(emitted).filteredOn(HealthSignal.RemoteConnectivity.class::isInstance)
                             .extracting(HealthSignal.RemoteConnectivity.class::cast)
                             .extracting(HealthSignal.RemoteConnectivity::observer,
                                         HealthSignal.RemoteConnectivity::peer,
                                         HealthSignal.RemoteConnectivity::state,
                                         HealthSignal.RemoteConnectivity::observedAtEpoch)
                             .containsExactly(org.assertj.core.groups.Tuple.tuple(OBSERVER,
                                                                                  PEER_A,
                                                                                  ConnectivityReport.DISCONNECTED,
                                                                                  Epoch.epoch(3L, 17L)));
        }

        @Test
        void fan_emptyPong_producesNoSignals() {
            var isLeader = new AtomicBoolean(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, isLeader::get);

            fan.fan(new ClusterSyncPong(OBSERVER, java.util.Map.of(), 0L, 0L, 0L, "ON_DUTY", List.of(), List.of(), List.of()));

            assertThat(emitted).isEmpty();
        }
    }
}
