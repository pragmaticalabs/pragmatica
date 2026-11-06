// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnresponsiveEntry;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.goalOf;
import static org.assertj.core.api.Assertions.assertThat;


/// CF-8 (#2759), verifier finding on #2771: under ALL_OR_NOTHING the permanent mark is scoped to the attempts it judged
/// ([ArtifactJudgmentValue#judgedAttempts]). A SUCCEEDED apply whose slice is later marked permanently failed is recovered by a
/// republish of the same artifact (NT-27); the judgment removal rides in the leader's target batch, so any reconcile before that batch
/// commits still sees the mark. The fresh attempt is never rolled back by it: in term, with the batch in flight, with the batch lost
/// with the term, and in the end state. Ported from the verifier's `V2771ProbeTest`; all four went red at a7459d84cd.
class RepublishAfterMarkTest {
    private static final String APP = "org.example:app:1.0.0";

    private static final AppBlueprintValue FIRST = blueprint(APP, false, "attempt-1", slice(ORDERS, 1, 1, Set.of()));

    private static final AppBlueprintValue AGAIN = blueprint(APP, false, "attempt-2", slice(ORDERS, 1, 1, Set.of()));

    private static Option<DeploymentOutcomeStatus> outcomeStatus(FsmHarness h) {
        return h.store.get(new DeploymentOutcomeKey(blueprintId(APP)))
                      .filter(DeploymentOutcomeValue.class::isInstance)
                      .map(DeploymentOutcomeValue.class::cast)
                      .map(DeploymentOutcomeValue::status);
    }

    private static Option<ArtifactJudgmentValue> judgment(FsmHarness h) {
        return h.store.get(new ArtifactJudgmentKey(ORDERS))
                      .filter(ArtifactJudgmentValue.class::isInstance)
                      .map(ArtifactJudgmentValue.class::cast);
    }

    /// FIRST deployed and SUCCEEDED on CORE_1, then its slice failed fatally: the mark is committed and judges attempt-1.
    private static FsmHarness succeededThenMarked() {
        var h = active();
        var node = node(h, CORE_1, 11);

        publish(h, FIRST);
        node.follows(ORDERS);
        h.drain();
        node.follows(ORDERS);
        h.drain();
        h.advanceBySeconds(60);
        assertThat(outcomeStatus(h)).as("control: succeeded").isEqualTo(Option.some(DeploymentOutcomeStatus.SUCCEEDED));
        node.fails(ORDERS, true);
        h.drain();
        h.advanceBySeconds(60);
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("control: the mark is committed")
                  .isEqualTo(Option.some(true));
        assertThat(judgment(h).map(ArtifactJudgmentValue::judgedAttempts)).as("the mark judges the attempt it was decided in")
                  .isEqualTo(Option.some(Set.of("attempt-1")));
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("control: no rollback of the succeeded apply").isEmpty();

        return h;
    }

    // Red when: rollbackMarked rolls back an open apply whose attempt the mark did not judge (the attempt comparison is dropped).
    @Test
    void republishAfterMark_allWritesApplied_freshAttemptNotRolledBack() {
        var h = succeededThenMarked();

        publish(h, AGAIN, Option.some(FIRST));
        h.advance(h.settings.timing().tick() * 3);
        assertThat(judgment(h)).as("control: the republish removed the mark").isEqualTo(Option.none());
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("normal path: no rollback").isEmpty();
        assertThat(outcomeStatus(h)).as("normal path: not rolled back")
                  .isNotEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    // Red when: a tick's reconcile, run while the publish batch that removes the mark is in flight, rolls the fresh attempt back.
    @Test
    void republishAfterMark_publishBatchInFlight_freshAttemptNotRolledBack() {
        var h = succeededThenMarked();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, AGAIN, Option.some(FIRST));
        assertThat(h.submits(SubmitKind.TARGETS)).as("control: the publish batch is in flight").isNotEmpty();
        h.advance(h.settings.timing().tick());
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("the fresh attempt is not rolled back by the mark its own batch removes")
                  .isEmpty();
    }

    // Red when: the next leader, rebuilt with the mark still committed (the removal was lost with the term), rolls the fresh attempt back.
    @Test
    void republishAfterMark_publishBatchLostWithTerm_freshAttemptNotRolledBack() {
        var h = succeededThenMarked();

        h.policy = FsmHarness.SettlePolicy.LOSE;
        publish(h, AGAIN, Option.some(FIRST));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        h.activate();
        h.advance(h.settings.timing().tick() * 3);
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("new leader: fresh attempt not rolled back by a stale mark")
                  .isEmpty();
    }

