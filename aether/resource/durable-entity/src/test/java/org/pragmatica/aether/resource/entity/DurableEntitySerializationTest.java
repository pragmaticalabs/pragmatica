// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The gate for plan Phase 2a: proves the per-key serialization mechanism is correct on BOTH axes —
/// same-key operations apply in a consistent total order (no lost updates) AND different keys make
/// progress in parallel.
class DurableEntitySerializationTest {
    private static final TimeSpan AWAIT = timeSpan(10).seconds();
    private static final int CONCURRENT_UPDATES = 200;
    private static final int DISTINCT_KEYS = 16;

    /// Same-key total order: fire {@value CONCURRENT_UPDATES} concurrent increments against ONE key
    /// from {@value CONCURRENT_UPDATES} virtual threads released simultaneously. If the per-key
    /// serialization holds, every increment is applied exactly once and the final value is exactly
    /// the number of updates — any lost update (a read-modify-write race) would yield a smaller value.
    @Test
    void update_appliesEveryIncrementInTotalOrder_whenConcurrentOnSameKey() {
        var entity = InMemoryDurableEntity.<String, Integer> inMemoryDurableEntity();

        entity.create("counter", 0).await(AWAIT).onFailure(DurableEntitySerializationTest::fail);

        var updates = IntStream.range(0, CONCURRENT_UPDATES)
                               .mapToObj(_ -> entity.update("counter", value -> value + 1))
                               .toList();

        Promise.allOf(updates).await(AWAIT);

        entity.get("counter")
              .await(AWAIT)
              .onFailure(DurableEntitySerializationTest::fail)
              .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(CONCURRENT_UPDATES));
    }

    /// Cross-key parallelism: schedule one update on each of {@value DISTINCT_KEYS} distinct keys,
    /// where every mutator rendezvouses on a single barrier of that width. The barrier can only trip
    /// if all {@value DISTINCT_KEYS} mutators run concurrently — i.e. different keys are NOT
    /// serialized against each other. Were the queue global (all keys serialized), the first mutator
    /// would block at the barrier forever and the test would time out. The barrier in the mutator is a
    /// test instrument for observing scheduling; the Promise executor uses virtual threads, so the
    /// blocked rendezvous does not starve the pool.
    @Test
    void update_runsDifferentKeysInParallel_whenScheduledConcurrently() {
        var entity = InMemoryDurableEntity.<Integer, Integer> inMemoryDurableEntity();
        var barrier = new CyclicBarrier(DISTINCT_KEYS);

        IntStream.range(0, DISTINCT_KEYS)
                 .forEach(key -> entity.create(key, 0).await(AWAIT).onFailure(DurableEntitySerializationTest::fail));

        var updates = IntStream.range(0, DISTINCT_KEYS)
                               .mapToObj(key -> entity.update(key, value -> rendezvous(barrier, value)))
                               .toList();

        Promise.allOf(updates).await(AWAIT);

        assertThat(barrier.getNumberWaiting()).isZero();
        assertResultsSucceeded(updates);
    }

    private static void assertResultsSucceeded(List<Promise<Integer>> updates) {
        updates.forEach(update -> update.await(AWAIT)
                                        .onFailure(DurableEntitySerializationTest::fail)
                                        .onSuccess(value -> assertThat(value).isEqualTo(1)));
    }

    /// Rendezvous on the barrier (test instrument) then return the incremented value. Surfaces a
    /// timeout as an unchecked failure so a missing cross-key parallelism would fail the test loudly.
    private static Integer rendezvous(CyclicBarrier barrier, Integer value) {
        return await(barrier) + value + 1;
    }

    private static int await(CyclicBarrier barrier) {
        try {
            barrier.await(AWAIT.nanos(), TimeUnit.NANOSECONDS);

            return 0;
        } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
            throw new AssertionError("Keys did not run in parallel (barrier did not trip): " + e);
        }
    }

    private static void fail(Cause cause) {
        throw new AssertionError(cause.message());
    }
}
