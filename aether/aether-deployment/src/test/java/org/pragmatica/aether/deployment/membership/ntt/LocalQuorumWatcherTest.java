// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.LocalQuorumWatcher.localQuorumWatcher;


/// Unit tests for [`LocalQuorumWatcher`] — mechanism in isolation, no QUIC/config wiring.
class LocalQuorumWatcherTest {
    private static final NodeId PEER_A = NodeId.randomNodeId();
    private static final NodeId PEER_B = NodeId.randomNodeId();
    private static final NodeId PEER_C = NodeId.randomNodeId();
    private static final NodeId PEER_D = NodeId.randomNodeId();

    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private RecordingListener listener;
    private LocalQuorumWatcher watcher;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        scheduler = new ManualScheduler();
        listener = new RecordingListener();
        watcher = localQuorumWatcher(membershipConfig(), timeSource, scheduler);
        watcher.setQuorumLossListener(listener);
    }

    @Nested
    class DefaultState {
        @Test
        void freshWatcher_isNotBelow_andSchedulesNoTimer() {
            assertThat(watcher.isBelowThreshold()).isFalse();
            assertThat(watcher.currentRequiredThreshold()).isZero();
            assertThat(watcher.belowThresholdSinceNanos().isPresent()).isFalse();
            assertThat(scheduler.pendingTasks()).isEmpty();
            assertThat(listener.events()).isEmpty();
        }

        @Test
        void connectsBeforeCoreCount_doNotFire_thresholdUnknown() {
            watcher.onPeerConnected(PEER_A);
            watcher.onPeerConnected(PEER_B);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
            assertThat(watcher.isBelowThreshold()).isFalse();
        }
    }

    @Nested
    class BelowThresholdFiring {
        @Test
        void coreCountChangedToFive_onlySelf_belowThreshold_intentFiresAfterDeadline() {
            watcher.onConfiguredCoreCountChanged(5);

            assertThat(watcher.currentRequiredThreshold()).isEqualTo(3);
            assertThat(watcher.currentLocalQuorumCount()).isEqualTo(1);
            assertThat(watcher.isBelowThreshold()).isTrue();
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
        void peersAddedThatRestoreThreshold_intentDoesNotFire_evenIfScheduledTaskRuns() {
            watcher.onConfiguredCoreCountChanged(5);
            watcher.onPeerConnected(PEER_A);
            watcher.onPeerConnected(PEER_B);

            assertThat(watcher.isBelowThreshold()).isFalse();
            assertThat(watcher.currentLocalQuorumCount()).isEqualTo(3);

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
        }

        @Test
        void peerAddedBeforeDeadline_cancelsScheduledTask() {
            watcher.onConfiguredCoreCountChanged(5);
            timeSource.advanceTimeMillis(3_000);

            watcher.onPeerConnected(PEER_A);
            watcher.onPeerConnected(PEER_B);

            assertThat(scheduler.pendingTasks().getFirst().cancelled()).isTrue();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
            assertThat(watcher.belowThresholdSinceNanos().isPresent()).isFalse();
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
        void peersAddedThenRemoved_intentFires_onSecondWindowsOwnTask_notFirstWindows() {
            watcher.onConfiguredCoreCountChanged(5);
            // Window 1 starts at T=0; task #0 scheduled.
            timeSource.advanceTimeMillis(5_000);
            // Add enough peers to go above threshold (need 3): self + A + B.
            watcher.onPeerConnected(PEER_A);
            watcher.onPeerConnected(PEER_B);
            assertThat(watcher.isBelowThreshold()).isFalse();

            // Window 1's task was cancelled on recovery.
            assertThat(scheduler.pendingTasks().getFirst().cancelled()).isTrue();
            assertThat(listener.events()).isEmpty();

            // Drop back below threshold by removing PEER_B — opens window 2 at T=6s.
            timeSource.advanceTimeMillis(1_000);
            watcher.onPeerDisconnected(PEER_B);
            assertThat(watcher.isBelowThreshold()).isTrue();
            assertThat(watcher.currentLocalQuorumCount()).isEqualTo(2);
            assertThat(watcher.belowThresholdSinceNanos().isPresent()).isTrue();
            watcher.belowThresholdSinceNanos()
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
        void coreCountShrunkToMatchCurrentPeers_aboveThreshold_noFire() {
            watcher.onConfiguredCoreCountChanged(7);
            watcher.onPeerConnected(PEER_A);
            watcher.onPeerConnected(PEER_B);
            // threshold=4, quorum=3, below
            assertThat(watcher.isBelowThreshold()).isTrue();

            // Shrink so quorum (3) meets threshold (3): coreCount=3 → threshold=2 (3/2+1).
            watcher.onConfiguredCoreCountChanged(3);
            assertThat(watcher.currentRequiredThreshold()).isEqualTo(2);
            assertThat(watcher.isBelowThreshold()).isFalse();

            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(listener.events()).isEmpty();
        }
    }

    @Nested
    class Idempotence {
        @Test
        void duplicateOnPeerConnected_doesNotChangeQuorumCount_orRescheduleWindow() {
            watcher.onConfiguredCoreCountChanged(5);
            watcher.onPeerConnected(PEER_A);
            watcher.onPeerConnected(PEER_A);
            watcher.onPeerConnected(PEER_A);

            assertThat(watcher.currentLocalQuorumCount()).isEqualTo(2);
            // Still below (need 3): exactly one window scheduled, the original one.
            assertThat(scheduler.pendingTasks()).hasSize(1);
            assertThat(scheduler.pendingTasks().getFirst().cancelled()).isFalse();
        }

        @Test
        void disconnectOfUntrackedPeer_isNoOp() {
            watcher.onConfiguredCoreCountChanged(5);
            watcher.onPeerConnected(PEER_A);
            watcher.onPeerConnected(PEER_B);
            watcher.onPeerConnected(PEER_C);
            // Above threshold: 4 ≥ 3.
            assertThat(watcher.isBelowThreshold()).isFalse();

            watcher.onPeerDisconnected(PEER_D);

            assertThat(watcher.currentLocalQuorumCount()).isEqualTo(4);
            assertThat(watcher.isBelowThreshold()).isFalse();
        }

        @Test
        void firingTaskAtMostOnce_perBelowWindow() {
            watcher.onConfiguredCoreCountChanged(5);
            timeSource.advanceTimeMillis(8_000);

            scheduler.fireAll();
            scheduler.fireAll();
            scheduler.fireAll();

            assertThat(listener.events()).hasSize(1);
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
