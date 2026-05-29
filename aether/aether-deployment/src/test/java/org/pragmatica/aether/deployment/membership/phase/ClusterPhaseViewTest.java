// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.phase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.phase.ClusterPhaseView.MembershipViewReader;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// E.6 unit tests for `ClusterPhaseView`. Covers spec §7 derivation table:
/// COLD_BOOT, COLD_BOOT→NORMAL stability, NORMAL→RECOVERING on quorum loss,
/// RECOVERING→NORMAL after recovery window, single-node quorum floor, and leader
/// absence.
class ClusterPhaseViewTest {
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();
    private static final NodeId PEER_C = NodeId.nodeId("peer-c").unwrap();
    private static final NodeId PEER_D = NodeId.nodeId("peer-d").unwrap();
    private static final NodeId PEER_E = NodeId.nodeId("peer-e").unwrap();

    private static final TimeSpan STABLE_WINDOW = timeSpan(5).seconds();
    private static final TimeSpan RECOVERY_WINDOW = timeSpan(5).seconds();

    private static final long T_BASE = 1_000_000L;

    @Nested @DisplayName("COLD_BOOT branch (never reached NORMAL)") class ColdBootBranch {
        @Test void compute_zeroOnDuty_returnsColdBoot() {
            var view = view(5,
                            lifecycleSnapshot(),
                            () -> Option.none(),
                            () -> true);
            assertThat(view.compute(T_BASE)).isEqualTo(ClusterPhase.COLD_BOOT);
        }

        @Test void compute_subQuorumOnDuty_returnsColdBoot() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE);
            // 5-node cluster, quorum = 3, only 2 ON_DUTY
            var view = view(5,
                            snapshot,
                            () -> Option.none(),
                            () -> true);
            assertThat(view.compute(T_BASE + 60_000L)).isEqualTo(ClusterPhase.COLD_BOOT);
        }

        @Test void compute_quorumOnDuty_noStableWindowGate_returnsNormal() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE);
            // RC1 membership-v2 step 1: the KV `updatedAt` stable-window gate is dropped —
            // quorum reached + leader present ⇒ NORMAL immediately (SWIM aliveness is the gate).
            var view = view(5,
                            snapshot,
                            () -> Option.none(),
                            () -> true);
            assertThat(view.compute(T_BASE + 1_000L)).isEqualTo(ClusterPhase.NORMAL);
        }

        @Test void compute_quorumOnDuty_pastStableWindow_returnsNormal() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE + 1_000L)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE + 2_000L);
            // 5-node cluster, quorum = 3, oldest ON_DUTY at T_BASE, window = 5s
            var view = view(5,
                            snapshot,
                            () -> Option.none(),
                            () -> true);
            assertThat(view.compute(T_BASE + STABLE_WINDOW.millis())).isEqualTo(ClusterPhase.NORMAL);
        }

        @Test void compute_quorumOnDuty_pastStableWindow_noLeader_returnsColdBoot() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE);
            var view = view(5,
                            snapshot,
                            () -> Option.none(),
                            () -> false);
            assertThat(view.compute(T_BASE + STABLE_WINDOW.millis() + 1_000L)).isEqualTo(ClusterPhase.COLD_BOOT);
        }

        @Test void compute_singleNodeCluster_quorumFloorIsOne() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE);
            // 1-node cluster → quorum = max(1, 1/2+1) = 1
            var view = view(1,
                            snapshot,
                            () -> Option.none(),
                            () -> true);
            assertThat(view.compute(T_BASE + STABLE_WINDOW.millis() + 100L)).isEqualTo(ClusterPhase.NORMAL);
        }
    }

    @Nested @DisplayName("RECOVERING branch (prior NORMAL reached)") class RecoveringBranch {
        @Test void compute_priorNormal_quorumDrop_returnsRecovering() {
            // 5-node cluster, only 2 ON_DUTY (sub-quorum)
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_C, NodeLifecycleState.STOPPED, T_BASE + 1_000L)
                .with(PEER_D, NodeLifecycleState.STOPPED, T_BASE + 1_000L)
                .with(PEER_E, NodeLifecycleState.STOPPED, T_BASE + 1_000L);
            var view = view(5,
                            snapshot,
                            () -> Option.some(ClusterPhase.NORMAL),
                            () -> true);
            assertThat(view.compute(T_BASE + 30_000L)).isEqualTo(ClusterPhase.RECOVERING);
        }

        @Test void compute_priorRecovering_quorumRestored_pastRecoveryWindow_returnsNormal() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE + 100L)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE + 200L);
            var view = view(5,
                            snapshot,
                            () -> Option.some(ClusterPhase.RECOVERING),
                            () -> true);
            assertThat(view.compute(T_BASE + RECOVERY_WINDOW.millis())).isEqualTo(ClusterPhase.NORMAL);
        }

        @Test void compute_priorRecovering_quorumRestored_noRecoveryWindowGate_returnsNormal() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE);
            // RC1 membership-v2 step 1: recovery stable-window gate dropped — quorum restored
            // + leader present ⇒ NORMAL immediately.
            var view = view(5,
                            snapshot,
                            () -> Option.some(ClusterPhase.RECOVERING),
                            () -> true);
            assertThat(view.compute(T_BASE + 2_000L)).isEqualTo(ClusterPhase.NORMAL);
        }

        @Test void compute_priorNormal_leaderLost_returnsRecovering() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE);
            var view = view(5,
                            snapshot,
                            () -> Option.some(ClusterPhase.NORMAL),
                            () -> false);
            assertThat(view.compute(T_BASE + 60_000L)).isEqualTo(ClusterPhase.RECOVERING);
        }

        @Test void compute_priorNormal_stillQuorate_returnsNormal() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE);
            var view = view(5,
                            snapshot,
                            () -> Option.some(ClusterPhase.NORMAL),
                            () -> true);
            assertThat(view.compute(T_BASE + RECOVERY_WINDOW.millis() + 60_000L)).isEqualTo(ClusterPhase.NORMAL);
        }
    }

    @Nested @DisplayName("Prior-phase semantics") class PriorPhaseSemantics {
        @Test void compute_noPriorPhase_quorumMet_returnsNormal() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE);
            var view = view(1,
                            snapshot,
                            () -> Option.none(),
                            () -> true);
            // RC1 membership-v2 step 1: no stable-window gate — single-node quorum + leader ⇒
            // NORMAL immediately even with no prior phase.
            assertThat(view.compute(T_BASE + 100L)).isEqualTo(ClusterPhase.NORMAL);
        }

        @Test void compute_priorColdBoot_quorumMet_returnsNormal() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE);
            var view = view(1,
                            snapshot,
                            () -> Option.some(ClusterPhase.COLD_BOOT),
                            () -> true);
            assertThat(view.compute(T_BASE + 100L)).isEqualTo(ClusterPhase.NORMAL);
        }
    }

    /// SWIM consumer path: cold-boot suppression gate fires only when the view
    /// returns COLD_BOOT. Models `swimIsBootingSupplier = () -> view.compute(...) == COLD_BOOT`.
    @Nested @DisplayName("SWIM cold-boot suppression integration") class SwimGateBehaviour {
        @Test void swimGate_coldBoot_suppressesFaulty() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE);
            // 5-node cluster, sub-quorum — view returns COLD_BOOT
            var view = view(5,
                            snapshot,
                            () -> Option.none(),
                            () -> true);
            assertThat(view.compute(T_BASE + 60_000L)).isEqualTo(ClusterPhase.COLD_BOOT);
            // Gate consults `view.compute(...) == COLD_BOOT` → true → suppression active
            BooleanSupplier swimIsBooting = () -> view.compute(T_BASE + 60_000L) == ClusterPhase.COLD_BOOT;
            assertThat(swimIsBooting.getAsBoolean()).isTrue();
        }

        @Test void swimGate_normal_emitsFaulty() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE);
            var view = view(5,
                            snapshot,
                            () -> Option.some(ClusterPhase.NORMAL),
                            () -> true);
            assertThat(view.compute(T_BASE + 60_000L)).isEqualTo(ClusterPhase.NORMAL);
            BooleanSupplier swimIsBooting = () -> view.compute(T_BASE + 60_000L) == ClusterPhase.COLD_BOOT;
            assertThat(swimIsBooting.getAsBoolean()).isFalse();
        }
    }

    private static ClusterPhaseView view(int expectedClusterSize,
                                         MembershipViewReader membershipReader,
                                         Supplier<Option<ClusterPhase>> priorPhaseReader,
                                         BooleanSupplier haveLeader) {
        return ClusterPhaseView.clusterPhaseView(expectedClusterSize,
                                                 STABLE_WINDOW,
                                                 RECOVERY_WINDOW,
                                                 membershipReader,
                                                 priorPhaseReader,
                                                 haveLeader);
    }

    private static LifecycleSnapshotBuilder lifecycleSnapshot() {
        return new LifecycleSnapshotBuilder();
    }

    /// Test builder — collects per-peer lifecycle states and exposes them as a
    /// `MembershipViewReader`. Every `ON_DUTY` peer is mapped to SWIM `HEALTHY` (the only
    /// status `ClusterPhaseView` counts); `updatedAt` is retained for call-site compatibility
    /// but ignored (the view no longer reads a per-peer consensus timestamp).
    private static final class LifecycleSnapshotBuilder implements MembershipViewReader {
        private final Map<NodeId, NodeLifecycleState> states = new LinkedHashMap<>();

        LifecycleSnapshotBuilder with(NodeId peer, NodeLifecycleState state, @SuppressWarnings("unused") long updatedAt) {
            states.put(peer, state);
            return this;
        }

        @Override public MembershipView view() {
            return MembershipView.membershipView(() -> Option.some(buildSnapshot()));
        }

        private HealthSnapshot buildSnapshot() {
            var swim = new LinkedHashMap<NodeId, SwimHealth>();
            states.forEach((peer, state) -> putIfOnDuty(swim, peer, state));

            return HealthSnapshot.healthSnapshot(swim);
        }

        private static void putIfOnDuty(Map<NodeId, SwimHealth> swim, NodeId peer, NodeLifecycleState state) {
            if (state == NodeLifecycleState.ON_DUTY) {
                swim.put(peer, SwimHealth.HEALTHY);
            }
        }
    }
}
