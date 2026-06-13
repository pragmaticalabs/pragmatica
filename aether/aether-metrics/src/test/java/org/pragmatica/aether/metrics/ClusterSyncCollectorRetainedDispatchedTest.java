// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.consensus.topology.TransportObservation.SelfShutdown;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// Provisioning-stickiness fix — `ClusterSyncCollector` retains the leader-broadcast `dispatchedNodes`
/// set STICKILY: term-fenced (a higher-or-equal-term ping replaces, even with an empty set; a strictly
/// lower-term ping is rejected) but NOT TTL-gated (it survives the readiness-cache freshness window so
/// a new leader can seed from it across a leaderless gap). A new leader reads it via
/// [`ClusterSyncCollector#retainedDispatchedNodes`] to avoid re-dispatching provisions the prior
/// leader already issued.
class ClusterSyncCollectorRetainedDispatchedTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId LEADER = NodeId.nodeId("leader").unwrap();
    private static final NodeId DISPATCHED_A = NodeId.nodeId("dispatched-a").unwrap();
    private static final NodeId DISPATCHED_B = NodeId.nodeId("dispatched-b").unwrap();

    private static ClusterSyncPing leaderPing(long term, Set<NodeId> dispatched) {
        return new ClusterSyncPing(LEADER, Map.of(), term, term, 0L, Set.of(), Set.of(), Map.of(), dispatched);
    }

    @Test
    void freshCollector_retainedDispatchedNodes_isEmpty() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());

        assertThat(collector.retainedDispatchedNodes()).isEmpty();
    }

    @Test
    void leaderPing_storesDispatchedSet() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());

        collector.onClusterSyncPing(leaderPing(1L, Set.of(DISPATCHED_A)));

        assertThat(collector.retainedDispatchedNodes()).containsExactly(DISPATCHED_A);
    }

    @Test
    void higherTermPing_replacesRetainedSet() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());
        collector.onClusterSyncPing(leaderPing(1L, Set.of(DISPATCHED_A)));

        collector.onClusterSyncPing(leaderPing(2L, Set.of(DISPATCHED_B)));

        assertThat(collector.retainedDispatchedNodes()).containsExactly(DISPATCHED_B);
    }

    @Test
    void higherTermPing_replacesRetainedSet_evenWhenEmpty() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());
        collector.onClusterSyncPing(leaderPing(1L, Set.of(DISPATCHED_A, DISPATCHED_B)));
        assertThat(collector.retainedDispatchedNodes()).containsExactlyInAnyOrder(DISPATCHED_A, DISPATCHED_B);

        // Empty-from-the-current-(higher-term)-leader is the truth: the leader now has nothing
        // in flight. It must REPLACE the prior non-empty retention, not be ignored.
        collector.onClusterSyncPing(leaderPing(2L, Set.of()));

        assertThat(collector.retainedDispatchedNodes()).isEmpty();
    }

    @Test
    void sameTermPing_replacesRetainedSet() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());
        collector.onClusterSyncPing(leaderPing(3L, Set.of(DISPATCHED_A)));

        collector.onClusterSyncPing(leaderPing(3L, Set.of(DISPATCHED_A, DISPATCHED_B)));

        assertThat(collector.retainedDispatchedNodes()).containsExactlyInAnyOrder(DISPATCHED_A, DISPATCHED_B);
    }

    @Test
    void lowerTermPing_isRejected_retainsHigherTermSet() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());
        collector.onClusterSyncPing(leaderPing(5L, Set.of(DISPATCHED_B)));

        // A stale lower-term ping (e.g. from a deposed leader) must NOT clobber the current retention.
        collector.onClusterSyncPing(leaderPing(2L, Set.of(DISPATCHED_A)));

        assertThat(collector.retainedDispatchedNodes()).containsExactly(DISPATCHED_B);
    }

    /// Proves the retained set is NOT TTL-gated, unlike the readiness cache. Wire the readiness-view
    /// context with a controllable clock, deliver one leader ping carrying both a readiness view and a
    /// dispatched set, then advance the clock PAST the readiness TTL window. The follower readiness
    /// view ages out (goes empty), but the retained dispatched set is still readable — it has no TTL
    /// and must survive a leaderless gap.
    @Test
    void retainedDispatched_survivesPastReadinessTtlWindow() {
        var clock = new AtomicLong(0L);
        var pingInterval = TimeSpan.timeSpan(1).seconds();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());
        // Follower: never leader, but believes LEADER is the leader, so the readiness cache stores
        // the leader's broadcast view.
        collector.setReadinessViewContext(new FollowerLeaderManager(LEADER), clock::get, pingInterval);

        var ping = new ClusterSyncPing(LEADER, Map.of(), 1L, 1L, 0L, Set.of(), Set.of(),
                                       Map.of(DISPATCHED_A, "READY"), Set.of(DISPATCHED_A, DISPATCHED_B));
        collector.onClusterSyncPing(ping);

        // Immediately after the ping: both the readiness view and the retained dispatched set are present.
        assertThat(collector.reportedStates()).isNotEmpty();
        assertThat(collector.retainedDispatchedNodes()).containsExactlyInAnyOrder(DISPATCHED_A, DISPATCHED_B);

        // Advance well past the readiness TTL (3 ping intervals). The readiness view goes stale...
        clock.set(pingInterval.nanos() * (FollowerReadinessCache.TTL_PING_INTERVALS + 1));

        assertThat(collector.reportedStates()).isEmpty();
        // ...but the retained dispatched set is NOT TTL-gated and survives.
        assertThat(collector.retainedDispatchedNodes()).containsExactlyInAnyOrder(DISPATCHED_A, DISPATCHED_B);
    }

    /// Minimal `LeaderManager` double: this node is a FOLLOWER (`isLeader()==false`) but reports a
    /// fixed `leader()` so `FollowerReadinessCache.maybeStore` accepts the leader's broadcast view.
    private record FollowerLeaderManager(NodeId leaderId) implements LeaderManager {
        @Override public Option<NodeId> leader() {
            return Option.some(leaderId);
        }

        @Override public boolean isLeader() {
            return false;
        }

        @Override public Option<Long> currentLeaderEpoch() {
            return Option.none();
        }

        @Override public void onLeaderCommitted(NodeId leader) {}
        @Override public void triggerElection() {}
        @Override public void stop() {}
        @Override public void peerJoined(PeerJoined p) {}
        @Override public void peerDisconnected(PeerDisconnected p) {}
        @Override public void peerObservedFaulty(PeerObservedFaulty p) {}
        @Override public void peerReconnected(PeerReconnected p) {}
        @Override public void selfShutdown(SelfShutdown s) {}
        @Override public void watchClusterState(ClusterStateNotification q) {}
    }
}
