// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.pragmatica.aether.deployment.cluster.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeclaredRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRoute;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.RouteState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The goal gate of the declared-route table (#3389 A2 part 2): a LOADED or ACTIVE goal stands only once the artifact's declared rows are committed
/// in the mirror, and the GOALS transaction carries a ReadWitness on each row, so a slice never loads before its routes are Declared. Each test
/// drives the production order: blueprint put, targets commit, placement derives the goal, the goal transaction goes out.
class RoutesGateTest {
    private static FsmHarness leaderWithOneCore() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);

        h.activate();

        return h;
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> loads(FsmHarness h) {
        return desiredPuts(h, goal -> goal != SliceGoal.UNLOADED);
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> activations(FsmHarness h) {
        return desiredPuts(h, goal -> goal == SliceGoal.ACTIVE);
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> desiredPuts(FsmHarness h, Predicate<SliceGoal> goals) {
        return h.mutations(SliceDesiredKey.class)
                .stream()
                .filter(mutation -> mutation.replacement()
                                            .filter(SliceDesiredValue.class::isInstance)
                                            .map(SliceDesiredValue.class::cast)
                                            .map(desired -> goals.test(desired.goal()))
                                            .or(false))
                .toList();
    }

    private static DeclaredRoute billingRoute() {
        return DeclaredRoute.declaredRoute("GET", "/bills");
    }

    private static DeclaredRouteKey billingKey() {
        return DeclaredRouteKey.declaredRouteKey(BILLING, "GET", "/bills").unwrap();
    }

    /// The blueprint of one ORDERS and one BILLING instance, each declaring one route.
    private static AppBlueprintValue twoSlices(String attempt) {
        var expanded = ExpandedBlueprint.expandedBlueprint(APP_ID,
                                                           List.of(slice(ORDERS, 1, 1, Set.of()), slice(BILLING, 1, 1, Set.of())));

        return AppBlueprintValue.appBlueprintValue(expanded, false, attempt, Map.of(ORDERS, List.of(LIST), BILLING, List.of(billingRoute())));
    }

    private static void releaseHeld(FsmHarness h) {
        releaseHeldOf(h, null);
    }

    private static void releaseHeldOf(FsmHarness h, SubmitKind kind) {
        new ArrayList<>(h.held.values()).stream().filter(submit -> kind == null || submit.kind() == kind).forEach(submit -> {
            h.applyHeld(submit.correlationId());
            h.settleHeld(submit, new Settlement.Accepted());
        });
    }

    /// Red when: `Outbox.goalDraft` does not hold a LOADED goal while the artifact's declared rows are uncommitted: the leader schedules the first
    /// load while its ROUTES transaction is still in flight, and the slice loads before its routes are Declared. The rows commit afterwards, the
    /// reconcile they request re-derives the goal, and the load goes out with the rows already committed.
    @Test
    void gate_aLoadWaitsForTheDeclaredRowsToCommit() {
        var h = leaderWithOneCore();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publish(h, blueprintOf("attempt-1", LIST));
        assertThat(h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.ROUTES)).as("control: the rows are drafted and in flight").hasSize(1);
        assertThat(h.model().declaredRoutes.isEmpty()).as("control: nothing committed yet").isTrue();
        assertThat(loads(h)).as("no goal while the rows are uncommitted").isEmpty();
        h.holdKinds = Set.of();
        releaseHeld(h);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(declared("attempt-1")));
        assertThat(loads(h)).as("the load follows the rows").hasSize(1);
        var kinds = h.submits().stream().map(ClusterEffect.Submit::kind).toList();

        assertThat(kinds.indexOf(SubmitKind.ROUTES)).isLessThan(kinds.indexOf(SubmitKind.GOALS));
    }

    /// Red when: the GOALS transaction carries no ReadWitness on the declared row (or witnesses a value other than the committed one): a row
    /// that moves between the decision and the commit does not refuse the goal. The held goal transaction is refused after the row is flipped.
    @Test
    void gate_theGoalTransactionWitnessesEachDeclaredRow() {
        var h = leaderWithOneCore();

        h.holdKinds = Set.of(SubmitKind.GOALS);
        publish(h, blueprintOf("attempt-1", LIST, GET_ONE));
        var goals = h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.GOALS).toList();

        assertThat(goals).as("the goal is drafted once the rows are committed").hasSize(1);
        var witnessed = goals.getFirst().transaction().guards().stream().filter(guard -> guard.key() instanceof DeclaredRouteKey).toList();

        assertThat(witnessed.stream().map(KVCommand.ReadWitness::key).toList()).containsExactlyInAnyOrder(key(GET_ONE), key(LIST));
        assertThat(witnessed.stream().map(KVCommand.ReadWitness::expected).toList())
                  .containsOnly(Option.some(declared("attempt-1")));
        h.seed(key(LIST), published("attempt-1"));
        h.drain();
        var goalKey = goals.getFirst().transaction().mutations().getFirst().key();
        var settlement = h.applyHeld(goals.getFirst().correlationId());

        assertThat(settlement).isInstanceOf(Settlement.Refused.class);
        assertThat(h.store.get(goalKey)).as("the refused goal wrote nothing").isEqualTo(Option.none());
    }

    /// Red when: a refused goal transaction is resubmitted without its row witnesses (the split by movement keeps only the observed guard of a
    /// pair), or the goal commits although its row was removed between the decision and the commit. The held goal transaction is refused after the
    /// row is removed and its re-declaration is held: no desired put lands and no second goal transaction goes out while the row is uncommitted.
    @Test
    void gate_aRowRemovedBeforeTheCommitRefusesTheGoalAndItIsNotResubmitted() {
        var h = leaderWithOneCore();

        h.holdKinds = Set.of(SubmitKind.GOALS, SubmitKind.ROUTES);
        publish(h, blueprintOf("attempt-1", LIST));
        releaseHeldOf(h, SubmitKind.ROUTES);
        var goals = h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.GOALS).toList();

        assertThat(goals).as("control: the goal is drafted once the row is committed").hasSize(1);
        h.seedRemove(key(LIST));
        h.drain();
        assertThat(committed(h, LIST)).as("control: the row is gone").isEqualTo(Option.none());
        var goalKey = goals.getFirst().transaction().mutations().getFirst().key();
        var settlement = h.applyHeld(goals.getFirst().correlationId());

        assertThat(settlement).isInstanceOf(Settlement.Refused.class);
        h.settleHeld(goals.getFirst(), settlement);
        h.drain();
        assertThat(h.store.get(goalKey)).as("the refused goal wrote nothing").isEqualTo(Option.none());
        assertThat(h.submits(SubmitKind.GOALS)).as("no goal transaction while the row is uncommitted").hasSize(1);
    }

    /// Red when: a `Failed` row of an earlier attempt counts as committed: the blueprint is at attempt-1, its DECLARE is in flight, and the row
    /// that lands is `Failed` for attempt-0 (the stale kind; a `Failed` row of the current attempt does not count either, see below). No goal is
    /// drafted against it.
    @Test
    void gate_aFailedRowOfAnEarlierAttemptIsNotCommitted() {
        var h = leaderWithOneCore();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publish(h, blueprintOf("attempt-1", LIST));
        h.seed(key(LIST), failed("attempt-0", "boom"));
        h.drain();
        assertThat(committed(h, LIST)).as("control: the earlier attempt's row is committed").isEqualTo(Option.some(failed("attempt-0", "boom")));
        assertThat(h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.ROUTES)).as("control: the DECLARE is in flight").hasSize(1);
        assertThat(h.submits(SubmitKind.GOALS)).as("no goal against the row").isEmpty();
    }

    /// Red when: a `Declared` row of an earlier attempt counts as committed (ruling A of #3836): the owner is at attempt-1, its DECLARE is in
    /// flight, and the row that is committed still speaks for attempt-0. No goal is drafted against it.
    @Test
    void gate_aDeclaredRowOfAnEarlierAttemptIsNotCommitted() {
        assertRowKeepsTheGateClosed(declared("attempt-0"));
    }

    /// Red when: a `Published` row of an earlier attempt counts as committed: same setting as the `Declared` case.
    @Test
    void gate_aPublishedRowOfAnEarlierAttemptIsNotCommitted() {
        assertRowKeepsTheGateClosed(published("attempt-0"));
    }

    /// Red when: a `Failed` row of the owner's current attempt counts as committed: the gate counts only `Declared` and `Published` rows at the
    /// current attempt, so a goal is not drafted over a row that says the deploy already failed.
    @Test
    void gate_aFailedRowOfTheCurrentAttemptIsNotCommitted() {
        assertRowKeepsTheGateClosed(failed("attempt-1", "boom"));
    }

    /// Red when: a row another blueprint owns counts as committed because its attemptId equals the owner's: a row speaks only for the blueprint
    /// that owns it, so the owner's DECLARE must take it over before any goal is drafted.
    @Test
    void gate_aRowAnotherBlueprintOwnsIsNotCommitted() {
        assertRowKeepsTheGateClosed(DeclaredRouteValue.declaredRouteValue("org.example:other:1.0.0", "attempt-1", RouteState.Declared.declared()));
    }

    /// Red when: a `Published` row of the current attempt does not count as committed: a node's routes are served, so the gate must not hold
    /// the goal over the row that says so.
    @Test
    void gate_aPublishedRowOfTheCurrentAttemptIsCommitted() {
        var h = leaderWithOneCore();

        publish(h, blueprintOf("attempt-1", LIST));
        publishRoutes(h, CORE_1);
        assertThat(committed(h, LIST)).as("control: published at the owner's attempt").isEqualTo(Option.some(published("attempt-1")));
        assertThat(Routes.rowsCommitted(h.model(), ORDERS)).isTrue();
    }

    /// A ROUTES transaction that puts a row (the DECLARE), not one that only removes the rows of a blueprint that no longer owns them.
    private static boolean writesARow(ClusterEffect.Submit submit) {
        return submit.transaction().mutations().stream().anyMatch(mutation -> mutation.replacement().isPresent());
    }

    private static void assertRowKeepsTheGateClosed(DeclaredRouteValue row) {
        var h = leaderWithOneCore();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publish(h, blueprintOf("attempt-1", LIST));
        h.seed(key(LIST), row);
        h.drain();
        assertThat(committed(h, LIST)).as("control: the row is committed").isEqualTo(Option.some(row));
        assertThat(h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.ROUTES).filter(RoutesGateTest::writesARow)).as("control: the DECLARE is in flight").hasSize(1);
        assertThat(Routes.rowsCommitted(h.model(), ORDERS)).as("the gate is closed").isFalse();
        assertThat(h.submits(SubmitKind.GOALS)).as("no goal against the row").isEmpty();
    }

    /// Red when: an ACTIVE goal is not gated (the gate covers only LOADED): the instance is LOADED, its row goes and its re-declaration is held;
    /// the activation must wait for the row, and follows it.
    @Test
    void gate_anActivationWaitsForTheDeclaredRowsToo() {
        var h = leaderWithOneCore();

        publish(h, blueprintOf("attempt-1", LIST));
        assertThat(loads(h)).as("control: loaded with the row committed").hasSize(1);
        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.seedRemove(key(LIST));
        h.drain();
        assertThat(committed(h, LIST)).as("control: the row is gone and its re-declaration held").isEqualTo(Option.none());
        node(h, CORE_1, 11).writes(ORDERS, SliceObservedState.LOADED);
        h.drain();
        assertThat(activations(h)).as("no ACTIVE goal while the row is uncommitted").isEmpty();
        h.holdKinds = Set.of();
        releaseHeld(h);
        h.advanceBySeconds(3 * Timing.DEFAULT.tick() / 1_000L);
        assertThat(activations(h)).as("the activation follows the row within three ticks").hasSize(1);
    }

    /// Red when: one committed row is enough (the gate asks `anyMatch`, not every row): LIST is committed, GET_ONE is not, and the goal waits.
    @Test
    void gate_everyRowMustBeCommitted_notOne() {
        var h = leaderWithOneCore();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publish(h, blueprintOf("attempt-1", LIST, GET_ONE));
        h.seed(key(LIST), declared("attempt-1"));
        h.drain();
        assertThat(h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.ROUTES)).as("control: the DECLARE is in flight").hasSize(1);
        assertThat(committed(h, LIST)).as("control: one row is committed").isEqualTo(Option.some(declared("attempt-1")));
        assertThat(committed(h, GET_ONE)).as("control: the other is not").isEqualTo(Option.none());
        assertThat(h.submits(SubmitKind.GOALS)).as("no goal while one row is uncommitted").isEmpty();
    }

    /// Red when: a target that no blueprint owns (a manual deploy) is held for want of an owner's rows (a missing owner is read as "uncommitted").
    @Test
    void gate_aSliceNoBlueprintOwnsLoadsAsBefore() {
        var h = leaderWithOneCore();

        h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();
        assertThat(loads(h)).hasSize(1);
    }

    /// Red when: a `registerOnly` owner's declared routes are waited for: it writes no rows, so a `Failed` row of its earlier attempt would hold
    /// the load for ever.
    @Test
    void gate_aRegisterOnlyOwnerDeclaresNothingToWaitFor() {
        var h = leaderWithOneCore();
        var expanded = ExpandedBlueprint.expandedBlueprint(APP_ID, List.of(slice(ORDERS, 1, 1, Set.of())));

        h.seed(new AppBlueprintKey(APP_ID), AppBlueprintValue.appBlueprintValue(expanded, true, "attempt-2", Map.of(ORDERS, List.of(LIST))));
        h.seed(key(LIST), failed("attempt-1", "boom"));
        h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1, Option.some(APP_ID)));
        h.drain();
        assertThat(committed(h, LIST)).as("control: the registerOnly blueprint leaves the row as it is").isEqualTo(Option.some(failed("attempt-1", "boom")));
        assertThat(loads(h)).hasSize(1);
    }

    /// Red when: a refusal caused by one artifact's row moves the other artifact's goal too (the pair's guards take every declared row of the
    /// transaction), or the goal that stays is resubmitted without its row witness. ORDERS' row changes; BILLING's goal is resubmitted alone,
    /// still witnessing BILLING's row and not ORDERS'.
    @Test
    void gate_aRefusedTransactionKeepsTheOtherArtifactsGoalWithItsWitness() {
        var h = leaderWithOneCore();

        h.holdKinds = Set.of(SubmitKind.GOALS);
        publish(h, twoSlices("attempt-1"));
        var first = h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.GOALS).toList();

        assertThat(first).as("control: one goal transaction for the node").hasSize(1);
        assertThat(first.getFirst().transaction().mutations()).as("control: it carries both goals").hasSize(2);
        h.seed(key(LIST), published("attempt-1"));
        h.drain();
        var settlement = h.applyHeld(first.getFirst().correlationId());

        assertThat(settlement).isInstanceOf(Settlement.Refused.class);
        h.settleHeld(first.getFirst(), settlement);
        var billingOnly = h.submits(SubmitKind.GOALS)
                           .stream()
                           .filter(submit -> submit.transaction().mutations().stream().allMatch(mutation -> mutation.key().equals(new SliceDesiredKey(CORE_1, BILLING))))
                           .toList();

        assertThat(billingOnly).as("BILLING's goal is resubmitted alone").hasSize(1);
        assertThat(billingOnly.getFirst().transaction().guards().stream().map(KVCommand.ReadWitness::key).filter(DeclaredRouteKey.class::isInstance).toList())
                  .as("with its own row witness only")
                  .containsExactly(billingKey());
    }

    /// The one goal transaction of the node, held, with the rows committed and nothing else in flight: ROUTES is held and released first.
    private static ClusterEffect.Submit heldGoalOf(FsmHarness h, AppBlueprintValue blueprint) {
        h.holdKinds = Set.of(SubmitKind.GOALS, SubmitKind.ROUTES);
        publish(h, blueprint);
        releaseHeldOf(h, SubmitKind.ROUTES);
        var goals = h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.GOALS).toList();

        assertThat(goals).as("control: one goal transaction is in flight").hasSize(1);

        return goals.getFirst();
    }

    private static List<AetherKey> witnessedKeys(ClusterEffect.Submit submit) {
        return submit.transaction().guards().stream().map(KVCommand.ReadWitness::key).toList();
    }

    /// Red when: the GOALS transaction does not witness the SliceTarget row (the owner id the gate reads) or the owner's AppBlueprintValue (#3836
    /// ruling B): the transaction carries both, at the committed values, and with nothing moved it commits.
    @Test
    void gate_theGoalTransactionWitnessesTheOwnerAndItsBlueprint_nothingMovedCommits() {
        var h = leaderWithOneCore();
        var goal = heldGoalOf(h, blueprintOf("attempt-1", LIST));
        var guards = goal.transaction().guards();

        assertThat(guards.stream().filter(guard -> guard.key().equals(new SliceTargetKey(ORDERS.base()))).map(KVCommand.ReadWitness::expected).toList())
                  .containsExactly(h.store.get(new SliceTargetKey(ORDERS.base())).map(value -> (Object) value));
        assertThat(guards.stream().filter(guard -> guard.key().equals(AppBlueprintKey.appBlueprintKey(APP_ID))).map(KVCommand.ReadWitness::expected).toList())
                  .containsExactly(Option.some(blueprintOf("attempt-1", LIST)));
        assertThat(h.applyHeld(goal.correlationId())).isInstanceOf(Settlement.Accepted.class);
        assertThat(h.store.get(goal.transaction().mutations().getFirst().key()).isPresent()).as("the goal landed").isTrue();
    }

    /// Red when: the owner's blueprint moving to the next attempt between the read and the commit does not refuse the GOALS transaction (the
    /// blueprint is not witnessed; the row witnesses alone hold, as the re-declaration is still in flight). The refused goal is judged again
    /// against the new attempt: its row is of the earlier attempt, so no second goal goes out.
    @Test
    void gate_theOwnersBlueprintMovingToTheNextAttemptRefusesTheGoalAndItIsJudgedAgain() {
        var h = leaderWithOneCore();
        var goal = heldGoalOf(h, blueprintOf("attempt-1", LIST));
        var goalKey = goal.transaction().mutations().getFirst().key();

        h.seed(AppBlueprintKey.appBlueprintKey(APP_ID), blueprintOf("attempt-2", LIST));
        h.drain();
        var settlement = h.applyHeld(goal.correlationId());

        assertThat(settlement).isInstanceOf(Settlement.Refused.class);
        assertThat(h.store.get(goalKey)).as("the refused goal wrote nothing").isEqualTo(Option.none());
        h.settleHeld(goal, settlement);
        assertThat(h.submits(SubmitKind.GOALS)).as("judged again: the row is of attempt-1, no second goal").hasSize(1);
    }

    /// Red when: the SliceTarget owner changing between the read and the commit does not refuse the GOALS transaction (the target row is not
    /// witnessed; the blueprint and the rows are untouched).
    @Test
    void gate_theSliceTargetOwnerChangingBeforeTheCommitRefusesTheGoal() {
        var h = leaderWithOneCore();
        var goal = heldGoalOf(h, blueprintOf("attempt-1", LIST));
        var goalKey = goal.transaction().mutations().getFirst().key();

        h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1, Option.some(blueprintId("org.example:other:1.0.0"))));
        h.drain();

        assertThat(h.applyHeld(goal.correlationId())).isInstanceOf(Settlement.Refused.class);
        assertThat(h.store.get(goalKey)).as("the refused goal wrote nothing").isEqualTo(Option.none());
    }

    /// Red when: the blueprint two pairs of one owner share is written once per pair: the transaction carries the whole `AppBlueprintValue`, so a
    /// copy per pair grows it with the blueprint's artifacts.
    @Test
    void gate_theBlueprintTwoPairsShareIsWitnessedOnce() {
        var h = leaderWithOneCore();
        var goal = heldGoalOf(h, twoSlices("attempt-1"));

        assertThat(goal.transaction().mutations()).as("control: both goals ride one transaction").hasSize(2);
        assertThat(witnessedKeys(goal).stream().filter(AppBlueprintKey.appBlueprintKey(APP_ID)::equals).count()).isEqualTo(1);
    }

    /// Red when: a pair resubmitted after a refusal drops the target and blueprint witnesses (the split keeps only the observed guard and the
    /// declared rows): ORDERS' row changes, BILLING's goal is resubmitted alone, with BILLING's target and the shared blueprint witnessed and
    /// not ORDERS' target; the blueprint then moves, and the resubmitted goal is refused.
    @Test
    void gate_aResubmittedGoalStillWitnessesItsOwnerAndTheBlueprint() {
        var h = leaderWithOneCore();
        var first = heldGoalOf(h, twoSlices("attempt-1"));

        h.seed(key(LIST), published("attempt-1"));
        h.drain();
        var settlement = h.applyHeld(first.correlationId());

        assertThat(settlement).as("control: the first transaction is refused by ORDERS' row").isInstanceOf(Settlement.Refused.class);
        h.settleHeld(first, settlement);
        var billingOnly = h.held.values()
                               .stream()
                               .filter(submit -> submit.kind() == SubmitKind.GOALS)
                               .filter(submit -> submit.transaction().mutations().stream().allMatch(mutation -> mutation.key().equals(new SliceDesiredKey(CORE_1, BILLING))))
                               .toList();

        assertThat(billingOnly).as("BILLING's goal is resubmitted alone").hasSize(1);
        assertThat(billingOnly.getFirst().transaction().mutations()).hasSize(1);
        assertThat(witnessedKeys(billingOnly.getFirst())).contains(new SliceTargetKey(BILLING.base()), AppBlueprintKey.appBlueprintKey(APP_ID), billingKey())
                                                         .doesNotContain(new SliceTargetKey(ORDERS.base()), key(LIST));
        h.seed(AppBlueprintKey.appBlueprintKey(APP_ID), twoSlices("attempt-2"));
        h.drain();

        assertThat(h.applyHeld(billingOnly.getFirst().correlationId())).isInstanceOf(Settlement.Refused.class);
    }

    /// Red when: the gate also holds an UNLOADED goal: with the blueprint committed and its row gone from the mirror (removed while its
    /// re-declaration is in flight), a stray instance on a second node is condemned (goal UNLOADED) and the condemnation must still go out.
    @Test
    void gate_neverHoldsAnUnload() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1, CORE_2);

        h.activate();
        publish(h, blueprintOf("attempt-1", LIST));
        assertThat(loads(h)).as("control: loaded with the rows committed").hasSize(1);
        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.seedRemove(key(LIST));
        h.drain();
        assertThat(committed(h, LIST)).as("control: the row is gone and its re-declaration held").isEqualTo(Option.none());
        h.clearLog();
        node(h, CORE_2, 12).writes(ORDERS, SliceObservedState.ACTIVE, gen(1L));
        h.drain();
        assertThat(h.warns("stray-instance")).as("control: the stray is condemned").hasSize(1);
        assertThat(h.submits(SubmitKind.GOALS)
                    .stream()
                    .filter(submit -> submit.transaction().mutations().stream().anyMatch(mutation -> mutation.key().equals(new SliceDesiredKey(CORE_2, ORDERS))))
                    .flatMap(submit -> submit.transaction().guards().stream())
                    .filter(guard -> guard.key() instanceof DeclaredRouteKey)).as("an unload carries no row witness").isEmpty();
        assertThat(h.mutations(SliceDesiredKey.class)
                    .stream()
                    .filter(mutation -> mutation.key().equals(new SliceDesiredKey(CORE_2, ORDERS)))
                    .anyMatch(mutation -> mutation.replacement()
                                                  .map(value -> ((SliceDesiredValue) value).goal() == SliceGoal.UNLOADED)
                                                  .or(false))).isTrue();
    }

    /// Red when: an artifact that declares no routes is gated (a missing row set is read as "uncommitted"): every blueprint without routes
    /// would never load.
    @Test
    void gate_anArtifactWithoutRoutesLoadsAsBefore() {
        var h = leaderWithOneCore();

        publish(h, blueprintOf("attempt-1"));
        assertThat(loads(h)).hasSize(1);
        assertThat(h.submits(SubmitKind.GOALS).getFirst().transaction().guards().stream().filter(guard -> guard.key() instanceof DeclaredRouteKey)).isEmpty();
    }
}
