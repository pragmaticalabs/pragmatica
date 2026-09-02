// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies that `ClusterSyncScheduler` reports a peer to SWIM as transport-unreachable once its
/// per-target miss counter reaches K consecutive ticks without a pong, and that `onPongReceived`
/// resets the counter so the report stops firing — per spec §8.1 (leader-side ping-timeout
/// detection).
///
/// Every test wires a `SwimAwareCollector` holding NO peers alive, so `emitPingTimeoutIfExceeded`
/// clears its SWIM-HEALTHY early-skip and the `reportUnreachable` call becomes observable.
///
/// Recipients are sourced from `connectedPeers()` (broadcast model), so each test uses a
/// network whose connected set fixes the broadcast/miss-tracking recipients.
class ClusterSyncSchedulerPingTimeoutTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    @Test
    void kMissedPings_reportsUnreachableForTarget() {
        // Two facts the old assertion pinned are not carried by `reportUnreachable(NodeId)`:
        // the missed count (3) and the observation stamp (`Epoch.epoch(7, 0)`). The count is
        // still pinned one level down by `ClusterSyncFsmTest.CounterBehaviour` via
        // `counterForPeer`; the epoch stamp has no live carrier anywhere.
        var reported = new CopyOnWriteArrayList<NodeId>();
        var collector = new SwimAwareCollector(Set.of());
        collector.setUnreachableReporter(reported::add);
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new ConnectedPeersNetwork(Set.of(PEER_A)),
                                                           collector,
                                                           TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           3,
                                                           () -> Epoch.epoch(7L, 0L));
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        scheduler.sendPingsNow();
        scheduler.sendPingsNow();
        scheduler.sendPingsNow();

        assertThat(reported).containsExactly(PEER_A);
    }

    @Test
    void missedPingsBelowThreshold_noUnreachableReported() {
        var reported = new CopyOnWriteArrayList<NodeId>();
        var collector = new SwimAwareCollector(Set.of());
        collector.setUnreachableReporter(reported::add);
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new ConnectedPeersNetwork(Set.of(PEER_A)),
                                                           collector,
                                                           TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           3,
                                                           () -> Epoch.epoch(7L, 0L));
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        scheduler.sendPingsNow();
        scheduler.sendPingsNow();

        assertThat(reported).isEmpty();
    }

    @Test
    void onPongReceived_resetsMissedCounter() {
        var reported = new CopyOnWriteArrayList<NodeId>();
        var collector = new SwimAwareCollector(Set.of());
        collector.setUnreachableReporter(reported::add);
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new ConnectedPeersNetwork(Set.of(PEER_A)),
                                                           collector,
                                                           TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           3,
                                                           () -> Epoch.epoch(7L, 0L));
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        scheduler.sendPingsNow();
        scheduler.sendPingsNow();
        scheduler.onPongReceived(PEER_A);
        scheduler.sendPingsNow();
        scheduler.sendPingsNow();

        assertThat(reported).isEmpty();
    }

    @Test
    void kMissedPings_perTargetIsIndependent() {
        var reported = new CopyOnWriteArrayList<NodeId>();
        var collector = new SwimAwareCollector(Set.of());
        collector.setUnreachableReporter(reported::add);
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new ConnectedPeersNetwork(Set.of(PEER_A, PEER_B)),
                                                           collector,
                                                           TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           3,
                                                           () -> Epoch.epoch(7L, 0L));
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        scheduler.sendPingsNow();
        scheduler.onPongReceived(PEER_B);
        scheduler.sendPingsNow();
        scheduler.onPongReceived(PEER_B);
        scheduler.sendPingsNow();

        assertThat(reported).as("PEER_A crossed the threshold; PEER_B's pongs kept resetting it")
                            .containsExactly(PEER_A);
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
