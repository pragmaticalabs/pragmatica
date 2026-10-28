// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.PendingGoal;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The second verifier round (v-2174 F19): the batches the spec calls one, the pending goals a committed key answers, and the
/// resubmission cadence of refused target batches.
class VerifierRound2Test {
    private static final String APP = "org.example:app:1.0.0";

    /// Red when: a republish drops the artifact's judgment in a transaction of its own (G-R.1: one batch), so a refused target batch can
    /// leave the mark erased without the republish.
    @Test
    void republish_dropsTheJudgmentInTheTargetTransaction() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);

        h.activate();
        publish(h, blueprint(APP, false, "attempt-1", slice(ORDERS, 1, 1, Set.of())));
        h.seed(new ArtifactJudgmentKey(ORDERS),
               new ArtifactJudgmentValue(true, Map.of(), Map.of()));
        h.drain();
        h.clearLog();
        publish(h, blueprint(APP, false, "attempt-2", slice(ORDERS, 1, 1, Set.of())));
        var targets = h.submits(SubmitKind.TARGETS);

        assertThat(targets).hasSize(1);
        assertThat(targets.getFirst().transaction().mutations().stream().map(mutation -> mutation.key()).toList()).contains(new SliceTargetKey(ORDERS.base()),
                                                                                                                            new ArtifactJudgmentKey(ORDERS));
    }

    /// Red when: the removal of a blueprint writes its judgment separately from its targets and apply record (one batch, G-R.1).
    @Test
    void blueprintRemoval_isOneTransaction() {
        var h = deployed(1);

        h.seed(new ArtifactJudgmentKey(ORDERS),
               new ArtifactJudgmentValue(false, Map.of(CORE_1, 2), Map.of()));
        h.drain();
        h.clearLog();
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
        var removals = h.submits(SubmitKind.BLUEPRINT_REMOVAL);

        assertThat(removals).hasSize(1);
        assertThat(removals.getFirst().transaction().mutations().stream().map(mutation -> mutation.key()).toList()).contains(new SliceTargetKey(ORDERS.base()),
                                                                                                                             new DeploymentApplyKey(blueprintId(APP)),
                                                                                                                             new ArtifactJudgmentKey(ORDERS));
        assertThat(h.submits(SubmitKind.FAILURE)).as("no judgment transaction of its own").isEmpty();
    }

    /// Red when: a refused target batch is resubmitted at once (6.2: one resubmission per bound, by the awaited-target sweep).
    @Test
    void refusedTargetBatch_isNotResubmittedAtOnce() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);

        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, blueprint(APP, false, "attempt-1", slice(ORDERS, 1, 1, Set.of())));
        var first = h.submits(SubmitKind.TARGETS).getFirst();
        var before = h.submits(SubmitKind.TARGETS).size();

        h.held.clear();
        h.settleHeld(first, h.refused(first));
        assertThat(h.submits(SubmitKind.TARGETS)).as("not at once").hasSize(before);
    }

    /// Red when: a committed desired key clears an in-flight goal of another generation: the goal issued later would stop counting as
    /// placed (C11b: "the matching pendingGoals").
    @Test
    void committedDesiredKey_clearsOnlyThePendingGoalOfItsGeneration() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);

        h.activate();
        var model = h.model();
        var pair = SlicePair.slicePair(CORE_1, ORDERS);

        Indexer.putPending(model, pair, new PendingGoal(SliceGoal.LOADED, gen(5)));
        Indexer.clearMatchingPending(model, pair, gen(4));
        assertThat(model.pendingGoals.containsKey(pair)).as("another generation: stays in flight").isTrue();
        Indexer.clearMatchingPending(model, pair, gen(5));
        assertThat(model.pendingGoals.containsKey(pair)).as("its own generation: answered").isFalse();
    }

    /// Red when: a committed removal clears an in-flight placement (only an in-flight UNLOADED goal is answered by a removal).
    @Test
    void committedRemoval_clearsOnlyAnInFlightUnload() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);

        h.activate();
        var model = h.model();
        var pair = SlicePair.slicePair(CORE_1, ORDERS);

        Indexer.putPending(model, pair, new PendingGoal(SliceGoal.LOADED, gen(5)));
        Indexer.clearRemovedPending(model, pair);
        assertThat(model.pendingGoals.containsKey(pair)).as("a placement stays in flight").isTrue();
        Indexer.putPending(model, pair, new PendingGoal(SliceGoal.UNLOADED, gen(6)));
        Indexer.clearRemovedPending(model, pair);
        assertThat(model.pendingGoals.containsKey(pair)).as("the unload the removal answers").isFalse();
    }

    /// Red when: a refused blueprint removal whose keys moved is dropped instead of re-derived at the current values (nothing else
    /// derives it: the blueprint is already unindexed).
    @Test
    void refusedBlueprintRemoval_isRederivedAtTheCurrentValues() {
        var h = deployed(1);

        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.clearLog();
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
        var first = h.submits(SubmitKind.BLUEPRINT_REMOVAL).getFirst();

        h.held.clear();
        h.seed(new ArtifactJudgmentKey(ORDERS),
               new ArtifactJudgmentValue(false, Map.of(CORE_1, 1), Map.of()));
        h.settleHeld(first, h.refused(first));
        assertThat(h.submits(SubmitKind.BLUEPRINT_REMOVAL)).as("resubmitted").hasSizeGreaterThan(1);
        var second = h.submits(SubmitKind.BLUEPRINT_REMOVAL).getLast();

        assertThat(second.transaction()
                         .mutations()
                         .stream()
                         .allMatch(mutation -> mutation.expected()
                                                       .isPresent() && mutation.replacement()
                                                                               .isEmpty())).isTrue();
    }
}
