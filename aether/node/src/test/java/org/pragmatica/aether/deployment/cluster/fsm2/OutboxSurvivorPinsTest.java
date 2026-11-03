// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.ArtifactIntent;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.Submitted;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Comparisons and guards of `Outbox` that the mutation battery left unpinned (lane fsmf1). Each test names the line whose operator it holds.
/// `rederiveByPair`'s `kind == ROLLBACK ? ROLLBACK_RESUBMISSIONS : GOAL_RESUBMISSIONS` is not pinned: both constants are 5 (`Outbox.java:54-55`),
/// so swapping the arms selects the same limit.
class OutboxSurvivorPinsTest {
    private static final SlicePair PAIR = SlicePair.slicePair(CORE_1, ORDERS);
    private static final LeaderValue LEADER = new LeaderValue(FsmHarness.SELF, 1L);

    private static KVCommand.Mutation<AetherKey, AetherValue> desiredPut(Option<AetherValue> expected) {
        return new KVCommand.Mutation<>(new SliceDesiredKey(CORE_1, ORDERS), expected, Option.some(desired(SliceGoal.LOADED, gen(2))));
    }

    private static Option<AetherValue> someDesired() {
        return Option.some(desired(SliceGoal.LOADED, gen(1)));
    }

    // -- :668 moved ---------------------------------------------------------------------------------------------------
    /// Red when: a draft counts as moved only if a mutation AND a guard moved (`&&` instead of `||`): a draft whose guard alone moved is sent
    /// as if nothing had changed under it.
    @Test
    void moved_isTrue_whenOnlyAGuardMoved() {
        var guard = new KVCommand.ReadWitness<AetherKey>(PAIR.observedKey(), Option.<Object> some(row(SliceObservedState.ACTIVE, 1L, gen(1), process(1L))));
        var draft = TxDraft.draft(SubmitKind.GOALS, new SliceDesiredKey(CORE_1, ORDERS), List.of(desiredPut(Option.none()))).withGuards(List.of(guard));

        assertThat(Outbox.moved(new ClusterModel(), draft)).isTrue();
    }

    /// Red when: the same operator ignores a mutation that moved while the guards did not.
    @Test
    void moved_isTrue_whenOnlyAMutationMoved() {
        var guard = new KVCommand.ReadWitness<AetherKey>(PAIR.observedKey(), Option.none());
        var draft = TxDraft.draft(SubmitKind.GOALS, new SliceDesiredKey(CORE_1, ORDERS), List.of(desiredPut(someDesired()))).withGuards(List.of(guard));

        assertThat(Outbox.moved(new ClusterModel(), draft)).isTrue();
    }

    /// Control: nothing moved.
    @Test
    void moved_isFalse_whenNothingMoved() {
        var guard = new KVCommand.ReadWitness<AetherKey>(PAIR.observedKey(), Option.none());
        var draft = TxDraft.draft(SubmitKind.GOALS, new SliceDesiredKey(CORE_1, ORDERS), List.of(desiredPut(Option.none()))).withGuards(List.of(guard));

        assertThat(Outbox.moved(new ClusterModel(), draft)).isFalse();
    }

    // -- :483 expire --------------------------------------------------------------------------------------------------
    /// Red when: a submission is lost only past the expiry (`>` instead of `>=`): at exactly the bound it is still counted in flight.
    @Test
    void expire_releasesASubmission_atExactlyTheBound() {
        var bound = SurvivorPinSupport.settings().submissionExpiry() * 1_000_000L;

        assertThat(submissionsLeftAfterExpiry(bound)).as("at the bound").isZero();
        assertThat(submissionsLeftAfterExpiry(bound - 1)).as("control: one ns before it").isEqualTo(1);
    }

    private static int submissionsLeftAfterExpiry(long ageNs) {
        var m = new ClusterModel();

        m.submitted = m.submitted.put("c1", new Submitted("c1", SubmitKind.GOALS, List.of(), 1L, LEADER, 5L));
        Outbox.expire(m, SurvivorPinSupport.ctx(1_000L, 5L + ageNs));

        return m.submitted.size();
    }

