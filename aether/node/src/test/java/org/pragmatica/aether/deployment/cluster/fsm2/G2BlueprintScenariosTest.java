// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// CF-8 (#2759), G2 P2 (blueprint lifecycle writes) ported to fsm2: each write of the blueprint lifecycle (delete, SUCCEEDED, the
/// BEST_EFFORT outcome, the rollback) converges when it fails once or is lost with its term. Mechanism: a removal is parked and
/// replayed, and every other write is re-derived by the reconcile from committed facts (outcome sweep, failure sweep, the durable
/// judgment and apply records), so no write depends on an event that was consumed.
class G2BlueprintScenariosTest {
    private static final String APP = "org.example:app:1.0.0";
    private static final long TICK_SECONDS = Timing.DEFAULT.tick() / 1_000L;

    private static final AppBlueprintValue FIRST = blueprint(APP, false, "attempt-1", slice(ORDERS, 1, 1, Set.of()));

    private static final AppBlueprintValue SECOND = blueprint(APP, false, "attempt-2", slice(ORDERS_V2, 1, 1, Set.of()));

    private static Option<DeploymentOutcomeStatus> outcomeStatus(FsmHarness h) {
        return h.store.get(new DeploymentOutcomeKey(blueprintId(APP)))
                      .filter(DeploymentOutcomeValue.class::isInstance)
                      .map(DeploymentOutcomeValue.class::cast)
                      .map(DeploymentOutcomeValue::status);
    }

    private static Option<AppBlueprintValue> committedBlueprint(FsmHarness h) {
        return h.store.get(new AppBlueprintKey(blueprintId(APP)))
                      .filter(AppBlueprintValue.class::isInstance)
                      .map(AppBlueprintValue.class::cast);
    }

    /// FIRST deployed and settled on CORE_1, then SECOND published over it (previous = FIRST) and loading.
    private static NodeSim republished(FsmHarness h) {
        var node = node(h, CORE_1, 11);

        publish(h, FIRST);
        node.follows(ORDERS);
        h.drain();
        node.follows(ORDERS);
        h.drain();
        publish(h, SECOND, Option.some(FIRST));
        node.writes(ORDERS_V2, SliceObservedState.LOADING);
        h.drain();

        return node;
    }

