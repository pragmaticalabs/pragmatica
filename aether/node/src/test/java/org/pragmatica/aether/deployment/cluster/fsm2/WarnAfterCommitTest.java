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
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
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

    /// Control for the two above and for the late settle: with the settlement processed while this node leads, the opener is published
    /// exactly once.
    @Test
    void settledBeforeDeactivate_isPublishedOnce() {
        var h = exhausted();
        var write = warningsWrite(h);

        h.settleHeld(write, h.applyHeld(write.correlationId()));

        assertThat(committed(h)).isTrue();
        assertThat(h.warns(OPENER)).as("published once, after commit").hasSize(1);
    }

    // -- the late settle (#3160, M1) and the row committed after Rebuild (#3160, F1) ------------------------------------------------------
    /// #3160, late settle: the WARNINGS write commits, this node mirrors the successor's `LeaderKey`, then its settlement arrives. Red when
    /// the deposed leader publishes the opener at that late settle (`Warnings.publish` without its leader guard): the successor replays the
    /// committed marker, so the operator sees the opener from both, or the deposed leader's opener after the successor closed the key.
    @Test
    void lateSettle_afterAnotherLeaderCommitted_publishesNothing_andTheSuccessorPublishesOnce() {
        var h = exhausted();
        var write = warningsWrite(h);
        var settlement = h.applyHeld(write.correlationId());

        assertThat(settlement).as("precondition: the write committed").isInstanceOf(Settlement.Accepted.class);
        h.commitLeader(CORE_2, 2L);
        h.drain();
        h.settleHeld(write, settlement);

        assertThat(h.warns(OPENER)).as("the deposed leader publishes nothing at its late settle").isEmpty();

        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 3L);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.drain();
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        h.drain();

        assertThat(h.warns(OPENER)).as("the successor replays the committed marker once").hasSize(1);
    }

    /// `ORDERS` one failure short on core-1, with the committed leader naming the old leader (core-2): this node rebuilds suspended.
    /// `row`, when present, is committed before the rebuild (the snapshot holds it).
    private static FsmHarness suspendedSuccessor(Option<FsmWarningValue> row) {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, SlFixtures.G1);
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.LOADING, SlFixtures.G1, 11L);
        SlFixtures.seedJudgment(h, ORDERS, new ArtifactJudgmentValue(false, Map.of(CORE_1, 5), Map.of(), Set.of()));
        h.commitLeader(CORE_2, 2L);
        row.onPresent(value -> h.seed(KEY, value));
        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;

        return h;
    }

    private static void resume(FsmHarness h) {
        h.commitLeader(FsmHarness.SELF, 3L);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.deliver(new ClusterInput.Reconcile());
        h.drain();
    }

    private static List<String> messages(FsmHarness h, String code) {
        return h.warns(code)
                .stream()
                .map(ClusterEffect.Warn::message)
                .toList();
    }

    private static final FsmWarningValue LATE_OPENER = FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some("late opener"), Option.none());

    /// #3160, F1: the old leader's marked opener row commits after this node's snapshot (its leader fence still holds). Red when the late row
    /// is only mirrored: the resume's flush removes it and nobody publishes the opener (an event-only family never raises it again). The
    /// replayed opener is held while the old leader is still the committed one, and published at the resume.
    @Test
    void openerRowCommittedAfterRebuild_isPublishedAtTheResume() {
        var h = suspendedSuccessor(Option.none());

        h.seed(KEY, LATE_OPENER);
        h.drain();

        assertThat(committed(h)).as("precondition: the late write was accepted").isTrue();
        assertThat(h.warns(OPENER)).as("not published while another node leads").isEmpty();

        resume(h);

        assertThat(messages(h, OPENER)).as("the late-committed opener is published once, at the resume").containsExactly("late opener");
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("and adopted").isTrue();
        assertThat(committed(h)).as("its row is kept open").isTrue();
    }

    /// Positive control for F1: the same row committed before the rebuild is replayed by `inherit`.
    @Test
    void openerRowCommittedBeforeRebuild_isPublishedOnce() {
        var h = suspendedSuccessor(Option.some(LATE_OPENER));

        resume(h);

        assertThat(messages(h, OPENER)).containsExactly("late opener");
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).isTrue();
    }

    /// #3160, F1 mirror arm: this node adopted the committed open row, then the old leader's close commits as a tombstone carrying the
    /// recovery. Red when the tombstone is only mirrored: the resume rewrites the row open and the recovery is never published.
    @Test
    void tombstoneCommittedAfterRebuild_publishesItsRecovery() {
        var h = suspendedSuccessor(Option.some(FsmWarningValue.fsmWarningValue(Map.of())));

        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("precondition: the open row was adopted").isTrue();
        h.seed(KEY, FsmWarningValue.fsmWarningValue(Map.of(), false, Option.none(), Option.some("late close")));
        h.drain();
        resume(h);

        assertThat(messages(h, RECOVERY)).as("the late-committed recovery is published once").containsOnlyOnce("late close");
        assertThat(h.warns(OPENER)).as("no opener is raised").isEmpty();
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

    // -- the marker bookkeeping, one pin per mutant the first verification found surviving ---------------------------------------------
    private static final String UNJUDGEABLE = Warnings.UNJUDGEABLE;
    private static final String JUDGEABLE = Warnings.FAMILIES.get(UNJUDGEABLE);
    private static final FsmWarningKey UNJUDGEABLE_KEY = FsmWarningKey.fsmWarningKey(UNJUDGEABLE, "c1");

    private static ClusterEffect.Warn warn(String code, String subject, String message) {
        return new ClusterEffect.Warn(code, subject, message);
    }

    /// Red when: `noteRaised` cleans the set before publishing (`publish(clean(m))`): the warning published at once leaves the set dirty,
    /// and the next flush drafts an empty write.
    @Test
    void warningPublishedAtOnce_leavesNothingToFlush() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        ctx.warn(OPENER, ORDERS.asString(), "raised");
        Warnings.noteRaised(m, ctx, 0);

        assertThat(ctx.effects()).as("precondition: published at once").hasSize(1);
        assertThat(Warnings.pending(m)).as("nothing left to write").isFalse();
    }

    /// Red when: `inherit` leaves the set clean: the replayed markers are never cleared until some reconcile happens to mark it dirty.
    @Test
    void inherit_alone_queuesTheMarkerClear() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.indexRow(m, UNJUDGEABLE_KEY, FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some("o"), Option.none()));
        Warnings.inherit(m, ctx);

        assertThat(Warnings.pending(m)).as("dirty after inherit, before any reconcile").isTrue();
        assertThat(Warnings.difference(m)).as("the next write clears the marker")
                                          .singleElement()
                                          .satisfies(mutation -> assertThat(mutation.replacement())
                                              .isEqualTo(Option.some(FsmWarningValue.fsmWarningValue(Map.of()))));
    }

    /// Red when: replay emits an open row's markers opener first (it must end open: recovery, then opener), or emits a blank subject.
    @Test
    void replay_ofAnOpenRowWithBothMarkers_endsWithTheOpener() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.indexRow(m, UNJUDGEABLE_KEY, FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some("o"), Option.some("r")));
        Warnings.inherit(m, ctx);

        assertThat(ctx.effects()).containsExactly(warn(JUDGEABLE, "c1", "r"), warn(UNJUDGEABLE, "c1", "o"));
    }

    /// Red when: replay emits a tombstone's markers recovery first (it must end closed: opener, then recovery).
    @Test
    void replay_ofATombstoneWithBothMarkers_endsWithTheRecovery() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.indexRow(m, UNJUDGEABLE_KEY, FsmWarningValue.fsmWarningValue(Map.of(), false, Option.some("o"), Option.some("r")));
        Warnings.inherit(m, ctx);

        assertThat(ctx.effects()).containsExactly(warn(UNJUDGEABLE, "c1", "o"), warn(JUDGEABLE, "c1", "r"));
    }

    /// Red when: `inherit` replays rows in the mirror's iteration order instead of key order (a `Rebuild` is deterministic).
    @Test
    void inherit_replaysRowsInKeyOrder() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);
        var subjects = List.of("zeta", "alpha", "mike", "bravo", "yankee", "kilo", "delta", "quebec");

        subjects.forEach(subject -> Warnings.indexRow(m,
                                                      FsmWarningKey.fsmWarningKey(UNJUDGEABLE, subject),
                                                      FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some(subject), Option.none())));
        Warnings.inherit(m, ctx);

        assertThat(ctx.effects()).extracting(effect -> ((ClusterEffect.Warn) effect).subject())
                                 .containsExactlyElementsOf(subjects.stream().sorted().toList());
    }

    /// Red when: replay runs over a row of an unknown code (no family): the recovery lookup is null and `inherit` throws.
    @Test
    void inherit_ofAnUnknownCode_replaysNothing() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.indexRow(m,
                          FsmWarningKey.fsmWarningKey("not-a-family", "c1"),
                          FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some("o"), Option.some("r")));

        assertThatCode(() -> Warnings.inherit(m, ctx)).doesNotThrowAnyException();
        assertThat(ctx.effects()).isEmpty();
    }

    /// Red when: a key changed several times in one in-flight window marks an earlier message instead of the latest of each code.
    @Test
    void severalChangesOfOneKey_markTheLatestMessages() {
        var m = new ClusterModel();

        m.warningsHeld = List.of(warn(OPENER, ORDERS.asString(), "o1"),
                                 warn(RECOVERY, ORDERS.asString(), "r1"),
                                 warn(OPENER, ORDERS.asString(), "o2"),
                                 warn(RECOVERY, ORDERS.asString(), "r2"),
                                 warn(OPENER, ORDERS.asString(), "o3"));

        assertThat(Warnings.desired(m).get(KEY)).isEqualTo(FsmWarningValue.fsmWarningValue(Map.of(), false, Option.some("o3"), Option.some("r2")));
    }

    /// Red when: a committed tombstone counts as open in the settled check: an unrelated warning with nothing to write is held instead of
    /// published at once.
    @Test
    void committedTombstone_doesNotHoldAnUnrelatedWarning() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.indexRow(m, UNJUDGEABLE_KEY, FsmWarningValue.fsmWarningValue(Map.of(), false, Option.none(), Option.some("r")));
        ctx.warn(OPENER, ORDERS.asString(), "raised");
        Warnings.noteRaised(m, ctx, 0);

        assertThat(ctx.effects()).containsExactly(warn(OPENER, ORDERS.asString(), "raised"));
        assertThat(m.warningsHeld).isEmpty();
    }

    /// Red when: the settled check drops a committed row's members: with a members-bearing family (stray) committed exactly as the family
    /// set describes it, an unrelated warning is held instead of published at once.
    @Test
    void committedRowWithMembers_doesNotHoldAnUnrelatedWarning() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);
        var strayKey = FsmWarningKey.fsmWarningKey(Warnings.STRAY, BILLING.asString());

        m.strayWarned = m.strayWarned.put(BILLING, PSet.<NodeId>empty(m.scans).add(CORE_1));
        Warnings.indexRow(m, strayKey, Warnings.desired(m).get(strayKey));
        assertThat(Warnings.desired(m).get(strayKey).members()).as("precondition: the row has members").containsOnlyKeys(CORE_1.id());
        ctx.warn(OPENER, ORDERS.asString(), "raised");
        Warnings.noteRaised(m, ctx, 0);

        assertThat(ctx.effects()).containsExactly(warn(OPENER, ORDERS.asString(), "raised"));
        assertThat(m.warningsHeld).isEmpty();
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
