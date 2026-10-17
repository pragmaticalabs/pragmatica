// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.lang.Option;


/// What a consumer read of one partition can reach right now (#1333): the earliest offset still
/// retained and the last VISIBLE offset. Read from the ring that holds the partition — locally, or on
/// the owner through the read-forward path, where the two longs ride every `ReadForwardResponse`.
/// `visibleHead` is `-1` while nothing is visible; [#isEmpty] is that case, and [#absent] is the wire
/// form of "the serving node holds no ring" (`-1/-1`), which [#of] maps back to [Option#none].
public record VisibleBounds(long earliestRetained, long visibleHead) {
    public static final long NONE = -1L;

    public static VisibleBounds visibleBounds(long earliestRetained, long visibleHead) {
        return new VisibleBounds(earliestRetained, visibleHead);
    }

    public static VisibleBounds absent() {
        return new VisibleBounds(NONE, NONE);
    }

    /// Decode the wire pair: `-1/-1` is "no ring", anything else is a real answer.
    public static Option<VisibleBounds> of(long earliestRetained, long visibleHead) {
        return earliestRetained == NONE && visibleHead == NONE
               ? Option.none()
               : Option.some(new VisibleBounds(earliestRetained, visibleHead));
    }

    public boolean isEmpty() {
        return visibleHead < 0 || visibleHead < earliestRetained;
    }
}
