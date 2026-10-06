// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1403: a graceful detach (unsubscribe, close) issued while a delivery is in flight commits the cursor AFTER that
/// delivery's advance — one past the event whose handler completed — so the event is not redelivered on reattach. The
/// wait is bounded by [ConsumerRuntimeState#DETACH_ADVANCE_BOUND]; at the bound the cursor is committed as it stands.
class DetachAwaitsInFlightAdvanceTest {
    private static final String STREAM = "orders";
    private static final String GROUP = "group-1";

    private StreamPartitionManager manager;
    private final List<Long> commits = new CopyOnWriteArrayList<>();
    private StreamConsumerRuntime runtime;

    private final ConsumerCursorStore store = new ConsumerCursorStore() {
        @Override
        public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
            commits.add(offset);

            return Promise.success(CommitOutcome.persisted());
        }

        @Override
        public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
            return Promise.success(commits.isEmpty()
                                   ? Option.none()
                                   : option(commits.getLast()));
        }
    };

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"));
        runtime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        manager.close();
    }

    /// The handler is running when the consumer is unsubscribed and completes inside the bound. Red under "the flush
    /// commits the cursor as it stands": that commit is the pre-delivery 0, and offset 0 is redelivered on reattach.
    @Test
    void unsubscribe_duringInFlightDelivery_commitsPastTheCompletedEvent_noRedelivery() throws InterruptedException {
        var handlerEntered = new CountDownLatch(1);
        var pending = Promise.<Unit>promise();
        var delivered = new CopyOnWriteArrayList<Long>();

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> {
            delivered.add(offset);
            handlerEntered.countDown();

            return pending;
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
        assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();

        runtime.unsubscribe(STREAM, 0, GROUP);
        pending.succeed(unit());

        assertThat(awaitCommits(1)).as("the detach flush lands").isTrue();
        assertThat(commits).as("the only commit is the detach flush, one past the completed event").containsExactly(1L);

        var redelivered = new CountDownLatch(1);

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> {
            delivered.add(offset);
            redelivered.countDown();

            return Promise.unitPromise();
        });
        manager.publishLocal(STREAM, 0, "event-1".getBytes(UTF_8), 2000L);
        assertThat(redelivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered).as("each offset delivered once across the graceful detach").containsExactly(0L, 1L);
    }

    /// The bound: a handler that does not complete holds the flush for [ConsumerRuntimeState#DETACH_ADVANCE_BOUND],
    /// then the cursor is committed as it stands (the event is redelivered, at-least-once). Red under "no barrier":
    /// the flush lands at once instead of waiting.
    @Test
    void unsubscribe_handlerNeverCompletes_flushesThePreDeliveryCursorAtTheBound() throws InterruptedException {
        var handlerEntered = new CountDownLatch(1);

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            handlerEntered.countDown();

            return Promise.promise();
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
        assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();

        var start = System.nanoTime();

        runtime.unsubscribe(STREAM, 0, GROUP);
        Thread.sleep(300);
        assertThat(commits).as("the flush waits for the in-flight delivery, up to the bound").isEmpty();
        assertThat(awaitCommits(1)).as("the flush lands at the bound, never hangs").isTrue();

        var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(commits).as("the pre-delivery cursor, as before the barrier").containsExactly(0L);
        assertThat(elapsedMs).as("held for the bound").isGreaterThanOrEqualTo(ConsumerRuntimeState.DETACH_ADVANCE_BOUND.millis());
    }

    /// `close()` has the same shape: its final flush waits for the in-flight advance inside the shutdown bound.
    @Test
    void close_duringInFlightDelivery_commitsPastTheCompletedEvent() throws InterruptedException {
        var handlerEntered = new CountDownLatch(1);
        var pending = Promise.<Unit>promise();

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            handlerEntered.countDown();

            return pending;
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
        assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        SharedScheduler.schedule(() -> pending.succeed(unit()), timeSpan(100).millis());

        runtime.close();

        assertThat(commits).as("close() returned after its final flush, one past the completed event").containsExactly(1L);
    }

    /// Sibling: a RETRY of the head event is in flight (the first attempt failed) when the consumer is unsubscribed.
    /// Red under "the retry holds no in-flight slot": the flush commits 0 and the retried event is redelivered.
    @Test
    void unsubscribe_duringInFlightRetry_commitsPastTheRetriedEvent() throws InterruptedException {
        var attempts = new AtomicInteger();
        var retryEntered = new CountDownLatch(1);
        var pending = Promise.<Unit>promise();

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            if (attempts.incrementAndGet() == 1) {
                return StreamError.General.BUFFER_EMPTY.promise();
            }
            retryEntered.countDown();

            return pending;
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
        assertThat(retryEntered.await(5, TimeUnit.SECONDS)).isTrue();

        runtime.unsubscribe(STREAM, 0, GROUP);
        pending.succeed(unit());

        assertThat(awaitCommits(1)).isTrue();
        assertThat(commits).as("one past the event whose retry completed").containsExactly(1L);
    }

    /// Sibling: a SKIP strategy's dead-letter append is in flight when the consumer is unsubscribed; the cursor moves
    /// past the event only once the sink has stored it. Red under "the append holds no in-flight slot": the flush
    /// commits 0 and the dead-lettered event is delivered (and dead-lettered) again on reattach.
    @Test
    void unsubscribe_duringInFlightDeadLetterAppend_commitsPastTheDeadLetteredEvent() throws InterruptedException {
        var appendEntered = new CountDownLatch(1);
        var pending = Promise.<Unit>promise();
        var sink = new DeadLetterHandler() {
            @Override
            public Promise<Unit> append(String streamName,
                                        int partition,
                                        long offset,
                                        String failingGroup,
                                        byte[] payload,
                                        String errorMessage,
                                        int attemptCount) {
                appendEntered.countDown();

                return pending;
            }

            @Override
            public List<DeadLetterEntry> read(String streamName, int maxCount) {
                return List.of();
            }
        };
        var skipping = streamConsumerRuntime(manager, sink, store);

        try {
            skipping.subscribe(STREAM,
                               0,
                               ConsumerConfig.consumerConfig(GROUP,
                                                             1,
                                                             ConsumerConfig.ProcessingMode.ORDERED,
                                                             ConsumerConfig.ErrorStrategy.SKIP),
                               (_, _, _) -> StreamError.General.BUFFER_EMPTY.promise());
            manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
            assertThat(appendEntered.await(5, TimeUnit.SECONDS)).isTrue();

            skipping.unsubscribe(STREAM, 0, GROUP);
            // v-str-1914: settled only after the flush has certainly chained behind the append — settled at once, the
            // test thread advanced the cursor before the asynchronously dispatched flush ran, and passed without the slot.
            Thread.sleep(300);
            pending.succeed(unit());

            assertThat(awaitCommits(1)).isTrue();
            assertThat(commits).as("one past the event the sink stored").containsExactly(1L);
        } finally {
            skipping.close();
        }
    }

    /// v-str-1914 probe D, adopted: the dead-letter append reached from the RETRY path (outside the delivery slot), the
    /// sink settled 300 ms after the unsubscribe. Red under "the dead-letter append holds no in-flight slot":
    /// `commits=[0]`.
    @Test
    void unsubscribe_duringDeadLetterAppendAfterRetriesExhausted_commitsPastTheStoredEvent() throws InterruptedException {
        var appendEntered = new CountDownLatch(1);
        var pending = Promise.<Unit>promise();
        var sink = new DeadLetterHandler() {
            @Override
            public Promise<Unit> append(String streamName,
                                        int partition,
                                        long offset,
                                        String failingGroup,
                                        byte[] payload,
                                        String errorMessage,
                                        int attemptCount) {
                appendEntered.countDown();

                return pending;
            }

            @Override
            public List<DeadLetterEntry> read(String streamName, int maxCount) {
                return List.of();
            }
        };
        var retrying = streamConsumerRuntime(manager, sink, store);

        try {
            retrying.subscribe(STREAM,
                               0,
                               ConsumerConfig.consumerConfig(GROUP,
                                                             1,
                                                             ConsumerConfig.ProcessingMode.ORDERED,
                                                             ConsumerConfig.ErrorStrategy.RETRY,
                                                             1000L,
                                                             1,
                                                             "dlq"),
                               (_, _, _) -> StreamError.General.BUFFER_EMPTY.promise());
            manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
            assertThat(appendEntered.await(10, TimeUnit.SECONDS)).isTrue();

            retrying.unsubscribe(STREAM, 0, GROUP);
            Thread.sleep(300);
            pending.succeed(unit());

            assertThat(awaitCommits(1)).isTrue();
            assertThat(commits).as("one past the event the sink stored").containsExactly(1L);
        } finally {
            retrying.close();
        }
    }

    /// v-str-1914 probe R, adopted (F1): a slow handler holds offset 0; the group is unsubscribed and at once
    /// re-subscribed on this node, and the new consumer checkpoints far ahead; then the old handler completes. The held
    /// flush must not land after the successor's commits. Red under "the successor's fetch does not wait for the held
    /// flush": commits `[1000, 1]`, the group rewound.
    @Test
    void resubscribeDuringHeldFlush_committedCursorNeverMovesBackwards() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var pending = Promise.<Unit>promise();
        var delivered = new java.util.concurrent.atomic.AtomicLong(-1);

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            entered.countDown();

            return pending;
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.unsubscribe(STREAM, 0, GROUP);
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> {
            delivered.set(offset);

            return Promise.unitPromise();
        });
        for (var i = 1; i <= 1500; i++) {
            manager.publishLocal(STREAM, 0, ("event-" + i).getBytes(UTF_8), 1000L + i);
        }
        Thread.sleep(200);
        pending.succeed(unit());

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (delivered.get() < 1500 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(300);

        var highest = commits.stream().mapToLong(Long::longValue).max().orElse(-1);

        assertThat(delivered.get()).as("the successor delivered the backlog").isEqualTo(1500L);
        assertThat(commits.getFirst()).as("the old consumer's flush lands first, one past its completed event").isEqualTo(1L);
        assertThat(commits.getLast()).as("no commit below an earlier one: %s", commits).isEqualTo(highest);
    }

    /// v-str-1914 probe R2 (F1b): A is held by a slow handler; B subscribes and unsubscribes before its fetch (its own
    /// flush settles at once); C then subscribes and checkpoints far ahead; then A's handler completes. B's settled
    /// flush must not release C past A's still-held one. Red under "the pending flush is REPLACED, not chained":
    /// commits `[1000, 1]`, the group rewound.
    @Test
    void churnedSubscriptionDuringHeldFlush_committedCursorNeverMovesBackwards() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var pending = Promise.<Unit>promise();
        var delivered = new java.util.concurrent.atomic.AtomicLong(-1);

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            entered.countDown();

            return pending;
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.unsubscribe(STREAM, 0, GROUP);
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> Promise.unitPromise());
        runtime.unsubscribe(STREAM, 0, GROUP);
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> {
            delivered.set(offset);

            return Promise.unitPromise();
        });
        for (var i = 1; i <= 1500; i++) {
            manager.publishLocal(STREAM, 0, ("event-" + i).getBytes(UTF_8), 1000L + i);
        }
        Thread.sleep(200);
        pending.succeed(unit());

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (delivered.get() < 1500 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(300);

        var highest = commits.stream().mapToLong(Long::longValue).max().orElse(-1);

        assertThat(delivered.get()).as("the last successor delivered the backlog").isEqualTo(1500L);
        assertThat(commits.getFirst()).as("A's flush lands first, one past its completed event: %s", commits).isEqualTo(1L);
        assertThat(commits.getLast()).as("no commit below an earlier one: %s", commits).isEqualTo(highest);
    }

    /// The bound survives chaining: a handler that never returns, then three same-key detach/re-subscribe rounds. The
    /// held flush still settles at [ConsumerRuntimeState#DETACH_ADVANCE_BOUND] and the final successor starts after it
    /// — not before (it would rewind), and not after N bounds (the chained flushes wait concurrently, not in series).
    /// The upper limit carries 2 s of scheduling slack; [unverified: under load beyond that slack].
    @Test
    void chainedDetachesWithNeverReturningHandler_successorStartsAfterOneBound() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var successorDelivered = new CountDownLatch(1);

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            entered.countDown();

            return Promise.promise();
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        var start = System.nanoTime();

        runtime.unsubscribe(STREAM, 0, GROUP);
        for (var round = 0; round < 2; round++) {
            runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> Promise.unitPromise());
            runtime.unsubscribe(STREAM, 0, GROUP);
        }
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            successorDelivered.countDown();

            return Promise.unitPromise();
        });
        assertThat(successorDelivered.await(8, TimeUnit.SECONDS)).isTrue();

        var elapsedMs = (System.nanoTime() - start) / 1_000_000;
        var boundMs = ConsumerRuntimeState.DETACH_ADVANCE_BOUND.millis();

        assertThat(elapsedMs).as("the successor waited out the held flush").isGreaterThanOrEqualTo(boundMs);
        assertThat(elapsedMs).as("chained flushes wait concurrently").isLessThan(boundMs + 2000);
        assertThat(commits).as("the held flush committed the pre-delivery cursor at the bound").startsWith(0L);
    }

    /// v-str-1914 N1: 50,000 same-key detach/re-subscribe rounds pile up behind one held flush. Resolving a nested
    /// chain of gates recursed once per link and overflowed the stack at about 20,000 links (logged only as "Throwable
    /// escaped"), so the successor never started. Red under "the gates are a nested chain": the final successor does
    /// not deliver after the bound.
    @Test
    void deepChurnBehindOneHeldFlush_finalSuccessorStartsAfterTheBound() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var successorDelivered = new CountDownLatch(1);

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            entered.countDown();

            return Promise.promise();
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.unsubscribe(STREAM, 0, GROUP);
        for (var round = 0; round < 50_000; round++) {
            runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> Promise.unitPromise());
            runtime.unsubscribe(STREAM, 0, GROUP);
        }
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            successorDelivered.countDown();

            return Promise.unitPromise();
        });
        assertThat(successorDelivered.await(15, TimeUnit.SECONDS)).as("the final successor started").isTrue();
        assertThat(commits).as("the held flush committed the pre-delivery cursor at the bound").startsWith(0L);
    }

    /// v-str-1914 UU: unsubscribes of an already-unsubscribed key (NOT_FOUND) while A's flush is held must release their
    /// own count and nothing more. Red under "the NOT_FOUND path never releases": the count never reaches zero, so the
    /// successor stays gated for good and delivers nothing.
    @Test
    void repeatedUnsubscribeOfUnknownKeyDuringHeldFlush_successorStillStartsAndCursorNeverMovesBackwards() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var pending = Promise.<Unit>promise();
        var delivered = new java.util.concurrent.atomic.AtomicLong(-1);

        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            entered.countDown();

            return pending;
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.unsubscribe(STREAM, 0, GROUP);
        assertThat(runtime.unsubscribe(STREAM, 0, GROUP).isFailure()).isTrue();
        assertThat(runtime.unsubscribe(STREAM, 0, GROUP).isFailure()).isTrue();
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> {
            delivered.set(offset);

            return Promise.unitPromise();
        });
        for (var i = 1; i <= 1500; i++) {
            manager.publishLocal(STREAM, 0, ("event-" + i).getBytes(UTF_8), 1000L + i);
        }
        Thread.sleep(200);
        pending.succeed(unit());

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (delivered.get() < 1500 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(300);

        var highest = commits.stream().mapToLong(Long::longValue).max().orElse(-1);

        assertThat(delivered.get()).as("the successor started and delivered the backlog; commits=%s", commits).isEqualTo(1500L);
        assertThat(commits.getFirst()).as("A's flush lands first: %s", commits).isEqualTo(1L);
        assertThat(commits.getLast()).as("no commit below an earlier one: %s", commits).isEqualTo(highest);
    }

    /// v-str-1914 NF: an unsubscribe of an unknown key with nothing pending leaves no pending entry behind. Observed
    /// through behaviour, not the private map: a leaked count would gate the next subscription of that key forever.
    /// Red under "the NOT_FOUND path never releases": the subscription below never delivers.
    @Test
    void unsubscribeOfUnknownKey_leavesNoPendingDetach_nextSubscriptionDelivers() throws InterruptedException {
        var delivered = new CountDownLatch(1);

        assertThat(runtime.unsubscribe(STREAM, 0, GROUP).isFailure()).isTrue();
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> {
            delivered.countDown();

            return Promise.unitPromise();
        });
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);

        assertThat(delivered.await(5, TimeUnit.SECONDS)).as("the key is not gated by a leaked pending detach").isTrue();
    }

    /// v-str-1914 second window: the old consumer leaves the registry before its flush is registered, and a store whose
    /// commit blocks the CALLER keeps the flush from being registered at all. A same-key subscription on another thread
    /// in that gap must still wait for the flush. Red under "the gate is registered after the consumer is removed": the
    /// successor's fetch runs while the commit is still blocked.
    @Test
    void resubscribeWhileDetachFlushBlocksItsCaller_fetchWaitsForTheCommit() throws InterruptedException {
        var commitEntered = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var fetches = new AtomicInteger();
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        var fetchedBeforeCommit = new java.util.concurrent.atomic.AtomicBoolean();
        var successorFetched = new CountDownLatch(1);
        var blocking = new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
                commitEntered.countDown();
                try {
                    releaseCommit.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                committed.set(true);

                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                if (fetches.incrementAndGet() == 2) {
                    fetchedBeforeCommit.set(!committed.get());
                    successorFetched.countDown();
                }

                return Promise.success(Option.none());
            }
        };
        var blockingRuntime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), blocking);

        try {
            blockingRuntime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> Promise.unitPromise());
            assertThat(fetches.get()).as("the first consumer fetched its cursor").isEqualTo(1);

            var detacher = new Thread(() -> blockingRuntime.unsubscribe(STREAM, 0, GROUP));

            detacher.start();
            assertThat(commitEntered.await(5, TimeUnit.SECONDS)).as("the detach flush is blocked in the store").isTrue();
            blockingRuntime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> Promise.unitPromise());
            assertThat(fetches.get()).as("the successor has not fetched while the old flush is uncommitted").isEqualTo(1);

            releaseCommit.countDown();
            assertThat(successorFetched.await(5, TimeUnit.SECONDS)).as("the successor fetches once the flush settles").isTrue();
            assertThat(fetchedBeforeCommit.get()).isFalse();
            detacher.join(5000);
        } finally {
            releaseCommit.countDown();
            blockingRuntime.close();
        }
    }

    private boolean awaitCommits(int count) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (commits.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        return commits.size() >= count;
    }
}
