// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.phase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.swim.membership.MembershipPhase;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// Unit tests for the thin `ClusterPhaseView` adapter. The tracker's [`MembershipPhase`]
/// is mapped onto [`ClusterPhase`], with the leader-awareness refinement: `NORMAL`
/// without a leader degrades to `RECOVERING`.
class ClusterPhaseViewTest {
    @Nested @DisplayName("Tracker phase mapping") class PhaseMapping {
        @Test void compute_trackerColdBoot_returnsColdBoot() {
            var view = view(MembershipPhase.COLD_BOOT, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.COLD_BOOT);
        }

        @Test void compute_trackerColdBoot_noLeader_returnsColdBoot() {
            var view = view(MembershipPhase.COLD_BOOT, () -> false);
            assertThat(view.compute()).isEqualTo(ClusterPhase.COLD_BOOT);
        }

        @Test void compute_trackerNormal_withLeader_returnsNormal() {
            var view = view(MembershipPhase.NORMAL, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.NORMAL);
        }

        @Test void compute_trackerNormal_noLeader_returnsRecovering() {
            var view = view(MembershipPhase.NORMAL, () -> false);
            assertThat(view.compute()).isEqualTo(ClusterPhase.RECOVERING);
        }

        @Test void compute_trackerRecovering_withLeader_returnsRecovering() {
            var view = view(MembershipPhase.RECOVERING, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.RECOVERING);
        }

        @Test void compute_trackerRecovering_noLeader_returnsRecovering() {
            var view = view(MembershipPhase.RECOVERING, () -> false);
            assertThat(view.compute()).isEqualTo(ClusterPhase.RECOVERING);
        }
    }

    /// SWIM consumer path: cold-boot suppression gate fires only when the view returns
    /// COLD_BOOT. Models `swimIsBootingSupplier = () -> view.compute() == COLD_BOOT`.
    @Nested @DisplayName("SWIM cold-boot suppression integration") class SwimGateBehaviour {
        @Test void swimGate_coldBoot_suppressesFaulty() {
            var view = view(MembershipPhase.COLD_BOOT, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.COLD_BOOT);
            BooleanSupplier swimIsBooting = () -> view.compute() == ClusterPhase.COLD_BOOT;
            assertThat(swimIsBooting.getAsBoolean()).isTrue();
        }

        @Test void swimGate_normal_emitsFaulty() {
            var view = view(MembershipPhase.NORMAL, () -> true);
            assertThat(view.compute()).isEqualTo(ClusterPhase.NORMAL);
            BooleanSupplier swimIsBooting = () -> view.compute() == ClusterPhase.COLD_BOOT;
            assertThat(swimIsBooting.getAsBoolean()).isFalse();
        }
    }

    private static ClusterPhaseView view(MembershipPhase trackerPhase, BooleanSupplier haveLeader) {
        Supplier<MembershipPhase> trackerPhaseSupplier = () -> trackerPhase;
        return ClusterPhaseView.clusterPhaseView(trackerPhaseSupplier, haveLeader);
    }
}
