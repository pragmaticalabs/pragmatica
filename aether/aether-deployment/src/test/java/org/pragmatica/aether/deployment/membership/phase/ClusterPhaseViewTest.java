// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.phase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.phase.ClusterPhaseView.LifecycleSnapshotReader;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

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
                            () -> Map.<NodeId, NodeLifecycleValue>of(),
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

        @Test void compute_quorumOnDuty_withinStableWindow_returnsColdBoot() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE);
            // 5-node cluster, quorum = 3 — just reached, hasn't dwelled long enough
            var view = view(5,
                            snapshot,
                            () -> Option.none(),
                            () -> true);
            assertThat(view.compute(T_BASE + 1_000L)).isEqualTo(ClusterPhase.COLD_BOOT);
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
                .with(PEER_C, NodeLifecycleState.DECOMMISSIONED, T_BASE + 1_000L)
                .with(PEER_D, NodeLifecycleState.DECOMMISSIONED, T_BASE + 1_000L)
                .with(PEER_E, NodeLifecycleState.DECOMMISSIONED, T_BASE + 1_000L);
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

        @Test void compute_priorRecovering_quorumRestored_withinRecoveryWindow_stillRecovering() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_B, NodeLifecycleState.ON_DUTY, T_BASE)
                .with(PEER_C, NodeLifecycleState.ON_DUTY, T_BASE);
            var view = view(5,
                            snapshot,
                            () -> Option.some(ClusterPhase.RECOVERING),
                            () -> true);
            assertThat(view.compute(T_BASE + 2_000L)).isEqualTo(ClusterPhase.RECOVERING);
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
        @Test void compute_noPriorPhase_treatedAsColdBoot() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE);
            var view = view(1,
                            snapshot,
                            () -> Option.none(),
                            () -> true);
            // Within stable window — still COLD_BOOT
            assertThat(view.compute(T_BASE + 100L)).isEqualTo(ClusterPhase.COLD_BOOT);
        }

        @Test void compute_priorColdBoot_treatedAsNeverNormal() {
            var snapshot = lifecycleSnapshot()
                .with(PEER_A, NodeLifecycleState.ON_DUTY, T_BASE);
            var view = view(1,
                            snapshot,
                            () -> Option.some(ClusterPhase.COLD_BOOT),
                            () -> true);
            assertThat(view.compute(T_BASE + 100L)).isEqualTo(ClusterPhase.COLD_BOOT);
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
                                         LifecycleSnapshotReader lifecycleReader,
                                         Supplier<Option<ClusterPhase>> priorPhaseReader,
                                         BooleanSupplier haveLeader) {
        return ClusterPhaseView.clusterPhaseView(expectedClusterSize,
                                                 STABLE_WINDOW,
                                                 RECOVERY_WINDOW,
                                                 lifecycleReader,
                                                 priorPhaseReader,
                                                 haveLeader);
    }

    private static LifecycleSnapshotBuilder lifecycleSnapshot() {
        return new LifecycleSnapshotBuilder();
    }

    private static final class LifecycleSnapshotBuilder implements LifecycleSnapshotReader {
        private final Map<NodeId, NodeLifecycleValue> snapshot = new LinkedHashMap<>();

        LifecycleSnapshotBuilder with(NodeId peer, NodeLifecycleState state, long updatedAt) {
            snapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(state, updatedAt));
            return this;
        }

        @Override public Map<NodeId, NodeLifecycleValue> snapshot() {
            return snapshot;
        }
    }
}
