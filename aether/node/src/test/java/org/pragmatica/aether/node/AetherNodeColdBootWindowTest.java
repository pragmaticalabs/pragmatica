// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// A6 cold-boot convergence window. The SWIM cold-boot FAULTY-suppression gate
/// ({@code swimIsBootingSupplier}) must stay active for a bounded window after THIS node's boot even
/// once the cluster phase has flipped out of {@code COLD_BOOT} (which happens at first quorum, e.g.
/// 3/5). Without it, on a simultaneous full-cluster restart a straggler whose QUIC link forms only via
/// the transport's 60s force-dial would be FAULTY-evicted (then terminally REMOVED) before it can
/// connect, wedging the cluster below full membership. These tests pin the pure window predicate.
class AetherNodeColdBootWindowTest {
    private static final long WINDOW_MS = 75_000L;

    @Test
    void coldBootConvergenceActive_phaseColdBoot_active_regardlessOfElapsed() {
        // COLD_BOOT phase: always suppressing, even long past the window.
        assertThat(AetherNode.coldBootConvergenceActive(true, 0L, 10_000_000L, WINDOW_MS)).isTrue();
    }

    @Test
    void coldBootConvergenceActive_phaseNormal_withinWindow_active() {
        // Phase flipped to NORMAL (first quorum) but still within this node's boot window: keep suppressing.
        assertThat(AetherNode.coldBootConvergenceActive(false, 1_000L, 1_000L + WINDOW_MS - 1, WINDOW_MS)).isTrue();
    }

    @Test
    void coldBootConvergenceActive_phaseNormal_atWindowBoundary_inactive() {
        // Exactly at the window edge the suppression has ended (strict less-than).
        assertThat(AetherNode.coldBootConvergenceActive(false, 1_000L, 1_000L + WINDOW_MS, WINDOW_MS)).isFalse();
    }

    @Test
    void coldBootConvergenceActive_phaseNormal_afterWindow_inactive() {
        // Past the window: steady-state detection resumes (never-HEALTHY peers may FAULTY normally).
        assertThat(AetherNode.coldBootConvergenceActive(false, 1_000L, 1_000L + WINDOW_MS + 1, WINDOW_MS)).isFalse();
    }
}
