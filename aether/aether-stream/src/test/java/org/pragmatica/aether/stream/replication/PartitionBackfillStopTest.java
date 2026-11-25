// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.OwnerLifetime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #3754: the backfill's two timers, the refusal hold ([PartitionBackfill#holdSlot]) and the flight's idle watch
/// ([PartitionBackfill#watch]), end with the backfill ([PartitionBackfill#close]). For each timer: (a) one armed before the
/// stop never runs its body after it; (b) one whose body is already running when the stop lands starts no re-run, which is
/// the only effect either body can reach (a wire pull, in-memory replica rows and the local WAL, none of them written by another
/// node). Both timers are armed through a [OwnerLifetime] on a scheduler the test fires by hand. Each test has a control that
/// fires the same timer without the stop and sees the re-run, so the negative assertions cannot pass for want of a timer.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PartitionBackfillStopTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId SOURCE = NodeId.randomNodeId();
    private static final TimeSpan HOUR = TimeSpan.timeSpan(1).hours();
    private static final TimeSpan IDLE_BOUND = TimeSpan.timeSpan(50).millis();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private ReplicaRegistry registry;
    private AlignedRecovery recovery;
    private CopyOnWriteArrayList<Promise<ReplicationMessage.CatchupResponse>> pulls;
    private FakeSeam seam;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM));
        recovery = manager.alignedRecovery();
        pulls = new CopyOnWriteArrayList<>();
        seam = new FakeSeam();
    }

    /// Every pull is held until the test answers it; `pulls` lists them in the order they were issued.
    private CatchupTransport gated() {
        return (_, _) -> {
            var response = Promise.<ReplicationMessage.CatchupResponse>promise();

            pulls.add(response);

            return response;
        };
    }

    /// A backfill whose timers run on the fake seam; `flightBound` is the idle watch's bound.
    private PartitionBackfill backfill(TimeSpan flightBound) {
        registry.registerReplica(STREAM, PARTITION, SOURCE);
        registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
        registry.registerReplica(STREAM, PARTITION, SELF);

        return partitionBackfill(registry,
                                 recovery,
                                 gated(),
                                 ReplicationTransport.NOOP,
                                 SELF,
                                 flightBound,
                                 OwnerLifetime.ownerLifetime(seam));
    }

    private static StreamForwardError.ForwardNotSent refusal() {
        return StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(SOURCE));
    }

    /// A flight with a joined call, then its refusal: it lands failed and keeps the slot until the hold timer releases it.
    /// The landing runs on the run's own chain, not before `fail` returns; the flight's result resolves after the hold is armed,
    /// so waiting for it makes the hold's timer visible to the caller.
    private Promise<Long> refuseJoinedFlight(PartitionBackfill backfill) {
        var first = backfill.backfill(STREAM, PARTITION);

        backfill.backfill(STREAM, PARTITION);
        pulls.getFirst().fail(refusal());
        assertThat(first.await(AWAIT).isFailure()).as("the refused flight landed, its hold is armed").isTrue();

        return first;
    }

    /// A flight with a joined call that no answer reaches, left idle past its bound.
    private Promise<Long> idleJoinedFlight(PartitionBackfill backfill) {
        var first = backfill.backfill(STREAM, PARTITION);

        backfill.backfill(STREAM, PARTITION);
        sleepPast(IDLE_BOUND);

        return first;
    }

    private static void sleepPast(TimeSpan bound) {
        var deadline = System.nanoTime() + 2 * bound.nanos();

        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000L);
        }
    }

    @Nested
    class RefusalHold {
        private Runnable holdTimer() {
            var holds = seam.armedWithDelayBelow(HOUR);

            assertThat(holds).as("the hold was armed through the lifetime's seam, once").hasSize(1);

            return holds.getFirst();
        }

        @Test
        void holdTimer_withoutStop_releasesTheSlotAndRerunsOnce_control() {
            var backfill = backfill(HOUR);
            var released = new AtomicInteger();

            backfill.releaseWindowHook(released::incrementAndGet);
            refuseJoinedFlight(backfill);
            holdTimer().run();

            assertThat(released.get()).as("the hold's body released the slot").isEqualTo(1);
            assertThat(pulls).as("the joined call's re-run pulled").hasSize(2);
        }

        @Test
        void holdTimerArmedBeforeStop_stopped_neverRunsItsBody() {
            var backfill = backfill(HOUR);
            var released = new AtomicInteger();

            backfill.releaseWindowHook(released::incrementAndGet);
            refuseJoinedFlight(backfill);
            var timer = holdTimer();

            backfill.close();
            timer.run();

            assertThat(released.get()).as("the hold's body did not run after the stop").isZero();
            assertThat(pulls).as("no re-run after the stop").hasSize(1);
        }

        @Test
        void holdBodyInFlightAtStop_stopped_startsNoRerun() {
            var backfill = backfill(HOUR);
            var released = new AtomicInteger();

            backfill.releaseWindowHook(() -> stopWhileReleasing(backfill, released));
            refuseJoinedFlight(backfill);

            holdTimer().run();

            assertThat(released.get()).as("the body was running when the stop landed").isEqualTo(1);
            assertThat(pulls).as("the body that was past its lifetime read started no re-run").hasSize(1);
        }
    }

    @Nested
    class IdleWatch {
        private Runnable watchTimer() {
            assertThat(seam.armed()).as("the idle watch was armed through the lifetime's seam, once").hasSize(1);

            return seam.armed().getFirst().wrapper();
        }

        @Test
        void watchTimer_withoutStop_timesTheFlightOutAndRerunsOnce_control() {
            var backfill = backfill(IDLE_BOUND);
            var first = idleJoinedFlight(backfill);

            watchTimer().run();

            assertThat(first.isResolved()).as("the watch's body failed the idle flight").isTrue();
            assertThat(pulls).as("the joined call's re-run pulled").hasSize(2);
        }

        @Test
        void watchTimerArmedBeforeStop_stopped_neverRunsItsBody() {
            var backfill = backfill(IDLE_BOUND);
            var first = idleJoinedFlight(backfill);
            var timer = watchTimer();

            backfill.close();
            timer.run();

            assertThat(first.isResolved()).as("the watch's body did not run after the stop").isFalse();
            assertThat(pulls).as("no re-run after the stop").hasSize(1);
        }

        @Test
        void watchBodyInFlightAtStop_stopped_startsNoRerun() {
            var backfill = backfill(IDLE_BOUND);
            var released = new AtomicInteger();

            backfill.releaseWindowHook(() -> stopWhileReleasing(backfill, released));
            var first = idleJoinedFlight(backfill);

            watchTimer().run();

            assertThat(released.get()).as("the body was running when the stop landed").isEqualTo(1);
            assertThat(first.isResolved()).as("the body timed the flight out before the stop took effect").isTrue();
            assertThat(pulls).as("the body that was past its lifetime read started no re-run").hasSize(1);
        }
    }

    private static void stopWhileReleasing(PartitionBackfill backfill, AtomicInteger released) {
        released.incrementAndGet();
        backfill.close();
    }

    /// A cancelled-or-not future; the lifetime only ever cancels it.
    private static final class FakeFuture implements ScheduledFuture<Object> {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
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

    private record Armed(Runnable wrapper, TimeSpan delay, FakeFuture future) {}

    /// Records each wrapper the lifetime enqueues; the test runs it by hand.
    private static final class FakeSeam implements OwnerLifetime.Seam {
        private final CopyOnWriteArrayList<Armed> armed = new CopyOnWriteArrayList<>();

        @Override
        public ScheduledFuture<?> enqueue(Runnable wrapper, TimeSpan initialDelay, Option<TimeSpan> period) {
            var future = new FakeFuture();

            armed.add(new Armed(wrapper, initialDelay, future));

            return future;
        }

        List<Armed> armed() {
            return armed;
        }

        List<Runnable> armedWithDelayBelow(TimeSpan limit) {
            return armed.stream()
                        .filter(entry -> entry.delay().nanos() < limit.nanos())
                        .map(Armed::wrapper)
                        .toList();
        }
    }
}
