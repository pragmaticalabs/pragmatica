// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


/// Regression test for the decision-plane / observation-plane split.
///
/// **Bug class (architectural).** Pre-fix, the aggregator's `topologySupplier` was
/// wired to QUIC's `TopologyObserver.topology()` — a transport-reactive set that
/// shrank the instant `processViewChange(REMOVE)` fired after a peer was killed.
/// The aggregator's self-fold loop (foldSelfObservations) then stopped iterating
/// the dead peer, no new UNREACHABLE observation was recorded, prior entries aged
/// out after 15s TTL, and the dead peer became structurally invisible to the
/// UNREACHABLE-quorum gate.
///
/// **Fix.** `topologySupplier` is now a KV-derived projection of non-terminal
/// NodeLifecycleKey entries — the cluster's decision plane. It remains stable
/// across QUIC drops: a killed peer continues to be tracked until consensus
/// writes its NodeLifecycleKey to DECOMMISSIONED (which only happens AFTER the
/// UNREACHABLE quorum is reached). This test pins that invariant.
class ReachabilityAggregatorTopologySourceTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");
    private static final long TTL_MS = 15_000L;

    /// Pre-kill: 5-node cluster, all peers reachable from self via QUIC. With
    /// SYMMETRIC quorum (⌈N/2⌉+1 = 3) and only one observer (self), no peer
    /// reaches REACHABLE quorum; all show UNKNOWN. That's expected — the snapshot
    /// only matters once observations from peers arrive. The interesting property
    /// we pin here is that ALL 4 non-self peers appear in the snapshot.
    @Test
    void selfFoldIncludesAllKvTopologyPeers_evenWhenLocalQuicDisconnected() {
        var clock = new AtomicLong(1_000L);
        // KV topology is canonical: {SELF, A, B, C, D}. Persists across the test.
        AtomicReference<Set<NodeId>> kvTopology = new AtomicReference<>(Set.of(SELF, A, B, C, D));
        AtomicReference<Set<NodeId>> connected = new AtomicReference<>(Set.of(A, B, C, D));

        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> kvTopology.get().size(),
            connected::get,
            kvTopology::get,
            clock::get,
            TTL_MS);

        var snapshot = aggregator.snapshot().unwrap();
        assertThat(snapshot.states().keySet()).containsExactlyInAnyOrder(A, B, C, D);
    }

    /// Core regression: simulate a `docker kill` of peer D. The local QUIC layer
    /// fires a DISCONNECTED transition for D — pre-fix, `connected` AND `topology`
    /// would both drop D at this instant. Post-fix, only `connected` drops D; the
    /// KV-derived `topology` retains D because consensus has not yet written its
    /// DECOMMISSIONED entry (that's what we're trying to reach quorum for).
    ///
    /// After the kill, every aggregator snapshot must continue to produce an
    /// observation for D from self with kind=UNREACHABLE. Without this property,
    /// the UNREACHABLE quorum can never be reached and the dead peer hangs around
    /// in ON_DUTY indefinitely (the 17-27s S01 elapsed bug).
    @Test
    void killedPeerStaysTrackedUntilDecommissioned_andProducesUnreachableObservation() {
        var clock = new AtomicLong(1_000L);
        AtomicReference<Set<NodeId>> kvTopology = new AtomicReference<>(Set.of(SELF, A, B, C, D));
        AtomicReference<Set<NodeId>> connected = new AtomicReference<>(Set.of(A, B, C, D));

        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> kvTopology.get().size(),
            connected::get,
            kvTopology::get,
            clock::get,
            TTL_MS);

        // Pre-kill snapshot establishes baseline.
        aggregator.snapshot().unwrap();

        // Simulate docker kill of D: QUIC drops D from `connected`, but consensus
        // has NOT yet written D's NodeLifecycleKey to DECOMMISSIONED — that's what
        // the quorum gate is supposed to trigger. So kvTopology STILL contains D.
        connected.set(Set.of(A, B, C));
        clock.addAndGet(1_000L);

        var snapshot = aggregator.snapshot().unwrap();
        var d = snapshot.states().get(D);
        assertThat(d).as("D must remain in snapshot after kill — decision plane is KV not QUIC").isNotNull();
        // Self is sole live observer; quorum=3 not met → UNKNOWN. But the observation
        // IS recorded (observerCount=1), keeping D in the tracking set until peer
        // observations arrive.
        assertThat(d.observerCount()).isEqualTo(1);

        // Add UNREACHABLE observations from peers A, B, C — quorum=3 reached.
        clock.addAndGet(500L);
        ingestUnreachable(aggregator, A, D, clock.get());
        ingestUnreachable(aggregator, B, D, clock.get());
        ingestUnreachable(aggregator, C, D, clock.get());

        var quorumSnapshot = aggregator.snapshot().unwrap();
        var dQuorum = quorumSnapshot.states().get(D);
        // Self UNREACHABLE + 3 peer UNREACHABLE = 4 observers. Quorum threshold for
        // N=5 is ⌈5/2⌉+1 = 3 → met.
        assertThat(dQuorum.kind()).isEqualTo(ReachabilityKind.UNREACHABLE);
        assertThat(dQuorum.observerCount()).isGreaterThanOrEqualTo(3);
    }

    /// Pre-fix regression: even if local QUIC drops a peer immediately, the
    /// aggregator's self-fold must keep producing an UNREACHABLE observation for
    /// it on every snapshot until the peer leaves the KV decision plane. Walk
    /// time forward past TTL to prove the observation is being refreshed each
    /// snapshot, not just retained as a stale entry.
    @Test
    void selfObservationRefreshesEachSnapshot_evenAfterTtlBoundary() {
        var clock = new AtomicLong(1_000L);
        AtomicReference<Set<NodeId>> kvTopology = new AtomicReference<>(Set.of(SELF, A, B, C, D));
        AtomicReference<Set<NodeId>> connected = new AtomicReference<>(Set.of(A, B, C));

        var aggregator = ReachabilityAggregator.reachabilityAggregator(
            SELF,
            () -> kvTopology.get().size(),
            connected::get,
            kvTopology::get,
            clock::get,
            TTL_MS);

        // Walk the clock forward by 2× TTL, taking a snapshot each second. If the
        // self-fold is not refreshing D's observation, the entry would age out and
        // D would disappear from the snapshot.
        for (var step = 0; step < 35; step++) {
            clock.addAndGet(1_000L);
            var snapshot = aggregator.snapshot().unwrap();
            var d = snapshot.states().get(D);
            assertThat(d)
                .as("D must remain after %d seconds — self-fold must refresh observation each snapshot",
                    step)
                .isNotNull();
        }
    }

    private static void ingestUnreachable(ReachabilityAggregator aggregator, NodeId observer, NodeId target, long atMs) {
        aggregator.ingest(observer,
                          List.of(new PeerConnectivityObservation(target, ConnectivityState.DISCONNECTED, 0L, 0L, atMs)),
                          List.of());
    }
}
