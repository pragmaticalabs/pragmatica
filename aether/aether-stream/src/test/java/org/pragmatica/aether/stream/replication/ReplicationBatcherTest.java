// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationBatcher.replicationBatcher;


class ReplicationBatcherTest {

    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final NodeId REPLICA_A = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final int PARTITION = 0;
    private static final byte[] PAYLOAD = "test-event".getBytes();
    private static final long TIMESTAMP = 1000L;

    private ReplicaRegistry registry;
    private List<SentMessage> sentMessages;
    private ReplicationBatcher batcher;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        sentMessages = new ArrayList<>();
        registry.registerReplica(STREAM, PARTITION, REPLICA_A);
    }

    @Nested
    class SizeBasedFlush {

        @Test
        void add_flushesWhenMaxEventsReached() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 3, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);
            batcher.add(STREAM, PARTITION, 1L, "second".getBytes(), TIMESTAMP + 1, Epoch.ZERO);
            assertThat(sentMessages).isEmpty();

            batcher.add(STREAM, PARTITION, 2L, "third".getBytes(), TIMESTAMP + 2, Epoch.ZERO);
            assertThat(sentMessages).hasSize(1);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.fromOffset()).isEqualTo(0L);
            assertThat(message.payloads()).hasSize(3);
            assertThat(message.timestamps()).containsExactly(TIMESTAMP, TIMESTAMP + 1, TIMESTAMP + 2);

            batcher.close();
        }

        @Test
        void add_preservesPayloadOrder() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 2, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 5L, "first".getBytes(), 100L, Epoch.ZERO);
            batcher.add(STREAM, PARTITION, 6L, "second".getBytes(), 200L, Epoch.ZERO);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.fromOffset()).isEqualTo(5L);
            assertThat(new String(message.payloads().getFirst())).isEqualTo("first");
            assertThat(new String(message.payloads().getLast())).isEqualTo("second");

            batcher.close();
        }
    }

    @Nested
    class ManualFlush {

        @Test
        void flushAll_sendsAccumulatedEvents() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 100, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);
            batcher.add(STREAM, PARTITION, 1L, "second".getBytes(), TIMESTAMP + 1, Epoch.ZERO);
            assertThat(sentMessages).isEmpty();

            batcher.flushAll();
            assertThat(sentMessages).hasSize(1);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.payloads()).hasSize(2);

            batcher.close();
        }

        /// #1380 tangential: a drained batch's one-shot has nothing left to do; leaving it armed put 10,000 no-op
        /// timers on the process-wide scheduler for `maxDelay` after `flushAll_tenThousandPartitionsGoneIdle…`.
        @Test
        void flushAll_cancelsTheOneShotOfEveryBatchItDrains() {
            var scheduled = new ArrayList<ScheduledFuture<?>>();
            ReplicationBatcher.FlushScheduler capturing = (flush, delay) -> {
                scheduled.add(SharedScheduler.schedule(flush, delay));

                return scheduled.getLast();
            };

            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 100, TimeSpan.timeSpan(10).seconds(), capturing);
            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);
            assertThat(scheduled).hasSize(1);
            assertThat(scheduled.getFirst().isCancelled()).isFalse();

            batcher.flushAll();

            assertThat(scheduled.getFirst().isCancelled()).as("flushAll cancels the one-shot it made redundant").isTrue();
            assertThat(sentMessages).hasSize(1);

            batcher.close();
        }

        @Test
        void flushAll_noEventsAccumulated_sendsNothing() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 100, TimeSpan.timeSpan(10).seconds());

            batcher.flushAll();
            assertThat(sentMessages).isEmpty();

            batcher.close();
        }
    }

    @Nested
    class CloseTests {

        @Test
        void close_flushesRemainingEvents() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 100, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);
            assertThat(sentMessages).isEmpty();

            batcher.close();
            assertThat(sentMessages).hasSize(1);
        }
    }

    @Nested
    class MultiPartition {

        @Test
        void add_batchesPerPartitionIndependently() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 2, TimeSpan.timeSpan(10).seconds());

            registry.registerReplica(STREAM, 1, REPLICA_A);

            batcher.add(STREAM, 0, 0L, "p0-first".getBytes(), TIMESTAMP, Epoch.ZERO);
            batcher.add(STREAM, 1, 0L, "p1-first".getBytes(), TIMESTAMP, Epoch.ZERO);

            // Neither partition has reached threshold
            assertThat(sentMessages).isEmpty();

            // Partition 0 reaches threshold
            batcher.add(STREAM, 0, 1L, "p0-second".getBytes(), TIMESTAMP + 1, Epoch.ZERO);
            assertThat(sentMessages).hasSize(1);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.partition()).isEqualTo(0);
            assertThat(message.payloads()).hasSize(2);

            // Partition 1 still has one event pending
            batcher.close();
            assertThat(sentMessages).hasSize(2);

            var p1Message = (ReplicationMessage.ReplicateEvents) sentMessages.getLast().message();
            assertThat(p1Message.partition()).isEqualTo(1);
            assertThat(p1Message.payloads()).hasSize(1);
        }
    }

    @Nested
    class NoReplicas {

        @Test
        void add_noReplicas_dropsOnFlush() {
            var emptyRegistry = replicaRegistry();
            batcher = replicationBatcher(capturingTransport(), emptyRegistry, GOVERNOR, 2, TimeSpan.timeSpan(10).seconds());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);
            batcher.add(STREAM, PARTITION, 1L, PAYLOAD, TIMESTAMP + 1, Epoch.ZERO);

            // Size threshold hit, but no replicas — nothing sent
            assertThat(sentMessages).isEmpty();

            batcher.close();
        }
    }

    @Nested
    class TimeBasedFlush {

        @Test
        void scheduledFlush_sendsAccumulatedEvents() throws InterruptedException {
            var latch = new CountDownLatch(1);
            ReplicationTransport latchingTransport = (target, message) -> {
                sentMessages.add(new SentMessage(target, message));
                latch.countDown();
            };

            batcher = replicationBatcher(latchingTransport, registry, GOVERNOR, 1000, TimeSpan.timeSpan(50).millis());

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);

            // Wait for time-based flush (50ms interval + margin)
            assertThat(latch.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(sentMessages).hasSize(1);

            batcher.close();
        }

        /// #1246 review N2: pins the documented `maxDelay` bound. A lone event is flushed by its batch's
        /// one-shot at `maxDelay` — not immediately (lower bound) and not late (upper bound, with 3x margin
        /// for a loaded box; a 5x-delayed one-shot fails it).
        @Test
        void scheduledFlush_loneEvent_sentWithinMaxDelayBound() throws InterruptedException {
            var sentAt = new AtomicLong();
            var latch = new CountDownLatch(1);
            ReplicationTransport timingTransport = (_, _) -> {
                sentAt.set(System.nanoTime());
                latch.countDown();
            };

            batcher = replicationBatcher(timingTransport, registry, GOVERNOR, 1000, TimeSpan.timeSpan(200).millis());

            var addedAt = System.nanoTime();
            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

            var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(sentAt.get() - addedAt);

            assertThat(elapsedMillis).isBetween(150L, 600L);

            batcher.close();
        }
    }

    @Nested
    class BatchingReplicationManagerTests {

        @Test
        void batchingManager_delegatesToBatcher() {
            var manager = ReplicationManager.batchingReplicationManager(GOVERNOR, registry, capturingTransport(),
                                                                       2, TimeSpan.timeSpan(10).seconds());

            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);
            assertThat(sentMessages).isEmpty();

            manager.replicateEvent(STREAM, PARTITION, 1L, "second".getBytes(), TIMESTAMP + 1, Epoch.ZERO);
            assertThat(sentMessages).hasSize(1);

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.payloads()).hasSize(2);

            manager.close();
        }

        @Test
        void batchingManager_close_flushesPending() {
            var manager = ReplicationManager.batchingReplicationManager(GOVERNOR, registry, capturingTransport(),
                                                                       100, TimeSpan.timeSpan(10).seconds());

            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);
            assertThat(sentMessages).isEmpty();

            manager.close();
            assertThat(sentMessages).hasSize(1);
        }
    }

    /// #1246: an accumulator exists only while its partition has a pending batch. Before the fix every
    /// accumulator ever created stayed in the map and was locked on every 1 ms tick, idle or not.
    @Nested
    class AccumulatorEviction {
        private static final int IDLE_PARTITIONS = 10_000;

        @Test
        void flushAll_tenThousandPartitionsGoneIdle_accumulatorCountReturnsToZero() {
            batcher = replicationBatcher(countingTransport(new AtomicInteger()), registry, GOVERNOR, 1000,
                                         TimeSpan.timeSpan(10).seconds());

            addOneEventPerPartition();
            assertThat(batcher.accumulatorCount()).isEqualTo(IDLE_PARTITIONS);

            batcher.flushAll();

            // flushAll locks exactly the accumulators in the map, so an empty map means the next
            // flush over these 10k idle partitions performs zero lock acquisitions.
            assertThat(batcher.accumulatorCount()).isZero();

            batcher.close();
        }

        @Test
        void scheduledFlush_tenThousandPartitionsGoneIdle_evictsEveryAccumulator() throws InterruptedException {
            batcher = replicationBatcher(countingTransport(new AtomicInteger()), registry, GOVERNOR, 1000,
                                         TimeSpan.timeSpan(20).millis());

            addOneEventPerPartition();

            assertThat(awaitAccumulatorCount(0, 5_000)).isZero();

            batcher.close();
        }

        @Test
        void add_concurrentWithSizeAndTimerFlushes_losesNoEvent() throws InterruptedException {
            var delivered = new AtomicInteger();
            batcher = replicationBatcher(countingTransport(delivered), registry, GOVERNOR, 7,
                                         TimeSpan.timeSpan(1).millis());

            var threads = IntStream.range(0, 4)
                                   .mapToObj(_ -> Thread.ofVirtual().start(this::addBurst))
                                   .toList();

            for (var thread : threads) {
                thread.join();
            }

            batcher.close();

            assertThat(delivered.get()).isEqualTo(4 * 5_000);
            assertThat(batcher.accumulatorCount()).isZero();
        }

        private void addOneEventPerPartition() {
            IntStream.range(0, IDLE_PARTITIONS)
                     .forEach(partition -> batcher.add(STREAM, partition, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO));
        }

        private void addBurst() {
            LongStream.range(0, 5_000)
                      .forEach(offset -> batcher.add(STREAM, PARTITION, offset, PAYLOAD, TIMESTAMP, Epoch.ZERO));
        }

        private int awaitAccumulatorCount(int expected, long timeoutMillis) throws InterruptedException {
            var deadline = System.currentTimeMillis() + timeoutMillis;

            while (batcher.accumulatorCount() != expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            return batcher.accumulatorCount();
        }
    }

    /// #1246 review N3: identity, not count. Every event carries a unique id, and the delivered multiset must
    /// equal the added set exactly — a count-only check cannot see a loss cancelled by an equal resend.
    @Nested
    class DeliveryIdentity {
        private static final int THREADS = 4;
        private static final int EVENTS_PER_THREAD = 5_000;
        private static final int ROUNDS = 10;

        @Test
        void add_concurrentWithSizeAndTimerFlushes_deliversEveryIdExactlyOnce() throws InterruptedException {
            for (int round = 0; round < ROUNDS; round++) {
                assertEveryIdDeliveredOnce(round);
            }
        }

        private void assertEveryIdDeliveredOnce(int round) throws InterruptedException {
            var delivered = new ConcurrentLinkedQueue<String>();
            batcher = replicationBatcher(identityTransport(delivered), registry, GOVERNOR, 7, TimeSpan.timeSpan(1).millis());

            var threads = IntStream.range(0, THREADS)
                                   .mapToObj(thread -> Thread.ofVirtual().start(() -> addIdentified(thread)))
                                   .toList();

            for (var thread : threads) {
                thread.join();
            }

            batcher.close();

            var expected = IntStream.range(0, THREADS)
                                    .boxed()
                                    .flatMap(thread -> IntStream.range(0, EVENTS_PER_THREAD)
                                                                .mapToObj(i -> eventId(thread, i)))
                                    .collect(Collectors.toSet());

            assertThat(delivered).as("round %d: total deliveries", round).hasSize(THREADS * EVENTS_PER_THREAD);
            assertThat(Set.copyOf(delivered)).as("round %d: delivered ids", round).isEqualTo(expected);
        }

        private void addIdentified(int thread) {
            IntStream.range(0, EVENTS_PER_THREAD)
                     .forEach(i -> batcher.add(STREAM, PARTITION, i, eventId(thread, i).getBytes(), TIMESTAMP, Epoch.ZERO));
        }

        private static String eventId(int thread, int index) {
            return thread + "-" + index;
        }

        private static ReplicationTransport identityTransport(ConcurrentLinkedQueue<String> delivered) {
            return (_, message) -> ((ReplicationMessage.ReplicateEvents) message).payloads()
                                                                                 .forEach(payload -> delivered.add(new String(payload)));
        }
    }

    /// #1246 review N6: close() is a lifecycle end. At base it cancelled the flush timer, so nothing was sent
    /// after close; the one-shot design must not regress that — an add after close is refused, not sent.
    @Nested
    class AfterClose {

        @Test
        void addAfterClose_sendsNothing() throws InterruptedException {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 1000, TimeSpan.timeSpan(20).millis());
            batcher.close();

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);

            TimeUnit.MILLISECONDS.sleep(200);

            assertThat(sentMessages).isEmpty();
            assertThat(batcher.accumulatorCount()).isZero();
        }

        @Test
        void addAfterClose_isRefusedWithBatcherClosed() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 1000, TimeSpan.timeSpan(10).seconds());
            batcher.close();

            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO)
                   .onSuccess(_ -> fail("an add after close must be refused"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(ReplicationError.Lifecycle.BATCHER_CLOSED));
            assertThat(ReplicationError.Lifecycle.BATCHER_CLOSED.isTerminal()).isTrue();
        }

        @Test
        void close_cancelsPendingOneShot_andDrainsAcceptedEvents() {
            var scheduled = new ArrayList<ScheduledFuture<?>>();
            ReplicationBatcher.FlushScheduler capturing = (flush, delay) -> record(scheduled,
                                                                                   SharedScheduler.schedule(flush, delay));

            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 1000, TimeSpan.timeSpan(10).seconds(), capturing);
            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);

            assertThat(scheduled).hasSize(1);
            assertThat(scheduled.getFirst().isCancelled()).isFalse();

            batcher.close();

            assertThat(scheduled.getFirst().isCancelled()).as("close() must cancel the pending one-shot").isTrue();
            assertThat(sentMessages).as("close() drains the accepted event").hasSize(1);
        }

        private static ScheduledFuture<?> record(List<ScheduledFuture<?>> scheduled, ScheduledFuture<?> future) {
            scheduled.add(future);

            return future;
        }
    }

    /// #1380: deterministic interleaving of "a one-shot flush has drained its accumulator and is inside
    /// `transport.send` when `close()` runs". The one-shot body is captured through the `FlushScheduler` seam and
    /// run on a thread of the test's own; the transport counts down `entered` and then blocks on `gate`. The
    /// property under test is the barrier `add_concurrentWithSizeAndTimerFlushes_losesNoEvent` relies on: when
    /// `close()` returns, every event accepted before it was called has been handed to the transport.
    @Nested
    class CloseBarrier {
        private static final int EVENTS = 5;

        private final AtomicInteger delivered = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicReference<Runnable> capturedFlush = new AtomicReference<>();

        private final ReplicationBatcher.FlushScheduler capturing = (flush, _) -> {
            capturedFlush.set(flush);

            return SharedScheduler.schedule(() -> {}, TimeSpan.timeSpan(1).hours());
        };

        /// Blocks inside `send` until the test opens the gate.
        private final ReplicationTransport held = (_, message) -> {
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            delivered.addAndGet(((ReplicationMessage.ReplicateEvents) message).payloads().size());
        };

        @Test
        void close_oneShotFlushBetweenDrainAndSend_waitsForItBeforeReturning() throws InterruptedException {
            batcher = replicationBatcher(held, registry, GOVERNOR, 7, TimeSpan.timeSpan(1).millis(), capturing);

            var flush = startCapturedFlushAndAwaitEntered();
            var closer = startCloser();

            var returnedBeforeRelease = closer.returned().await(300, TimeUnit.MILLISECONDS);

            gate.countDown();
            flush.join();

            assertThat(closer.returned().await(2, TimeUnit.SECONDS))
                .as("close() returns promptly once the in-flight flush completes, not at the barrier bound")
                .isTrue();
            assertThat(closer.deliveredAtClose().get())
                .as("delivered as of close() returning — the pristine test's assertion at :323")
                .isEqualTo(EVENTS);
            assertThat(returnedBeforeRelease).as("close() did not return while a drained flush was in transport.send")
                                             .isFalse();
            assertThat(batcher.accumulatorCount()).isZero();
        }

        /// The bound: the transport never returns while `close()` waits. `close()` must give up at the bound and
        /// return — never hang — leaving the flush to complete on its own thread (late, not lost).
        @Test
        void close_inFlightFlushOutlivesTheBound_returnsAtTheBoundWithoutLosingTheBatch() throws InterruptedException {
            var bound = TimeSpan.timeSpan(200).millis();
            batcher = replicationBatcher(held, registry, GOVERNOR, 7, TimeSpan.timeSpan(1).millis(), bound, capturing);

            var flush = startCapturedFlushAndAwaitEntered();
            var closer = startCloser();

            assertThat(closer.returned().await(5, TimeUnit.SECONDS)).as("close() returned at the bound rather than hanging")
                                                                   .isTrue();
            assertThat(closer.elapsedNanos().get()).as("close() waited the full bound before giving up")
                                                   .isGreaterThanOrEqualTo(bound.nanos());
            assertThat(closer.deliveredAtClose().get()).as("the flush was still held when close() gave up").isZero();
            assertThat(batcher.inFlightFlushes()).as("close() gave up on a flush that is genuinely still in flight")
                                                 .isEqualTo(1);

            gate.countDown();
            flush.join();

            assertThat(delivered.get()).as("the abandoned wait did not abandon the batch").isEqualTo(EVENTS);
            assertThat(batcher.inFlightFlushes()).isZero();
        }

        /// An interrupted closer leaves the barrier at once with the flag intact (#914 shape: a parked wait that
        /// ignores the interrupt spins to the deadline instead).
        @Test
        void close_closerInterruptedWhileWaiting_returnsWithFlagSetWithoutLosingTheBatch() throws InterruptedException {
            batcher = replicationBatcher(held, registry, GOVERNOR, 7, TimeSpan.timeSpan(1).millis(), capturing);

            var flush = startCapturedFlushAndAwaitEntered();
            var closer = startCloser();

            assertThat(closer.returned().await(300, TimeUnit.MILLISECONDS)).as("close() is waiting on the barrier").isFalse();

            closer.thread().interrupt();

            assertThat(closer.returned().await(2, TimeUnit.SECONDS)).as("the interrupt ended the wait, well inside the 5 s default bound")
                                                                   .isTrue();
            assertThat(closer.interruptedAtReturn().get()).as("the interrupt flag is preserved for the caller").isTrue();
            assertThat(closer.deliveredAtClose().get()).isZero();

            gate.countDown();
            flush.join();

            assertThat(delivered.get()).isEqualTo(EVENTS);
        }

        private Thread startCapturedFlushAndAwaitEntered() throws InterruptedException {
            for (long offset = 0; offset < EVENTS; offset++) {
                batcher.add(STREAM, PARTITION, offset, PAYLOAD, TIMESTAMP, Epoch.ZERO);
            }
            assertThat(capturedFlush.get()).as("one-shot scheduled for the opened batch").isNotNull();

            var flush = Thread.ofVirtual().start(capturedFlush.get());

            assertThat(entered.await(5, TimeUnit.SECONDS)).as("flush drained and entered transport.send").isTrue();

            return flush;
        }

        private Closer startCloser() {
            var returned = new CountDownLatch(1);
            var deliveredAtClose = new AtomicInteger(-1);
            var elapsedNanos = new AtomicLong();
            var interruptedAtReturn = new AtomicBoolean();
            var thread = Thread.ofPlatform().start(() -> {
                var started = System.nanoTime();

                batcher.close();
                elapsedNanos.set(System.nanoTime() - started);
                interruptedAtReturn.set(Thread.currentThread().isInterrupted());
                deliveredAtClose.set(delivered.get());
                returned.countDown();
            });

            return new Closer(thread, returned, deliveredAtClose, elapsedNanos, interruptedAtReturn);
        }

        /// One `close()` on its own thread: when it returned, what `delivered` read at that instant, how long it
        /// took, and whether the thread's interrupt flag was still set.
        private record Closer(Thread thread,
                              CountDownLatch returned,
                              AtomicInteger deliveredAtClose,
                              AtomicLong elapsedNanos,
                              AtomicBoolean interruptedAtReturn) {}
    }

    /// #1246 review N4: deterministic interleaving of "drainer retired the accumulator but is descheduled
    /// before its `remove`". The test drains the live accumulator directly — retiring it without evicting — and
    /// then adds. The retry path must evict the retired accumulator itself and land the event on a fresh one;
    /// without that eviction every retry finds the same retired accumulator and recursion never ends.
    @Nested
    class RetryPath {

        @Test
        void add_onRetiredButNotEvictedAccumulator_evictsItAndAppendsToFreshOne() {
            batcher = replicationBatcher(capturingTransport(), registry, GOVERNOR, 1000, TimeSpan.timeSpan(10).seconds());
            batcher.add(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP, Epoch.ZERO);

            var stalled = batcher.accumulatorFor(STREAM, PARTITION)
                                 .fold(() -> fail("accumulator expected after add"), accumulator -> accumulator);

            stalled.drain();

            batcher.add(STREAM, PARTITION, 1L, "second".getBytes(), TIMESTAMP + 1, Epoch.ZERO)
                   .onFailure(cause -> fail(cause.message()));

            assertThat(batcher.accumulatorCount()).isEqualTo(1);
            assertThat(batcher.accumulatorFor(STREAM, PARTITION)
                              .map(fresh -> fresh != stalled)
                              .or(false)).as("the retired accumulator was replaced").isTrue();

            batcher.flushAll();

            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();

            assertThat(message.fromOffset()).isEqualTo(1L);
            assertThat(message.payloads()).hasSize(1);

            batcher.close();
        }
    }

    private ReplicationTransport countingTransport(AtomicInteger delivered) {
        return (_, message) -> delivered.addAndGet(((ReplicationMessage.ReplicateEvents) message).payloads().size());
    }

    private ReplicationTransport capturingTransport() {
        return (target, message) -> sentMessages.add(new SentMessage(target, message));
    }

    /// Captured transport message for test assertions.
    record SentMessage(NodeId target, ReplicationMessage message) {}
}
