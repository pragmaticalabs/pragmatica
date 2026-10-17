// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import static org.assertj.core.api.Assertions.assertThat;

class GovernorCandidateHealthTest {
    @Test void assignmentAndBoundedDirectProbe_areRequiredBeforeEligibility() {
        var candidate = new NodeId("candidate");
        var other = new NodeId("other");
        var assignments = new HashMap<>(Map.of(candidate, "c", other, "c"));
        var clock = new AtomicLong(100);
        var health = GovernorCandidateHealth.governorCandidateHealth(node -> Option.option(assignments.get(node)), clock::get, org.pragmatica.lang.io.TimeSpan.timeSpan(100).millis(), 1);
        var observation = new MetricObservation(1, 1, System.currentTimeMillis(), Map.of());
        assertThat(health.recordPong(candidate, "READY", observation)).isFalse();
        assertThat(health.request(candidate, "wrong")).isFalse();
        assertThat(health.request(candidate, "c")).isTrue();
        assertThat(health.request(candidate, "c")).isFalse();
        assertThat(health.request(other, "c")).isFalse();
        assertThat(health.recordPong(candidate, "DRAINING", observation)).isFalse();
        assertThat(health.recordPong(candidate, "READY", observation)).isTrue();
        assertThat(health.isEligible(candidate)).isTrue();
        clock.addAndGet(100_000_000);
        assertThat(health.isEligible(candidate)).isFalse();
        assertThat(health.request(candidate, "c")).isTrue();
        assertThat(health.recordPong(candidate, "READY", observation)).isFalse();
        assertThat(health.recordPong(candidate, "READY", new MetricObservation(1, 2, System.currentTimeMillis(), Map.of()))).isTrue();
        assignments.put(candidate, "other");
        assertThat(health.isEligible(candidate)).isFalse();
    }
}
