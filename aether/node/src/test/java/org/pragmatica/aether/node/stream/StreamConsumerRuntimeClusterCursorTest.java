// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #654 round 2: [ClusterCursorStore] chains the consensus checkpoint publish onto `commit(...)`'s own
/// Promise, and the runtime's observability surface (`cursorCommitFailureCount`,
/// `SubscriptionSnapshot#lastCursorCommitFailure`) must reflect a RECOVERED publish failure exactly as
/// it reflects a local-commit failure. [ClusterCursorStore] lives in `aether-node` and
/// [StreamConsumerRuntime] in `aether-stream` (the dependency runs one way — node depends on stream,
/// never the reverse), so this module is the only place both the real store and the real runtime are on
/// the classpath together. Testing the fold-in against a `ConsumerCursorStore` test double instead would
/// only prove the runtime handles whatever the double reports, never that the real store reports the
/// right thing in the first place — the exact gap #654 round 2 exists to close.
class StreamConsumerRuntimeClusterCursorTest {
    private static final String GROUP = "group-1";
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig("orders",
                                                        4,
                                                        RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000),
                                                        "earliest"));
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    /// `onSuccess`/`onFailure` attached to a still-unresolved [Promise] fire through virtual-thread
    /// event dispatch once it resolves, not synchronously in the resolving thread.
    private static void awaitCount(LongSupplier actual, long expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while (actual.getAsLong() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static ConsumerCursorStore succeedingLocal() {
        record succeedingLocal() implements ConsumerCursorStore {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(Option.none());
            }
        }

        return new succeedingLocal();
    }

    private static ConsumerCursorStore clusterStoreWith(Promise<Unit> publishResult) {
        return ClusterCursorStore.clusterCursorStore(succeedingLocal(),
                                                     _ -> Option.none(),
                                                     _ -> publishResult);
    }

    @Test
    void close_countsRecoveredCheckpointFailure_whenConsensusPublishFails_butLocalCommitSucceeds() throws Exception {
        var store = clusterStoreWith(CheckpointRejected.INSTANCE.promise());
        var runtime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);

        runtime.subscribe("orders",
                          0,
                          ConsumerConfig.consumerConfig(GROUP),
                          (offset, payload, ts) -> Promise.unitPromise());
        assertThat(runtime.cursorCommitFailureCount()).isZero();

        runtime.close();

        assertThat(runtime.cursorCommitFailureCount())
                .describedAs("the local commit succeeded but the consensus publish was recovered rather than failing commit(...) — the recovery must still be counted")
                .isEqualTo(1L);
    }

    /// The message-text distinction the ruling requires: a recovered checkpoint-publish failure must
    /// read differently from a local-commit failure, so an operator is never told the local disk write
    /// failed when only the consensus publish did. Exercised on the PERIODIC path so the snapshot can
    /// be read while the runtime is still open — a final commit's snapshot is gone once `close()` clears
    /// the consumer map.
    @Test
    void checkpoint_reportsCheckpointPublishText_distinctFromLocalCommitText() throws InterruptedException {
        var store = clusterStoreWith(CheckpointRejected.INSTANCE.promise());
        var runtime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);
        var config = ConsumerConfig.consumerConfig(GROUP, 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY, 10L, 3, "");
        var latch = new CountDownLatch(1);

        try {
            runtime.subscribe("orders",
                              0,
                              config,
                              (offset, payload, ts) -> {
                                  latch.countDown();

                                  return Promise.unitPromise();
                              });
            // The 10ms checkpoint interval elapses before the first event, so the first successful
            // delivery already trips the time-based checkpoint branch.
            Thread.sleep(50);
            manager.publishLocal("orders", 0, "event-1".getBytes(UTF_8), 1000L);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            awaitCount(runtime::cursorCommitFailureCount, 1L);

            assertThat(runtime.subscriptions())
                    .singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.lastCursorCommitFailure().or(""))
                                                    .describedAs("must name which stage recovered — never read like the local commit failed")
                                                    .contains("checkpoint publish")
                                                    .doesNotContain("local commit"));
        } finally {
            runtime.close();
        }
    }

    /// #654 round 2: the count reaches 1 IMMEDIATELY when `close()` returns — the bound expiring with
    /// `pending` still unresolved is what counts it, not the later `pending.fail(...)`.
    /// #654 round 4: a commit already reported this way that goes on to resolve with a genuine failure
    /// is the SAME incident, not a second one — [ConsumerRuntimeState#reportCommitOutcome] CASes a
    /// token minted per commit; whichever of the bound-expiry report or the later failure wins that CAS
    /// owns the one increment, and the loser logs at WARNING, not ERROR, so the count stays at 1 even
    /// after the late failure lands. The `succeed`-after-bound test below pins the no-rollback guarantee
    /// for a late SUCCESS.
    @Test
    void close_countsUnsettledCommit_whenConsensusPublishNeverSettlesWithinBound() throws InterruptedException {
        Promise<Unit> pending = Promise.promise();
        var store = clusterStoreWith(pending);
        var runtime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);

        runtime.subscribe("orders",
                          0,
                          ConsumerConfig.consumerConfig(GROUP),
                          (offset, payload, ts) -> Promise.unitPromise());

        var start = System.nanoTime();

        runtime.close();

        var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).describedAs("a wedged consensus publish must not hold node stop past the #654 shutdown bound")
                  .isBetween(4500L, 9000L);
        assertThat(runtime.cursorCommitFailureCount())
                  .describedAs("unresolved at the shutdown bound counts as failed for THIS shutdown immediately, before the promise ever resolves")
                  .isEqualTo(1L);

        var lateResolution = new CountDownLatch(1);

        pending.onResult(_ -> lateResolution.countDown());
        pending.fail(CheckpointRejected.INSTANCE);

        assertThat(lateResolution.await(2, TimeUnit.SECONDS))
                .describedAs("the late failure handler must actually run before the counter assertion means anything")
                .isTrue();
        assertThat(runtime.cursorCommitFailureCount())
                .describedAs("the promise later resolving with a genuine failure is the same incident already counted at the bound, not a second one")
                .isEqualTo(1L);
    }

    /// #654 round 2: the ruling's actual guarantee — a commit marked unsettled at the shutdown bound
    /// must stay counted even when it turns out, after the fact, that the write succeeded. A plain
    /// success carries no failure for [ConsumerRuntimeState#onCursorCommitFailure] /
    /// [ConsumerRuntimeState#recordIfRecovered] to observe, so without the bound-expiry mark this commit
    /// would never be counted at all despite genuinely overrunning the shutdown bound.
    @Test
    void close_countsUnsettledCommit_evenWhenConsensusPublishLaterSucceeds() throws InterruptedException {
        Promise<Unit> pending = Promise.promise();
        var store = clusterStoreWith(pending);
        var runtime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);

        runtime.subscribe("orders",
                          0,
                          ConsumerConfig.consumerConfig(GROUP),
                          (offset, payload, ts) -> Promise.unitPromise());

        var start = System.nanoTime();

        runtime.close();

        var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).describedAs("a wedged consensus publish must not hold node stop past the #654 shutdown bound")
                  .isBetween(4500L, 9000L);
        assertThat(runtime.cursorCommitFailureCount())
                  .describedAs("unresolved at the shutdown bound counts as failed for THIS shutdown immediately, before the promise ever resolves")
                  .isEqualTo(1L);

        var lateResolution = new CountDownLatch(1);

        pending.onResult(_ -> lateResolution.countDown());
        pending.succeed(Unit.unit());

        assertThat(lateResolution.await(2, TimeUnit.SECONDS))
                .describedAs("the late success handler must actually run before the counter assertion means anything")
                .isTrue();
        assertThat(runtime.cursorCommitFailureCount())
                .describedAs("a later success must not decrement or clear a commit already marked unsettled at the shutdown bound — the node was already stopping without durable confirmation")
                .isEqualTo(1L);
    }

    @Test
    void close_countsNothing_whenConsensusPublishSucceeds() throws Exception {
        var store = clusterStoreWith(Promise.unitPromise());
        var runtime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);

        runtime.subscribe("orders",
                          0,
                          ConsumerConfig.consumerConfig(GROUP),
                          (offset, payload, ts) -> Promise.unitPromise());
        runtime.close();

        assertThat(runtime.cursorCommitFailureCount())
                .describedAs("both stages succeeded — nothing to recover, nothing to count")
                .isZero();
    }

    /// #1239 scope (review claim P2): a consensus-publish failure is recovered into a local-only success,
    /// and the periodic checkpoint used to retry only when `commit(...)` itself FAILED — so a failed
    /// cluster checkpoint was never retried and the cluster cursor stayed stale on a quiet partition.
    /// One event, then silence: only a retry of the local-only outcome can land the cluster checkpoint.
    @Test
    void periodicCheckpoint_retriesALocalOnlyOutcome_untilTheClusterCheckpointLands() throws InterruptedException {
        var calls = new AtomicInteger();
        var clusterPersisted = new CopyOnWriteArrayList<Long>();
        var store = ClusterCursorStore.clusterCursorStore(succeedingLocal(),
                                                          _ -> Option.none(),
                                                          command -> firstRejected(calls, clusterPersisted, command));
        var runtime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);
        var config = ConsumerConfig.consumerConfig(GROUP, 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY, 10L, 3, "");

        try {
            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> Promise.unitPromise());
            // The 10ms interval elapses first, so the single delivery trips the time-based checkpoint.
            Thread.sleep(50);
            manager.publishLocal("orders", 0, "event-1".getBytes(UTF_8), 1000L);

            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

            while (clusterPersisted.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(calls.get()).describedAs("the first cluster checkpoint was attempted and rejected")
                                   .isGreaterThanOrEqualTo(1);
            assertThat(clusterPersisted).describedAs("a local-only outcome is retried until the cluster checkpoint lands")
                                        .isNotEmpty();
            assertThat(clusterPersisted.getLast()).isEqualTo(runtime.cursorPosition("orders", 0, GROUP).or(-1L));
        } finally {
            runtime.close();
        }
    }

    /// #1239 scope (review claim P2): the detach flush must not overlap the in-flight periodic commit.
    /// Overlapping commits for one key let one commit's outcome be read as another's — the old per-key
    /// side map reported B with A's cause, or lost A's cause when B cleared it. Here A (periodic) is held
    /// and later rejected; B (detach flush) must wait for A, and each commit reports only its own outcome:
    /// A's rejection exactly once, B's success not at all.
    @Test
    void detachFlush_waitsForTheInFlightPeriodicCommit_andEachCommitReportsOnlyItsOwnOutcome() throws InterruptedException {
        var calls = new AtomicInteger();
        Promise<Unit> heldA = Promise.promise();
        var store = ClusterCursorStore.clusterCursorStore(succeedingLocal(),
                                                          _ -> Option.none(),
                                                          _ -> calls.incrementAndGet() == 1 ? heldA : Promise.unitPromise());
        var runtime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);
        var config = ConsumerConfig.consumerConfig(GROUP, 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY, 10L, 3, "");

        try {
            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> Promise.unitPromise());
            Thread.sleep(50);
            manager.publishLocal("orders", 0, "event-1".getBytes(UTF_8), 1000L);
            awaitCount(calls::get, 1L);
            assertThat(calls.get()).describedAs("commit A (periodic) is in flight").isEqualTo(1);

            runtime.unsubscribe("orders", 0, GROUP);
            Thread.sleep(200);
            assertThat(calls.get()).describedAs("commit B (detach flush) must not be issued while commit A is in flight")
                                   .isEqualTo(1);

            heldA.fail(CheckpointRejected.INSTANCE);
            awaitCount(calls::get, 2L);
            awaitCount(runtime::cursorCommitFailureCount, 1L);
            Thread.sleep(200);
            assertThat(calls.get()).describedAs("B is issued once A settles, and nothing retries after detach")
                                   .isEqualTo(2);
            assertThat(runtime.cursorCommitFailureCount()).describedAs("A's rejection is counted exactly once; B's success adds nothing")
                                                          .isEqualTo(1L);
        } finally {
            runtime.close();
        }
    }

    private static Promise<Unit> firstRejected(AtomicInteger calls, List<Long> persisted, KVCommand<AetherKey> command) {
        if (calls.incrementAndGet() == 1) {
            return CheckpointRejected.INSTANCE.promise();
        }
        if (command instanceof KVCommand.Put<?, ?> put && put.value() instanceof StreamCursorCheckpointValue value) {
            persisted.add(value.committedOffset());
        }

        return Promise.unitPromise();
    }

    private enum CheckpointRejected implements Cause {
        INSTANCE;

        @Override
        public String message() {
            return "no quorum";
        }
    }
}
