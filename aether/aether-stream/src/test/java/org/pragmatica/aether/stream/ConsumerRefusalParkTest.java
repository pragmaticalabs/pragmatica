// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.stream.forward.RefusalBackoff;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.none;

/// #2574: the consumer's refusal park, on a manual clock and a captured scheduler. A refused read parks the loop for the
/// consumer's refusal hold; nothing starts a pass inside it. A scheduler that wakes LATE must end only its own park: once the
/// park expired by the clock a poll tick may already have run a pass and parked again, and clearing that newer park would
/// re-read at once (seen on a loaded full-suite run). Red if the resume ends whatever park is in force.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConsumerRefusalParkTest {
    private static final String NOT_LOCAL = "not-local";

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Long> delays = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Runnable> scheduled = new LinkedBlockingQueue<>();
    private final AtomicInteger reads = new AtomicInteger();
    private StreamPartitionManager manager;
    private ConsumerRuntimeState runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        runtime = new ConsumerRuntimeState(manager,
                                           DeadLetterHandler.deadLetterHandler(),
                                           none(),
                                           none(),
                                           (_, _, _, _) -> refusedRead(),
                                           ConsumerRuntimeState.DEAD_LETTER_APPEND_TIMEOUT,
                                           clock::get,
                                           (task, delay) -> schedule(task, delay.millis()));
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        manager.close();
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> refusedRead() {
        reads.incrementAndGet();

        return StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(new NodeId("owner")))
                                                        .promise();
    }

    private void schedule(Runnable task, long delayMs) {
        delays.add(delayMs);
        scheduled.add(task);
    }

    @Test
    void refusedRead_parksTheLoop_forTheRefusalHold_andNothingStartsAPassInsideIt() throws InterruptedException {
        runtime.subscribe(NOT_LOCAL, 0, ConsumerConfig.consumerConfig("group-1"), (_, _, _) -> Promise.unitPromise());
        awaitUntil(() -> delays.size() >= 1, "the first park");
        settle();

        assertThat(reads.get()).as("one read, then parked: the poll ticks inside the park start nothing").isEqualTo(1);
        assertThat(delays.getFirst()).as("the first refusal hold, 250 ms +-20%")
                                     .isBetween(200L, RefusalBackoff.FIRST_HOLD.millis() * 6 / 5);
    }

    @Test
    void lateResume_ofAnExpiredPark_doesNotEndTheNewerPark() throws InterruptedException {
        runtime.subscribe(NOT_LOCAL, 0, ConsumerConfig.consumerConfig("group-1"), (_, _, _) -> Promise.unitPromise());
        awaitUntil(() -> delays.size() >= 1, "the first park");
        var staleResume = scheduled.take();

        // the first park expires by the clock; a poll tick runs the next pass, which is refused and parks again
        clock.addAndGet(delays.getFirst() + 1L);
        awaitUntil(() -> delays.size() >= 2, "the second park");
        assertThat(reads.get()).isEqualTo(2);

        // the first park's resume wakes late, inside the second park
        staleResume.run();
        settle();

        assertThat(reads.get()).as("the stale resume did not end the newer park: no read inside it").isEqualTo(2);
    }

    private static void awaitUntil(BooleanSupplier done, String what) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }

        assertThat(done.getAsBoolean()).as(what).isTrue();
    }

    private static void settle() throws InterruptedException {
        Thread.sleep(300L);
    }
}
