// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.timeout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Unit tests for `TimeoutMetricsRegistry` — the per-subsystem timeout-fired
/// counter store. Covers initial-zero contract, per-subsystem isolation
/// (incrementing one subsystem does not bleed into siblings), accumulation,
/// and snapshot shape (every `TimeoutSubsystem` value present).
class TimeoutMetricsRegistryTest {

    @Test
    void snapshot_initial_allZero() {
        var registry = TimeoutMetricsRegistry.timeoutMetricsRegistry();

        var snapshot = registry.snapshot();

        assertEquals(TimeoutSubsystem.values().length, snapshot.size(),
                      "Snapshot must contain every subsystem enum value");
        for (var subsystem : TimeoutSubsystem.values()) {
            assertEquals(0L, snapshot.get(subsystem),
                          "Counter for " + subsystem + " must start at 0");
        }
    }

    @Test
    void recordTimeout_incrementsTargetSubsystem_only() {
        var registry = TimeoutMetricsRegistry.timeoutMetricsRegistry();

        registry.recordTimeout(TimeoutSubsystem.CONSENSUS);

        assertEquals(1L, registry.firedCount(TimeoutSubsystem.CONSENSUS));
        assertEquals(0L, registry.firedCount(TimeoutSubsystem.DHT),
                      "Sibling subsystem must remain at 0 after a CONSENSUS increment");
        assertEquals(0L, registry.firedCount(TimeoutSubsystem.SWIM));
    }

    @Test
    void recordTimeout_accumulates_acrossMultipleCalls() {
        var registry = TimeoutMetricsRegistry.timeoutMetricsRegistry();

        for (int i = 0; i < 7; i++) {
            registry.recordTimeout(TimeoutSubsystem.DHT);
        }

        assertEquals(7L, registry.firedCount(TimeoutSubsystem.DHT));
        assertEquals(7L, registry.snapshot().get(TimeoutSubsystem.DHT));
    }

    @Test
    void snapshot_isPointInTime_doesNotMutateOnLaterIncrement() {
        var registry = TimeoutMetricsRegistry.timeoutMetricsRegistry();
        registry.recordTimeout(TimeoutSubsystem.CLUSTER);

        var snapshot = registry.snapshot();
        registry.recordTimeout(TimeoutSubsystem.CLUSTER);

        assertEquals(1L, snapshot.get(TimeoutSubsystem.CLUSTER),
                      "Snapshot must remain at the value captured when it was taken");
        assertEquals(2L, registry.firedCount(TimeoutSubsystem.CLUSTER),
                      "Live counter must reflect the post-snapshot increment");
    }

    @Test
    void snapshot_isImmutable() {
        var registry = TimeoutMetricsRegistry.timeoutMetricsRegistry();
        var snapshot = registry.snapshot();

        assertThat(snapshot)
            .as("Snapshot map must be immutable to prevent caller mutation leaking into shared state")
            .isUnmodifiable();
    }

    @Test
    void allSubsystems_havePresent_lowercaseIds() {
        for (var subsystem : TimeoutSubsystem.values()) {
            assertThat(subsystem.id())
                .as("Subsystem %s must have a non-blank id()", subsystem)
                .isNotBlank();
        }
    }
}
