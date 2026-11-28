// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.pragmatica.aether.deployment.cluster.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeclaredRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRoute;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.RouteState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The declared-route table's PUBLISH (W2) and FAIL (W3) writers (#3389 A2 part 2), each driven through the harness in production order: the
/// node's plain Put is committed, the FSM reacts, the leader transaction is applied; a fatal FAILED row is committed, the FSM settles it.
class RoutesPublishFailTest {
    private static final String ATTEMPT_1 = "attempt-1";
    private static final String ATTEMPT_2 = "attempt-2";

    private static FsmHarness declaredApp() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);

        h.activate();
        publish(h, blueprintOf(ATTEMPT_1, LIST, GET_ONE));

        return h;
    }

    private static void failFatally(FsmHarness h) {
        var node = node(h, CORE_1, 11);

        node.writes(ORDERS, SliceObservedState.LOADING).fails(ORDERS, true);
        h.drain();
    }

    /// One transient failure of CORE_1's instance; `unloaded` also lets the node follow the UNLOAD that the failure writes.
    private static void transientFailure(FsmHarness h, NodeSim node, boolean unloaded) {
        h.advance(30_000L);
        node.writes(ORDERS, SliceObservedState.LOADING).fails(ORDERS, false);
        h.drain();
        if (unloaded) {
            node.follows(ORDERS);
            h.drain();
        }
    }

    private static Option<ArtifactJudgmentValue> judgment(FsmHarness h) {
        return h.store.get(new ArtifactJudgmentKey(ORDERS))
                      .filter(ArtifactJudgmentValue.class::isInstance)
                      .map(ArtifactJudgmentValue.class::cast);
    }

    private static boolean carriesRows(ClusterEffect.Submit submit) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(mutation -> mutation.key().equals(key(LIST)));
    }

    // -- W2: PUBLISH ------------------------------------------------------------------------------------------------
    /// Red when: the leader does not flip a Declared row on the node's committed routes Put (no `Routes` publication in the diff, or no event
    /// hook on the committed put): the rows stay Declared and every request for a served route answers 503.
    @Test
    void publish_aNodesRoutesFlipTheDeclaredRowsToPublished() {
        var h = declaredApp();

        assertThat(committed(h, LIST)).as("control: declared before any node publishes").isEqualTo(Option.some(declared(ATTEMPT_1)));
        publishRoutes(h, CORE_1);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(published(ATTEMPT_1)));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(published(ATTEMPT_1)));
    }

    /// Red when: the flip's `expected` is not the committed Declared row, or the sweep flips from lingering evidence: a flip derived for an
    /// earlier attemptId lands on a row that has since been reset for a later attempt. The Put's evidence is gone (the node unloaded) and the row
    /// belongs to attempt-2, as does its owner blueprint (a row of a later attempt than its owner speaks for nobody), when the held flip is
    /// applied: it must be refused, and no later pass may flip the row.
    @Test
    void publish_aFlipDerivedForAStaleAttemptDoesNotFlipTheRow() {
        var h = declaredApp();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.clearLog();
        publishRoutes(h, CORE_1);
        var flips = h.submits(SubmitKind.ROUTES).stream().filter(RoutesPublishFailTest::carriesRows).toList();

        assertThat(flips).as("the Put drafts the flip").hasSize(1);
        h.seedRemove(new NodeRoutesKey(CORE_1, ORDERS));
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf(ATTEMPT_2, LIST, GET_ONE));
        h.seed(key(LIST), declared(ATTEMPT_2));
        h.drain();
        var settlement = h.applyHeld(flips.getFirst().correlationId());

        assertThat(settlement).as("expected Declared at attempt-1, committed Declared at attempt-2").isInstanceOf(Settlement.Refused.class);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(declared(ATTEMPT_2)));
        h.holdKinds = Set.of();
        h.advance(h.settings.submissionExpiry() + 2 * h.settings.timing().tick());
        assertThat(committed(h, LIST)).as("no evidence, no flip").isEqualTo(Option.some(declared(ATTEMPT_2)));
    }

    /// Red when: a publication overwrites a Failed row: a late PUBLISHED must never undo FAILED (the flip may only expect Declared).
    @Test
    void publish_neverOverwritesAFailedRow() {
        var h = declaredApp();

        h.seed(key(LIST), failed(ATTEMPT_1, "boom"));
        h.drain();
        publishRoutes(h, CORE_1);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
        assertThat(committed(h, GET_ONE)).as("the row that is still Declared does flip").isEqualTo(Option.some(published(ATTEMPT_1)));
    }

    /// Red when: the sweep flips without evidence (a Declared row is published because a blueprint declared it, or on any node's routes): no node
    /// serves the route, and the row says it does.
    @Test
    void publish_withoutANodesRoutesTheRowsStayDeclared() {
        var h = declaredApp();

        h.advance(h.settings.submissionExpiry() + 2 * h.settings.timing().tick());
        assertThat(committed(h, LIST)).isEqualTo(Option.some(declared(ATTEMPT_1)));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(declared(ATTEMPT_1)));
    }

    /// Red when: the evidence is matched without the artifact's version (any `orders` routes publish the row): a node still serving the earlier
    /// version's routes marks the new version's routes as served.
    @Test
    void publish_anotherVersionsRoutesDoNotPublish() {
        var h = declaredApp();

        publishRoutes(h, CORE_1, ORDERS_V2);
        h.advance(h.settings.submissionExpiry() + 2 * h.settings.timing().tick());
        assertThat(committed(h, LIST)).isEqualTo(Option.some(declared(ATTEMPT_1)));
    }

    // -- W3: FAIL ---------------------------------------------------------------------------------------------------
    /// Red when: the Fatal FAILURE transaction does not carry the Declared rows' Failed mutations (no `Routes.fail` in `Failures.settle`): the
    /// judgment commits and the rows stay Declared. One transaction holds the judgment and every row.
    @Test
    void fail_theFatalFailureTransactionFailsTheDeclaredRows() {
        var h = declaredApp();

        failFatally(h);
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("control: the judgment is permanent").isEqualTo(Option.some(true));
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
        var carrying = h.submits(SubmitKind.FAILURE).stream().filter(RoutesPublishFailTest::carriesRows).toList();

        assertThat(carrying).as("one FAILURE transaction").hasSize(1);
        assertThat(carrying.getFirst().transaction().mutations().stream().map(mutation -> (AetherKey) mutation.key()).toList())
                  .as("the judgment and both rows ride together")
                  .contains(new ArtifactJudgmentKey(ORDERS), key(LIST), key(GET_ONE));
    }

    /// Red when: a row that is already Failed is rewritten by a later failure (its first reason is lost, and the transaction expects a row it
    /// does not need to touch).
    @Test
    void fail_aFailedRowKeepsItsReason() {
        var h = declaredApp();

        h.seed(key(LIST), failed(ATTEMPT_1, "earlier"));
        h.drain();
        failFatally(h);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_1, "earlier")));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
    }

    /// Red when: the Failed mutations ride the transaction in the order the mirror's index iterates (hash order) instead of key order: the
    /// transaction's mutation list, which is replayed and compared across leaders, is then not a function of the committed rows alone. Eight
    /// rows make a coincidental match of the two orders negligible.
    @Test
    void fail_theRowMutationsRideInKeyOrder() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);
        var routes = IntStream.rangeClosed(1, 8)
                              .mapToObj(i -> DeclaredRoute.declaredRoute("GET", "/r" + i))
                              .toArray(DeclaredRoute[]::new);

        h.activate();
        publish(h, blueprintOf(ATTEMPT_1, routes));
        failFatally(h);
        var rowKeys = h.submits(SubmitKind.FAILURE)
                       .stream()
                       .flatMap(submit -> submit.transaction().mutations().stream())
                       .map(mutation -> (AetherKey) mutation.key())
                       .filter(DeclaredRouteKey.class::isInstance)
                       .toList();

        assertThat(rowKeys).as("all eight rows ride the failure").hasSize(8);
        assertThat(rowKeys.stream().map(AetherKey::asString).toList()).isSorted();
    }

    /// Red when: a Published row is left out of the Failed set (only Declared is failed): the served-then-failed deploy keeps answering 503.
    @Test
    void fail_aPublishedRowFailsToo() {
        var h = declaredApp();

        publishRoutes(h, CORE_1);
        assertThat(committed(h, LIST)).as("control: published").isEqualTo(Option.some(published(ATTEMPT_1)));
        failFatally(h);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
    }

    /// Red when: the rows are failed in a transaction of their own: a refused FAILURE transaction then leaves the rows Failed (or the judgment
    /// committed without them). The held transaction is refused because a row moved under it; neither the judgment nor any row may change.
    @Test
    void fail_aRefusedFailureTransactionLeavesTheRowsAndTheJudgmentUnchanged() {
        var h = declaredApp();

        h.holdKinds = Set.of(SubmitKind.FAILURE);
        failFatally(h);
        var failure = h.submits(SubmitKind.FAILURE).stream().filter(RoutesPublishFailTest::carriesRows).toList();

        assertThat(failure).as("the failure drafts the rows").hasSize(1);
        h.seed(key(LIST), published(ATTEMPT_1));
        h.drain();
        var settlement = h.applyHeld(failure.getFirst().correlationId());

        assertThat(settlement).isInstanceOf(Settlement.Refused.class);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(published(ATTEMPT_1)));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(declared(ATTEMPT_1)));
        assertThat(judgment(h)).as("the judgment did not commit without the rows").isEqualTo(Option.none());
    }

    /// Red when: only `onFailed`'s fatal branch fails the rows: the sixth transient report exhausts the retries with the apply outstanding,
    /// `Failures.exhausted` settles the artifact, and the rows stay Declared although the judgment is permanent.
    @Test
    void fail_anExhaustedApplyFailsTheDeclaredRowsToo() {
        var h = declaredApp();
        var node = node(h, CORE_1, 11);

        for (var i = 0; i < 5; i++) {
            transientFailure(h, node, true);
        }

        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("control: five reports do not exhaust").isEqualTo(Option.some(false));
        assertThat(committed(h, LIST)).as("control: still declared").isEqualTo(Option.some(declared(ATTEMPT_1)));
        transientFailure(h, node, false);
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("control: report 6 exhausts").isEqualTo(Option.some(true));
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(failed(ATTEMPT_1, "boom")));
    }

    /// Red when: the exhaustion's rows are failed in a transaction of their own (or outside the FAILURE transaction): the refusal then leaves the
    /// rows Failed, or the permanent mark committed without them. The sixth report's transaction is refused because a row moved under it.
    @Test
    void fail_aRefusedExhaustionTransactionLeavesTheRowsAndTheJudgmentUnchanged() {
        var h = declaredApp();
        var node = node(h, CORE_1, 11);

        for (var i = 0; i < 5; i++) {
            transientFailure(h, node, true);
        }

        h.holdKinds = Set.of(SubmitKind.FAILURE);
        transientFailure(h, node, false);
        var exhaustion = h.submits(SubmitKind.FAILURE).stream().filter(RoutesPublishFailTest::carriesRows).toList();

        assertThat(exhaustion).as("the exhaustion drafts the rows").hasSize(1);
        h.seed(key(LIST), published(ATTEMPT_1));
        h.drain();
        var settlement = h.applyHeld(exhaustion.getFirst().correlationId());

        assertThat(settlement).isInstanceOf(Settlement.Refused.class);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(published(ATTEMPT_1)));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(declared(ATTEMPT_1)));
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("no Fatal judgment is recorded").isEqualTo(Option.some(false));
    }

    // -- ruling A (#3836): a row speaks only for its owner's current attemptId ------------------------------------------
    /// Red when: a publication derived while the owner is at an earlier attempt is applied to a row the later attempt has not re-declared. The
    /// flip is refused (owner witness) and the rows end `Published` at the owner's CURRENT attempt, never at the earlier one.
    @Test
    void publish_aFlipDerivedForAnEarlierAttemptIsRefusedAndTheRowsPublishAtTheCurrentAttempt() {
        var h = declaredApp();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.clearLog();
        publishRoutes(h, CORE_1);
        var flips = h.submits(SubmitKind.ROUTES).stream().filter(RoutesPublishFailTest::carriesRows).toList();

        assertThat(flips).as("the Put drafts the flip at attempt-1").hasSize(1);
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf(ATTEMPT_2, LIST, GET_ONE));
        h.drain();
        var settlement = h.applyHeld(flips.getFirst().correlationId());

        assertThat(settlement).as("the owner moved to attempt-2 under the flip").isInstanceOf(Settlement.Refused.class);
        h.holdKinds = Set.of();
        h.advance(h.settings.submissionExpiry() + 2 * h.settings.timing().tick());
        assertThat(committed(h, LIST)).as("published at the owner's current attempt").isEqualTo(Option.some(published(ATTEMPT_2)));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(published(ATTEMPT_2)));
    }

    /// Red when: `fail` restates a row at the attemptId the row holds: the failure of attempt-2's artifact is written over a row that still
    /// carries attempt-1 (its re-declaration is in flight), so the row reads as a Failed row of an earlier attempt.
    @Test
    void fail_writesTheOwnersCurrentAttemptId() {
        var h = declaredApp();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf(ATTEMPT_2, LIST, GET_ONE));
        h.drain();
        assertThat(committed(h, LIST)).as("control: the row still holds attempt-1").isEqualTo(Option.some(declared(ATTEMPT_1)));
        h.holdKinds = Set.of();
        failFatally(h);
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed(ATTEMPT_2, "boom")));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(failed(ATTEMPT_2, "boom")));
    }

    /// Red when (G2): a row kept from an earlier attempt is failed under the new attempt and the next reconcile re-declares it: the failed
    /// artifact's row must stay `Failed`, at the new attempt, until the attempt after.
    @Test
    void fail_aRowFailedUnderTheNewAttemptStaysFailedThroughAReconcile() {
        var h = declaredApp();

        publishRoutes(h, CORE_1);
        publish(h, blueprintOf(ATTEMPT_2, LIST, GET_ONE), Option.some(blueprintOf(ATTEMPT_1, LIST, GET_ONE)));
        failFatally(h);
        assertThat(committed(h, LIST)).as("control: the failure failed the row at attempt-2").isEqualTo(Option.some(failed(ATTEMPT_2, "boom")));
        h.deliver(new ClusterInput.Reconcile());
        h.drain();
        h.advance(h.settings.submissionExpiry() + 2 * h.settings.timing().tick());
        assertThat(committed(h, LIST)).as("the failed artifact's row stays Failed at attempt-2").isEqualTo(Option.some(failed(ATTEMPT_2, "boom")));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(failed(ATTEMPT_2, "boom")));
    }

    /// Red when (#3836 F2): a same-id ALL_OR_NOTHING rollback restores the blueprint under the rolled-back attemptId (#972: the ROLLED_BACK
    /// outcome fences to its attempt) and its rows, Failed at that very attempt, stay `Failed` for good: the restored blueprint's gate never
    /// opens although its slice is retryable. After the restore the rows speak for the restored blueprint again and the gate opens with no
    /// further publish, while the restored blueprint keeps the rolled-back attemptId and the outcome stays ROLLED_BACK at it.
    @Test
    void restore_aSameIdRollbackReopensTheGateAndKeepsTheRolledBackAttempt() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5, CORE_1);

        h.activate();
        publish(h, blueprintOf(ATTEMPT_1, LIST));
        publish(h, blueprintOf(ATTEMPT_2, LIST), Option.some(blueprintOf(ATTEMPT_1, LIST)));
        failFatally(h);
        for (int i = 0; i < 5; i++) {
            h.advance(h.settings.timing().tick());
        }
        var restored = h.store.get(new AppBlueprintKey(APP_ID))
                              .filter(AppBlueprintValue.class::isInstance)
                              .map(AppBlueprintValue.class::cast);

        assertThat(restored.map(AppBlueprintValue::attemptId)).as("#972: the restore carries the rolled-back attemptId")
                  .isEqualTo(Option.some(ATTEMPT_2));
        assertThat(h.store.get(new DeploymentOutcomeKey(APP_ID))
                          .map(outcome -> ((DeploymentOutcomeValue) outcome).status())).as("#972: the outcome closes ROLLED_BACK")
                  .isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
        assertThat(Routes.rowsCommitted(h.model(), ORDERS)).as("the restored blueprint's gate opens (row now %s)", committed(h, LIST))
                  .isTrue();
    }

    /// Red when (#3836 F1 and F2 together): 2.0.0 of the same base shares LIST with 1.0.0 and fails fatally under ALL_OR_NOTHING, so 1.0.0 is
    /// restored under its own committed attemptId and 2.0.0 is removed. Whoever was the claimant of the row, after the rollback the row has one
    /// claimant (a declarer that is still committed), the writes stop and the restored blueprint's gate opens with no further publish.
    @Test
    void restore_aDifferentIdRollbackLeavesOneClaimantAndAnOpenGate() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5, CORE_1);
        var upgrade = upgradeOf(ATTEMPT_2, LIST);

        h.activate();
        publish(h, blueprintOf(ATTEMPT_1, LIST));
        publish(h, upgrade, Option.some(blueprintOf(ATTEMPT_1, LIST)));
        failFatally(h);
        for (int i = 0; i < 5; i++) {
            h.advance(h.settings.timing().tick());
        }
        var before = h.submits(SubmitKind.ROUTES).size();

        for (int i = 0; i < 5; i++) {
            h.advance(h.settings.timing().tick());
        }
        assertThat(h.store.get(new AppBlueprintKey(upgrade.blueprint().id())).isPresent()).as("control: 2.0.0 is rolled back and removed")
                  .isFalse();
        assertThat(h.submits(SubmitKind.ROUTES).size() - before).as("no ROUTES write once the rollback settled").isZero();
        assertThat(committed(h, LIST).map(row -> ((DeclaredRouteValue) row).blueprintId())).as("the claimant is the restored 1.0.0")
                  .isEqualTo(Option.some(APP));
        assertThat(Routes.rowsCommitted(h.model(), ORDERS)).as("the restored blueprint's gate opens (row now %s)", committed(h, LIST))
                  .isTrue();
    }

    /// Red when: `fail` writes a row at the attemptId of the blueprint the ROW names instead of its claimant's. 2.0.0 is taking LIST over from
    /// 1.0.0 (the takeover is in flight, the row is still 1.0.0's) and the artifact fails: the row is failed for the claimant 2.0.0, at its
    /// attempt, under its id.
    @Test
    void fail_aRowStillOwnedByTheOldVersionIsFailedAtItsClaimant() {
        var h = declaredApp();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publish(h, upgradeOf(ATTEMPT_2, LIST), Option.some(blueprintOf(ATTEMPT_1, LIST, GET_ONE)));
        assertThat(committed(h, LIST).map(row -> ((DeclaredRouteValue) row).blueprintId())).as("control: the row is still 1.0.0's")
                  .isEqualTo(Option.some(APP));
        h.holdKinds = Set.of();
        failFatally(h);
        assertThat(committed(h, LIST)).as("failed at the claimant 2.0.0's attempt")
                  .isEqualTo(Option.some(DeclaredRouteValue.declaredRouteValue("org.example:app:2.0.0", ATTEMPT_2, RouteState.Failed.failed("boom"))));
    }

    private static AppBlueprintValue upgradeOf(String attempt, DeclaredRoute... routes) {
        var expanded = ExpandedBlueprint.expandedBlueprint(blueprintId("org.example:app:2.0.0"),
                                                           List.of(slice(ORDERS, 1, 1, Set.of())));

        return AppBlueprintValue.appBlueprintValue(expanded,
                                                   false,
                                                   attempt,
                                                   Map.of(ORDERS, List.of(routes)));
    }
}
