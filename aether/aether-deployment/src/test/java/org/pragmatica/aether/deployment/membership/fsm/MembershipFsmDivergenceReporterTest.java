// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmDivergenceReporter.MembershipDivergence;
import org.pragmatica.aether.deployment.membership.ntt.ReconcileIntent;
import org.pragmatica.aether.deployment.membership.ntt.ReconcileTrigger;
import org.pragmatica.consensus.NodeId;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.ntt.ReconcileIntent.reconcileIntent;

/// Verifies [`MembershipFsmDivergenceReporter`] diffs the shadow ([`ShadowMembershipFsm`]) verdict
/// against the live [`ReconcileIntent`] + live member set: AGREE → [`org.pragmatica.lang.Option#none`],
/// any count or membership-set mismatch → [`org.pragmatica.lang.Option#some`] of the
/// [`MembershipDivergence`] delta; inactive shadow → none regardless of intent. The shadow is driven
/// into the needed states via its public ingress, mirroring [`ShadowMembershipFsmTest`].
class MembershipFsmDivergenceReporterTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final NodeId D = new NodeId("node-d");
    private static final NodeId E = new NodeId("node-e");

    private static ShadowMembershipFsm activeManager() {
        var manager = ShadowMembershipFsm.shadowMembershipFsm();
        manager.activate(Set.of());
        return manager;
    }

    private static ReconcileIntent intent(int configuredCoreCount,
                                          int clusterMembershipCount,
                                          int provisionCount,
                                          int drainCount) {
        return reconcileIntent(0L,
                               ReconcileTrigger.NTT_FIRE,
                               clusterMembershipCount,
                               configuredCoreCount,
                               provisionCount,
                               drainCount,
                               0);
    }

    private static void promoteToMember(ShadowMembershipFsm manager, NodeId id) {
        manager.onSwimHealthy(id, 1L);
        manager.onSwimHealthy(id, 1L);
    }

    private static void evict(ShadowMembershipFsm manager, NodeId id) {
        manager.onSwimFaulty(id, 9L);
        manager.onLivenessGone(id);
    }

    @Nested
    class Agreement {
        @Test
        void onReconcileIntent_fiveMembersMatchLive_returnsNone() {
            var manager = activeManager();
            var members = Set.of(A, B, C, D, E);

            members.forEach(id -> promoteToMember(manager, id));
            assertThat(manager.effective()).isEqualTo(5);

            var reporter = MembershipFsmDivergenceReporter.membershipFsmDivergenceReporter(manager);
            var result = reporter.onReconcileIntent(intent(5, 5, 0, 0), members);

            assertThat(result.isEmpty()).isTrue();
        }
    }

    @Nested
    class CountDivergence {
        @Test
        void onReconcileIntent_shadowSeesProvisionDeficitButLiveProvisionsNothing_returnsSomeMentioningProvision() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            promoteToMember(manager, C);
            assertThat(manager.effective()).isEqualTo(3);
            assertThat(manager.wouldProvision(5)).isEqualTo(2);

            var reporter = MembershipFsmDivergenceReporter.membershipFsmDivergenceReporter(manager);
            var result = reporter.onReconcileIntent(intent(5, 3, 0, 0), Set.of(A, B, C));

            assertThat(result.isPresent()).isTrue();
            var divergence = result.or((MembershipDivergence) null);
            assertThat(divergence.shadowWouldProvision()).isEqualTo(2);
            assertThat(divergence.liveProvisionCount()).isZero();
            assertThat(divergence.detail()).contains("provision");
        }
    }

    @Nested
    class MembershipSetDivergence {
        @Test
        void onReconcileIntent_shadowStillCountsNodeLiveSetDropped_returnsSomeMentioningMembershipSet() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            assertThat(manager.effective()).isEqualTo(2);

            // Live reconciler dropped B from its view, but counts agree (live members=2 includes a ghost C).
            var liveMembers = Set.of(A, C);
            var reporter = MembershipFsmDivergenceReporter.membershipFsmDivergenceReporter(manager);
            var result = reporter.onReconcileIntent(intent(2, 2, 0, 0), liveMembers);

            assertThat(result.isPresent()).isTrue();
            assertThat(result.or((MembershipDivergence) null).detail()).contains("membership-set");
        }

        @Test
        void onReconcileIntent_suspectMembersStillCountInShadowSet_matchLive() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onSwimFaulty(B, 4L);
            assertThat(manager.memberStates()).containsEntry(B, "Suspect");

            // SUSPECT still counts: shadow present-set is {A, B}; live agrees.
            var reporter = MembershipFsmDivergenceReporter.membershipFsmDivergenceReporter(manager);
            var result = reporter.onReconcileIntent(intent(2, 2, 0, 0), Set.of(A, B));

            assertThat(result.isEmpty()).isTrue();
        }
    }

    @Nested
    class InactiveShadow {
        @Test
        void onReconcileIntent_shadowNeverActivated_returnsNone() {
            var manager = ShadowMembershipFsm.shadowMembershipFsm();
            var reporter = MembershipFsmDivergenceReporter.membershipFsmDivergenceReporter(manager);

            var result = reporter.onReconcileIntent(intent(5, 0, 5, 0), Set.of());

            assertThat(manager.isActive()).isFalse();
            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void onReconcileIntent_afterDeactivate_returnsNoneDespiteCountMismatch() {
            var manager = activeManager();

            promoteToMember(manager, A);
            evict(manager, A);
            manager.deactivate();

            var reporter = MembershipFsmDivergenceReporter.membershipFsmDivergenceReporter(manager);
            var result = reporter.onReconcileIntent(intent(5, 3, 2, 0), Set.of(A, B, C));

            assertThat(result.isEmpty()).isTrue();
        }
    }
}
