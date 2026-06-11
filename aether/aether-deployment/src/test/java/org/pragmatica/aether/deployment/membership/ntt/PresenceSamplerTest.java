// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.swim.SwimObservation.DepartedObserved;
import org.pragmatica.swim.SwimObservation.FaultyObserved;
import org.pragmatica.swim.SwimObservation.HealthyObserved;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.ntt.PresenceSampler.presenceSampler;


/// Unit proof for [`PresenceSampler`] (membership v2 §6) — periodic-sample + delta +
/// asymmetric-hysteresis FSM. Drives the FSM deterministically via direct
/// [`PresenceSampler#sample`] calls (no real scheduler) against an injected mutable
/// health snapshot and an injected clock.
class PresenceSamplerTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId A = NodeId.randomNodeId();
    private static final NodeId B = NodeId.randomNodeId();
    private static final NodeId C = NodeId.randomNodeId();

    private static final TimeSpan INTERVAL = TimeSpan.timeSpan(100).millis();
    private static final int K_UP = 2;
    private static final int K_DOWN = 3;

    private Map<NodeId, SwimHealth> liveness;
    private AtomicInteger reconcileInvocations;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        liveness = new HashMap<>();
        reconcileInvocations = new AtomicInteger(0);
        clock = new AtomicLong(0);
    }

    private PresenceSampler sampler() {
        Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
        return presenceSampler(SELF, health, INTERVAL, K_UP, K_DOWN, clock::get,
                                   reconcileInvocations::incrementAndGet);
    }

    private PresenceSampler sampler(Consumer<NodeId> onDownHysteresisCrossing) {
        Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
        return presenceSampler(SELF, health, INTERVAL, K_UP, K_DOWN, clock::get,
                                   reconcileInvocations::incrementAndGet, onDownHysteresisCrossing);
    }

    private void healthy(NodeId node) {
        liveness.put(node, SwimHealth.HEALTHY);
    }

    private void absent(NodeId node) {
        liveness.remove(node);
    }

    private void faulty(NodeId node) {
        liveness.put(node, SwimHealth.FAULTY);
    }

    private void sampleTimes(PresenceSampler sampler, int times) {
        for (var i = 0; i < times; i++) {
            clock.addAndGet(1_000_000L);
            sampler.sample();
        }
    }

    @Nested
    class Hysteresis {
        @Test
        void sample_absorbsTransientFlap_noReconcile() {
            var sampler = sampler();

            healthy(A);
            sampleTimes(sampler, 1); // up-streak 1 (K_UP = 2, not yet stable)
            absent(A);
            sampleTimes(sampler, 1); // flips to down-streak before reaching K-up
            healthy(A);
            sampleTimes(sampler, 1); // back up, streak 1 again

            assertThat(reconcileInvocations.get()).isZero();
            assertThat(sampler.currentMembers()).containsExactly(SELF);
        }

        @Test
        void sample_stableJoin_invokesReconcileExactlyOnce() {
            var sampler = sampler();

            healthy(A);
            sampleTimes(sampler, K_UP - 1); // not yet stable
            assertThat(reconcileInvocations.get()).isZero();

            sampleTimes(sampler, 1); // K_UP-th consecutive healthy → ENTER
            sampleTimes(sampler, 3); // further healthy samples must NOT re-fire

            assertThat(reconcileInvocations.get()).isEqualTo(1);
            assertThat(sampler.currentMembers()).containsExactlyInAnyOrder(SELF, A);
        }

        @Test
        void sample_stableDeparture_invokesReconcileExactlyOnce() {
            var sampler = sampler();

            healthy(A);
            sampleTimes(sampler, K_UP); // A enters
            assertThat(reconcileInvocations.get()).isEqualTo(1);

            absent(A);
            sampleTimes(sampler, K_DOWN - 1); // not yet stable-departed
            assertThat(reconcileInvocations.get()).isEqualTo(1);

            sampleTimes(sampler, 1); // K_DOWN-th consecutive absent → LEAVE
            sampleTimes(sampler, 3); // further absent samples must NOT re-fire

            assertThat(reconcileInvocations.get()).isEqualTo(2);
            assertThat(sampler.currentMembers()).containsExactly(SELF);
        }

        @Test
        void sample_asymmetricEdges_upFastDownSlow() {
            var sampler = sampler();

            healthy(A);
            sampleTimes(sampler, K_UP); // fast admit at K_UP=2
            assertThat(sampler.currentMembers()).contains(A);

            absent(A);
            sampleTimes(sampler, K_UP); // K_UP absent samples are NOT enough to drop (K_DOWN=3)
            assertThat(sampler.currentMembers()).contains(A);

            sampleTimes(sampler, K_DOWN - K_UP); // reach K_DOWN total → drop
            assertThat(sampler.currentMembers()).containsExactly(SELF);
        }

        @Test
        void sample_faultyHealthCountsAsAbsent_drivesDeparture() {
            var sampler = sampler();

            healthy(A);
            sampleTimes(sampler, K_UP);
            assertThat(sampler.currentMembers()).contains(A);

            faulty(A); // FAULTY is not HEALTHY → absent for the candidate set
            sampleTimes(sampler, K_DOWN);

            assertThat(sampler.currentMembers()).containsExactly(SELF);
        }
    }

    /// DOWN-hysteresis crossing callback — the additive FSM-routing seam. Fires exactly at
    /// the crossing edge (mirroring the presence-sensor `stableMembers.remove`), not on every
    /// absent sample, and leaves the existing membership-removal + reconcile-trigger
    /// behaviour unchanged.
    @Nested
    class DownHysteresisCrossingCallback {
        @Test
        void advanceOne_downHysteresisCrossing_firesDownCrossingCallbackOnce() {
            var crossings = new ArrayList<NodeId>();
            var sampler = sampler(crossings::add);

            healthy(A);
            sampleTimes(sampler, K_UP); // A enters the stable set
            assertThat(sampler.currentMembers()).contains(A);
            assertThat(crossings).isEmpty(); // up edge does not fire the down-crossing

            absent(A);
            sampleTimes(sampler, K_DOWN - 1); // not yet at the crossing
            assertThat(crossings).isEmpty();

            sampleTimes(sampler, 1); // K_DOWN-th consecutive absent → DOWN crossing
            sampleTimes(sampler, 3); // further absent samples must NOT re-fire

            assertThat(crossings).containsExactly(A); // exactly once, with A's id
            // Existing presence-sensor removal + reconcile-trigger behaviour unchanged.
            assertThat(sampler.currentMembers()).containsExactly(SELF);
            assertThat(reconcileInvocations.get()).isEqualTo(2); // join + departure
        }
    }

    @Nested
    class Self {
        @Test
        void members_alwaysIncludesSelf_evenWithNoLiveness() {
            var sampler = sampler();

            sampleTimes(sampler, K_DOWN * 2);

            assertThat(sampler.currentMembers()).containsExactly(SELF);
            assertThat(sampler.currentMemberCount()).isEqualTo(1);
        }

        @Test
        void sample_selfNeverLeaves_evenIfReportedFaulty() {
            var sampler = sampler();

            faulty(SELF); // self is force-added to candidate set regardless
            sampleTimes(sampler, K_DOWN * 2);

            assertThat(sampler.currentMembers()).contains(SELF);
        }
    }

    @Nested
    class MemberSet {
        @Test
        void freshTracker_membersContainSelfOnly() {
            var sampler = sampler();

            assertThat(sampler.currentMembers()).containsExactly(SELF);
            assertThat(sampler.currentMemberCount()).isEqualTo(1);
        }

        @Test
        void memberCount_reflectsAddRemoveSequence() {
            var sampler = sampler();

            healthy(A);
            healthy(B);
            healthy(C);
            sampleTimes(sampler, K_UP);
            assertThat(sampler.currentMemberCount()).isEqualTo(4);

            absent(B);
            sampleTimes(sampler, K_DOWN);
            assertThat(sampler.currentMemberCount()).isEqualTo(3);
            assertThat(sampler.currentMembers()).containsExactlyInAnyOrder(SELF, A, C);
        }
    }

    @Nested
    class SwimObservationBias {
        @Test
        void onSwimObservation_healthy_biasesPresent_butStillRequiresKSamples() {
            var sampler = sampler();

            // A is NOT in the snapshot, but a HealthyObserved biases one sample present.
            sampler.onSwimObservation(new HealthyObserved(A, 1L));
            sampleTimes(sampler, 1); // up-streak 1 only
            assertThat(sampler.currentMembers()).containsExactly(SELF); // not bypassed

            sampleTimes(sampler, K_UP); // no continued presence → cannot reach K_UP again
            assertThat(sampler.currentMembers()).containsExactly(SELF);
            assertThat(reconcileInvocations.get()).isZero();
        }

        @Test
        void onSwimObservation_faulty_biasesAbsent_butDoesNotBypassHysteresis() {
            var sampler = sampler();

            healthy(A);
            sampleTimes(sampler, K_UP); // A is a stable member
            assertThat(sampler.currentMembers()).contains(A);

            // A is still SWIM-healthy in the snapshot, but FaultyObserved biases the NEXT
            // sample absent — a single biased sample must NOT evict A.
            sampler.onSwimObservation(new FaultyObserved(A, 2L));
            sampleTimes(sampler, 1);
            assertThat(sampler.currentMembers()).contains(A);
            assertThat(reconcileInvocations.get()).isEqualTo(1); // still only the join

            // Snapshot shows A healthy again → recovers, transient bias absorbed.
            sampleTimes(sampler, K_DOWN);
            assertThat(sampler.currentMembers()).contains(A);
            assertThat(reconcileInvocations.get()).isEqualTo(1);
        }

        @Test
        void onSwimObservation_departed_biasesAbsent() {
            var sampler = sampler();

            healthy(A);
            sampleTimes(sampler, K_UP);
            assertThat(sampler.currentMembers()).contains(A);

            absent(A);
            sampler.onSwimObservation(new DepartedObserved(A, 2L));
            sampleTimes(sampler, K_DOWN);

            assertThat(sampler.currentMembers()).containsExactly(SELF);
        }
    }

    @Nested
    class QuicHints {
        @Test
        void onQuicDisconnect_biasesAbsent_butDoesNotBypassHysteresis() {
            var sampler = sampler();

            healthy(A);
            sampleTimes(sampler, K_UP); // A is a stable member
            assertThat(sampler.currentMembers()).contains(A);

            // A is still SWIM-healthy, but QUIC says "likely gone". One biased sample must
            // NOT evict A (hysteresis).
            sampler.onQuicDisconnect(A);
            sampleTimes(sampler, 1);
            assertThat(sampler.currentMembers()).contains(A); // not bypassed
            assertThat(reconcileInvocations.get()).isEqualTo(1); // still only the join

            // Without a renewed hint, the next sample sees A SWIM-healthy → recovers.
            sampleTimes(sampler, K_DOWN);
            assertThat(sampler.currentMembers()).contains(A);
            assertThat(reconcileInvocations.get()).isEqualTo(1);
        }

        @Test
        void onQuicDisconnect_sustainedWithAbsence_eventuallyEvictsViaHysteresis() {
            var sampler = sampler();

            healthy(A);
            sampleTimes(sampler, K_UP);
            assertThat(sampler.currentMembers()).contains(A);

            // SWIM also drops A; disconnect hints reinforce each absent sample.
            absent(A);
            sampler.onQuicDisconnect(A);
            sampleTimes(sampler, 1);
            sampler.onQuicDisconnect(A);
            sampleTimes(sampler, 1);
            assertThat(sampler.currentMembers()).contains(A); // still within window (K_DOWN=3)
            sampler.onQuicDisconnect(A);
            sampleTimes(sampler, 1); // K_DOWN-th absent → evicted

            assertThat(sampler.currentMembers()).containsExactly(SELF);
        }

        @Test
        void onQuicReconnect_biasesPresent_butStillRequiresKSamples() {
            var sampler = sampler();

            // A is NOT SWIM-healthy, but a reconnect hint biases one sample present.
            sampler.onQuicReconnect(A);
            sampleTimes(sampler, 1); // up-streak 1 only
            assertThat(sampler.currentMembers()).containsExactly(SELF); // not bypassed

            sampleTimes(sampler, K_UP);
            assertThat(sampler.currentMembers()).containsExactly(SELF);
            assertThat(reconcileInvocations.get()).isZero();
        }
    }

    /// High-water membership mark — the one-way peak the leader reconciler latches its
    /// cold-start-over guard off (independent of reconcile-pass timing).
    @Nested
    class PeakMembership {
        private PresenceSampler formedTracker() {
            var sampler = sampler();
            healthy(A);
            healthy(B);
            healthy(C);
            sampleTimes(sampler, K_UP); // SELF + A + B + C = 4 stable
            return sampler;
        }

        @Test
        void freshTracker_peakIsSelfOnly() {
            assertThat(sampler().peakMembershipCount()).isEqualTo(1);
        }

        @Test
        void peakRecordsFormationGrowth() {
            var sampler = formedTracker();

            assertThat(sampler.currentMemberCount()).isEqualTo(4);
            assertThat(sampler.peakMembershipCount()).isEqualTo(4);
        }

        @Test
        void peakNeverDecreasesAfterDeparture() {
            var sampler = formedTracker(); // SELF + A + B + C = 4
            assertThat(sampler.peakMembershipCount()).isEqualTo(4);

            absent(C);
            sampleTimes(sampler, K_DOWN); // C leaves the stable set: 4 -> 3

            assertThat(sampler.currentMemberCount()).isEqualTo(3);
            // Peak is a one-way high-water mark — it holds at the formation maximum.
            assertThat(sampler.peakMembershipCount()).isEqualTo(4);
        }
    }
}
