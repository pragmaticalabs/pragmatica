// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector.quorumLossDetector;


/// Unit tests for [`QuorumLossDetector`] — mechanism in isolation, no SWIM/config wiring.
/// Member count is derived by a supplier ([`QuorumLossDetector#reevaluate`] is the edge input) and
/// already includes self; a cluster of "self + N connected peers" is fed as `N + 1`.
class QuorumLossDetectorTest {
    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private RecordingListener listener;
    private MutableIntSupplier coreCount;
    private QuorumLossDetector detector;
    private final AtomicInteger memberCount = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        scheduler = new ManualScheduler();
        listener = new RecordingListener();
        coreCount = new MutableIntSupplier(0);
        memberCount.set(1);
        detector = quorumLossDetector(membershipConfig(), coreCount, memberCount::get, timeSource, scheduler);
        detector.setQuorumLossListener(listener);
    }

    /// Feed a fresh member count (includes self) and remember it so a subsequent core-count
    /// change can re-trigger a recompute against the same membership.
    @Contract
    private void members(int newMemberCount) {
        memberCount.set(newMemberCount);
        detector.reevaluate();
    }

    /// Replicate the original `onConfiguredCoreCountChanged` semantics — change the configured
    /// core size and recompute against the current member count.
    @Contract
    private void coreCount(int newCoreCount) {
        coreCount.set(newCoreCount);
        detector.reevaluate();
    }

    /// #1853 — the count is DERIVED on every read, never stored: an input that changes without a push cannot
    /// leave the detector reporting a stale value.
    @Nested
    class DerivedCount {
        @Test
        void inputChangeWithoutReevaluate_isVisibleToEveryRead() {
            coreCount.set(5);
            memberCount.set(5);

            assertThat(detector.currentMemberCount()).isEqualTo(5);
            assertThat(detector.isBelowThreshold()).isFalse();
            assertThat(QuorumLossSnapshot.from(detector).strictMemberCount()).isEqualTo(5);
            assertThat(QuorumLossSnapshot.from(detector).belowThreshold()).isFalse();

            memberCount.set(1);

            assertThat(detector.currentMemberCount()).isEqualTo(1);
            assertThat(detector.isBelowThreshold()).isTrue();
            assertThat(QuorumLossSnapshot.from(detector).belowThreshold()).isTrue();
        }

        @Test
        void reads_areObservationOnly_theyNeverArmOrOpenAWindow() {
            coreCount.set(5);
            memberCount.set(5);

            detector.currentMemberCount();
            detector.isBelowThreshold();
            QuorumLossSnapshot.from(detector);

            assertThat(detector.isArmed()).as("only an edge input arms").isFalse();
            assertThat(scheduler.pendingTasks()).isEmpty();
        }

        @Test
        void reevaluate_afterInputChangedUnderneath_armsOnTheQuorateCount() {
            coreCount.set(5);
            memberCount.set(0);
            detector.reevaluate();
            assertThat(detector.isArmed()).as("arming: an empty electorate derives 0").isFalse();

            memberCount.set(5);
            detector.reevaluate();

            assertThat(detector.isArmed()).isTrue();
        }

        /// A derived count can change between two reads, so a snapshot that reads it twice can report a count and
        /// a `belowThreshold` that contradict each other. The supplier here answers 5, then 1.
        @Test
        void snapshot_readsTheCountOnce_soItsFieldsAgree() {
            var reads = new AtomicInteger();
            var racing = quorumLossDetector(membershipConfig(),
                                            () -> 5,
                                            () -> reads.getAndIncrement() == 0
                                                  ? 5
                                                  : 1,
                                            timeSource,
                                            scheduler);

            var snapshot = QuorumLossSnapshot.from(racing);

            assertThat(reads.get()).as("one read of the derived count per snapshot").isEqualTo(1);
            assertThat(snapshot.strictMemberCount()).isEqualTo(5);
            assertThat(snapshot.belowThreshold()).as("5 of required 3 is not below").isFalse();
        }

        @Test
        void firingCheck_readsCurrentCount_notTheCountOfTheEdgeThatOpenedTheWindow() {
            coreCount.set(5);
            members(5);
            members(1);
            assertThat(scheduler.pendingTasks()).hasSize(1);

            // Quorum returns by an input whose change nothing re-evaluated.
            memberCount.set(4);
            timeSource.advanceTimeMillis(membershipConfig().splitTimeout().millis());
            scheduler.fireAll();

            assertThat(listener.events()).as("the firing check reads the live count: recovered, no drain").isEmpty();
        }
    }

    @Nested
    class DefaultState {
        @Test
        void freshDetector_isNotBelow_andSchedulesNoTimer() {
            assertThat(detector.isBelowThreshold()).isFalse();
            assertThat(detector.currentRequiredThreshold()).isZero();
            assertThat(detector.belowThresholdSinceNanos().isPresent()).isFalse();
            assertThat(scheduler.pendingTasks()).isEmpty();
            assertThat(listener.events()).isEmpty();
        }

        @Test
        void membersBeforeCoreCount_doNotFire_thresholdUnknown() {
            // self + PEER_A + PEER_B = 3 members.
            members(3);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
            assertThat(detector.isBelowThreshold()).isFalse();
        }
    }

    @Nested
    class BelowThresholdFiring {
        @Test
        void coreCountChangedToFive_onlySelf_belowThreshold_intentFiresAfterDeadline() {
            // Reach quorum first so the detector arms (cold-start guard); self + 4 peers = 5.
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();
            // Now drop to self only = 1 member.
            members(1);

            assertThat(detector.currentRequiredThreshold()).isEqualTo(3);
            assertThat(detector.currentMemberCount()).isEqualTo(1);
            assertThat(detector.isBelowThreshold()).isTrue();
            assertThat(scheduler.pendingTasks()).hasSize(1);
            assertThat(scheduler.pendingTasks().getFirst().delay())
                    .isEqualTo(membershipConfig().splitTimeout());

            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
            var intent = listener.events().getFirst();

            assertThat(intent.observedLocalQuorumCount()).isEqualTo(1);
            assertThat(intent.requiredThreshold()).isEqualTo(3);
            assertThat(intent.observedAtNanos()).isEqualTo(TimeSpan.timeSpan(8_000).millis().nanos());
        }
    }

    /// A6 cold-boot self-fence gate: while the injected cold-boot supplier is active the quorum-loss
    /// drain is suppressed (a node still converging on a full-cluster restart must not self-fence on the
    /// transiently-low SWIM-alive count); once it clears, firing resumes. Mirrors the BelowThresholdFiring
    /// setup (the only addition is the cold-boot supplier).
    @Nested
    class ColdBootSuppression {
        @Test
        void belowThreshold_whileColdBoot_doesNotFire() {
            detector.setColdBootSupplier(() -> true);
            members(5);
            coreCount(5);
            members(1);

            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
        }

        @Test
        void belowThreshold_notColdBoot_stillFires() {
            detector.setColdBootSupplier(() -> false);
            members(5);
            coreCount(5);
            members(1);

            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
        }

        @Test
        void nullColdBootSupplier_restoresNeverSuppressDefault_fires() {
            detector.setColdBootSupplier(() -> true);
            detector.setColdBootSupplier(null);
            members(5);
            coreCount(5);
            members(1);

            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
        }

        /// #415 regression: the cold-boot suppression must DEFER, not DROP. The count-path firing
        /// check lands inside the window (suppressed), then the window clears — the re-armed check
        /// must fire the deferred self-fence. Before the fix the one-shot intent was dropped and the
        /// latched below-window never re-scheduled, stranding the node as a permanent zombie.
        @Test
        void belowThreshold_coldBootClearsAfterSuppression_firesDeferredDrain() {
            var coldBoot = new AtomicBoolean(true);
            detector.setColdBootSupplier(coldBoot::get);
            members(5);
            coreCount(5);
            members(1);

            // First firing check runs inside the cold-boot window: suppressed, not dropped.
            scheduler.fireAll();
            assertThat(listener.events()).isEmpty();

            // Window closes; the re-armed check fires the deferred drain.
            coldBoot.set(false);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().observedLocalQuorumCount()).isEqualTo(1);
            assertThat(listener.events().getFirst().requiredThreshold()).isEqualTo(3);
        }

        /// #415 symmetry: the PASSIVE quorum-presence path emits through the same cold-boot gate, so
        /// it must defer-and-re-arm too. Members stay quorate (5) so only the presence path opens a
        /// window; the drain fires once the cold-boot supplier clears.
        @Test
        void passiveEdge_coldBootClearsAfterSuppression_firesDeferredDrain() {
            var coldBoot = new AtomicBoolean(true);
            detector.setColdBootSupplier(coldBoot::get);
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.onQuorumPresence(false);

            scheduler.fireAll();
            assertThat(listener.events()).isEmpty();

            coldBoot.set(false);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
        }

        /// #415 idempotence: repeated firing checks while cold-boot stays active must never emit and
        /// never pile up timers — each suppressed check re-arms exactly ONE successor (the scheduler's
        /// cancel-prior idiom), so at most one live re-check exists at any time. The deferred drain
        /// still fires once, on the first check after the window clears.
        @Test
        void belowThreshold_coldBootStaysActive_reArmsWithoutPileup_thenFiresOnClear() {
            var coldBoot = new AtomicBoolean(true);
            detector.setColdBootSupplier(coldBoot::get);
            members(5);
            coreCount(5);
            members(1);

            scheduler.fireAll();
            scheduler.fireAll();
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
            assertThat(scheduler.pendingTasks().stream().filter(t -> !t.cancelled() && !t.isDone()).count())
                    .as("exactly one live re-check in flight — no timer pile-up")
                    .isEqualTo(1L);

            coldBoot.set(false);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
        }
    }

    @Nested
    class RecoveryBeforeDeadline {
        @Test
        void membersAddedThatRestoreThreshold_intentDoesNotFire_evenIfScheduledTaskRuns() {
            members(1);
            coreCount(5);
            // self + PEER_A + PEER_B = 3 members.
            members(3);

            assertThat(detector.isBelowThreshold()).isFalse();
            assertThat(detector.currentMemberCount()).isEqualTo(3);

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
        }

        @Test
        void membersAddedBeforeDeadline_cancelsScheduledTask() {
            members(1);
            coreCount(5);
            timeSource.advanceTimeMillis(3_000);

            // self + PEER_A + PEER_B = 3 members.
            members(3);

            assertThat(scheduler.pendingTasks().getFirst().cancelled()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
            assertThat(detector.belowThresholdSinceNanos().isPresent()).isFalse();
        }
    }

    @Nested
    class WindowResemantics {
        /// Verifies the structural property: each below-window schedules a FRESH task with the
        /// full `quorumLossDrainThreshold` delay; the previous window's task is cancelled
        /// on recovery. The `ManualScheduler` doesn't enforce wall-clock elapsed time on
        /// fire — that's the production [`org.pragmatica.lang.utils.SharedScheduler`]'s job.
        /// What we verify here is the per-window deadline reset: a second below-window after
        /// a brief above-window gets its own full-deadline task, not a residual of the first.
        @Test
        void membersAddedThenRemoved_intentFires_onSecondWindowsOwnTask_notFirstWindows() {
            members(1);
            coreCount(5);
            // Window 1 starts at T=0; task #0 scheduled.
            timeSource.advanceTimeMillis(5_000);
            // Add enough members to go above threshold (need 3): self + A + B = 3.
            members(3);
            assertThat(detector.isBelowThreshold()).isFalse();

            // Window 1's task was cancelled on recovery.
            assertThat(scheduler.pendingTasks().getFirst().cancelled()).isTrue();
            assertThat(listener.events()).isEmpty();

            // Drop back below threshold (self + A = 2) — opens window 2 at T=6s.
            timeSource.advanceTimeMillis(1_000);
            members(2);
            assertThat(detector.isBelowThreshold()).isTrue();
            assertThat(detector.currentMemberCount()).isEqualTo(2);
            assertThat(detector.belowThresholdSinceNanos().isPresent()).isTrue();
            detector.belowThresholdSinceNanos()
                    .onPresent(ts -> assertThat(ts).isEqualTo(TimeSpan.timeSpan(6_000).millis().nanos()));

            // A new, uncancelled task with the FULL window delay was scheduled.
            assertThat(scheduler.pendingTasks()).hasSize(2);
            var window2Task = scheduler.pendingTasks().get(1);

            assertThat(window2Task.cancelled()).isFalse();
            assertThat(window2Task.delay()).isEqualTo(membershipConfig().splitTimeout());

            // Firing window 2's task emits an intent (it observes the current
            // belowThresholdSinceNanos == its captured windowStart at T=6s).
            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();
            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().observedLocalQuorumCount()).isEqualTo(2);
        }
    }

    @Nested
    class ConfigurationShrinks {
        @Test
        void coreCountShrunkToMatchCurrentMembers_aboveThreshold_noFire() {
            // self + PEER_A + PEER_B = 3 members.
            members(3);
            coreCount(7);
            // threshold=4, members=3, below
            assertThat(detector.isBelowThreshold()).isTrue();

            // Shrink so members (3) meets threshold (3): coreCount=3 → threshold=2 (3/2+1).
            coreCount(3);
            assertThat(detector.currentRequiredThreshold()).isEqualTo(2);
            assertThat(detector.isBelowThreshold()).isFalse();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
        }
    }

    @Nested
    class Idempotence {
        @Test
        void repeatedSameMemberCount_doesNotChangeQuorumCount_orRescheduleWindow() {
            // self + PEER_A = 2 members; need 3, so below.
            coreCount(5);
            members(2);
            members(2);
            members(2);

            assertThat(detector.currentMemberCount()).isEqualTo(2);
            // Still below (need 3): exactly one window scheduled, the original one.
            assertThat(scheduler.pendingTasks()).hasSize(1);
            assertThat(scheduler.pendingTasks().getFirst().cancelled()).isFalse();
        }

        @Test
        void firingTaskAtMostOnce_perBelowWindow() {
            // Arm via a quorate observation before configuring the core count, then drop below.
            members(5);
            coreCount(5);
            members(1);
            timeSource.advanceTimeMillis(8_000);

            scheduler.fireAll();
            scheduler.fireAll();
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
        }
    }

    @Nested
    class ArmingLatch {
        @Test
        void notYetArmed_belowThresholdFromStart_doesNotFire() {
            // Boot straight into a minority cluster (self + PEER_A = 2; need 3) — never quorate.
            members(2);
            coreCount(5);
            assertThat(detector.isArmed()).isFalse();
            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
        }

        @Test
        void armedThenLoss_firesExactlyOnce() {
            // Reach quorum (self + 4 peers = 5) — arms the latch.
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            // Then drop below quorum (self + PEER_A = 2; need 3).
            members(2);
            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().observedLocalQuorumCount()).isEqualTo(2);
            assertThat(listener.events().getFirst().requiredThreshold()).isEqualTo(3);
        }

        @Test
        void armingIsLatch_firesOnEachPostArmLossWindow_acrossRecovery() {
            // Window 1: arm via quorum, then drop below.
            members(5);
            coreCount(5);
            members(2);
            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();
            assertThat(listener.events()).hasSize(1);

            // Recover to quorum (latch stays armed), then drop again — window 2.
            members(5);
            assertThat(detector.isArmed()).isTrue();
            timeSource.advanceTimeMillis(1_000);
            members(2);
            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(2);
        }
    }

    /// Wave 9 Fix A — the `ClusterStateNotification` PASSIVE/ACTIVE quorum-presence edge is
    /// debounced through the SAME split-timeout `T` window as the count path (no immediate
    /// process-exit drain on PASSIVE). Quorum regained within `T` cancels; sustained loss past
    /// `T` fires once. Arm is gated on the cold-start latch.
    @Nested
    class QuorumPresenceEdgeWindowed {
        @Test
        void passiveEdge_quorumRegainedWithinT_noDrain() {
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.onQuorumPresence(false);
            assertThat(scheduler.pendingTasks()).hasSize(1);

            timeSource.advanceTimeMillis(3_000);
            detector.onQuorumPresence(true);
            assertThat(scheduler.pendingTasks().getFirst().cancelled()).isTrue();

            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();
            assertThat(listener.events()).isEmpty();
        }

        @Test
        void passiveEdge_sustainedLossPastT_drainFiresOnce() {
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.onQuorumPresence(false);
            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
        }

        @Test
        void passiveEdge_neverQuorate_noArm_noDrain() {
            // Never reached quorum (cold-start guard): a PASSIVE edge must never self-drain.
            detector.onQuorumPresence(false);

            assertThat(detector.isArmed()).isFalse();
            assertThat(scheduler.pendingTasks()).isEmpty();
            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();
            assertThat(listener.events()).isEmpty();
        }
    }

    /// Fix C — membership co-confirmation gate (split-brain self-fence safety), refined to
    /// PER-MEMBER SUFFICIENCY. The detector's numerator is the STRICT (MEMBER-only) core count.
    /// When that drops below quorum the gate consults a co-confirmation snapshot and suppresses the
    /// drain iff the EFFECTIVE count — the snapshot's own strict + the stuck members INDIVIDUALLY
    /// co-confirmed SWIM-alive — meets quorum. One genuinely-dead stuck member contributes 0 rather
    /// than vetoing the whole suppression (the false-self-fence bug from the loaded-host SLOW-APPLY
    /// incident). The snapshot carries its own strict count, so the predicate is self-contained and
    /// the unwired/absent default (strict 0) can never suppress. The NEGATIVE cases are the
    /// split-brain proof: a genuine minority partition must STILL fence.
    ///
    /// Test-harness note: `members(N)` controls the count-path firing guard (the count path only
    /// reaches the gate when the live member count is BELOW threshold); the snapshot's own
    /// `strictCount` drives the suppression arithmetic. Count-path tests set `members(N)` to match
    /// the snapshot strict so both views agree at the firing instant, exactly as production samples
    /// them together from the FSM. Presence-path tests keep `members` quorate to avoid opening a
    /// count window, exercising ONLY the presence path through the same gate.
    @Nested
    class CoConfirmationGate {
        /// Tonight's exact Hetzner incident: 5-core cluster, threshold 3, leader's strict count
        /// stalled to 2 under a 10s SLOW-APPLY, stuck=[A,B,C] with A+B verified SWIM-alive (probe
        /// acks + QUIC handshake) and only C genuinely dead. Effective = 2 + 2 = 4 >= 3 → the false
        /// self-fence MUST be SUPPRESSED. Under the old all-or-nothing rule the single dead C vetoed
        /// suppression and killed a healthy leader.
        @Test
        void strictTwo_threeStuck_twoSwimAlive_oneDead_suppressesDrain() {
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(
                    2,
                    5,
                    List.of(new NodeId("b-node-1"), new NodeId("b-node-2")),
                    List.of(new NodeId("b-node-4"))));

            members(2);
            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("strict 2 + 2 SWIM-alive stuck = 4 >= threshold 3 (one dead member contributes 0) → SUPPRESSED")
                .isEmpty();
        }

        /// True minority partition: strict 2, stuck=[A,B,C] ALL SWIM-dead (no probe-acks reach this
        /// node). Effective = 2 + 0 = 2 < 3 → drain MUST FIRE. This is the split-brain proof: a real
        /// partition counts zero stuck members and self-fences.
        @Test
        void strictTwo_threeStuck_allSwimDead_drainFires() {
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(
                    2,
                    5,
                    List.of(),
                    List.of(new NodeId("gone-a"), new NodeId("gone-b"), new NodeId("gone-c"))));

            members(2);
            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("strict 2 + 0 SWIM-alive stuck = 2 < threshold 3 (real partition) → FIRES")
                .hasSize(1);
            assertThat(listener.events().getFirst().observedLocalQuorumCount()).isEqualTo(2);
            assertThat(listener.events().getFirst().requiredThreshold()).isEqualTo(3);
        }

        /// Boundary: strict 2, threshold 3, exactly ONE stuck SWIM-alive. Effective = 2 + 1 = 3,
        /// meeting threshold → SUPPRESSED.
        @Test
        void strictTwo_exactlyOneStuckSwimAlive_atBoundary_suppressesDrain() {
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(
                    2,
                    3,
                    List.of(new NodeId("stuck-alive")),
                    List.of()));

            members(2);
            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("strict 2 + 1 SWIM-alive stuck = 3 == threshold 3 → SUPPRESSED")
                .isEmpty();
        }

        /// Below boundary: strict 1, threshold 3, one stuck SWIM-alive. Effective = 1 + 1 = 2 < 3 →
        /// drain MUST FIRE. One alive member is not enough to lift a strict count of 1 to quorum.
        @Test
        void strictOne_oneStuckSwimAlive_belowBoundary_drainFires() {
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(
                    1,
                    2,
                    List.of(new NodeId("stuck-alive")),
                    List.of()));

            members(1);
            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("strict 1 + 1 SWIM-alive stuck = 2 < threshold 3 → FIRES")
                .hasSize(1);
            assertThat(listener.events().getFirst().observedLocalQuorumCount()).isEqualTo(1);
            assertThat(listener.events().getFirst().requiredThreshold()).isEqualTo(3);
        }

        /// Superset of the original all-alive suppression case (must stay green): strict drops to 2,
        /// two stuck members BOTH SWIM-alive, none dead. Effective = 2 + 2 = 4 >= 3 → SUPPRESSED.
        @Test
        void strictBelowButCountedQuorate_allStuckSwimAlive_suppressesDrain() {
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(
                    2,
                    4,
                    List.of(new NodeId("stuck-a"), new NodeId("stuck-b")),
                    List.of()));

            members(2);
            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("strict<threshold but all stuck members SWIM-alive → drain SUPPRESSED")
                .isEmpty();
        }

        @Test
        void strictBelowAndCountedBelow_realPartition_drainFires() {
            // Genuine minority partition: strict 1 AND no stuck members (the unreachable members have
            // aged out of SUSPECT, so they no longer count). Effective = 1 + 0 = 1 → drain MUST FIRE.
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(1, 1, List.of(), List.of()));

            members(1);
            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("strict<threshold AND no SWIM-alive stuck (real partition) → drain FIRES")
                .hasSize(1);
            assertThat(listener.events().getFirst().observedLocalQuorumCount()).isEqualTo(1);
            assertThat(listener.events().getFirst().requiredThreshold()).isEqualTo(3);
        }

        @Test
        void strictBelowCountedQuorateButStuckNotSwimAlive_drainFires() {
            // Counted is transiently 4 >= 3 (unreachable members still in SUSPECT during the
            // down-hysteresis window) BUT they are NOT SWIM-alive — a real partition. Effective =
            // 2 + 0 = 2 < 3 → drain MUST FIRE. (b) discriminates on raw SWIM liveness, which a
            // partitioned member fails immediately.
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(
                    2, 4, List.of(), List.of(new NodeId("gone-a"), new NodeId("gone-b"))));

            members(2);
            assertThat(detector.isBelowThreshold()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("strict<threshold, stuck members NOT SWIM-alive → drain FIRES")
                .hasSize(1);
        }

        @Test
        void unwiredCoConfirmation_strictBelow_drainFires() {
            // No co-confirmation supplier wired (legacy default = QuorumCoConfirmation.absent,
            // strictCount=0, zero alive stuck): the gate never suppresses, so the strict shortfall
            // fires as before.
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            members(1);
            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("unwired co-confirmation must preserve legacy fire-on-strict-shortfall behaviour")
                .hasSize(1);
        }

        @Test
        void coConfirmationGate_alsoGuardsPresencePath() {
            // The PASSIVE quorum-presence path goes through the same gate. A stuck-promotion artifact
            // (snapshot strict 2 + one stuck SWIM-alive, effective 3 >= 3) on a PASSIVE edge must NOT
            // drain. Members stay quorate (5) so no count-path window opens — only the presence path
            // is exercised.
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(
                    2, 4, List.of(new NodeId("stuck-a")), List.of()));

            detector.onQuorumPresence(false);
            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("PASSIVE presence-edge drain also suppressed by stuck-promotion co-confirmation")
                .isEmpty();
        }

        @Test
        void presencePath_realPartition_stillDrains() {
            // PASSIVE edge with a genuine partition (snapshot strict 1, no SWIM-alive stuck,
            // effective 1 < 3) → drain fires. Members stay quorate (5) so ONLY the presence path
            // runs — no count-path task is scheduled, so exactly one intent is emitted.
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();

            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(1, 1, List.of(), List.of()));

            detector.onQuorumPresence(false);
            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("PASSIVE presence-edge real partition (effective<threshold) → drain FIRES")
                .hasSize(1);
        }

        /// #1560: the count-path check that the gate suppresses must be re-evaluated, not dropped. An
        /// isolated core's gate first reads its unreachable peers as SWIM-alive stuck members; when they
        /// age out the re-check must fire the fence — before the fix the single suppressed check was the
        /// last one, and the fence waited for an unrelated PASSIVE edge.
        @Test
        void suppressedFiringCheck_gateLiftsLater_reCheckFiresDrain() {
            var coConfirmation = new AtomicReference<>(
                QuorumCoConfirmation.quorumCoConfirmation(1, 3, List.of(new NodeId("a"), new NodeId("b")), List.of()));
            detector.setCoConfirmationSupplier(coConfirmation::get);
            members(5);
            coreCount(5);
            members(1);

            scheduler.fireAll();
            assertThat(listener.events()).as("strict 1 + 2 SWIM-alive stuck = 3 → suppressed at T").isEmpty();
            assertThat(liveTasks()).as("the suppressed check re-arms exactly one successor").hasSize(1);
            assertThat(liveTasks().getFirst().delay()).isEqualTo(TimeSpan.timeSpan(1).seconds());

            coConfirmation.set(QuorumCoConfirmation.quorumCoConfirmation(1, 1, List.of(), List.of()));
            scheduler.fireAll();

            assertThat(listener.events()).as("gate lifted → the re-check fires the fence").hasSize(1);
        }

        /// #1560 symmetry: the PASSIVE presence path goes through the same gate and must re-check too.
        @Test
        void suppressedPresenceCheck_gateLiftsLater_reCheckFiresDrain() {
            var coConfirmation = new AtomicReference<>(
                QuorumCoConfirmation.quorumCoConfirmation(2, 4, List.of(new NodeId("stuck-a")), List.of()));
            detector.setCoConfirmationSupplier(coConfirmation::get);
            members(5);
            coreCount(5);

            detector.onQuorumPresence(false);
            scheduler.fireAll();
            assertThat(listener.events()).isEmpty();
            assertThat(liveTasks()).hasSize(1);

            coConfirmation.set(QuorumCoConfirmation.quorumCoConfirmation(1, 1, List.of(), List.of()));
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
        }

        /// #1560 × A6: a co-confirmation re-check that lands inside the cold-boot convergence window must
        /// not fence — the lifted gate still hands the intent to the cold-boot gate, which defers it — and
        /// the deferred fence fires only once the window closes.
        @Test
        void suppressedFiringCheck_reCheckInsideColdBootWindow_neverFences_firesAfterWindow() {
            var coldBoot = new AtomicBoolean(true);
            var coConfirmation = new AtomicReference<>(
                QuorumCoConfirmation.quorumCoConfirmation(1, 3, List.of(new NodeId("a"), new NodeId("b")), List.of()));
            detector.setColdBootSupplier(coldBoot::get);
            detector.setCoConfirmationSupplier(coConfirmation::get);
            members(5);
            coreCount(5);
            members(1);

            scheduler.fireAll();
            assertThat(listener.events()).as("suppressed by co-confirmation at T").isEmpty();

            coConfirmation.set(QuorumCoConfirmation.quorumCoConfirmation(1, 1, List.of(), List.of()));
            scheduler.fireAll();
            scheduler.fireAll();
            assertThat(listener.events()).as("gate lifted inside the cold-boot window: still deferred").isEmpty();
            assertThat(liveTasks()).as("the cold-boot deferral keeps exactly one re-check").hasSize(1);
            assertThat(liveTasks().getFirst().delay()).isEqualTo(membershipConfig().splitTimeout());

            coldBoot.set(false);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
        }

        /// A persistent stuck-promotion artifact stays suppressed across re-checks, keeps exactly one
        /// re-check in flight, and recovery cancels it — the re-check never outlives the window.
        @Test
        void suppressedFiringCheck_persistentArtifact_staysSuppressed_recoveryCancelsReCheck() {
            detector.setCoConfirmationSupplier(() ->
                QuorumCoConfirmation.quorumCoConfirmation(2, 4, List.of(new NodeId("stuck-a")), List.of()));
            members(5);
            coreCount(5);
            members(2);

            scheduler.fireAll();
            scheduler.fireAll();
            scheduler.fireAll();
            assertThat(listener.events()).isEmpty();
            assertThat(liveTasks()).as("no timer pile-up").hasSize(1);

            members(5);
            assertThat(liveTasks()).as("recovery cancels the pending re-check").isEmpty();
            scheduler.fireAll();
            assertThat(listener.events()).isEmpty();
        }

        private List<ManualTask> liveTasks() {
            return scheduler.pendingTasks()
                            .stream()
                            .filter(task -> !task.cancelled() && !task.isDone())
                            .toList();
        }
    }

    /// #642 terminal stop. A detector whose node has stopped keeps its timers on the process-wide
    /// SharedScheduler while its inputs die, so its member count freezes below threshold and the #415
    /// re-arm retries off that frozen value until the cold-boot window expires — then fires a drain for
    /// a node that no longer exists. In a shared JVM the drain is resolved by node id against the LIVE
    /// registry, so it terminates the id's NEXT incarnation.
    ///
    /// Each test below isolates ONE guarded point, so removing that single check turns exactly one test
    /// red rather than the whole class.
    @Nested
    class TerminalStop {
        @Test
        void stop_armedBelowThresholdChain_neverFires() {
            armBelowThreshold();
            assertThat(scheduler.pendingTasks()).hasSize(1);

            detector.stop();
            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("a stopped detector must emit nothing, however long the below-threshold count persists")
                .isEmpty();
        }

        @Test
        void stop_countPathCheckAlreadyDispatched_neverFires() {
            armBelowThreshold();

            var coConfirmation = new CountingCoConfirmation();

            detector.setCoConfirmationSupplier(coConfirmation);

            var dispatched = scheduler.pendingTasks().getFirst();

            detector.stop();
            timeSource.advanceTimeMillis(20_000);
            // Cancellation cannot reach a body SharedScheduler has already dispatched, so the latch —
            // not the cancel — has to be what stops this.
            dispatched.forceRun();

            assertThat(listener.events())
                .as("the count-path firing check must no-op on a stopped detector even when cancellation lost the race")
                .isEmpty();
            assertThat(coConfirmation.samples())
                .as("it must bail at its ENTRY, not deeper: sampling co-confirmation reads live membership "
                   + "and SWIM state that a stopped node no longer owns")
                .isZero();
        }

        @Test
        void stop_presencePathCheckAlreadyDispatched_neverFires() {
            members(5);
            coreCount(5);
            assertThat(detector.isArmed()).isTrue();
            // Members stay quorate, so ONLY the PASSIVE presence path arms a task.
            detector.onQuorumPresence(false);

            var coConfirmation = new CountingCoConfirmation();

            detector.setCoConfirmationSupplier(coConfirmation);

            var dispatched = scheduler.pendingTasks().getFirst();

            detector.stop();
            timeSource.advanceTimeMillis(20_000);
            dispatched.forceRun();

            assertThat(listener.events())
                .as("the PASSIVE presence-edge check must no-op on a stopped detector even when cancellation lost the race")
                .isEmpty();
            assertThat(coConfirmation.samples())
                .as("the presence path must bail at its ENTRY too, for the same reason")
                .isZero();
        }

        /// Isolates the [`QuorumLossDetector#emitIntent`] latch — the one guarding the window between a
        /// check passing its OWN entry guard and the listener actually being called. The
        /// co-confirmation supplier is sampled inside the firing check immediately before `emitIntent`,
        /// so stopping from there reproduces that interleaving deterministically.
        @Test
        void stop_landingBetweenCheckAndEmit_neverFires() {
            armBelowThreshold();
            detector.setCoConfirmationSupplier(QuorumLossDetectorTest.this::stopDetectorThenReportAbsent);
            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events())
                .as("stop() landing after the firing check passed must still suppress the intent at the emit point")
                .isEmpty();
        }

        @Test
        void stop_memberCountChangedAfterwards_schedulesNoNewCheck() {
            armBelowThreshold();
            detector.stop();

            var tasksAtStop = scheduler.pendingTasks().size();

            // Recovery then a fresh below-edge would normally open a new window and arm a new check.
            members(5);
            members(1);

            assertThat(scheduler.pendingTasks())
                .as("a stopped detector must never arm another firing check, whatever its inputs do")
                .hasSize(tasksAtStop);
            scheduler.fireAll();
            assertThat(listener.events()).isEmpty();
        }

        @Test
        void stop_quorumPresenceLostAfterwards_schedulesNoNewCheck() {
            members(5);
            coreCount(5);
            detector.stop();

            var tasksAtStop = scheduler.pendingTasks().size();

            detector.onQuorumPresence(false);

            assertThat(scheduler.pendingTasks())
                .as("a stopped detector must never arm another presence check either")
                .hasSize(tasksAtStop);
            scheduler.fireAll();
            assertThat(listener.events()).isEmpty();
        }

        @Test
        void stop_pendingCountPathCheck_isCancelled() {
            armBelowThreshold();

            var pending = scheduler.pendingTasks().getFirst();

            detector.stop();

            assertThat(pending.cancelled())
                .as("stop() also cancels, so a check that has NOT yet been dispatched never runs at all")
                .isTrue();
        }
    }

    /// Reach quorum (arming the detector), then drop to self-only so a below-threshold window opens and
    /// a firing check is armed.
    @Contract
    private void armBelowThreshold() {
        members(5);
        coreCount(5);
        members(1);
    }

    /// Co-confirmation supplier that stops the detector as a side effect of being sampled. The sample
    /// happens inside the firing check just before `emitIntent`, which is the only way to land a stop()
    /// in that window deterministically.
    private QuorumCoConfirmation stopDetectorThenReportAbsent() {
        detector.stop();

        return QuorumCoConfirmation.absent();
    }

    /// Co-confirmation supplier that counts how often the firing paths sample it. That sample sits
    /// AFTER a check's entry latch and BEFORE `emitIntent`, so it is the observable that distinguishes
    /// "bailed at the entry" from "ran the whole check and was stopped at the last gate". Without it
    /// the entry latches are invisible to a test, because `emitIntent`'s latch masks their absence.
    private static final class CountingCoConfirmation implements Supplier<QuorumCoConfirmation> {
        private final AtomicInteger samples = new AtomicInteger();

        @Override
        public QuorumCoConfirmation get() {
            samples.incrementAndGet();

            return QuorumCoConfirmation.absent();
        }

        int samples() {
            return samples.get();
        }
    }

    private static final class RecordingListener implements Consumer<QuorumLossIntent> {
        private final List<QuorumLossIntent> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(QuorumLossIntent intent) {
            events.add(intent);
        }

        List<QuorumLossIntent> events() {
            return List.copyOf(events);
        }
    }

    private static final class MutableIntSupplier implements IntSupplier {
        private final AtomicInteger value;

        MutableIntSupplier(int initial) {
            this.value = new AtomicInteger(initial);
        }

        @Override
        public int getAsInt() {
            return value.get();
        }

        @Contract
        void set(int newValue) {
            value.set(newValue);
        }
    }

    /// Controllable time source — advances only on explicit method calls.
    private static final class TestTimeSource implements TimeSource {
        private volatile long nanos = 0L;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Contract
        void advanceTimeMillis(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    /// Manual scheduler — captures `(Runnable, delay)` pairs without ever invoking them on a
    /// background thread. Tests drive fire/cancel explicitly via `fireAll()` / the returned
    /// future's `cancel(false)`.
    private static final class ManualScheduler implements NttTimerScheduler {
        private final List<ManualTask> tasks = new ArrayList<>();

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            var task = new ManualTask(runnable, delay);

            tasks.add(task);

            return task;
        }

        @Contract
        synchronized void fireAll() {
            for (var task : List.copyOf(tasks)) {
                task.runIfLive();
            }
        }

        synchronized List<ManualTask> pendingTasks() {
            return List.copyOf(tasks);
        }
    }

    private static final class ManualTask implements ScheduledFuture<Object> {
        private final Runnable runnable;
        private final TimeSpan delay;
        private volatile boolean cancelled;
        private volatile boolean done;

        ManualTask(Runnable runnable, TimeSpan delay) {
            this.runnable = runnable;
            this.delay = delay;
        }

        TimeSpan delay() {
            return delay;
        }

        boolean cancelled() {
            return cancelled;
        }

        @Contract
        void runIfLive() {
            if (cancelled || done) {
                return;
            }
            done = true;
            runnable.run();
        }

        /// Run the body IGNORING cancellation. This models what the production
        /// [`org.pragmatica.lang.utils.SharedScheduler`] does once a task has been handed to its
        /// virtual thread: `cancel(false)` returns, but the body still runs to completion. That race
        /// is exactly what the detector's terminal `stopped` latch exists to close (#642), and it is
        /// unreachable through [#runIfLive], which honours the cancel flag.
        @Contract
        void forceRun() {
            done = true;
            runnable.run();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delay.nanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled || done;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
