// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
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

    private static ClusterSyncPong readyPong(NodeId sender) {
        return new ClusterSyncPong(sender, java.util.Map.of(), 0L, 0L, 0L, "READY",
                                   List.of(), List.of(), List.of(), Option.none(), 1L);
    }

    @Nested
    class FollowerGate {
        @Test
        void fan_leaderTransition_reflectedWithoutDispatchingState() {
            // Leadership is re-read on every `fan()` call, not captured at construction: a demotion
            // between calls stops the readiness view from accreting further peers. PEER_B must be a
            // SECOND peer — the PEER_A entry recorded while leader is not cleared on demotion, so
            // reusing PEER_A would assert nothing about when leadership was read.
            var leaderManager = new TestLeaderManager(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(leaderManager);

            fan.fan(readyPong(PEER_A));
            assertThat(fan.readinessSnapshot()).containsKey(PEER_A);

            leaderManager.setLeader(false);
            fan.fan(readyPong(PEER_B));

            assertThat(fan.readinessSnapshot()).doesNotContainKey(PEER_B);
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
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(leaderManager, sink);

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
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(leaderManager, sink);

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
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(leaderManager, sink);

            var pong = new ClusterSyncPong(OBSERVER, java.util.Map.of(), 0L, 0L, 0L, "JOINING",
                                            List.of(), List.of(), List.of(), Option.some(PEER_A));

            fan.fan(pong);

            assertThat(recorded).isEmpty();
        }

        @Test
        void fan_legacyFactoryWithoutSink_doesNotThrowOnCandidatePresent() {
            // Backward-compat: factory(leaderManager) without ReadyCandidateSink defaults
            // to NOOP. A candidate-present pong must not NPE or raise — it is silently dropped.
            var leaderManager = new TestLeaderManager(true);
            var fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(leaderManager);

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
            return ClusterSyncPongSignalFan.clusterSyncPongSignalFan(leaderManager,
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
