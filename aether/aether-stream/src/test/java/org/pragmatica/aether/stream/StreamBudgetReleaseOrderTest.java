// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 C-03 (spec 4.4 item 4): the freed generation moves only AFTER the bytes it announces are back in the pool. A ring
/// that read a moved generation and was then refused would remember that generation and stay frozen with the bytes unused
/// (a lost wake-up). A destroy returns the control bytes (one bump) and then the ring's data bytes (a second bump); a reader
/// that sees the generation between the two bumps must already see the pool without the first release's bytes. Racy by
/// nature: on the reversed order this reddens thousands of times per run (v-2547: 27,050 of 831,930 intermediate reads at
/// 250,000 cycles); on the correct order no interleaving can redden it.
class StreamBudgetReleaseOrderTest {
    private static final int CYCLES = 100_000;

    private static StreamConfig stream(String name) {
        return StreamConfig.streamConfig(name, 1, RetentionPolicy.retentionPolicy(100, 64 * 1024L, 3_600_000L), "latest");
    }

    @Test
    void theFreedGenerationMovesOnlyAfterTheBytesAreBackInThePool() throws Exception {
        var manager = streamPartitionManager(64L * 1024 * 1024);
        var field = StreamPartitionManager.class.getDeclaredField("budgetFreedGeneration");
        field.setAccessible(true);
        var generation = (AtomicLong) field.get(manager);

        manager.createStream(stream("anchor")).onFailure(cause -> fail(cause.message()));
        manager.createStream(stream("b")).onFailure(cause -> fail(cause.message()));
        var withB = manager.totalAllocatedBytes();
        var before = generation.get();
        manager.destroyStream("b").onFailure(cause -> fail(cause.message()));
        var bumpsPerDestroy = generation.get() - before;

        assertThat(bumpsPerDestroy).as("a destroy releases twice (control bytes, then ring bytes)").isGreaterThanOrEqualTo(2);

        var stop = new AtomicBoolean();
        var violations = new AtomicLong();
        var observed = new AtomicLong();
        var cycleStart = new AtomicLong(-1);
        var reader = new Thread(() -> {
            while (!stop.get()) {
                var start = cycleStart.get();
                var seen = generation.get();
                var total = manager.totalAllocatedBytes();
                var after = generation.get();

                if (start < 0 || seen != after) {
                    continue;
                }
                var step = seen - start;

                if (step >= 1 && step < bumpsPerDestroy) {
                    observed.incrementAndGet();

                    if (total >= withB) {
                        violations.incrementAndGet();
                    }
                }
            }
        });
        reader.start();

        for (var i = 0; i < CYCLES; i++) {
            manager.createStream(stream("b")).onFailure(cause -> fail(cause.message()));
            cycleStart.set(generation.get());
            manager.destroyStream("b");
            cycleStart.set(-1);
        }
        stop.set(true);
        reader.join();
        manager.close();

        // Only an order violation fails. A reader starved of CPU (a loaded CI runner) may never land between the two
        // releases; that run proved nothing, so it is skipped with the count, never failed.
        assertThat(violations.get()).as("reads of a moved generation whose bytes were not yet back in the pool (of %d)",
                                        observed.get())
                                    .isZero();
        assumeTrue(observed.get() > 0,
                   "the reader never saw the pool between the two releases of a destroy in " + CYCLES
                   + " cycles (observed=" + observed.get() + "): starved, nothing was tested");
    }
}
