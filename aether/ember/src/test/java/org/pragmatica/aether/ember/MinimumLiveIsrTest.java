// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// The ISR sampling of `EmberOrdinaryFailoverNoFalseAlertTest` (#2084's CI went red at the minimum assertion with actual 0): a
/// sample taken while the nodes disagree on the record is no reading, and must not lower the minimum.
class MinimumLiveIsrTest {
    @Test
    void sample_disagreeingNodes_doesNotLowerTheMinimum_andIsNotCounted() {
        var isr = new MinimumLiveIsr();

        isr.sample(Option.some(2L));
        isr.sample(Option.none());
        isr.sample(Option.some(3L));

        assertThat(isr.minimum()).as("the none sample is not an ISR of size 0").isEqualTo(2);
        assertThat(isr.taken()).isEqualTo(2);
    }

    @Test
    void sample_realShrink_isStillReported() {
        var isr = new MinimumLiveIsr();

        isr.sample(Option.some(2L));
        isr.sample(Option.none());
        isr.sample(Option.some(1L));

        assertThat(isr.minimum()).as("an ISR that really shrank still lowers it").isEqualTo(1);
    }

    @Test
    void sample_onlyDisagreement_takesNothing() {
        var isr = new MinimumLiveIsr();

        isr.sample(Option.none());
        isr.sample(Option.none());

        assertThat(isr.taken()).isZero();
    }
}
