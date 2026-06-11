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
import org.pragmatica.consensus.net.NetworkServiceMessage;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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

    /// Short terminal-eviction backstop (#131 Model C) for the existing co-confirmation tests that
    /// assert the TERMINAL DEAD outcome: with a near-zero window the deferred backstop fires almost
    /// immediately, so a co-confirmed-dead member reaches DEAD quickly and those tests poll for it via
    /// [`#awaitDead`]. The Model C nested tests use their own explicit windows (LONG to prove the
    /// SUSPECT hold, SHORT to prove the backstop fires / a recovery cancels it).
    private static final TimeSpan SHORT_BACKSTOP = TimeSpan.timeSpan(40).millis();
    /// Default suspect-hint TTL for the short-backstop factory used by [`#activeManager`] — never
    /// decay (byte-identical to the default factory), matching the pre-#131 behaviour for every hint
    /// assertion.
    private static final long NO_HINT_DECAY = Long.MAX_VALUE;

    private static MembershipFsm activeManager() {
        return shortBackstopManager(emptySampler());
    }

    /// Manager wired with the short Model C backstop so co-confirmed death reaches terminal DEAD
    /// promptly (polled via [`#awaitDead`]). Used by every legacy co-confirmation test; the deferral
    /// itself (the SUSPECT hold) is exercised separately by the [`ModelCBackstop`] nested tests with a
    /// LONG window.
    private static MembershipFsm shortBackstopManager(PresenceSampler sampler) {
        return MembershipFsm.membershipFsm(sampler, FsmObserver.noop(), System::currentTimeMillis, NO_HINT_DECAY, SHORT_BACKSTOP);
    }

    /// Poll until `id` has reached terminal DEAD in `manager` (the Model C backstop has fired). Real
    /// SharedScheduler time drives the backstop, so the terminal outcome is asynchronous — every
    /// legacy co-confirmation assertion that previously read DEAD synchronously now awaits it here. The
    /// 2s ceiling is comfortably above the 40ms [`#SHORT_BACKSTOP`]; widen the ceiling (not the
    /// backstop) if real-time scheduling ever makes it flaky.
    private static void awaitDead(MembershipFsm manager, NodeId id) {
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(manager.memberStates()).containsEntry(id, "Dead"));
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
            awaitDead(manager, A);
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

            var manager = shortBackstopManager(presenceSampler);
            manager.seed(Set.of(A));
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);

            awaitDead(manager, A);
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

            awaitDead(manager, A);
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

            var manager = shortBackstopManager(presenceSampler);
            var fired = new ArrayList<NodeId>();
            manager.onConfirmedDeparture(fired::add);
            manager.seed(Set.of(A));
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);

            awaitDead(manager, A);
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

    /// Wave-4 membership-delta edge (cluster-topology-overhaul spec): the FSM emits one typed
    /// [`MembershipDeltaEdge`] per counted-set lifecycle edge from the central dispatch
    /// chokepoint — JOINED on the first OBSERVED→MEMBER promotion, REMOVED on a fresh DEAD
    /// edge of a previously-JOINED member. `everJoined` semantics: an OBSERVED→DEAD member
    /// that never reached MEMBER emits NOTHING (the spec's tangential consideration); a
    /// fenced rejoin re-emits JOINED (the flag clears with the REMOVED emission).
    @Nested
    class DeltaEdge {
        @Test
        void onSwimHealthy_firstPromotion_emitsJoinedWithIncarnationAndRole() {
            var manager = activeManager();
            var edges = new ArrayList<MembershipDeltaEdge>();
            manager.onMembershipDelta(edges::add);

            manager.onMemberDescriptor(labeledInfo(A, "host-a", 6001, Map.of(NodeInfo.LABEL_ROLE, "core")));
            manager.onSwimHealthy(A, 7L);

            assertThat(edges).hasSize(1);
            assertThat(edges.getFirst().node()).isEqualTo(A);
            assertThat(edges.getFirst().kind()).isEqualTo(MembershipDeltaEdge.Kind.JOINED);
            assertThat(edges.getFirst().incarnation()).isEqualTo(7L);
            assertThat(edges.getFirst().role()).isEqualTo("core");
        }

        /// The boot seed promotes through the SAME chokepoint as a SWIM-driven promotion, so
        /// seeded members are baselined too — without this, an original core's later death
        /// would emit no REMOVED (the #245 gap re-opened for boot-seeded members).
        @Test
        void seed_promotion_emitsJoinedEdge() {
            var manager = activeManager();
            var edges = new ArrayList<MembershipDeltaEdge>();
            manager.onMembershipDelta(edges::add);

            manager.seed(Set.of(A));

            assertThat(edges).hasSize(1);
            assertThat(edges.getFirst().node()).isEqualTo(A);
            assertThat(edges.getFirst().kind()).isEqualTo(MembershipDeltaEdge.Kind.JOINED);
        }

        /// A worker-labelled join still fires the (role-agnostic) FSM edge with the worker
        /// role in the payload — the core-scoping filter lives in the projector.
        @Test
        void workerLabelledPromotion_emitsJoinedCarryingWorkerRole() {
            var manager = activeManager();
            var edges = new ArrayList<MembershipDeltaEdge>();
            manager.onMembershipDelta(edges::add);

            manager.onMemberDescriptor(labeledInfo(A, "host-a", 6001, Map.of(NodeInfo.LABEL_ROLE, "worker")));
            manager.onSwimHealthy(A, 3L);

            assertThat(edges).hasSize(1);
            assertThat(edges.getFirst().kind()).isEqualTo(MembershipDeltaEdge.Kind.JOINED);
            assertThat(edges.getFirst().role()).isEqualTo("worker");
        }

        /// Death of a previously-JOINED member emits REMOVED exactly once (graceful path —
        /// synchronous DEAD, no backstop timing).
        @Test
        void gracefulDeath_afterJoined_emitsJoinedThenRemoved() {
            var manager = activeManager();
            var edges = new ArrayList<MembershipDeltaEdge>();
            manager.onMembershipDelta(edges::add);

            promoteToMember(manager, A);
            manager.onSwimDeparted(A, 5L);

            assertThat(edges).hasSize(2);
            assertThat(edges.getFirst().kind()).isEqualTo(MembershipDeltaEdge.Kind.JOINED);
            assertThat(edges.getLast().kind()).isEqualTo(MembershipDeltaEdge.Kind.REMOVED);
            assertThat(edges.getLast().node()).isEqualTo(A);
            assertThat(edges.getLast().incarnation()).isEqualTo(5L);
        }

        /// Co-confirmed death (the backstop path) also emits REMOVED — all DEAD paths flow
        /// through the same chokepoint.
        @Test
        void coConfirmedDeath_afterJoined_emitsRemoved() {
            var manager = activeManager();
            var edges = new ArrayList<MembershipDeltaEdge>();
            manager.onMembershipDelta(edges::add);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);

            awaitDead(manager, A);
            assertThat(edges).hasSize(2);
            assertThat(edges.getLast().kind()).isEqualTo(MembershipDeltaEdge.Kind.REMOVED);
        }

        /// `everJoined` semantics (the spec's tangential consideration): an OBSERVED member
        /// that never reached MEMBER emits NOTHING on its OBSERVED→DEAD join-grace expiry —
        /// it never counted, so its death is a no-op delta (while the confirmed-departure
        /// listener still fires, covered in [`ConfirmedDeparture`]).
        @Test
        void joinGraceExpiry_neverMember_emitsNoDelta() {
            var manager = activeManager();
            var edges = new ArrayList<MembershipDeltaEdge>();
            manager.onMembershipDelta(edges::add);

            manager.onPeerDisconnected(A);
            assertThat(manager.memberStates()).containsEntry(A, "Observed");
            manager.onJoinGraceExpired(A);

            assertThat(manager.memberStates()).containsEntry(A, "Dead");
            assertThat(edges).isEmpty();
        }

        /// No duplicate JOINED without an intervening REMOVED: repeated healthy edges and a
        /// SUSPECT→MEMBER recovery never re-fire the join.
        @Test
        void suspectRecovery_andRepeatedHealthy_emitNoDuplicateJoined() {
            var manager = activeManager();
            var edges = new ArrayList<MembershipDeltaEdge>();
            manager.onMembershipDelta(edges::add);

            promoteToMember(manager, A);
            manager.onSwimHealthy(A, 2L);
            manager.onSwimSuspect(A, 3L);
            manager.onSwimHealthy(A, 4L);

            assertThat(manager.memberStates()).containsEntry(A, "Member");
            assertThat(edges).hasSize(1);
            assertThat(edges.getFirst().kind()).isEqualTo(MembershipDeltaEdge.Kind.JOINED);
        }

        /// A fenced rejoin (higher incarnation after DEAD) re-fires JOINED — the REMOVED
        /// emission cleared `everJoined`, so the sequence is JOINED / REMOVED / JOINED.
        @Test
        void rejoinAfterDeath_emitsFreshJoined() {
            var manager = activeManager();
            var edges = new ArrayList<MembershipDeltaEdge>();
            manager.onMembershipDelta(edges::add);

            promoteToMember(manager, A);
            manager.onSwimDeparted(A, 5L);
            manager.onSwimHealthy(A, 6L);

            assertThat(manager.memberStates()).containsEntry(A, "Member");
            assertThat(edges).hasSize(3);
            assertThat(edges.get(0).kind()).isEqualTo(MembershipDeltaEdge.Kind.JOINED);
            assertThat(edges.get(1).kind()).isEqualTo(MembershipDeltaEdge.Kind.REMOVED);
            assertThat(edges.get(2).kind()).isEqualTo(MembershipDeltaEdge.Kind.JOINED);
            assertThat(edges.get(2).incarnation()).isEqualTo(6L);
        }

        /// A `null` listener resets to the no-op (API symmetry with `onConfirmedDeparture`).
        @Test
        void onMembershipDelta_nullResetsToNoop() {
            var manager = activeManager();
            var edges = new ArrayList<MembershipDeltaEdge>();
            manager.onMembershipDelta(edges::add);
            manager.onMembershipDelta(null);

            promoteToMember(manager, A);

            assertThat(edges).isEmpty();
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
            awaitDead(manager, A);
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
            awaitDead(manager, A);
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

        @Test
        void onMemberDescriptor_emptyAddressUpdate_doesNotDowngradeKnownAddress() {
            // Step 3 guard: a descriptor update that would ERASE a known non-empty address to none must
            // be ignored for the address field — otherwise the member silently drops out of
            // desiredConnections (which skips address-unknown members) and is never dialed again.
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            // A degraded observation with NO resolved address arrives.
            manager.onMemberDescriptor(addresslessInfo(A));

            assertThat(manager.desiredConnections())
                    .as("a known address must NOT be downgraded to none by an empty-address update")
                    .containsExactly(new PeerTarget(A, address("10.0.0.1", 7000)));
        }

        @Test
        void onMemberDescriptor_emptyAddressUpdate_stillAppliesRoleLastWins() {
            // The guard retains only what the update LACKS; a non-blank incoming role still wins, so
            // a worker re-label (which excludes the member from the core dial-set) takes effect even
            // when the re-label observation carries no address.
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            manager.onMemberDescriptor(addresslessWorkerInfo(A));

            assertThat(manager.desiredConnections())
                    .as("an address-less worker re-label still excludes the member from the core dial-set")
                    .isEmpty();
        }

        @Test
        void onMemberDescriptor_blankIncomingRole_keepsKnownRole() {
            // Wave 2 / audit M9: a label-less observation (e.g. a gossip-rebuilt peer NodeInfo) must
            // NOT wipe the member's self-asserted role to blank — blank role counts as core, so the
            // erase would silently re-classify a worker as core.
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(workerInfo(A, "10.0.0.1", 7000));
            manager.onMemberDescriptor(unlabeledInfo(A, "10.0.0.1", 7000));

            assertThat(descriptorOf(manager, A).role())
                    .as("a blank incoming role must not erase a known role")
                    .isEqualTo("worker");
        }

        @Test
        void onMemberDescriptor_nonBlankIncomingRole_replacesRole() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            manager.onMemberDescriptor(workerInfo(A, "10.0.0.1", 7000));

            assertThat(descriptorOf(manager, A).role())
                    .as("a non-blank incoming role still wins (re-label works)")
                    .isEqualTo("worker");
        }

        @Test
        void onMemberDescriptor_blankIncomingSource_keepsKnownSource() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(sourcedInfo(A, "10.0.0.1", 7000, "core", "seed"));
            manager.onMemberDescriptor(unlabeledInfo(A, "10.0.0.1", 7000));

            assertThat(descriptorOf(manager, A).source())
                    .as("a blank incoming source must not erase a known source")
                    .isEqualTo("seed");
        }

        @Test
        void onMemberDescriptor_nonBlankIncomingSource_replacesSource() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(sourcedInfo(A, "10.0.0.1", 7000, "core", "seed"));
            manager.onMemberDescriptor(sourcedInfo(A, "10.0.0.1", 7000, "core", "replacement"));

            assertThat(descriptorOf(manager, A).source())
                    .as("a non-blank incoming source still wins")
                    .isEqualTo("replacement");
        }

        @Test
        void onMemberDescriptor_addresslessUnlabeledUpdate_keepsAddressRoleAndSource() {
            // Combined per-field guard: an observation carrying NO address and NO labels (the
            // degenerate gossip-rebuilt NodeInfo) erases NOTHING previously known.
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(sourcedInfo(A, "10.0.0.1", 7000, "worker", "scale-up"));
            manager.onMemberDescriptor(addresslessUnlabeledInfo(A));

            var descriptor = descriptorOf(manager, A);
            assertThat(descriptor.address()).isEqualTo(Option.some(address("10.0.0.1", 7000)));
            assertThat(descriptor.role()).isEqualTo("worker");
            assertThat(descriptor.source()).isEqualTo("scale-up");
        }
    }

    /// Wave 2 role-reach (cluster-topology-overhaul): the `ConnectionEstablished`-driven descriptor
    /// feed. The QUIC transport now supplies the Hello NodeInfo for ALL peers (known + unknown) and
    /// AetherNode routes `connection.nodeInfo().onPresent(membershipFsm::onMemberDescriptor)`, so a
    /// directly-dialed peer's self-asserted role/source labels land in the member descriptor on the
    /// FIRST handshake — independent of SWIM seed lists or label-less gossip MembershipUpdate. These
    /// tests exercise that exact feed expression against the real FSM, including the
    /// blank-downgrade-guard interplay (a label-less Hello erases nothing).
    @Nested
    class HelloDescriptorReach {
        @Test
        void connectionEstablishedHello_withLabels_landsRoleSourceAndAddressInDescriptor() {
            var manager = activeManager();

            promoteToMember(manager, A);

            var hello = NetworkServiceMessage.ConnectionEstablished.connectionEstablished(A,
                                                                                          sourcedInfo(A, "10.0.0.7", 7000, "worker", "docker"));
            hello.nodeInfo().onPresent(manager::onMemberDescriptor);

            var descriptor = descriptorOf(manager, A);
            assertThat(descriptor.role())
                    .as("the Hello role label must land in the descriptor on first handshake")
                    .isEqualTo("worker");
            assertThat(descriptor.source()).isEqualTo("docker");
            assertThat(descriptor.address()).isEqualTo(Option.some(address("10.0.0.7", 7000)));
        }

        @Test
        void connectionEstablishedHello_labelLess_erasesNothing() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(sourcedInfo(A, "10.0.0.7", 7000, "worker", "docker"));

            var labelLessHello = NetworkServiceMessage.ConnectionEstablished.connectionEstablished(A,
                                                                                                   unlabeledInfo(A, "10.0.0.7", 7000));
            labelLessHello.nodeInfo().onPresent(manager::onMemberDescriptor);

            var descriptor = descriptorOf(manager, A);
            assertThat(descriptor.role())
                    .as("a label-less Hello must not erase the known role (blank-downgrade guard)")
                    .isEqualTo("worker");
            assertThat(descriptor.source()).isEqualTo("docker");
        }

        @Test
        void connectionEstablishedWithoutInfo_feedsNothing() {
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(sourcedInfo(A, "10.0.0.7", 7000, "worker", "docker"));

            var bare = NetworkServiceMessage.ConnectionEstablished.connectionEstablished(A);
            bare.nodeInfo().onPresent(manager::onMemberDescriptor);

            assertThat(descriptorOf(manager, A).role())
                    .as("an info-less ConnectionEstablished feeds nothing — the descriptor is untouched")
                    .isEqualTo("worker");
        }
    }

    /// Drain-safety grace age source ([`MembershipFsm#memberAgeMs`], cluster-topology-overhaul
    /// Wave 2): wall-clock age since the member's FIRST observation (tracking creation), on the
    /// manager's injected clock; `none()` for an untracked id; retained across DEAD/rejoin (the
    /// descriptor is retained too, so the role-propagation race the consumer guards against does
    /// not re-open on rejoin).
    @Nested
    class MemberAge {
        @Test
        void memberAgeMs_trackedMember_isClockDeltaFromFirstObservation() {
            var clock = new AtomicLong(1_000L);
            var manager = MembershipFsm.membershipFsm(emptySampler(), FsmObserver.noop(), clock::get, NO_HINT_DECAY, SHORT_BACKSTOP);

            promoteToMember(manager, A);
            clock.set(31_000L);

            assertThat(manager.memberAgeMs(A)).isEqualTo(Option.some(30_000L));
        }

        @Test
        void memberAgeMs_untrackedId_isNone() {
            var manager = activeManager();

            assertThat(manager.memberAgeMs(A).isEmpty()).isTrue();
        }

        @Test
        void memberAgeMs_retainedAcrossDeadRejoin_keepsOriginalStamp() {
            var clock = new AtomicLong(1_000L);
            var manager = MembershipFsm.membershipFsm(emptySampler(), FsmObserver.noop(), clock::get, NO_HINT_DECAY, SHORT_BACKSTOP);

            promoteToMember(manager, A);
            manager.onSwimDeparted(A, 2L);
            clock.set(5_000L);
            // Higher-incarnation recovery re-arms the SAME tracking (DEAD entries are retained).
            manager.onSwimHealthy(A, 3L);

            assertThat(manager.memberAgeMs(A))
                    .as("rejoin re-arms the same tracking — the first-observation stamp is retained")
                    .isEqualTo(Option.some(4_000L));
        }
    }

    @Nested
    class DesiredConnections {
        @Test
        void desiredConnections_keepsSuspectCore_soTransportStillReconcilesIt() {
            // Step 3 verify (no behavior change expected): a SWIM-SUSPECT core member counts toward
            // effective and MUST stay in the transport's desired dial-set, so the reconciler keeps a
            // momentarily-suspected peer's link converging rather than tearing it down.
            var manager = activeManager();

            promoteToMember(manager, A);
            manager.onMemberDescriptor(coreInfo(A, "10.0.0.1", 7000));
            manager.onSwimSuspect(A, 5L);
            assertThat(manager.memberStates()).containsEntry(A, "Suspect");

            assertThat(manager.desiredConnections())
                    .as("a SWIM-SUSPECT core member stays in the desired dial-set")
                    .containsExactly(new PeerTarget(A, address("10.0.0.1", 7000)));
        }

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
            awaitDead(manager, C);
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
            awaitDead(manager, A);
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

    /// A NodeInfo whose dial-preferred (resolved) address is ABSENT (null) — its derived
    /// MemberDescriptor has an empty address. Used to exercise the address-downgrade guard.
    private static NodeInfo addresslessInfo(NodeId id) {
        return NodeInfo.nodeInfo(id, address("0.0.0.0", 1), NodeRole.ACTIVE,
                                 Map.of(NodeInfo.LABEL_ROLE, "core"), null);
    }

    /// Address-less observation that ALSO re-labels the member as a worker (non-blank role wins).
    private static NodeInfo addresslessWorkerInfo(NodeId id) {
        return NodeInfo.nodeInfo(id, address("0.0.0.0", 1), NodeRole.ACTIVE,
                                 Map.of(NodeInfo.LABEL_ROLE, "worker"), null);
    }

    /// An observation with NO resolved address and NO labels — the degenerate gossip-rebuilt
    /// NodeInfo. Exercises the combined per-field downgrade guard (Wave 2 / audit M9).
    private static NodeInfo addresslessUnlabeledInfo(NodeId id) {
        return NodeInfo.nodeInfo(id, address("0.0.0.0", 1), NodeRole.ACTIVE, Map.of(), null);
    }

    private static void promoteToMember(MembershipFsm manager, NodeId id) {
        manager.onSwimHealthy(id, 1L);
    }

    /// Membership-FSM unification (Wave D, consumer #4): the FSM-state → quiescence `HealthHint`
    /// projection that replaces the SWIM-hints map feeding `ClusterQuiescenceEvaluator`. Downgrade-only
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
        void dead_isOmitted_soTombstoneNeverPoisonsQuiescence() {
            var manager = activeManager();

            driveToDead(manager, A, 4L);
            assertThat(manager.memberStates())
                    .as("the member is retained in the map as a DEAD tombstone for incarnation-fenced rejoin")
                    .containsEntry(A, "Dead");
            assertThat(manager.healthHints())
                    .as("#68 — a terminally-DEAD tombstone must NOT emit a FAULTY quiesce hint")
                    .doesNotContainKey(A);
        }

        @Test
        void mixedCluster_carriesSuspectButNotDead() {
            var manager = activeManager();

            promoteToMember(manager, A);
            promoteToMember(manager, B);
            manager.onSwimSuspect(B, 2L);
            driveToDead(manager, C, 4L);

            var hints = manager.healthHints();
            assertThat(hints)
                    .as("a HEALTHY member is omitted (projector defaults it to HEALTHY)")
                    .doesNotContainKey(A);
            assertThat(hints)
                    .as("a SUSPECT member is carried — a real in-progress death still blocks quiescence")
                    .containsEntry(B, HealthHint.SUSPECTED);
            assertThat(hints)
                    .as("#68 — a co-confirmed-DEAD ghost is filtered out, so it cannot pin DEGRADED")
                    .doesNotContainKey(C);
        }

        /// #68 core regression: a SUSPECT (real in-progress death) STILL blocks quiescence while a
        /// terminally-DEAD ghost does NOT — only terminal DEAD is filtered, the SUSPECT window is
        /// preserved so genuine deaths are not masked.
        @Test
        void healthHints_suspectPresentDeadAbsent_onlyTerminalDeadIsFiltered() {
            var manager = activeManager();

            promoteToMember(manager, A);   // healthy MEMBER
            promoteToMember(manager, B);
            manager.onSwimSuspect(B, 2L);  // SUSPECT — real in-progress death
            driveToDead(manager, C, 4L);   // co-confirmed terminally DEAD ghost

            assertThat(manager.memberStates())
                    .containsEntry(A, "Member")
                    .containsEntry(B, "Suspect")
                    .containsEntry(C, "Dead");

            var hints = manager.healthHints();
            assertThat(hints)
                    .as("a genuinely co-confirmed-DEAD member is absent (its FAULTY tombstone is filtered)")
                    .doesNotContainKey(C);
            assertThat(hints)
                    .as("a SUSPECT member is still present, so real deaths block quiescence during the SUSPECT window")
                    .containsEntry(B, HealthHint.SUSPECTED);
            assertThat(hints)
                    .as("the healthy MEMBER is omitted (projector defaults it to HEALTHY)")
                    .doesNotContainKey(A);
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

    /// #131 Model C — DEFERRED terminal eviction. A node co-confirmed dead (SWIM-FAULTY ∧
    /// liveness-gone) during a brief network partition stays SUSPECT (counted, recoverable) instead of
    /// marching straight to DEAD. Terminal DEAD is reached only when the per-member backstop timer
    /// (= `quorumLossDrainThreshold`) fires OR via the existing confirmed-departure paths. A partition
    /// shorter than the backstop heals while the node is SUSPECT (a `SwimHealthy` recovery edge cancels
    /// the backstop) → it rejoins via SUSPECT→MEMBER and is never fenced. These tests drive the
    /// real-time backstop through the explicit-window factory overload: a LONG window proves the
    /// SUSPECT hold, a SHORT window proves the backstop fires and that a recovery cancels it.
    @Nested
    class ModelCBackstop {
        /// 30s window — long enough that the backstop CANNOT fire during the synchronous assertion, so
        /// the member is observed in its deferred SUSPECT hold.
        private static final TimeSpan LONG_BACKSTOP = TimeSpan.timeSpan(30).seconds();
        /// 150ms window — short enough to fire promptly when no recovery cancels it.
        private static final TimeSpan FIRING_BACKSTOP = TimeSpan.timeSpan(150).millis();

        /// Co-confirmed death (both planes) NO LONGER marches straight to DEAD: with a long backstop
        /// the member is held in SUSPECT and still counts immediately after co-confirmation.
        @Test
        void coConfirmedDead_dualSignal_staysSuspectAndCountedNotDead() {
            var manager = backstopManager(LONG_BACKSTOP);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);

            assertThat(manager.memberStates())
                    .as("co-confirmed death is DEFERRED — the member is held SUSPECT, not DEAD")
                    .containsEntry(A, "Suspect");
            assertThat(manager.countedMembers())
                    .as("a held-SUSPECT co-confirmed member still counts (recoverable)")
                    .contains(A);
        }

        /// The core anti-#131 property: a `SwimHealthy` recovery within the backstop window cancels the
        /// pending terminal, recovers SUSPECT→MEMBER, and the member is STILL not DEAD after the window
        /// has comfortably elapsed (the timer was cancelled, not merely outrun).
        @Test
        void coConfirmedDead_recoveryBeforeBackstop_cancelsBackstopAndRecoversToMember() {
            var manager = backstopManager(FIRING_BACKSTOP);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);
            // Recovery lands inside the window: higher incarnation drives SUSPECT→MEMBER and clears the
            // co-confirmation flags, cancelling the armed backstop.
            manager.onSwimHealthy(A, 5L);

            assertThat(manager.memberStates())
                    .as("a recovery edge within the window recovers the member SUSPECT→MEMBER")
                    .containsEntry(A, "Member");
            assertThat(manager.countedMembers()).contains(A);

            // Wait WELL beyond the backstop window: a cancelled timer must never fire.
            await().pollDelay(FIRING_BACKSTOP.millis() * 3, TimeUnit.MILLISECONDS)
                   .atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(manager.memberStates())
                           .as("the cancelled backstop must NOT later evict the recovered member")
                           .containsEntry(A, "Member"));
        }

        /// No recovery: the backstop fires after the window and performs the original terminal march —
        /// the member reaches DEAD and drops out of the count.
        @Test
        void coConfirmedDead_backstopExpires_evictsToDead() {
            var manager = backstopManager(FIRING_BACKSTOP);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);
            manager.onLivenessGone(A);

            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(manager.memberStates()).containsEntry(A, "Dead"));
            assertThat(manager.countedMembers())
                    .as("a backstop-evicted member is no longer counted")
                    .doesNotContain(A);
        }

        /// Regression guard: ONE plane alone (bare SWIM-FAULTY, no liveness-gone) never arms the
        /// backstop and never reaches DEAD — it stays SUSPECT and counted even after the window.
        @Test
        void singleSignal_swimFaultyOnly_staysSuspectNoBackstopTerminal() {
            var manager = backstopManager(FIRING_BACKSTOP);

            promoteToMember(manager, A);
            manager.onSwimFaulty(A, 4L);

            assertThat(manager.memberStates()).containsEntry(A, "Suspect");
            assertThat(manager.countedMembers()).contains(A);

            // Past the window a single-plane signal still must not have evicted.
            await().pollDelay(FIRING_BACKSTOP.millis() * 3, TimeUnit.MILLISECONDS)
                   .atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(manager.memberStates())
                               .as("a single death plane never arms the backstop, never evicts")
                               .containsEntry(A, "Suspect");
                       assertThat(manager.countedMembers()).contains(A);
                   });
        }

        private static MembershipFsm backstopManager(TimeSpan backstop) {
            return MembershipFsm.membershipFsm(emptySampler(), FsmObserver.noop(), System::currentTimeMillis, NO_HINT_DECAY, backstop);
        }
    }

    /// Drive `id` to terminal DEAD via co-confirmation (SWIM-FAULTY ∧ liveness-gone) and AWAIT the
    /// Model C backstop firing. Callers built with [`#activeManager`] / [`#shortBackstopManager`] use
    /// the 40ms [`#SHORT_BACKSTOP`], so DEAD lands within the [`#awaitDead`] ceiling. The deferral
    /// behaviour itself (the SUSPECT hold before the backstop) is covered by [`ModelCBackstop`].
    private static void driveToDead(MembershipFsm manager, NodeId id, long incarnation) {
        promoteToMember(manager, id);
        manager.onSwimFaulty(id, incarnation);
        manager.onLivenessGone(id);
        awaitDead(manager, id);
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
