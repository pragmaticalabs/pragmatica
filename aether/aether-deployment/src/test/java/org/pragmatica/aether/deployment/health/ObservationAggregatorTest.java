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
    void aggregator_kOfNHealthyObservations_emitsOnDutyEdge() {
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        // 3 of 5 → quorum reached
        aggregator.onObservation(SELF, healthy(TARGET), 5, t0);
        aggregator.onObservation(OBS_A, healthy(TARGET), 5, t0);
        var emitted = aggregator.onObservation(OBS_B, healthy(TARGET), 5, t0);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().target()).isEqualTo(TARGET);
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.ON_DUTY);
    }

    @Test
    void aggregator_kOfNFaultyObservations_emitsFaultyEdge() {
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        // First promote target (so cold-boot honor doesn't suppress FAULTY)
        aggregator.onObservation(SELF, healthy(TARGET), 5, t0);
        aggregator.onObservation(OBS_A, healthy(TARGET), 5, t0);
        aggregator.onObservation(OBS_B, healthy(TARGET), 5, t0);
        // Now flip 3 of 5 to FAULTY
        aggregator.onObservation(SELF, faulty(TARGET), 5, t0 + 100);
        aggregator.onObservation(OBS_A, faulty(TARGET), 5, t0 + 100);
        var emitted = aggregator.onObservation(OBS_B, faulty(TARGET), 5, t0 + 100);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
    }

    @Test
    void aggregator_belowQuorumObservations_emitsNothing() {
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        // Only 2 of 5 → below k=3
        aggregator.onObservation(SELF, healthy(TARGET), 5, t0);
        var emitted = aggregator.onObservation(OBS_A, healthy(TARGET), 5, t0);
        assertThat(emitted.isEmpty()).isTrue();
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
    void aggregator_idempotentSameState_noEdgeEmitted() {
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        aggregator.onObservation(SELF, healthy(TARGET), 5, t0);
        aggregator.onObservation(OBS_A, healthy(TARGET), 5, t0);
        // First quorum-crossing: emits
        var first = aggregator.onObservation(OBS_B, healthy(TARGET), 5, t0);
        assertThat(first.isPresent()).isTrue();
        // Same state — no new edge
        var second = aggregator.onObservation(OBS_C, healthy(TARGET), 5, t0 + 100);
        assertThat(second.isEmpty()).isTrue();
    }

    @Test
    void aggregator_unknownOnly_neverEmitsFaulty() {
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        // Only UnknownObserved + FaultyObserved — never HEALTHY
        aggregator.onObservation(SELF, unknown(TARGET), 5, t0);
        aggregator.onObservation(OBS_A, faulty(TARGET), 5, t0);
        aggregator.onObservation(OBS_B, faulty(TARGET), 5, t0);
        var emitted = aggregator.onObservation(OBS_C, faulty(TARGET), 5, t0);
        // Cold-boot: even quorum FAULTY does not promote because target was never HEALTHY
        assertThat(emitted.isEmpty()).isTrue();
    }

    @Test
    void aggregator_selfObservationCounted() {
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        // Cluster of 3 → k = 2; self + one peer == 2 distinct observers
        aggregator.onObservation(SELF, healthy(TARGET), 3, t0);
        var emitted = aggregator.onObservation(OBS_A, healthy(TARGET), 3, t0);
        assertThat(emitted.isPresent()).isTrue();
        assertThat(emitted.unwrap().newState()).isEqualTo(NodeLifecycleState.ON_DUTY);
    }

    @Test
    void aggregator_departedObservation_promotesToDecommissioned() {
        var aggregator = ObservationAggregator.observationAggregator();
        var t0 = 0L;
        // First reach HEALTHY so cold-boot honor allows DECOMMISSIONED
        aggregator.onObservation(SELF, healthy(TARGET), 3, t0);
        aggregator.onObservation(OBS_A, healthy(TARGET), 3, t0);
        // Now 2 of 3 observers report DEPARTED
        aggregator.onObservation(SELF, departed(TARGET), 3, t0 + 100);
        var emitted = aggregator.onObservation(OBS_A, departed(TARGET), 3, t0 + 100);
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
