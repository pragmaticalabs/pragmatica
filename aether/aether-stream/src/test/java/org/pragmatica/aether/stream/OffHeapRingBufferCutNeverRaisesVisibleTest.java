// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

/// #2981 S12 (design 2.2 check 3, r6 step 0(c)): `OffHeapRingBuffer.cutHeader` is the one LOWERING writer of the visible
/// position. A cut can only lower it, so it exposes nothing that was not already exposed under a valid lease; a cut that
/// raised it (`max` instead of `min`) would expose records no `ownerAdvance` check admitted.
class OffHeapRingBufferCutNeverRaisesVisibleTest {
    /// Visible is BELOW the cut point: the cut removes only unexposed records, so visible stays where it was.
    @Test
    void cutHeader_withKeepAboveVisible_leavesVisibleUnchanged() {
        try (var ring = offHeapRingBuffer(8, 4096)) {
            appendUnexposed(ring, 6);
            ring.advanceVisible(2);

            var before = ring.visibleOffset();

            assertThat(ring.truncateSuffix(4).unwrap()).isEqualTo(1L);

            assertThat(before).isEqualTo(2L);
            assertThat(ring.visibleOffset()).as("a cut above visible must not raise it").isLessThanOrEqualTo(before)
                                            .isEqualTo(2L);
        }
    }

    /// Visible is ABOVE the cut point: the cut lowers it to the cut point, never to anything higher.
    @Test
    void cutHeader_withKeepBelowVisible_lowersVisibleToKeep() {
        try (var ring = offHeapRingBuffer(8, 4096)) {
            appendUnexposed(ring, 6);
            ring.advanceVisible(5);

            var before = ring.visibleOffset();

            assertThat(ring.truncateSuffix(3).unwrap()).isEqualTo(2L);

            assertThat(ring.visibleOffset()).as("a cut below visible lowers it").isLessThanOrEqualTo(before)
                                            .isEqualTo(3L);
        }
    }

    /// Ordered appends take offsets but expose nothing: visible stays where the test puts it.
    private static void appendUnexposed(OffHeapRingBuffer ring, int count) {
        for (var i = 0; i < count; i++) {
            var offset = ring.appendOrdered("e%d".formatted(i).getBytes(StandardCharsets.UTF_8), i, Result::success).unwrap();

            ring.markDurable(offset);
        }
    }
}
