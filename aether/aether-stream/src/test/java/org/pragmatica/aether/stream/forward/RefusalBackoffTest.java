// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.ArrayList;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// #2574 (CTO, know 89e17399a9): the refused-read hold. Pins, each red when mutated: the doubling from 250 ms, the 4 s cap
/// (and the 4.8 s jittered bound under the 5 s CF2 ack timeout), the reset on a sent read, the +-20% jitter bound, and the
/// keys' independence. Jitter draws from a seeded source.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RefusalBackoffTest {
    private static final long CF2_ACK_TIMEOUT_MS = 5_000L;
    private static final long[] NOMINAL_MS = {250, 500, 1_000, 2_000, 4_000, 4_000, 4_000};

    /// No jitter (the source answers the midpoint): the bare schedule.
    private static RefusalBackoff<String> unjittered() {
        return RefusalBackoff.refusalBackoff(() -> 0.5);
    }

    @Test
    void refused_doublesFrom250ms_andCapsAt4s() {
        var backoff = unjittered();
        var holds = new ArrayList<Long>();

        for (var ignored : NOMINAL_MS) {
            holds.add(backoff.refused("p").millis());
        }

        assertThat(holds).containsExactly(250L, 500L, 1_000L, 2_000L, 4_000L, 4_000L, 4_000L);
    }

    /// The bound the cap is for: after the lane recovers a held key waits out at most its current hold, which must stay
    /// under the 5 s CF2 ack timeout even with the full upward jitter (4 s x 1.2 = 4.8 s).
    @Test
    void longestHold_isTheCapWithFullJitter_andStaysUnderTheCf2AckTimeout() {
        var backoff = RefusalBackoff.<String>refusalBackoff(() -> Math.nextDown(1.0));
        var longest = 0L;

        for (var attempt = 0; attempt < 20; attempt++) {
            longest = Math.max(longest, backoff.refused("p").millis());
        }

        assertThat(RefusalBackoff.LONGEST_HOLD.millis()).isEqualTo(4_800L);
        assertThat(longest).as("never above the jittered cap").isLessThanOrEqualTo(RefusalBackoff.LONGEST_HOLD.millis());
        assertThat(longest).as("the cap is reached").isGreaterThanOrEqualTo(4_790L);
        assertThat(RefusalBackoff.LONGEST_HOLD.millis()).as("under the CF2 ack timeout").isLessThan(CF2_ACK_TIMEOUT_MS);
    }

    @Test
    void sent_resetsTheKeyToTheFirstHold() {
        var backoff = unjittered();

        backoff.refused("p");
        backoff.refused("p");
        backoff.refused("p");
        backoff.sent("p");

        assertThat(backoff.refused("p").millis()).as("a sent read resets the doubling").isEqualTo(250L);
    }

    /// Each hold is within +-20% of its nominal value, and the jitter actually spreads holds across that range (a seeded
    /// source), so keys do not re-send in lockstep.
    @Test
    void refused_isJitteredWithinPlusMinus20Percent_andSpreadsAcrossIt() {
        var backoff = RefusalBackoff.<Integer>refusalBackoff(new SplittableRandom(2574L)::nextDouble);
        var lowest = Double.MAX_VALUE;
        var highest = 0.0;

        for (var key = 0; key < 500; key++) {
            for (var nominal : NOMINAL_MS) {
                var ratio = backoff.refused(key).millis() / (double) nominal;

                assertThat(ratio).as("key %d, nominal %d ms", key, nominal).isBetween(0.799, 1.201);
                lowest = Math.min(lowest, ratio);
                highest = Math.max(highest, ratio);
            }
        }

        assertThat(lowest).as("jitter reaches near -20%").isLessThan(0.82);
        assertThat(highest).as("jitter reaches near +20%").isGreaterThan(1.18);
    }

    @Test
    void keys_holdIndependently() {
        var backoff = unjittered();

        backoff.refused("a");
        backoff.refused("a");

        assertThat(backoff.refused("b").millis()).as("another key starts at the first hold").isEqualTo(250L);
        assertThat(backoff.refused("a").millis()).isEqualTo(1_000L);
    }

    @Test
    void constants_areTheRuledShape() {
        assertThat(RefusalBackoff.FIRST_HOLD).isEqualTo(TimeSpan.timeSpan(250).millis());
        assertThat(RefusalBackoff.MAX_HOLD).isEqualTo(TimeSpan.timeSpan(4).seconds());
        assertThat(RefusalBackoff.JITTER).isEqualTo(0.2);
    }
}
