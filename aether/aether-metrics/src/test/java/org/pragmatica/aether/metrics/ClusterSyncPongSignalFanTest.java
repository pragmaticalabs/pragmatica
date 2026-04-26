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
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;

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
            var leaderManager = new TestLeaderManager(false);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, leaderManager);

            fan.fan(pongWithObservations());

            assertThat(emitted).isEmpty();
        }

        @Test
        void fan_leaderTransition_reflectedWithoutDispatchingState() {
            var leaderManager = new TestLeaderManager(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, leaderManager);

            fan.fan(pongWithObservations());
            assertThat(emitted).isNotEmpty();
            emitted.clear();

            // Flip SSOT — fan must reflect the change immediately on next call.
            leaderManager.setLeader(false);
            fan.fan(pongWithObservations());
            assertThat(emitted).isEmpty();
        }
    }

    @Nested
    class LeaderFanOut {
        @Test
        void fan_whenLeader_emitsRemoteSwimHintPerObservation() {
            var leaderManager = new TestLeaderManager(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, leaderManager);

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
            var leaderManager = new TestLeaderManager(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, leaderManager);

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
        void fan_emptyPong_emitsOnlySenderHealthyHint() {
            var leaderManager = new TestLeaderManager(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, leaderManager);

            fan.fan(new ClusterSyncPong(OBSERVER, java.util.Map.of(), 0L, 0L, 0L, "ON_DUTY", List.of(), List.of(), List.of()));

            assertThat(emitted).filteredOn(HealthSignal.SwimHint.class::isInstance)
                               .extracting(HealthSignal.SwimHint.class::cast)
                               .extracting(HealthSignal.SwimHint::nodeId, HealthSignal.SwimHint::state)
                               .containsExactly(org.assertj.core.groups.Tuple.tuple(OBSERVER, HealthHint.HEALTHY));
            assertThat(emitted).filteredOn(HealthSignal.RemoteSwimHint.class::isInstance).isEmpty();
            assertThat(emitted).filteredOn(HealthSignal.RemoteConnectivity.class::isInstance).isEmpty();
        }
    }

    /// Controllable LeaderManager stub for SSOT testing.
    static final class TestLeaderManager implements LeaderManager {
        private volatile boolean leader;

        TestLeaderManager(boolean initial) {
            this.leader = initial;
        }

        void setLeader(boolean value) {
            this.leader = value;
        }

        @Override public Option<NodeId> leader() {
            return leader ? Option.some(OBSERVER) : Option.none();
        }

        @Override public boolean isLeader() {
            return leader;
        }

        @Override public Option<Long> currentLeaderEpoch() {
            return Option.none();
        }

        @Override public void onLeaderCommitted(NodeId leader) {}
        @Override public void triggerElection() {}
        @Override public void stop() {}
        @Override public void nodeAdded(org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded n) {}
        @Override public void nodeRemoved(org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved n) {}
        @Override public void nodeDown(org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown n) {}
        @Override public void watchQuorumState(org.pragmatica.consensus.topology.QuorumStateNotification q) {}
    }
}
