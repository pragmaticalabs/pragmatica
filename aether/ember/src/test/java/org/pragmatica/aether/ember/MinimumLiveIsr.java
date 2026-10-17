// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.pragmatica.lang.Option;

/// The smallest live-ISR size seen across a sequence of samples of the committed ownership record.
///
/// A sample is `none` while the live nodes disagree on the record, which is the normal state mid-ISR-transition (one node
/// has applied v5, another still holds v4). That is no reading of the ISR at all, so it is skipped: scoring it as an ISR of
/// size 0 reported a shrunken ISR that never existed. [#taken] counts the samples that were real, so a test can refuse to
/// pass on none.
final class MinimumLiveIsr {
    private int minimum = Integer.MAX_VALUE;
    private int taken;

    /// Takes one sample: the live ISR size the nodes agree on, or none when they disagree.
    void sample(Option<Long> liveIsrSize) {
        liveIsrSize.onPresent(this::take);
    }

    private void take(long size) {
        minimum = (int) Math.min(minimum, size);
        taken++;
    }

    int minimum() {
        return minimum;
    }

    /// Samples that carried a reading; disagreeing samples are not counted.
    int taken() {
        return taken;
    }
}
