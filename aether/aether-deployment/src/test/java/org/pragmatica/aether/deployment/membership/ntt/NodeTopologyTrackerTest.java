// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.SwimObservation.DepartedObserved;
import org.pragmatica.swim.SwimObservation.HealthyObserved;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker.nodeTopologyTracker;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Unit tests for [`NodeTopologyTracker`] — mechanism in isolation, no SWIM/QUIC wiring.
class NodeTopologyTrackerTest {
    private static final NodeId PEER = NodeId.randomNodeId();

    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private NodeTopologyTracker ntt;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        scheduler = new ManualScheduler();
        ntt = nodeTopologyTracker(membershipConfig(), timeSource, scheduler);
    }

    @Nested
    class HappyPath {
        @Test
        void onSwimObservation_schedulesTimer_whenDeparted() {
            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));

            assertThat(ntt.pendingTimerCount()).isEqualTo(1);
            assertThat(ntt.firedEventCount()).isZero();
            assertThat(scheduler.pendingTasks()).hasSize(1);
            assertThat(scheduler.pendingTasks().getFirst().delay()).isEqualTo(membershipConfig().nttDepartureTimeout());
        }

        @Test
        void timerFire_emitsClaimableEvent_afterDeadline() {
            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));
            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            assertThat(ntt.firedEventCount()).isEqualTo(1);
            assertThat(ntt.pendingTimerCount()).isZero();

            var claimed = ntt.claim(PEER);

            assertThat(claimed.isPresent()).isTrue();
            claimed.onPresent(event -> {
                assertThat(event.peerId()).isEqualTo(PEER);
                assertThat(event.firedAtNanos()).isEqualTo(timeSpan(20_000).millis().nanos());
            });
            assertThat(ntt.firedEventCount()).isZero();
        }

        @Test
        void drainAllFiredEvents_returnsAllFired_andClearsMap() {
            var second = NodeId.randomNodeId();

            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));
            ntt.onSwimObservation(new DepartedObserved(second, 1L));
            timeSource.advanceTimeMillis(20_000);
            scheduler.fireAll();

            var drained = ntt.drainAllFiredEvents();

            assertThat(drained).hasSize(2);
            assertThat(drained.stream().map(TopologyUnhealthyEvent::peerId)).containsExactlyInAnyOrder(PEER, second);
            assertThat(ntt.firedEventCount()).isZero();
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

            // Even if the scheduler tried to fire (race window — scheduler has the task
            // reference but cancel was called), nothing would land in the entries map.
            scheduler.fireAll();

            assertThat(ntt.firedEventCount()).isZero();
        }

        @Test
        void onQuicReconnect_clearsAlreadyFiredEvent() {
            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));
            scheduler.fireAll();
            assertThat(ntt.firedEventCount()).isEqualTo(1);

            ntt.onQuicReconnect(PEER);

            assertThat(ntt.firedEventCount()).isZero();
            assertThat(ntt.claim(PEER).isPresent()).isFalse();
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

        @Test
        void claim_onMissingPeer_returnsNone() {
            assertThat(ntt.claim(PEER).isPresent()).isFalse();
        }

        @Test
        void claim_beforeTimerFires_returnsNone_andLeavesTimerArmed() {
            ntt.onSwimObservation(new DepartedObserved(PEER, 1L));

            assertThat(ntt.claim(PEER).isPresent()).isFalse();
            assertThat(ntt.pendingTimerCount()).isEqualTo(1);
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
