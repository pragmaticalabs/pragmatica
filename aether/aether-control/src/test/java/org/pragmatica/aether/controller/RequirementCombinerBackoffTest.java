// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.controller.RequirementCombiner.LeaderCombiner;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the combiner's retry delay schedule by value (v2713c mutants q12-q14, q16, q17): 1 s doubling to a 30 s cap, reached at
/// attempt 6 (32 s uncapped), constant afterwards.
class RequirementCombinerBackoffTest {
    @Test
    void retryDelay_doublesFromOneSecond() {
        assertThat(LeaderCombiner.retryDelayMs(1)).isEqualTo(1_000L);
        assertThat(LeaderCombiner.retryDelayMs(2)).isEqualTo(2_000L);
        assertThat(LeaderCombiner.retryDelayMs(3)).isEqualTo(4_000L);
        assertThat(LeaderCombiner.retryDelayMs(4)).isEqualTo(8_000L);
        assertThat(LeaderCombiner.retryDelayMs(5)).isEqualTo(16_000L);
    }

    @Test
    void retryDelay_isCappedAtThirtySecondsFromAttemptSix() {
        assertThat(LeaderCombiner.retryDelayMs(6)).isEqualTo(30_000L);
        assertThat(LeaderCombiner.retryDelayMs(7)).isEqualTo(30_000L);
        assertThat(LeaderCombiner.retryDelayMs(40)).isEqualTo(30_000L);
    }
}
