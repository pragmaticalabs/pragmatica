// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;


class PortBlockTest {
    @Test
    void checkSlots_poolAboveTheBlock_isRefusedNamingBothNumbers() {
        var failure = failureOf(32);

        assertThat(failure).isInstanceOf(PortError.SlotPoolTooLarge.class);
        assertThat(failure.message()).contains("32").contains("28");
    }

    @Test
    void checkSlots_pools_fitUpToTheBlock_andMustBePositive() {
        assertThat(PortBlock.checkSlots(28).isSuccess()).isTrue();
        assertThat(PortBlock.checkSlots(1).isSuccess()).isTrue();
        assertThat(failureOf(0)).isInstanceOf(PortError.SlotsNotPositive.class);
        assertThat(PortBlock.checkSlots(29).isFailure()).isTrue();
    }

    @Test
    void layout_everySlotKindStaysInsideTheStride() {
        var block = PortBlock.portBlock(20_000, PortBlock.MAX_SLOTS).unwrap();

        assertThat(PortBlock.STRIDE).isEqualTo(128);
        assertThat(block.ports().count()).isEqualTo(128);
        assertThat(block.quicBase() + PortBlock.MAX_SLOTS - 1).isLessThan(block.mgmtBase());
        assertThat(block.mgmtBase() + PortBlock.MAX_SLOTS - 1).isLessThan(block.appBase());
        assertThat(block.appBase() + PortBlock.MAX_SLOTS - 1).isLessThan(block.swimBase());
        assertThat(block.swimBase() + PortBlock.MAX_SLOTS - 1).isEqualTo(20_000 + 127);
    }

    private static Cause failureOf(int slots) {
        return PortBlock.checkSlots(slots).fold(cause -> cause, _ -> null);
    }
}
