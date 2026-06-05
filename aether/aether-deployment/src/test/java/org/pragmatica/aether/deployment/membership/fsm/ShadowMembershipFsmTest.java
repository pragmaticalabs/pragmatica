// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the shadow membership manager ([`ShadowMembershipFsm`]) drives the per-member FSM
/// faithfully from tapped events and computes the cluster aggregate (spec §3.4 effective / would-
/// provision / would-drain). Mirrors the live promotion (NTT up-hysteresis = 2) and confirmed-
/// eviction (LeaderReconciler co-confirmation: SWIM-FAULTY ∧ liveness-gone) drivers.
class ShadowMembershipFsmTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");

    private static ShadowMembershipFsm activeManager() {
        var manager = ShadowMembershipFsm.shadowMembershipFsm();
        manager.activate();
        return manager;
    }

    @Nested
    class Promotion {
        @Test
        void onSwimHealthy_consecutiveSamplesReachUpHysteresis_promotesToMember() {
            var manager = activeManager();

            manager.onSwimHealthy(A, 1L);
            assertThat(manager.effective()).isZero();
            assertThat(manager.memberStates()).containsEntry(A, "Observed");

            manager.onSwimHealthy(A, 1L);
            assertThat(manager.memberStates()).containsEntry(A, "Member");
            assertThat(manager.effective()).isEqualTo(1);
        }
    }

    @Nested
    class SuspectStillCounts {
        @Test
        void onSwimSuspect_afterMember_staysCountedThenRecovers() {
            var manager = activeManager();

            promoteToMember(manager, A);
            assertThat(manager.effective()).isEqualTo(1);

            manager.onSwimSuspect(A, 2L);
            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(manager.effective()).isEqualTo(1);

            manager.onSwimHealthy(A, 3L);
            assertThat(manager.memberStates()).containsEntry(A, "Member");
            assertThat(manager.effective()).isEqualTo(1);
        }
    }

    @Nested
    class CoConfirmedEviction {
        @Test
        void onSwimFaultyPlusLivenessGone_drivesMemberToDead() {
            var manager = activeManager();

            promoteToMember(manager, A);
            assertThat(manager.effective()).isEqualTo(1);

            manager.onSwimFaulty(A, 4L);
            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(manager.effective()).isEqualTo(1);

            manager.onLivenessGone(A);
            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(manager.effective()).isZero();
            assertThat(manager.wouldProvision(5)).isEqualTo(5);
        }

        @Test
        void onSwimFaultyAlone_staysSuspectAndCounted() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);

            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(manager.effective()).isEqualTo(1);
        }

        @Test
        void onLivenessGoneAlone_staysSuspectAndCounted() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onLivenessGone(A);

            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(manager.effective()).isEqualTo(1);
        }
    }

    @Nested
    class Rejoin {
        @Test
        void onSwimHealthy_higherIncarnationAfterDead_reArmsToObserved() {
            var manager = activeManager();

            driveToDead(manager, A, 4L);
            assertThat(manager.memberStates()).containsEntry(A, "Dead");

            manager.onSwimHealthy(A, 9L);
            assertThat(manager.memberStates()).containsEntry(A, "Observed");

            manager.onSwimHealthy(A, 9L);
            assertThat(manager.memberStates()).containsEntry(A, "Member");
            assertThat(manager.effective()).isEqualTo(1);
        }

        @Test
        void onSwimHealthy_staleIncarnationAfterDead_staysDead() {
            var manager = activeManager();

            driveToDead(manager, A, 7L);

            manager.onSwimHealthy(A, 3L);
            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(manager.effective()).isZero();
        }
    }

    @Nested
    class GracefulDeparture {
        @Test
        void onSwimDeparted_drivesMemberToDead() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onSwimDeparted(A, 5L);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(manager.effective()).isZero();
        }
    }

    @Nested
    class Aggregate {
        @Test
        void effectiveAndWouldProvision_trackFiveMembersThenTwoKilled() {
            var manager = activeManager();
            var members = fivePromotedMembers(manager);

            assertThat(manager.effective()).isEqualTo(5);
            assertThat(manager.wouldProvision(5)).isZero();
            assertThat(manager.wouldDrain(5)).isZero();

            driveToDead(manager, members[0], 100L);
            driveToDead(manager, members[1], 100L);

            assertThat(manager.effective()).isEqualTo(3);
            assertThat(manager.wouldProvision(5)).isEqualTo(2);
        }

        @Test
        void wouldDrain_sixMembersConfiguredFive_reportsSurplusOfOne() {
            var manager = activeManager();

            for (var i = 0; i < 6; i++) {
                promoteToMember(manager, new NodeId("core-" + i));
            }
            assertThat(manager.effective()).isEqualTo(6);
            assertThat(manager.wouldDrain(5)).isEqualTo(1);
            assertThat(manager.wouldProvision(5)).isZero();
        }
    }

    @Nested
    class LeaderGating {
        @Test
        void ingressBeforeActivate_isNoOp() {
            var manager = ShadowMembershipFsm.shadowMembershipFsm();

            manager.onSwimHealthy(A, 1L);
            manager.onSwimHealthy(A, 1L);

            assertThat(manager.isActive()).isFalse();
            assertThat(manager.effective()).isZero();
            assertThat(manager.memberStates()).isEmpty();
        }

        @Test
        void deactivate_clearsTrackedMembers() {
            var manager = activeManager();

            promoteToMember(manager, A);
            assertThat(manager.effective()).isEqualTo(1);

            manager.deactivate();
            assertThat(manager.isActive()).isFalse();
            assertThat(manager.effective()).isZero();
            assertThat(manager.memberStates()).isEmpty();
        }
    }

    @Nested
    class IngressOnUnseenIds {
        @Test
        void ingressForNeverSeenId_linksFsmInObserved() {
            var manager = activeManager();

            manager.onPeerDisconnected(B);

            assertThat(manager.memberStates()).containsEntry(B, "Observed");
            assertThat(manager.effective()).isZero();
        }

        @Test
        void allIngressKindsOnFreshId_areHandled() {
            var manager = activeManager();

            manager.onSwimUnknown(new NodeId("u1"), 1L);
            manager.onPeerConnected(new NodeId("u2"));
            manager.onLivenessGone(new NodeId("u3"));
            manager.onSwimSuspect(new NodeId("u4"), 1L);
            manager.onSwimFaulty(new NodeId("u5"), 1L);
            manager.onSwimDeparted(new NodeId("u6"), 1L);
            manager.onJoinGraceExpired(new NodeId("u7"));

            assertThat(manager.memberStates()).containsKeys(new NodeId("u1"), new NodeId("u2"),
                                                            new NodeId("u3"), new NodeId("u4"),
                                                            new NodeId("u5"), new NodeId("u6"));
            assertThat(manager.memberStates()).containsEntry(new NodeId("u7"), "Dead");
        }
    }

    // --- helpers ---

    private static void promoteToMember(ShadowMembershipFsm manager, NodeId id) {
        manager.onSwimHealthy(id, 1L);
        manager.onSwimHealthy(id, 1L);
    }

    private static void driveToDead(ShadowMembershipFsm manager, NodeId id, long incarnation) {
        promoteToMember(manager, id);
        manager.onSwimFaulty(id, incarnation);
        manager.onLivenessGone(id);
    }

    private static NodeId[] fivePromotedMembers(ShadowMembershipFsm manager) {
        var ids = new NodeId[]{
                new NodeId("m0"), new NodeId("m1"), new NodeId("m2"), new NodeId("m3"), new NodeId("m4")
        };
        for (var id : ids) {
            promoteToMember(manager, id);
        }
        return ids;
    }
}
