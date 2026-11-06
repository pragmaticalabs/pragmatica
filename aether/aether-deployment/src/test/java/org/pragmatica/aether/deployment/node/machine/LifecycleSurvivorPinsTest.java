// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;

/// Pins for `Lifecycle.isServing`: the quorum loss unpublishes an instance only when ALL of serving, no chain and a current
/// ACTIVE row hold. Each test names the mutation that must turn it red.
class LifecycleSurvivorPinsTest {
    private static Transition lose(NodeHarness h, InstanceRecord rec) {
        return Lifecycle.disappeared(h.fsm, h.model.withInstance(ARTIFACT, rec));
    }

    /// Red when: `rec.serving() && rec.chain().isEmpty()` weakens to `||` (machine-52): a serving flag over a row that is not
    /// ACTIVE would then be unpublished.
    @Test
    @DisplayName("spec 3.4: a serving flag over a row that is not ACTIVE is not unpublished on a quorum loss")
    void disappeared_doesNotSuspend_servingInstanceWhoseRowIsNotActive() {
        var h = new NodeHarness().establish();
        var rec = InstanceRecord.instanceRecord()
                                .withObserved(Option.some(SurvivorRows.row(h, SliceObservedState.LOADED, 2L, G1)))
                                .withServing(true);
        var step = lose(h, rec);

        assertThat(step.effects()).as("no SUSPEND is emitted").noneMatch(e -> e instanceof NodeFsmEffect.LocalOp op && op.step() == LocalStep.SUSPEND);
        assertThat(step.model().instance(ARTIFACT).suspended()).isFalse();
        assertThat(step.model().instance(ARTIFACT).serving()).as("the flag is kept").isTrue();
    }

    /// Red when: `(serving && chain.isEmpty()) && current ACTIVE row` weakens to `||` on the last operand (machine-92): an ACTIVE
    /// row with no serving flag would then be unpublished.
    @Test
    @DisplayName("spec 3.4: an ACTIVE row whose instance does not serve is not unpublished on a quorum loss")
    void disappeared_doesNotSuspend_activeRowWithoutServingFlag() {
        var h = new NodeHarness().establish();
        var rec = InstanceRecord.instanceRecord()
                                .withObserved(Option.some(SurvivorRows.row(h, SliceObservedState.ACTIVE, 2L, G1)))
                                .withServing(false);
        var step = lose(h, rec);

        assertThat(step.effects()).as("no SUSPEND is emitted").noneMatch(e -> e instanceof NodeFsmEffect.LocalOp op && op.step() == LocalStep.SUSPEND);
        assertThat(step.model().instance(ARTIFACT).suspended()).isFalse();
    }

    /// Control for both: the instance that serves over a current ACTIVE row IS unpublished.
    @Test
    @DisplayName("spec 3.4: a serving instance over a current ACTIVE row is unpublished and carried as suspended")
    void disappeared_suspends_servingInstanceOverActiveRow() {
        var h = new NodeHarness().establish();
        var rec = InstanceRecord.instanceRecord()
                                .withObserved(Option.some(SurvivorRows.row(h, SliceObservedState.ACTIVE, 2L, G1)))
                                .withServing(true);
        var step = lose(h, rec);

        assertThat(step.effects()).anyMatch(e -> e instanceof NodeFsmEffect.LocalOp op && op.step() == LocalStep.SUSPEND);
        assertThat(step.model().instance(ARTIFACT).suspended()).isTrue();
    }
}
