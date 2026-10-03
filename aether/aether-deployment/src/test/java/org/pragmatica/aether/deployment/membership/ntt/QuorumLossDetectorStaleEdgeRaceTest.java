// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.TimeSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;

/// v1854 probe: `recompute()` reads the derived count OUTSIDE the monitor that `reconcileWindow` takes. Two
/// concurrent re-evaluations can therefore apply their window edges in the opposite order to their reads: the
/// stale (quorate) evaluation closes the window the fresh (below) evaluation opened, and no later trigger exists.
class QuorumLossDetectorStaleEdgeRaceTest {
    @Test
    void reevaluate_staleQuorateReadRacingFreshBelowRead_windowStillOpensAndFences() throws Exception {
        var count = new AtomicInteger(5);
        var staleThread = new AtomicReference<Thread>();
        var staleRead = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tasks = new CopyOnWriteArrayList<Task>();
        var intents = new ArrayList<QuorumLossIntent>();
        var detector = QuorumLossDetector.quorumLossDetector(membershipConfig(),
                                                             () -> 5,
                                                             () -> readCount(count, staleThread, staleRead, release),
                                                             TimeSource.system(),
                                                             (runnable, delay) -> schedule(tasks, runnable));

        detector.setQuorumLossListener(intents::add);
        detector.reevaluate();
        assertThat(detector.isArmed()).isTrue();

        var a = new Thread(detector::reevaluate);

        staleThread.set(a);
        a.start();
        staleRead.await();

        count.set(2);
        var b = new Thread(detector::reevaluate);

        b.start();
        // Unfixed: B completes and opens the window while A is stalled. Fixed: B waits on the detector monitor.
        while (b.isAlive() && b.getState() != Thread.State.BLOCKED) {
            Thread.onSpinWait();
        }

        release.countDown();
        a.join();
        b.join();

        List.copyOf(tasks).forEach(Task::runIfLive);

        assertThat(detector.isBelowThreshold()).as("the node IS below threshold").isTrue();
        assertThat(detector.belowThresholdSinceNanos().isPresent()).as("a below-threshold window is open").isTrue();
        assertThat(intents).as("the self-fence fires").hasSize(1);
    }

    /// Control: the same sequence without the interleaving fences. Proves the instrument can go green.
    @Test
    void reevaluate_sequentialBelowRead_opensWindowAndFences() {
        var count = new AtomicInteger(5);
        var tasks = new CopyOnWriteArrayList<Task>();
        var intents = new ArrayList<QuorumLossIntent>();
        var detector = QuorumLossDetector.quorumLossDetector(membershipConfig(),
                                                             () -> 5,
                                                             count::get,
                                                             TimeSource.system(),
                                                             (runnable, delay) -> schedule(tasks, runnable));

        detector.setQuorumLossListener(intents::add);
        detector.reevaluate();
        detector.reevaluate();
        count.set(2);
        detector.reevaluate();
        List.copyOf(tasks).forEach(Task::runIfLive);

        assertThat(detector.belowThresholdSinceNanos().isPresent()).isTrue();
        assertThat(intents).hasSize(1);
    }

    private static int readCount(AtomicInteger count,
                                 AtomicReference<Thread> staleThread,
                                 CountDownLatch staleRead,
                                 CountDownLatch release) {
        var value = count.get();

        if (Thread.currentThread() == staleThread.get()) {
            staleRead.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return value;
    }

    private static ScheduledFuture<?> schedule(List<Task> tasks, Runnable runnable) {
        var task = new Task(runnable);

        tasks.add(task);

        return task;
    }

    private static final class Task implements ScheduledFuture<Object> {
        private final Runnable runnable;
        private volatile boolean cancelled;
        private volatile boolean done;

        Task(Runnable runnable) {
            this.runnable = runnable;
        }

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
