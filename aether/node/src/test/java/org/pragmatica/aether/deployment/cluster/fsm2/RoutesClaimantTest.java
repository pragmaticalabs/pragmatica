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
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRoute;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeclaredRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.APP;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.APP_ID;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.GET_ONE;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.LIST;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.blueprintOf;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.committed;
import static org.pragmatica.aether.deployment.cluster.fsm2.RouteFixtures.publishRoutes;
import static org.assertj.core.api.Assertions.assertThat;

/// The claimant transient of a declared route row and its heal bound (#3836, ruling R1), and the claimant rule's fallback arm.
///
/// The bound: the row reaches its claimant at the first tick after the claimant has no ROUTES draft in flight (at most submission expiry plus one
/// tick); on a suspended node at the resume, or at the new leader's activation reconcile. Safety in the meantime: the ORDERS gate
/// (`Routes.rowsCommitted`) stays closed until the row reaches its claimant. Enforcers: the gate; `Lifecycle.tick`, then `Reconcile.run`, then
/// `Routes.sweep`. Every heal test spends POST_ACTIVATION first ([#settledLeader]), so only the input it names can heal the row.
class RoutesClaimantTest {
    private static final BlueprintId V2 = blueprintId("org.example:app:2.0.0");
    private static final BlueprintId V3 = blueprintId("org.example:app:3.0.0");

    private static AppBlueprintValue version(BlueprintId id, String attempt, DeclaredRoute... routes) {
        var expanded = ExpandedBlueprint.expandedBlueprint(id, List.of(slice(ORDERS, 1, 1, Set.of())));

        return AppBlueprintValue.appBlueprintValue(expanded, false, attempt, Map.of(ORDERS, List.of(routes)));
    }

    private static Option<String> ownerOf(FsmHarness h, DeclaredRoute route) {
        return committed(h, route).filter(DeclaredRouteValue.class::isInstance)
                                  .map(DeclaredRouteValue.class::cast)
                                  .map(DeclaredRouteValue::blueprintId);
    }

    private static List<ClusterEffect.Submit> heldRoutes(FsmHarness h) {
        return h.held.values().stream().filter(s -> s.kind() == SubmitKind.ROUTES).toList();
    }

    private static void ticks(FsmHarness h, int count) {
        for (int i = 0; i < count; i++) {
            h.advance(h.settings.timing().tick());
        }
    }

    private static boolean gate(FsmHarness h) {
        return Routes.rowsCommitted(h.model(), ORDERS);
    }

    /// An active leader whose post-activation reconcile has already fired, so a later heal is owed to the input the test names.
    private static FsmHarness settledLeader() {
        var h = active();

        h.advance(h.settings.timing().postActivation() + 1);
        return h;
    }

    /// The author's interleaving up to the refusal: 1.0.0's late redeclare commits after the target moved to 2.0.0; 2.0.0's takeover, derived over
    /// the earlier row, is returned (not yet applied) so each test decides its fate. Gate closed at every step.
    private static ClusterEffect.Submit transient_(FsmHarness h, List<String> trace) {
        publish(h, blueprintOf("attempt-1", LIST));
        trace.add("t0 gate=" + gate(h));
        h.holdKinds = Set.of(SubmitKind.ROUTES);
        h.seed(new AppBlueprintKey(APP_ID), blueprintOf("attempt-2", LIST));
        h.drain();
        var firstDraft = heldRoutes(h).getFirst();

        publish(h, version(V2, "attempt-b", LIST), Option.some(blueprintOf("attempt-2", LIST)));
        assertThat(heldRoutes(h)).as("control: 1.0.0's redeclare and 2.0.0's takeover are both in flight").hasSize(2);
        assertThat(gate(h)).as("gate closed: both drafts in flight").isFalse();
        var secondDraft = heldRoutes(h).getLast();

        h.holdKinds = Set.of();
        h.settle(firstDraft, h.applyHeld(firstDraft.correlationId()));
        h.drain();
        assertThat(ownerOf(h, LIST)).as("control: the late draft wrote the row at the old claimant").isEqualTo(Option.some(APP));
        assertThat(gate(h)).as("gate closed: the row is 1.0.0's, the target owner is 2.0.0").isFalse();
        return secondDraft;
    }

