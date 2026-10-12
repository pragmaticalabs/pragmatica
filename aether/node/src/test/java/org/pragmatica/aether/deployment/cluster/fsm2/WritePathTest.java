// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// NT-17, NT-85, NT-90, NT-115, NT-122: the write path against the REAL applier (R-5.7).
class WritePathTest {
    private static SlicePair pair(org.pragmatica.consensus.NodeId node,
                                  org.pragmatica.aether.artifact.Artifact artifact) {
        return SlicePair.slicePair(node, artifact);
    }

    private static long started(FsmHarness h) {
        return h.effects(ClusterEffect.Route.class)
                .stream()
                .filter(r -> r.event() instanceof RoutedEvent.DeploymentStarted)
                .count();
    }

    /// Hold the goal transactions of a two-instance placement: target committed, nothing applied yet.
    private static FsmHarness heldPlacement(int instances) {
        var h = active();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), instances, 1));
        h.drain();

        return h;
    }

    // NT-17
    // Red when: a refused goal transaction leaves placed counting the pair, or is not re-derived; success does not route one DeploymentStarted.
    @Test
    void refusedGoalTransaction_releasesItsPair_isReDerived_andOnlyTheAcceptedOneRoutesDeploymentStarted() {
        var h = heldPlacement(2);
        var goals = h.submits(SubmitKind.GOALS);

        assertThat(goals).hasSize(2);
        new NodeSim(h, CORE_1, process(11)).writes(ORDERS, SliceObservedState.LOADING, new Generation(0L, 0L));
        h.drain();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        goals.forEach(submit -> h.settleHeld(submit, h.applyTransaction(submit)));
        assertThat(started(h)).as("exactly the accepted LOAD routes DeploymentStarted").isEqualTo(1);
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total",
                                 t -> String.valueOf(t.get("outcome")).startsWith("refused"))).isPositive();
        assertThat(Rows.placed(h.model(),
                               ORDERS)).as("re-derived: the pair is placed again, and not counted twice")
                  .isEqualTo(2);
        assertThat(h.model().pendingGoals.isEmpty()).isTrue();
    }

    // NT-17 control
    // Red when: the accepted LOADs do not each route one DeploymentStarted.
    @Test
    void acceptedGoalTransactions_routeOneDeploymentStartedEach() {
        var h = heldPlacement(2);

        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.submits(SubmitKind.GOALS).forEach(submit -> h.settleHeld(submit, h.applyTransaction(submit)));
        assertThat(started(h)).isEqualTo(2);
    }

    // NT-85
    // Red when: a goal transaction with the old LeaderValue is applied.
    @Test
    void goalTransactionWithTheOldLeaderValue_isRefusedByTheApplier_andTheCurrentOneIsAccepted() {
        var h = heldPlacement(1);
        var submit = h.submits(SubmitKind.GOALS).getFirst();

        h.commitLeader(FsmHarness.SELF, 2L);
        var verdict = h.applyTransaction(submit);

        assertThat(verdict).isInstanceOf(Settlement.Refused.class);
        assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS)).isPresent()).isFalse();
        var control = heldPlacement(1);

        assertThat(control.applyTransaction(control.submits(SubmitKind.GOALS).getFirst())).isInstanceOf(Settlement.Accepted.class);
    }

    // NT-85
    // Red when: a late duplicate goal whose expected value moved is applied.
    @Test
    void lateGoalWhoseExpectedValueMoved_isRefused() {
        var h = heldPlacement(1);
        var submit = h.submits(SubmitKind.GOALS).getFirst();
        var other = desired(SliceGoal.UNLOADED, gen(77));

        h.seed(new SliceDesiredKey(CORE_1, ORDERS), other);
        var verdict = h.applyTransaction(submit);

        assertThat(verdict).isInstanceOf(Settlement.Refused.class);
        assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS))).isEqualTo(org.pragmatica.lang.Option.some(other));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settleHeld(submit, verdict);
        assertThat(h.submits(SubmitKind.GOALS)
                    .stream()
                    .skip(1)
                    .flatMap(g -> g.transaction()
                                   .mutations()
                                   .stream())
                    .map(m -> m.expected())).as("the moved pair is split out: whatever follows is re-derived from the mirror, never the stale expected value")
                  .doesNotContain(org.pragmatica.lang.Option.<org.pragmatica.aether.slice.kvstore.AetherValue> none());
    }

    // NT-90 / NT-122
    // Red when: with a mirror one apply behind there is no retry, or more than 5 retries, or no refused_mirror_lag; a moved pair is resubmitted with its partners; the partners are not resubmitted in the same round.
    @Test
    void mirrorOneApplyBehind_splitsTheMovedPairOut_resubmitsThePartnerOnce_andCountsMirrorLag() {
        var h = leader();

        h.seams = Seams.UNWIRED.withCountedCores(Set.of(CORE_1)).withReady(Set.of(CORE_1));
        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.seedQuietly(new SliceDesiredKey(CORE_1, BILLING), desired(SliceGoal.ACTIVE, gen(5)));
        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.seed(new SliceTargetKey(BILLING.base()),
               SliceTargetValue.sliceTargetValue(BILLING.version(), 1, 1));
        h.drain();
        var first = h.submits(SubmitKind.GOALS).getFirst();

        assertThat(first.transaction().mutations()).as("the node transaction carries both pairs").hasSize(2);
        h.clearLog();
        h.settleHeld(first, h.applyTransaction(first));
        var again = h.submits(SubmitKind.GOALS);

        assertThat(again).as("one resubmission").hasSize(1);
        assertThat(again.getFirst().transaction().mutations()).as("the moved pair is split out: only the partner remains")
                  .hasSize(1);
        assertThat(again.getFirst().transaction().mutations().getFirst().key()).isEqualTo(new SliceDesiredKey(CORE_1,
                                                                                                              ORDERS));
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total",
                                 t -> "refused_mirror_lag".equals(t.get("outcome")))).isPositive();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settleHeld(again.getFirst(),
                     h.applyTransaction(again.getFirst()));
        assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS)).isPresent()).isTrue();
    }

    // NT-90 (bound)
    // Red when: a goal transaction that keeps being refused is resubmitted more than 5 times, or is re-derived before the tick.
    @Test
    void persistentRefusal_isResubmittedAtMostFiveTimes_thenWaitsForTheTick() {
        var h = heldPlacement(1);
        var resubmissions = 0;
        var current = h.submits(SubmitKind.GOALS).getFirst();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        for (var i = 0; i < 10; i++) {
            var before = h.submits(SubmitKind.GOALS).size();

            h.settleHeld(current,
                         new Settlement.Refused(h.committedLeader(), Map.of()));
            if (h.submits(SubmitKind.GOALS).size() == before) {
                break;
            }

            resubmissions++;
            current = h.submits(SubmitKind.GOALS).getLast();
        }

        assertThat(resubmissions).isEqualTo(5);
        var total = h.submits(SubmitKind.GOALS).size();

        h.advance(31_000L);
        assertThat(h.submits(SubmitKind.GOALS).size()).as("re-derived by the tick").isGreaterThan(total);
    }

    // NT-115 (C10a)
    // Red when: C10a built on v is applied after the node committed v+1.
    @Test
    void activateBuiltOnV_isRefusedOnceTheNodeCommittedVPlusOne() {
        var h = deployed(1);
        var node = new NodeSim(h, CORE_1, process(11));

        h.policy = FsmHarness.SettlePolicy.HOLD;
        node.writes(ORDERS, SliceObservedState.LOADING).writes(ORDERS, SliceObservedState.LOADED);
        h.drain();
        var activate = h.submits(SubmitKind.GOALS).getLast();

        assertThat(activate.transaction().mutations().getFirst().replacement().map(v -> ((SliceDesiredValue) v).goal())).isEqualTo(org.pragmatica.lang.Option.some(SliceGoal.ACTIVE));
        node.writes(ORDERS, SliceObservedState.ACTIVATING);
        assertThat(h.applyTransaction(activate)).isInstanceOf(Settlement.Refused.class);
    }

    // NT-115 (control)
    // Red when: the guard refuses a transaction whose observed value did not move.
    @Test
    void activateBuiltOnV_isAcceptedWhenTheRowDidNotMove() {
        var h = deployed(1);
        var node = new NodeSim(h, CORE_1, process(11));

        h.policy = FsmHarness.SettlePolicy.HOLD;
        node.writes(ORDERS, SliceObservedState.LOADING).writes(ORDERS, SliceObservedState.LOADED);
        h.drain();
        assertThat(h.applyTransaction(h.submits(SubmitKind.GOALS).getLast())).isInstanceOf(Settlement.Accepted.class);
    }

    // NT-115 (adopt)
    // Red when: an adoption built on v is applied after the node committed v+1.
    @Test
    void adoptBuiltOnV_isRefusedOnceTheNodeCommittedVPlusOne() {
        var h = leader();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()),
                      SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.seedQuietly(new org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey(CORE_3, ORDERS),
                      row(SliceObservedState.LOADED, 1L, gen(9), process(13)));
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        var adopt = h.submits(SubmitKind.GOALS).getLast();

        assertThat(adopt.transaction()
                        .mutations()
                        .getFirst()
                        .replacement()
                        .map(v -> ((SliceDesiredValue) v).generation())).as("adopted at the row's own generation")
                  .isEqualTo(org.pragmatica.lang.Option.some(gen(9)));
        new NodeSim(h, CORE_3, process(13)).writes(ORDERS, SliceObservedState.ACTIVATING, gen(9));
        assertThat(h.applyTransaction(adopt)).isInstanceOf(Settlement.Refused.class);
    }

    // NT-115 (retraction)
    // Red when: a retraction built on v is applied after the node committed v+1.
    @Test
    void retractionBuiltOnV_isRefusedOnceTheNodeCommittedVPlusOne() {
        var h = deployed(1);

        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.advance(360_001L);
        h.deliver(new ClusterInput.Reconcile());
        var retraction = h.submits(SubmitKind.FAILURE).getLast();

        assertThat(retraction.transaction()
                             .mutations()
                             .stream()
                             .anyMatch(m -> m.key() instanceof org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey)).isTrue();
        new NodeSim(h, CORE_1, process(11)).writes(ORDERS, SliceObservedState.LOADING);
        assertThat(h.applyTransaction(retraction)).isInstanceOf(Settlement.Refused.class);
    }

    // NT-115 (retraction control)
    // Red when: a retraction whose observed value did not move is refused.
    @Test
    void retractionBuiltOnV_isAcceptedWhenTheRowDidNotMove() {
        var h = deployed(1);

        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.advance(360_001L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.applyTransaction(h.submits(SubmitKind.FAILURE).getLast())).isInstanceOf(Settlement.Accepted.class);
    }
}
