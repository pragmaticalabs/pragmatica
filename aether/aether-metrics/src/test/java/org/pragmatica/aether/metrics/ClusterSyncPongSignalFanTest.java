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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
                                   "READY",
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

            fan.fan(new ClusterSyncPong(OBSERVER, java.util.Map.of(), 0L, 0L, 0L, "READY", List.of(), List.of(), List.of()));

            assertThat(emitted).filteredOn(HealthSignal.SwimHint.class::isInstance)
                               .extracting(HealthSignal.SwimHint.class::cast)
                               .extracting(HealthSignal.SwimHint::nodeId, HealthSignal.SwimHint::state)
                               .containsExactly(org.assertj.core.groups.Tuple.tuple(OBSERVER, HealthHint.HEALTHY));
            assertThat(emitted).filteredOn(HealthSignal.RemoteSwimHint.class::isInstance).isEmpty();
            assertThat(emitted).filteredOn(HealthSignal.RemoteConnectivity.class::isInstance).isEmpty();
        }
    }

    @Nested
    class ReadyCandidateFanOut {
        @Test
        void fan_whenLeaderAndCandidatePresent_invokesReadyCandidateSink() {
            var leaderManager = new TestLeaderManager(true);
            var recorded = new java.util.ArrayList<java.util.Map.Entry<NodeId, NodeId>>();
            ClusterSyncPongSignalFan.ReadyCandidateSink sink =
                (sender, candidate) -> recorded.add(java.util.Map.entry(sender, candidate));
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, leaderManager, sink);

            var pong = new ClusterSyncPong(OBSERVER, java.util.Map.of(), 0L, 0L, 0L, "JOINING",
                                            List.of(), List.of(), List.of(), Option.some(PEER_A));

            fan.fan(pong);

            assertThat(recorded).containsExactly(java.util.Map.entry(OBSERVER, PEER_A));
        }

        @Test
        void fan_whenLeaderAndCandidateAbsent_doesNotInvokeReadyCandidateSink() {
            var leaderManager = new TestLeaderManager(true);
            var recorded = new java.util.ArrayList<java.util.Map.Entry<NodeId, NodeId>>();
            ClusterSyncPongSignalFan.ReadyCandidateSink sink =
                (sender, candidate) -> recorded.add(java.util.Map.entry(sender, candidate));
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, leaderManager, sink);

            var pong = new ClusterSyncPong(OBSERVER, java.util.Map.of(), 0L, 0L, 0L, "READY",
                                            List.of(), List.of(), List.of(), Option.none());

            fan.fan(pong);

            assertThat(recorded).isEmpty();
        }

        @Test
        void fan_whenNotLeaderAndCandidatePresent_doesNotInvokeReadyCandidateSink() {
            var leaderManager = new TestLeaderManager(false);
            var recorded = new java.util.ArrayList<java.util.Map.Entry<NodeId, NodeId>>();
            ClusterSyncPongSignalFan.ReadyCandidateSink sink =
                (sender, candidate) -> recorded.add(java.util.Map.entry(sender, candidate));
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, leaderManager, sink);

            var pong = new ClusterSyncPong(OBSERVER, java.util.Map.of(), 0L, 0L, 0L, "JOINING",
                                            List.of(), List.of(), List.of(), Option.some(PEER_A));

            fan.fan(pong);

            assertThat(recorded).isEmpty();
        }

        @Test
        void fan_legacyFactoryWithoutSink_doesNotThrowOnCandidatePresent() {
            // Backward-compat: factory(sink, leaderManager) without ReadyCandidateSink defaults
            // to NOOP. A candidate-present pong must not NPE or raise — it is silently dropped.
            var leaderManager = new TestLeaderManager(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink, leaderManager);

            var pong = new ClusterSyncPong(OBSERVER, java.util.Map.of(), 0L, 0L, 0L, "JOINING",
                                            List.of(), List.of(), List.of(), Option.some(PEER_A));

            fan.fan(pong); // must not throw
        }
    }

    @Nested
    class ReadinessView {
        private ClusterSyncPong pong(NodeId sender, String state, long incarnation) {
            return new ClusterSyncPong(sender, java.util.Map.of(), 0L, 0L, 0L, state,
                                       List.of(), List.of(), List.of(), Option.none(), incarnation);
        }

        private ClusterSyncPongSignalFan fan(LeaderManager leaderManager, AtomicLong clock) {
            return ClusterSyncPongSignalFan.clusterSyncPongSignalFan(recordingSink,
                                                                     leaderManager,
                                                                     ClusterSyncPongSignalFan.ReadyCandidateSink.NOOP,
                                                                     clock::get);
        }

        @Test
        void fan_pongFromReadyNode_populatesSnapshotWithReady() {
            var clock = new AtomicLong(100L);
            var f = fan(new TestLeaderManager(true), clock);

            f.fan(pong(PEER_A, "READY", 1L));

            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.READY);
        }

        @Test
        void fan_pongFromSyncingNode_populatesSnapshotWithSyncing() {
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));

            f.fan(pong(PEER_A, "SYNCING", 1L));

            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.SYNCING);
        }

        @Test
        void fan_pongFromDrainingNode_populatesSnapshotWithDraining() {
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));

            f.fan(pong(PEER_A, "DRAINING", 1L));

            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.DRAINING);
        }

        @Test
        void fan_unknownLifecycleString_parsesAsSyncing() {
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));

            f.fan(pong(PEER_A, "BOGUS", 1L));

            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.SYNCING);
        }

        @Test
        void fan_higherIncarnation_replacesEntry() {
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));

            f.fan(pong(PEER_A, "READY", 1L));
            f.fan(pong(PEER_A, "DRAINING", 2L));

            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.DRAINING);
        }

        @Test
        void fan_equalIncarnation_updatesState() {
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));

            f.fan(pong(PEER_A, "SYNCING", 1L));
            f.fan(pong(PEER_A, "READY", 1L));

            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.READY);
        }

        @Test
        void fan_lowerIncarnation_ignoredAsStale() {
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));

            f.fan(pong(PEER_A, "READY", 5L));
            f.fan(pong(PEER_A, "DRAINING", 2L));

            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.READY);
        }

        @Test
        void fan_syncingPongs_decrementCountdownThenReapWhenWarmedUp() {
            var reaped = new ArrayList<NodeId>();
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));
            f.onStuckSyncing(reaped::add);
            f.warmedUp(() -> true);

            for (int i = 0; i <= ClusterSyncPongSignalFan.SYNC_REAP_THRESHOLD; i++) {
                f.fan(pong(PEER_A, "SYNCING", 1L));
            }

            assertThat(reaped).containsExactly(PEER_A);
            assertThat(f.readinessSnapshot()).doesNotContainKey(PEER_A);
        }

        @Test
        void fan_readyPongResetsCountdown_noReapAfterPriorSyncing() {
            var reaped = new ArrayList<NodeId>();
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));
            f.onStuckSyncing(reaped::add);
            f.warmedUp(() -> true);

            for (int i = 0; i < ClusterSyncPongSignalFan.SYNC_REAP_THRESHOLD - 1; i++) {
                f.fan(pong(PEER_A, "SYNCING", 1L));
            }
            f.fan(pong(PEER_A, "READY", 1L));

            assertThat(reaped).isEmpty();
            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.READY);
        }

        @Test
        void fan_stuckSyncingButNotWarmedUp_doesNotReap() {
            var reaped = new ArrayList<NodeId>();
            var warmed = new AtomicBoolean(false);
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));
            f.onStuckSyncing(reaped::add);
            f.warmedUp(warmed::get);

            for (int i = 0; i < ClusterSyncPongSignalFan.SYNC_REAP_THRESHOLD + 5; i++) {
                f.fan(pong(PEER_A, "SYNCING", 1L));
            }

            assertThat(reaped).isEmpty();
            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.SYNCING);
        }

        @Test
        void evict_removesEntryRegardlessOfIncarnation() {
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));
            f.fan(pong(PEER_A, "READY", 9L));

            f.evict(PEER_A);

            assertThat(f.readinessSnapshot()).doesNotContainKey(PEER_A);
        }

        @Test
        void sweepStale_removesEntriesOlderThanMaxAge() {
            var clock = new AtomicLong(1_000L);
            var f = fan(new TestLeaderManager(true), clock);
            f.fan(pong(PEER_A, "READY", 1L));   // stamped at 1_000
            clock.set(5_000L);
            f.fan(pong(PEER_B, "READY", 1L));   // stamped at 5_000

            clock.set(5_500L);
            f.sweepStale(1_000L);               // cutoff = 4_500 — PEER_A removed, PEER_B kept

            assertThat(f.readinessSnapshot()).doesNotContainKey(PEER_A)
                                             .containsKey(PEER_B);
        }

        @Test
        void readinessSnapshot_reflectsMultipleNodeStates() {
            var f = fan(new TestLeaderManager(true), new AtomicLong(0L));
            f.fan(pong(PEER_A, "READY", 1L));
            f.fan(pong(PEER_B, "DRAINING", 1L));

            assertThat(f.readinessSnapshot()).containsEntry(PEER_A, NodeReportedState.READY)
                                             .containsEntry(PEER_B, NodeReportedState.DRAINING);
        }

        @Test
        void fan_whenNotLeader_doesNotPopulateReadiness() {
            var f = fan(new TestLeaderManager(false), new AtomicLong(0L));

            f.fan(pong(PEER_A, "READY", 1L));

            assertThat(f.readinessSnapshot()).isEmpty();
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
        @Override public void peerJoined(org.pragmatica.consensus.topology.TransportObservation.PeerJoined p) {}
        @Override public void peerDisconnected(org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected p) {}
        @Override public void peerObservedFaulty(org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty p) {}
        @Override public void peerReconnected(org.pragmatica.consensus.topology.TransportObservation.PeerReconnected p) {}
        @Override public void selfShutdown(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown s) {}
        @Override public void watchClusterState(org.pragmatica.consensus.topology.ClusterStateNotification q) {}
    }
}
