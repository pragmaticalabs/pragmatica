// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1050 — `isSurplusTrim` decides which drains the grace backstop re-checks (R1′). Pinned over EVERY constant:
/// verify-1057 N1 found that flipping `OVERPROVISION_SCALE_DOWN` out, or `OPERATOR_COMMAND` in, left the whole
/// module green, because no production `drainNode` caller passes either reason yet.
class DrainReasonTest {
    @Test
    void isSurplusTrim_isTrueExactlyForTheOverprovisionReasons() {
        assertThat(Arrays.stream(DrainReason.values())
                         .filter(DrainReason::isSurplusTrim)).containsExactlyInAnyOrder(DrainReason.OVERPROVISION_SCALE_DOWN,
                                                                                         DrainReason.OVERPROVISION_PARTITION_HEAL);
    }
}