    /// Red when: `Lifecycle.tick` does not request the reconcile (no heal at the tick: the row stays at the old claimant for good unless a put or a
    /// leader change comes), or something other than the tick heals it earlier (a put-triggered heal, another timer), or the gate opens before the
    /// row reached its claimant. POST_ACTIVATION is spent first, so the tick is the only input.
    @Test
    void heal_aRefusedTakeoverKeepsTheGateClosedUntilTheTick() {
        var h = settledLeader();
        var trace = new ArrayList<String>();
        var takeover = transient_(h, trace);
        var refused = h.applyHeld(takeover.correlationId());

        assertThat(refused).as("control: refused").isInstanceOf(Settlement.Refused.class);
        h.settle(takeover, refused);
        h.drain();
        assertThat(ownerOf(h, LIST)).as("after the refusal: still 1.0.0").isEqualTo(Option.some(APP));
        assertThat(gate(h)).as("gate closed after the refusal").isFalse();
        var untilTick = (h.timers.stream()
                                 .filter(d -> d.timer().kind() == TimerId.Kind.TICK)
                                 .mapToLong(FsmHarness.Due::atNs)
                                 .min()
                                 .orElseThrow() - h.monoNs) / 1_000_000L;

        h.advance(untilTick - 1_000);
        assertThat(ownerOf(h, LIST)).as("one second before the next tick (%s ms away): still 1.0.0 (no put, no other timer heals); timers %s", untilTick,
                                        h.timers.stream().map(d -> d.timer().kind()).toList()).isEqualTo(Option.some(APP));
        assertThat(gate(h)).as("gate closed one second before the tick").isFalse();
        h.advance(1_000);
        assertThat(ownerOf(h, LIST)).as("at the tick: the claimant 2.0.0").isEqualTo(Option.some(V2.asString()));
        assertThat(gate(h)).as("gate opens at the heal").isTrue();
    }

    /// Red when: `Routes.inFlight` stops skipping a claimant whose takeover is lost (a second draft goes out beside the lost one, so the heal comes
    /// at the first tick instead of at expiry plus a tick), or the gate opens while the row is unhealed. The takeover is never settled: it stays in
    /// flight until the submission expires.
    @Test
    void heal_aLostTakeoverWaitsForSubmissionExpiryPlusATick() {
        var h = settledLeader();
        var takeover = transient_(h, new ArrayList<>());

        h.held.remove(takeover.correlationId());
        var healedAt = -1;

        for (int tick = 1; tick <= 6 && healedAt < 0; tick++) {
            ticks(h, 1);
            if (ownerOf(h, LIST).equals(Option.some(V2.asString()))) {
                healedAt = tick;
            } else {
                assertThat(gate(h)).as("gate closed while unhealed, tick %s", tick).isFalse();
            }
        }
        assertThat(healedAt).as("ticks to heal a lost takeover (expiry %s ms, tick %s ms)",
                                h.settings.submissionExpiry(), h.settings.timing().tick())
                            .isGreaterThan(1);
        assertThat(gate(h)).as("gate opens at the heal").isTrue();
    }

    /// Red when: the resume (`Lifecycle.onLeader`) does not request the reconcile (the suspended node's row waits for the next leader), or a tick
    /// heals the row while another node is the committed leader. Ticks heal nothing while suspended; the resume heals without a tick.
    @Test
    void heal_aSuspensionHealsAtTheResumeNotAtATick() {
        var h = settledLeader();
        var takeover = transient_(h, new ArrayList<>());
        var refused = h.applyHeld(takeover.correlationId());

        h.settle(takeover, refused);
        h.drain();
        h.commitLeader(CORE_2, 2L);
        h.drain();
        ticks(h, 3);
        assertThat(ownerOf(h, LIST)).as("suspended: 3 ticks heal nothing").isEqualTo(Option.some(APP));
        assertThat(gate(h)).as("gate closed while suspended").isFalse();
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        assertThat(ownerOf(h, LIST)).as("the resume's reconcile heals without a tick").isEqualTo(Option.some(V2.asString()));
    }

    /// Red when: the activation reconcile does not run `Routes.sweep` (a new leader leaves the row at the old claimant until its first tick), or the
    /// gate stays closed after it. A deposed and re-activated incarnation heals without a tick.
    @Test
    void heal_aLeaderChangeHealsAtTheActivationReconcile() {
        var h = settledLeader();
        var takeover = transient_(h, new ArrayList<>());
        var refused = h.applyHeld(takeover.correlationId());

        h.settle(takeover, refused);
        h.drain();
        h.deliver(new ClusterInput.Deactivate());
        h.activate();
        assertThat(ownerOf(h, LIST)).as("the activation reconcile heals without a tick").isEqualTo(Option.some(V2.asString()));
        assertThat(gate(h)).as("and the gate opens").isTrue();
    }

