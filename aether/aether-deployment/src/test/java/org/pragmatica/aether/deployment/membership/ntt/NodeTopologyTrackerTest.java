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
import org.pragmatica.swim.SwimObservation.DepartedObserved;
import org.pragmatica.swim.SwimObservation.HealthyObserved;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker.nodeTopologyTracker;


/// Unit tests for [`NodeTopologyTracker`] (E2 Phase 1.5) — timer-only mechanism;
/// fire invokes a `Runnable onReconcileNeeded` callback. NTT no longer holds
/// per-peer event records.
class NodeTopologyTrackerTest {
    private static final NodeId PEER = NodeId.randomNodeId();

    private ManualScheduler scheduler;
    private AtomicInteger reconcileInvocations;
    private NodeTopologyTracker ntt;

    @BeforeEach
    void setUp() {
        scheduler = new ManualScheduler();
        reconcileInvocations = new AtomicInteger(0);
        ntt = nodeTopologyTracker(membershipConfig(), scheduler, reconcileInvocations::incrementAndGet);
    }

    @Nested
    class HappyPath {
        @Test
        void onSwimObservation_schedulesTimer_whenDeparted() {
            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));

            assertThat(ntt.pendingTimerCount()).isEqualTo(1);
            assertThat(scheduler.pendingTasks()).hasSize(1);
            assertThat(scheduler.pendingTasks().getFirst().delay()).isEqualTo(membershipConfig().nttDepartureTimeout());
            assertThat(reconcileInvocations.get()).isZero();
        }

        @Test
        void timerFire_invokesReconcileTrigger_afterDeadline_andRemovesEntry() {
            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));

            scheduler.fireAll();

            assertThat(reconcileInvocations.get()).isEqualTo(1);
            assertThat(ntt.pendingTimerCount()).isZero();
        }

        @Test
        void multipleTimersFire_invokeTriggerOncePerExpiry() {
            var second = NodeId.randomNodeId();

            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));
            ntt.onSwimObservation(new DepartedObserved(second, 1L));
            scheduler.fireAll();

            assertThat(reconcileInvocations.get()).isEqualTo(2);
            assertThat(ntt.pendingTimerCount()).isZero();
        }
    }

    @Nested
    class Cancellation {
        @Test
        void onQuicReconnect_cancelsPendingTimer_andNeverFires() {
            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));

            ntt.onQuicReconnect(PEER);

            assertThat(ntt.pendingTimerCount()).isZero();
            assertThat(scheduler.pendingTasks().getFirst().cancelled()).isTrue();

            scheduler.fireAll();

            assertThat(reconcileInvocations.get()).isZero();
        }

        @Test
        void onQuicReconnect_onUntracked_isNoOp() {
            ntt.onQuicReconnect(PEER);

            assertThat(ntt.pendingTimerCount()).isZero();
            assertThat(reconcileInvocations.get()).isZero();
        }
    }

    @Nested
    class Idempotence {
        @Test
        void duplicateDeparturedObservation_doesNotRescheduleDeadline() {
            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));
            ntt.onSwimObservation(new DepartedObserved(PEER, 2L));

            assertThat(ntt.pendingTimerCount()).isEqualTo(1);
            assertThat(scheduler.pendingTasks()).hasSize(1);
        }

        @Test
        void nonDepartedObservation_isIgnored() {
            ntt.onSwimObservation(new HealthyObserved(PEER, 1L));

            assertThat(ntt.pendingTimerCount()).isZero();
            assertThat(scheduler.pendingTasks()).isEmpty();
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
