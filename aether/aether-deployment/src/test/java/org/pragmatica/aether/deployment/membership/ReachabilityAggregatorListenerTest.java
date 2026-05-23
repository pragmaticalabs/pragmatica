// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// Topology-observation refactor Step 3: snapshot-listener contract on
/// [`ReachabilityAggregator`]. Listeners are invoked synchronously from the
/// snapshot-builder thread after each successful (non-empty) snapshot build.
class ReachabilityAggregatorListenerTest {
    private static final NodeId SELF = new NodeId("self");

    private static final NodeId N1 = new NodeId("n1");

    private static final NodeId N2 = new NodeId("n2");

    private static final long TTL_MS = 30_000L;

    @Test
    void listenerInvoked_onSnapshotBuild_singleInvocationWithProducedSnapshot() {
        var clock = new AtomicLong(1_000L);
        // 3-node cluster with self folding two connected peers — produces a non-empty snapshot
        // so the listener fires (empty snapshots return Option.none() and listeners are NOT
        // invoked, by design).
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 3,
            () -> Set.of(N1, N2),
            () -> Set.of(SELF, N1, N2),
            clock::get,
            TTL_MS);
        var received = new ArrayList<AggregatedReachabilitySnapshot>();
        aggregator.addSnapshotListener(received::add);

        var produced = aggregator.snapshot().unwrap();

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isSameAs(produced);
    }

    @Test
    void multipleListeners_allInvoked_inRegistrationOrder() {
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 3,
            () -> Set.of(N1, N2),
            () -> Set.of(SELF, N1, N2),
            clock::get,
            TTL_MS);
        var order = new ArrayList<Integer>();
        aggregator.addSnapshotListener(_ -> order.add(1));
        aggregator.addSnapshotListener(_ -> order.add(2));
        aggregator.addSnapshotListener(_ -> order.add(3));

        aggregator.snapshot();

        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    void listenerThrows_otherListenersStillInvoked_aggregatorDoesNotCrash() {
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 3,
            () -> Set.of(N1, N2),
            () -> Set.of(SELF, N1, N2),
            clock::get,
            TTL_MS);
        var invocations = new ArrayList<String>();
        aggregator.addSnapshotListener(_ -> invocations.add("before"));
        aggregator.addSnapshotListener(ReachabilityAggregatorListenerTest::failingListener);
        aggregator.addSnapshotListener(_ -> invocations.add("after"));

        var produced = aggregator.snapshot();

        assertThat(produced.isPresent()).isTrue();
        assertThat(invocations).containsExactly("before", "after");
    }

    private static void failingListener(AggregatedReachabilitySnapshot ignored) {
        throw new RuntimeException("boom");
    }

    @Test
    void emptySnapshot_listenerNotInvoked() {
        // Cold-start: no observations, no topology — snapshot() returns Option.none() and
        // listeners must NOT fire. Documents the contract: listeners receive only non-empty
        // snapshots.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 0,
            Set::of,
            Set::of,
            clock::get,
            TTL_MS);
        var invocations = new ArrayList<AggregatedReachabilitySnapshot>();
        aggregator.addSnapshotListener(invocations::add);

        var produced = aggregator.snapshot();

        assertThat(produced.isPresent()).isFalse();
        assertThat(invocations).isEmpty();
    }

    @Test
    void ingestedObservations_listenerReceivesAggregatedView() {
        // Validates the listener receives the actual snapshot value (not a stale reference).
        // 3-node cluster, two observers report N1 REACHABLE — quorum threshold = 2, so the
        // snapshot promotes N1 to REACHABLE. Note: self is the observer, the second observer
        // is ingested via PeerConnectivityObservation from N2.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 3,
            () -> Set.of(N1),
            () -> Set.of(SELF, N1, N2),
            clock::get,
            TTL_MS);
        var received = new ArrayList<AggregatedReachabilitySnapshot>();
        aggregator.addSnapshotListener(received::add);
        aggregator.ingest(N2,
                          List.of(new PeerConnectivityObservation(N1, ConnectivityState.CONNECTED, 0L, 0L, 1_000L)),
                          List.of());

        aggregator.snapshot();

        assertThat(received).hasSize(1);
        var n1State = received.get(0).states().get(N1);
        assertThat(n1State).isNotNull();
        assertThat(n1State.kind()).isEqualTo(ReachabilityKind.REACHABLE);
    }
}
