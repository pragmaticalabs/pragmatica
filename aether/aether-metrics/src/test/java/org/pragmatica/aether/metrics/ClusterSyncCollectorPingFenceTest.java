// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1529: the authority-ping fence orders `(cluster incarnation, rabiaTerm)`, incarnation first. A cold
/// restart restarts the Rabia term, so once #1533 advances the incarnation the new run's leader pings
/// at a LOWER raw term than the previous run's; a raw-term high-water would make a surviving worker
/// refuse every authority ping of the new run forever. A higher incarnation resets the term high-water;
/// within one incarnation a lower term is still refused. The retained dispatched set sits behind the
/// same ordering, so a new run's leader replaces the previous run's retention.
class ClusterSyncCollectorPingFenceTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId LEADER = NodeId.nodeId("leader").unwrap();
    private static final NodeId OLD_RUN_DISPATCHED = NodeId.nodeId("old-run-dispatched").unwrap();
    private static final NodeId NEW_RUN_DISPATCHED = NodeId.nodeId("new-run-dispatched").unwrap();

    private ClusterSyncCollector collector;

    @BeforeEach
    void setUp() {
        collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());
        collector.setMetricsProducerEligibility(_ -> true);
        collector.setPingAuthority(LEADER::equals, LEADER::equals);
    }

    private static ClusterSyncPing leaderPing(long incarnation, long term, long counter, Set<NodeId> dispatched) {
        return new ClusterSyncPing(LEADER,
                                   Map.of(),
                                   term,
                                   incarnation,
                                   term,
                                   counter,
                                   Set.of(),
                                   Set.of(),
                                   Map.of(),
                                   dispatched,
                                   true,
                                   true);
    }

    @Test
    void onClusterSyncPing_acceptsANewerIncarnationAtALowerTerm() {
        collector.onClusterSyncPing(leaderPing(1L, 50L, 9L, Set.of(OLD_RUN_DISPATCHED)));
        collector.onClusterSyncPing(leaderPing(2L, 3L, 1L, Set.of(NEW_RUN_DISPATCHED)));

        assertThat(collector.observedEpoch()).isEqualTo(Epoch.epoch(2L, 3L, 1L));
        assertThat(collector.observedRabiaTerm()).isEqualTo(3L);
        assertThat(collector.retainedDispatchedNodes()).containsExactly(NEW_RUN_DISPATCHED);
    }

    @Test
    void onClusterSyncPing_refusesALowerTermWithinTheSameIncarnation() {
        collector.onClusterSyncPing(leaderPing(1L, 50L, 9L, Set.of(OLD_RUN_DISPATCHED)));
        collector.onClusterSyncPing(leaderPing(1L, 49L, 20L, Set.of(NEW_RUN_DISPATCHED)));

        assertThat(collector.observedEpoch()).isEqualTo(Epoch.epoch(1L, 50L, 9L));
        assertThat(collector.observedRabiaTerm()).isEqualTo(50L);
        assertThat(collector.retainedDispatchedNodes()).containsExactly(OLD_RUN_DISPATCHED);
    }

    @Test
    void onClusterSyncPing_refusesThePreviousRunOnceANewerIncarnationWasAccepted() {
        collector.onClusterSyncPing(leaderPing(1L, 50L, 9L, Set.of(OLD_RUN_DISPATCHED)));
        collector.onClusterSyncPing(leaderPing(2L, 3L, 1L, Set.of(NEW_RUN_DISPATCHED)));
        collector.onClusterSyncPing(leaderPing(1L, 51L, 10L, Set.of(OLD_RUN_DISPATCHED)));

        assertThat(collector.observedEpoch()).isEqualTo(Epoch.epoch(2L, 3L, 1L));
        assertThat(collector.observedRabiaTerm()).isEqualTo(3L);
        assertThat(collector.retainedDispatchedNodes()).containsExactly(NEW_RUN_DISPATCHED);
    }
}
