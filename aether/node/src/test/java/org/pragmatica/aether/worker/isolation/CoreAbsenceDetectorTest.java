// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.isolation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.ntt.NttTimerScheduler;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.worker.isolation.CoreAbsenceDetector.coreAbsenceDetector;


/// The mechanism in isolation — no cluster, no collector. Time and timer fire are both driven
/// explicitly, so nothing here depends on wall-clock advancement.
class CoreAbsenceDetectorTest {
    private static final TimeSpan CORE_ABSENCE = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan CHECK_INTERVAL = TimeSpan.timeSpan(1).seconds();

    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private AtomicInteger fenceCount;
    private CoreAbsenceDetector detector;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        scheduler = new ManualScheduler();
        fenceCount = new AtomicInteger();
        detector = coreAbsenceDetector(CORE_ABSENCE, CHECK_INTERVAL, timeSource, scheduler);
        detector.setCoreAbsenceListener(_ -> fenceCount.incrementAndGet());
        detector.start();
    }

    @Nested
    class ColdStart {

        /// The safety-critical case. A node that has NEVER heard the core is forming, not isolated.
        /// Without this latch every community would dissolve itself during formation — the same
        /// class of false-positive the core tier's arm-after-first-quorum guard exists to prevent.
        @Test
        void evaluate_noPingEverReceived_neverFences() {
            for (var tick = 0; tick < 100; tick++) {
                timeSource.advanceTimeMillis(1_000);
                scheduler.fireAll();
            }

            assertFalse(detector.isArmed());
            assertFalse(detector.isFenced());
            assertEquals(0, fenceCount.get());
        }

        @Test
        void isArmed_afterFirstPing_latchesTrue() {
            assertFalse(detector.isArmed());
            detector.recordCorePing();

            assertTrue(detector.isArmed());
        }
    }

    @Nested
    class Fencing {

        @Test
        void evaluate_pingWithinWindow_doesNotFence() {
            detector.recordCorePing();
            timeSource.advanceTimeMillis(9_000);
            scheduler.fireAll();

            assertFalse(detector.isFenced());
            assertEquals(0, fenceCount.get());
        }

        @Test
        void evaluate_pingStaleBeyondWindow_fences() {
            detector.recordCorePing();
            timeSource.advanceTimeMillis(10_000);
            scheduler.fireAll();

            assertTrue(detector.isFenced());
            assertEquals(1, fenceCount.get());
        }

        /// Dissolve is not an operation to run twice. The tick keeps running after the fence so the
        /// observability accessors stay honest, which is exactly the condition under which a missing
        /// CAS guard would re-fire on every subsequent tick.
        @Test
        void evaluate_staleForManyTicks_firesExactlyOnce() {
            detector.recordCorePing();
            timeSource.advanceTimeMillis(10_000);

            for (var tick = 0; tick < 20; tick++) {
                timeSource.advanceTimeMillis(1_000);
                scheduler.fireAll();
            }

            assertEquals(1, fenceCount.get());
        }

        /// A ping arriving late but before the deadline resets the countdown. This is the routine
        /// case — a slow tick or a brief hiccup must not dissolve a healthy community.
        @Test
        void recordCorePing_beforeDeadline_preventsFence() {
            detector.recordCorePing();
            timeSource.advanceTimeMillis(9_000);
            scheduler.fireAll();
            detector.recordCorePing();
            timeSource.advanceTimeMillis(9_000);
            scheduler.fireAll();

            assertFalse(detector.isFenced());
            assertEquals(0, fenceCount.get());
        }

        /// The fence is terminal by design: recovery is a re-join, not a node deciding on its own
        /// that it is serving again. Pings resuming after the fence must not un-fence it.
        @Test
        void recordCorePing_afterFence_doesNotUnFence() {
            detector.recordCorePing();
            timeSource.advanceTimeMillis(10_000);
            scheduler.fireAll();
            detector.recordCorePing();
            scheduler.fireAll();

            assertTrue(detector.isFenced());
            assertEquals(1, fenceCount.get());
        }
    }

    @Nested
    class Observability {

        @Test
        void remainingBeforeFenceNanos_unarmed_isEmpty() {
            assertTrue(detector.remainingBeforeFenceNanos().isEmpty());
        }

        @Test
        void remainingBeforeFenceNanos_countsDownFromTheThreshold() {
            detector.recordCorePing();
            timeSource.advanceTimeMillis(4_000);

            assertEquals(TimeSpan.timeSpan(6).seconds().nanos(),
                         detector.remainingBeforeFenceNanos().or(-1L));
        }

        @Test
        void remainingBeforeFenceNanos_pastTheThreshold_clampsAtZeroThenEmptiesOnFence() {
            detector.recordCorePing();
            timeSource.advanceTimeMillis(12_000);

            assertEquals(0L, detector.remainingBeforeFenceNanos().or(-1L));
            scheduler.fireAll();

            assertTrue(detector.remainingBeforeFenceNanos().isEmpty());
        }

        @Test
        void sinceLastCorePingNanos_neverPinged_isEmpty() {
            assertTrue(detector.sinceLastCorePingNanos().isEmpty());
        }
    }

    @Nested
    class Lifecycle {

        /// Forge runs many nodes in ONE JVM on a shared scheduler, so a tick left armed after stop()
        /// outlives its node.
        @Test
        void stop_cancelsThePendingTick() {
            var pending = scheduler.pendingTasks();

            assertEquals(1, pending.size());
            detector.stop();

            assertTrue(pending.getFirst().cancelled());
        }

        @Test
        void stop_thenTick_doesNotReschedule() {
            detector.stop();
            scheduler.fireAll();

            assertTrue(scheduler.pendingTasks()
                                .stream()
                                .allMatch(task -> task.cancelled() || task.done()));
        }
    }

    private static final class TestTimeSource implements TimeSource {
        private volatile long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Contract
        void advanceTimeMillis(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    /// Captures `(Runnable, delay)` pairs without ever running them on a background thread; the test
    /// drives fire and cancel explicitly. `fireAll` iterates a COPY, so a task the detector schedules
    /// during the sweep runs on the next sweep rather than looping forever inside this one.
    private static final class ManualScheduler implements NttTimerScheduler {
        private final List<ManualTask> tasks = new ArrayList<>();

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            var task = new ManualTask(runnable);

            tasks.add(task);

            return task;
        }

        @Contract
        synchronized void fireAll() {
            List.copyOf(tasks).forEach(ManualTask::runIfLive);
        }

        synchronized List<ManualTask> pendingTasks() {
            return List.copyOf(tasks);
        }
    }

    private static final class ManualTask implements ScheduledFuture<Object> {
        private final Runnable runnable;
        private volatile boolean cancelled;
        private volatile boolean done;

        ManualTask(Runnable runnable) {
            this.runnable = runnable;
        }

        boolean cancelled() {
            return cancelled;
        }

        boolean done() {
            return done;
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
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;

            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
