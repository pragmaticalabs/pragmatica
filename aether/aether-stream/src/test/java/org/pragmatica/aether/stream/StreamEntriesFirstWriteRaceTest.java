// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;

import static org.assertj.core.api.Assertions.assertThat;

/// #2201 know `f7bc44d170` item 1, verifier v2559 delta: the writer accessor installs a life's state with a compare-and-set,
/// so writers that first-touch a fresh life at the same moment all reach ONE state and no write is lost. A plain `put` in
/// its place lets a second writer replace the first one's state, and the first writer's write lands in an object no reader
/// sees. Race-shaped, so it runs many rounds behind a spin barrier: correct code loses nothing in any round; the `put`
/// mutant loses writes in some round with near certainty.
class StreamEntriesFirstWriteRaceTest {
    private static final long ENDED_LIFE = 5L;
    private static final long LIFE = 11L;
    private static final int WRITERS = 6;
    private static final int ROUNDS = 2000;
    private static final long SPIN_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(5);

    /// No state stored yet: the install is `putIfAbsent`.
    @Test
    @Timeout(120)
    void writersFirstTouchingALife_atOnce_allReachOneState_andNoWriteIsLost() throws InterruptedException {
        assertThat(lostRounds(false)).as("rounds in which a writer's first write landed outside the life's stored state").isZero();
    }

    /// An ended life's state is still stored: the install is `replace(name, ended, fresh)`.
    @Test
    @Timeout(120)
    void writersReplacingAnEndedLifesState_atOnce_allReachOneState_andNoWriteIsLost() throws InterruptedException {
        assertThat(lostRounds(true)).as("rounds in which a writer's first write landed outside the life's stored state").isZero();
    }

    private static int lostRounds(boolean endedLifeStored) throws InterruptedException {
        var lost = 0;

        for (var round = 0; round < ROUNDS; round++) {
            lost += lostWritesIn(round, endedLifeStored) > 0 ? 1 : 0;
        }

        return lost;
    }

    private static int lostWritesIn(int round, boolean endedLifeStored) throws InterruptedException {
        var name = "orders-" + round;
        var entries = StreamEntries.streamEntries();

        if (endedLifeStored) {
            entries.appliedConfigs().put(name, config(name, ENDED_LIFE));
            entries.livePartitionState(name);
        }

        entries.appliedConfigs().put(name, config(name, LIFE));

        var arrived = new AtomicInteger();
        var threads = new ArrayList<Thread>();

        for (var writer = 0; writer < WRITERS; writer++) {
            var partition = writer;

            threads.add(Thread.ofPlatform().start(() -> {
                arrived.incrementAndGet();
                spinUntilAllArrived(arrived);
                entries.partitionState(name, LIFE).standing().cutGenerations().put(partition, 1L);
            }));
        }

        for (var thread : threads) {
            thread.join();
        }

        var held = entries.existingPartitionState(name)
                          .map(state -> state.standing().cutGenerations().size())
                          .or(0);

        return WRITERS - held;
    }

    private static void spinUntilAllArrived(AtomicInteger arrived) {
        var deadline = System.nanoTime() + SPIN_DEADLINE_NANOS;

        while (arrived.get() < WRITERS && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static StreamConfig config(String name, long incarnation) {
        return StreamConfig.streamConfig(name, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 3_600_000L), "earliest")
                           .withIncarnation(incarnation);
    }
}
