// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimObservation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Contract: asymmetric-threshold aggregator (RC1, post-revision 2026-05-12).
///
/// **Asymmetric thresholds.** The aggregator applies two different thresholds
/// keyed on the observed lifecycle state:
///
/// - `ON_DUTY` — majority quorum `(onDutyCount / 2) + 1` (floored at 1).
///   Peer re-confirmation gate; usually a no-op since bootstrap goes through
///   `attemptSelfOnDutyWrite`, not the aggregator.
/// - `DECOMMISSIONED` — single-witness (threshold = 1). The aggregator
///   currently receives observations only from the local SWIM detector
///   (`HealthReconcilerImpl.aggregateEdge` always tags `self` as the observer);
///   cross-node observation propagation is not yet wired. Requiring a majority
///   of distinct observers would make `DECOMMISSIONED` unreachable — dead nodes
///   cannot self-promote, so the local-SWIM-driven aggregator is the only write
///   path. Cluster-wide agreement is still preserved: the leader's
///   `DECOMMISSIONED` write is consensus-replicated, so all nodes converge on
///   the same lifecycle even though the threshold here is single-witness.
///
/// **Rationale (single-aggregator-per-node).** Because there is one aggregator
/// instance per node and each receives only its own SWIM observations, the
/// "distinct observers" count this aggregator sees never exceeds 1 in practice.
/// Asymmetric thresholds let `ON_DUTY` stay strict (rare path, peer-confirmation
/// semantics still meaningful for future cross-node propagation) while
/// `DECOMMISSIONED` (failure detection, must fire) bypasses the unreachable
/// majority. True symmetric majority quorum returns once cross-node observation
/// gossip is wired.
///
/// **Pending semantics** — observations below threshold remain in the per-target
/// sliding window. Each new observation re-evaluates the tally. Observations
/// older than `aggregationWindow` are evicted on the next call to
/// `onObservation` and no longer count.
class ObservationAggregatorTest {
    private static final NodeId SELF = nodeId("self").unwrap();
    private static final NodeId OBS_A = nodeId("obs-a").unwrap();
    private static final NodeId OBS_B = nodeId("obs-b").unwrap();
    private static final NodeId OBS_C = nodeId("obs-c").unwrap();
    private static final NodeId OBS_D = nodeId("obs-d").unwrap();
    private static final NodeId TARGET = nodeId("target").unwrap();

    private static SwimObservation healthy(NodeId target) {
        return new SwimObservation.HealthyObserved(target, 1L);
    }

    private static SwimObservation faulty(NodeId target) {
        return new SwimObservation.FaultyObserved(target, 1L);
    }

    private static SwimObservation departed(NodeId target) {
        return new SwimObservation.DepartedObserved(target, 1L);
    }

    private static SwimObservation unknown(NodeId target) {
        return new SwimObservation.UnknownObserved(target, 1L);
    }

