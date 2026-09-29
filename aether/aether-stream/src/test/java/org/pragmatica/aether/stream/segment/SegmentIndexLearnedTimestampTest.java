// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1604/#1616: a timestamp learned after the fact fills only an UNKNOWN one. A segment sealed live already
/// carries its events' time, and a late read must never move it -- an older value would reclaim it early.
class SegmentIndexLearnedTimestampTest {
    @Test
    void recordMaxTimestamp_fillsAnUnknownTimestamp_andNeverOverwritesAKnownOne() {
        var index = new SegmentIndex();

        index.addSegment("orders", 0, 0, 9, 5_000L);
        index.addSegment("orders", 0, 10, 19);

        index.recordMaxTimestamp("orders", 0, 0, 1_000L);
        index.recordMaxTimestamp("orders", 0, 10, 7_000L);

        assertThat(index.listSegments("orders", 0)).extracting(SegmentIndex.SegmentRef::maxTimestamp)
                                                   .as("the known 5000 is kept; the unknown one is filled")
                                                   .containsExactly(5_000L, 7_000L);
    }
}
