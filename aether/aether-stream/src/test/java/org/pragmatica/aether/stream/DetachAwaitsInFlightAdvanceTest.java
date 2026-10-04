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

    private boolean awaitCommits(int count) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (commits.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        return commits.size() >= count;
    }
}