    @Test
    void aggregator_singleObserverOnLargeCluster_doesNotAdvance() {
        // Cluster of 5 on-duty → ON_DUTY threshold = 3. A single observer's HEALTHY
        // observation must NOT emit a StateChanged edge; the observation stays
        // pending in the per-target window awaiting majority confirmation.
        // (DECOMMISSIONED is single-witness; HEALTHY/ON_DUTY remains majority.)
        var aggregator = ObservationAggregator.observationAggregator();
        var emitted = aggregator.onObservation(SELF, healthy(TARGET), 5, 0L);
        assertThat(emitted.isEmpty())
                .as("Single observer on 5-node cluster must NOT cross ON_DUTY majority threshold (3)")
                .isTrue();
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.ON_DUTY))
                .as("Observation is retained as pending")
                .isEqualTo(1);
    }

    @Test
    void aggregator_majorityHealthy_emitsOnDutyEdge() {
        // Cluster of 5 on-duty → ON_DUTY threshold = 3. Three distinct observers
        // reporting HEALTHY for the same target cross the threshold and emit ON_DUTY.
        var aggregator = ObservationAggregator.observationAggregator();
        assertThat(aggregator.onObservation(SELF, healthy(TARGET), 5, 0L).isEmpty()).isTrue();
        assertThat(aggregator.onObservation(OBS_A, healthy(TARGET), 5, 0L).isEmpty()).isTrue();
        var emitted = aggregator.onObservation(OBS_B, healthy(TARGET), 5, 0L);
        assertThat(emitted.isPresent())
                .as("Third distinct observer crosses majority threshold and emits edge")
                .isTrue();
        assertThat(emitted.unwrap().target()).isEqualTo(TARGET);
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.ON_DUTY);
    }

    @Test
    void aggregator_majorityFaulty_emitsDecommissionedEdge() {
        // Cluster of 5 on-duty. Under asymmetric thresholds, DECOMMISSIONED is
        // single-witness — the FIRST FAULTY observer emits the edge immediately.
        // Cross-node propagation is not wired; the aggregator only sees local
        // SWIM observations, so requiring a majority would make DECOMMISSIONED
        // unreachable.
        var aggregator = ObservationAggregator.observationAggregator();
        var emitted = aggregator.onObservation(SELF, faulty(TARGET), 5, 0L);
        assertThat(emitted.isPresent())
                .as("First FAULTY observer emits DECOMMISSIONED (single-witness)")
                .isTrue();
        assertThat(emitted.unwrap().target()).isEqualTo(TARGET);
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_threeNodeCluster_majorityIsTwo() {
        // Cluster of 3 on-duty → ON_DUTY majority = 2, but DECOMMISSIONED is
        // single-witness regardless of cluster size. A single FAULTY observer
        // emits the edge immediately.
        var aggregator = ObservationAggregator.observationAggregator();
        var emitted = aggregator.onObservation(SELF, faulty(TARGET), 3, 0L);
        assertThat(emitted.isPresent())
                .as("Single FAULTY observer emits DECOMMISSIONED (single-witness, regardless of cluster size)")
                .isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_singleNodeCluster_thresholdFloorsToOne() {
        // Cluster of 1 on-duty → threshold = 1 (floor). Self observation alone
        // advances lifecycle, preserving solo-cluster / pre-quorum-bootstrap
        // behaviour.
        var aggregator = ObservationAggregator.observationAggregator();
        var emitted = aggregator.onObservation(SELF, healthy(TARGET), 1, 0L);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.ON_DUTY);
    }

    @Test
    void aggregator_repeatedSameStateObservations_emitsOnceOnly() {
        // Idempotence is enforced by `lastAggregated` state. Once an edge is
        // emitted, subsequent observations of the same state from any observer
        // do not re-emit.
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        aggregator.onObservation(SELF, healthy(TARGET), 3, t0);
        var first = aggregator.onObservation(OBS_A, healthy(TARGET), 3, t0);
        assertThat(first.isPresent()).isTrue();
        var second = aggregator.onObservation(OBS_B, healthy(TARGET), 3, t0);
        assertThat(second.isEmpty()).isTrue();
        var third = aggregator.onObservation(OBS_C, healthy(TARGET), 3, t0);
        assertThat(third.isEmpty()).isTrue();
    }

    @Test
    void aggregator_pendingObservationsExpire_afterAggregationWindow() {
        // Pending sub-threshold observations are evicted once they age past
        // `aggregationWindow`. A subsequent observation cannot collude with
        // expired ones to fabricate a majority. Test exercised against HEALTHY
        // (ON_DUTY) because DECOMMISSIONED is single-witness and would emit
        // immediately — no pending state to expire.
        var aggregator = ObservationAggregator.observationAggregator(timeSpan(1).seconds());
        // 5-node cluster, ON_DUTY threshold = 3. Two observers report HEALTHY at
        // t0 — pending, no edge.
        assertThat(aggregator.onObservation(SELF, healthy(TARGET), 5, 0L).isEmpty()).isTrue();
        assertThat(aggregator.onObservation(OBS_A, healthy(TARGET), 5, 0L).isEmpty()).isTrue();
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.ON_DUTY)).isEqualTo(2);
        // After 5s (5x window), a third observer reports HEALTHY. The two stale
        // entries have been evicted; only the fresh observation counts → still
        // below threshold, no edge.
        var emitted = aggregator.onObservation(OBS_B, healthy(TARGET), 5, 5_000L);
        assertThat(emitted.isEmpty())
                .as("Stale entries evicted; fresh single observation cannot reach majority alone")
                .isTrue();
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.ON_DUTY))
                .as("Only the fresh OBS_B observation remains; SELF + OBS_A were evicted")
                .isEqualTo(1);
    }

    @Test
    void aggregator_majorityWithinWindow_emitsEdge() {
        // Boundary case: three observers within `aggregationWindow` reach
        // majority; the time-spread does not defeat the tally as long as no
        // entry has aged past the window edge. Exercised against HEALTHY
        // (ON_DUTY) — DECOMMISSIONED is single-witness and would emit on the
        // first observation, bypassing the windowing logic under test here.
        var aggregator = ObservationAggregator.observationAggregator(timeSpan(10).seconds());
        assertThat(aggregator.onObservation(SELF, healthy(TARGET), 5, 0L).isEmpty()).isTrue();
        assertThat(aggregator.onObservation(OBS_A, healthy(TARGET), 5, 3_000L).isEmpty()).isTrue();
        var emitted = aggregator.onObservation(OBS_B, healthy(TARGET), 5, 8_000L);
        assertThat(emitted.isPresent())
                .as("All three entries still within the 10s window — majority reached")
                .isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.ON_DUTY);
    }

    @Test
    void aggregator_emitsDecommissionedEdge_evenWhenNeverHealthy() {
        // Contract: aggregator does not gate on prior HEALTHY history.
        // Cold-boot suppression lives upstream (`SwimProtocol.emitFaultyOrUnknown`
        // gates by phase; `HealthReconcilerImpl.suppressedByPhase` gates the
        // write while COLD_BOOT). Under single-witness DECOMMISSIONED, a single
        // FAULTY observer emits regardless of prior HEALTHY history.
        var aggregator = ObservationAggregator.observationAggregator();
        aggregator.onObservation(SELF, unknown(TARGET), 5, 0L);
        assertThat(aggregator.everSeenHealthy(TARGET))
                .as("Precondition: target has never been observed HEALTHY")
                .isFalse();
        var emitted = aggregator.onObservation(SELF, faulty(TARGET), 5, 0L);
        assertThat(emitted.isPresent())
                .as("Single FAULTY observer emits DECOMMISSIONED regardless of prior HEALTHY history")
                .isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_departedObservation_promotesToDecommissioned() {
        // DEPARTED observations translate to DECOMMISSIONED; single-witness rule
        // applies — the first DEPARTED observer emits immediately.
        var aggregator = ObservationAggregator.observationAggregator();
        var emitted = aggregator.onObservation(SELF, departed(TARGET), 3, 0L);
        assertThat(emitted.isPresent())
                .as("First DEPARTED observer emits DECOMMISSIONED (single-witness)")
                .isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_observerChangesMind_replacesPriorObservation() {
        // An observer flipping its observation replaces (does not duplicate) its
        // prior entry. Tally never double-counts the same observer. Note: under
        // single-witness DECOMMISSIONED, OBS_A's FAULTY observation at the
        // second call fires the edge immediately.
        var aggregator = ObservationAggregator.observationAggregator();
        aggregator.onObservation(SELF, healthy(TARGET), 5, 0L);
        aggregator.onObservation(OBS_A, healthy(TARGET), 5, 0L);
        var emitted = aggregator.onObservation(OBS_A, faulty(TARGET), 5, 100L);
        assertThat(emitted.isPresent())
                .as("OBS_A's flip to FAULTY emits DECOMMISSIONED via single-witness threshold")
                .isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
        // Observer counts: SELF still HEALTHY, OBS_A replaced (now FAULTY → DECOMMISSIONED).
        assertThat(aggregator.observerCount(TARGET)).isEqualTo(2);
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.ON_DUTY)).isEqualTo(1);
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.DECOMMISSIONED)).isEqualTo(1);
    }

    @Test
    void aggregator_subThresholdObservations_stayPendingUntilSeconded() {
        // Pending-window contract: an observation below threshold does NOT
        // produce an edge, but is retained for re-evaluation. A later confirming
        // observation can lift the pending tally over the threshold. Exercised
        // against HEALTHY (ON_DUTY majority) — DECOMMISSIONED is single-witness
        // and never stays pending.
        var aggregator = ObservationAggregator.observationAggregator();
        // 5-node cluster, ON_DUTY threshold = 3. First two observers pending.
        assertThat(aggregator.onObservation(SELF, healthy(TARGET), 5, 0L).isEmpty()).isTrue();
        assertThat(aggregator.onObservation(OBS_A, healthy(TARGET), 5, 1_000L).isEmpty()).isTrue();
        // Third observer arrives within the window — emits ON_DUTY.
        var emitted = aggregator.onObservation(OBS_B, healthy(TARGET), 5, 2_000L);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.ON_DUTY);
        // Subsequent observers do not re-emit (idempotence).
        var fourth = aggregator.onObservation(OBS_C, healthy(TARGET), 5, 3_000L);
        assertThat(fourth.isEmpty()).isTrue();
        var fifth = aggregator.onObservation(OBS_D, healthy(TARGET), 5, 4_000L);
        assertThat(fifth.isEmpty()).isTrue();
    }

    @Test
    void aggregator_faultyObserved_emitsImmediately_singleWitness_bypass() {
        // Contract lock: DECOMMISSIONED uses single-witness threshold and emits
        // on the first FAULTY observation, then is idempotent against further
        // FAULTY from other observers.
        //
        // WHY single-witness: the aggregator instance receives observations only
        // from its local SWIM detector — `HealthReconcilerImpl.aggregateEdge`
        // always tags the observer as `self`. Cross-node SWIM observation
        // propagation is not yet wired. Under a majority threshold of 3 on a
        // 5-node cluster (where ON_DUTY requires 3 distinct observers), the
        // aggregator would never see more than 1 distinct observer and
        // DECOMMISSIONED would be unreachable. The leader's lifecycle write is
        // consensus-replicated, so cluster-wide agreement is preserved despite
        // single-witness emission here.
        var aggregator = ObservationAggregator.observationAggregator();
        // 5-node cluster: ON_DUTY would need 3 distinct observers. FAULTY must
        // bypass that and emit on first witness.
        var first = aggregator.onObservation(SELF, faulty(TARGET), 5, 0L);
        assertThat(first.isPresent())
                .as("Single FAULTY observer on 5-node cluster emits DECOMMISSIONED (single-witness bypass)")
                .isTrue();
        assertThat(first.unwrap().target()).isEqualTo(TARGET);
        assertThat(first.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
        // Subsequent FAULTY from another observer must NOT re-emit (idempotence).
        var second = aggregator.onObservation(OBS_A, faulty(TARGET), 5, 100L);
        assertThat(second.isEmpty())
                .as("Subsequent FAULTY from a different observer must not re-emit (idempotence)")
                .isTrue();
    }
}
