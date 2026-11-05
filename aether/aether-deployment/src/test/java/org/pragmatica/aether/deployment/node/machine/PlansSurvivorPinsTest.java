// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;

/// Pins for `Plans.isExecuted`. Each test names the mutation that must turn it red.
class PlansSurvivorPinsTest {
    private static Plan decide(NodeHarness h, InstanceRecord rec, SliceDesiredValue desired) {
        return Plans.decide(h.fsm, h.model, ARTIFACT, rec, desired);
    }

    /// Red when: `row.forGeneration().equals(generation) || rec.executed(generation)` becomes `&&` (machine-30): a FAILED row
    /// for the goal's generation, written by a process whose record never ran it, would start a new chain.
    @Test
    @DisplayName("N15: a FAILED row for the goal's generation settles even when this record never executed it")
    void decide_settles_failedRowForTheGeneration_withoutALocalExecution() {
        var h = new NodeHarness();
        var rec = InstanceRecord.instanceRecord().withObserved(Option.some(SurvivorRows.row(h, SliceObservedState.FAILED, 2L, G1)));

        assertThat(decide(h, rec, new SliceDesiredValue(SliceGoal.LOADED, G1, h.wall))).isInstanceOf(Plan.Settled.class);
    }

    /// Red when: the same `||` becomes `&&` (machine-30): a generation this record executed settles on a FAILED row written
    /// for an earlier generation.
    @Test
    @DisplayName("N15: a FAILED row of an earlier generation settles when this record executed the goal's generation")
    void decide_settles_failedRow_whenTheRecordExecutedTheGoalGeneration() {
        var h = new NodeHarness();
        var rec = InstanceRecord.instanceRecord()
                                .withObserved(Option.some(SurvivorRows.row(h, SliceObservedState.FAILED, 2L, G1)))
                                .withChainGeneration(Option.some(G2));

        assertThat(decide(h, rec, new SliceDesiredValue(SliceGoal.LOADED, G2, h.wall))).isInstanceOf(Plan.Settled.class);
    }

    /// Control: neither the row nor the record covers the goal's generation, so the FAILED row starts a new load.
    @Test
    @DisplayName("N15: a FAILED row of an earlier generation, never executed here, starts a new chain")
    void decide_runs_failedRow_ofAnEarlierGenerationNobodyExecuted() {
        var h = new NodeHarness();
        var rec = InstanceRecord.instanceRecord().withObserved(Option.some(SurvivorRows.row(h, SliceObservedState.FAILED, 2L, G1)));

        assertThat(decide(h, rec, new SliceDesiredValue(SliceGoal.LOADED, G2, h.wall))).isInstanceOf(Plan.Run.class);
    }
}
