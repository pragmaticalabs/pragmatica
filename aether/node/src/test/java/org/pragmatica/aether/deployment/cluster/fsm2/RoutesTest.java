// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeclaredRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRoute;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRouteValue;
import org.pragmatica.aether.slice.kvstore.RouteState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The declared-route table's writers that follow the committed blueprint (#3389 A2 part 1): DECLARE (W1), its re-drive (W1b) and REMOVE (W4),
/// each driven through the harness in production order: the blueprint put or remove is committed, the FSM reacts, the ROUTES transaction is applied.
/// W5 and W6 have no writer of their own; `restoredBlueprint_redeclaresItsRoutes` pins that the restoring put is enough.
class RoutesTest {
    private static final String APP = "org.example:app:1.0.0";
    private static final BlueprintId APP_ID = blueprintId(APP);
    private static final DeclaredRoute LIST = DeclaredRoute.declaredRoute("GET", "/orders");
    private static final DeclaredRoute GET_ONE = DeclaredRoute.declaredRoute("GET", "/orders/{id}");

    private static DeclaredRouteKey key(DeclaredRoute route) {
        return DeclaredRouteKey.declaredRouteKey(ORDERS,
                                                 route.httpMethod(),
                                                 route.pathTemplate())
                               .unwrap();
    }

    private static DeclaredRouteValue row(String owner, String attempt, RouteState state) {
        return DeclaredRouteValue.declaredRouteValue(owner, attempt, state);
    }

    private static DeclaredRouteValue declared(String attempt) {
        return row(APP, attempt, RouteState.Declared.declared());
    }

    private static AppBlueprintValue blueprintOf(String attempt, boolean registerOnly, DeclaredRoute... routes) {
        var expanded = ExpandedBlueprint.expandedBlueprint(APP_ID,
                                                           List.of(slice(ORDERS, 1, 1, Set.of())));

        return AppBlueprintValue.appBlueprintValue(expanded,
                                                   registerOnly,
                                                   attempt,
                                                   Map.of(ORDERS, List.of(routes)));
    }

    private static Option<AetherValue> committed(FsmHarness h, DeclaredRoute route) {
        return h.store.get(key(route));
    }

