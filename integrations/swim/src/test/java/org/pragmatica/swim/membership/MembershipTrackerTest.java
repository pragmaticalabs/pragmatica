/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.swim.membership;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.swim.membership.MembershipTrackerConfig.membershipTrackerConfig;

/// Unit proof for [`MembershipTracker`] (membership-unification-spec §4). Drives the
/// sample FSM deterministically via direct [`MembershipTracker#sample`] calls — no real
/// scheduler — with an injected mutable health snapshot and an injected clock.
class MembershipTrackerTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");

    private static final int K = 3;

    private Map<NodeId, SwimHealth> liveness;
    private List<MembershipChange> deltas;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        liveness = new HashMap<>();
        deltas = new ArrayList<>();
        clock = new AtomicLong(0);
    }

    private MembershipTracker tracker(int coreSize) {
        Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness));
        return MembershipTracker.membershipTracker(SELF,
                                                   membershipTrackerConfig(
                                                       TimeSpan.timeSpan(100).millis(), K, K),
                                                   health,
                                                   () -> coreSize,
                                                   clock::get,
                                                   deltas::add);
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

    private void sampleTimes(MembershipTracker tracker, int times) {
        for (var i = 0; i < times; i++) {
            clock.addAndGet(1_000_000L);
            tracker.sample();
        }
    }

    @Nested
    class Hysteresis {
        @Test
        void sample_absorbsTransientFlap_noDeltaEmitted() {
            var tracker = tracker(3);

            healthy(A);
            sampleTimes(tracker, 1); // up-streak 1
            absent(A);
            sampleTimes(tracker, 1); // flips to down-streak, never reached K-up
            healthy(A);
            sampleTimes(tracker, 1); // back up, streak 1 again

            assertThat(deltas).isEmpty();
            assertThat(tracker.members()).containsExactly(SELF);
        }

        @Test
        void sample_stableJoin_emitsExactlyOneJoinedDelta() {
            var tracker = tracker(3);

            healthy(A);
            sampleTimes(tracker, K - 1); // not yet stable
            assertThat(deltas).isEmpty();

            sampleTimes(tracker, 1); // K-th consecutive healthy → ENTER
            sampleTimes(tracker, 3); // further healthy samples must NOT re-emit

            assertThat(deltas).hasSize(1);
            assertThat(deltas.getFirst().joined()).containsExactly(A);
            assertThat(deltas.getFirst().left()).isEmpty();
            assertThat(tracker.members()).containsExactlyInAnyOrder(SELF, A);
        }

        @Test
        void sample_stableDeparture_emitsExactlyOneLeftDelta() {
            var tracker = tracker(3);

            healthy(A);
            sampleTimes(tracker, K); // A enters
            assertThat(deltas).hasSize(1);

            absent(A);
            sampleTimes(tracker, K - 1); // not yet stable-departed
            assertThat(deltas).hasSize(1);

            sampleTimes(tracker, 1); // K-th consecutive absent → LEAVE
            sampleTimes(tracker, 3); // further absent samples must NOT re-emit

            assertThat(deltas).hasSize(2);
            assertThat(deltas.get(1).left()).containsExactly(A);
            assertThat(deltas.get(1).joined()).isEmpty();
            assertThat(tracker.members()).containsExactly(SELF);
        }

        @Test
        void sample_faultyHealthCountsAsAbsent_drivesDeparture() {
            var tracker = tracker(3);

            healthy(A);
            sampleTimes(tracker, K);
            assertThat(tracker.members()).contains(A);

            faulty(A); // FAULTY is not HEALTHY → absent for the candidate set
            sampleTimes(tracker, K);

            assertThat(tracker.members()).containsExactly(SELF);
            assertThat(deltas.getLast().left()).containsExactly(A);
        }
    }

    @Nested
    class Self {
        @Test
        void members_alwaysIncludesSelf_evenWithNoLiveness() {
            var tracker = tracker(3);

            sampleTimes(tracker, K * 2);

            assertThat(tracker.members()).containsExactly(SELF);
        }

        @Test
        void sample_selfNeverLeaves_evenIfReportedFaulty() {
            var tracker = tracker(3);

            faulty(SELF); // self is force-added to candidate set regardless
            sampleTimes(tracker, K * 2);

            assertThat(tracker.members()).contains(SELF);
        }
    }

    @Nested
    class Quorum {
        @Test
        void hasQuorum_flipsAtMajorityThreshold_coreSizeFive() {
            var tracker = tracker(5); // quorum threshold = 5/2 + 1 = 3

            healthy(A);
            sampleTimes(tracker, K); // members = {SELF, A} = 2 < 3
            assertThat(tracker.hasQuorum()).isFalse();
            assertThat(tracker.phase()).isEqualTo(MembershipPhase.COLD_BOOT);

            healthy(B);
            sampleTimes(tracker, K); // members = {SELF, A, B} = 3 >= 3
            assertThat(tracker.memberCount()).isEqualTo(3);
            assertThat(tracker.hasQuorum()).isTrue();
            assertThat(tracker.phase()).isEqualTo(MembershipPhase.NORMAL);
        }

        @Test
        void phase_recoveringAfterQuorumLost_distinctFromColdBoot() {
            var tracker = tracker(3); // quorum threshold = 2

            healthy(A);
            sampleTimes(tracker, K); // {SELF, A} = 2 >= 2 → quorate
            assertThat(tracker.phase()).isEqualTo(MembershipPhase.NORMAL);

            absent(A);
            sampleTimes(tracker, K); // {SELF} = 1 < 2 → lost, but was quorate
            assertThat(tracker.hasQuorum()).isFalse();
            assertThat(tracker.phase()).isEqualTo(MembershipPhase.RECOVERING);
        }
    }

    @Nested
    class QuicHints {
        @Test
        void onQuicDisconnect_biasesAbsent_butDoesNotBypassHysteresis() {
            var tracker = tracker(3);

            healthy(A);
            sampleTimes(tracker, K); // A is a stable member
            assertThat(tracker.members()).contains(A);

            // A is still SWIM-healthy, but QUIC says "likely gone". The hint biases the
            // NEXT sample absent — but a single biased sample must NOT evict A (hysteresis).
            tracker.onQuicDisconnect(A);
            sampleTimes(tracker, 1);
            assertThat(tracker.members()).contains(A); // not bypassed
            assertThat(deltas).hasSize(1); // still only the original join

            // Without a renewed hint the next sample sees A SWIM-healthy again → recovers,
            // so the transient disconnect hint is correctly absorbed.
            sampleTimes(tracker, K);
            assertThat(tracker.members()).contains(A);
            assertThat(deltas).hasSize(1);
        }

        @Test
        void onQuicDisconnect_sustainedWithAbsence_eventuallyEvictsViaHysteresis() {
            var tracker = tracker(3);

            healthy(A);
            sampleTimes(tracker, K);
            assertThat(tracker.members()).contains(A);

            // SWIM also drops A; disconnect hints reinforce each absent sample.
            absent(A);
            tracker.onQuicDisconnect(A);
            sampleTimes(tracker, 1);
            tracker.onQuicDisconnect(A);
            sampleTimes(tracker, 1);
            assertThat(tracker.members()).contains(A); // still within window
            tracker.onQuicDisconnect(A);
            sampleTimes(tracker, 1); // K-th absent → evicted

            assertThat(tracker.members()).containsExactly(SELF);
            assertThat(deltas.getLast().left()).containsExactly(A);
        }

        @Test
        void onQuicReconnect_biasesPresent_butStillRequiresKSamples() {
            var tracker = tracker(3);

            // A is NOT SWIM-healthy, but a reconnect hint biases one sample present.
            tracker.onQuicReconnect(A);
            sampleTimes(tracker, 1); // up-streak 1 only
            assertThat(tracker.members()).containsExactly(SELF); // not bypassed

            // Without continued presence the streak cannot reach K.
            sampleTimes(tracker, K);
            assertThat(tracker.members()).containsExactly(SELF);
            assertThat(deltas).isEmpty();
        }
    }

    @Nested
    class MembershipViewContract {
        @Test
        void membershipView_projectsStableSet_onAllAccessors() {
            var tracker = tracker(5);

            healthy(A);
            healthy(B);
            healthy(C);
            sampleTimes(tracker, K);

            assertThat(tracker.coreMemberIds()).containsExactlyInAnyOrder(SELF, A, B, C);
            assertThat(tracker.onDutyMemberIds()).containsExactlyInAnyOrder(SELF, A, B, C);
            assertThat(tracker.healthyOnDutyCount()).isEqualTo(4);
            assertThat(tracker.desiredCoreSize()).isEqualTo(5);
        }

        @Test
        void deltaMembers_carryFullPostTransitionSet() {
            var tracker = tracker(5);

            healthy(A);
            healthy(B);
            sampleTimes(tracker, K);

            assertThat(deltas).hasSize(1);
            assertThat(deltas.getFirst().members()).containsExactlyInAnyOrder(SELF, A, B);
            assertThat(deltas.getFirst().joined()).containsExactlyInAnyOrder(A, B);
        }
    }
}
