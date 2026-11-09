// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #263 send lane, the verifier's probes P1-P4 of PR #2896 adopted as tests: the lane's send budget (condition 1), the
/// runner's re-arm loop (no stall, never two runners) and the hand-over under the accumulator lock (condition 2, raced).
///
/// Interleavings are fixed by hooks in the lane's queue (installed by reflection: the queue's element type is private),
/// by latches and by thread joins; no sleep orders anything.
class ReplicationBatcherSendLaneTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final TimeSpan WINDOW = TimeSpan.timeSpan(1).millis();
    private static final int KIB = 1024;
    private static final ReplicationBatcher.FlushScheduler NEVER = (_, _) -> SharedScheduler.schedule(() -> {},
                                                                                                     TimeSpan.timeSpan(1)
                                                                                                             .hours());
    private static final Executor THREAD_PER_RUN = runnable -> Thread.ofPlatform()
                                                                     .name("lane-runner")
                                                                     .start(runnable);

    private final List<Long> delivered = new CopyOnWriteArrayList<>();
    private final Deque<Runnable> laneRuns = new ArrayDeque<>();

    /// P1: the sender never runs (a stuck replica path). The publisher keeps reserving 1 KiB per event: once the held
    /// bytes reach the budget every further reservation is refused, typed and transient, and the held bytes never pass
    /// the budget. Before the bound (PR head b475dc1ca1) the lane took all 20,000 batches and refused none.
    @Test
    void sendBudget_refusesReservationsAtTheBound_heldBytesNeverExceedIt() {
        var budget = 8L * KIB;
        var batcher = budgeted(budget);
        var refused = 0;

        for (var offset = 0; offset < 20_000; offset++) {
            refused += publish(batcher, offset, KIB) ? 0 : 1;
            assertThat(batcher.heldBytes()).as("held bytes after event %d", offset).isLessThanOrEqualTo(budget);
        }

        assertThat(refused).as("refused publishes").isEqualTo(20_000 - 8);
        assertThat(batcher.heldBytes()).isEqualTo(budget);
        assertThat(batcher.reservedBytes()).as("every reservation was released").isZero();
        var refusedReservation = batcher.reserve(KIB);

        assertThat(refusedReservation.isFailure()).as("the budget is spent").isTrue();
        refusedReservation.onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.REPLICATION_BEHIND));
        assertThat(StreamError.General.REPLICATION_BEHIND.transientCapacity()).as("a retryable capacity refusal").isTrue();
    }

    /// The sends return: their bytes leave the budget, and the next reservation is granted.
    @Test
    void sentBatches_freeTheirBytes_andTheNextReservationIsGranted() {
        var batcher = budgeted(2L * KIB);

        publish(batcher, 0, KIB);
        publish(batcher, 1, KIB);

        assertThat(publish(batcher, 2, KIB)).as("the budget is spent").isFalse();

        runLanes();

        assertThat(delivered).containsExactly(0L, 1L);
        assertThat(batcher.heldBytes()).isZero();
        assertThat(publish(batcher, 2, KIB)).as("granted once the sends returned").isTrue();
    }

    /// A reservation counts until it is released, and a run's bytes are held as a whole.
    @Test
    void reservationAndRunBytes_countAgainstTheBudget() {
        var batcher = budgeted(4L * KIB);

        assertThat(batcher.reserve(3L * KIB).isSuccess()).isTrue();
        assertThat(batcher.reserve(2L * KIB).isSuccess()).as("3 KiB reserved, 2 more would pass 4").isFalse();

        batcher.addRun(STREAM, PARTITION, 0, List.of(new byte[KIB], new byte[KIB], new byte[KIB]), List.of(0L, 1L, 2L), Epoch.ZERO);
        batcher.release(3L * KIB);

        assertThat(batcher.heldBytes()).as("the run is held whole").isEqualTo(3L * KIB);
        assertThat(batcher.reserve(2L * KIB).isSuccess()).as("3 KiB held, 2 more would pass 4").isFalse();
        assertThat(batcher.reserve(KIB).isSuccess()).as("exactly at the budget").isTrue();
    }

    /// With nothing held or reserved, an event larger than the budget is still granted, so a partition can always
    /// progress; with anything held it is refused.
    @Test
    void eventAboveTheBudget_isGrantedOnlyWhenNothingIsHeld() {
        var batcher = budgeted(KIB);

        assertThat(batcher.reserve(2L * KIB).isSuccess()).isTrue();
        assertThat(batcher.reserve(1).isSuccess()).isFalse();

        batcher.release(2L * KIB);

        assertThat(batcher.reserve(1).isSuccess()).isTrue();
        assertThat(batcher.reserve(2L * KIB).isSuccess()).isFalse();
    }

    /// An event or a run the batcher refuses (closed) holds nothing, and a batch whose sender throws still frees its bytes.
    @Test
    void refusedAndFailedBatches_holdNothing() {
        var failing = ReplicationBatcher.adaptiveReplicationBatcher((_, _) -> {
                                                                        throw new IllegalStateException("encode failed");
                                                                    },
                                                                    100,
                                                                    WINDOW,
                                                                    KIB,
                                                                    NEVER,
                                                                    Runnable::run);

        failing.add(STREAM, PARTITION, 0, new byte[KIB], 0, Epoch.ZERO);

        assertThat(failing.heldBytes()).as("a throwing sender frees its batch").isZero();

        var batcher = budgeted(KIB);

        batcher.close();

        assertThat(batcher.add(STREAM, PARTITION, 0, new byte[KIB], 0, Epoch.ZERO).isFailure()).isTrue();
        assertThat(batcher.addRun(STREAM, PARTITION, 0, List.of(new byte[KIB]), List.of(0L), Epoch.ZERO).isFailure()).isTrue();
        assertThat(batcher.heldBytes()).as("refused events hold nothing").isZero();
    }

    /// The retry after an eviction race counts the event once (#1246 review N4's path).
    @Test
    void retryOnAFreshAccumulator_countsTheEventOnce() {
        var batcher = ReplicationBatcher.adaptiveReplicationBatcher((_, snapshot) -> delivered.add(snapshot.fromOffset()),
                                                                    100,
                                                                    WINDOW,
                                                                    NEVER,
                                                                    laneRuns::add);

        batcher.add(STREAM, PARTITION, 0, new byte[3], 0, Epoch.ZERO);
        batcher.add(STREAM, PARTITION, 1, new byte[5], 1, Epoch.ZERO);
        runLanes();
        batcher.accumulatorFor(STREAM, PARTITION)
               .onEmpty(() -> fail("the window is open"))
               .onPresent(ReplicationBatcher.BatchAccumulator::drain);

        assertThat(batcher.heldBytes()).as("the drained window's event stays counted: it never reached a lane").isEqualTo(5);

        batcher.add(STREAM, PARTITION, 2, new byte[7], 2, Epoch.ZERO);
        runLanes();

        assertThat(delivered).containsExactly(0L, 2L);
        assertThat(batcher.heldBytes()).as("offset 2 was counted once and freed once").isEqualTo(5);
    }

    /// P2, item 2 (stall): a batch offered after the runner's last `poll()` and before it releases `running` loses the
    /// start CAS; the runner's re-check takes it. Without the re-check loop the batch is stranded.
    @Test
    void batchOfferedInTheRearmWindow_isSent() throws Exception {
        var sent = new CountDownLatch(2);
        var batcher = ReplicationBatcher.adaptiveReplicationBatcher((_, snapshot) -> deliver(sent, snapshot),
                                                                    100,
                                                                    WINDOW,
                                                                    NEVER,
                                                                    THREAD_PER_RUN);
        var armed = new AtomicBoolean(true);

        replaceQueue(batcher, new HookedQueue() {
            @Override
            public Object poll() {
                var polled = super.poll();

                if (polled == null && armed.compareAndSet(true, false)) {
                    joinOn(Thread.ofPlatform().start(() -> addRun(batcher, 2, 3)));
                }

                return polled;
            }
        });
        addRun(batcher, 0, 1);

        assertThat(sent.await(5, TimeUnit.SECONDS)).as("the re-arm window's batch was sent: %s", delivered).isTrue();
        assertThat(armed.get()).as("the hook ran").isFalse();
        assertThat(delivered).containsExactly(0L, 2L);
    }

    /// P3, item 2 (two runners): batches offered right after the runner released `running` start a second runner, which
    /// takes the first of them. The first runner's re-check finds the queue non-empty and must lose the CAS: two runners
    /// at once would send concurrently and out of order. The second runner's send waits for the first runner to end.
    @Test
    void runnerReleasingRunning_neverRunsBesideASecondRunner() throws Exception {
        var concurrent = new AtomicInteger();
        var maxConcurrent = new AtomicInteger();
        var secondSending = new CountDownLatch(1);
        var firstRunner = new AtomicReference<Thread>();
        var sent = new CountDownLatch(3);
        ReplicationBatcher.BatchSender sender = (_, snapshot) -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            awaitFirstRunnerIfSecond(snapshot, firstRunner, secondSending);
            concurrent.decrementAndGet();
            deliver(sent, snapshot);
        };
        var batcher = ReplicationBatcher.adaptiveReplicationBatcher((key, snapshot) -> firstRunsFirst(sender,
                                                                                                     firstRunner,
                                                                                                     key,
                                                                                                     snapshot),
                                                                    100,
                                                                    WINDOW,
                                                                    NEVER,
                                                                    THREAD_PER_RUN);
        var armed = new AtomicBoolean(true);

        replaceQueue(batcher, new HookedQueue() {
            @Override
            public boolean isEmpty() {
                if (Thread.currentThread() == firstRunner.get() && armed.compareAndSet(true, false)) {
                    joinOn(Thread.ofPlatform().start(() -> offerTwoRuns(batcher)));
                    awaitOn(secondSending);
                }

                return super.isEmpty();
            }
        });
        addRun(batcher, 0, 1);

        assertThat(sent.await(5, TimeUnit.SECONDS)).as("all three batches sent: %s", delivered).isTrue();
        assertThat(armed.get()).as("the hook ran").isFalse();
        assertThat(maxConcurrent.get()).as("senders running at once").isEqualTo(1);
        assertThat(delivered).containsExactly(0L, 2L, 3L);
    }

    /// P4, condition 2 raced: the window's one-shot drains batch A on its own thread; while it hands A to the lane, a
    /// publisher thread publishes the successor's first event. The hand-over is under the accumulator lock, so the
    /// publisher waits for it and the lane holds A first. With the hand-over after the lock is released, the successor's
    /// event overtakes A.
    @Test
    void windowFlushRacingTheSuccessorsPublish_laneKeepsOffsetOrder() throws Exception {
        var windows = new ArrayDeque<Runnable>();
        ReplicationBatcher.FlushScheduler capturing = (flush, _) -> {
            windows.add(flush);

            return SharedScheduler.schedule(() -> {}, TimeSpan.timeSpan(1).hours());
        };
        var batcher = ReplicationBatcher.adaptiveReplicationBatcher((_, snapshot) -> delivered.add(snapshot.fromOffset()),
                                                                    100,
                                                                    WINDOW,
                                                                    capturing,
                                                                    laneRuns::add);

        batcher.add(STREAM, PARTITION, 0, new byte[4], 0, Epoch.ZERO);
        batcher.add(STREAM, PARTITION, 1, new byte[4], 1, Epoch.ZERO);

        var oneShotThread = new AtomicReference<Thread>();
        var publisher = new AtomicReference<Thread>();
        var queue = new HookedQueue() {
            @Override
            public boolean add(Object queued) {
                if (oneShotThread.compareAndSet(Thread.currentThread(), null)) {
                    publisher.set(Thread.ofPlatform().start(() -> batcher.add(STREAM, PARTITION, 2, new byte[4], 2, Epoch.ZERO)));
                    awaitParkedOrDone(publisher.get());
                }

                return super.add(queued);
            }
        };

        queue.addAll(replaceQueue(batcher, queue));

        var oneShot = Thread.ofPlatform().unstarted(windows.poll());

        oneShotThread.set(oneShot);
        oneShot.start();
        oneShot.join(TimeUnit.SECONDS.toMillis(5));
        // The publisher's hand-over of offset 2 (and its lane start) comes after its fresh accumulator is visible, so
        // the test waits for the publisher itself, not for the accumulator.
        joinOn(publisher.get());
        runLanes();

        assertThat(delivered).as("lane order").containsExactly(0L, 1L, 2L);
    }

    /// Overhaul 7.2 item 2: only an idle partition sends its next event alone. A partition that flushed within `maxDelay`
    /// opens an ordinary window for its next event, which waits for the window; past `maxDelay` the next event goes at once.
    @Test
    void partitionThatFlushedWithinMaxDelay_windowsItsNextEvent_anIdleOneSendsItAtOnce() {
        var now = new AtomicLong();
        var windows = new ArrayDeque<Runnable>();
        ReplicationBatcher.FlushScheduler capturing = (flush, _) -> {
            windows.add(flush);

            return SharedScheduler.schedule(() -> {}, TimeSpan.timeSpan(1).hours());
        };
        var batcher = ReplicationBatcher.adaptiveReplicationBatcher((_, snapshot) -> delivered.add(snapshot.fromOffset()),
                                                                    100,
                                                                    WINDOW,
                                                                    ReplicationBatcher.UNBUDGETED,
                                                                    capturing,
                                                                    laneRuns::add,
                                                                    now::get);

        batcher.add(STREAM, PARTITION, 0, new byte[4], 0, Epoch.ZERO);
        runLanes();

        assertThat(delivered).as("never flushed: idle, sent at once").containsExactly(0L);

        windows.poll().run();
        now.addAndGet(WINDOW.nanos() / 2);
        batcher.add(STREAM, PARTITION, 1, new byte[4], 1, Epoch.ZERO);
        runLanes();

        assertThat(delivered).as("flushed half a window ago: the event waits for its window").containsExactly(0L);

        windows.poll().run();
        runLanes();

        assertThat(delivered).containsExactly(0L, 1L);

        now.addAndGet(WINDOW.nanos() + 1);
        batcher.add(STREAM, PARTITION, 2, new byte[4], 2, Epoch.ZERO);
        runLanes();

        assertThat(delivered).as("flushed more than a window ago: idle again").containsExactly(0L, 1L, 2L);
    }

    private ReplicationBatcher budgeted(long budget) {
        return ReplicationBatcher.adaptiveReplicationBatcher((_, snapshot) -> delivered.add(snapshot.fromOffset()),
                                                             1,
                                                             WINDOW,
                                                             budget,
                                                             NEVER,
                                                             laneRuns::add);
    }

    /// The owner's publish as `StreamPartitionManager` makes it: reserve, hand the event over, release.
    private static boolean publish(ReplicationBatcher batcher, long offset, int size) {
        var reserved = batcher.reserve(size).isSuccess();

        if (reserved) {
            batcher.add(STREAM, PARTITION, offset, new byte[size], offset, Epoch.ZERO);
            batcher.release(size);
        }

        return reserved;
    }

    private void runLanes() {
        while (!laneRuns.isEmpty()) {
            laneRuns.poll().run();
        }
    }

    private void deliver(CountDownLatch sent, ReplicationBatcher.BatchSnapshot snapshot) {
        delivered.add(snapshot.fromOffset());
        sent.countDown();
    }

    private static void addRun(ReplicationBatcher batcher, long first, long last) {
        batcher.addRun(STREAM, PARTITION, first, List.of(new byte[4], new byte[4]).subList(0, (int) (last - first + 1)),
                       LongStream.rangeClosed(first, last).boxed().toList(), Epoch.ZERO);
    }

    private static void offerTwoRuns(ReplicationBatcher batcher) {
        addRun(batcher, 2, 2);
        addRun(batcher, 3, 3);
    }

    private static void firstRunsFirst(ReplicationBatcher.BatchSender sender,
                                       AtomicReference<Thread> firstRunner,
                                       PartitionKey key,
                                       ReplicationBatcher.BatchSnapshot snapshot) {
        if (snapshot.fromOffset() == 0L) {
            firstRunner.set(Thread.currentThread());
        }

        sender.deliver(key, snapshot);
    }

    /// The second runner's send of offset 2 holds until the first runner's thread has ended, so a first runner that does
    /// not leave would send offset 3 beside it.
    private static void awaitFirstRunnerIfSecond(ReplicationBatcher.BatchSnapshot snapshot,
                                                 AtomicReference<Thread> firstRunner,
                                                 CountDownLatch secondSending) {
        if (snapshot.fromOffset() == 2L) {
            secondSending.countDown();
            joinOn(firstRunner.get());
        }
    }

    /// Returns once `publisher` parks (on the accumulator lock) or ends.
    private static void awaitParkedOrDone(Thread publisher) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (publisher.isAlive() && publisher.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void joinOn(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitOn(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// Installs `hooked` as the partition's lane queue and returns the queue it replaced.
    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<Object> replaceQueue(ReplicationBatcher batcher, HookedQueue hooked) throws Exception {
        Method laneFor = ReplicationBatcher.class.getDeclaredMethod("laneFor", PartitionKey.class);

        laneFor.setAccessible(true);

        var lane = laneFor.invoke(batcher, PartitionKey.partitionKey(STREAM, PARTITION));
        Field queue = lane.getClass().getDeclaredField("queue");

        queue.setAccessible(true);

        var replaced = (ConcurrentLinkedQueue<Object>) queue.get(lane);

        queue.set(lane, hooked);

        return replaced;
    }

    /// The lane's queue with test hooks; its element type is the lane's private record, hence `Object`.
    private static class HookedQueue extends ConcurrentLinkedQueue<Object> {}
}
