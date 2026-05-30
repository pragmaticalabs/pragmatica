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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker.nodeTopologyTracker;


/// Unit proof for [`NodeTopologyTracker`] (membership v2 §6) — periodic-sample + delta +
/// asymmetric-hysteresis FSM. Drives the FSM deterministically via direct
/// [`NodeTopologyTracker#sample`] calls (no real scheduler) against an injected mutable
/// health snapshot and an injected clock.
class NodeTopologyTrackerTest {
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

    private NodeTopologyTracker tracker() {
        Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
        return nodeTopologyTracker(SELF, health, INTERVAL, K_UP, K_DOWN, clock::get,
                                   reconcileInvocations::incrementAndGet);
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

    private void sampleTimes(NodeTopologyTracker tracker, int times) {
        for (var i = 0; i < times; i++) {
            clock.addAndGet(1_000_000L);
            tracker.sample();
        }
    }

    @Nested
    class Hysteresis {
        @Test
        void sample_absorbsTransientFlap_noReconcile() {
            var ntt = tracker();

            healthy(A);
            sampleTimes(ntt, 1); // up-streak 1 (K_UP = 2, not yet stable)
            absent(A);
            sampleTimes(ntt, 1); // flips to down-streak before reaching K-up
            healthy(A);
            sampleTimes(ntt, 1); // back up, streak 1 again

            assertThat(reconcileInvocations.get()).isZero();
            assertThat(ntt.currentMembers()).containsExactly(SELF);
        }

        @Test
        void sample_stableJoin_invokesReconcileExactlyOnce() {
            var ntt = tracker();

            healthy(A);
            sampleTimes(ntt, K_UP - 1); // not yet stable
            assertThat(reconcileInvocations.get()).isZero();

            sampleTimes(ntt, 1); // K_UP-th consecutive healthy → ENTER
            sampleTimes(ntt, 3); // further healthy samples must NOT re-fire

            assertThat(reconcileInvocations.get()).isEqualTo(1);
            assertThat(ntt.currentMembers()).containsExactlyInAnyOrder(SELF, A);
        }

        @Test
        void sample_stableDeparture_invokesReconcileExactlyOnce() {
            var ntt = tracker();

            healthy(A);
            sampleTimes(ntt, K_UP); // A enters
            assertThat(reconcileInvocations.get()).isEqualTo(1);

            absent(A);
            sampleTimes(ntt, K_DOWN - 1); // not yet stable-departed
            assertThat(reconcileInvocations.get()).isEqualTo(1);

            sampleTimes(ntt, 1); // K_DOWN-th consecutive absent → LEAVE
            sampleTimes(ntt, 3); // further absent samples must NOT re-fire

            assertThat(reconcileInvocations.get()).isEqualTo(2);
            assertThat(ntt.currentMembers()).containsExactly(SELF);
        }

        @Test
        void sample_asymmetricEdges_upFastDownSlow() {
            var ntt = tracker();

            healthy(A);
            sampleTimes(ntt, K_UP); // fast admit at K_UP=2
            assertThat(ntt.currentMembers()).contains(A);

            absent(A);
            sampleTimes(ntt, K_UP); // K_UP absent samples are NOT enough to drop (K_DOWN=3)
            assertThat(ntt.currentMembers()).contains(A);

            sampleTimes(ntt, K_DOWN - K_UP); // reach K_DOWN total → drop
            assertThat(ntt.currentMembers()).containsExactly(SELF);
        }

        @Test
        void sample_faultyHealthCountsAsAbsent_drivesDeparture() {
            var ntt = tracker();

            healthy(A);
            sampleTimes(ntt, K_UP);
            assertThat(ntt.currentMembers()).contains(A);

            faulty(A); // FAULTY is not HEALTHY → absent for the candidate set
            sampleTimes(ntt, K_DOWN);

            assertThat(ntt.currentMembers()).containsExactly(SELF);
        }
    }

    @Nested
    class Self {
        @Test
        void members_alwaysIncludesSelf_evenWithNoLiveness() {
            var ntt = tracker();

            sampleTimes(ntt, K_DOWN * 2);

            assertThat(ntt.currentMembers()).containsExactly(SELF);
            assertThat(ntt.currentMemberCount()).isEqualTo(1);
        }

        @Test
        void sample_selfNeverLeaves_evenIfReportedFaulty() {
            var ntt = tracker();

            faulty(SELF); // self is force-added to candidate set regardless
            sampleTimes(ntt, K_DOWN * 2);

            assertThat(ntt.currentMembers()).contains(SELF);
        }
    }

    @Nested
    class MemberSet {
        @Test
        void freshTracker_membersContainSelfOnly() {
            var ntt = tracker();

            assertThat(ntt.currentMembers()).containsExactly(SELF);
            assertThat(ntt.currentMemberCount()).isEqualTo(1);
        }

        @Test
        void memberCount_reflectsAddRemoveSequence() {
            var ntt = tracker();

            healthy(A);
            healthy(B);
            healthy(C);
            sampleTimes(ntt, K_UP);
            assertThat(ntt.currentMemberCount()).isEqualTo(4);

            absent(B);
            sampleTimes(ntt, K_DOWN);
            assertThat(ntt.currentMemberCount()).isEqualTo(3);
            assertThat(ntt.currentMembers()).containsExactlyInAnyOrder(SELF, A, C);
        }
    }

    @Nested
    class SwimObservationBias {
        @Test
        void onSwimObservation_healthy_biasesPresent_butStillRequiresKSamples() {
            var ntt = tracker();

            // A is NOT in the snapshot, but a HealthyObserved biases one sample present.
            ntt.onSwimObservation(new HealthyObserved(A, 1L));
            sampleTimes(ntt, 1); // up-streak 1 only
            assertThat(ntt.currentMembers()).containsExactly(SELF); // not bypassed

            sampleTimes(ntt, K_UP); // no continued presence → cannot reach K_UP again
            assertThat(ntt.currentMembers()).containsExactly(SELF);
            assertThat(reconcileInvocations.get()).isZero();
        }

        @Test
        void onSwimObservation_faulty_biasesAbsent_butDoesNotBypassHysteresis() {
            var ntt = tracker();

            healthy(A);
            sampleTimes(ntt, K_UP); // A is a stable member
            assertThat(ntt.currentMembers()).contains(A);

            // A is still SWIM-healthy in the snapshot, but FaultyObserved biases the NEXT
            // sample absent — a single biased sample must NOT evict A.
            ntt.onSwimObservation(new FaultyObserved(A, 2L));
            sampleTimes(ntt, 1);
            assertThat(ntt.currentMembers()).contains(A);
            assertThat(reconcileInvocations.get()).isEqualTo(1); // still only the join

            // Snapshot shows A healthy again → recovers, transient bias absorbed.
            sampleTimes(ntt, K_DOWN);
            assertThat(ntt.currentMembers()).contains(A);
            assertThat(reconcileInvocations.get()).isEqualTo(1);
        }

        @Test
        void onSwimObservation_departed_biasesAbsent() {
            var ntt = tracker();

            healthy(A);
            sampleTimes(ntt, K_UP);
            assertThat(ntt.currentMembers()).contains(A);

            absent(A);
            ntt.onSwimObservation(new DepartedObserved(A, 2L));
            sampleTimes(ntt, K_DOWN);

            assertThat(ntt.currentMembers()).containsExactly(SELF);
        }
    }

    @Nested
    class QuicHints {
        @Test
        void onQuicDisconnect_biasesAbsent_butDoesNotBypassHysteresis() {
            var ntt = tracker();

            healthy(A);
            sampleTimes(ntt, K_UP); // A is a stable member
            assertThat(ntt.currentMembers()).contains(A);

            // A is still SWIM-healthy, but QUIC says "likely gone". One biased sample must
            // NOT evict A (hysteresis).
            ntt.onQuicDisconnect(A);
            sampleTimes(ntt, 1);
            assertThat(ntt.currentMembers()).contains(A); // not bypassed
            assertThat(reconcileInvocations.get()).isEqualTo(1); // still only the join

            // Without a renewed hint, the next sample sees A SWIM-healthy → recovers.
            sampleTimes(ntt, K_DOWN);
            assertThat(ntt.currentMembers()).contains(A);
            assertThat(reconcileInvocations.get()).isEqualTo(1);
        }

        @Test
        void onQuicDisconnect_sustainedWithAbsence_eventuallyEvictsViaHysteresis() {
            var ntt = tracker();

            healthy(A);
            sampleTimes(ntt, K_UP);
            assertThat(ntt.currentMembers()).contains(A);

            // SWIM also drops A; disconnect hints reinforce each absent sample.
            absent(A);
            ntt.onQuicDisconnect(A);
            sampleTimes(ntt, 1);
            ntt.onQuicDisconnect(A);
            sampleTimes(ntt, 1);
            assertThat(ntt.currentMembers()).contains(A); // still within window (K_DOWN=3)
            ntt.onQuicDisconnect(A);
            sampleTimes(ntt, 1); // K_DOWN-th absent → evicted

            assertThat(ntt.currentMembers()).containsExactly(SELF);
        }

        @Test
        void onQuicReconnect_biasesPresent_butStillRequiresKSamples() {
            var ntt = tracker();

            // A is NOT SWIM-healthy, but a reconnect hint biases one sample present.
            ntt.onQuicReconnect(A);
            sampleTimes(ntt, 1); // up-streak 1 only
            assertThat(ntt.currentMembers()).containsExactly(SELF); // not bypassed

            sampleTimes(ntt, K_UP);
            assertThat(ntt.currentMembers()).containsExactly(SELF);
            assertThat(reconcileInvocations.get()).isZero();
        }
    }

    @Nested
    class DownHysteresisDerivation {
        @Test
        void downHysteresisFor_ceilDivOfDepartureTimeoutOverSampleInterval() {
            assertThat(NodeTopologyTracker.downHysteresisFor(TimeSpan.timeSpan(15).seconds(),
                                                             TimeSpan.timeSpan(1).seconds()))
                .isEqualTo(15);
            assertThat(NodeTopologyTracker.downHysteresisFor(TimeSpan.timeSpan(1500).millis(),
                                                             TimeSpan.timeSpan(1).seconds()))
                .isEqualTo(2); // ceil(1.5) = 2
            assertThat(NodeTopologyTracker.downHysteresisFor(TimeSpan.timeSpan(0).millis(),
                                                             TimeSpan.timeSpan(1).seconds()))
                .isEqualTo(1); // floored at 1
        }
    }
}
