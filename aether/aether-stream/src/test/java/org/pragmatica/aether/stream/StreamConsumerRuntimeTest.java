// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.DeadLetterHandler.DeadLetterEntry;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.assertj.core.api.Assertions.assertThat;


class StreamConsumerRuntimeTest {
    private StreamPartitionManager manager;
    private StreamConsumerRuntime runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        runtime = streamConsumerRuntime(manager);
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
        manager.close();
    }

    private void createTestStream(String name) {
        var retention = RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000);

        manager.createStream(StreamConfig.streamConfig(name, 4, retention, "earliest"));
    }

    @Nested
    class Subscribe {
        @Test
        void subscribe_success_validStream() {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders",
                              0,
                              config,
                              (offset, payload, ts) -> Promise.unitPromise())
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
        }

        @Test
        void subscribe_failure_duplicateSubscription() {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");
            StreamConsumerRuntime.ConsumerCallback noop = (offset, payload, ts) -> Promise.unitPromise();

            runtime.subscribe("orders", 0, config, noop);
            runtime.subscribe("orders", 0, config, noop)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSUMER_ALREADY_SUBSCRIBED));
        }

        @Test
        void subscribe_callbackInvoked_afterPublish() throws InterruptedException {
            createTestStream("orders");
            var received = new CopyOnWriteArrayList<Long>();
            var latch = new CountDownLatch(2);
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders",
                              0,
                              config,
                              (offset, payload, ts) -> {
                                  received.add(offset);
                                  latch.countDown();

                                  return Promise.unitPromise();
                              });
            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "event-2".getBytes(), 2000L);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactly(0L, 1L);
        }

        @Test
        void subscribe_cursorAdvances_afterSuccessfulDelivery() throws InterruptedException {
            createTestStream("orders");
            var latch = new CountDownLatch(1);
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders",
                              0,
                              config,
                              (offset, payload, ts) -> {
                                  latch.countDown();

                                  return Promise.unitPromise();
                              });
            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            // Allow a poll cycle for cursor update
            Thread.sleep(150);
            var cursor = runtime.cursorPosition("orders", 0, "group-1");

            assertThat(cursor.isPresent()).isTrue();
            cursor.onPresent(pos -> assertThat(pos).isGreaterThanOrEqualTo(1L));
        }
    }

    @Nested
    class Unsubscribe {
        @Test
        void unsubscribe_success_existingConsumer() {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> Promise.unitPromise());
            runtime.unsubscribe("orders", 0, "group-1")
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
        }

        @Test
        void unsubscribe_failure_nonExistent() {
            runtime.unsubscribe("orders", 0, "group-1")
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSUMER_NOT_FOUND));
        }

        @Test
        void unsubscribe_stopsDelivery_afterUnsubscribe() throws InterruptedException {
            createTestStream("orders");
            var callCount = new AtomicInteger(0);
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders",
                              0,
                              config,
                              (offset, payload, ts) -> {
                                  callCount.incrementAndGet();

                                  return Promise.unitPromise();
                              });
            // Publish, wait for delivery, then unsubscribe
            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L);
            Thread.sleep(300);
            runtime.unsubscribe("orders", 0, "group-1");
            var countAfterUnsub = callCount.get();
            // Publish more events — should not be delivered
            manager.publishLocal("orders", 0, "event-2".getBytes(), 2000L);
            Thread.sleep(300);
            assertThat(callCount.get()).isEqualTo(countAfterUnsub);
        }
    }

    @Nested
    class CursorPosition {
        @Test
        void cursorPosition_none_noSubscription() {
            var cursor = runtime.cursorPosition("orders", 0, "group-1");

            assertThat(cursor.isEmpty()).isTrue();
        }

        @Test
        void cursorPosition_present_afterSubscribe() {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> Promise.unitPromise());
            var cursor = runtime.cursorPosition("orders", 0, "group-1");

            assertThat(cursor.isPresent()).isTrue();
            cursor.onPresent(pos -> assertThat(pos).isEqualTo(0L));
        }
    }

    @Nested
    class RetryStrategy {
        @Test
        void retry_redeliversOnFailure_thenSucceeds() throws InterruptedException {
            createTestStream("orders");
            var attempts = new AtomicInteger(0);
            var latch = new CountDownLatch(1);
            var config = ConsumerConfig.consumerConfig("group-1", 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY);

            runtime.subscribe("orders",
                              0,
                              config,
                              (offset, payload, ts) -> {
                                  if (attempts.incrementAndGet() < 3) {
                                  return StreamError.General.BUFFER_EMPTY.promise();
                              }

                                  latch.countDown();

                                  return Promise.unitPromise();
                              });
            manager.publishLocal("orders", 0, "event".getBytes(), 1000L);
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
        }

        @Test
        void retry_sendsToDeadLetter_afterMaxRetries() throws InterruptedException {
            createTestStream("orders");
            var latch = new CountDownLatch(1);
            var config = ConsumerConfig.consumerConfig("group-1", 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY);

            runtime.subscribe("orders",
                              0,
                              config,
                              (offset, payload, ts) -> {
                                  latch.countDown();

                                  return StreamError.General.BUFFER_EMPTY.promise();
                              });
            manager.publishLocal("orders", 0, "event".getBytes(), 1000L);
            // Wait for retries to exhaust (5 retries with backoff)
            Thread.sleep(5000);
            var dlEntries = runtime.deadLetterHandler().read("orders", 10);

            assertThat(dlEntries).isNotEmpty();
            assertThat(dlEntries.getFirst().offset()).isEqualTo(0L);
        }
    }

    @Nested
    class SkipStrategy {
        @Test
        void skip_advancesCursor_onFailure() throws InterruptedException {
            createTestStream("orders");
            var deliveredOffsets = new CopyOnWriteArrayList<Long>();
            var latch = new CountDownLatch(2);
            var config = ConsumerConfig.consumerConfig("group-1", 1, ProcessingMode.ORDERED, ErrorStrategy.SKIP);

            runtime.subscribe("orders",
                              0,
                              config,
                              (offset, payload, ts) -> {
                                  deliveredOffsets.add(offset);
                                  latch.countDown();
                                  if (offset == 0L) {
                                  return StreamError.General.BUFFER_EMPTY.promise();
                              }

                                  return Promise.unitPromise();
                              });
            manager.publishLocal("orders", 0, "fail-event".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "ok-event".getBytes(), 2000L);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            // First event was skipped, second delivered
            assertThat(deliveredOffsets).contains(0L, 1L);
            // Dead letter should have the skipped event
            var dlEntries = runtime.deadLetterHandler().read("orders", 10);

            assertThat(dlEntries).isNotEmpty();
            assertThat(dlEntries.getFirst().offset()).isEqualTo(0L);
        }
    }

    @Nested
    class DeadLetterAppendContract {
        /// Pins durable-pubsub-spec §9's no-silent-loss property at the runtime seam: retries
        /// exhausted -> the cursor does NOT advance past the event until the dead-letter sink has
        /// accepted it, the partition's delivery loop is held meanwhile (no re-delivery to the
        /// handler from the un-advanced cursor), the append retries until the sink recovers, and
        /// recovery yields exactly ONE dead-letter entry and a resumed loop.
        @Test
        void retryExhaustion_holdsCursorAndLoop_untilSinkAccepts() throws Exception {
            createTestStream("orders");
            var sink = new FlakyDeadLetterSink(2);
            var flakyRuntime = streamConsumerRuntime(manager, sink);

            try {
                var deliveredOffsets = new CopyOnWriteArrayList<Long>();
                var tailLatch = new CountDownLatch(2);
                var config = ConsumerConfig.consumerConfig("group-dlq",
                                                           1,
                                                           ProcessingMode.ORDERED,
                                                           ErrorStrategy.RETRY,
                                                           1000L,
                                                           1,
                                                           "");

                flakyRuntime.subscribe("orders",
                                       0,
                                       config,
                                       (offset, payload, ts) -> {
                                           deliveredOffsets.add(offset);
                                           if (offset == 0L) {
                                           return StreamError.General.BUFFER_EMPTY.promise();
                                       }

                                           tailLatch.countDown();

                                           return Promise.unitPromise();
                                       });
                manager.publishLocal("orders", 0, "poison".getBytes(UTF_8), 1000L);
                // Two failed append attempts prove the retry-with-backoff loop runs.
                assertThat(sink.failedAttempts.await(5, TimeUnit.SECONDS)).isTrue();
                // A new append would normally trigger delivery; the in-flight guard must hold the
                // loop, so the poison event is NOT re-delivered from the un-advanced cursor.
                manager.publishLocal("orders", 0, "next-1".getBytes(UTF_8), 2000L);
                Thread.sleep(300);
                assertThat(deliveredOffsets).containsExactly(0L);
                assertThat(flakyRuntime.cursorPosition("orders", 0, "group-dlq").or(-1L)).isEqualTo(0L);
                // Sink recovers -> pending append retry succeeds, cursor advances, loop resumes on
                // the next append notification.
                sink.recover();
                manager.publishLocal("orders", 0, "next-2".getBytes(UTF_8), 3000L);
                assertThat(tailLatch.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(deliveredOffsets).containsExactly(0L, 1L, 2L);
                // Exactly one entry: failed append attempts must not mint duplicates.
                var dlEntries = flakyRuntime.deadLetterHandler().read("orders", 10);

                assertThat(dlEntries).hasSize(1);
                assertThat(dlEntries.getFirst().offset()).isEqualTo(0L);
            } finally {
                flakyRuntime.close();
            }
        }

        @Test
        void skip_holdsCursor_untilSinkAccepts() throws Exception {
            createTestStream("orders");
            var sink = new FlakyDeadLetterSink(1);
            var flakyRuntime = streamConsumerRuntime(manager, sink);

            try {
                var deliveredOffsets = new CopyOnWriteArrayList<Long>();
                var tailLatch = new CountDownLatch(1);
                var config = ConsumerConfig.consumerConfig("group-skip", 1, ProcessingMode.ORDERED, ErrorStrategy.SKIP);

                flakyRuntime.subscribe("orders",
                                       0,
                                       config,
                                       (offset, payload, ts) -> {
                                           deliveredOffsets.add(offset);
                                           if (offset == 0L) {
                                           return StreamError.General.BUFFER_EMPTY.promise();
                                       }

                                           tailLatch.countDown();

                                           return Promise.unitPromise();
                                       });
                manager.publishLocal("orders", 0, "poison".getBytes(UTF_8), 1000L);
                assertThat(sink.failedAttempts.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(flakyRuntime.cursorPosition("orders", 0, "group-skip").or(-1L)).isEqualTo(0L);
                sink.recover();
                manager.publishLocal("orders", 0, "next".getBytes(UTF_8), 2000L);
                assertThat(tailLatch.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(deliveredOffsets).containsExactly(0L, 1L);
                assertThat(flakyRuntime.deadLetterHandler().read("orders", 10)).hasSize(1);
            } finally {
                flakyRuntime.close();
            }
        }
    }

    /// Sink that refuses appends until [#recover] is called, then delegates to the in-memory
    /// default. The volatile default can never fail, so it can never exercise the failure-aware
    /// contract — this stub is the adversarial half. `failedAttempts` counts down once per refused
    /// append, letting a test await proof that the retry loop ran.
    static final class FlakyDeadLetterSink implements DeadLetterHandler {
        final CountDownLatch failedAttempts;
        private final AtomicBoolean failing = new AtomicBoolean(true);
        private final DeadLetterHandler delegate = DeadLetterHandler.deadLetterHandler();

        FlakyDeadLetterSink(int failuresToAwait) {
            this.failedAttempts = new CountDownLatch(failuresToAwait);
        }

        void recover() {
            failing.set(false);
        }

        @Override
        public Promise<Unit> append(String streamName,
                                    int partition,
                                    long offset,
                                    String failingGroup,
                                    byte[] payload,
                                    String errorMessage,
                                    int attemptCount) {
            if (failing.get()) {
                failedAttempts.countDown();

                return StreamError.General.BUFFER_FULL.promise();
            }

            return delegate.append(streamName, partition, offset, failingGroup, payload, errorMessage, attemptCount);
        }

        @Override
        public List<DeadLetterEntry> read(String streamName, int maxCount) {
            return delegate.read(streamName, maxCount);
        }
    }

    @Nested
    class StallStrategy {
        @Test
        void stall_stopsDelivery_onFailure() throws InterruptedException {
            createTestStream("orders");
            var deliveredOffsets = new CopyOnWriteArrayList<Long>();
            var config = ConsumerConfig.consumerConfig("group-1", 1, ProcessingMode.ORDERED, ErrorStrategy.STALL);

            runtime.subscribe("orders",
                              0,
                              config,
                              (offset, payload, ts) -> {
                                  deliveredOffsets.add(offset);
                                  if (offset == 0L) {
                                  return StreamError.General.BUFFER_EMPTY.promise();
                              }

                                  return Promise.unitPromise();
                              });
            manager.publishLocal("orders", 0, "fail-event".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "should-not-deliver".getBytes(), 2000L);
            Thread.sleep(500);
            // Only the first event should be delivered; consumer is stalled
            assertThat(deliveredOffsets).containsExactly(0L);
            // Cursor should not have advanced
            var cursor = runtime.cursorPosition("orders", 0, "group-1");

            cursor.onPresent(pos -> assertThat(pos).isEqualTo(0L));
        }
    }

    @Nested
    class CloseTests {
        @Test
        void close_stopsAllConsumers() throws Exception {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> Promise.unitPromise());
            runtime.close();
            // After close, subscribe should fail
            runtime.subscribe("orders",
                              0,
                              ConsumerConfig.consumerConfig("group-2"),
                              (offset, payload, ts) -> Promise.unitPromise())
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSUMER_RUNTIME_CLOSED));
        }
    }

    /// #654: `flushCursorForKey` (detach) and `checkpointIfNeeded` (periodic) used to discard
    /// `ConsumerCursorStore.commit`'s result outright — no await, no log, no counter, no visible
    /// surface. These pin the fix: a synchronously-failing commit is observed immediately, a commit
    /// that outlives the shutdown bound does not hold [ConsumerRuntimeState#close], a late resolution
    /// past the bound is still logged and counted, a successful commit lets a later attach resume
    /// without redelivery, and a periodic checkpoint failure never blocks delivery.
    @Nested
    class CursorCommitObservability {
        private static ConsumerCursorStore committing(Promise<Unit> commitResult) {
            return new ConsumerCursorStore() {
                @Override
                public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
                    return commitResult;
                }

                @Override
                public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                    return Promise.success(none());
                }
            };
        }

        /// #654 round 4 (D1 regression): a periodic checkpoint commit and a final close-time commit
        /// for the same `(group, stream, partition)` key are two DISTINCT calls into `commit(...)`, so
        /// this hands back a different promise per call instead of [#committing]'s single fixed one —
        /// the shape D1 needed to reproduce two commits sharing one [ConsumerRuntimeState.ConsumerState].
        private static ConsumerCursorStore committingSequence(List<Promise<Unit>> commitResults, CountDownLatch commitsIssued) {
            var index = new AtomicInteger(0);

            return new ConsumerCursorStore() {
                @Override
                public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
                    var i = Math.min(index.getAndIncrement(), commitResults.size() - 1);

                    commitsIssued.countDown();

                    return commitResults.get(i);
                }

                @Override
                public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                    return Promise.success(none());
                }
            };
        }

        @Test
        void close_countsFailure_whenFinalCommitFailsSynchronously_andDoesNotWaitOutTheBound() throws Exception {
            createTestStream("orders");
            var store = committing(StreamError.General.BUFFER_EMPTY.promise());
            var observedRuntime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);

            observedRuntime.subscribe("orders",
                                      0,
                                      ConsumerConfig.consumerConfig("group-1"),
                                      (offset, payload, ts) -> Promise.unitPromise());
            assertThat(observedRuntime.cursorCommitFailureCount()).isZero();

            var start = System.nanoTime();

            observedRuntime.close();

            var elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(observedRuntime.cursorCommitFailureCount()).describedAs("the previously-discarded commit failure is now observed")
                      .isEqualTo(1L);
            assertThat(elapsedMs).describedAs("a commit that fails synchronously must not wait out the shutdown bound")
                      .isLessThan(1000L);
        }

        /// #654 round 2: the documented contract for [ConsumerRuntimeState#awaitFinalCursorCommits] is
        /// that a commit still unresolved when the shutdown bound expires counts as failed for THIS
        /// shutdown immediately — not only if/when it eventually resolves. The count therefore reaches
        /// 1 the instant `close()` returns, before `pending` is ever settled.
        /// #654 round 4: a commit already reported at the bound that later resolves with a genuine
        /// failure is the SAME incident, not a second one — [ConsumerRuntimeState#reportCommitOutcome]
        /// CASes a token minted per commit ([ConsumerRuntimeState.TrackedCommit#reported]); whichever of
        /// the bound-expiry report or the later failure wins that CAS owns the one increment, and the
        /// loser logs at WARNING ("late resolution of an already-reported cursor commit"), not ERROR, so
        /// the count stays at 1 even after the late failure lands. The no-rollback guarantee for a late
        /// SUCCESS is pinned by the `succeed`-after-bound test below.
        @Test
        void close_countsUnsettledCommit_whenFinalCommitNeverSettlesWithinBound() throws InterruptedException {
            createTestStream("orders");
            Promise<Unit> pending = Promise.promise();
            var store = committing(pending);
            var observedRuntime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);

            observedRuntime.subscribe("orders",
                                      0,
                                      ConsumerConfig.consumerConfig("group-1"),
                                      (offset, payload, ts) -> Promise.unitPromise());

            var start = System.nanoTime();

            observedRuntime.close();

            var elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).describedAs("node stop must not be held past the #654 shutdown bound")
                      .isBetween(4500L, 9000L);
            assertThat(observedRuntime.cursorCommitFailureCount())
                      .describedAs("unresolved at the shutdown bound counts as failed for THIS shutdown immediately, before the promise ever resolves")
                      .isEqualTo(1L);

            var lateResolution = new CountDownLatch(1);

            pending.onResult(_ -> lateResolution.countDown());
            pending.fail(StreamError.General.BUFFER_EMPTY);

            assertThat(lateResolution.await(2, TimeUnit.SECONDS))
                      .describedAs("the late failure handler must actually run before the counter assertion means anything")
                      .isTrue();
            assertThat(observedRuntime.cursorCommitFailureCount())
                      .describedAs("the promise later resolving with a genuine failure is the same incident already counted at the bound, not a second one")
                      .isEqualTo(1L);
        }

        /// #654 round 2: the ruling's actual guarantee — a commit marked unsettled at the shutdown
        /// bound must stay counted even when it turns out, after the fact, that the write succeeded.
        /// A plain success carries no failure for the ordinary `observedCommit` handlers to observe, so
        /// without the bound-expiry mark this commit would never be counted at all despite genuinely
        /// overrunning the shutdown bound. [ConsumerState#recordCursorCommitFailure] is only ever
        /// cleared at the START of a NEXT commit attempt, and there is no next attempt once this
        /// consumer is torn down at close, so the later success cannot roll this back.
        @Test
        void close_countsUnsettledCommit_evenWhenFinalCommitLaterSucceeds() throws InterruptedException {
            createTestStream("orders");
            Promise<Unit> pending = Promise.promise();
            var store = committing(pending);
            var observedRuntime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);

            observedRuntime.subscribe("orders",
                                      0,
                                      ConsumerConfig.consumerConfig("group-1"),
                                      (offset, payload, ts) -> Promise.unitPromise());

            var start = System.nanoTime();

            observedRuntime.close();

            var elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).describedAs("node stop must not be held past the #654 shutdown bound")
                      .isBetween(4500L, 9000L);
            assertThat(observedRuntime.cursorCommitFailureCount())
                      .describedAs("unresolved at the shutdown bound counts as failed for THIS shutdown immediately, before the promise ever resolves")
                      .isEqualTo(1L);

            var lateResolution = new CountDownLatch(1);

            pending.onResult(_ -> lateResolution.countDown());
            pending.succeed(Unit.unit());

            assertThat(lateResolution.await(2, TimeUnit.SECONDS))
                      .describedAs("the late success handler must actually run before the counter assertion means anything")
                      .isTrue();
            assertThat(observedRuntime.cursorCommitFailureCount())
                      .describedAs("a later success must not decrement or clear a commit already marked unsettled at the shutdown bound — the node was already stopping without durable confirmation")
                      .isEqualTo(1L);
        }

        @Test
        void unsubscribe_thenResubscribe_resumesFromCommittedCursor_noRedelivery() throws InterruptedException {
            createTestStream("orders");
            var committed = new AtomicReference<Long>();
            var store = new ConsumerCursorStore() {
                @Override
                public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
                    committed.set(offset);

                    return Promise.unitPromise();
                }

                @Override
                public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                    return Promise.success(option(committed.get()));
                }
            };
            var observedRuntime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);
            var delivered = new CopyOnWriteArrayList<Long>();
            var firstLatch = new CountDownLatch(1);

            try {
                observedRuntime.subscribe("orders",
                                          0,
                                          ConsumerConfig.consumerConfig("group-1"),
                                          (offset, payload, ts) -> {
                                              delivered.add(offset);
                                              firstLatch.countDown();

                                              return Promise.unitPromise();
                                          });
                manager.publishLocal("orders", 0, "event-1".getBytes(UTF_8), 1000L);
                assertThat(firstLatch.await(5, TimeUnit.SECONDS)).isTrue();
                observedRuntime.unsubscribe("orders", 0, "group-1");
                assertThat(committed.get()).describedAs("committed offset is one past the last delivered offset")
                          .isEqualTo(1L);

                var secondLatch = new CountDownLatch(1);

                observedRuntime.subscribe("orders",
                                          0,
                                          ConsumerConfig.consumerConfig("group-1"),
                                          (offset, payload, ts) -> {
                                              delivered.add(offset);
                                              secondLatch.countDown();

                                              return Promise.unitPromise();
                                          });
                manager.publishLocal("orders", 0, "event-2".getBytes(UTF_8), 2000L);
                assertThat(secondLatch.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(delivered).describedAs("each offset is delivered exactly once across the detach/reattach")
                          .containsExactly(0L, 1L);
            } finally {
                observedRuntime.close();
            }
        }

        @Test
        void checkpointIfNeeded_countsFailure_withoutBlockingDelivery() throws InterruptedException {
            createTestStream("orders");
            var store = committing(StreamError.General.BUFFER_EMPTY.promise());
            var observedRuntime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);
            var config = ConsumerConfig.consumerConfig("group-1", 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY, 10L, 3, "");
            var delivered = new CopyOnWriteArrayList<Long>();
            var latch = new CountDownLatch(2);

            try {
                observedRuntime.subscribe("orders",
                                          0,
                                          config,
                                          (offset, payload, ts) -> {
                                              delivered.add(offset);
                                              latch.countDown();

                                              return Promise.unitPromise();
                                          });
                // The 10ms checkpoint interval elapses before the first event, so the very first
                // successful delivery already trips checkpointIfNeeded's time-based branch.
                Thread.sleep(50);
                manager.publishLocal("orders", 0, "event-1".getBytes(UTF_8), 1000L);
                manager.publishLocal("orders", 0, "event-2".getBytes(UTF_8), 2000L);
                assertThat(latch.await(5, TimeUnit.SECONDS)).describedAs("a periodic checkpoint commit failure must not block delivery")
                          .isTrue();
                assertThat(delivered).containsExactly(0L, 1L);
                assertThat(observedRuntime.cursorCommitFailureCount()).describedAs("the periodic checkpoint's discarded failure is now observed")
                          .isGreaterThanOrEqualTo(1L);
            } finally {
                observedRuntime.close();
            }
        }

        /// #654 round 4 (D1 regression): `checkpointIfNeeded` and `close()`'s `flushCursorForKey` can
        /// each issue their own commit for the SAME consumer inside one `close()` — the periodic one is
        /// still in flight when the final one is issued, and nothing drains it (`closed` only stops new
        /// poll cycles). Before round 4 both shared ONE per-consumer dedup flag, so only the FIRST of
        /// the two to be reported ever incremented the counter and the other's later resolution logged
        /// ERROR without incrementing — one incident silently discarded. Round 4 gives each commit its
        /// own [ConsumerRuntimeState.TrackedCommit#reported] token: the bound must count both.
        @Test
        void close_countsBothUnsettledCommits_whenPeriodicAndFinalCommitShareOneConsumer() throws InterruptedException {
            createTestStream("orders");
            Promise<Unit> periodicPending = Promise.promise();
            Promise<Unit> finalPending = Promise.promise();
            var commitsIssued = new CountDownLatch(1);
            var store = committingSequence(List.of(periodicPending, finalPending), commitsIssued);
            var observedRuntime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);
            var config = ConsumerConfig.consumerConfig("group-1", 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY, 10L, 3, "");
            var delivered = new CountDownLatch(1);

            observedRuntime.subscribe("orders",
                                      0,
                                      config,
                                      (offset, payload, ts) -> {
                                          delivered.countDown();

                                          return Promise.unitPromise();
                                      });
            // The 10ms checkpoint interval elapses before the first event, so the very first successful
            // delivery already trips checkpointIfNeeded's time-based branch, putting the PERIODIC commit
            // in flight before close() issues the second, final commit for the same key.
            Thread.sleep(50);
            manager.publishLocal("orders", 0, "event-1".getBytes(UTF_8), 1000L);
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(commitsIssued.await(2, TimeUnit.SECONDS))
                      .describedAs("the periodic checkpoint commit must be in flight before close() issues the final commit for the same key")
                      .isTrue();

            observedRuntime.close();

            assertThat(observedRuntime.cursorCommitFailureCount())
                      .describedAs("two distinct unsettled commits for one consumer at the bound must count as two incidents, not one")
                      .isEqualTo(2L);

            var lateResolution = new CountDownLatch(1);

            periodicPending.onResult(_ -> lateResolution.countDown());
            periodicPending.fail(StreamError.General.BUFFER_EMPTY);

            assertThat(lateResolution.await(2, TimeUnit.SECONDS))
                      .describedAs("the periodic commit's late failure handler must actually run before the counter assertion means anything")
                      .isTrue();
            assertThat(observedRuntime.cursorCommitFailureCount())
                      .describedAs("the periodic commit's token already won at the bound; its later genuine failure must not add a second increment")
                      .isEqualTo(2L);
        }
    }

    /// #488. The reaper measures time since the last `pollPartition`, which for a PUSH-listener
    /// consumer advances only when events arrive. On a quiet partition a perfectly healthy
    /// deployment-declared consumer therefore looks "idle" and, before the IdlePolicy split, was
    /// silently unsubscribed after 60s — reintroducing the exact silent-no-delivery defect #488 fixes,
    /// one layer down. This logic had never run in a cluster, so it is pinned here.
    ///
    /// Time is injected rather than waited on: the threshold is 60s.
    @Nested
    class IdleReaping {
        private static final long WELL_PAST_TIMEOUT_MS = 120_000;

        @Test
        void reapIdleConsumers_unsubscribesClientConsumer_whenIdlePastTimeout() {
            createTestStream("orders");
            runtime.subscribe("orders",
                              0,
                              ConsumerConfig.consumerConfig("client-group"),
                              (offset, payload, ts) -> Promise.unitPromise());
            reapAt(System.currentTimeMillis() + WELL_PAST_TIMEOUT_MS);
            assertThat(runtime.subscriptions()).describedAs("a client-driven consumer that stopped polling is still reaped")
                      .isEmpty();
        }

        @Test
        void reapIdleConsumers_keepsDeclarativeConsumer_whenIdlePastTimeout() {
            createTestStream("orders");
            runtime.subscribe("orders",
                              0,
                              ConsumerConfig.consumerConfig("declared-group"),
                              (offset, payload, ts) -> Promise.unitPromise(),
                              StreamConsumerRuntime.IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED);
            reapAt(System.currentTimeMillis() + WELL_PAST_TIMEOUT_MS);
            assertThat(runtime.subscriptions()).describedAs("a deployment-declared consumer survives an arbitrarily quiet partition")
                      .hasSize(1);
            assertThat(runtime.subscriptions().getFirst().consumerGroup()).isEqualTo("declared-group");
        }

        @Test
        void reapIdleConsumers_reapsOnlyTheClientConsumer_whenBothAreIdle() {
            createTestStream("orders");
            runtime.subscribe("orders",
                              0,
                              ConsumerConfig.consumerConfig("client-group"),
                              (offset, payload, ts) -> Promise.unitPromise());
            runtime.subscribe("orders",
                              1,
                              ConsumerConfig.consumerConfig("declared-group"),
                              (offset, payload, ts) -> Promise.unitPromise(),
                              StreamConsumerRuntime.IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED);
            reapAt(System.currentTimeMillis() + WELL_PAST_TIMEOUT_MS);
            assertThat(runtime.subscriptions()).extracting(StreamConsumerRuntime.SubscriptionSnapshot::consumerGroup)
                      .containsExactly("declared-group");
        }

        @Test
        void reapIdleConsumers_keepsBoth_whenNeitherIsIdle() {
            createTestStream("orders");
            runtime.subscribe("orders",
                              0,
                              ConsumerConfig.consumerConfig("client-group"),
                              (offset, payload, ts) -> Promise.unitPromise());
            runtime.subscribe("orders",
                              1,
                              ConsumerConfig.consumerConfig("declared-group"),
                              (offset, payload, ts) -> Promise.unitPromise(),
                              StreamConsumerRuntime.IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED);
            reapAt(System.currentTimeMillis());
            assertThat(runtime.subscriptions()).hasSize(2);
        }

        private void reapAt(long now) {
            ((ConsumerRuntimeState) runtime).reapIdleConsumers(now);
        }
    }

    /// #488 operator surface: the subscription snapshot backing `GET /api/streams/declarative-consumers`.
    @Nested
    class SubscriptionSnapshots {
        @Test
        void subscriptions_reportsStreamPartitionAndGroup_forEachSubscription() {
            createTestStream("orders");
            runtime.subscribe("orders",
                              2,
                              ConsumerConfig.consumerConfig("group-a"),
                              (offset, payload, ts) -> Promise.unitPromise());
            assertThat(runtime.subscriptions()).singleElement()
                      .satisfies(snapshot -> {
                                     assertThat(snapshot.streamName()).isEqualTo("orders");
                                     assertThat(snapshot.partition()).isEqualTo(2);
                                     assertThat(snapshot.consumerGroup()).isEqualTo("group-a");
                                     assertThat(snapshot.stalled()).isFalse();
                                 });
        }

        @Test
        void subscriptions_isEmpty_whenNothingSubscribed() {
            createTestStream("orders");
            assertThat(runtime.subscriptions()).isEmpty();
        }
    }

    /// #535: a consumer no longer needs the partition's ring to be local.
    ///
    /// The node wires [StreamConsumerRuntime.PartitionReader] to the routed reader, which forwards to
    /// the HRW owner when the local read fails `PARTITION_NOT_LOCAL`. These tests stand in for that
    /// router with a reader serving a synthetic remote log, and subscribe to a stream this node has
    /// never created — precisely the shape of an assignee that does not own the partition.
    @Nested
    class RoutedReads {
        private static final int REMOTE_LOG_SIZE = 3;

        private StreamConsumerRuntime routedRuntime;

        @AfterEach
        void closeRouted() throws Exception {
            if (routedRuntime != null) {
                routedRuntime.close();
            }
        }

        @Test
        void subscribe_deliversEvents_whenPartitionIsNotLocalButReaderServesIt() throws InterruptedException {
            var latch = new CountDownLatch(REMOTE_LOG_SIZE);
            var received = new CopyOnWriteArrayList<String>();

            routedRuntime = runtimeReading((_, _, fromOffset, _) -> Promise.success(remoteLog(fromOffset)));
            routedRuntime.subscribe("never-created-here",
                                    0,
                                    ConsumerConfig.consumerConfig("group-1"),
                                    (offset, payload, ts) -> record(received, latch, payload));
            assertThat(latch.await(10, TimeUnit.SECONDS)).describedAs("the routed reader is what makes a non-owner assignee able to consume at all")
                      .isTrue();
            assertThat(received).containsExactly("remote-0", "remote-1", "remote-2");
        }

        /// Non-vacuity: the SAME subscription against the default local reader delivers nothing, because
        /// the ring is not here. Without this arm the test above could pass for the wrong reason.
        @Test
        void subscribe_deliversNothing_whenPartitionIsNotLocalAndReaderIsTheLocalRing() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var received = new CopyOnWriteArrayList<String>();

            routedRuntime = runtimeReading(StreamConsumerRuntime.localPartitionReader(manager));
            routedRuntime.subscribe("never-created-here",
                                    0,
                                    ConsumerConfig.consumerConfig("group-1"),
                                    (offset, payload, ts) -> record(received, latch, payload));
            assertThat(latch.await(2, TimeUnit.SECONDS)).describedAs("this is the #535 defect: no local ring, so the local reader consumes nothing")
                      .isFalse();
            assertThat(received).isEmpty();
        }

        @Test
        void subscribe_keepsPolling_whenTheReaderKeepsFailing() throws InterruptedException {
            var attempts = new AtomicInteger();

            routedRuntime = runtimeReading((_, _, _, _) -> failedRead(attempts));
            routedRuntime.subscribe("never-created-here",
                                    0,
                                    ConsumerConfig.consumerConfig("group-1"),
                                    (offset, payload, ts) -> Promise.unitPromise());
            TimeUnit.MILLISECONDS.sleep(500);
            assertThat(attempts.get()).describedAs("an unreachable owner must back off and retry, not give up and not spin")
                      .isBetween(2, 200);
        }

        private StreamConsumerRuntime runtimeReading(StreamConsumerRuntime.PartitionReader reader) {
            return new ConsumerRuntimeState(manager, DeadLetterHandler.deadLetterHandler(), none(), none(), reader);
        }

        private static Promise<List<OffHeapRingBuffer.RawEvent>> failedRead(AtomicInteger attempts) {
            attempts.incrementAndGet();

            return StreamError.General.PARTITION_NOT_LOCAL.promise();
        }

        /// A synthetic remote log: everything at or after `fromOffset`, so the cursor converges exactly
        /// as it would against a real partition rather than redelivering forever.
        private static List<OffHeapRingBuffer.RawEvent> remoteLog(long fromOffset) {
            return LongStream.range(fromOffset, REMOTE_LOG_SIZE)
                             .mapToObj(offset -> new OffHeapRingBuffer.RawEvent(offset,
                                                                                ("remote-" + offset).getBytes(UTF_8),
                                                                                0L))
                             .toList();
        }

        private static Promise<Unit> record(List<String> received, CountDownLatch latch, byte[] payload) {
            received.add(new String(payload, UTF_8));
            latch.countDown();

            return Promise.unitPromise();
        }
    }
}
