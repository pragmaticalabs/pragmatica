// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
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
    /// publish exactly the warnings it carried (rule E over its own pairs): the first opener waits for the second write, or the second is
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

    /// Rule E, the pair alone (#3274 §0): a mutation over a key with no family notifies nothing, a family key notifies its opener. Red when
    /// a non-family row is notified, or the family row is not (positive control).
    @Test
    void nonFamilyMutation_isNotNotified() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);
        var row = Option.<AetherValue>some(otherNodesRow(Map.of(), true, "a family"));

        Warnings.accepted(m,
                          ctx,
                          TxDraft.draft(WARNINGS,
                                        KEY,
                                        List.of(new KVCommand.Mutation<>(FsmWarningKey.fsmWarningKey("some-other-code", ORDERS.asString()),
                                                                         Option.<AetherValue>none(),
                                                                         row),
                                                new KVCommand.Mutation<>(KEY, Option.<AetherValue>none(), row))));

        assertThat(ctx.effects()).containsExactly(new ClusterEffect.Warn(OPENER, ORDERS.asString(), "a family"));
    }

    /// #3274: a raise on a key absent from the mirror drafts a write of the key over "no row" and publishes nothing at once: the opener
    /// is the notification of the accepted write. Red when the raise reaches the sink in its own step, or no write is due.
    @Test
    void raiseOnAKeyAbsentFromTheMirror_isWrittenOverNoRow_andNotPublishedAtOnce() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.raise(m, OPENER, ORDERS.asString(), Warnings.SINGLE, Warnings.SINGLE, "raised");

        assertThat(ctx.effects()).as("not published at once").isEmpty();
        assertThat(Warnings.pending(m)).as("a write is due").isTrue();
        assertThat(Warnings.difference(m, ctx)).as("it writes the key over \"no row\"")
                                               .anyMatch(mutation -> mutation.key().equals(KEY) && mutation.expected().isEmpty());
    }

    /// A `Rebuild` closes the inherited warning whose subject left the model (the hold test refuses it), then applies the C17r removal as
    /// an ordinary input in the same step. The close is a row write like any other: its recovery is the notification of that write's
    /// acceptance. Red when the recovery reaches the sink in the `Rebuild` step, or never after the write is accepted.
    @Test
    void inheritClose_isNotifiedWhenItsWriteIsAccepted() {
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

        assertThat(h.warns(RECOVERY)).as("held with its write: not published in the Rebuild step").isEmpty();

        var write = warningsWrite(h);

        h.settleHeld(write, h.applyTransaction(write));

        assertThat(h.warns(RECOVERY)).as("published once its close row is accepted").hasSize(1);
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

    /// #3274: the FSM never removes a WARNINGS row: a closed key keeps a closed row (no members) under a later revision.
    private static boolean cleared(FsmHarness h, FsmWarningKey key) {
        return h.snapshot()
                .entries()
                .stream()
                .anyMatch(entry -> entry.key()
                                        .equals(key) && entry.value() instanceof FsmWarningValue row && !row.open() && row.members()
                                                                                                                          .isEmpty());
    }

    private static FsmWarningValue otherNodesRow(Map<String, String> members, boolean open, String message) {
        return Fixtures.otherNodesRow(members, open, message);
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
    /// WARNINGS write carrying it commits: `Lifecycle.rebuild` takes no step window: the opener is the notification of its write, never of the raise.
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

    /// Control: with the hold row committed, `inherit` seeds the family state and the `Rebuild` raises nothing: no notification, no write of the hold row.
    @Test
    void rebuildWithTheHoldRowCommitted_raisesNothing() {
        var h = holdCommitted();
        var holdWrites = h.warningPuts().stream().filter(HOLD_KEY::equals).count();

        h.deliver(new ClusterInput.Deactivate());
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();

        assertThat(h.warns(HOLD)).as("inherited: not raised again").hasSize(1);
        assertThat(h.warningPuts()).as("the inherited state equals the hold row: no second write of it").filteredOn(HOLD_KEY::equals).hasSize((int) holdWrites);
    }

    // -- the third outcome: committed, never published, inherited (#3160, Warnings, "Publish after commit") --------------------------------
    /// #3160, raise side: the WARNINGS write commits, but `Deactivate` is processed before its settlement. The row is committed open, so the
    /// successor inherits it (the operator's open set is the committed rows, #3274 reader invariant). Red when: the successor does not adopt
    /// the committed open row, or closes it. (The successor's replay of the unpublished opener is removed: rule E notifies only the
    /// acceptance of a write, `replay` is gone.)
    @Test
    void committedBeforeDeactivateIsProcessed_isInheritedBySuccessor() {
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
        assertThat(h.warns(RECOVERY)).as("and did not close it").isEmpty();
    }

    /// #3160, raise side through a reincarnation (`StateRestored`: `Deactivate` then `Activate` in one dispatch): the write committed, its
    /// settlement reaches the new incarnation and is dropped as stale. Red when: the new incarnation does not adopt the committed open row.
    @Test
    void reincarnatedAfterCommit_staleSettlement_rowIsInheritedByTheNewIncarnation() {
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
    /// processed before its settlement. Red when: the successor reads the committed closed row as open, or raises the opener again. (The
    /// successor's replay of the recovery is removed with `replay`: rule E notifies only an accepted write.)
    @Test
    void closeCommittedBeforeDeactivateIsProcessed_isInheritedClosedBySuccessor() {
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

        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("the successor inherited the key closed").isFalse();
        assertThat(h.warns(PLACEMENT)).as("the opener is not raised again").hasSize(1);
        assertThat(cleared(h, PLACEMENT_KEY)).as("a closed row is left: the row is kept, never removed (#3274)").isTrue();
    }

    /// Control for the close side: with the settlement processed before `Deactivate`, the recovery is published exactly once and a closed
    /// row is left (#3274: never removed).
    @Test
    void closeSettledBeforeDeactivate_isPublishedOnce() {
        var h = closeInFlight();

        h.policy = FsmHarness.SettlePolicy.APPLY;
        while (!h.held.isEmpty()) {
            var write = h.held.values().iterator().next();

            h.settleHeld(write, h.applyHeld(write.correlationId()));
        }

        assertThat(h.warns(PLACEMENT_VALID)).as("published once, after commit").hasSize(1);
        assertThat(cleared(h, PLACEMENT_KEY)).as("a closed row is left: the row is kept, never removed (#3274)").isTrue();
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
    /// #3160, late settle: the WARNINGS write commits (this node no longer leads at its settlement), this node
    /// mirrors the successor's `LeaderKey`, then its settlement arrives. Under rule E (erratum 2026-11-16, know e2db9ca83b7c) the deposed
    /// writer that handles its Accepted settlement emits the opener for its committed pair exactly once. Red when it publishes none or
    /// more than one, and when the successor then raises the opener again from the committed row.
    @Test
    void lateSettle_afterAnotherLeaderCommitted_publishesOnceAndSuccessorNone() {
        var h = exhausted();
        var write = warningsWrite(h);
        var settlement = h.applyHeld(write.correlationId());

        assertThat(settlement).as("precondition: the write committed").isInstanceOf(Settlement.Accepted.class);
        h.commitLeader(CORE_2, 2L);
        h.drain();
        h.settleHeld(write, settlement);

        assertThat(h.warns(OPENER)).as("the deposed writer publishes the opener of its committed pair once, at its late settle (rule E)").hasSize(1);

        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 3L);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.drain();
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        h.drain();

        assertThat(h.warns(OPENER)).as("the successor does not raise it again from the committed row: still the one opener").hasSize(1);
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

    private static final FsmWarningValue LATE_OPENER = Fixtures.otherNodesRow(Map.of(), true, "late opener");

    /// #3160, F1 (rule O, "none" evidence): another node's open row commits after this node's snapshot, while it is suspended. Red when the
    /// late row is only mirrored: the family state does not adopt it, so the resume's flush writes the key closed and an event-only family
    /// (retry exhaustion) is never raised again.
    @Test
    void openerRowCommittedAfterRebuild_isAdoptedAndKept() {
        var h = suspendedSuccessor(Option.none());

        h.seed(KEY, LATE_OPENER);
        h.drain();

        assertThat(committed(h)).as("precondition: the late write was accepted").isTrue();
        assertThat(h.warns(OPENER)).as("a row is not a notification: nothing is published").isEmpty();
        assertThat(h.model().openText.get(KEY)).as("the row's message is the key's open text").isEqualTo(Option.some("late opener"));

        resume(h);

        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("adopted").isTrue();
        assertThat(committed(h)).as("its row is kept open").isTrue();
    }

    /// Positive control for F1: the same row committed before the rebuild is adopted by `inherit` (rule O over the snapshot).
    @Test
    void openerRowCommittedBeforeRebuild_isAdopted() {
        var h = suspendedSuccessor(Option.some(LATE_OPENER));

        resume(h);

        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).isTrue();
        assertThat(h.model().openText.get(KEY)).isEqualTo(Option.some("late opener"));
        assertThat(committed(h)).as("its row is kept open").isTrue();
    }

    /// #3160, F1 mirror arm (rule O, "none" evidence): this node adopted the committed open row, then another node's closed row arrives
    /// for the same key. Red when the closed row is only mirrored: the state stays held and the resume rewrites the row open.
    @Test
    void closedRowCommittedAfterRebuild_isAdopted() {
        var h = suspendedSuccessor(Option.some(otherNodesRow(Map.of(), true, "open")));

        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("precondition: the open row was adopted").isTrue();
        h.seed(KEY, otherNodesRow(Map.of(), false, "late close"));
        h.drain();
        resume(h);

        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("the closed row was applied").isFalse();
        assertThat(h.warns(OPENER)).as("no opener is raised").isEmpty();
    }

    // -- the family state and the mirror ----------------------------------------------------------------------------------------------------
    private static final String UNJUDGEABLE = Warnings.UNJUDGEABLE;
    private static final FsmWarningKey UNJUDGEABLE_KEY = FsmWarningKey.fsmWarningKey(UNJUDGEABLE, "c1");

    private static ClusterModel raisedOpener(ClusterModel m, String text) {
        return Warnings.raise(m, OPENER, ORDERS.asString(), Warnings.SINGLE, Warnings.SINGLE, text);
    }

    /// Red when: a flush drafts a write for a state that equals the mirror's rows (rows compare by content, never by revision or writer).
    @Test
    void draftWithNothingToWrite_draftsNothing() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        raisedOpener(m, "held");
        Warnings.indexRow(m, KEY, Warnings.desired(m, ctx).get(KEY));
        Warnings.draft(m, ctx);

        assertThat(m.outbox).isEmpty();
    }

    /// Red when: `clean` leaves the set dirty although the state equals the mirror (a no-op flush would be queued).
    @Test
    void stateEqualToTheMirror_isCleaned() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        raisedOpener(m, "raised");
        Warnings.indexRow(m, KEY, Warnings.desired(m, ctx).get(KEY));
        assertThat(Warnings.pending(m)).as("precondition: `raise` marks dirty").isTrue();
        Warnings.clean(m, ctx);

        assertThat(Warnings.pending(m)).isFalse();
    }

    /// Red when: `inherit` leaves the set clean (the first flush must compare state and rows), replays a notification (rule E notifies only
    /// an accepted write), or does not take the row's message as the key's open text.
    @Test
    void inherit_ofAnotherNodesOpenRow_adoptsItWithoutNotifying() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.indexRow(m, UNJUDGEABLE_KEY, otherNodesRow(Map.of(), true, "o"));
        Warnings.inherit(m, ctx);

        assertThat(Warnings.pending(m)).as("dirty after inherit, before any reconcile").isTrue();
        assertThat(m.openText.get(UNJUDGEABLE_KEY)).isEqualTo(Option.some("o"));
        assertThat(Warnings.difference(m, ctx)).as("the state says what the row says: nothing to write").isEmpty();
        assertThat(ctx.effects()).as("no replay").isEmpty();
    }

    /// Red when: replay runs over a row of an unknown code (no family): the lookup fails and `inherit` throws, or the row is notified.
    @Test
    void inherit_ofAnUnknownCode_doesNothing() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.indexRow(m, FsmWarningKey.fsmWarningKey("not-a-family", "c1"), otherNodesRow(Map.of(), true, "o"));

        assertThatCode(() -> Warnings.inherit(m, ctx)).doesNotThrowAnyException();
        assertThat(ctx.effects()).isEmpty();
    }

    /// Red when: a key changed several times keeps the wrong text: a passing opener sets the open text (a recurrence on a held key does not
    /// replace it), a passing recovery sets the close text.
    @Test
    void severalChangesOfOneKey_keepTheTextOfThePassingOnes() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        raisedOpener(m, "o1");
        Warnings.raise(m, RECOVERY, ORDERS.asString(), Warnings.SINGLE, Warnings.SINGLE, "r1");
        raisedOpener(m, "o2");
        raisedOpener(m, "o3");

        assertThat(Warnings.desired(m, ctx).get(KEY).message()).as("the opener of the last transition, not the recurrence").isEqualTo("o2");

        Warnings.raise(m, RECOVERY, ORDERS.asString(), Warnings.SINGLE, Warnings.SINGLE, "r2");

        assertThat(Warnings.desired(m, ctx)).doesNotContainKey(KEY);
        assertThat(m.closeText.get(KEY)).isEqualTo(Option.some("r2"));
    }

    /// Red when: the desired row drops a member: with a members-bearing family (stray) committed exactly as the family state describes it,
    /// the difference is not empty.
    @Test
    void committedRowWithMembers_isNotRewritten() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);
        var strayKey = FsmWarningKey.fsmWarningKey(Warnings.STRAY, BILLING.asString());

        Warnings.raise(m, Warnings.STRAY, BILLING.asString(), CORE_1.id(), Warnings.SINGLE, "stray");
        assertThat(Warnings.desired(m, ctx).get(strayKey).members()).as("precondition: the row has members").containsOnlyKeys(CORE_1.id());
        Warnings.indexRow(m, strayKey, Warnings.desired(m, ctx).get(strayKey));

        assertThat(Warnings.difference(m, ctx)).isEmpty();
    }

    /// Red when: `copy()` drops a warning field, so the next step loses the mirror, the texts, the evidence or the dirty mark.
    @Test
    void copy_carriesTheWarningFields() {
        var m = new ClusterModel();

        m.warningRows = m.warningRows.put(KEY, otherNodesRow(Map.of(), true, "o"));
        m.openText = m.openText.put(KEY, "o");
        m.closeText = m.closeText.put(KEY, "c");
        m.fresh = m.fresh.put(Warnings.Member.member(KEY, Warnings.SINGLE), true);
        m.warningsDirty = true;
        var c = m.copy();

        assertThat(c.warningRows.get(KEY)).isEqualTo(m.warningRows.get(KEY));
        assertThat(c.openText.get(KEY)).isEqualTo(Option.some("o"));
        assertThat(c.closeText.get(KEY)).isEqualTo(Option.some("c"));
        assertThat(c.fresh.get(Warnings.Member.member(KEY, Warnings.SINGLE))).isEqualTo(Option.some(true));
        assertThat(c.warningsDirty).isTrue();
    }
}
