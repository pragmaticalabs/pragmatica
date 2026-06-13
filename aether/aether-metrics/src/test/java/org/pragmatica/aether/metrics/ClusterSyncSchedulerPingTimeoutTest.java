// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies that `ClusterSyncScheduler` emits `HealthSignal.PingTimeout` on its
/// per-target miss counter after K consecutive ticks without a pong, and that
/// `onPongReceived` resets the counter so the signal stops firing — per spec
/// §8.1 (leader-side PingTimeout emission source).
///
/// Recipients are sourced from `connectedPeers()` (broadcast model), so each test uses a
/// network whose connected set fixes the broadcast/miss-tracking recipients.
class ClusterSyncSchedulerPingTimeoutTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    @Test
    void kMissedPings_emitsPingTimeoutSignalWithMissedCount() {
        var captured = new CopyOnWriteArrayList<HealthSignal>();
        HealthSignalSink sink = captured::add;
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new ConnectedPeersNetwork(Set.of(PEER_A)),
                                                           new NoopClusterSyncCollector(),
                                                           TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           sink,
                                                           3,
                                                           () -> Epoch.epoch(7L, 0L));
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        scheduler.sendPingsNow();
        scheduler.sendPingsNow();
        scheduler.sendPingsNow();

        var pingTimeouts = captured.stream()
                                    .filter(s -> s instanceof HealthSignal.PingTimeout)
                                    .map(s -> (HealthSignal.PingTimeout) s)
                                    .toList();
        assertThat(pingTimeouts).hasSize(1);
        assertThat(pingTimeouts.getFirst().nodeId()).isEqualTo(PEER_A);
        assertThat(pingTimeouts.getFirst().missedIntervals()).isEqualTo(3);
        assertThat(pingTimeouts.getFirst().observedAt()).isEqualTo(Epoch.epoch(7L, 0L));
    }

    @Test
    void missedPingsBelowThreshold_noSignalEmitted() {
        var captured = new CopyOnWriteArrayList<HealthSignal>();
        HealthSignalSink sink = captured::add;
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new ConnectedPeersNetwork(Set.of(PEER_A)),
                                                           new NoopClusterSyncCollector(),
                                                           TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           sink,
                                                           3,
                                                           () -> Epoch.epoch(7L, 0L));
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        scheduler.sendPingsNow();
        scheduler.sendPingsNow();

        assertThat(captured).isEmpty();
    }

    @Test
    void onPongReceived_resetsMissedCounter() {
        var captured = new CopyOnWriteArrayList<HealthSignal>();
        HealthSignalSink sink = captured::add;
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new ConnectedPeersNetwork(Set.of(PEER_A)),
                                                           new NoopClusterSyncCollector(),
                                                           TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           sink,
                                                           3,
                                                           () -> Epoch.epoch(7L, 0L));
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        scheduler.sendPingsNow();
        scheduler.sendPingsNow();
        scheduler.onPongReceived(PEER_A);
        scheduler.sendPingsNow();
        scheduler.sendPingsNow();

        assertThat(captured).isEmpty();
    }

    @Test
    void kMissedPings_perTargetIsIndependent() {
        var captured = new CopyOnWriteArrayList<HealthSignal>();
        HealthSignalSink sink = captured::add;
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new ConnectedPeersNetwork(Set.of(PEER_A, PEER_B)),
                                                           new NoopClusterSyncCollector(),
                                                           TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           sink,
                                                           3,
                                                           () -> Epoch.epoch(7L, 0L));
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        scheduler.sendPingsNow();
        scheduler.onPongReceived(PEER_B);
        scheduler.sendPingsNow();
        scheduler.onPongReceived(PEER_B);
        scheduler.sendPingsNow();

        var peerATimeouts = captured.stream()
                                    .filter(s -> s instanceof HealthSignal.PingTimeout)
                                    .map(s -> (HealthSignal.PingTimeout) s)
                                    .filter(t -> t.nodeId().equals(PEER_A))
                                    .toList();
        assertThat(peerATimeouts).hasSize(1);
        var peerBTimeouts = captured.stream()
                                    .filter(s -> s instanceof HealthSignal.PingTimeout)
                                    .map(s -> (HealthSignal.PingTimeout) s)
                                    .filter(t -> t.nodeId().equals(PEER_B))
                                    .toList();
        assertThat(peerBTimeouts).isEmpty();
    }

    /// `NoopNetwork` variant with a fixed `connectedPeers()` set — the broadcast/miss-tracking
    /// recipient source in the broadcast ping model.
    private static final class ConnectedPeersNetwork extends NoopNetwork {
        private final Set<NodeId> connected;

        ConnectedPeersNetwork(Set<NodeId> connected) {
            this.connected = Set.copyOf(connected);
        }

        @Override public Set<NodeId> connectedPeers() { return connected; }
    }
}
