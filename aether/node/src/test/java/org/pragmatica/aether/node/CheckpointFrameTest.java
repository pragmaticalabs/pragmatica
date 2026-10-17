// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;

import static org.assertj.core.api.Assertions.assertThat;

/// #2087 B4-cut: two copies that retain different parts of a checkpoint's window compare over the records both hold.
class CheckpointFrameTest {
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 2L, 1L);

    private static RawEvent event(long offset, String data) {
        return new RawEvent(offset, data.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0L);
    }

    private static byte[] identity(Epoch epoch, RawEvent... events) {
        return CheckpointFrame.lineageOf(List.of(events), epoch).unwrap();
    }

    @Test
    void identities_overDifferentPartsOfTheWindow_agreeWhenTheRecordsBothHoldAreTheSame() {
        var full = identity(E1, event(5, "a"), event(6, "b"), event(7, "c"));
        var tail = identity(E1, event(6, "b"), event(7, "c"));

        assertThat(CheckpointFrame.agree(full, tail)).isTrue();
        assertThat(CheckpointFrame.agree(tail, full)).isTrue();
    }

    @Test
    void aRecordBothHold_withDifferentBytes_isADifferentLineage_evenUnderTheSameEpoch() {
        var recorded = identity(E1, event(5, "a"), event(6, "b"), event(7, "c"));
        var local = identity(E1, event(6, "B"), event(7, "c"));

        assertThat(CheckpointFrame.agree(recorded, local)).isFalse();
    }

    @Test
    void theOwnerEpoch_andTheClaimOffset_areComparedToo() {
        var recorded = identity(E1, event(6, "b"), event(7, "c"));

        assertThat(CheckpointFrame.agree(recorded, identity(E2, event(6, "b"), event(7, "c")))).as("another owner epoch").isFalse();
        assertThat(CheckpointFrame.agree(recorded, identity(E1, event(5, "b"), event(6, "c")))).as("another claim").isFalse();
    }

    @Test
    void anEmptyOrMalformedIdentity_agreesWithNothing() {
        var held = identity(E1, event(7, "c"));

        assertThat(CheckpointFrame.agree(new byte[0], held)).isFalse();
        assertThat(CheckpointFrame.agree(held, new byte[0])).isFalse();
        assertThat(CheckpointFrame.agree(new byte[0], new byte[0])).isFalse();
        assertThat(CheckpointFrame.agree(held, java.util.Arrays.copyOf(held, held.length - 1))).isFalse();
    }
}
