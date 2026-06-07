// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.ntt.PresenceSampler;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.ntt.PresenceSampler.presenceSampler;

/// Verifies the LIVE membership manager ([`MembershipFsm`]) drives the per-member FSM faithfully from
/// tapped events, computes the cluster aggregate (spec §3.4 effective / would-provision / would-drain),
/// AND — as the authoritative death decision-maker — hard-evicts a co-confirmed-dead member from the
/// [`PresenceSampler`] on every transition into DEAD. Promotion is edge-driven (a single SWIM
/// HealthyObserved edge promotes OBSERVED→MEMBER, up-hysteresis = 1) with a one-time formation seed;
/// confirmed eviction requires co-confirmation (SWIM-FAULTY ∧ liveness-gone) and is never undone by a
/// later seed.
class MembershipFsmTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");

    private static final NodeId SAMPLER_SELF = new NodeId("sampler-self");
    private static final TimeSpan INTERVAL = TimeSpan.timeSpan(100).millis();
    private static final int K_UP = 2;
    private static final int K_DOWN = 3;

    private static MembershipFsm activeManager() {
        return MembershipFsm.membershipFsm(emptySampler());
    }

    /// A real [`PresenceSampler`] with no live members — eviction of an absent id is a harmless
    /// no-op, so the FSM's DEAD→evict hook never affects these behavioral assertions.
    private static PresenceSampler emptySampler() {
        Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.of());
        return presenceSampler(SAMPLER_SELF, health, INTERVAL, K_UP, K_DOWN, () -> 0L);
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
    class SamplerEviction {
        /// The cardinal Phase-2 contract: a co-confirmed-dead member (SWIM-FAULTY ∧ liveness-gone)
        /// must be hard-evicted from the live [`PresenceSampler`] on the transition into DEAD.
        /// Drive a real member into presence sampler's stable set via samples, promote + co-confirm it dead in the
        /// FSM, and assert presence sampler's presence view no longer contains it (the observable effect that the
        /// presence-derived TopologyObserver path then emits NODE_FAILED from).
        @Test
        void enteringDead_coConfirmed_evictsFromSampler() {
            var liveness = new HashMap<NodeId, SwimHealth>();
            var clock = new AtomicLong(0L);
            Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
            var presenceSampler = presenceSampler(SAMPLER_SELF, health, INTERVAL, K_UP, K_DOWN, clock::get);

            liveness.put(A, SwimHealth.HEALTHY);
            sampleTimes(presenceSampler, K_UP);
            assertThat(presenceSampler.currentMembers()).contains(A);

            var manager = MembershipFsm.membershipFsm(presenceSampler);
            manager.seed(Set.of(A));
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(presenceSampler.currentMembers()).doesNotContain(A);
        }

        /// Graceful departure also reaches DEAD (no co-confirmation needed) and must evict from presence sampler.
        @Test
        void enteringDead_graceful_evictsFromSampler() {
            var liveness = new HashMap<NodeId, SwimHealth>();
            var clock = new AtomicLong(0L);
            Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
            var presenceSampler = presenceSampler(SAMPLER_SELF, health, INTERVAL, K_UP, K_DOWN, clock::get);

            liveness.put(A, SwimHealth.HEALTHY);
            sampleTimes(presenceSampler, K_UP);
            assertThat(presenceSampler.currentMembers()).contains(A);

            var manager = MembershipFsm.membershipFsm(presenceSampler);
            manager.seed(Set.of(A));
            manager.onSwimDeparted(A, 5L);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(presenceSampler.currentMembers()).doesNotContain(A);
        }

        /// Single-plane death (bare SWIM-FAULTY) stays SUSPECT — it must NOT evict from presence sampler.
        @Test
        void singlePlaneFaulty_doesNotEvictFromSampler() {
            var liveness = new HashMap<NodeId, SwimHealth>();
            var clock = new AtomicLong(0L);
            Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
            var presenceSampler = presenceSampler(SAMPLER_SELF, health, INTERVAL, K_UP, K_DOWN, clock::get);

            liveness.put(A, SwimHealth.HEALTHY);
            sampleTimes(presenceSampler, K_UP);
            assertThat(presenceSampler.currentMembers()).contains(A);

            var manager = MembershipFsm.membershipFsm(presenceSampler);
            manager.seed(Set.of(A));
            manager.onSwimFaulty(A, 4L);

            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(presenceSampler.currentMembers()).contains(A);
        }

        private static void sampleTimes(PresenceSampler presenceSampler, int times) {
            for (var i = 0; i < times; i++) {
                presenceSampler.sample();
            }
        }
    }

    @Nested
    class ConfirmedDeparture {
        /// Co-confirmed death (SWIM-FAULTY ∧ liveness-gone) is a fresh edge into DEAD — the listener
        /// fires exactly once.
        @Test
        void onConfirmedDeparture_coConfirmedDeath_firesExactlyOnce() {
            var manager = activeManager();
            var fired = new ArrayList<NodeId>();
            manager.onConfirmedDeparture(fired::add);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(fired).containsExactly(A);
        }

        /// Graceful departure reaches DEAD without co-confirmation — the listener still fires once.
        @Test
        void onConfirmedDeparture_gracefulDeparted_firesExactlyOnce() {
            var manager = activeManager();
            var fired = new ArrayList<NodeId>();
            manager.onConfirmedDeparture(fired::add);

            promoteToMember(manager, A);
            manager.onSwimDeparted(A, 5L);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(fired).containsExactly(A);
        }

        /// Join-grace expiry on an OBSERVED member drives OBSERVED→DEAD — a fresh edge into DEAD, so the
        /// listener fires once.
        @Test
        void onConfirmedDeparture_joinGraceExpiryOnObserved_firesExactlyOnce() {
            var manager = activeManager();
            var fired = new ArrayList<NodeId>();
            manager.onConfirmedDeparture(fired::add);

            // Link the FSM in OBSERVED without promoting, then expire its join grace.
            manager.onPeerDisconnected(A);
            assertThat(manager.memberStates()).containsEntry(A, "Observed");
            manager.onJoinGraceExpired(A);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(fired).containsExactly(A);
        }

        /// A single-plane signal (bare SWIM-FAULTY) leaves the member in SUSPECT, never DEAD — the
        /// listener must NOT fire (gated on real DEAD, not doubt).
        @Test
        void onConfirmedDeparture_singlePlaneFaulty_doesNotFire() {
            var manager = activeManager();
            var fired = new ArrayList<NodeId>();
            manager.onConfirmedDeparture(fired::add);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);

            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(fired).isEmpty();
        }

        /// A single-plane signal (bare liveness-gone) leaves the member in SUSPECT, never DEAD — the
        /// listener must NOT fire.
        @Test
        void onConfirmedDeparture_singlePlaneLivenessGone_doesNotFire() {
            var manager = activeManager();
            var fired = new ArrayList<NodeId>();
            manager.onConfirmedDeparture(fired::add);

            promoteToMember(manager, A);
            manager.onLivenessGone(A);

            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(fired).isEmpty();
        }

        /// The confirmed-departure listener fires ALONGSIDE the presence-sampler eviction at the same
        /// central DEAD chokepoint — both side effects occur on the one death edge.
        @Test
        void onConfirmedDeparture_firesAlongsideSamplerEviction() {
            var liveness = new HashMap<NodeId, SwimHealth>();
            var clock = new AtomicLong(0L);
            Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
            var presenceSampler = presenceSampler(SAMPLER_SELF, health, INTERVAL, K_UP, K_DOWN, clock::get);

            liveness.put(A, SwimHealth.HEALTHY);
            for (var i = 0; i < K_UP; i++) {
                presenceSampler.sample();
            }
            assertThat(presenceSampler.currentMembers()).contains(A);

            var manager = MembershipFsm.membershipFsm(presenceSampler);
            var fired = new ArrayList<NodeId>();
            manager.onConfirmedDeparture(fired::add);
            manager.seed(Set.of(A));
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(presenceSampler.currentMembers()).doesNotContain(A);
            assertThat(fired).containsExactly(A);
        }

        /// Passing `null` resets the listener to the no-op — a subsequent death does not throw and does
        /// not invoke a stale listener.
        @Test
        void onConfirmedDeparture_nullResetsToNoop() {
            var manager = activeManager();
            var fired = new ArrayList<NodeId>();
            manager.onConfirmedDeparture(fired::add);
            manager.onConfirmedDeparture(null);

            driveToDead(manager, A, 4L);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(fired).isEmpty();
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
            var manager = MembershipFsm.membershipFsm(emptySampler());

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
            var manager = MembershipFsm.membershipFsm(emptySampler());

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
            var manager = MembershipFsm.membershipFsm(emptySampler());

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

    @Nested
    class Descriptor {
        @Test
        void onMemberDescriptor_upsertsAddressRoleSource_withoutChangingLifecycleState() {
            var manager = activeManager();

            promoteToMember(manager, A);
            assertThat(manager.memberStates()).containsEntry(A, "Member");

            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));

            assertThat(manager.memberStates()).containsEntry(A, "Member");
            assertThat(manager.desiredConnections())
                    .contains(new PeerTarget(A, address("10.0.0.1", 7000)));
        }

        @Test
        void onMemberDescriptor_onUnseenId_linksFsmInObservedAndDoesNotPromote() {
            var manager = activeManager();

            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));

            assertThat(manager.memberStates()).containsEntry(A, "Observed");
            assertThat(manager.effective()).isZero();
            assertThat(manager.desiredConnections()).isEmpty();
        }

        @Test
        void onMemberDescriptor_laterDescriptor_overwritesLastWins() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.9", 7100));

            assertThat(manager.desiredConnections())
                    .containsExactly(new PeerTarget(A, address("10.0.0.9", 7100)));
        }
    }

    @Nested
    class DesiredConnections {
        @Test
        void desiredConnections_includesMemberAndSuspect_withKnownAddress() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            manager.onMemberDescriptor(coreInfo(B, "10.0.0.2", 7000));
            manager.onSwimFaulty(B, 4L);
            assertThat(manager.memberStates()).containsEntry(B, "Suspect");

            assertThat(manager.desiredConnections())
                    .containsExactlyInAnyOrder(new PeerTarget(A, address("10.0.0.1", 7000)),
                                               new PeerTarget(B, address("10.0.0.2", 7000)));
        }

        @Test
        void desiredConnections_excludesDeadDepartingObserved() {
            var manager = activeManager();

            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            assertThat(manager.memberStates()).containsEntry(A, "Observed");

            promoteToMember(manager, B);
            manager.onMemberDescriptor(coreInfo(B, "10.0.0.2", 7000));
            manager.onSwimFaulty(B, 4L);
            manager.onDownHysteresisMet(B);
            assertThat(manager.memberStates()).containsEntry(B, "Departing");

            promoteToMember(manager, C);
            manager.onMemberDescriptor(coreInfo(C, "10.0.0.3", 7000));
            manager.onSwimFaulty(C, 4L);
            manager.onLivenessGone(C);
            assertThat(manager.memberStates()).containsEntry(C, "Dead");

            assertThat(manager.desiredConnections()).isEmpty();
        }

        @Test
        void desiredConnections_excludesExplicitWorkerRole() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            manager.onMemberDescriptor(workerInfo(B, "10.0.0.2", 7000));

            assertThat(manager.desiredConnections())
                    .containsExactly(new PeerTarget(A, address("10.0.0.1", 7000)));
        }

        @Test
        void desiredConnections_includesUnknownRole_allCoreCluster() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onMemberDescriptor(unlabeledInfo(A, "10.0.0.1", 7000));
            manager.onMemberDescriptor(unlabeledInfo(B, "10.0.0.2", 7000));

            assertThat(manager.desiredConnections())
                    .containsExactlyInAnyOrder(new PeerTarget(A, address("10.0.0.1", 7000)),
                                               new PeerTarget(B, address("10.0.0.2", 7000)));
        }

        @Test
        void desiredConnections_excludesUnknownAddress() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onMemberDescriptor(coreInfo(B, "10.0.0.2", 7000));

            assertThat(manager.desiredConnections())
                    .containsExactly(new PeerTarget(B, address("10.0.0.2", 7000)));
        }
    }

    @Nested
    class CoreMembers {
        @Test
        void coreMembers_includesCountedNonWorker_unknownRoleIncluded() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            promoteToMember(manager, C);
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            manager.onMemberDescriptor(workerInfo(B, "10.0.0.2", 7000));

            assertThat(manager.coreMembers()).containsExactlyInAnyOrder(A, C);
        }

        @Test
        void coreMembers_excludesDeadMember() {
            var manager = activeManager();

            promoteToMember(manager, A);
            driveToDead(manager, B, 4L);

            assertThat(manager.coreMembers()).containsExactly(A);
        }
    }

    @Nested
    class BroadcastEligibleMembers {
        @Test
        void broadcastEligibleMembers_includesObservedMemberSuspect_excludesDead() {
            var manager = activeManager();

            // A: bare OBSERVED (descriptor links the FSM without promoting).
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            assertThat(manager.memberStates()).containsEntry(A, "Observed");

            // B: MEMBER.
            promoteToMember(manager, B);
            assertThat(manager.memberStates()).containsEntry(B, "Member");

            // C: SUSPECT (still in the lifecycle).
            promoteToMember(manager, C);
            manager.onSwimSuspect(C, 2L);
            assertThat(manager.memberStates()).containsEntry(C, "Suspect");

            // D: terminally DEAD — the storm's zombie, the only exclusion.
            var d = new NodeId("node-d");
            driveToDead(manager, d, 4L);
            assertThat(manager.memberStates()).containsEntry(d, "Dead");

            assertThat(manager.broadcastEligibleMembers())
                    .as("OBSERVED + MEMBER + SUSPECT stay broadcast targets; only DEAD is excluded")
                    .containsExactlyInAnyOrder(A, B, C);
        }

        @Test
        void broadcastEligibleMembers_includesDeparting() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);
            manager.onDownHysteresisMet(A);
            assertThat(manager.memberStates()).containsEntry(A, "Departing");

            assertThat(manager.broadcastEligibleMembers())
                    .as("a DEPARTING member is still draining and must keep receiving consensus")
                    .containsExactly(A);
        }

        @Test
        void broadcastEligibleMembers_includesWorkerRole_noRoleFilter() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            manager.onMemberDescriptor(workerInfo(B, "10.0.0.2", 7000));

            assertThat(manager.broadcastEligibleMembers())
                    .as("broadcast carries more than consensus — NO worker/role filter (#241 later)")
                    .containsExactlyInAnyOrder(A, B);
        }

        @Test
        void broadcastEligibleMembers_excludesDeadMember() {
            var manager = activeManager();

            promoteToMember(manager, A);
            driveToDead(manager, B, 4L);

            assertThat(manager.broadcastEligibleMembers()).containsExactly(A);
        }
    }

    @Nested
    class ReachableMembers {
        @Test
        void reachableMembers_filtersToCountedMembers_preservingOrder() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            driveToDead(manager, C, 4L);

            assertThat(manager.reachableMembers(List.of(C, B, A))).containsExactly(B, A);
        }

        @Test
        void reachableMembers_emptyWhenNoCandidatesCount() {
            var manager = activeManager();

            driveToDead(manager, A, 4L);

            assertThat(manager.reachableMembers(List.of(A))).isEmpty();
        }

        @Test
        void reachableMembers_includesObservedAndDeparting_excludesOnlyDead() {
            var manager = activeManager();

            // observed: bare descriptor links the FSM in OBSERVED without promoting.
            var observed = new NodeId("observed");
            manager.onMemberDescriptor(coreInfo(observed, "10.0.0.9", 7000));
            assertThat(manager.memberStates()).containsEntry(observed, "Observed");

            // member: promoted MEMBER.
            var member = new NodeId("member");
            promoteToMember(manager, member);

            // suspect: MEMBER then a bare SWIM-suspect.
            var suspect = new NodeId("suspect");
            promoteToMember(manager, suspect);
            manager.onSwimSuspect(suspect, 2L);
            assertThat(manager.memberStates()).containsEntry(suspect, "Suspect");

            // departing: MEMBER then SWIM-faulty + down-hysteresis → DEPARTING (still UP, draining).
            var departing = new NodeId("departing");
            promoteToMember(manager, departing);
            manager.onSwimFaulty(departing, 3L);
            manager.onDownHysteresisMet(departing);
            assertThat(manager.memberStates()).containsEntry(departing, "Departing");

            // dead: co-confirmed.
            var dead = new NodeId("dead");
            driveToDead(manager, dead, 4L);
            assertThat(manager.memberStates()).containsEntry(dead, "Dead");

            assertThat(manager.reachableMembers(List.of(observed, member, suspect, departing, dead)))
                    .as("best-effort serving set is NOT-DEAD: OBSERVED + DEPARTING serve too, only DEAD is excluded")
                    .containsExactly(observed, member, suspect, departing);
        }
    }

    @Nested
    class MemberDescriptors {
        @Test
        void memberDescriptor_returnsStoredAddressRoleSource() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(sourcedInfo(A, "10.0.0.1", 7000, "core", "seed"));

            var descriptor = descriptorOf(manager, A);
            assertThat(descriptor.address()).isEqualTo(Option.some(address("10.0.0.1", 7000)));
            assertThat(descriptor.role()).isEqualTo("core");
            assertThat(descriptor.source()).isEqualTo("seed");
        }

        @Test
        void memberDescriptor_untrackedId_returnsNone() {
            var manager = activeManager();

            assertThat(manager.memberDescriptor(A).isEmpty()).isTrue();
        }

        @Test
        void memberDescriptor_survivesIntoDead_sourceStillQueryable() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(sourcedInfo(A, "10.0.0.1", 7000, "core", "replacement"));

            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);
            assertThat(manager.memberStates()).containsEntry(A, "Dead");

            assertThat(descriptorOf(manager, A).source()).isEqualTo("replacement");
        }

        @Test
        void memberDescriptors_snapshotsAllTrackedMembers() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onMemberDescriptor(sourcedInfo(A, "10.0.0.1", 7000, "core", "seed"));
            manager.onMemberDescriptor(sourcedInfo(B, "10.0.0.2", 7000, "worker", "scale-up"));

            var snapshot = manager.memberDescriptors();
            assertThat(snapshot).containsOnlyKeys(A, B);
            assertThat(snapshot.get(A).source()).isEqualTo("seed");
            assertThat(snapshot.get(B).role()).isEqualTo("worker");
        }

        @Test
        void memberDescriptors_unobservedMember_carriesUnknownDescriptor() {
            var manager = activeManager();

            promoteToMember(manager, A);

            var snapshot = manager.memberDescriptors();
            assertThat(snapshot).containsKey(A);
            assertThat(snapshot.get(A).address().isEmpty()).isTrue();
            assertThat(snapshot.get(A).role()).isEmpty();
        }
    }

    // --- helpers ---

    private static MemberDescriptor descriptorOf(MembershipFsm manager, NodeId id) {
        return manager.memberDescriptor(id).or(MemberDescriptor.UNKNOWN);
    }

    private static NodeAddress address(String host, int port) {
        return NodeAddress.nodeAddress(host, port).unwrap();
    }

    private static NodeInfo sourcedInfo(NodeId id, String host, int port, String role, String source) {
        return labeledInfo(id, host, port, Map.of(NodeInfo.LABEL_ROLE, role, NodeInfo.LABEL_SOURCE, source));
    }

    private static NodeInfo coreInfo(NodeId id, String host, int port) {
        return labeledInfo(id, host, port, Map.of(NodeInfo.LABEL_ROLE, "core"));
    }

    private static NodeInfo workerInfo(NodeId id, String host, int port) {
        return labeledInfo(id, host, port, Map.of(NodeInfo.LABEL_ROLE, "worker"));
    }

    private static NodeInfo unlabeledInfo(NodeId id, String host, int port) {
        return labeledInfo(id, host, port, Map.of());
    }

    private static NodeInfo labeledInfo(NodeId id, String host, int port, Map<String, String> labels) {
        return NodeInfo.nodeInfo(id, address(host, port), NodeRole.ACTIVE, labels);
    }

    private static void promoteToMember(MembershipFsm manager, NodeId id) {
        manager.onSwimHealthy(id, 1L);
    }

    /// Membership-FSM unification (Wave D, consumer #4): the FSM-state → quiescence `HealthHint`
    /// projection that replaces the SWIM-hints map feeding `ClusterGenerationProjector`. Downgrade-only
    /// (DEAD → FAULTY, SUSPECT → SUSPECTED); every healthy-by-construction state is OMITTED so the
    /// projector defaults it to HEALTHY — reproducing the swimHints semantics exactly.
    @Nested
    class HealthHints {
        @Test
        void member_isOmitted_soProjectorDefaultsHealthy() {
            var manager = activeManager();

            promoteToMember(manager, A);
            assertThat(manager.memberStates()).containsEntry(A, "Member");
            assertThat(manager.healthHints()).doesNotContainKey(A);
        }

        @Test
        void observed_isOmitted_soProjectorDefaultsHealthy() {
            var manager = activeManager();

            manager.onMemberDescriptor(unlabeledInfo(A, "host-a", 9001));
            assertThat(manager.memberStates()).containsEntry(A, "Observed");
            assertThat(manager.healthHints()).doesNotContainKey(A);
        }

        @Test
        void suspect_mapsToSuspected() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onSwimSuspect(A, 2L);
            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(manager.healthHints()).containsEntry(A, HealthHint.SUSPECTED);
        }

        @Test
        void departing_isOmitted_soProjectorDefaultsHealthy() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onSwimSuspect(A, 2L);
            manager.onDownHysteresisMet(A);
            assertThat(manager.memberStates()).containsEntry(A, "Departing");
            assertThat(manager.healthHints()).doesNotContainKey(A);
        }

        @Test
        void dead_mapsToFaulty() {
            var manager = activeManager();

            driveToDead(manager, A, 4L);
            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(manager.healthHints()).containsEntry(A, HealthHint.FAULTY);
        }

        @Test
        void mixedCluster_carriesOnlyDowngrades() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onSwimSuspect(B, 2L);
            driveToDead(manager, C, 4L);

            var hints = manager.healthHints();
            assertThat(hints).doesNotContainKey(A);
            assertThat(hints).containsEntry(B, HealthHint.SUSPECTED);
            assertThat(hints).containsEntry(C, HealthHint.FAULTY);
        }
    }

    /// #68 — the quiesce SUSPECTED health-hint ages out after a TTL of no fresh doubt (parity with the
    /// legacy `SwimHintsRegistry#currentTtlFiltered`), while the member STAYS in FSM SUSPECT and in
    /// `countedMembers` (membership unaffected). The default factory uses TTL = `Long.MAX_VALUE`
    /// (never decay), so this is exercised only via the clock-injecting factory overload.
    @Nested
    class SuspectHintTtlDecay {
        private static final long TTL_MS = 1000L;

        @Test
        void suspectHint_freshDoubt_isSuspected() {
            var clock = new long[]{10_000L};
            var manager = ttlManager(clock);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 2L);
            assertThat(manager.memberStates()).containsEntry(A, "Suspect");

            assertThat(manager.healthHints()).containsEntry(A, HealthHint.SUSPECTED);
        }

        @Test
        void suspectHint_pastTtl_decaysToHealthyButStillCounts() {
            var clock = new long[]{10_000L};
            var manager = ttlManager(clock);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 2L);

            clock[0] = 10_000L + TTL_MS + 1L;

            assertThat(manager.healthHints())
                    .as("a stale one-shot SWIM-suspect decays OUT of the quiesce hint after the TTL")
                    .doesNotContainKey(A);
            assertThat(manager.memberStates())
                    .as("membership is unaffected — the member stays in FSM SUSPECT")
                    .containsEntry(A, "Suspect");
            assertThat(manager.countedMembers())
                    .as("a decayed-hint SUSPECT still counts toward effective membership")
                    .contains(A);
        }

        @Test
        void suspectHint_freshDoubtAfterDecay_reStampsToSuspected() {
            var clock = new long[]{10_000L};
            var manager = ttlManager(clock);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 2L);

            clock[0] = 10_000L + TTL_MS + 1L;
            assertThat(manager.healthHints()).doesNotContainKey(A);

            // A fresh doubt re-stamps the doubt time → SUSPECTED again.
            manager.onSwimSuspect(A, 3L);
            assertThat(manager.healthHints()).containsEntry(A, HealthHint.SUSPECTED);
        }

        private static MembershipFsm ttlManager(long[] clock) {
            return MembershipFsm.membershipFsm(emptySampler(), FsmObserver.noop(), () -> clock[0], TTL_MS);
        }
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
