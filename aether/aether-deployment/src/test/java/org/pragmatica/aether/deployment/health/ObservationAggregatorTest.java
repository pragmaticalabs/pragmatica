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


/// Contract: cross-node majority-quorum aggregator.
///
/// **Threshold** — `(onDutyCount / 2) + 1`, floored at 1. Single-witness mode
/// (threshold = 1 for clusters of 1 or pre-quorum bootstrap) is preserved so
/// solo / sub-quorum clusters can still advance lifecycle. For N >= 2 the
/// aggregator requires majority agreement among distinct observers within the
/// aggregation window before emitting a `StateChanged` edge.
///
/// **Pending semantics** — observations below threshold remain in the per-target
/// sliding window. Each new observation re-evaluates the tally. Observations
/// older than `aggregationWindowMs` are evicted on the next call to
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
        // Cluster of 5 on-duty → threshold = 3. A single observer's HEALTHY
        // observation must NOT emit a StateChanged edge; the observation stays
        // pending in the per-target window awaiting majority confirmation.
        var aggregator = ObservationAggregator.observationAggregator();
        var emitted = aggregator.onObservation(SELF, healthy(TARGET), 5, 0L);
        assertThat(emitted.isEmpty())
                .as("Single observer on 5-node cluster must NOT cross majority threshold (3)")
                .isTrue();
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.ON_DUTY))
                .as("Observation is retained as pending")
                .isEqualTo(1);
    }

    @Test
    void aggregator_majorityHealthy_emitsOnDutyEdge() {
        // Cluster of 5 on-duty → threshold = 3. Three distinct observers reporting
        // HEALTHY for the same target cross the threshold and emit ON_DUTY.
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
        // Cluster of 5 on-duty → threshold = 3. Three distinct observers reporting
        // FAULTY for the same target cross the threshold and emit DECOMMISSIONED.
        var aggregator = ObservationAggregator.observationAggregator();
        aggregator.onObservation(SELF, faulty(TARGET), 5, 0L);
        aggregator.onObservation(OBS_A, faulty(TARGET), 5, 0L);
        var emitted = aggregator.onObservation(OBS_B, faulty(TARGET), 5, 0L);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().target()).isEqualTo(TARGET);
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_threeNodeCluster_majorityIsTwo() {
        // Cluster of 3 on-duty → threshold = 2. Two FAULTY observers cross the
        // threshold; a single observation does not.
        var aggregator = ObservationAggregator.observationAggregator();
        assertThat(aggregator.onObservation(SELF, faulty(TARGET), 3, 0L).isEmpty()).isTrue();
        var emitted = aggregator.onObservation(OBS_A, faulty(TARGET), 3, 0L);
        assertThat(emitted.isPresent()).isTrue();
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
        // `aggregationWindowMs`. A subsequent observation cannot collude with
        // expired ones to fabricate a majority.
        var aggregator = ObservationAggregator.observationAggregator(1_000L);
        // 5-node cluster, threshold = 3. Two observers report FAULTY at t0 —
        // pending, no edge.
        assertThat(aggregator.onObservation(SELF, faulty(TARGET), 5, 0L).isEmpty()).isTrue();
        assertThat(aggregator.onObservation(OBS_A, faulty(TARGET), 5, 0L).isEmpty()).isTrue();
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.DECOMMISSIONED)).isEqualTo(2);
        // After 5s (5x window), a third observer reports FAULTY. The two stale
        // entries have been evicted; only the fresh observation counts → still
        // below threshold, no edge.
        var emitted = aggregator.onObservation(OBS_B, faulty(TARGET), 5, 5_000L);
        assertThat(emitted.isEmpty())
                .as("Stale entries evicted; fresh single observation cannot reach majority alone")
                .isTrue();
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.DECOMMISSIONED))
                .as("Only the fresh OBS_B observation remains; SELF + OBS_A were evicted")
                .isEqualTo(1);
    }

    @Test
    void aggregator_majorityWithinWindow_emitsEdge() {
        // Boundary case: three observers within `aggregationWindowMs` reach
        // majority; the time-spread does not defeat the tally as long as no
        // entry has aged past the window edge.
        var aggregator = ObservationAggregator.observationAggregator(10_000L);
        assertThat(aggregator.onObservation(SELF, faulty(TARGET), 5, 0L).isEmpty()).isTrue();
        assertThat(aggregator.onObservation(OBS_A, faulty(TARGET), 5, 3_000L).isEmpty()).isTrue();
        var emitted = aggregator.onObservation(OBS_B, faulty(TARGET), 5, 8_000L);
        assertThat(emitted.isPresent())
                .as("All three entries still within the 10s window — majority reached")
                .isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_emitsDecommissionedEdge_evenWhenNeverHealthy() {
        // Contract: aggregator does not gate on prior HEALTHY history.
        // Cold-boot suppression lives upstream (`SwimProtocol.emitFaultyOrUnknown`
        // gates by phase; `HealthReconcilerImpl.suppressedByPhase` gates the
        // write while BOOTING). Once majority is reached, the aggregator emits.
        var aggregator = ObservationAggregator.observationAggregator();
        aggregator.onObservation(SELF, unknown(TARGET), 5, 0L);
        assertThat(aggregator.everSeenHealthy(TARGET))
                .as("Precondition: target has never been observed HEALTHY")
                .isFalse();
        aggregator.onObservation(SELF, faulty(TARGET), 5, 0L);
        aggregator.onObservation(OBS_A, faulty(TARGET), 5, 0L);
        var emitted = aggregator.onObservation(OBS_B, faulty(TARGET), 5, 0L);
        assertThat(emitted.isPresent())
                .as("Majority FAULTY observers emit DECOMMISSIONED regardless of prior HEALTHY history")
                .isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_departedObservation_promotesToDecommissioned() {
        // DEPARTED observations translate to DECOMMISSIONED; same majority rule.
        var aggregator = ObservationAggregator.observationAggregator();
        aggregator.onObservation(SELF, departed(TARGET), 3, 0L);
        var emitted = aggregator.onObservation(OBS_A, departed(TARGET), 3, 100L);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_observerChangesMind_replacesPriorObservation() {
        // An observer flipping its observation replaces (does not duplicate) its
        // prior entry. Tally never double-counts the same observer.
        var aggregator = ObservationAggregator.observationAggregator();
        aggregator.onObservation(SELF, healthy(TARGET), 5, 0L);
        aggregator.onObservation(OBS_A, healthy(TARGET), 5, 0L);
        aggregator.onObservation(OBS_A, faulty(TARGET), 5, 100L);
        // SELF healthy, OBS_A faulty — neither state has majority on 5-node cluster.
        assertThat(aggregator.observerCount(TARGET)).isEqualTo(2);
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.ON_DUTY)).isEqualTo(1);
        assertThat(aggregator.observerCount(TARGET, NodeLifecycleState.DECOMMISSIONED)).isEqualTo(1);
    }

    @Test
    void aggregator_subThresholdObservations_stayPendingUntilSeconded() {
        // Pending-window contract: an observation below threshold does NOT
        // produce an edge, but is retained for re-evaluation. A later
        // confirming observation can lift the pending tally over the threshold.
        var aggregator = ObservationAggregator.observationAggregator();
        // 5-node cluster, threshold = 3. First two observers pending.
        assertThat(aggregator.onObservation(SELF, faulty(TARGET), 5, 0L).isEmpty()).isTrue();
        assertThat(aggregator.onObservation(OBS_A, faulty(TARGET), 5, 1_000L).isEmpty()).isTrue();
        // Third observer arrives within the window — emits DECOMMISSIONED.
        var emitted = aggregator.onObservation(OBS_B, faulty(TARGET), 5, 2_000L);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
        // Subsequent observers do not re-emit (idempotence).
        var fourth = aggregator.onObservation(OBS_C, faulty(TARGET), 5, 3_000L);
        assertThat(fourth.isEmpty()).isTrue();
        var fifth = aggregator.onObservation(OBS_D, faulty(TARGET), 5, 4_000L);
        assertThat(fifth.isEmpty()).isTrue();
    }
}
