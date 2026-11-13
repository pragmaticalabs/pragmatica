// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.Submitted;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind.WARNINGS;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// #3085: a leader-local family warning reaches the operator-warning sink only once the WARNINGS row that carries it has committed (publish
/// after commit). The window it closes: a warning raised in the drain whose leader is deposed before its WARNINGS write commits was published
/// but never persisted, so the successor did not inherit it, and an event-only family (retry exhaustion) never raised or closed it again.
class WarnAfterCommitTest {
    private static final String OPENER = "retry-exhausted-unsettled";
    private static final String RECOVERY = "retry-exhausted-settled";
    private static final FsmWarningKey KEY = FsmWarningKey.fsmWarningKey(OPENER, ORDERS.asString());

    /// `artifacts` on core-1, each one failure short of exhaustion, the leader active, every submission held from here on.
    private static FsmHarness oneFailureShort(Artifact... artifacts) {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        for (var artifact : artifacts) {
            SlFixtures.seedTarget(h, artifact, 1, 1);
            SlFixtures.seedGoal(h, CORE_1, artifact, SliceGoal.LOADED, SlFixtures.G1);
            SlFixtures.seedRow(h, CORE_1, artifact, SliceObservedState.LOADING, SlFixtures.G1, 11L);
            SlFixtures.seedJudgment(h, artifact, new ArtifactJudgmentValue(false, Map.of(CORE_1, 5), Map.of(), Set.of()));
        }

        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;

        return h;
    }

    private static FsmHarness exhausted() {
        var h = oneFailureShort(ORDERS);

        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("precondition: the leader decided the exhaustion").isTrue();
        assertThat(h.warningPuts()).as("precondition: the last WARNINGS write carries it").endsWith(KEY);

        return h;
    }

    private static boolean committed(FsmHarness h) {
        return h.snapshot()
                .entries()
                .stream()
                .anyMatch(entry -> entry.key()
                                        .equals(KEY));
    }

    private static ClusterEffect.Submit warningsWrite(FsmHarness h) {
        return h.submits(WARNINGS).getLast();
    }

    /// The deposed leader's WARNINGS write is fenced (positive control: it is refused), the successor rebuilds from the store and runs its
    /// passes. Red when: an opener published by the deposed leader is neither in the committed open set nor closed by a recovery, so it stays
    /// open in the operator's view for ever (the event-only family is never raised or closed again).
    @Test
    void warningRaisedInTheDeposedDrain_isNotLeftOpen() {
        var h = exhausted();

        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(CORE_2, 2L);
        var fenced = (KVCommand.TransactionResult) h.applyCommand(warningsWrite(h).transaction());

        assertThat(fenced.accepted()).as("precondition: the deposed leader's WARNINGS write is refused by the fence").isFalse();

        h.commitLeader(FsmHarness.SELF, 3L);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.drain();
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());

        var published = h.warns(OPENER).size();
        var accountedFor = (committed(h) ? 1 : 0) + h.warns(RECOVERY).size();

