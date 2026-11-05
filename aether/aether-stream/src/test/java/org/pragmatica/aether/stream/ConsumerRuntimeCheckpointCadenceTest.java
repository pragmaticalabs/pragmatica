// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// #2590 P0.4 cadence pin: under continuous delivery a consumer's periodic checkpoint fires once per checkpoint interval PLUS
/// one commit latency, not once per commit latency. A commit that persists starts the interval again (`checkpointPersisted`
/// schedules the next one `checkpointInterval()` later), so with a 100 ms interval and a store that resolves each commit after
/// 50 ms the period is 150 ms, and 2 s of delivery commits about 2 / 0.15 times. The bound is wide on purpose: this is timing,
/// and the test fails only outside `[2/0.15 - 3, 2/0.15 + 3]`.
class ConsumerRuntimeCheckpointCadenceTest {
    private static final String GROUP = "group-1";
    private static final long INTERVAL_MS = 100L;
    private static final long COMMIT_LATENCY_MS = 50L;
    private static final long DELIVERY_MS = 2_000L;
    private static final double EXPECTED = DELIVERY_MS / (double) (INTERVAL_MS + COMMIT_LATENCY_MS);

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig("orders",
                                                       1,
                                                       RetentionPolicy.retentionPolicy(100_000, 64 * 1024 * 1024, 60_000),
                                                       "earliest"));
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    @Test
    void periodicCheckpoint_continuousDelivery_firesOncePerIntervalPlusCommitLatency() throws InterruptedException {
        var commits = new AtomicInteger();
        var runtime = streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), resolvingAfterLatency(commits));

        try {
            runtime.subscribe("orders", 0, config(), (offset, payload, ts) -> Promise.unitPromise());
            deliverContinuously();
            var counted = commits.get();

            assertThat((double) counted).as("commits in %d ms at a %d ms interval and a %d ms commit latency (expected ~%.1f)",
                                            DELIVERY_MS,
                                            INTERVAL_MS,
                                            COMMIT_LATENCY_MS,
                                            EXPECTED)
                                        .isBetween(EXPECTED - 3, EXPECTED + 3);
        } finally {
            runtime.close();
        }
    }

    /// One event every 5 ms for [#DELIVERY_MS]: far below the 1000-event trigger, so only the interval decides the cadence.
    private void deliverContinuously() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DELIVERY_MS);
        var sequence = 0L;

        while (System.nanoTime() < deadline) {
            manager.publishLocal("orders", 0, ("event-" + sequence++).getBytes(UTF_8), System.currentTimeMillis());
            Thread.sleep(5);
        }
    }

    /// Every commit persists [#COMMIT_LATENCY_MS] after it is made, and is counted when it is made.
    private static ConsumerCursorStore resolvingAfterLatency(AtomicInteger commits) {
        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
                commits.incrementAndGet();

                return Promise.promise(timeSpan(COMMIT_LATENCY_MS).millis(), () -> Result.success(CommitOutcome.persisted()));
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(Option.none());
            }
        };
    }

    private static ConsumerConfig config() {
        return ConsumerConfig.consumerConfig(GROUP, 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY, INTERVAL_MS, 3, "");
    }
}
