// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
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

    /// Red when: the removal's own step does not queue the ROUTES removal (W4 is left to the next reconcile's sweep): the rows then outlive the
    /// blueprint by a reconcile pass, which the end state alone cannot show because the sweep reaches the same end state. The one input is
    /// stepped without a drain, so the outbox holds what the removal step itself drafted, before the flush and before any reconcile.
    @Test
    void remove_blueprintRemovalQueuesTheRowRemovalInItsOwnStep() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        h.notifying = false;
        h.seedRemove(new AppBlueprintKey(APP_ID));
        h.notifying = true;
        h.step(new ClusterInput.KvRemove(new AppBlueprintKey(APP_ID)));
        assertThat(h.model().outbox.stream().filter(draft -> draft.kind() == SubmitKind.ROUTES)).hasSize(1);
    }

    /// Red when: the sweep considers only committed blueprints: rows whose owner is gone (a removal draft lost with its leader) are never removed.
    @Test
    void sweep_removesTheRowsOfABlueprintThatIsGone() {
        var h = active();
        var orphan = row("org.example:gone:1.0.0", "attempt-9", RouteState.Declared.declared());

        // A committed row requests the reconcile (#3389), so the removal is drafted at once; it is held to show the row committed first.
        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.seed(key(LIST), orphan);
        h.drain();
        assertThat(committed(h, LIST)).isEqualTo(Option.some(orphan));
        assertThat(h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.ROUTES)).as("the sweep drafted the removal").hasSize(1);
        releaseHeld(h);
        assertThat(committed(h, LIST)).isEqualTo(Option.none());
    }

    /// Red when: the owner index keeps the artifact of a row another blueprint took over, or the by-artifact index keeps a removed key.
    @Test
    void indexer_aTakenOverRowLeavesItsOldOwnersSet() {
        var h = active();
        var other = blueprintId("org.example:other:1.0.0");

        // A committed row requests the reconcile (#3389), whose sweep would remove these ownerless rows: the removal is held in flight.
        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.seed(key(LIST), declared("attempt-1"));
        h.drain();
        assertThat(h.model().declaredByOwner.get(APP_ID).map(artifacts -> artifacts.contains(ORDERS))).isEqualTo(Option.some(true));
        h.seed(key(LIST),
               row(other.asString(), "attempt-1", RouteState.Declared.declared()));
        h.drain();
        assertThat(h.model().declaredByOwner.get(APP_ID)).isEqualTo(Option.none());
        assertThat(h.model().declaredByOwner.get(other).map(artifacts -> artifacts.contains(ORDERS))).isEqualTo(Option.some(true));
    }

    // -- #3836 round 3: a declared route row speaks for exactly one claimant ---------------------------------------------------------------
    private static final BlueprintId APP_V2_ID = blueprintId("org.example:app:2.0.0");

    /// The upgrade of `APP`: the same base at 2.0.0, one ORDERS instance, declaring `routes`. C2 lets it coexist with 1.0.0 (same base).
    private static AppBlueprintValue upgradeOf(String attempt, DeclaredRoute... routes) {
        var expanded = ExpandedBlueprint.expandedBlueprint(APP_V2_ID,
                                                           List.of(slice(ORDERS, 1, 1, Set.of())));

        return AppBlueprintValue.appBlueprintValue(expanded,
                                                   false,
                                                   attempt,
                                                   Map.of(ORDERS, List.of(routes)));
    }

    private static Option<String> ownerOf(FsmHarness h, DeclaredRoute route) {
        return committed(h, route).filter(DeclaredRouteValue.class::isInstance)
                                  .map(DeclaredRouteValue.class::cast)
                                  .map(DeclaredRouteValue::blueprintId);
    }

    private static void ticks(FsmHarness h, int count) {
        for (int i = 0; i < count; i++) {
            h.advance(h.settings.timing().tick());
        }
    }

    /// Red when: two live versions of one base that declare the same route each take the row from the other (each sees the other's row as
    /// `STALE` and re-declares it), so the table never settles and the sweep drafts a ROUTES write on every pass. The claimant of a key is
    /// one function of the committed values: after both blueprints are committed the writes stop, one version owns the row, the shared
    /// artifact's gate counts it, and a new leader that rebuilds from the same committed state writes nothing either.
    @Test
    void claimant_twoVersionsDeclaringOneRowSettleWithOneClaimant() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        assertThat(ownerOf(h, LIST)).as("control: 1.0.0 owns the row while it is alone").isEqualTo(Option.some(APP));
        publish(h, upgradeOf("attempt-b", LIST), Option.some(blueprintOf("attempt-1", false, LIST)));
        var before = h.submits(SubmitKind.ROUTES).size();

        ticks(h, 5);
        var settled = ownerOf(h, LIST);

        assertThat(h.submits(SubmitKind.ROUTES).size() - before).as("ROUTES writes over 5 ticks with both versions committed (owner now %s)",
                                                                   settled)
                  .isZero();
        assertThat(settled.map(owner -> owner.equals(APP) || owner.equals(APP_V2_ID.asString()))).as("the row has a claimant: a declarer")
                  .isEqualTo(Option.some(true));
        assertThat(Routes.rowsCommitted(h.model(), ORDERS)).as("the shared artifact's gate counts the row").isTrue();
        h.deliver(new ClusterInput.Deactivate());
        h.activate();
        before = h.submits(SubmitKind.ROUTES).size();
        ticks(h, 5);
        assertThat(h.submits(SubmitKind.ROUTES).size() - before).as("a leader that rebuilds the same committed state writes nothing").isZero();
        assertThat(ownerOf(h, LIST)).as("and picks the same claimant").isEqualTo(settled);
    }

    /// Red when: a version writes, fails or removes a row it is not the claimant of. 1.0.0 declares LIST and GET_ONE, 2.0.0 only LIST: the
    /// shared LIST row settles on one claimant, and the GET_ONE row, which only 1.0.0 declares, never goes to or is taken by 2.0.0.
    @Test
    void claimant_aRowOnlyOneVersionDeclaresIsNeverWrittenByTheOther() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST, GET_ONE));
        publish(h, upgradeOf("attempt-b", LIST), Option.some(blueprintOf("attempt-1", false, LIST, GET_ONE)));
        var before = h.submits(SubmitKind.ROUTES).size();

        ticks(h, 5);
        assertThat(h.submits(SubmitKind.ROUTES).size() - before).as("ROUTES writes over 5 ticks with both versions committed").isZero();
        assertThat(ownerOf(h, GET_ONE)).as("GET_ONE is declared by 1.0.0 alone").isIn(Option.some(APP), Option.none());
    }

    /// Red when: `removal` (W4) stops filtering on the row's owner: a version that does not declare a route removes the row another live
    /// version of its base owns. 2.0.0 declares GET_ONE only, 1.0.0 declares LIST only; neither row is the other's to remove.
    @Test
    void removal_aRowAnotherVersionOwnsIsNeverRemoved() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        publish(h, upgradeOf("attempt-b", GET_ONE), Option.some(blueprintOf("attempt-1", false, LIST)));
        assertThat(ownerOf(h, GET_ONE)).as("control: 2.0.0 declared GET_ONE").isEqualTo(Option.some(APP_V2_ID.asString()));
        h.clearLog();
        ticks(h, 3);
        assertThat(ownerOf(h, GET_ONE)).as("2.0.0's row stays").isEqualTo(Option.some(APP_V2_ID.asString()));
        assertThat(ownerOf(h, LIST)).as("1.0.0's row stays").isEqualTo(Option.some(APP));
        assertThat(h.submits(SubmitKind.ROUTES)).as("no ROUTES write once both are declared").isEmpty();
    }

    /// Red when: DECLARE takes the standing of a committed row from its attemptId alone (or a gate or removal does): a row another blueprint owns,
    /// at the very attemptId the claimant is committed at, then reads as the claimant's own and is never taken over. APP is the only declarer, so it
    /// is the claimant; the foreign row is `Published` at its attempt, so only the owner check can tell it is not APP's.
    @Test
    void declare_aForeignOwnersRowAtTheClaimantsCurrentAttemptIsTakenOver() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        h.seed(key(LIST), row("org.example:other:1.0.0", "attempt-1", RouteState.Published.published()));
        h.drain();
        ticks(h, 3);
        assertThat(ownerOf(h, LIST)).as("the claimant declares the row over the foreign owner's").isEqualTo(Option.some(APP));
        assertThat(Routes.rowsCommitted(h.model(), ORDERS)).as("and the gate counts it as the claimant's row").isTrue();
    }

    /// Red when: removing the claimant of a shared row removes the row (or leaves it with a blueprint that is gone) while another committed
    /// version still declares it. The claimant moves to the remaining declarer within the ticks that follow, and once the last declarer is
    /// gone the row goes with it: no row is ever left without a claimant while a declarer exists, and none outlives its last declarer.
    @Test
    void claimant_removingTheClaimantMovesTheRowToTheRemainingDeclarer() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        publish(h, upgradeOf("attempt-b", LIST), Option.some(blueprintOf("attempt-1", false, LIST)));
        ticks(h, 3);
        var firstIsV1 = ownerOf(h, LIST).equals(Option.some(APP));
        var first = firstIsV1 ? APP_ID : APP_V2_ID;
        var second = firstIsV1 ? APP_V2_ID : APP_ID;

        h.seedRemove(new AppBlueprintKey(first));
        h.drain();
        ticks(h, 3);
        assertThat(ownerOf(h, LIST)).as("the remaining declarer claims the row").isEqualTo(Option.some(second.asString()));
        h.seedRemove(new AppBlueprintKey(second));
        h.drain();
        ticks(h, 3);
        assertThat(committed(h, LIST)).as("the last declarer's removal removes the row").isEqualTo(Option.none());
    }

    private static List<ClusterEffect.Submit> heldRoutes(FsmHarness h) {
        return h.held.values()
                 .stream()
                 .filter(submit -> submit.kind() == SubmitKind.ROUTES)
                 .toList();
    }

    /// Red when: `removal` stops asking whether a row's key has a claimant by ownership alone (a version that does not declare a row removes any
    /// row nobody claims, so two versions each draft the same removal). 1.0.0 stops declaring LIST while 2.0.0 (GET_ONE only) shares its artifact:
    /// only the owner, 1.0.0, drafts the removal.
    @Test
    void removal_aRowNobodyClaimsIsDraftedForRemovalByItsOwnerAlone() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        publish(h, upgradeOf("attempt-b", GET_ONE), Option.some(blueprintOf("attempt-1", false, LIST)));
        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-2", false));
        h.drain();
        assertThat(heldRoutes(h)).as("one removal draft for LIST, by its owner 1.0.0, not by 2.0.0 as well").hasSize(1);
    }

    /// Red when: `removal` removes a row whose key another committed version claims. 2.0.0 takes over LIST from 1.0.0 (its takeover is in flight,
    /// the row is still 1.0.0's) and 1.0.0 then stops declaring LIST: 1.0.0 must not draft a removal of the row 2.0.0 is taking over.
    @Test
    void removal_aRowAnotherVersionClaimsIsTakenOverAndNeverRemoved() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publish(h, upgradeOf("attempt-b", LIST), Option.some(blueprintOf("attempt-1", false, LIST)));
        assertThat(heldRoutes(h)).as("control: 2.0.0's takeover of LIST is in flight").hasSize(1);
        assertThat(ownerOf(h, LIST)).as("control: the row is still 1.0.0's").isEqualTo(Option.some(APP));
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-2", false));
        h.drain();
        assertThat(heldRoutes(h)).as("1.0.0 drafts no removal of a row 2.0.0 claims").hasSize(1);
    }

    /// Red when: the SliceTarget owner stops being the claimant of a key it declares: 2.0.0 is committed and declares LIST too, but the
    /// SliceTarget row (its TARGETS batch is held) still names 1.0.0, which declares LIST, so 1.0.0 keeps the row whatever the id order, the
    /// gate (which reads the same owner) counts it and nothing is written.
    @Test
    void claimant_theTargetOwnerKeepsARowWhileAGreaterVersionAlsoDeclaresIt() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        h.holdKinds = Set.of(SubmitKind.TARGETS);
        publish(h, upgradeOf("attempt-b", LIST), Option.some(blueprintOf("attempt-1", false, LIST)));
        h.clearLog();
        ticks(h, 5);
        assertThat(ownerOf(h, LIST)).as("the target owner 1.0.0 claims LIST over the greater id 2.0.0").isEqualTo(Option.some(APP));
        assertThat(Routes.rowsCommitted(h.model(), ORDERS)).as("the gate reads the same owner").isTrue();
        assertThat(h.submits(SubmitKind.ROUTES)).as("no ROUTES write").isEmpty();
    }

    /// Red when: a put classified as a new attempt is treated as a restore (it bypasses the in-flight check): a republish under a fresh attemptId
    /// repeats the ROUTES draft already in flight, which the sweep derives again anyway if the applier refuses it.
    @Test
    void put_aNewAttemptDoesNotRepeatARoutesDraftInFlight() {
        var h = active();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publish(h, blueprintOf("attempt-1", false, LIST));
        assertThat(heldRoutes(h)).as("control: the first put drafted the rows").hasSize(1);
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-2", false, LIST));
        h.drain();
        assertThat(heldRoutes(h)).as("a new attempt waits for the draft in flight").hasSize(1);
    }

    /// Red when: a restore (a put under the attemptId the mirror already holds) waits for the ROUTES draft in flight: that draft carries the
    /// witness of the blueprint value the restore just replaced, so the applier refuses it, and the restore's own rows are never drafted.
    @Test
    void put_aRestoreDraftsItsRowsAlthoughARoutesDraftIsInFlight() {
        var h = active();

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publish(h, blueprintOf("attempt-1", false, LIST));
        assertThat(heldRoutes(h)).as("control: the first put drafted its rows").hasSize(1);
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-1", false, LIST, GET_ONE));
        h.drain();
        assertThat(heldRoutes(h)).as("the restore drafts its own rows").hasSize(2);
    }

    /// Red when: `inFlight` counts a draft of another kind that carries the blueprint's witness (a TARGETS batch does): a row made stale while
    /// a TARGETS draft of the blueprint is in flight is then never re-declared until that draft settles.
    @Test
    void sweep_aTargetsDraftInFlightDoesNotHoldBackTheRoutesRedrive() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        h.holdKinds = Set.of(SubmitKind.TARGETS);
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-2", false, LIST));
        h.drain();
        assertThat(h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.TARGETS)).as("control: a TARGETS draft is in flight")
                  .isNotEmpty();
        h.seed(key(LIST), row(APP, "attempt-1", RouteState.Declared.declared()));
        h.drain();
        assertThat(committed(h, LIST)).as("the stale row is re-declared at once").isEqualTo(Option.some(declared("attempt-2")));
    }

    /// Red when: the sweep stops ordering the blueprints it reconciles by id: eight rows of gone owners are drafted for removal in whatever order
    /// the model's maps iterate, not in id order, and a leader that rebuilds the same state drafts them in another.
    @Test
    void sweep_draftsInBlueprintIdOrder() {
        var h = active();
        var paths = List.of("/s8", "/s7", "/s6", "/s5", "/s4", "/s3", "/s2", "/s1");

        h.holdKinds = Set.of(SubmitKind.ROUTES);
        paths.forEach(path -> h.seed(key(DeclaredRoute.declaredRoute("GET", path)),
                                     row("org.example:" + path.substring(1) + ":1.0.0", "attempt-1", RouteState.Declared.declared())));
        h.drain();
        var drafted = heldRoutes(h).stream()
                                   .map(submit -> ((DeclaredRouteKey) submit.transaction().mutations().getFirst().key()).asString())
                                   .toList();

        assertThat(drafted).as("one removal draft per gone owner").hasSize(8);
        assertThat(drafted).as("in blueprint id order").isEqualTo(drafted.stream().sorted().toList());
    }

    /// Pins the one transient the claimant rule has, and that it heals. A ROUTES draft is guarded on its own blueprint's row only: a draft derived
    /// while 1.0.0 was the claimant commits after the SliceTarget moved to 2.0.0 and writes the row at 1.0.0 (a witness cannot cover the target
    /// row alone: a version put later, a greater declarer, is a row that did not exist when the draft was derived). Meanwhile the gate, which
    /// reads the target owner, holds the goal (the row is `STALE` for 2.0.0); 2.0.0's own takeover draft, derived over the earlier row, is refused,
    /// and the next sweep re-derives it: the row moves to the claimant within one sweep.
    @Test
    void claimant_aDraftDerivedBeforeTheTargetMovedCommitsAtTheOldClaimantAndTheSweepHealsIt() {
        var h = active();

        publish(h, blueprintOf("attempt-1", false, LIST));
        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-2", false, LIST));
        h.drain();
        var firstDraft = heldRoutes(h).getFirst();

        publish(h, upgradeOf("attempt-b", LIST), Option.some(blueprintOf("attempt-2", false, LIST)));
        assertThat(heldRoutes(h)).as("control: 1.0.0's redeclare and 2.0.0's takeover are both in flight").hasSize(2);
        var secondDraft = heldRoutes(h).getLast();

        h.holdKinds = Set.of();
        var late = h.applyHeld(firstDraft.correlationId());

        h.settle(firstDraft, late);
        h.drain();
        assertThat(ownerOf(h, LIST)).as("the late draft wrote the row at the old claimant").isEqualTo(Option.some(APP));
        assertThat(Routes.rowsCommitted(h.model(), ORDERS)).as("the gate, reading the target owner 2.0.0, does not count it").isFalse();
        var refused = h.applyHeld(secondDraft.correlationId());

        h.settle(secondDraft, refused);
        h.drain();
        ticks(h, 3);
        assertThat(refused).as("2.0.0's takeover was derived over the row 1.0.0's draft replaced").isInstanceOf(Settlement.Refused.class);
        assertThat(ownerOf(h, LIST)).as("the sweep moved the row to the claimant").isEqualTo(Option.some(APP_V2_ID.asString()));
        assertThat(Routes.rowsCommitted(h.model(), ORDERS)).as("and the gate opens").isTrue();
    }

    private static void releaseHeld(FsmHarness h) {
        new ArrayList<>(h.held.values()).forEach(submit -> {
            h.applyHeld(submit.correlationId());
            h.settleHeld(submit, new Settlement.Accepted());
        });
    }
}
