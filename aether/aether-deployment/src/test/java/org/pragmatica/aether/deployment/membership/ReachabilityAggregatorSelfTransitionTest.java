// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// Covers Step 4 of the topology-observation refactor: leader-side QUIC transitions feed
/// `ReachabilityAggregator` directly via `ingestSelfTransition`, bypassing the 5s self-fold
/// tick and the follower→leader pong-relay roundtrip. The leader is its own observer for
/// the `(self, target)` cell — these tests verify latest-wins semantics, transition
/// reflection in the snapshot under symmetric-quorum, and idempotence on repeat calls.
class ReachabilityAggregatorSelfTransitionTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId N1 = new NodeId("n1");
    private static final NodeId N2 = new NodeId("n2");
    private static final NodeId N3 = new NodeId("n3");
    private static final NodeId N4 = new NodeId("n4");

    private static final long TTL_MS = 30_000L;

    @Test
    void ingestSelfTransition_unreachable_reflectsInSnapshotUnderQuorum() {
        // 5-node cluster, quorum threshold = 3. Pre-state: aggregator has REACHABLE
        // observations from N1, N3 plus self (via fold) for N2 — so N2 is REACHABLE.
        // Leader's QUIC then drops N2: ingestSelfTransition flips self's observer
        // entry to UNREACHABLE WHILE connectedPeers ALSO drops N2 (realistic ordering;
        // QUIC fires the drop after removing the peer from its connected set). With
        // one UNREACHABLE dissenter, REACHABLE upgrade is blocked (zero-dissent rule);
        // state degrades from REACHABLE → UNKNOWN.
        var clock = new AtomicLong(1_000L);
        var connectedPeers = new java.util.concurrent.atomic.AtomicReference<Set<NodeId>>(
            Set.of(N1, N2, N3, N4));
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            connectedPeers::get,
            () -> Set.of(SELF, N1, N2, N3, N4),
            clock::get,
            TTL_MS);
        aggregator.ingest(N1, List.of(conn(N2, ConnectivityState.CONNECTED, 1_000L)), List.of());
        aggregator.ingest(N3, List.of(conn(N2, ConnectivityState.CONNECTED, 1_000L)), List.of());
        var before = aggregator.snapshot().unwrap();
        assertThat(before.states().get(N2).kind()).isEqualTo(ReachabilityKind.REACHABLE);

        // QUIC drop: peer leaves connected set AND fires onPeerDisconnected at the
        // same moment.
        clock.set(2_000L);
        connectedPeers.set(Set.of(N1, N3, N4));
        aggregator.ingestSelfTransition(N2, ReachabilityKind.UNREACHABLE, 2_000L);

        var after = aggregator.snapshot().unwrap();
        // Self's UNREACHABLE observation blocks REACHABLE upgrade (single dissent rule).
        // Without ⌈N/2⌉+1=3 UNREACHABLE observers, the kind degrades to UNKNOWN.
        assertThat(after.states().get(N2).kind()).isEqualTo(ReachabilityKind.UNKNOWN);
    }

    @Test
    void ingestSelfTransition_unreachable_quorumPromotesWhenPeerDroppedFromConnected() {
        // Realistic timing: peer killed → QUIC drop → connectedPeers no longer
        // contains N1 → both the synchronous ingestSelfTransition and the next
        // self-fold tick AGREE on UNREACHABLE. Quorum with two remote DISCONNECTED
        // observers promotes the state.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            () -> Set.of(N2, N3, N4),  // N1 dropped — self contributes UNREACHABLE
            () -> Set.of(SELF, N1, N2, N3, N4),
            clock::get,
            TTL_MS);
        aggregator.ingest(N2, List.of(conn(N1, ConnectivityState.DISCONNECTED, 1_500L)), List.of());
        aggregator.ingest(N3, List.of(conn(N1, ConnectivityState.DISCONNECTED, 1_500L)), List.of());

        clock.set(2_000L);
        aggregator.ingestSelfTransition(N1, ReachabilityKind.UNREACHABLE, 2_000L);

        var after = aggregator.snapshot().unwrap();
        var n1 = after.states().get(N1);
        assertThat(n1.kind()).isEqualTo(ReachabilityKind.UNREACHABLE);
        assertThat(n1.observerCount()).isEqualTo(3);
    }

    @Test
    void ingestSelfTransition_latestWinsOverEarlierObservation() {
        // Earlier REACHABLE self-observation must be overwritten by a later
        // UNREACHABLE one even if a fresh snapshot self-fold ran in between.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 3,
            () -> Set.of(N1),  // self sees N1 connected → fold contributes REACHABLE
            () -> Set.of(SELF, N1),
            clock::get,
            TTL_MS);
        // Pre-warm self entry via fold.
        aggregator.snapshot();

        clock.set(2_000L);
        // Older timestamp must be ignored (latest-wins keyed on producedAtMs).
        aggregator.ingestSelfTransition(N1, ReachabilityKind.UNREACHABLE, 500L);
        // The self-fold from the next snapshot at clock=2_000L will write
        // REACHABLE at observedAtMs=2_000L, eclipsing the stale 500L entry.
        var afterStale = aggregator.snapshot().unwrap();
        assertThat(afterStale.states().get(N1).kind()).isNotEqualTo(ReachabilityKind.UNREACHABLE);

        // Newer timestamp wins over the self-fold entry.
        clock.set(3_000L);
        aggregator.ingestSelfTransition(N1, ReachabilityKind.UNREACHABLE, 4_000L);
        var afterFresh = aggregator.snapshot().unwrap();
        // The self-fold at 3_000L runs first inside snapshot(); ingestSelfTransition
        // happened BEFORE the snapshot call, so the latest-wins check at the fold
        // (now=3_000L vs existing=4_000L) preserves the UNREACHABLE entry.
        assertThat(afterFresh.states().get(N1).kind()).isEqualTo(ReachabilityKind.UNKNOWN);
        // Verify observer is self with UNREACHABLE kind preserved by inspecting
        // observerCount = 1 (only self observation present, since N=3 quorum=2).
        assertThat(afterFresh.states().get(N1).observerCount()).isEqualTo(1);
    }

    @Test
    void ingestSelfTransition_idempotentRepeatCallsHonorLatest() {
        // Repeat calls with monotonically increasing timestamps simply update the
        // self entry; no duplicate observer counted.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            Set::of,
            () -> Set.of(SELF, N1, N2, N3, N4),
            clock::get,
            TTL_MS);
        aggregator.ingestSelfTransition(N1, ReachabilityKind.UNREACHABLE, 1_000L);
        aggregator.ingestSelfTransition(N1, ReachabilityKind.UNREACHABLE, 1_500L);
        aggregator.ingestSelfTransition(N1, ReachabilityKind.UNREACHABLE, 2_000L);

        clock.set(2_000L);
        var snapshot = aggregator.snapshot().unwrap();
        // Self contributes one observer; quorum=3 not reached → UNKNOWN with count=1.
        var n1 = snapshot.states().get(N1);
        assertThat(n1.observerCount()).isEqualTo(1);
        assertThat(n1.kind()).isEqualTo(ReachabilityKind.UNKNOWN);
        assertThat(n1.lastObservedAtMs()).isEqualTo(2_000L);
    }

    private static PeerConnectivityObservation conn(NodeId target, ConnectivityState state, long producedAtMs) {
        return new PeerConnectivityObservation(target, state, 1L, 0L, producedAtMs);
    }
}
