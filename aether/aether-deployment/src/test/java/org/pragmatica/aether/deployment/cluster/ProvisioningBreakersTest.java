// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProvisioningBreakersTest {
    private final ProvisioningBreakers breakers = new ProvisioningBreakers();

    /// The refusal window is its own state: it is open strictly before `untilMs`, never counted as a failure, and closed by a reset.
    @Test
    void refusalWindow_isOpenUntilItsInstant_neverCounts_andResetsClearIt() {
        breakers.refuseUntil("eu-1", 1_000L);

        assertThat(breakers.isRefused("eu-1", 999L)).isTrue();
        assertThat(breakers.isRefused("eu-1", 1_000L)).as("the instant itself is outside the window").isFalse();
        assertThat(breakers.isRefused("eu-2", 0L)).as("keyed by source").isFalse();
        assertThat(breakers.snapshot()).as("a refusal is not a breaker failure").isEmpty();

        breakers.reset("eu-1");
        assertThat(breakers.isRefused("eu-1", 0L)).isFalse();

        breakers.refuseUntil("a", 1_000L);
        breakers.refuseUntil("b", 1_000L);
        breakers.resetAll();
        assertThat(breakers.isRefused("a", 0L) || breakers.isRefused("b", 0L)).isFalse();
    }

    /// The window is half-open: a breaker at the cap is open strictly before `nextAllowedMs`, and the instant itself admits the probe.
    @Test
    void isOpen_isStrictlyBeforeTheWindowEnd_andNeverBelowTheCap() {
        breakers.recordFailure("eu-1", 2, 100L, 50L);
        assertThat(breakers.isOpen("eu-1", 2, 100L)).as("one failure is below the cap").isFalse();

        breakers.recordFailure("eu-1", 2, 100L, 50L);

        assertThat(breakers.isOpen("eu-1", 2, 149L)).as("inside the window").isTrue();
        assertThat(breakers.isOpen("eu-1", 2, 150L)).as("at the window end the probe is admitted").isFalse();
        assertThat(breakers.isOpen("eu-1", 2, 151L)).as("after the window").isFalse();
    }

    /// The snapshot is in name order whatever the order the failures arrived in.
    @Test
    void snapshot_isInNameOrder_whateverTheOrderOfArrival() {
        var names = new ArrayList<String>();

        for (var i = 11; i >= 0; i--) {
            names.add("source-" + i);
            breakers.recordFailure("source-" + i, 3, 0L, 1L);
        }
        List<String> sorted = names.stream().sorted().toList();

        assertThat(List.copyOf(breakers.snapshot().keySet())).isEqualTo(sorted);
    }
}
