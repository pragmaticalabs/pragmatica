// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.consensus.NodeId;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// #588 (round 2) — the leader's ping is the SOLE feed of a FOLLOWER's pong roster, so the follower
/// must RETAIN that roster to the ping's key set rather than merge into it.
///
/// Only the leader dispatches pings (`ClusterSyncState.handlePingTick` ignores the tick on a
/// non-leader) and a pong is addressed to the pinger alone (`ClusterSyncCollector.onClusterSyncPing`
/// ends with `network.send(ping.sender(), pong)`), so nothing but the leader's ping writes a
/// follower's `remoteMetrics` — and `removeNode` was its only eviction. Merging made every removal
/// ORDER-DEPENDENT: each node projects the death on its OWN membership verdict, so a follower that
/// pruned a departed worker at t_F took it straight back from a leader ping issued before the
/// leader's own verdict at t_L > t_F, and no later ping ever evicted it again. Measured in the
/// round-1 author's own green log: followers reached terminal DEAD 650-700ms AHEAD of the leader
/// against a 1s ping interval, which is exactly the malignant window.
///
/// Retaining converges the follower on the first ping after t_L whatever the order was — the
/// follower's own prune is no longer load-bearing.
class ClusterSyncCollectorPingRosterRetentionTest {
    private static final NodeId SELF = NodeId.nodeId("follower-self").unwrap();
    private static final NodeId LEADER = NodeId.nodeId("leader").unwrap();
    private static final NodeId WORKER = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId PEER = NodeId.nodeId("peer-2").unwrap();

    private static ClusterSyncPing leaderPing(long term, NodeId... roster) {
        return new ClusterSyncPing(LEADER, metricsFor(roster), term, term, 0L, Set.of(), Set.of(), Map.of(), Set.of());
    }

    private static Map<NodeId, Map<String, Double>> metricsFor(NodeId... roster) {
        return Arrays.stream(roster).collect(Collectors.toMap(id -> id, _ -> Map.of("cpu", 0.5)));
    }

    /// THE PIN. A worker the follower already pruned is re-installed by an in-flight ping from a
    /// leader that has not yet projected the death — unavoidable, the leader still believes it
    /// alive — but the FIRST ping carrying the leader's own post-prune roster must evict it again.
    /// Before the retain that last assertion failed: the ghost outlived every subsequent ping.
    @Test
    void pingWithoutTheWorker_evictsAWorkerAnEarlierPingInstalled() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());

        collector.onClusterSyncPing(leaderPing(1L, LEADER, WORKER, PEER));
        assertThat(collector.allMetrics()).as("positive control: the ping installs the worker").containsKey(WORKER);

        // t_F — this follower's own membership verdict prunes the departed worker first.
        collector.removeNode(WORKER);
        assertThat(collector.allMetrics()).doesNotContainKey(WORKER);

        // t_F < ping < t_L — the leader has not projected the death yet and pings the worker back.
        collector.onClusterSyncPing(leaderPing(1L, LEADER, WORKER, PEER));
        assertThat(collector.allMetrics()).as("an in-flight ping from a leader that still holds the worker re-installs it")
                                          .containsKey(WORKER);

        // t_L — the leader's own verdict landed; every later ping carries the smaller roster.
        collector.onClusterSyncPing(leaderPing(1L, LEADER, PEER));

        assertThat(collector.allMetrics()).as("#588: a ping that no longer carries the worker must evict the follower's ghost")
                                          .doesNotContainKey(WORKER);
        assertThat(collector.allMetrics()).as("and must not disturb the peers the ping still carries")
                                          .containsKeys(LEADER, PEER);
    }

    /// The retain must never be a roster wipe: everything the ping carries survives it, and the
    /// follower's own entry — which `storeRemoteMetrics` never writes and `allMetrics` always adds —
    /// is unaffected whether or not the leader lists it.
    @Test
    void retention_keepsEveryNodeThePingCarries_andSelf() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());

        collector.onClusterSyncPing(leaderPing(1L, LEADER, WORKER, PEER, SELF));

        assertThat(collector.allMetrics()).containsKeys(LEADER, WORKER, PEER, SELF);

        collector.onClusterSyncPing(leaderPing(1L, LEADER, WORKER, PEER));

        assertThat(collector.allMetrics()).as("self survives a ping that does not list it")
                                          .containsKeys(LEADER, WORKER, PEER, SELF);
    }

    /// The retain sits BEHIND the term fence, so a stale-term ping — which `acceptPingFencing`
    /// rejects before any metrics work — cannot evict anything. Without that ordering a deposed
    /// leader's late ping would empty a live follower's roster.
    @Test
    void staleTermPing_isRejectedBeforeTheRetain_andEvictsNothing() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());
        collector.onClusterSyncPing(leaderPing(5L, LEADER, WORKER, PEER));
        assertThat(collector.allMetrics()).containsKeys(LEADER, WORKER, PEER);

        collector.onClusterSyncPing(leaderPing(4L, LEADER));

        assertThat(collector.allMetrics()).as("a fenced-out ping is not evidence about the roster")
                                          .containsKeys(LEADER, WORKER, PEER);
    }
}
