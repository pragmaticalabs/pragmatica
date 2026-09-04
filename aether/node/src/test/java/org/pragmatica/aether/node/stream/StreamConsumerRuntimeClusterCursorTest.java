// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
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
            public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
                return Promise.unitPromise();
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

    @Test
    void close_boundsTheWait_whenConsensusPublishNeverSettles_thenCountsALateRecoveredFailure() throws InterruptedException {
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
        assertThat(runtime.cursorCommitFailureCount()).describedAs("an unresolved publish has nothing to count yet")
                  .isZero();

        pending.fail(CheckpointRejected.INSTANCE);

        awaitCount(runtime::cursorCommitFailureCount, 1L);
        assertThat(runtime.cursorCommitFailureCount())
                .describedAs("a publish that recovers after the bound is still logged and counted, same as a local-commit failure would be")
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

    private enum CheckpointRejected implements Cause {
        INSTANCE;

        @Override
        public String message() {
            return "no quorum";
        }
    }
}
