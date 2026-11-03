// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.Submitted;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Guards of `Outcomes` that the mutation battery left unpinned (lane fsmf1). Each test names the line whose operator it holds.
/// `restore`'s `return true` and `rollback`'s empty-blueprint-or-empty-apply guard are not pinned: the first result is discarded by its
/// only caller (`restoration`'s `previous.fold`), and the second guard is unreachable because `openApplies` already requires both records.
class OutcomesSurvivorPinsTest {
    private static final String ATTEMPT = "a1";

    private static ClusterModel withApply(String blueprintAttempt, String applyAttempt) {
        var m = new ClusterModel();
        var value = blueprint("org.example:app:1.0.0", false, blueprintAttempt, slice(ORDERS, 1, 1, Set.of()));
        var id = value.blueprint().id();

        Indexer.indexBlueprint(m, id, value);
        Indexer.indexApply(m, id, new DeploymentApplyValue(applyAttempt, Option.none(), 0L));

        return m;
    }

    private static BlueprintId idOf(ClusterModel m) {
        return m.blueprints.keys().getFirst();
    }

    /// Red when: an apply is outstanding if it is open OR of the committed attempt (`||` instead of `&&`): an open apply of a superseded
    /// attempt, or a closed apply of the current one, counts as outstanding.
    @Test
    void applyOutstanding_needsAnOpenApply_ofTheCommittedAttempt() {
        assertThat(Outcomes.applyOutstanding(withApply(ATTEMPT, ATTEMPT), ORDERS)).as("open, current attempt").isTrue();
        assertThat(Outcomes.applyOutstanding(withApply("a2", ATTEMPT), ORDERS)).as("open, superseded attempt").isFalse();

        var closed = withApply(ATTEMPT, ATTEMPT);

        Indexer.indexOutcome(closed, idOf(closed), DeploymentOutcomeValue.succeeded(1L, 1L, ATTEMPT));
        assertThat(Outcomes.applyOutstanding(closed, ORDERS)).as("closed, current attempt").isFalse();
    }

    private static ClusterModel deployed() {
        var m = withApply(ATTEMPT, ATTEMPT);
        var pair = SlicePair.slicePair(CORE_1, ORDERS);

        m.seams = Seams.UNWIRED.withCountedCores(Set.of(CORE_1));
        Indexer.indexDesired(m, SurvivorPinSupport.ctx(1_000L, 0L), pair, desired(SliceGoal.ACTIVE, gen(1)));
        m.observed = m.observed.put(pair, row(SliceObservedState.ACTIVE, 1L, gen(1), process(1L)));
        m.observedByArtifact = Idx.add(m.observedByArtifact, ORDERS, CORE_1);

        return m;
    }

    private static Submitted submitted(ClusterModel m, SubmitKind kind) {
        var outcomeKey = new DeploymentOutcomeKey(idOf(m));
        var draft = TxDraft.draft(kind, outcomeKey, List.of());

        return new Submitted("c1", kind, List.of(draft), 1L, new LeaderValue(FsmHarness.SELF, 1L), 0L);
    }

    private static long succeededDrafts(ClusterModel m) {
        return m.outbox.stream().filter(draft -> draft.kind() == SubmitKind.SUCCEEDED).count();
    }

    /// Control: a deployed open apply with nothing in flight gets its SUCCEEDED verdict queued.
    @Test
    void sweep_queuesTheVerdict_whenNothingIsInFlight() {
        var m = deployed();

        Outcomes.sweep(m, SurvivorPinSupport.ctx(1_000L, 0L));

        assertThat(succeededDrafts(m)).isEqualTo(1);
    }

    /// Red when: only a draft that is NOT a SUCCEEDED counts as the verdict in flight (`!=` instead of `==`): a submitted SUCCEEDED is
    /// then written again by every sweep.
    @Test
    void sweep_doesNotQueueTheVerdictAgain_whileOneIsSubmitted() {
        var m = deployed();

        m.submitted = m.submitted.put("c1", submitted(m, SubmitKind.SUCCEEDED));
        Outcomes.sweep(m, SurvivorPinSupport.ctx(1_000L, 0L));

        assertThat(succeededDrafts(m)).isZero();
    }

    /// Red when: the same operator makes any other kind of draft on the outcome key block the verdict.
    @Test
    void sweep_queuesTheVerdict_whileAnotherKindIsSubmittedOnTheSameKey() {
        var m = deployed();

        m.submitted = m.submitted.put("c1", submitted(m, SubmitKind.ROLLBACK));
        Outcomes.sweep(m, SurvivorPinSupport.ctx(1_000L, 0L));

        assertThat(succeededDrafts(m)).isEqualTo(1);
    }

    /// Red when: only a queued draft that is NOT a SUCCEEDED counts as the verdict in flight (`!=` instead of `==` in the outbox half): a
    /// SUCCEEDED already queued is queued again by the next sweep.
    @Test
    void sweep_doesNotQueueTheVerdictAgain_whileOneIsQueued() {
        var m = deployed();

        m.outbox = List.of(submitted(m, SubmitKind.SUCCEEDED).drafts().getFirst());
        Outcomes.sweep(m, SurvivorPinSupport.ctx(1_000L, 0L));

        assertThat(succeededDrafts(m)).as("only the one already queued").isEqualTo(1);
    }

    /// Red when: the same operator makes any other kind of queued draft on the outcome key block the verdict.
    @Test
    void sweep_queuesTheVerdict_whileAnotherKindIsQueuedOnTheSameKey() {
        var m = deployed();

        m.outbox = List.of(submitted(m, SubmitKind.ROLLBACK).drafts().getFirst());
        Outcomes.sweep(m, SurvivorPinSupport.ctx(1_000L, 0L));

        assertThat(succeededDrafts(m)).isEqualTo(1);
    }
}
