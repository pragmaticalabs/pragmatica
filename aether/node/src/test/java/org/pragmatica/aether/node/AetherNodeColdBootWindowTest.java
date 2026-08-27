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
///
/// ## What #642 changed, and what is NOT covered here
/// #642 moved the window's ANCHOR from assembly time to {@code start()}: {@code assembleNode} seeds an
/// {@code AtomicLong} and {@code start()} re-stamps it, so the window covers a node's first
/// {@code COLD_BOOT_CONVERGENCE_WINDOW_MS} of RUNNING life instead of of existence. Anchored at
/// assembly, a node held back longer than the window — routine in a staggered-restart harness — began
/// running with the suppression already spent.
///
/// The predicate below is unchanged by that fix and is what these tests cover. **The fix itself — that
/// {@code start()} really re-stamps the anchor — is NOT unit-tested and cannot be**: the holder is a
/// local of the ~2900-line {@code assembleNode} and the write happens inside the assembled node's
/// {@code start()}. Its only coverage is the forge/Ember gate. A unit test here could assert nothing
/// but its own arrangement, so there deliberately is not one [design intent — unverified].
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

    @Test
    void coldBootConvergenceActive_windowConstant_matchesTheOneWiringUses() {
        // The cases above run against a local copy of the window. Pin it to the production constant so
        // they cannot silently drift into testing a number the node no longer uses.
        assertThat(WINDOW_MS).isEqualTo(AetherNode.COLD_BOOT_CONVERGENCE_WINDOW_MS);
    }
}
