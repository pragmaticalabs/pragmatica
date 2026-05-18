// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityState;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// Unit tests for [`ReachabilityAggregator`] covering self-fold from local QUIC,
/// quorum threshold derivation, TTL eviction, cold-start fallback, seed-from-cache,
/// reset, and latest-wins per (target, observer) pair.
///
/// All tests use a manual clock so TTL behavior is deterministic. The aggregator
/// is leader-side only — these tests exercise it as if this node is leader; no
/// leadership simulation is needed.
class ReachabilityAggregatorTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId N1 = new NodeId("n1");
    private static final NodeId N2 = new NodeId("n2");
    private static final NodeId N3 = new NodeId("n3");
    private static final NodeId N4 = new NodeId("n4");

    private static final long TTL_MS = 30_000L;

    @Test
    void coldStart_noObservations_returnsNone() {
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 0,
            Set::of,
            Set::of,
            clock::get,
            TTL_MS);
        assertThat(aggregator.snapshot()).isEqualTo(Option.none());
    }

    @Test
    void selfFold_connectedPeers_stayUnknownBelowQuorum() {
        // 5-node cluster (quorum threshold = 3); self sees only N1, N2 as connected. With
        // SYMMETRIC quorum, a single positive self observation is NOT enough to upgrade
        // to REACHABLE — quorum=3 required AND zero dissent. N1, N2 → UNKNOWN (1 positive
        // observer, below threshold). N3, N4 → UNKNOWN (1 UNREACHABLE observer, below
        // threshold). This is by design: on a stable cluster SWIM HEALTHY drives the
        // resolveOnDutyStatus fast path; the snapshot only matters when SWIM is NOT HEALTHY.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            () -> Set.of(N1, N2),
            () -> Set.of(SELF, N1, N2, N3, N4),
            clock::get,
            TTL_MS);
        var snapshot = aggregator.snapshot().unwrap();

        var n1 = snapshot.states().get(N1);
        var n3 = snapshot.states().get(N3);
        assertThat(n1.kind()).isEqualTo(ReachabilityKind.UNKNOWN);
        assertThat(n1.observerCount()).isEqualTo(1);
        assertThat(n3.kind()).isEqualTo(ReachabilityKind.UNKNOWN);
        assertThat(n3.observerCount()).isEqualTo(1);
    }

    @Test
    void quorum_threeObserversAgreeReachable_promotesToReachable() {
        // 5-node cluster, quorum threshold = 3. Three observers (self + N1 + N2)
        // all report N3 as CONNECTED — quorum reached, state promotes to REACHABLE.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            () -> Set.of(N1, N2, N3, N4),  // self sees N3 → counts as one observer
            () -> Set.of(SELF, N1, N2, N3, N4),
            clock::get,
            TTL_MS);
        // N1 and N2 each report N3 as CONNECTED.
        aggregator.ingest(N1, List.of(conn(N3, ConnectivityState.CONNECTED, 1_000L)), List.of());
        aggregator.ingest(N2, List.of(conn(N3, ConnectivityState.CONNECTED, 1_000L)), List.of());

        var snapshot = aggregator.snapshot().unwrap();
        var n3 = snapshot.states().get(N3);
        assertThat(n3.kind()).isEqualTo(ReachabilityKind.REACHABLE);
        assertThat(n3.observerCount()).isEqualTo(3);
    }

    @Test
    void quorum_threeObserversAgreeUnreachable_promotesToUnreachable() {
        // self does not have N3 in connectedPeers → self contributes UNREACHABLE.
        // N1 and N2 report N3 as DISCONNECTED → quorum of 3 UNREACHABLE.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            () -> Set.of(N1, N2, N4),
            () -> Set.of(SELF, N1, N2, N3, N4),
            clock::get,
            TTL_MS);
        aggregator.ingest(N1, List.of(conn(N3, ConnectivityState.DISCONNECTED, 1_000L)), List.of());
        aggregator.ingest(N2, List.of(conn(N3, ConnectivityState.DISCONNECTED, 1_000L)), List.of());

        var snapshot = aggregator.snapshot().unwrap();
        assertThat(snapshot.states().get(N3).kind()).isEqualTo(ReachabilityKind.UNREACHABLE);
    }

    @Test
    void ttl_observationOlderThanTtl_dropped() {
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            Set::of,
            () -> Set.of(SELF, N1, N2, N3),
            clock::get,
            TTL_MS);
        // Three observers report N3 as CONNECTED at t=1000ms.
        aggregator.ingest(N1, List.of(conn(N3, ConnectivityState.CONNECTED, 1_000L)), List.of());
        aggregator.ingest(N2, List.of(conn(N3, ConnectivityState.CONNECTED, 1_000L)), List.of());
        // Advance past TTL — observations should be evicted.
        clock.set(1_000L + TTL_MS + 1);
        var snapshot = aggregator.snapshot();
        // Self-fold runs every snapshot — at t=clock now, self contributes
        // UNREACHABLE for N3 (not in empty connectedPeers). Empty cluster
        // observations after TTL → only self contributes → below quorum.
        var n3 = snapshot.unwrap().states().get(N3);
        assertThat(n3.observerCount()).isEqualTo(1);
        assertThat(n3.kind()).isEqualTo(ReachabilityKind.UNKNOWN);
    }

    @Test
    void healthObservation_faulty_translatesUnreachable() {
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 3,  // 3-node cluster, quorum = 2
            () -> Set.of(N1),  // self sees N1 only
            () -> Set.of(SELF, N1, N2),
            clock::get,
            TTL_MS);
        // N1 reports N2 as FAULTY via SWIM health observation — UNREACHABLE.
        aggregator.ingest(N1, List.of(), List.of(health(N2, HealthHintWire.FAULTY, 1_000L)));
        var snapshot = aggregator.snapshot().unwrap();
        // Self contributes UNREACHABLE for N2 (not in connected). N1 contributes
        // UNREACHABLE via FAULTY. Two observers → quorum=2 reached.
        assertThat(snapshot.states().get(N2).kind()).isEqualTo(ReachabilityKind.UNREACHABLE);
        assertThat(snapshot.states().get(N2).observerCount()).isEqualTo(2);
    }

    @Test
    void latestWins_perObserverPerTarget() {
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 3,
            Set::of,
            () -> Set.of(SELF, N1, N2),
            clock::get,
            TTL_MS);
        // N1 first reports N2 as CONNECTED at t=1000, then DISCONNECTED at t=2000.
        aggregator.ingest(N1, List.of(conn(N2, ConnectivityState.CONNECTED, 1_000L)), List.of());
        aggregator.ingest(N1, List.of(conn(N2, ConnectivityState.DISCONNECTED, 2_000L)), List.of());
        var snapshot = aggregator.snapshot().unwrap();
        // Self UNREACHABLE + N1 latest UNREACHABLE → quorum=2 reached.
        assertThat(snapshot.states().get(N2).kind()).isEqualTo(ReachabilityKind.UNREACHABLE);
        assertThat(snapshot.states().get(N2).observerCount()).isEqualTo(2);
    }

    @Test
    void seedFromCache_populatesSelfObservations() {
        var clock = new AtomicLong(5_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            Set::of,
            () -> Set.of(SELF, N1, N2, N3),  // self UNREACHABLE for N1/N2/N3
            clock::get,
            TTL_MS);
        var cached = new AggregatedReachabilitySnapshot(4_500L,
            Map.of(N1, new ReachabilityState(N1, ReachabilityKind.REACHABLE, 4, 4_500L),
                   N2, new ReachabilityState(N2, ReachabilityKind.UNREACHABLE, 3, 4_500L)));
        aggregator.seedFromCache(cached);
        var snapshot = aggregator.snapshot().unwrap();
        // Self contributes UNREACHABLE for N1, N2, N3 (none in connected).
        // Seed contributes prior leader's per-target kind for N1, N2 (as self-observed
        // entries by design — see ReachabilityAggregator.seedFromCache).
        // After seed, only the seed-self entry remains for N1 — kind=REACHABLE.
        // Then self-fold OVERWRITES that same self entry with UNREACHABLE (latest-wins
        // by observedAtMs: 5_000 > 4_500).
        assertThat(snapshot.states().get(N1).kind()).isEqualTo(ReachabilityKind.UNKNOWN);
    }

    @Test
    void reset_clearsAllState() {
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 3,
            () -> Set.of(N1),
            () -> Set.of(SELF, N1, N2),
            clock::get,
            TTL_MS);
        aggregator.ingest(N1, List.of(conn(N2, ConnectivityState.CONNECTED, 1_000L)), List.of());
        aggregator.reset();
        // After reset, only self-fold contributes. Self sees N1 only → N2 UNREACHABLE.
        var snapshot = aggregator.snapshot().unwrap();
        var n2 = snapshot.states().get(N2);
        assertThat(n2.observerCount()).isEqualTo(1);
        assertThat(n2.kind()).isEqualTo(ReachabilityKind.UNKNOWN);
    }

    @Test
    void quorum_oneNodeCluster_thresholdOne() {
        // N=1 → threshold = ⌈1/2⌉+1 = 1. Even self-only observation reaches quorum.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 1,
            () -> Set.of(N1),
            () -> Set.of(SELF, N1),
            clock::get,
            TTL_MS);
        var snapshot = aggregator.snapshot().unwrap();
        assertThat(snapshot.states().get(N1).kind()).isEqualTo(ReachabilityKind.REACHABLE);
    }

    @Test
    void selfTargetingObservations_ignored() {
        // Observer SELF observing target SELF must be filtered (no self-reachability).
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 3,
            () -> Set.of(N1),
            () -> Set.of(SELF, N1),
            clock::get,
            TTL_MS);
        // SELF observing SELF as DISCONNECTED — should be ignored.
        aggregator.ingest(SELF, List.of(conn(SELF, ConnectivityState.DISCONNECTED, 1_000L)), List.of());
        var snapshot = aggregator.snapshot().unwrap();
        // No state for SELF should be in the snapshot (self-targeting filtered + self-fold
        // skips self).
        assertThat(snapshot.states().containsKey(SELF)).isFalse();
    }

    @Test
    void symmetricQuorum_staleReachableDoesNotOutvoteUnreachableQuorum() {
        // 12-network regression: under the prior asymmetric scheme a single stale CONNECTED
        // observation (buffered through an EVICTED→CONNECTED flap) outvoted ⌈N/2⌉+1 UNREACHABLE
        // observations and marked dead peers alive.
        //
        // Scenario: 5-node cluster, target = N4. Self + N1 + N2 each observe N4 as UNREACHABLE
        // (3 observers, meets quorum=3). N3 has a stale CONNECTED observation from a flap.
        // Symmetric quorum rule: REACHABLE requires quorum AND zero dissent. UNREACHABLE
        // requires quorum, dissent allowed. Expectation: UNREACHABLE wins.
        var clock = new AtomicLong(10_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            () -> Set.of(N1, N2, N3),  // self does NOT see N4 → self contributes UNREACHABLE
            () -> Set.of(SELF, N1, N2, N3, N4),
            clock::get,
            TTL_MS);
        // N1, N2 report N4 as DISCONNECTED. N3 has a stale CONNECTED observation.
        aggregator.ingest(N1, List.of(conn(N4, ConnectivityState.DISCONNECTED, 10_000L)), List.of());
        aggregator.ingest(N2, List.of(conn(N4, ConnectivityState.DISCONNECTED, 10_000L)), List.of());
        aggregator.ingest(N3, List.of(conn(N4, ConnectivityState.CONNECTED, 9_500L)), List.of());

        var snapshot = aggregator.snapshot().unwrap();
        var n4 = snapshot.states().get(N4);
        // Under asymmetric rule this would have been REACHABLE (1 positive, unreachable=3
        // didn't matter because the asymmetric branch fired first). Under symmetric rule
        // the 3 UNREACHABLE observers (self + N1 + N2) win.
        assertThat(n4.kind()).isEqualTo(ReachabilityKind.UNREACHABLE);
        assertThat(n4.observerCount()).isEqualTo(3);
    }

    @Test
    void symmetricQuorum_reachableRequiresZeroDissent() {
        // Even when REACHABLE observers reach quorum, a single UNREACHABLE observer blocks
        // the upgrade — the snapshot stays UNKNOWN until the dissent ages out via TTL.
        // 5-node cluster, threshold=3. Self+N1+N2 say REACHABLE; N3 dissents UNREACHABLE.
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            () -> Set.of(N1, N2, N3, N4),   // self contributes REACHABLE for N4
            () -> Set.of(SELF, N1, N2, N3, N4),
            clock::get,
            TTL_MS);
        aggregator.ingest(N1, List.of(conn(N4, ConnectivityState.CONNECTED, 1_000L)), List.of());
        aggregator.ingest(N2, List.of(conn(N4, ConnectivityState.CONNECTED, 1_000L)), List.of());
        aggregator.ingest(N3, List.of(conn(N4, ConnectivityState.DISCONNECTED, 1_000L)), List.of());

        var snapshot = aggregator.snapshot().unwrap();
        var n4 = snapshot.states().get(N4);
        // 3 REACHABLE >= threshold=3, but unreachable=1 != 0 → no REACHABLE upgrade.
        // 1 UNREACHABLE < threshold=3 → no UNREACHABLE upgrade. → UNKNOWN.
        assertThat(n4.kind()).isEqualTo(ReachabilityKind.UNKNOWN);
    }

    @Test
    void ttl_fiveSecondTtl_evictsStaleObservationsWithinSwimWindow() {
        // Production TTL is 5s (down from 30s) — within SWIM's 15s soft detection window
        // and well below the chaos test convergence budget. This test simulates the
        // production TTL value and verifies stale observations are evicted promptly so
        // the snapshot can converge during chaos events.
        var productionTtlMs = 5_000L;
        var clock = new AtomicLong(1_000L);
        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> 5,
            () -> Set.of(N1),  // self stable view: only N1 connected
            () -> Set.of(SELF, N1, N2, N3, N4),
            clock::get,
            productionTtlMs);
        // Stale CONNECTED flap from N2 at t=1000.
        aggregator.ingest(N2, List.of(conn(N4, ConnectivityState.CONNECTED, 1_000L)), List.of());
        // Advance 6 seconds — past 5s TTL.
        clock.set(7_000L);
        // Real UNREACHABLE quorum arrives now from N1+N2+N3 + self self-fold.
        aggregator.ingest(N1, List.of(conn(N4, ConnectivityState.DISCONNECTED, 7_000L)), List.of());
        aggregator.ingest(N2, List.of(conn(N4, ConnectivityState.DISCONNECTED, 7_000L)), List.of());
        aggregator.ingest(N3, List.of(conn(N4, ConnectivityState.DISCONNECTED, 7_000L)), List.of());

        var snapshot = aggregator.snapshot().unwrap();
        var n4 = snapshot.states().get(N4);
        // The original CONNECTED from N2 at t=1000 is past TTL and replaced by N2's later
        // DISCONNECTED at t=7000 (latest-wins overwrites in-place). Self + N1 + N2 + N3
        // all UNREACHABLE → 4 observers, >= quorum=3 → UNREACHABLE.
        assertThat(n4.kind()).isEqualTo(ReachabilityKind.UNREACHABLE);
        assertThat(n4.observerCount()).isEqualTo(4);
    }

    private static PeerConnectivityObservation conn(NodeId target, ConnectivityState state, long producedAtMs) {
        return new PeerConnectivityObservation(target, state, 0L, 0L, producedAtMs);
    }

    private static PeerHealthObservation health(NodeId target, HealthHintWire hint, long producedAtMs) {
        return new PeerHealthObservation(target, hint, 0L, 0L, producedAtMs);
    }
}