    /// Red when: `Blueprints.onBlueprintPut` does not call `Routes.reconcile`, or calls it after the targets are drafted: no row commits, or the
    /// ROUTES transaction is sent behind the TARGETS one and a goal can be derived before the rows are committed.
    @Test
    void declare_draftsTheRowsAheadOfTheTargetsAndCommitsThemDeclared() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST, GET_ONE));
        assertThat(committed(h, LIST)).isEqualTo(Option.some(declared("attempt-1")));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(declared("attempt-1")));
        assertThat(h.submits(SubmitKind.ROUTES)).hasSize(1);
        var kinds = h.submits().stream().map(ClusterEffect.Submit::kind).toList();

        assertThat(kinds.indexOf(SubmitKind.ROUTES)).isLessThan(kinds.indexOf(SubmitKind.TARGETS));
        assertThat(h.model().declaredRoutes.size()).isEqualTo(2);
        assertThat(h.model().declaredByArtifact.get(ORDERS).map(keys -> keys.size())).isEqualTo(Option.some(2));
        assertThat(h.model().declaredByOwner.get(APP_ID).map(artifacts -> artifacts.contains(ORDERS))).isEqualTo(Option.some(true));
    }

    /// Red when: a registerOnly blueprint declares rows (it deploys nothing, so a row would say "declared, loading" for ever).
    @Test
    void declare_aRegisterOnlyBlueprintDeclaresNothing() {
        var h = active();

        publish(h, blueprintOf("attempt-1", true, LIST));
        assertThat(committed(h, LIST)).isEqualTo(Option.none());
        assertThat(h.submits(SubmitKind.ROUTES)).isEmpty();
    }

    /// Red when: the diff stops removing the rows of routes the republished blueprint no longer declares, or leaves a `Failed` row of the
    /// earlier attempt `Failed` (the new attempt is a new chance: Failed becomes Declared on the new attemptId).
    @Test
    void declare_republishRemovesUndeclaredRowsAndRedeclaresAFailedRowOnTheNewAttempt() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST, GET_ONE));
        h.seed(key(LIST), row(APP, "attempt-1", RouteState.Failed.failed("boom")));
        h.drain();
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-2", false, LIST));
        h.drain();
        assertThat(committed(h, LIST)).isEqualTo(Option.some(declared("attempt-2")));
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.none());
        assertThat(h.model().declaredRoutes.containsKey(key(GET_ONE))).isFalse();
        assertThat(h.model().declaredByArtifact.get(ORDERS).map(keys -> keys.size())).isEqualTo(Option.some(1));
    }

    /// Red when: the diff rewrites a `Failed` row of the SAME attempt (a failure is final for its attempt; only a new attempt redeclares).
    @Test
    void declare_leavesAFailedRowOfTheSameAttemptAlone() {
        var h = active();
        var failed = row(APP, "attempt-1", RouteState.Failed.failed("boom"));

        publish(h, blueprintOf("attempt-1", false, LIST));
        h.seed(key(LIST), failed);
        h.drain();
        h.clearLog();
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.submits(SubmitKind.ROUTES)).isEmpty();
        assertThat(committed(h, LIST)).isEqualTo(Option.some(failed));
    }

    /// Red when: the restoring put of a rollback or a restored backup (W5, W6) is not enough to bring the table back: the rows of a route the
    /// intermediate publish dropped must be declared again from the restored blueprint alone.
    @Test
    void restoredBlueprint_redeclaresItsRoutes() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST, GET_ONE));
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-2", false, LIST));
        h.drain();
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.none());
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-1", false, LIST, GET_ONE));
        h.drain();
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.some(declared("attempt-1")));
    }

    /// Red when: a ROUTES draft that was lost is not derived again: the sweep step of the reconcile is missing, or a lost submission keeps
    /// blocking the blueprint (the in-flight check must end with the submission's expiry).
    @Test
    void sweep_redrivesADeclareThatWasLost() {
        var h = active();

        h.loseKinds = Set.of(SubmitKind.ROUTES);
        publish(h, blueprintOf("attempt-1", false, LIST));
        assertThat(committed(h, LIST)).isEqualTo(Option.none());
        h.loseKinds = Set.of();
        h.advance(h.settings.submissionExpiry() + 2 * h.settings.timing().tick());
        assertThat(committed(h, LIST)).isEqualTo(Option.some(declared("attempt-1")));
    }

    /// Red when: the in-flight check is missing: every reconcile pass while the first ROUTES draft is unsettled sends the same draft again.
    @Test
    void sweep_doesNotRepeatADraftStillInFlight() {
        var h = active();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publish(h, blueprintOf("attempt-1", false, LIST));
        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.submits(SubmitKind.ROUTES)).hasSize(1);
    }

    /// Red when: `Routes.reconcile` is not called on a blueprint removal after the blueprint is unindexed (W4), or removes a row at a value other
    /// than the committed one: the rows and their indexes stay.
    @Test
    void remove_blueprintRemovalRemovesTheRowsItOwns() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST, GET_ONE));
        h.seedRemove(new AppBlueprintKey(APP_ID));
        h.drain();
        assertThat(committed(h, LIST)).isEqualTo(Option.none());
        assertThat(committed(h, GET_ONE)).isEqualTo(Option.none());
        assertThat(h.model().declaredRoutes.size()).isZero();
        assertThat(h.model().declaredByArtifact.size()).isZero();
        assertThat(h.model().declaredByOwner.size()).isZero();
    }

    /// Red when: the removal's own step does not draft the ROUTES removal (W4 is left to the next reconcile's sweep): the rows then outlive the
    /// blueprint by a reconcile pass, which the end state alone cannot show because the sweep reaches the same end state.
    @Test
    void remove_blueprintRemovalDraftsTheRowRemovalInItsOwnStep() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        var before = h.steps.size();

        h.seedRemove(new AppBlueprintKey(APP_ID));
        h.drain();
        assertThat(h.steps.get(before)
                          .stream()
                          .anyMatch(effect -> effect instanceof ClusterEffect.Submit submit && submit.kind() == SubmitKind.ROUTES)).isTrue();
    }

    /// Red when: the sweep considers only committed blueprints: rows whose owner is gone (a removal draft lost with its leader) are never removed.
    @Test
    void sweep_removesTheRowsOfABlueprintThatIsGone() {
        var h = active();
        var orphan = row("org.example:gone:1.0.0", "attempt-9", RouteState.Declared.declared());

        h.seed(key(LIST), orphan);
        h.drain();
        assertThat(committed(h, LIST)).isEqualTo(Option.some(orphan));
        h.deliver(new ClusterInput.Reconcile());
        assertThat(committed(h, LIST)).isEqualTo(Option.none());
    }

    /// Red when: the owner index keeps the artifact of a row another blueprint took over, or the by-artifact index keeps a removed key.
    @Test
    void indexer_aTakenOverRowLeavesItsOldOwnersSet() {
        var h = active();
        var other = blueprintId("org.example:other:1.0.0");

        h.seed(key(LIST), declared("attempt-1"));
        h.drain();
        assertThat(h.model().declaredByOwner.get(APP_ID).map(artifacts -> artifacts.contains(ORDERS))).isEqualTo(Option.some(true));
        h.seed(key(LIST),
               row(other.asString(), "attempt-1", RouteState.Declared.declared()));
        h.drain();
        assertThat(h.model().declaredByOwner.get(APP_ID)).isEqualTo(Option.none());
        assertThat(h.model().declaredByOwner.get(other).map(artifacts -> artifacts.contains(ORDERS))).isEqualTo(Option.some(true));
    }
}
