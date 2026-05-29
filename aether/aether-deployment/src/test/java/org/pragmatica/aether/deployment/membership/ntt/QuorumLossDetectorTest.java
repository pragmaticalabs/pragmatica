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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector.quorumLossDetector;


/// Unit tests for [`QuorumLossDetector`] — mechanism in isolation, no SWIM/config wiring.
/// Member count is supplied externally via [`QuorumLossDetector#onMemberCountChanged`] and
/// already includes self; a cluster of "self + N connected peers" is fed as `N + 1`.
class QuorumLossDetectorTest {
    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private RecordingListener listener;
    private MutableIntSupplier coreCount;
    private QuorumLossDetector detector;
    private int lastMemberCount;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        scheduler = new ManualScheduler();
        listener = new RecordingListener();
        coreCount = new MutableIntSupplier(0);
        lastMemberCount = 1;
        detector = quorumLossDetector(membershipConfig(), coreCount, timeSource, scheduler);
        detector.setQuorumLossListener(listener);
    }

    /// Feed a fresh member count (includes self) and remember it so a subsequent core-count
    /// change can re-trigger a recompute against the same membership.
    @Contract
    private void members(int memberCount) {
        lastMemberCount = memberCount;
        detector.onMemberCountChanged(memberCount);
    }

    /// Replicate the original `onConfiguredCoreCountChanged` semantics — change the configured
    /// core size and recompute against the current member count.
    @Contract
    private void coreCount(int newCoreCount) {
        coreCount.set(newCoreCount);
        detector.onMemberCountChanged(lastMemberCount);
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
                    .isEqualTo(membershipConfig().quorumLossDrainThreshold());

            timeSource.advanceTimeMillis(8_000);
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
            var intent = listener.events().getFirst();

            assertThat(intent.observedLocalQuorumCount()).isEqualTo(1);
            assertThat(intent.requiredThreshold()).isEqualTo(3);
            assertThat(intent.observedAtNanos()).isEqualTo(TimeSpan.timeSpan(8_000).millis().nanos());
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
            assertThat(window2Task.delay()).isEqualTo(membershipConfig().quorumLossDrainThreshold());

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

    private static final class RecordingListener implements java.util.function.Consumer<QuorumLossIntent> {
        private final List<QuorumLossIntent> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(QuorumLossIntent intent) {
            events.add(intent);
        }

        List<QuorumLossIntent> events() {
            return List.copyOf(events);
        }
    }

    private static final class MutableIntSupplier implements java.util.function.IntSupplier {
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