    /// Red when (ruling R2, one ROUTES draft in flight per blueprint): the put whose notification lags behind a refusal reindex is classified
    /// `RESTORED` (the reindex put the new value in the mirror without `onBlueprintPut`) and the restore's bypass of `Routes.inFlight` submits a
    /// second ROUTES draft beside the sweep's, which is derived over that very value and carries its witness. A draft in flight that is still
    /// current (its blueprint witness is the value the mirror holds) is never repeated.
    @Test
    void put_aNewAttemptReindexedByARefusalAddsNoSecondRoutesDraft() {
        var h = settledLeader();

        publish(h, blueprintOf("attempt-1", LIST));
        h.holdKinds = Set.of(SubmitKind.ROUTES);
        publishRoutes(h, CORE_1);
        var flip = heldRoutes(h);

        assertThat(flip).as("control: 1.0.0's flip is in flight").hasSize(1);
        var value = blueprintOf("attempt-2", LIST);
        var outcome = DeploymentOutcomeValue.inProgress(h.wallMs, 2L, "attempt-2");
        var apply = new DeploymentApplyValue("attempt-2", Option.some(blueprintOf("attempt-1", LIST)), h.wallMs);

        h.seedQuietly(new DeploymentOutcomeKey(APP_ID), outcome);
        h.seedQuietly(new DeploymentApplyKey(APP_ID), apply);
        h.seedQuietly(new AppBlueprintKey(APP_ID), value);
        var refused = h.applyHeld(flip.getFirst().correlationId());

        assertThat(refused).as("control: the flip is refused (its witness moved)").isInstanceOf(Settlement.Refused.class);
        h.settle(flip.getFirst(), refused);
        h.drain();
        assertThat(h.model().blueprints.get(APP_ID).map(AppBlueprintValue::attemptId)).as("control: the reindex put attempt-2 in the mirror")
                                                                                       .isEqualTo(Option.some("attempt-2"));
        ticks(h, 1);
        assertThat(heldRoutes(h)).as("control: the sweep's redeclare at attempt-2 is in flight").hasSize(1);
        assertThat(Routes.classify(h.model().blueprints.get(APP_ID), value)).as("the late put classifies").isEqualTo(Routes.Put.RESTORED);
        var before = h.submits(SubmitKind.ROUTES).size();

        h.deliver(new ClusterInput.KvPut(new DeploymentOutcomeKey(APP_ID), outcome));
        h.deliver(new ClusterInput.KvPut(new DeploymentApplyKey(APP_ID), apply));
        h.deliver(new ClusterInput.KvPut(new AppBlueprintKey(APP_ID), value));
        var extra = h.submits(SubmitKind.ROUTES).size() - before;
        var inFlight = heldRoutes(h).size();

        assertThat(inFlight).as("exactly one ROUTES draft in flight after the put (the sweep's redeclare)").isEqualTo(1);

        h.holdKinds = Set.of();
        new ArrayList<>(h.held.values()).forEach(s -> {
            h.settle(s, h.applyHeld(s.correlationId()));
            h.drain();
        });
        ticks(h, 2);
        var settledRow = committed(h, LIST);
        var after = h.submits(SubmitKind.ROUTES).size();

        ticks(h, 3);
        assertThat(h.submits(SubmitKind.ROUTES).size() - after).as("converged: no ROUTES write over 3 ticks (row %s)", settledRow).isZero();
        assertThat(ownerOf(h, LIST)).as("row owner").isEqualTo(Option.some(APP));
        assertThat(extra).as("ROUTES drafts submitted by the late put (in flight now %s; row after %s)", inFlight, settledRow).isZero();
    }

