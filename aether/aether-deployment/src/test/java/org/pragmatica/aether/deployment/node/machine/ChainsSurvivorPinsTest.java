// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;

/// Pins for `Chains.loadedAfterCleanup`. Each test names the mutation that must turn it red.
class ChainsSurvivorPinsTest {
    /// Red when: the EVICT test becomes `step != LocalStep.EVICT` (machine-39): a dropped chain whose cleanup is UNPUBLISH would
    /// mark the still-loaded slice as gone from the store.
    @Test
    @DisplayName("N6: a failed activation unpublishes but leaves the slice recorded as loaded")
    void failChain_keepsTheSliceLoaded_whenOnlyTheRegistrationsAreUnwound() {
        var h = new NodeHarness().script(LocalStep.ACTIVATE, LocalOutcome.failed(Causes.cause("activation refused")));

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        assertThat(h.localSteps()).as("precondition: the chain loaded, tried to activate and unwound the registrations")
                                  .contains(LocalStep.LOAD, LocalStep.ACTIVATE, LocalStep.UNPUBLISH);
        assertThat(h.model.isLoaded(ARTIFACT)).as("UNPUBLISH is not EVICT: the slice stays in the store").isTrue();
    }
}
