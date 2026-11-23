// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// F1 (vRH INFO, M-2r-1): every public factory refuses `capacity < 1` BEFORE it allocates. The plain forms throw with a named
/// message, as their other failures do; the `Result` forms return it typed, and never reach the budget seam (nothing was
/// reserved, so nothing needs releasing).
class OffHeapRingBufferCapacityTest {
    private static final long DATA = 4096;

    @Test
    void plainFactories_refuseCapacityBelowOne_withANamedMessage() {
        for (var capacity : new long[]{0L, -1L}) {
            assertRefusedByThrow(() -> OffHeapRingBuffer.offHeapRingBuffer(capacity, DATA));
            assertRefusedByThrow(() -> OffHeapRingBuffer.offHeapRingBuffer("s", 0, capacity, DATA, EvictionListener.NOOP));
            assertRefusedByThrow(() -> OffHeapRingBuffer.offHeapRingBuffer("s",
                                                                           0,
                                                                           capacity,
                                                                           DATA,
                                                                           EvictionListener.NOOP,
                                                                           EvictionPolicy.DROP_OLDEST));
            assertRefusedByThrow(() -> OffHeapRingBuffer.offHeapRingBuffer("s",
                                                                           0,
                                                                           capacity,
                                                                           DATA,
                                                                           64L,
                                                                           EvictionListener.NOOP,
                                                                           EvictionPolicy.DROP_OLDEST));
            assertRefusedByThrow(() -> OffHeapRingBuffer.offHeapRingBuffer(OffHeapRingBuffer.Mode.MEMORY,
                                                                           "s",
                                                                           0,
                                                                           capacity,
                                                                           DATA,
                                                                           64L,
                                                                           EvictionListener.NOOP,
                                                                           EvictionPolicy.DROP_OLDEST));
        }
    }

    @Test
    void resultFactories_refuseCapacityBelowOne_typedAndBeforeTheBudgetSeam() {
        var reserved = new AtomicInteger();
        LongPredicate reserve = bytes -> reserved.incrementAndGet() > 0;
        LongConsumer release = _ -> {};

        for (var capacity : new long[]{0L, -1L}) {
            assertRefusedTyped(() -> OffHeapRingBuffer.offHeapRingBuffer("s",
                                                                         0,
                                                                         capacity,
                                                                         DATA,
                                                                         EvictionListener.NOOP,
                                                                         EvictionPolicy.DROP_OLDEST,
                                                                         reserve,
                                                                         release));
            assertRefusedTyped(() -> OffHeapRingBuffer.offHeapRingBuffer("s",
                                                                         0,
                                                                         capacity,
                                                                         DATA,
                                                                         EvictionListener.NOOP,
                                                                         EvictionPolicy.DROP_OLDEST,
                                                                         reserve,
                                                                         release,
                                                                         () -> 0L));
            assertRefusedTyped(() -> OffHeapRingBuffer.offHeapRingBuffer("s",
                                                                         0,
                                                                         capacity,
                                                                         DATA,
                                                                         64L,
                                                                         EvictionListener.NOOP,
                                                                         EvictionPolicy.DROP_OLDEST,
                                                                         reserve,
                                                                         release,
                                                                         () -> 0L));
        }

        assertThat(reserved).as("the budget seam was never consulted").hasValue(0);
    }

    /// Positive control: capacity 1 is the smallest ring and is built.
    @Test
    void capacityOne_isBuilt() {
        try (var ring = OffHeapRingBuffer.offHeapRingBuffer(1, DATA)) {
            assertThat(ring.append(new byte[]{1}, 1L).isSuccess()).isTrue();
        }
    }

    private static void assertRefusedByThrow(Runnable build) {
        assertThatThrownBy(build::run).isInstanceOf(IllegalArgumentException.class)
                                      .hasMessageContaining("capacity must be at least 1");
    }

    private static void assertRefusedTyped(Supplier<Result<OffHeapRingBuffer>> build) {
        var seen = new AtomicReference<Cause>();

        build.get().onSuccess(ring -> {
            ring.close();
            throw new AssertionError("a ring was built");
        }).onFailure(seen::set);

        assertThat(seen.get()).isInstanceOf(RingStateViolated.class);
        assertThat(seen.get().message()).contains("capacity must be at least 1");
    }
}
