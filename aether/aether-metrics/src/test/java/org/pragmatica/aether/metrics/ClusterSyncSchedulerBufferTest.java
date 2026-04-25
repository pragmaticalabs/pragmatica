// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/// Commit 2: the `ClusterSyncScheduler` is the `PeerObservationBuffer` owner.
/// Followers push observations; the buffer must:
///  - accumulate entries (push + drain works as expected)
///  - drain atomically (drain returns all and leaves buffer empty)
///  - respect a bounded cap derived from topology size (oldest evicted)
///  - clear on stop
class ClusterSyncSchedulerBufferTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    @Test
    void pushHealth_andDrain_returnsAllEntries_clearsBuffer() {
        var scheduler = newScheduler();

        scheduler.pushHealth(new PeerHealthObservation(PEER_A, HealthHintWire.FAULTY, 7L, 3L, 0L));
        scheduler.pushHealth(new PeerHealthObservation(PEER_B, HealthHintWire.SUSPECTED, 7L, 4L, 0L));

        var first = scheduler.drainHealth();
        assertThat(first).hasSize(2);
        assertThat(first.get(0).peerId()).isEqualTo(PEER_A);
        assertThat(first.get(1).peerId()).isEqualTo(PEER_B);

        assertThat(scheduler.drainHealth()).isEmpty();
    }

    @Test
    void pushConnectivity_andDrain_returnsAllEntries_clearsBuffer() {
        var scheduler = newScheduler();

        scheduler.pushConnectivity(new PeerConnectivityObservation(PEER_A, ConnectivityState.DISCONNECTED, 7L, 3L, 0L));

        var drained = scheduler.drainConnectivity();
        assertThat(drained).singleElement()
                           .satisfies(obs -> {
                               assertThat(obs.peerId()).isEqualTo(PEER_A);
                               assertThat(obs.state()).isEqualTo(ConnectivityState.DISCONNECTED);
                           });
        assertThat(scheduler.drainConnectivity()).isEmpty();
    }

    @Test
    void healthBuffer_enforcesCap_dropsOldest() {
        var scheduler = newScheduler();
        // Topology has SELF + 1 peer (PEER_A); cap = (topologySize - 1) * 4 = 4,
        // but MIN_BUFFER_CAP = 8, so effective cap is 8.
        scheduler.onTopologyChange(new TopologyChangeNotification.NodeAdded(PEER_A, List.of(SELF, PEER_A)));

        for (int i = 0; i < 12; i++) {
            scheduler.pushHealth(new PeerHealthObservation(PEER_A, HealthHintWire.FAULTY, 7L, i, 0L));
        }

        var drained = scheduler.drainHealth();
        assertThat(drained).hasSizeLessThanOrEqualTo(8);
        // Oldest entries dropped, most-recent retained.
        assertThat(drained.getLast().observedEpochCounter()).isEqualTo(11L);
    }

    @Test
    void stop_clearsBuffers() {
        var scheduler = newScheduler();

        scheduler.pushHealth(new PeerHealthObservation(PEER_A, HealthHintWire.FAULTY, 7L, 3L, 0L));
        scheduler.pushConnectivity(new PeerConnectivityObservation(PEER_A, ConnectivityState.DISCONNECTED, 7L, 3L, 0L));

        scheduler.stop();

        assertThat(scheduler.drainHealth()).isEmpty();
        assertThat(scheduler.drainConnectivity()).isEmpty();
    }

    private static ClusterSyncScheduler newScheduler() {
        return ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                         new NoopNetwork(),
                                                         new NoopClusterSyncCollector(),
                                                         TimeSpan.timeSpan(1).seconds(),
                                                         () -> 7L,
                                                         Option::none,
                                                         _ -> new byte[0]);
    }
}
