// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Core load levelling (B-061): one move per pass, after the hysteresis, from the most-loaded core hosting the artifact to the
/// least-loaded core not hosting it.
class GapRebalanceTest {
    private static final Artifact A = artifact("org.example:a:1.0.0");
    private static final Artifact B = artifact("org.example:b:1.0.0");
    private static final Artifact C = artifact("org.example:c:1.0.0");

    /// Core-1 hosts all three one-instance artifacts; cores 2 and 3 host nothing. Every row is settled.
    private FsmHarness lopsided() {
        var h = threeCores();

        for (var artifact : new Artifact[]{A, B, C}) {
            seedTarget(h, artifact, 1, 1);
            seedGoal(h, CORE_1, artifact, SliceGoal.ACTIVE, G1);
            seedRow(h, CORE_1, artifact, SliceObservedState.ACTIVE, G1, 11L);
        }

        return h;
    }

    /// Red when: a move is made on the first imbalanced tick (no hysteresis), more than one move is made in one pass (no budget),
    /// or the move is not "load the target, unload the donor" for the same artifact (B-061).
    @Test
    void imbalance_movesOneArtifactPerPass_afterTheHysteresis() {
        var h = lopsided();

        h.activate();
        assertThat(h.submits()).as("first imbalanced tick: hysteresis, no move").isEmpty();
        assertThat(h.model().imbalancedTicks.size()).isEqualTo(3);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.submits()).as("the second tick moves exactly one artifact: a load and an unload").hasSize(2);
        assertThat(goalOf(h, CORE_1, A)).isEqualTo(Option.some(SliceGoal.UNLOADED));
        assertThat(goalOf(h, CORE_2, A)).as("the least-loaded core, ties by id")
                  .isEqualTo(Option.some(SliceGoal.LOADED));
        assertThat(goalOf(h, CORE_1, B)).as("B waits for the next pass (budget)")
                  .isEqualTo(Option.some(SliceGoal.ACTIVE));
    }

    /// Red when: a balanced cluster (spread within one) is moved.
    @Test
    void balanced_neverMoves() {
        var h = threeCores();

        for (var artifact : new Artifact[]{A, B, C}) {
            seedTarget(h, artifact, 1, 1);
        }

        seedGoal(h, CORE_1, A, SliceGoal.ACTIVE, G1);
        seedRow(h, CORE_1, A, SliceObservedState.ACTIVE, G1, 11L);
        seedGoal(h, CORE_2, B, SliceGoal.ACTIVE, G1);
        seedRow(h, CORE_2, B, SliceObservedState.ACTIVE, G1, 12L);
        seedGoal(h, CORE_3, C, SliceGoal.ACTIVE, G1);
        seedRow(h, CORE_3, C, SliceObservedState.ACTIVE, G1, 13L);
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.submits()).isEmpty();
    }

    /// Red when: an instance hosted by a node outside the allocatable cores (a worker) is chosen as donor: a rebalance never
    /// pulls a placement off a worker.
    @Test
    void aWorkerHost_isNeverADonor() {
        var h = harness(CORE_1, CORE_2);
        var worker = node("worker-1");

        seedTarget(h, A, 1, 1);
        seedGoal(h, worker, A, SliceGoal.ACTIVE, G1);
        seedRow(h, worker, A, SliceObservedState.ACTIVE, G1, 21L);
        seedTarget(h, B, 1, 1);
        seedGoal(h, CORE_1, B, SliceGoal.ACTIVE, G1);
        seedRow(h, CORE_1, B, SliceObservedState.ACTIVE, G1, 11L);
        seedTarget(h, C, 1, 1);
        seedGoal(h, CORE_1, C, SliceGoal.ACTIVE, G1);
        seedRow(h, CORE_1, C, SliceObservedState.ACTIVE, G1, 11L);
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());
        assertThat(goalOf(h, worker, A)).as("the worker's instance stays").isEqualTo(Option.some(SliceGoal.ACTIVE));
    }
}
