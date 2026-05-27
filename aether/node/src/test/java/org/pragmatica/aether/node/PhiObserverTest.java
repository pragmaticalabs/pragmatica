// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.PhiAccrualDetector;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the hardened φ-actuation guards in [`PhiObserver#evaluateTick`] (#231 §12.8
/// Lifeguard). A hand-controlled [`StubDetector`] sets warm/suspected per peer, a recording sink
/// captures every decommission, and a mutable tracked-peer set drives the tick. The four guards
/// under test — local-stall skip, consecutive-tick debounce (K), quorum self-guard, and
/// self-incarnation exclusion — are exercised independently and in the exact regression
/// combination that collapsed a Docker cluster (a single all-peers-suspected stall tick).
class PhiObserverTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER_A = new NodeId("node-a");
    private static final NodeId PEER_B = new NodeId("node-b");
    private static final NodeId PEER_C = new NodeId("node-c");
    private static final NodeId PEER_D = new NodeId("node-d");
    private static final int K = 5;

    /// REGRESSION: a single tick where the leader's sample loop stalled (no pong advanced) and φ
    /// saturated for ALL peers must evict NOBODY — both the local-stall guard and the debounce
    /// guard independently veto it.
    @Test
    void evaluateTick_transientAllSuspectedSingleTick_decommissionsNobody() {
        var detector = new StubDetector();
        var sink = new RecordingSink();
        var tracked = trackedSet(PEER_A, PEER_B);
        var observer = observer(detector, () -> tracked, sink);

        // Tick 1: pongs advance, everyone healthy → establishes a baseline snapshot.
        advancePongs(observer, PEER_A, PEER_B);
        detector.allHealthy(PEER_A, PEER_B);
        observer.evaluateTick(1_000L);

        // Tick 2: the stall — NO pong advances, φ saturates for all peers at once.
        detector.allWarmSuspected(PEER_A, PEER_B);
        observer.evaluateTick(2_000L);

        assertThat(sink.decommissioned()).isEmpty();
    }

    /// A single genuinely silent peer in an otherwise-healthy cluster is evicted EXACTLY at tick K
    /// (debounce satisfied) and not one tick earlier.
    @Test
    void evaluateTick_sustainedSinglePeerSilence_decommissionsAtTickK() {
        var detector = new StubDetector();
        var sink = new RecordingSink();
        // Five-node cluster (self + 4 peers) so evicting one never approaches quorum.
        var tracked = trackedSet(PEER_A, PEER_B, PEER_C, PEER_D);
        var observer = observer(detector, () -> tracked, sink);

        for (var tick = 1; tick <= K; tick++) {
            // Healthy peers keep advancing pongs (defeats the local-stall guard); the silent one
            // never advances and reads warm+suspected every tick.
            advancePongs(observer, PEER_B, PEER_C, PEER_D);
            detector.allHealthy(PEER_B, PEER_C, PEER_D);
            detector.warmSuspected(PEER_A);

            observer.evaluateTick(tick * 1_000L);

            if (tick < K) {
                assertThat(sink.decommissioned())
                    .as("no eviction before tick K (tick=%d)", tick)
                    .isEmpty();
            }
        }

        assertThat(sink.decommissioned()).containsExactly(PEER_A);
    }

    /// All peers sustained warm+suspected for ≥K ticks (healthy pong advancement defeats the
    /// local-stall guard) → the quorum self-guard recognises a whole-world-died anomaly and
    /// suppresses the entire batch.
    @Test
    void evaluateTick_allPeersSuspectedSustained_quorumGuardSuppresses() {
        var detector = new StubDetector();
        var sink = new RecordingSink();
        var tracked = trackedSet(PEER_A, PEER_B, PEER_C);
        var observer = observer(detector, () -> tracked, sink);

        for (var tick = 1; tick <= K + 2; tick++) {
            // Pongs advance (so it is NOT a local stall) yet every peer reads suspected — the only
            // remaining guard is the quorum self-guard.
            advancePongs(observer, PEER_A, PEER_B, PEER_C);
            detector.allWarmSuspected(PEER_A, PEER_B, PEER_C);

            observer.evaluateTick(tick * 1_000L);
        }

        assertThat(sink.decommissioned()).isEmpty();
    }

    /// A tick on which no peer's pong advanced is treated as a local observer stall and suppresses
    /// eviction, even for a peer that would otherwise be a debounced candidate.
    @Test
    void evaluateTick_noPongAdvancement_suppressesEviction() {
        var detector = new StubDetector();
        var sink = new RecordingSink();
        var tracked = trackedSet(PEER_A, PEER_B, PEER_C, PEER_D);
        var observer = observer(detector, () -> tracked, sink);

        // Build PEER_A's streak to K-1 with healthy advancement so it is one tick from candidacy.
        for (var tick = 1; tick < K; tick++) {
            advancePongs(observer, PEER_B, PEER_C, PEER_D);
            detector.allHealthy(PEER_B, PEER_C, PEER_D);
            detector.warmSuspected(PEER_A);
            observer.evaluateTick(tick * 1_000L);
        }
        assertThat(sink.decommissioned()).isEmpty();

        // Tick K: NObody advances a pong → local-stall guard fires, no eviction despite PEER_A
        // reaching the streak threshold on this tick.
        detector.allWarmSuspected(PEER_A, PEER_B, PEER_C, PEER_D);
        observer.evaluateTick(K * 1_000L);

        assertThat(sink.decommissioned()).isEmpty();
    }

    /// A peer suspected for fewer than K ticks then recovering resets its streak — it is never
    /// evicted even though it later goes silent again briefly.
    @Test
    void evaluateTick_suspectedBelowKThenRecovers_resetsStreakNoDecommission() {
        var detector = new StubDetector();
        var sink = new RecordingSink();
        var tracked = trackedSet(PEER_A, PEER_B, PEER_C, PEER_D);
        var observer = observer(detector, () -> tracked, sink);

        // Ticks 1..K-1: PEER_A suspected (streak climbs to K-1), others healthy.
        for (var tick = 1; tick < K; tick++) {
            advancePongs(observer, PEER_B, PEER_C, PEER_D);
            detector.allHealthy(PEER_B, PEER_C, PEER_D);
            detector.warmSuspected(PEER_A);
            observer.evaluateTick(tick * 1_000L);
        }

        // Tick K: PEER_A recovers (pong landed) → streak resets to 0.
        advancePongs(observer, PEER_A, PEER_B, PEER_C, PEER_D);
        detector.allHealthy(PEER_A, PEER_B, PEER_C, PEER_D);
        observer.evaluateTick(K * 1_000L);

        // Ticks K+1..2K-1: PEER_A silent again for K-1 ticks — must NOT fire (streak restarted).
        for (var tick = K + 1; tick < (2 * K); tick++) {
            advancePongs(observer, PEER_B, PEER_C, PEER_D);
            detector.allHealthy(PEER_B, PEER_C, PEER_D);
            detector.warmSuspected(PEER_A);
            observer.evaluateTick(tick * 1_000L);
        }

        assertThat(sink.decommissioned()).isEmpty();
    }

    /// A stale prior incarnation of self (same base id, different KSUID suffix) is excluded from φ
    /// evaluation — the leader never decommissions its own ghost, even when φ reports it
    /// warm+suspected for well over K ticks.
    @Test
    void evaluateTick_selfPriorIncarnation_isExcludedFromEviction() {
        var realSelf = new NodeId("aether-b-node-3" + "-" + ksuid("Aa"));
        var ghostSelf = new NodeId("aether-b-node-3" + "-" + ksuid("Zz"));
        var detector = new StubDetector();
        var sink = new RecordingSink();
        var tracked = trackedSet(ghostSelf, PEER_A, PEER_B, PEER_C);
        var observer = PhiObserver.phiObserver(realSelf, () -> true, detector, () -> tracked, sink, K);

        for (var tick = 1; tick <= K + 2; tick++) {
            advancePongs(observer, PEER_A, PEER_B, PEER_C);
            detector.allHealthy(PEER_A, PEER_B, PEER_C);
            detector.warmSuspected(ghostSelf);
            observer.evaluateTick(tick * 1_000L);
        }

        assertThat(sink.decommissioned()).doesNotContain(ghostSelf);
        assertThat(sink.decommissioned()).isEmpty();
    }

    /// A distinct logical peer that merely shares a textual prefix with self is NOT mistaken for a
    /// self-incarnation — it must still be evictable. Guards against an over-eager prefix hack.
    @Test
    void evaluateTick_distinctPeerSharingPrefix_isStillEvictable() {
        var realSelf = new NodeId("aether-b-node-3" + "-" + ksuid("Aa"));
        var otherNode = new NodeId("aether-b-node-30" + "-" + ksuid("Bb"));
        var detector = new StubDetector();
        var sink = new RecordingSink();
        var tracked = trackedSet(otherNode, PEER_A, PEER_B, PEER_C);
        var observer = PhiObserver.phiObserver(realSelf, () -> true, detector, () -> tracked, sink, K);

        for (var tick = 1; tick <= K; tick++) {
            advancePongs(observer, PEER_A, PEER_B, PEER_C);
            detector.allHealthy(PEER_A, PEER_B, PEER_C);
            detector.warmSuspected(otherNode);
            observer.evaluateTick(tick * 1_000L);
        }

        assertThat(sink.decommissioned()).containsExactly(otherNode);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static PhiObserver observer(StubDetector detector, Supplier<Set<NodeId>> tracked, RecordingSink sink) {
        BooleanSupplier leaderAlways = () -> true;
        return PhiObserver.phiObserver(SELF, leaderAlways, detector, tracked, sink, K);
    }

    private static Set<NodeId> trackedSet(NodeId... peers) {
        return new HashSet<>(List.of(peers));
    }

    private static void advancePongs(PhiObserver observer, NodeId... peers) {
        for (var peer : peers) {
            observer.onPong(peer);
        }
    }

    /// Build a 27-char Base62 KSUID-shaped suffix from a short seed (right-padded with '0').
    private static String ksuid(String seed) {
        return (seed + "0".repeat(27)).substring(0, 27);
    }

    /// Recording [`PhiObserver.ForceDecommissionSink`] — captures the order and identity of every
    /// fired decommission.
    private static final class RecordingSink implements PhiObserver.ForceDecommissionSink {
        private final List<NodeId> fired = new ArrayList<>();

        @Override
        public void decommission(NodeId peer) {
            fired.add(peer);
        }

        List<NodeId> decommissioned() {
            return fired;
        }
    }

    /// Hand-controlled [`PhiAccrualDetector`]: warm/suspected/φ are set explicitly per peer and
    /// persist across ticks until changed. The actual algorithm is exercised by
    /// PhiAccrualDetectorTest; here we drive the actuator's guard logic directly.
    private static final class StubDetector implements PhiAccrualDetector {
        private final Map<NodeId, Boolean> warm = new HashMap<>();
        private final Map<NodeId, Boolean> suspected = new HashMap<>();

        void warmSuspected(NodeId peer) {
            warm.put(peer, true);
            suspected.put(peer, true);
        }

        void allWarmSuspected(NodeId... peers) {
            for (var peer : peers) {
                warmSuspected(peer);
            }
        }

        void healthy(NodeId peer) {
            warm.put(peer, true);
            suspected.put(peer, false);
        }

        void allHealthy(NodeId... peers) {
            for (var peer : peers) {
                healthy(peer);
            }
        }

        @Override
        public void heartbeat(NodeId peer, long nowMs) {
            // Warmth is driven explicitly via warmSuspected/healthy; a heartbeat alone is a no-op.
        }

        @Override
        public double phi(NodeId peer, long nowMs) {
            return suspected.getOrDefault(peer, false) ? 9.0 : 0.0;
        }

        @Override
        public boolean suspected(NodeId peer, long nowMs) {
            return suspected.getOrDefault(peer, false);
        }

        @Override
        public boolean isWarm(NodeId peer) {
            return warm.getOrDefault(peer, false);
        }

        @Override
        public void forget(NodeId peer) {
            warm.remove(peer);
            suspected.remove(peer);
        }

        @Override
        public void clear() {
            warm.clear();
            suspected.clear();
        }
    }
}
