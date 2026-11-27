// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #3853: pins [SlotPasses]'s own contract, which every caller depends on and none of them can observe going wrong: the extra pass
/// after the count-th read is sent must send nothing, and the result is that pass's, not the winning pass's.
class SlotPassesTest {
    private static final Duration DEADLINE = Duration.ofSeconds(5);

    /// Mutants: the "sends nothing" assertion dropped (s4), and `before` read after the pass instead of before it (s5).
    @Test
    void passUntilSent_extraPassSendsARead_failsTheContract() {
        var sent = new AtomicInteger();

        assertThatThrownBy(() -> SlotPasses.passUntilSent(sent::incrementAndGet, sent::get, 1, DEADLINE)).isInstanceOf(AssertionError.class)
                                                                                                          .hasMessageContaining("sends nothing");
    }

    @Test
    void passUntilSent_extraPassSendsNothing_returnsTheExtraPassesResult() {
        var sent = new AtomicInteger();
        var passes = new AtomicInteger();

        var result = SlotPasses.passUntilSent(() -> sendOnceThenCount(sent, passes), sent::get, 1, DEADLINE);

        assertThat(result).describedAs("pass 1 wins the slot, pass 2 is the extra pass").isEqualTo(2);
        assertThat(sent.get()).isEqualTo(1);
    }

    @Test
    void passUntilSent_readSentOnlyByALaterPass_keepsPassingUntilItIsSent() {
        var sent = new AtomicInteger();
        var passes = new AtomicInteger();

        var result = SlotPasses.passUntilSent(() -> sendOnThirdPass(sent, passes), sent::get, 1, DEADLINE);

        assertThat(result).describedAs("passes 1-2 send nothing, pass 3 sends, pass 4 is the extra pass").isEqualTo(4);
        assertThat(sent.get()).isEqualTo(1);
    }

    private static int sendOnceThenCount(AtomicInteger sent, AtomicInteger passes) {
        sent.compareAndSet(0, 1);
        return passes.incrementAndGet();
    }

    private static int sendOnThirdPass(AtomicInteger sent, AtomicInteger passes) {
        var pass = passes.incrementAndGet();

        if (pass == 3) {
            sent.incrementAndGet();
        }
        return pass;
    }
}
