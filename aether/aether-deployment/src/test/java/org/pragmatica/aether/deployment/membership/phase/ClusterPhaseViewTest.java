// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.phase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;


/// Unit tests for the `ClusterPhaseView` adapter. Phase is derived from two live signals —
/// in-quorum and have-leader — with a one-way `everQuorate` latch suppressing `COLD_BOOT`
/// once quorum is first reached. `NORMAL` requires both quorum and a leader; otherwise the
/// post-quorate phase is `RECOVERING`.
class ClusterPhaseViewTest {
    @Nested @DisplayName("Phase derivation") class PhaseDerivation {
        @Test void compute_beforeFirstQuorum_returnsColdBoot() {
            var view = view(() -> false, () -> false);
            assertThat(view.compute()).isEqualTo(ClusterPhase.COLD_BOOT);
        }

        @Test void compute_beforeFirstQuorum_withLeader_stillColdBoot() {
            var view = view(() -> false, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.COLD_BOOT);
        }

        @Test void compute_inQuorum_withLeader_returnsNormal() {
            var view = view(() -> true, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.NORMAL);
        }

        @Test void compute_inQuorum_noLeader_returnsRecovering() {
            var view = view(() -> true, () -> false);
            assertThat(view.compute()).isEqualTo(ClusterPhase.RECOVERING);
        }
    }

    @Nested @DisplayName("everQuorate latch") class QuorateLatch {
        @Test void compute_afterFirstQuorum_lostQuorum_returnsRecovering_notColdBoot() {
            var inQuorum = new AtomicBoolean(true);
            var view = view(inQuorum::get, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.NORMAL);

            inQuorum.set(false);

            assertThat(view.compute()).isEqualTo(ClusterPhase.RECOVERING);
        }

        @Test void compute_afterFirstQuorum_lostLeader_returnsRecovering() {
            var haveLeader = new AtomicBoolean(true);
            var view = view(() -> true, haveLeader::get);
            assertThat(view.compute()).isEqualTo(ClusterPhase.NORMAL);

            haveLeader.set(false);

            assertThat(view.compute()).isEqualTo(ClusterPhase.RECOVERING);
        }

        @Test void compute_latchNeverResets_recoversToNormalWhenSignalsReturn() {
            var inQuorum = new AtomicBoolean(true);
            var haveLeader = new AtomicBoolean(true);
            var view = view(inQuorum::get, haveLeader::get);
            assertThat(view.compute()).isEqualTo(ClusterPhase.NORMAL);

            inQuorum.set(false);
            assertThat(view.compute()).isEqualTo(ClusterPhase.RECOVERING);

            inQuorum.set(true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.NORMAL);
        }
    }

    /// SWIM consumer path: cold-boot suppression gate fires only when the view returns
    /// COLD_BOOT. Models `swimIsBootingSupplier = () -> view.compute() == COLD_BOOT`.
    @Nested @DisplayName("SWIM cold-boot suppression integration") class SwimGateBehaviour {
        @Test void swimGate_beforeQuorum_suppressesFaulty() {
            var view = view(() -> false, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.COLD_BOOT);
            BooleanSupplier swimIsBooting = () -> view.compute() == ClusterPhase.COLD_BOOT;
            assertThat(swimIsBooting.getAsBoolean()).isTrue();
        }

        @Test void swimGate_normal_emitsFaulty() {
            var view = view(() -> true, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.NORMAL);
            BooleanSupplier swimIsBooting = () -> view.compute() == ClusterPhase.COLD_BOOT;
            assertThat(swimIsBooting.getAsBoolean()).isFalse();
        }
    }

    private static ClusterPhaseView view(BooleanSupplier inQuorum, BooleanSupplier haveLeader) {
        return ClusterPhaseView.clusterPhaseView(inQuorum, haveLeader);
    }
}
