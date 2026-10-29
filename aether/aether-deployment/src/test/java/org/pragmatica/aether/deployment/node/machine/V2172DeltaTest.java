// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;

/// v-2172 delta pass at bfc99b0e2: kills for two survivors of the delta mutation run.
class V2172DeltaTest {
    /// Kills `d-rearm-full-bound`: the escalation re-armed at entry fires at `since + bound`, not `entry + bound` (B-158 is the
    /// park's age, which the outage does not reset).
    @Test
    @DisplayName("B-158: a park carried across a quorum cycle escalates at since + bound, not at entry + bound")
    void parkCarriedAcrossCycle_escalatesForTheTimeLeft() {
        var h = new NodeHarness();

        h.establish().goal(ARTIFACT, SliceGoal.LOADED, G1);
        h.advance(100_000L).feed(new NodeFsmInput.QuorumDisappeared());
        h.establish();
        h.advance(h.timings.parkedBoundMs() - 100_000L + 5_000L);

        assertThat(h.effectsOf(NodeFsmEffect.Warn.class))
            .as("escalated once the park is older than the bound, counted from its first refusal")
            .anyMatch(warn -> warn.code().equals(OperatorWarningCode.SLICE_START_AWAITING_TARGET.code()));
    }

    /// Kills `r2-remove-keeps-unconfirmed`: a FAILED owed to a goal that was removed must not be written when the leader
    /// re-adopts the committed row at its own generation (R-3.3.11) before the next N19 cycle. With the owed write kept, the
    /// adoption looks like a lagging re-issue (R-3.4.2) and R-6.0 rebuilds the stale FAILED.
    @Test
    @DisplayName("N17 + R-3.3.11: a re-adoption at the same generation does not resurrect a FAILED owed to the removed goal")
    void readoptionAfterRemoval_doesNotWriteTheOwedFailed() {
        var h = new NodeHarness();

        h.establish().permit(ARTIFACT);
        h.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("corrupt jar")));
        h.loseWritesOf(SliceObservedState.FAILED);
        h.goal(ARTIFACT, SliceGoal.LOADED, G1);
        h.advance(h.timings.writeAttemptMs() * h.timings.writeAttempts());
        assertThat(h.record(ARTIFACT).unconfirmed().isPresent()).as("precondition: FAILED owed").isTrue();

        h.removeGoal(ARTIFACT);
        h.script(LocalStep.LOAD, LocalOutcome.done());
        h.writeScript = _ -> null;
        h.clearEffects().goal(ARTIFACT, SliceGoal.LOADED, G1);

        assertThat(h.submitsOf(SliceObservedState.FAILED)).as("the failure died with the removed goal").isEmpty();
        assertThat(h.state(ARTIFACT).or(SliceObservedState.UNKNOWN)).isEqualTo(SliceObservedState.LOADED);
    }
}
