// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The bounds the node table names (spec R-3.4.3, R-3.4.4, R-1.6.3, N19), as literals. The behaviour tests derive their
/// boundaries from `NodeFsmTimings`, so a changed default moves them with it; this test is what pins the defaults themselves.
class NodeFsmTimingsTest {
    /// Red when: a default drifts from the number the spec names.
    @Test
    @DisplayName("R-3.4.3/R-3.4.4/R-1.6.3/N19: the default bounds are the spec's numbers")
    void defaults_areTheSpecNumbers() {
        var timings = NodeFsmTimings.nodeFsmTimings();

        assertThat(timings.loadMs()).as("N1: LocalOp(load, 120 s)").isEqualTo(120_000L);
        assertThat(timings.activateMs()).as("N4: activate (60 s)").isEqualTo(60_000L);
        assertThat(timings.chainBoundMs()).as("N4: bounded at 120 s").isEqualTo(120_000L);
        assertThat(timings.deactivateMs()).as("N13: deactivate (30 s)").isEqualTo(30_000L);
        assertThat(timings.unloadMs()).as("N16: unload (120 s)").isEqualTo(120_000L);
        assertThat(timings.activatingCheckMs()).as("N7: activatingCheck at 90 s").isEqualTo(90_000L);
        assertThat(timings.tombstoneGraceMs()).as("R-1.6.3: tombstoneGrace 120 s").isEqualTo(120_000L);
        assertThat(timings.writeAttempts()).as("R-3.4.4: 3 attempts").isEqualTo(3);
        assertThat(timings.writeAttemptMs()).as("R-3.4.4: of 30 s").isEqualTo(30_000L);
        assertThat(timings.failedRetryMs()).as("N19: every 2 s").isEqualTo(2_000L);
        assertThat(timings.failedCycles()).as("N19 / B-150: 6 cycles [guess]").isEqualTo(6);
        assertThat(timings.parkedBoundMs()).as("B-158: the LOADING timeout").isEqualTo(120_000L);
    }
}