    // G2 P2 blueprintDelete_targetRemoveFailed_removedAndUnloaded
    // Red when: the removal batch of a deleted blueprint, once its write failed, is not kept and sent again, so the target stays
    // and the instance keeps running with no blueprint.
    @Test
    void blueprintDelete_targetRemoveFailed_removedAndUnloaded() {
        var h = active();
        var node = node(h, CORE_1, 11);

        publish(h, FIRST);
        node.follows(ORDERS);
        h.drain();
        node.follows(ORDERS);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
        h.held.clear();
        assertThat(h.store.get(new SliceTargetKey(ORDERS.base())).isPresent()).as("control: the removal batch failed")
                  .isTrue();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advanceBySeconds(TICK_SECONDS * 3);
        assertThat(h.store.get(new SliceTargetKey(ORDERS.base()))).as("the target is removed").isEqualTo(Option.none());
        assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS))
                          .map(SliceDesiredValue.class::cast)
                          .map(SliceDesiredValue::goal)).as("the instance is unloaded")
                  .isEqualTo(Option.some(SliceGoal.UNLOADED));
    }

    // G2 P2 succeeded_writeFailed_singleSlice_recorded
    // Red when: the SUCCEEDED outcome write of a single-slice apply, once failed, is not derived again from the settled slices.
    @Test
    void succeeded_writeFailed_singleSlice_recorded() {
        var h = active();
        var node = node(h, CORE_1, 11);

        publish(h, FIRST);
        node.follows(ORDERS);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        node.follows(ORDERS);
        h.drain();
        h.held.clear();
        assertThat(outcomeStatus(h)).as("control: the SUCCEEDED write failed")
                  .isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advanceBySeconds(TICK_SECONDS * 3);
        assertThat(outcomeStatus(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.SUCCEEDED));
    }

    // G2 P2 bestEffortFailure_writeFailed_recorded
    // Red when: the failure of a BEST_EFFORT slice, once its write failed, is not handled again, so the outcome never names it.
    @Test
    void bestEffortFailure_writeFailed_recorded() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.0, CORE_1, CORE_2, CORE_3).activate();
        var node = node(h, CORE_1, 11);

        publish(h, FIRST);
        node.writes(ORDERS, SliceObservedState.LOADING);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        node.fails(ORDERS, true);
        h.drain();
        h.held.clear();
        assertThat(outcomeStatus(h)).as("control: the failure write failed")
                  .isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advanceBySeconds(TICK_SECONDS * 3);
        assertThat(outcomeStatus(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.FAILED));
    }

    // G2 P2 fencedRollback_applyFailedOnce_lands: the rollback restores a previous value (its write expects the failed one)
    // Red when: a rollback whose write failed once is not derived again, so the failed attempt stays committed.
    @Test
    void fencedRollback_applyFailedOnce_lands() {
        var h = active();
        var node = republished(h);

        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        node.fails(ORDERS_V2, true);
        h.drain();
        h.held.clear();
        assertThat(committedBlueprint(h)).as("control: the rollback write failed").isEqualTo(Option.some(SECOND));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advanceBySeconds(TICK_SECONDS * 5);
        assertThat(committedBlueprint(h).map(AppBlueprintValue::blueprint)).isEqualTo(Option.some(FIRST.blueprint()));
        assertThat(outcomeStatus(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    // G2 P2 unfencedRollback_applyFailedOnce_lands: the first publish has no previous value, so the rollback deletes the blueprint
    // Red when: a delete-rollback whose write failed once is not derived again, so the failed blueprint stays committed.
    @Test
    void unfencedRollback_applyFailedOnce_lands() {
        var h = active();
        var node = node(h, CORE_1, 11);

        publish(h, FIRST);
        node.writes(ORDERS, SliceObservedState.LOADING);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        node.fails(ORDERS, true);
        h.drain();
        h.held.clear();
        assertThat(committedBlueprint(h)).as("control: the rollback write failed").isEqualTo(Option.some(FIRST));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advanceBySeconds(TICK_SECONDS * 5);
        assertThat(committedBlueprint(h)).isEqualTo(Option.none());
        assertThat(outcomeStatus(h)).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    // CF-8 rollbackMarked, the open-apply guard: the permanent-failure mark of a SUCCEEDED apply never derives a rollback
    // Red when: the re-derivation from the mark rolls back an apply whose outcome is already terminal.
    @Test
    void succeededApply_laterPermanentFailure_isNotRolledBack() {
        var h = active();
        var node = node(h, CORE_1, 11);

        publish(h, FIRST);
        node.follows(ORDERS);
        h.drain();
        node.follows(ORDERS);
        h.drain();
        publish(h, SECOND, Option.some(FIRST));
        node.follows(ORDERS_V2);
        h.drain();
        node.follows(ORDERS_V2);
        h.drain();
        h.advanceBySeconds(TICK_SECONDS * 2);
        assertThat(outcomeStatus(h)).as("control: the apply succeeded")
                  .isEqualTo(Option.some(DeploymentOutcomeStatus.SUCCEEDED));
        node.fails(ORDERS_V2, true);
        h.drain();
        h.advanceBySeconds(TICK_SECONDS * 5);
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("a terminal apply is not rolled back by a later mark").isEmpty();
        assertThat(committedBlueprint(h).map(AppBlueprintValue::blueprint)).isEqualTo(Option.some(SECOND.blueprint()));
    }

    // G2 P2 newLeader_rollbackIntent_rollbackCompleted, and the residual rollbackIntent_intentLostThenLeaderChange: the decision to
    // roll back is the durable failure mark, so a rollback write lost with its term is derived by the next leader. The G2 residual
    // (an in-memory decision lost with the leader) has no fsm2 analogue and is listed in the PR.
    // Red when: the next leader does not derive the rollback from the committed judgment and apply record.
    @Test
    void newLeader_rollbackIntent_rollbackCompleted() {
        var h = active();
        var node = republished(h);

        h.policy = FsmHarness.SettlePolicy.HOLD;
        node.fails(ORDERS_V2, true);
        h.drain();
        var failure = h.submits(SubmitKind.FAILURE).getFirst();

        h.policy = FsmHarness.SettlePolicy.LOSE;
        h.settle(failure,
                 h.applyHeld(failure.correlationId()));
        h.drain();
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("control: the rollback was sent, and lost").hasSize(1);
        assertThat(committedBlueprint(h)).as("control: nothing rolled back").isEqualTo(Option.some(SECOND));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.held.clear();
        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.activate();
        h.advanceBySeconds(TICK_SECONDS * 5);
        assertThat(committedBlueprint(h).map(AppBlueprintValue::blueprint)).isEqualTo(Option.some(FIRST.blueprint()));
    }
}
