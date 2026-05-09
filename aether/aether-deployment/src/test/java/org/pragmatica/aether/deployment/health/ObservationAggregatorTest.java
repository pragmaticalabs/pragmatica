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


class ObservationAggregatorTest {
    private static final NodeId SELF = nodeId("self").unwrap();
    private static final NodeId OBS_A = nodeId("obs-a").unwrap();
    private static final NodeId OBS_B = nodeId("obs-b").unwrap();
    private static final NodeId OBS_C = nodeId("obs-c").unwrap();
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
    void aggregator_singleObserverHealthy_emitsOnDutyEdge() {
        // Single-observer mode: leader's local SWIM observation alone is sufficient.
        // First HEALTHY observation emits ON_DUTY edge.
        var aggregator = ObservationAggregator.observationAggregator();
        var emitted = aggregator.onObservation(SELF, healthy(TARGET), 5, 0L);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().target()).isEqualTo(TARGET);
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.ON_DUTY);
    }

    @Test
    void aggregator_singleObserverFaulty_emitsDecommissionedEdge() {
        // Single-observer mode: after target was previously HEALTHY, a single FAULTY
        // observation emits DECOMMISSIONED edge.
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        // First promote target so cold-boot honor allows DECOMMISSIONED
        aggregator.onObservation(SELF, healthy(TARGET), 5, t0);
        // Now a single FAULTY observation crosses the threshold
        var emitted = aggregator.onObservation(SELF, faulty(TARGET), 5, t0 + 100);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().target()).isEqualTo(TARGET);
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_repeatedSameStateObservations_emitsOnceOnly() {
        // Single-observer mode: idempotence is enforced by lastAggregated state, not by
        // a k-of-n threshold. First observation emits; subsequent observations of the
        // same state from any observer do not emit.
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        var first = aggregator.onObservation(SELF, healthy(TARGET), 5, t0);
        assertThat(first.isPresent()).isTrue();
        var second = aggregator.onObservation(OBS_A, healthy(TARGET), 5, t0);
        assertThat(second.isEmpty()).isTrue();
    }

    @Test
    void aggregator_staleObservationsEvicted_afterWindow() {
        var aggregator = ObservationAggregator.observationAggregator(1_000L);
        // Fill with HEALTHY at t0
        aggregator.onObservation(SELF, healthy(TARGET), 5, 0L);
        aggregator.onObservation(OBS_A, healthy(TARGET), 5, 0L);
        aggregator.onObservation(OBS_B, healthy(TARGET), 5, 0L);
        // Confirm ON_DUTY achieved, distinct count == 3
        assertThat(aggregator.observerCount(TARGET)).isEqualTo(3);
        // Move time past window — observations should be evicted
        aggregator.onObservation(OBS_C, unknown(TARGET), 5, 5_000L);
        // Now no entries (UnknownObserved doesn't add an entry, only triggers eviction)
        assertThat(aggregator.observerCount(TARGET)).isEqualTo(0);
    }

    @Test
    void aggregator_emitsDecommissionedEdge_evenWhenNeverHealthy() {
        // Contract change (commit 81e48e234, "drop respectColdBoot suppression"):
        // The aggregator no longer suppresses FAULTY edges for targets that have never
        // been observed HEALTHY. Cold-boot gating now lives upstream — SwimProtocol's
        // emitFaultyOrUnknown gates by phase, and HealthReconcilerImpl.suppressedByPhase
        // gates the actual lifecycle write while in BOOTING.
        // The aggregator's job is purely to collapse SWIM observations into edges; it
        // emits the first FAULTY edge it sees regardless of history. This test pins the
        // new contract: no everSeenHealthy filtering inside the aggregator.
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        // Seed UNKNOWN (does not record an entry; only triggers eviction).
        aggregator.onObservation(SELF, unknown(TARGET), 5, t0);
        assertThat(aggregator.everSeenHealthy(TARGET))
                .as("Precondition: target has never been observed HEALTHY")
                .isFalse();
        // First FAULTY observation crosses the single-observer threshold and emits the edge.
        var emitted = aggregator.onObservation(OBS_A, faulty(TARGET), 5, t0);
        assertThat(emitted.isPresent())
                .as("Aggregator emits DECOMMISSIONED edge even when target was never HEALTHY")
                .isTrue();
        assertThat(emitted.unwrap().target()).isEqualTo(TARGET);
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
        // Subsequent FAULTY observations do not re-emit (idempotence via lastAggregated).
        var second = aggregator.onObservation(OBS_B, faulty(TARGET), 5, t0);
        assertThat(second.isEmpty()).isTrue();
        var third = aggregator.onObservation(OBS_C, faulty(TARGET), 5, t0);
        assertThat(third.isEmpty()).isTrue();
    }

    @Test
    void aggregator_selfObservationCounted() {
        // Single-observer mode: self's own observation alone reaches the threshold.
        var aggregator = ObservationAggregator.observationAggregator();
        var emitted = aggregator.onObservation(SELF, healthy(TARGET), 3, 0L);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.ON_DUTY);
    }

    @Test
    void aggregator_departedObservation_promotesToDecommissioned() {
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        // First reach HEALTHY so cold-boot honor allows DECOMMISSIONED
        aggregator.onObservation(SELF, healthy(TARGET), 3, t0);
        // A single DEPARTED observation now emits DECOMMISSIONED
        var emitted = aggregator.onObservation(SELF, departed(TARGET), 3, t0 + 100);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_observerChangesMind_replacesPriorObservation() {
        var aggregator = ObservationAggregator.observationAggregator();
        // Promote first
        aggregator.onObservation(SELF, healthy(TARGET), 3, 0L);
        aggregator.onObservation(OBS_A, healthy(TARGET), 3, 0L);
        // OBS_A flips to FAULTY — replaces, not adds
        aggregator.onObservation(OBS_A, faulty(TARGET), 3, 100L);
        // Distinct observers still 2 (SELF healthy, OBS_A faulty)
        assertThat(aggregator.observerCount(TARGET)).isEqualTo(2);
    }
}
