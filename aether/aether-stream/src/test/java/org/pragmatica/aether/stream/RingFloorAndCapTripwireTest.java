// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// T-08 (stream storage design §9.1, S0): pins the ring floor and cap arithmetic at the read SHA.
/// Floor = header (64) + index (24 B per entry x capacity) + the first data chunk `min(256 KiB, hot bytes)`;
/// cap = header + index + the whole hot window. S2b changes the floor term (the first-chunk unit), which is why the
/// floor arms name S2b and T-218; the cap arm names no step, because none changes the cap.
class RingFloorAndCapTripwireTest {
    private static final long HOT_ENTRIES = 16_384L;
    private static final long FOUR_MIB = 4L * 1024 * 1024;
    private static final long KIB_128 = 128L * 1024;
    private static final String FLOOR_MESSAGE = "ring floor changed. If S2b changed firstSegmentBytes: enable T-218 and update this floor arm. "
                                                + "If the header (64) or the index entry size (24) changed, no planned step does that: re-derive every row";
    private static final String CAP_MESSAGE = "ring cap changed (64 + 24 x entries + the hot window): no planned step changes the cap "
                                              + "(S2b changes the floor term only), so re-derive every row";

    @Test
    void ringFloorAndCapAtHotDefaults() {
        assertThat(OffHeapRingBuffer.floorBytes(HOT_ENTRIES, FOUR_MIB)).as(FLOOR_MESSAGE + " (floor at 16 384 entries, 4 MiB)")
                                                                      .isEqualTo(655_424L);
        assertThat(OffHeapRingBuffer.capBytes(HOT_ENTRIES, FOUR_MIB)).as(CAP_MESSAGE + " (cap at 16 384 entries, 4 MiB)")
                                                                     .isEqualTo(4_587_584L);
        assertThat(OffHeapRingBuffer.floorBytes(HOT_ENTRIES, KIB_128)).as(FLOOR_MESSAGE + " (floor arm at hot.max_bytes = 128 KiB, the min term)")
                                                                     .isEqualTo(524_352L);
    }
}
