// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The `BUSY` retry rule (section 9): `uniform(0, min(15 s, 1 s x 2^n))`, never before 250 ms, seeded per node and attempt.
class BusyBackoffTest {
    /// Mutation: take the ceiling as `1 s x 2^n` without the 15 s cap, or shift by `n` instead of `n - 1`.
    @Test
    void ceiling_doublesFromOneSecond_andIsCappedAtFifteen() {
        assertThat(IntStream.rangeClosed(1, 7).mapToObj(BusyBackoff::ceilingMillis).toList())
            .containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L);
        assertThat(BusyBackoff.ceilingMillis(1_000)).as("a long run of refusals neither overflows nor leaves the cap").isEqualTo(15_000L);
        assertThat(BusyBackoff.ceilingMillis(0)).as("no refusal yet draws from the first ceiling").isEqualTo(1_000L);
    }

    /// Mutation: drop the 250 ms floor (`Math.max`), or draw above the ceiling (`nextLong(ceiling * 2)`).
    @Test
    void delay_isNeverBelowTheFloor_andNeverAboveTheCeiling() {
        for (var node = 0; node < 50; node++) {
            for (var refusals = 1; refusals <= 8; refusals++) {
                var delay = BusyBackoff.delayMillis("node-" + node, refusals);

                assertThat(delay).isBetween(BusyBackoff.MIN_DELAY_MILLIS, Math.max(BusyBackoff.MIN_DELAY_MILLIS, BusyBackoff.ceilingMillis(refusals)));
            }
        }
    }

    /// Full jitter is per node, so a synchronized restart does not re-synchronize its retries. Mutation: ignore the node id in the seed.
    @Test
    void delay_differsAcrossNodes_andIsRepeatableForOne() {
        var delays = IntStream.range(0, 50).mapToObj(node -> BusyBackoff.delayMillis("node-" + node, 5)).distinct().count();

        assertThat(delays).as("fifty nodes at the same attempt spread over the range").isGreaterThan(25);
        assertThat(BusyBackoff.delayMillis("node-7", 3)).as("the same node and attempt draw the same delay").isEqualTo(BusyBackoff.delayMillis("node-7", 3));
    }
}
