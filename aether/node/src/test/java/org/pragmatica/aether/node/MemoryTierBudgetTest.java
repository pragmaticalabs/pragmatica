// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The memory-tier budget (A0b): what fits is left alone, what does not is cut in one proportion and never exceeds the
/// budget. Each test names its mutation.
class MemoryTierBudgetTest {
    private static final long MIB = 1024L * 1024L;

    /// Mutation: `ofHeap` forgets `reservedBytes`.
    @Test
    void ofHeap_takesTheFractionOfTheHeap_lessWhatIsReserved() {
        assertThat(MemoryTierBudget.ofHeap(256 * MIB, 16 * MIB).totalBytes()).isEqualTo(112 * MIB);
    }

    /// Mutation: a negative budget (reserved above the fraction) is not floored at zero.
    @Test
    void ofHeap_whenReservedExceedsTheFraction_isZero() {
        assertThat(MemoryTierBudget.ofHeap(16 * MIB, 16 * MIB).totalBytes()).isZero();
    }

    /// Mutation: `fit` scales even when the request fits.
    @Test
    void fit_requestWithinBudget_isLeftExactlyAsConfigured() {
        var requested = Map.of("artifacts", 100 * MIB, "content", 50 * MIB);

        assertThat(new MemoryTierBudget(200 * MIB).fit(requested)).isEqualTo(requested);
    }

    /// Mutation: each entry is capped at the whole budget instead of scaled in proportion.
    @Test
    void fit_requestOverBudget_isScaledInOneProportion_andSumsWithinTheBudget() {
        var fitted = new MemoryTierBudget(100 * MIB).fit(Map.of("artifacts", 300 * MIB, "content", 100 * MIB));

        assertThat(fitted.get("artifacts")).isEqualTo(75 * MIB);
        assertThat(fitted.get("content")).isEqualTo(25 * MIB);
    }

    /// Mutation: `share` multiplies in `long` -- this overflows for large configured sizes on a large heap.
    @Test
    void fit_hugeConfiguredSizes_doNotOverflow() {
        var huge = 8L * 1024 * 1024 * MIB;
        var fitted = new MemoryTierBudget(huge / 2).fit(Map.of("artifacts", huge, "content", huge));

        assertThat(fitted.values().stream().mapToLong(Long::longValue).sum()).isLessThanOrEqualTo(huge / 2).isPositive();
    }
}
