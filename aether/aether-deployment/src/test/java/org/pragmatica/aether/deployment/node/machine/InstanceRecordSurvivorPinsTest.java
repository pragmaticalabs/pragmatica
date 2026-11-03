// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;

/// Pins for `InstanceRecord.executed`. Each test names the mutation that must turn it red.
class InstanceRecordSurvivorPinsTest {
    /// Red when: `chainGeneration == g || appliedGeneration == g` becomes `&&` (machine-67): either source alone must count.
    @Test
    @DisplayName("executed: a generation seen by the latest chain OR applied by the last completed one counts")
    void executed_isTrue_forEitherSourceAlone() {
        var blank = InstanceRecord.instanceRecord();

        assertThat(blank.withChainGeneration(Option.some(G1)).executed(G1)).as("chain only").isTrue();
        assertThat(blank.withAppliedGeneration(Option.some(G1)).executed(G1)).as("applied only").isTrue();
        assertThat(blank.withChainGeneration(Option.some(G1)).withAppliedGeneration(Option.some(G1)).executed(G1)).as("both").isTrue();
        assertThat(blank.withChainGeneration(Option.some(G2)).withAppliedGeneration(Option.some(G2)).executed(G1)).as("another generation").isFalse();
        assertThat(blank.executed(G1)).as("neither").isFalse();
    }
}