    /// Red when: two live versions of one base that declare LIST, 1.0.0 published by a node and 2.0.0 the upgrade, do not settle: over 3 sweeps
    /// after settling there is a ROUTES write, the row has no single claimant (the target owner 2.0.0) or the gate stays closed.
    @Test
    void claimant_twoLiveVersionsThroughAnUpgradeWriteNothingOverThreeSweeps() {
        var h = settledLeader();

        publish(h, blueprintOf("attempt-1", LIST));
        publishRoutes(h, CORE_1);
        ticks(h, 1);
        assertThat(committed(h, LIST)).as("control: 1.0.0's row published")
                                      .isEqualTo(Option.some(RouteFixtures.published("attempt-1")));
        publish(h, version(V2, "attempt-b", LIST), Option.some(blueprintOf("attempt-1", LIST)));
        publishRoutes(h, CORE_1);
        ticks(h, 2);
        assertThat(h.store.get(new AppBlueprintKey(APP_ID)).isPresent()).as("control: 1.0.0 still committed").isTrue();
        var before = h.submits(SubmitKind.ROUTES).size();

        ticks(h, 3);
        assertThat(h.submits(SubmitKind.ROUTES).size() - before).as("ROUTES writes over 3 sweeps (row %s)", committed(h, LIST)).isZero();
        assertThat(ownerOf(h, LIST)).as("one claimant: the target owner 2.0.0").isEqualTo(Option.some(V2.asString()));
        assertThat(gate(h)).as("the gate opens (row %s)", committed(h, LIST)).isTrue();
    }

    /// Red when: the fallback arm of `Routes.claimant` names the least declarer instead of the greatest id text (the documented, stable choice):
    /// the target owner 2.0.0 declares GET_ONE only, so LIST, declared by 1.0.0 and 3.0.0, is claimed by 3.0.0 and never by 1.0.0.
    @Test
    void claimant_theGreatestDeclarerClaimsARowTheTargetOwnerDoesNotDeclare() {
        var h = settledLeader();

        publish(h, blueprintOf("attempt-1", LIST));
        publish(h, version(V3, "attempt-c", LIST), Option.some(blueprintOf("attempt-1", LIST)));
        publish(h, version(V2, "attempt-b", GET_ONE), Option.some(version(V3, "attempt-c", LIST)));
        ticks(h, 2);
        assertThat(ownerOf(h, GET_ONE)).as("control: the target owner 2.0.0 declares GET_ONE").isEqualTo(Option.some(V2.asString()));
        assertThat(ownerOf(h, LIST)).as("the greatest declarer 3.0.0 claims LIST, not 1.0.0").isEqualTo(Option.some(V3.asString()));
    }

    /// Red when: the fallback arm of `Routes.claimant` (the target owner does not declare the key) names another declarer than the greatest, names
    /// none, or does not move with a removal: the target owner 2.0.0 declares GET_ONE only; 1.0.0 and 3.0.0 declare LIST, so LIST's claimant is the
    /// greatest declarer 3.0.0. Removing 3.0.0 moves LIST to 1.0.0; removing 1.0.0 leaves LIST with no claimant, so it is removed. The gate counts
    /// GET_ONE only (a `Declared` row counts, `Routes.counts`).
    @Test
    void claimant_theFallbackIsTheGreatestDeclarerAndMovesWithItsRemoval() {
        var h = settledLeader();

        publish(h, blueprintOf("attempt-1", LIST));
        publish(h, version(V3, "attempt-c", LIST), Option.some(blueprintOf("attempt-1", LIST)));
        publish(h, version(V2, "attempt-b", GET_ONE), Option.some(version(V3, "attempt-c", LIST)));
        ticks(h, 2);
        assertThat(ownerOf(h, GET_ONE)).as("control: the target owner 2.0.0 declares GET_ONE").isEqualTo(Option.some(V2.asString()));
        assertThat(ownerOf(h, LIST)).as("fallback: the greatest declarer 3.0.0 claims LIST").isEqualTo(Option.some(V3.asString()));
        var before = h.submits(SubmitKind.ROUTES).size();

        ticks(h, 3);
        assertThat(h.submits(SubmitKind.ROUTES).size() - before).as("no ROUTES write over 3 ticks").isZero();
        assertThat(gate(h)).as("gate open: the target owner's GET_ONE row is committed at its attempt, LIST is not waited for (GET_ONE %s)",
                               committed(h, GET_ONE)).isTrue();
        h.seedRemove(new AppBlueprintKey(V3));
        h.drain();
        ticks(h, 1);
        assertThat(ownerOf(h, LIST)).as("3.0.0 gone: LIST moves to 1.0.0").isEqualTo(Option.some(APP));
        h.seedRemove(new AppBlueprintKey(APP_ID));
        h.drain();
        ticks(h, 1);
        assertThat(committed(h, LIST)).as("1.0.0 gone: no declarer, LIST removed").isEqualTo(Option.none());
        assertThat(ownerOf(h, GET_ONE)).as("GET_ONE untouched").isEqualTo(Option.some(V2.asString()));
    }
}