    // Red when: once every held write lands in submission order, the committed outcome is ROLLED_BACK.
    @Test
    void republishAfterMark_publishBatchInFlight_endState() {
        var h = succeededThenMarked();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        publish(h, AGAIN, Option.some(FIRST));
        h.advance(h.settings.timing().tick());
        h.submits()
         .stream()
         .filter(submit -> h.held.containsKey(submit.correlationId()))
         .toList()
         .forEach(submit -> settleHeld(h, submit));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advance(h.settings.timing().tick() * 3);
        var committed = h.store.get(new AppBlueprintKey(blueprintId(APP)))
                               .filter(AppBlueprintValue.class::isInstance)
                               .map(AppBlueprintValue.class::cast)
                               .map(AppBlueprintValue::attemptId);

        assertThat(committed).as("control: the republish is committed").isEqualTo(Option.some("attempt-2"));
        assertThat(outcomeStatus(h)).as("end state: outcome")
                  .isNotEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    // Red when: an operation folded over a marked judgment drops the attempts it judged, or a second mark replaces rather than adds.
    @Test
    void judgmentFold_keepsJudgedAttemptsAcrossOtherOps() {
        var marked = JudgmentOp.fold(Option.none(),
                                     List.of(new JudgmentOp.Fatal(Set.of("attempt-1"))));
        var later = JudgmentOp.fold(marked,
                                    List.of(new JudgmentOp.Transient(CORE_1),
                                            new JudgmentOp.ResetRetries(CORE_1),
                                            new JudgmentOp.Exhausted(CORE_2),
                                            new JudgmentOp.Retract(CORE_2,
                                                                   new UnresponsiveEntry(gen(1), SliceGoal.ACTIVE, 1L)),
                                            new JudgmentOp.ClearUnresponsive(CORE_2),
                                            new JudgmentOp.Fatal(Set.of("attempt-3"))));

        assertThat(marked.map(ArtifactJudgmentValue::judgedAttempts)).isEqualTo(Option.some(Set.of("attempt-1")));
        assertThat(later.map(ArtifactJudgmentValue::judgedAttempts)).isEqualTo(Option.some(Set.of("attempt-1",
                                                                                                  "attempt-3")));
        assertThat(later.map(ArtifactJudgmentValue::retries)).isEqualTo(Option.some(Map.of()));
    }

    /// FIRST published and loading on CORE_1 (its apply open), then a judgment `seeded` for ORDERS: a mark committed by an earlier
    /// leader, whose settings may differ (atomicity is configuration).
    private static FsmHarness openApplyWithJudgment(FsmHarness h, ArtifactJudgmentValue seeded) {
        var node = node(h, CORE_1, 11);

        publish(h, FIRST);
        node.writes(ORDERS, SliceObservedState.LOADING);
        h.drain();
        h.seed(new ArtifactJudgmentKey(ORDERS), seeded);
        h.drain();
        h.advance(h.settings.timing().tick() * 3);

        return h;
    }

    // Control for the two pins below: a committed mark that judged the open attempt derives the rollback on a tick.
    // Red when: rollbackMarked does not roll back an open apply whose attempt the mark judged.
    @Test
    void allOrNothing_seededMarkOfTheOpenAttempt_rollsBack() {
        var h = openApplyWithJudgment(active(),
                                      new ArtifactJudgmentValue(true, Map.of(), Map.of(), Set.of("attempt-1")));

        assertThat(h.submits(SubmitKind.ROLLBACK)).as("the mark of the open attempt rolls it back").isNotEmpty();
    }

    // Red when: rollbackMarked runs under BEST_EFFORT (there a failure records FAILED with the mark, it never rolls back).
    @Test
    void bestEffort_seededMarkOfTheOpenAttempt_derivesNoRollback() {
        var h = openApplyWithJudgment(leader(DeploymentAtomicity.BEST_EFFORT, 0.0, CORE_1, CORE_2, CORE_3).activate(),
                                      new ArtifactJudgmentValue(true, Map.of(), Map.of(), Set.of("attempt-1")));

        assertThat(h.submits(SubmitKind.ROLLBACK)).as("BEST_EFFORT never rolls back from a mark").isEmpty();
    }

    // Red when: judged attempts without the permanent mark derive a rollback (the permanentFailure filter is dropped).
    @Test
    void allOrNothing_seededAttemptsWithoutAPermanentMark_deriveNoRollback() {
        var h = openApplyWithJudgment(active(),
                                      new ArtifactJudgmentValue(false, Map.of(CORE_1, 1), Map.of(), Set.of("attempt-1")));

        assertThat(h.submits(SubmitKind.ROLLBACK)).as("no permanent mark, no rollback").isEmpty();
    }

    private static boolean placed(FsmHarness h) {
        return Set.of(CORE_1, CORE_2, CORE_3)
                  .stream()
                  .anyMatch(node -> goalOf(h, node, ORDERS).equals(Option.some(SliceGoal.LOADED)));
    }

    /// The republish batch (the one that removes the mark) is lost with the term; the next leader rebuilds with the stale mark committed.
    private static FsmHarness lostBatchBeforeNewLeader(FsmHarness.SettlePolicy afterRebuild) {
        var h = succeededThenMarked();

        h.policy = FsmHarness.SettlePolicy.LOSE;
        publish(h, AGAIN, Option.some(FIRST));
        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        h.policy = afterRebuild;
        h.activate();

        return h;
    }

    // Red when: the leader never removes a mark that did not judge the open attempt (V2771b: the attempt stays IN_PROGRESS, never placed).
    @Test
    void lostBatchNewLeader_staleMark_removedAndLaterAttemptPlaced() {
        var h = lostBatchBeforeNewLeader(FsmHarness.SettlePolicy.APPLY);

        h.advanceBySeconds(120);
        assertThat(judgment(h)).as("the stale mark is superseded by the open attempt it did not judge")
                  .isEqualTo(Option.none());
        assertThat(placed(h)).as("the later attempt is placed within the normal bound").isTrue();
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("the later attempt is not rolled back by the stale mark")
                  .isEmpty();
    }

    // Red when: the removal is not retried after it is lost (the first removal is lost; a tick derives it again).
    @Test
    void lostBatchNewLeader_staleMarkRemovalLost_removedByALaterTick() {
        var h = lostBatchBeforeNewLeader(FsmHarness.SettlePolicy.LOSE);

        h.advance(h.settings.timing().tick() * 2);
        assertThat(h.submits(SubmitKind.STALE_MARK)).as("control: the removal was sent").isNotEmpty();
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("control: lost, the mark stands")
                  .isEqualTo(Option.some(true));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advanceBySeconds(120);
        assertThat(judgment(h)).as("a later tick removed it").isEqualTo(Option.none());
    }

    // Red when: every mark is removed (the attempt comparison is dropped), or a mark judging the open attempt no longer rolls it back.
    @Test
    void allOrNothing_markJudgingTheOpenAttempt_isNotRemovedAndRollsBack() {
        var h = openApplyWithJudgment(active(),
                                      new ArtifactJudgmentValue(true, Map.of(), Map.of(), Set.of("attempt-1")));

        h.advanceBySeconds(60);
        assertThat(h.submits(SubmitKind.STALE_MARK)).as("no removal of a mark that judged the open attempt").isEmpty();
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("the rollback still happens").isNotEmpty();
    }

    // Red when: the removal is not exact-value guarded: a newer mark committed while the removal is in flight is removed with it.
    @Test
    void staleMarkRemoval_racingANewerMark_leavesTheNewerMark() {
        var h = lostBatchBeforeNewLeader(FsmHarness.SettlePolicy.HOLD);
        var newer = new ArtifactJudgmentValue(true, Map.of(), Map.of(), Set.of("attempt-1", "attempt-2"));

        h.advance(h.settings.timing().tick() * 2);
        var removal = h.submits(SubmitKind.STALE_MARK);

        assertThat(removal).as("control: the removal of the stale mark is in flight").isNotEmpty();
        h.seed(new ArtifactJudgmentKey(ORDERS), newer);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        removal.stream()
               .filter(submit -> h.held.containsKey(submit.correlationId()))
               .toList()
               .forEach(submit -> settleHeld(h, submit));
        assertThat(judgment(h)).as("the removal of the older mark was refused: the newer mark stands")
                  .isEqualTo(Option.some(newer));
        h.advance(h.settings.timing().tick() * 3);
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("the newer mark judges the open attempt: it rolls it back")
                  .isNotEmpty();
    }

    private static FsmHarness settleHeld(FsmHarness h, ClusterEffect.Submit submit) {
        h.settle(submit,
                 h.applyHeld(submit.correlationId()));
        h.drain();

        return h;
    }
}
