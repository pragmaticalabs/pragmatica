// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.consensus.NodeId;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies `ClusterSyncCollector.emitPeriodicConnectivity` semantics required by
/// Step 1 of the topology-observation refactor (see
/// `aether/docs/specs/reachability-aggregator-spec.md` "Periodic Observation Mode"):
///  - self excluded (N-1 observations for N-peer topology)
///  - peers in `connected` get `ConnectivityState.CONNECTED`
///  - peers in `topology` but absent from `connected` get `ConnectivityState.DISCONNECTED`
///  - `producedAtMs` equals the provided `nowMs`
class ClusterSyncCollectorPeriodicEmissionTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();
    private static final NodeId PEER_C = NodeId.nodeId("peer-c").unwrap();
    private static final NodeId PEER_D = NodeId.nodeId("peer-d").unwrap();
    private static final NodeId PEER_E = NodeId.nodeId("peer-e").unwrap();

    @Test
    void emitPeriodicConnectivity_selfIsAPattern_yieldsFourObservationsWithCorrectStates() {
        // Per Step 1 plan: topology=[A,B,C,D,E], connected=[A,B], self=A, nowMs=1000.
        // Expected: 4 observations (B,C,D,E — self A excluded). B has CONNECTED;
        // C, D, E have DISCONNECTED. All observations have producedAtMs == 1000.
        var store = PeerObservationStore.peerObservationStore();
        var collector = newCollectorWithBuffer(store);
        var topology = Set.of(PEER_A, PEER_B, PEER_C, PEER_D, PEER_E);
        var connected = Set.of(PEER_A, PEER_B);

        collector.emitPeriodicConnectivity(topology, connected, PEER_A, 1000L);

        var drained = store.drainConnectivity();
        assertThat(drained).hasSize(4);
        assertThat(drained).extracting("peerId")
                            .containsExactlyInAnyOrder(PEER_B, PEER_C, PEER_D, PEER_E);
        var stateByPeer = drained.stream()
                                 .collect(java.util.stream.Collectors.toMap(o -> o.peerId(), o -> o.state()));
        assertThat(stateByPeer.get(PEER_B)).isEqualTo(ConnectivityState.CONNECTED);
        assertThat(stateByPeer.get(PEER_C)).isEqualTo(ConnectivityState.DISCONNECTED);
        assertThat(stateByPeer.get(PEER_D)).isEqualTo(ConnectivityState.DISCONNECTED);
        assertThat(stateByPeer.get(PEER_E)).isEqualTo(ConnectivityState.DISCONNECTED);
        assertThat(drained).allSatisfy(obs -> assertThat(obs.producedAtMs()).isEqualTo(1000L));
    }

    @Test
    void emitPeriodicConnectivity_emitsOneObservationPerPeerExcludingSelf() {
        var store = PeerObservationStore.peerObservationStore();
        var collector = newCollectorWithBuffer(store);
        var topology = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D, PEER_E);
        var connected = Set.of(PEER_A, PEER_B);

        collector.emitPeriodicConnectivity(topology, connected, SELF, 1000L);

        var drained = store.drainConnectivity();
        // topology has SELF + 5 peers; excluding SELF = 5 expected observations.
        assertThat(drained).hasSize(5);
        assertThat(drained).extracting("peerId")
                            .containsExactlyInAnyOrder(PEER_A, PEER_B, PEER_C, PEER_D, PEER_E);
    }

    @Test
    void emitPeriodicConnectivity_fivePeerTopologyProducesFourObservations() {
        // 5-node cluster (SELF + 4 peers). Each periodic invocation should yield
        // exactly N-1 = 4 observations.
        var store = PeerObservationStore.peerObservationStore();
        var collector = newCollectorWithBuffer(store);
        var topology = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        var connected = Set.of(PEER_A, PEER_B);

        collector.emitPeriodicConnectivity(topology, connected, SELF, 1000L);

        var drained = store.drainConnectivity();
        assertThat(drained).hasSize(4);
        assertThat(drained).extracting("peerId")
                            .containsExactlyInAnyOrder(PEER_A, PEER_B, PEER_C, PEER_D);
    }

    @Test
    void emitPeriodicConnectivity_marksConnectedAsCONNECTED_disconnectedAsDISCONNECTED() {
        var store = PeerObservationStore.peerObservationStore();
        var collector = newCollectorWithBuffer(store);
        var topology = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        var connected = Set.of(PEER_A, PEER_B);

        collector.emitPeriodicConnectivity(topology, connected, SELF, 1000L);

        var drained = store.drainConnectivity();
        var stateByPeer = drained.stream()
                                 .collect(java.util.stream.Collectors.toMap(o -> o.peerId(), o -> o.state()));
        assertThat(stateByPeer.get(PEER_A)).isEqualTo(ConnectivityState.CONNECTED);
        assertThat(stateByPeer.get(PEER_B)).isEqualTo(ConnectivityState.CONNECTED);
        assertThat(stateByPeer.get(PEER_C)).isEqualTo(ConnectivityState.DISCONNECTED);
        assertThat(stateByPeer.get(PEER_D)).isEqualTo(ConnectivityState.DISCONNECTED);
    }

    @Test
    void emitPeriodicConnectivity_producedAtMsMatchesProvidedNowMs() {
        var store = PeerObservationStore.peerObservationStore();
        var collector = newCollectorWithBuffer(store);
        var topology = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        var connected = Set.<NodeId>of();

        collector.emitPeriodicConnectivity(topology, connected, SELF, 1000L);

        var drained = store.drainConnectivity();
        assertThat(drained).allSatisfy(observation ->
            assertThat(observation.producedAtMs()).isEqualTo(1000L)
        );
    }

    @Test
    void emitPeriodicConnectivity_allDisconnectedWhenConnectedEmpty() {
        var store = PeerObservationStore.peerObservationStore();
        var collector = newCollectorWithBuffer(store);
        var topology = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);

        collector.emitPeriodicConnectivity(topology, Set.of(), SELF, 1000L);

        var drained = store.drainConnectivity();
        assertThat(drained).hasSize(4);
        assertThat(drained).allSatisfy(observation ->
            assertThat(observation.state()).isEqualTo(ConnectivityState.DISCONNECTED)
        );
    }

    @Test
    void emitPeriodicConnectivity_selfExcludedEvenIfPresentInConnected() {
        // Defensive: if `connected` somehow includes `self`, the periodic emission
        // must not produce a self-observation (single-writer self-fold is owned by
        // the leader's ReachabilityAggregator.foldSelfObservations).
        var store = PeerObservationStore.peerObservationStore();
        var collector = newCollectorWithBuffer(store);
        var topology = Set.of(SELF, PEER_A, PEER_B);
        var connected = Set.of(SELF, PEER_A);

        collector.emitPeriodicConnectivity(topology, connected, SELF, 1000L);

        var drained = store.drainConnectivity();
        assertThat(drained).extracting("peerId").doesNotContain(SELF);
        assertThat(drained).hasSize(2);
    }

    private static ClusterSyncCollector newCollectorWithBuffer(PeerObservationStore store) {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());
        collector.setPeerObservationBuffer(store);
        return collector;
    }
}
