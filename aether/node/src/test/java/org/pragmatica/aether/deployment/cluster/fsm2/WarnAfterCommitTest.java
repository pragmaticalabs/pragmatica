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
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.Submitted;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind.WARNINGS;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

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
}
