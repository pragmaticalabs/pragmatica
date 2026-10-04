// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.none;

/// #1367: a [VirtualMachineError] thrown synchronously out of the delivery pass (since #1311 `Result.lift` rethrows
/// it) must not leave the consumer's loop marked running. The reader's FIRST read throws; the event behind it must
/// still be delivered by a later pass. Red under "no release": the first pass escapes with `running` set, every
/// later poll tick only marks the loop dirty, and nothing is ever delivered.
class DrainPassVirtualMachineErrorTest {
    private StreamPartitionManager manager;
    private StreamConsumerRuntime runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        manager.close();
    }

    /// The recoverable VME: a failed pass, retried after the poll backoff.
    @Test
    void stackOverflowOutOfThePass_releasesTheLoop_andTheNextPassDelivers() throws InterruptedException {
        assertNextPassDelivers(StackOverflowError::new);
    }

    /// Any other VME propagates (the JVM is failing), but the loop is released first, so the consumer is not wedged.
    @Test
    void otherVirtualMachineErrorOutOfThePass_releasesTheLoopBeforePropagating() throws InterruptedException {
        assertNextPassDelivers(() -> new InternalError("injected by DrainPassVirtualMachineErrorTest"));
    }

    /// Sibling: a handler that throws [StackOverflowError] on a RETRY used to escape the scheduled retry with the
    /// retry hold set, wedging the consumer. Now it is a failed attempt: the next retry delivers. Red under "the
    /// handler's overflow escapes": the event is never delivered.
    @Test
    void handlerOverflowOnRetry_isAFailedAttempt_notAWedgedHold() throws InterruptedException {
        var calls = new AtomicInteger();
        var delivered = new CountDownLatch(1);

        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager);
        manager.createStream(StreamConfig.streamConfig("orders", 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000), "earliest"));
        runtime.subscribe("orders", 0, ConsumerConfig.consumerConfig("group-1"), (_, _, _) -> {
            return switch (calls.incrementAndGet()) {
                case 1 -> StreamError.General.BUFFER_EMPTY.promise();
                case 2 -> throw new StackOverflowError();
                default -> {
                    delivered.countDown();

                    yield Promise.unitPromise();
                }
            };
        });
        manager.publishLocal("orders", 0, "e-0".getBytes(UTF_8), 1L);

        assertThat(delivered.await(5, TimeUnit.SECONDS)).as("the retry after the overflowing one delivered").isTrue();
    }

    /// Sibling: under SKIP, a handler that overflows its stack is a failed delivery, so the event is dead-lettered and
    /// skipped. Red under "the overflow escapes the pass": it is retried as a failed pass forever and never reaches
    /// the error strategy.
    @Test
    void handlerOverflowUnderSkip_isDeadLettered() throws InterruptedException {
        var deadLettered = new CountDownLatch(1);
        var sink = new DeadLetterHandler() {
            @Override
            public Promise<org.pragmatica.lang.Unit> append(String streamName,
                                                            int partition,
                                                            long offset,
                                                            String failingGroup,
                                                            byte[] payload,
                                                            String errorMessage,
                                                            int attemptCount) {
                deadLettered.countDown();

                return Promise.unitPromise();
            }

            @Override
            public List<DeadLetterEntry> read(String streamName, int maxCount) {
                return List.of();
            }
        };

        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager, sink);
        manager.createStream(StreamConfig.streamConfig("orders", 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000), "earliest"));
        runtime.subscribe("orders",
                          0,
                          ConsumerConfig.consumerConfig("group-1", 1, ConsumerConfig.ProcessingMode.ORDERED, ConsumerConfig.ErrorStrategy.SKIP),
                          (_, _, _) -> {
                              throw new StackOverflowError();
                          });
        manager.publishLocal("orders", 0, "e-0".getBytes(UTF_8), 1L);

        assertThat(deadLettered.await(5, TimeUnit.SECONDS)).as("SKIP dead-lettered the overflowing event").isTrue();
    }

    /// Sibling: a dead-letter sink that overflows its stack used to escape with the dead-letter hold set, wedging the
    /// consumer. Now it is a failed append, retried with backoff; the retry stores the entry and the cursor moves on.
    /// Red under "the overflow escapes the call": no second append is ever attempted.
    @Test
    void deadLetterSinkOverflow_isAFailedAppend_retried_notAWedgedHold() throws InterruptedException {
        var appends = new AtomicInteger();
        var stored = new CountDownLatch(1);
        var sink = new DeadLetterHandler() {
            @Override
            public Promise<org.pragmatica.lang.Unit> append(String streamName,
                                                            int partition,
                                                            long offset,
                                                            String failingGroup,
                                                            byte[] payload,
                                                            String errorMessage,
                                                            int attemptCount) {
                if (appends.incrementAndGet() == 1) {
                    throw new StackOverflowError();
                }
                stored.countDown();

                return Promise.unitPromise();
            }

            @Override
            public List<DeadLetterEntry> read(String streamName, int maxCount) {
                return List.of();
            }
        };

        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager, sink);
        manager.createStream(StreamConfig.streamConfig("orders", 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000), "earliest"));
        runtime.subscribe("orders",
                          0,
                          ConsumerConfig.consumerConfig("group-1", 1, ConsumerConfig.ProcessingMode.ORDERED, ConsumerConfig.ErrorStrategy.SKIP),
                          (_, _, _) -> StreamError.General.BUFFER_EMPTY.promise());
        manager.publishLocal("orders", 0, "e-0".getBytes(UTF_8), 1L);

        assertThat(stored.await(5, TimeUnit.SECONDS)).as("the append after the overflowing one stored the entry").isTrue();
        StreamConsumerRuntimeTest.awaitCursorAt(runtime, "orders", 0, "group-1", 1L, 5_000);
        assertThat(runtime.cursorPosition("orders", 0, "group-1").or(-1L)).as("the cursor moved past the dead-lettered event").isEqualTo(1L);
    }

    private void assertNextPassDelivers(Supplier<VirtualMachineError> error) throws InterruptedException {
        var reads = new AtomicInteger();
        var delivered = new CountDownLatch(1);

        runtime = new ConsumerRuntimeState(manager,
                                           DeadLetterHandler.deadLetterHandler(),
                                           none(),
                                           none(),
                                           (_, _, fromOffset, _) -> {
                                               if (reads.incrementAndGet() == 1) {
                                                   throw error.get();
                                               }

                                               return Promise.success(fromOffset == 0
                                                                      ? List.of(new OffHeapRingBuffer.RawEvent(0, "e-0".getBytes(UTF_8), 0L))
                                                                      : List.of());
                                           });
        runtime.subscribe("not-local", 0, ConsumerConfig.consumerConfig("group-1"), (_, _, _) -> {
            delivered.countDown();

            return Promise.unitPromise();
        });

        assertThat(delivered.await(5, TimeUnit.SECONDS)).as("a pass after the one that threw delivered the event").isTrue();
        assertThat(reads.get()).as("the pass that threw, then at least one more").isGreaterThanOrEqualTo(2);
    }
}