        assertThat(published).as("a published %s is neither committed nor closed: open for ever", OPENER)
                             .isLessThanOrEqualTo(accountedFor);
    }

    private static List<String> openedSubjects(FsmHarness h) {
        return h.warns(OPENER)
                .stream()
                .map(ClusterEffect.Warn::subject)
                .toList();
    }

    /// Red when: the opener reaches the sink in the step that raised it, before the WARNINGS write carrying it is accepted.
    @Test
    void opener_isPublished_onlyOnceItsWarningsWriteIsAccepted() {
        var h = exhausted();

        assertThat(h.warns(OPENER)).as("held while its write is in flight").isEmpty();

        var write = warningsWrite(h);

        h.settleHeld(write, h.applyTransaction(write));

        assertThat(openedSubjects(h)).as("published once its row committed").containsExactly(ORDERS.asString());
        assertThat(committed(h)).isTrue();
    }

    /// A second exhaustion lands while the first WARNINGS write is in flight, so it rides the next write. Red when: the accepted write does not
    /// publish exactly the warnings it carried (`covering` / `committed`): the first opener waits for the second write, or the second is
    /// published before its row commits.
    @Test
    void acceptedWrite_publishesTheWarningsItCarried_andNotTheLaterOnes() {
        var h = oneFailureShort(ORDERS, BILLING);

        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        var first = warningsWrite(h);

        LcSupport.node(h, CORE_1, 11L).fails(BILLING, false);
        h.drain();
        assertThat(h.model().retryExhaustedWarned.contains(BILLING)).as("precondition: the second exhaustion is decided").isTrue();
        assertThat(warningsWrite(h)).as("precondition: no second write beside the one in flight").isSameAs(first);

        h.settleHeld(first, h.applyTransaction(first));

        assertThat(openedSubjects(h)).as("only the warning the accepted write carried").containsExactly(ORDERS.asString());

        var second = warningsWrite(h);

        assertThat(second).as("precondition: the second write is drafted").isNotSameAs(first);
        h.settleHeld(second, h.applyTransaction(second));

        assertThat(openedSubjects(h)).containsExactly(ORDERS.asString(), BILLING.asString());
    }

    /// Red when: a family warning whose change is already what the committed mirror holds (no write will carry it) is held for ever instead of
    /// published at once.
    @Test
    void warningWithNothingToWrite_isPublishedAtOnce() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        ctx.warn(OPENER, ORDERS.asString(), "raised");
        Warnings.noteRaised(m, ctx, 0);

        assertThat(ctx.effects()).containsExactly(new ClusterEffect.Warn(OPENER, ORDERS.asString(), "raised"));
        assertThat(m.warningsHeld).isEmpty();
    }

    /// Red when: a warning raised before the step's window (`from`) is taken into the held set: an earlier input's effect would be held twice.
    @Test
    void warningBeforeTheWindow_isNotWithdrawn() {
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        ctx.warn(OPENER, ORDERS.asString(), "earlier");
        ctx.warn(OPENER, BILLING.asString(), "this step");

        assertThat(ctx.withdraw(1, _ -> true)).containsExactly(new ClusterEffect.Warn(OPENER, BILLING.asString(), "this step"));
        assertThat(ctx.effects()).containsExactly(new ClusterEffect.Warn(OPENER, ORDERS.asString(), "earlier"));
    }

    /// A `Rebuild` closes the inherited warning whose subject left the model (immediate: it closes a committed row), then applies the C17r
    /// removal as an ordinary input in the same step. Red when: the FSM's window starts before that input (`ctx.effectCount()` not read at
    /// the start of `apply`), so the inherit close is taken into the held set and waits for a WARNINGS write that is held here.
    @Test
    void inheritClose_beforeTheFirstInputOfTheStep_isNotHeld() {
        var h = oneFailureShort(ORDERS);

        h.policy = FsmHarness.SettlePolicy.APPLY;
        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(committed(h)).as("precondition: the leader committed the opener").isTrue();

        h.deliver(new ClusterInput.Deactivate());
        h.seedRemove(new SliceTargetKey(ORDERS.base()));
        h.dead = ClusterInput.DeadSet.deadSet(Set.of(CORE_1), Set.of());
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();

        assertThat(h.warns(RECOVERY)).as("the inherit close is published in the Rebuild step, not after a later write").hasSize(1);
    }

    /// Red when: a non-family effect (an uncatalogued or node-local code, a metric) is held: only the leader-local families have a WARNINGS row.
    @Test
    void nonFamilyEffect_isNotHeld() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        m.submitted = m.submitted.put("c1",
                                      new Submitted("c1", WARNINGS, List.of(), 1L, new LeaderValue(FsmHarness.SELF, 1L), 0L));
        ctx.warn("some-other-code", ORDERS.asString(), "not a family");
        ctx.warn(OPENER, ORDERS.asString(), "a family");
        Warnings.noteRaised(m, ctx, 0);

        assertThat(ctx.effects()).as("the family warning is held while a write is in flight, the other is not")
                                 .containsExactly(new ClusterEffect.Warn("some-other-code", ORDERS.asString(), "not a family"));
        assertThat(m.warningsHeld).containsExactly(new ClusterEffect.Warn(OPENER, ORDERS.asString(), "a family"));
    }

    // -- a schema hold re-raised at Rebuild (Lifecycle.rebuild, outside any input's step) -----------------------------------------------
    private static final String HOLD = "schema-hold";
    private static final FsmWarningKey HOLD_KEY = FsmWarningKey.fsmWarningKey(HOLD, ORDERS.asString());
    private static final String APP = "org.example:app:1.0.0";

    private static boolean present(FsmHarness h, FsmWarningKey key) {
        return h.snapshot()
                .entries()
                .stream()
                .anyMatch(entry -> entry.key()
                                        .equals(key));
    }

    private static SchemaVersionValue pendingSchema() {
        return new SchemaVersionValue("orders-db", 1, "V1", SchemaStatus.PENDING, "org.example:migrations:1.0.0", blueprintId(APP), 0,
                                      FsmHarness.START_WALL);
    }

    /// ORDERS placed and held by its pending schema; the leader's hold opener committed (every write applied so far).
    private static FsmHarness holdCommitted() {
        var h = held();

        node(h, hosts(h, ORDERS).getFirst(), 1L).follows(ORDERS);
        h.drain();
        assertThat(h.warns(HOLD)).as("precondition: the first leader raised the hold").hasSize(1);
        assertThat(present(h, HOLD_KEY)).as("precondition: and committed it").isTrue();

        return h;
    }

    private static FsmHarness held() {
        var h = threeCores();

        h.activate();
        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())), Option.none());
        h.seed(new SchemaVersionKey("orders-db"), pendingSchema());
        h.drain();

        return h;
    }

    /// The hold row did not commit (its leader was deposed before its WARNINGS write landed), so the successor's `Rebuild` raises the hold
    /// again, with every submission held from here on.
    private static FsmHarness rebuiltWithoutTheHoldRow() {
        var h = holdCommitted();

        h.deliver(new ClusterInput.Deactivate());
        h.seedRemove(HOLD_KEY);
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();

        return h;
    }

    /// Red when: the hold opener `Rebuild` raises (`Slices.activateLoadedAtRebuild`) reaches the sink in the `Rebuild` step, before the
    /// WARNINGS write carrying it commits: `Lifecycle.rebuild` takes no step window, so `Warnings.noteRaised` never holds it.
    @Test
    void rebuildTimeHoldOpener_isHeldUntilItsRowCommits() {
        var h = rebuiltWithoutTheHoldRow();
        var write = warningsWrite(h);

        assertThat(present(h, HOLD_KEY)).as("precondition: the hold row is not committed").isFalse();
        assertThat(h.warns(HOLD)).as("held while its write is in flight").hasSize(1);

        h.settleHeld(write, h.applyHeld(write.correlationId()));

        assertThat(present(h, HOLD_KEY)).as("precondition: the hold row committed").isTrue();
        assertThat(h.warns(HOLD)).as("published once its row committed").hasSize(2);
    }

    /// The #3085 end state through `Rebuild`: the successor re-raises the hold, is deposed, and the fence refuses its WARNINGS write. Red
    /// when: the opener reached the sink although no row exists, an open warning no later leader can close.
    @Test
    void rebuildTimeHoldOpener_deposedBeforeItsRowCommits_isNeverPublished() {
        var h = rebuiltWithoutTheHoldRow();
        var write = warningsWrite(h);

        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(CORE_2, 100L);

        assertThat(h.applyHeld(write.correlationId())).as("precondition: the fence refuses the deposed leader's write")
                                                      .isInstanceOf(Settlement.Refused.class);
        assertThat(present(h, HOLD_KEY)).as("no row").isFalse();
        assertThat(h.warns(HOLD)).as("only the first leader's committed opener reached the sink").hasSize(1);
    }

    /// Control for the two above: the same opener raised by an ordinary input is held while its write is in flight.
    @Test
    void holdOpenerFromAnInput_isHeld() {
        var h = held();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        node(h, hosts(h, ORDERS).getFirst(), 1L).follows(ORDERS);
        h.drain();

        assertThat(h.warns(HOLD)).as("held: its write is not committed").isEmpty();
    }

    /// Control: with the hold row committed, `inherit` seeds the family set and the `Rebuild` raises nothing, held or published.
    @Test
    void rebuildWithTheHoldRowCommitted_raisesNothing() {
        var h = holdCommitted();

        h.deliver(new ClusterInput.Deactivate());
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();

        assertThat(h.warns(HOLD)).as("inherited: not raised again").hasSize(1);
        assertThat(h.model().warningsHeld).as("nor held").noneMatch(warn -> warn.code().equals(HOLD));
    }

    // -- the third outcome: committed, never published, inherited (#3160, Warnings, "Publish after commit") --------------------------------
    /// #3160, raise side: the WARNINGS write commits, but `Deactivate` is processed before its settlement. The row is committed open and
    /// carries the unpublished opener, so the successor inherits it and publishes the opener once. Red when: the successor inherits the row
    /// without publishing it (the operator sees no opener, and may later see its recovery alone).
    @Test
    void committedBeforeDeactivateIsProcessed_isPublishedByTheSuccessor() {
        var h = exhausted();
        var write = warningsWrite(h);

        h.deliver(new ClusterInput.Deactivate());
        assertThat(((KVCommand.TransactionResult) h.applyCommand(write.transaction())).accepted()).as("precondition: the write committed")
                                                                                                 .isTrue();
        h.commitLeader(CORE_2, 2L);
        h.commitLeader(FsmHarness.SELF, 3L);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.drain();
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());

        assertThat(committed(h)).as("row committed open").isTrue();
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("the successor inherited it").isTrue();
        assertThat(h.warns(OPENER)).as("the successor published the committed opener once").hasSize(1);
        assertThat(h.warns(RECOVERY)).as("and did not close it").isEmpty();
    }

    /// #3160, raise side through a reincarnation (`StateRestored`: `Deactivate` then `Activate` in one dispatch): the write committed, its
    /// settlement reaches the new incarnation and is dropped as stale. Red when: the new incarnation inherits the row without publishing it.
    @Test
    void reincarnatedAfterCommit_staleSettlement_isPublishedByTheNewIncarnation() {
        var h = exhausted();
        var write = warningsWrite(h);
        var settlement = h.applyHeld(write.correlationId());

        assertThat(settlement).as("precondition: the write committed").isInstanceOf(Settlement.Accepted.class);
        h.deliver(new ClusterInput.Deactivate());
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.activate();
        h.settleHeld(write, settlement);
        h.deliver(new ClusterInput.Reconcile());
        h.drain();

        assertThat(committed(h)).as("row committed open").isTrue();
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("the new incarnation inherited it").isTrue();
        assertThat(h.warns(OPENER)).as("the new incarnation published the committed opener once").hasSize(1);
    }

    private static final String PLACEMENT = "invalid-placement";
    private static final String PLACEMENT_VALID = "placement-valid";
    private static final FsmWarningKey PLACEMENT_KEY = FsmWarningKey.fsmWarningKey(PLACEMENT, ORDERS.asString());

    /// ORDERS' placement does not parse: the leader opened `invalid-placement`, committed and published it; every submission held from here
    /// on, the placement is fixed and the leader closes the warning, its WARNINGS write in flight.
    private static FsmHarness closeInFlight() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.activate();
        assertThat(h.warns(PLACEMENT)).as("precondition: the opener was published").hasSize(1);
        assertThat(present(h, PLACEMENT_KEY)).as("precondition: and committed").isTrue();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "CORE_ONLY"));
        h.drain();
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the leader closed it").isFalse();
        assertThat(h.warns(PLACEMENT_VALID)).as("precondition: the recovery is held while its write is in flight").isEmpty();

        return h;
    }

    /// #3160, close side (the #3085 symptom through another window): the WARNINGS write closing the warning commits, but `Deactivate` is
    /// processed before its settlement. Red when: the successor inherits nothing that names the close, so the opener stays open for ever in
    /// the operator's view.
    @Test
    void closeCommittedBeforeDeactivateIsProcessed_isPublishedByTheSuccessor() {
        var h = closeInFlight();
        var write = warningsWrite(h);

        h.deliver(new ClusterInput.Deactivate());
        assertThat(((KVCommand.TransactionResult) h.applyCommand(write.transaction())).accepted()).as("precondition: the close committed")
                                                                                                 .isTrue();
        h.commitLeader(CORE_2, 2L);
        h.commitLeader(FsmHarness.SELF, 3L);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.drain();
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());

        assertThat(h.warns(PLACEMENT_VALID)).as("the successor published the committed recovery once").hasSize(1);
        assertThat(h.warns(PLACEMENT)).as("the opener is not raised again").hasSize(1);
        assertThat(present(h, PLACEMENT_KEY)).as("nothing left committed").isFalse();
    }

    /// Control for the close side: with the settlement processed before `Deactivate`, the recovery is published exactly once and no row is
    /// left.
    @Test
    void closeSettledBeforeDeactivate_isPublishedOnce() {
        var h = closeInFlight();

        h.policy = FsmHarness.SettlePolicy.APPLY;
        while (!h.held.isEmpty()) {
            var write = h.held.values().iterator().next();

            h.settleHeld(write, h.applyHeld(write.correlationId()));
        }

        assertThat(h.warns(PLACEMENT_VALID)).as("published once, after commit").hasSize(1);
        assertThat(present(h, PLACEMENT_KEY)).as("nothing left committed").isFalse();
    }

    /// Control for the two above: with the settlement processed before `Deactivate`, the opener is published exactly once.
    @Test
    void settledBeforeDeactivate_isPublishedOnce() {
        var h = exhausted();
        var write = warningsWrite(h);

        h.settleHeld(write, h.applyHeld(write.correlationId()));

        assertThat(committed(h)).isTrue();
        assertThat(h.warns(OPENER)).as("published once, after commit").hasSize(1);
    }

    // -- the held-set bookkeeping ----------------------------------------------------------------------------------------------------------
    private static ClusterModel withAWarningsWriteInFlight() {
        var m = new ClusterModel();

        m.submitted = m.submitted.put("c1",
                                      new Submitted("c1", WARNINGS, List.of(), 1L, new LeaderValue(FsmHarness.SELF, 1L), 0L));

        return m;
    }

    /// Red when: a step that raised no family warning marks the open set dirty anyway (`noteRaised` without its `raised` guard).
    @Test
    void stepRaisingNothing_whileAWriteIsInFlight_doesNotMarkTheSetDirty() {
        var m = withAWarningsWriteInFlight();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        ctx.warn("some-other-code", ORDERS.asString(), "not a family");
        Warnings.noteRaised(m, ctx, 0);

        assertThat(m.warningsDirty).isFalse();
    }

    /// Red when: `publish` reads `warningsLanded` with nothing held (the empty-held guard is gone): the sublist is out of range.
    @Test
    void nothingHeld_isANoOp_whateverLandedSays() {
        var m = withAWarningsWriteInFlight();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        m.warningsLanded = 1;

        assertThatCode(() -> Warnings.noteRaised(m, ctx, 0)).doesNotThrowAnyException();
        assertThat(ctx.effects()).isEmpty();
    }

    /// Red when: an accepted WARNINGS transaction lands its coverage but leaves `warningsCovered` set for the next one.
    @Test
    void acceptedWarningsWrite_consumesTheCoverage() {
        var m = new ClusterModel();

        m.warningsCovered = 2;
        Warnings.accepted(m, TxDraft.draft(WARNINGS, KEY, List.of()));

        assertThat(m.warningsLanded).isEqualTo(2);
        assertThat(m.warningsCovered).isZero();
    }

    /// Red when: a flush that drafts nothing records coverage (`covering` applied to an empty draft). The held warning is already
    /// committed in its marker row (the mirror equals the desired set), so the flush has nothing to write (#3160 markers).
    @Test
    void draftWithNothingToWrite_coversNothing() {
        var m = new ClusterModel();

        m.warningsDirty = true;
        m.warningsHeld = List.of(new ClusterEffect.Warn(OPENER, ORDERS.asString(), "held"));
        Warnings.indexRow(m, KEY, Warnings.desired(m).get(KEY));
        Warnings.draft(m);

        assertThat(m.warningsCovered).isZero();
    }

    /// Red when: the window start is not the number of effects collected so far.
    @Test
    void effectCount_isTheNumberOfEffectsCollected() {
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        assertThat(ctx.effectCount()).isZero();
        ctx.warn(OPENER, ORDERS.asString(), "one");
        assertThat(ctx.effectCount()).isEqualTo(1);
        ctx.warn(OPENER, BILLING.asString(), "two");
        assertThat(ctx.effectCount()).isEqualTo(2);
    }

    /// Red when: `copy()` drops a held-warning field, so the next step loses what is held or what landed.
    @Test
    void copy_carriesTheHeldWarningFields() {
        var m = new ClusterModel();

        m.warningsHeld = List.of(new ClusterEffect.Warn(OPENER, ORDERS.asString(), "held"));
        m.warningsCovered = 1;
        m.warningsLanded = 1;
        var c = m.copy();

        assertThat(c.warningsHeld).isEqualTo(m.warningsHeld);
        assertThat(c.warningsCovered).isEqualTo(1);
        assertThat(c.warningsLanded).isEqualTo(1);
    }
}