    // -- :366 judgmentMutation ----------------------------------------------------------------------------------------
    /// Red when: a judgment mutation is skipped only if there are no operations AND the fold changed nothing (`&&` instead of `||`): an
    /// operation that leaves the committed judgment as it is still produces a transaction.
    @Test
    void materialize_writesNoJudgment_whenTheFoldChangesNothing() {
        var m = new ClusterModel();

        m.artifactIntents = m.artifactIntents.put(ORDERS, new ArtifactIntent(List.of(new JudgmentOp.ClearUnresponsive(CORE_1)), List.of(), List.of()));

        assertThat(Outbox.materialize(m, SurvivorPinSupport.ctx(1_000L, 0L))).isEmpty();
    }

    /// Control: an operation that changes the judgment is written.
    @Test
    void materialize_writesTheJudgment_whenTheFoldChangesIt() {
        var m = new ClusterModel();

        m.artifactIntents = m.artifactIntents.put(ORDERS, new ArtifactIntent(List.of(new JudgmentOp.Transient(CORE_1)), List.of(), List.of()));

        assertThat(Outbox.materialize(m, SurvivorPinSupport.ctx(1_000L, 0L))).hasSize(1);
    }

    // -- :884 resubmitSharedOnly --------------------------------------------------------------------------------------
    /// Red when: the event-only remainder is resubmitted with the same attempt count (`+ 0` instead of `+ 1`), so the resubmission bound never
    /// closes on it.
    @Test
    void refused_resubmitsTheSharedRemainder_withTheNextAttempt() {
        var m = new ClusterModel();
        var judgment = new KVCommand.Mutation<AetherKey, AetherValue>(new ArtifactJudgmentKey(ORDERS),
                                                                      Option.none(),
                                                                      Option.some(new ArtifactJudgmentValue(true, Map.of(), Map.of())));
        var draft = new TxDraft(SubmitKind.GOALS,
                                new ArtifactJudgmentKey(ORDERS),
                                List.of(desiredPut(Option.none()), judgment),
                                List.of(),
                                Set.of(PAIR),
                                Option.none(),
                                2,
                                List.of());

        Indexer.indexDesired(m, SurvivorPinSupport.ctx(1_000L, 0L), PAIR, desired(SliceGoal.LOADED, gen(1)));
        m.submitted = m.submitted.put("c1", new Submitted("c1", SubmitKind.GOALS, List.of(draft), 1L, LEADER, 0L));
        Outbox.settle(m, SurvivorPinSupport.ctx(1_000L, 0L), new ClusterInput.WriteSettled("c1", 1L, new Settlement.Refused(Option.some(LEADER), Map.of())));

        assertThat(m.outbox).hasSize(1);
        assertThat(m.outbox.getFirst().attempt()).isEqualTo(3);
        assertThat(m.outbox.getFirst().mutations()).containsExactly(judgment);
    }

    // -- :234 dropIntents ---------------------------------------------------------------------------------------------
    private static long refusedWitnessStaleTotal(ClusterModel m) {
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Outbox.flush(m, ctx, true);

        return ctx.effects()
                  .stream()
                  .filter(effect -> effect instanceof ClusterEffect.Metric metric && metric.tags().get("outcome").equals("refused_witness_stale"))
                  .count();
    }

    /// Red when: a suspended flush counts a drop when nothing was dropped (`>=` instead of `>` on the dropped total).
    @Test
    void flush_countsNoDrop_whenSuspendedWithNothingQueued() {
        assertThat(refusedWitnessStaleTotal(new ClusterModel())).isZero();
    }

    /// Control: the same flush with one queued draft counts the drop.
    @Test
    void flush_countsTheDrop_whenSuspendedWithADraftQueued() {
        var m = new ClusterModel();

        m.outbox = List.of(TxDraft.draft(SubmitKind.GOALS, new SliceDesiredKey(CORE_1, ORDERS), List.of(desiredPut(Option.none()))));

        assertThat(refusedWitnessStaleTotal(m)).isEqualTo(1);
    }
}
