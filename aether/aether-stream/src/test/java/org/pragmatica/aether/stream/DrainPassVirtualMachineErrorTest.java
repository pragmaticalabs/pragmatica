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
