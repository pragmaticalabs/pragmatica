// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker.nodeTopologyTracker;

/// Verifies the LIVE membership manager ([`MembershipFsm`]) drives the per-member FSM faithfully from
/// tapped events, computes the cluster aggregate (spec §3.4 effective / would-provision / would-drain),
/// AND — as the authoritative death decision-maker — hard-evicts a co-confirmed-dead member from the
/// [`NodeTopologyTracker`] on every transition into DEAD. Promotion is edge-driven (a single SWIM
/// HealthyObserved edge promotes OBSERVED→MEMBER, up-hysteresis = 1) with a one-time formation seed;
/// confirmed eviction requires co-confirmation (SWIM-FAULTY ∧ liveness-gone) and is never undone by a
/// later seed.
class MembershipFsmTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");

    private static final NodeId NTT_SELF = new NodeId("ntt-self");
    private static final TimeSpan INTERVAL = TimeSpan.timeSpan(100).millis();
    private static final int K_UP = 2;
    private static final int K_DOWN = 3;

    private static MembershipFsm activeManager() {
        return MembershipFsm.membershipFsm(emptyNtt());
    }

    /// A real [`NodeTopologyTracker`] with no live members — eviction of an absent id is a harmless
    /// no-op, so the FSM's DEAD→evict hook never affects these behavioral assertions.
    private static NodeTopologyTracker emptyNtt() {
        Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.of());
        return nodeTopologyTracker(NTT_SELF, health, INTERVAL, K_UP, K_DOWN, () -> 0L);
    }

    @Nested
    class Promotion {
        @Test
        void onSwimHealthy_firstEdge_promotesToMember() {
            var manager = activeManager();

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
    class NttEviction {
        /// The cardinal Phase-2 contract: a co-confirmed-dead member (SWIM-FAULTY ∧ liveness-gone)
        /// must be hard-evicted from the live [`NodeTopologyTracker`] on the transition into DEAD.
        /// Drive a real member into NTT's stable set via samples, promote + co-confirm it dead in the
        /// FSM, and assert NTT's presence view no longer contains it (the observable effect that the
        /// presence-derived TopologyObserver path then emits NODE_FAILED from).
        @Test
        void enteringDead_coConfirmed_evictsFromNtt() {
            var liveness = new HashMap<NodeId, SwimHealth>();
            var clock = new AtomicLong(0L);
            Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
            var ntt = nodeTopologyTracker(NTT_SELF, health, INTERVAL, K_UP, K_DOWN, clock::get);

            liveness.put(A, SwimHealth.HEALTHY);
            sampleTimes(ntt, K_UP);
            assertThat(ntt.currentMembers()).contains(A);

            var manager = MembershipFsm.membershipFsm(ntt);
            manager.seed(Set.of(A));
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(ntt.currentMembers()).doesNotContain(A);
        }

        /// Graceful departure also reaches DEAD (no co-confirmation needed) and must evict from NTT.
        @Test
        void enteringDead_graceful_evictsFromNtt() {
            var liveness = new HashMap<NodeId, SwimHealth>();
            var clock = new AtomicLong(0L);
            Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
            var ntt = nodeTopologyTracker(NTT_SELF, health, INTERVAL, K_UP, K_DOWN, clock::get);

            liveness.put(A, SwimHealth.HEALTHY);
            sampleTimes(ntt, K_UP);
            assertThat(ntt.currentMembers()).contains(A);

            var manager = MembershipFsm.membershipFsm(ntt);
            manager.seed(Set.of(A));
            manager.onSwimDeparted(A, 5L);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(ntt.currentMembers()).doesNotContain(A);
        }

        /// Single-plane death (bare SWIM-FAULTY) stays SUSPECT — it must NOT evict from NTT.
        @Test
        void singlePlaneFaulty_doesNotEvictFromNtt() {
            var liveness = new HashMap<NodeId, SwimHealth>();
            var clock = new AtomicLong(0L);
            Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
            var ntt = nodeTopologyTracker(NTT_SELF, health, INTERVAL, K_UP, K_DOWN, clock::get);

            liveness.put(A, SwimHealth.HEALTHY);
            sampleTimes(ntt, K_UP);
            assertThat(ntt.currentMembers()).contains(A);

            var manager = MembershipFsm.membershipFsm(ntt);
            manager.seed(Set.of(A));
            manager.onSwimFaulty(A, 4L);

            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(ntt.currentMembers()).contains(A);
        }

        private static void sampleTimes(NodeTopologyTracker ntt, int times) {
            for (var i = 0; i < times; i++) {
                ntt.sample();
            }
        }
    }

    @Nested
    class Rejoin {
        @Test
        void onSwimHealthy_higherIncarnationAfterDead_reArmsAndPromotes() {
            var manager = activeManager();

            driveToDead(manager, A, 4L);
            assertThat(manager.memberStates()).containsEntry(A, "Dead");

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
    class CountedMembers {
        @Test
        void countedMembers_memberAndSuspect_includesBoth() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onSwimFaulty(B, 4L);
            assertThat(manager.memberStates()).containsEntry(B, "Suspect");

            assertThat(manager.countedMembers()).containsExactlyInAnyOrder(A, B);
            assertThat(manager.countedMembers()).hasSize(2);
            assertThat(manager.countedMembers()).hasSize(manager.effective());
        }

        @Test
        void countedMembers_afterDead_excludesDeadMember() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);
            assertThat(manager.memberStates()).containsEntry(A, "Dead");

            assertThat(manager.countedMembers()).doesNotContain(A);
            assertThat(manager.countedMembers()).containsExactly(B);
        }
    }

    @Nested
    class DownHysteresis {
        @Test
        void onDownHysteresisMet_suspectMember_transitionsToDeparting() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);
            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(manager.effective()).isEqualTo(1);

            manager.onDownHysteresisMet(A);

            assertThat(manager.memberStates()).containsEntry(A, "Departing");
            assertThat(manager.countedMembers()).doesNotContain(A);
            assertThat(manager.effective()).isZero();
        }

        @Test
        void onDownHysteresisMet_onObservedId_isIgnored() {
            var manager = MembershipFsm.membershipFsm(emptyNtt());

            manager.onDownHysteresisMet(A);

            assertThat(manager.memberStates()).containsEntry(A, "Observed");
            assertThat(manager.effective()).isZero();
        }
    }

    @Nested
    class Seeding {
        @Test
        void seed_promotesAllUntrackedToMember() {
            var manager = activeManager();

            manager.seed(Set.of(A, B, C));

            assertThat(manager.memberStates()).containsEntry(A, "Member")
                                              .containsEntry(B, "Member")
                                              .containsEntry(C, "Member");
            assertThat(manager.effective()).isEqualTo(3);
        }

        @Test
        void seed_calledTwice_isIdempotent() {
            var manager = activeManager();

            manager.seed(Set.of(A, B, C));
            manager.seed(Set.of(A, B, C));

            assertThat(manager.effective()).isEqualTo(3);
            assertThat(manager.memberStates()).containsEntry(A, "Member")
                                              .containsEntry(B, "Member")
                                              .containsEntry(C, "Member");
        }

        @Test
        void seed_promotesObservedButNotDead() {
            var manager = activeManager();

            manager.onPeerDisconnected(A);
            assertThat(manager.memberStates()).containsEntry(A, "Observed");
            driveToDead(manager, B, 4L);
            assertThat(manager.memberStates()).containsEntry(B, "Dead");

            manager.seed(Set.of(A, B));

            assertThat(manager.memberStates()).containsEntry(A, "Member")
                                              .containsEntry(B, "Dead");
            assertThat(manager.effective()).isEqualTo(1);
        }

        @Test
        void seed_atConstruction_promotesInitialMembers() {
            var manager = MembershipFsm.membershipFsm(emptyNtt());

            manager.seed(Set.of(A, B));

            assertThat(manager.effective()).isEqualTo(2);
            assertThat(manager.memberStates()).containsEntry(A, "Member")
                                              .containsEntry(B, "Member");
        }

        @Test
        void seed_afterDeath_doesNotResurrect() {
            var manager = activeManager();

            manager.seed(Set.of(A, B));
            assertThat(manager.effective()).isEqualTo(2);

            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);
            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(manager.effective()).isEqualTo(1);

            manager.seed(Set.of(A, B));
            assertThat(manager.memberStates()).containsEntry(A, "Dead")
                                              .containsEntry(B, "Member");
            assertThat(manager.effective()).isEqualTo(1);
        }
    }

    @Nested
    class AlwaysOn {
        /// Ingress is processed unconditionally from construction (no leader gate): a fresh manager that
        /// was never seeded still tracks and promotes a member on its first SWIM HealthyObserved edge.
        @Test
        void ingressFromConstruction_isTracked() {
            var manager = MembershipFsm.membershipFsm(emptyNtt());

            manager.onSwimHealthy(A, 1L);

            assertThat(manager.memberStates()).containsEntry(A, "Member");
            assertThat(manager.effective()).isEqualTo(1);
        }

        /// Eviction fires on every node (no leader gate): a co-confirmed-dead member is driven to DEAD
        /// and drops out of the count regardless of any leadership role.
        @Test
        void evictionFires_withoutAnyActivation() {
            var manager = activeManager();

            driveToDead(manager, A, 4L);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(manager.effective()).isZero();
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

    private static void promoteToMember(MembershipFsm manager, NodeId id) {
        manager.onSwimHealthy(id, 1L);
    }

    private static void driveToDead(MembershipFsm manager, NodeId id, long incarnation) {
        promoteToMember(manager, id);
        manager.onSwimFaulty(id, incarnation);
        manager.onLivenessGone(id);
    }

    private static NodeId[] fivePromotedMembers(MembershipFsm manager) {
        var ids = new NodeId[]{
                new NodeId("m0"), new NodeId("m1"), new NodeId("m2"), new NodeId("m3"), new NodeId("m4")
        };
        for (var id : ids) {
            promoteToMember(manager, id);
        }
        return ids;
    }
}
