// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;

/// NT-23, NT-24, NT-28, NT-94, NT-100, NT-101, NT-102: reconcile coalescing, retry delays, the verdict tick and the C2 divergence
/// clocks, all against the real applier and the injected clocks.
class DivergenceClockTest {
    private static final long T_LOAD_MS = 120_000L;
    private static final org.pragmatica.consensus.NodeId N1 = CORE_1;

    private static long reissues(FsmHarness h) {
        return h.metricTotal("aether_deployment_fsm_divergence_total", t -> "reissue".equals(t.get("stage")));
    }

    private static FsmHarness withTarget(int instances) {
        var h = active();

        h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), instances, 1));
        h.drain();
        return h;
    }

    // NT-28
    // Red when: 5 triggers in one drain run more than 1 reconcile pass.
    @Test
    void fiveTriggersInOneDrain_runOneReconcilePass() {
        var h = active();
        var inputs = new ClusterInput[5];

        for (var i = 0; i < 5; i++) {
            inputs[i] = new ClusterInput.KvPut(new SliceTargetKey(artifact("org.example:s" + i + ":1.0.0").base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        }

        h.clearLog();
        h.deliverTogether(inputs);

        assertThat(h.metricTotal("aether_deployment_fsm_reconcile_passes_total", t -> true)).isEqualTo(1);
    }

    // NT-23
    // Red when: a lost SUCCEEDED with all instances ACTIVE is not re-submitted by the tick.
    @Test
    void lostSucceeded_isResubmittedByTheTick_andNotWhileItIsInFlight() {
        var h = deployed(1);
        var node = node(h, N1, 11);

        node.follows(ORDERS);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.LOSE;
        node.follows(ORDERS);
        h.drain();
        assertThat(h.submits(SubmitKind.SUCCEEDED)).hasSize(1);

        h.advance(5_000L);
        assertThat(h.submits(SubmitKind.SUCCEEDED)).as("still in flight: not submitted again").hasSize(1);

        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advance(60_000L);
        assertThat(h.submits(SubmitKind.SUCCEEDED)).as("lost: the tick submits it again").hasSizeGreaterThan(1);
        assertThat(h.store.get(new org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey(blueprintId("org.example:app:1.0.0"))).isPresent()).isFalse();
    }

    // NT-24
    // Red when: the retry delays fall outside the 2^(n-1) s jitter bounds.
    @Test
    void retryDelays_followTwoToTheNMinusOne_withinTheJitterBounds() {
        for (var jitter : new double[] {0.0, 0.999}) {
            var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, jitter, N1);
            var node = node(h, N1, 11);

            h.activate();
            h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
            h.drain();
            for (var n = 1; n <= 5; n++) {
                node.writes(ORDERS, SliceObservedState.LOADING).fails(ORDERS, false);
                h.drain();
                var delay = h.effects(ClusterEffect.Schedule.class).stream()
                             .filter(s -> s.timer().kind() == TimerId.Kind.RECONCILE && s.timer().subject().startsWith("retry:"))
                             .reduce((a, b) -> b).orElseThrow().delayMs();
                var base = Math.min(1_000L << (n - 1), 30_000L);
                var expected = (long) (base * (0.5 + jitter));

                assertThat(delay).as("n=" + n + " jitter=" + jitter).isEqualTo(expected);
                node.follows(ORDERS);
                h.drain();
                assertThat(h.model().effectiveGoal(pair(N1, ORDERS)).map(g -> g == SliceGoal.LOADED).or(false))
                    .as("within the delay the node is not chosen again").isFalse();
                h.advance(delay);
                assertThat(h.model().effectiveGoal(pair(N1, ORDERS))).as("after the delay it is").isEqualTo(Option.some(SliceGoal.LOADED));
            }
        }
    }

    // NT-94
    // Red when: a late LOADED for g-1 triggers goal ACTIVE at g.
    @Test
    void lateLoadedForAnOlderGeneration_triggersNothing_andTheCurrentOneActivates() {
        var h = deployed(1);
        var node = node(h, N1, 11);
        var older = new Generation(0L, 9L);

        node.writes(ORDERS, SliceObservedState.LOADING, older).writes(ORDERS, SliceObservedState.LOADED, older);
        h.drain();
        assertThat(h.model().desired(pair(N1, ORDERS)).map(SliceDesiredValue::goal)).as("no ACTIVE goal for an answer to g-1").isEqualTo(Option.some(SliceGoal.LOADED));

        node.writes(ORDERS, SliceObservedState.LOADED);
        h.drain();
        assertThat(h.model().desired(pair(N1, ORDERS)).map(SliceDesiredValue::goal)).as("control: LOADED for g activates").isEqualTo(Option.some(SliceGoal.ACTIVE));
    }

    // NT-100
    // Red when: no re-issue at T and 2T; the re-issued value differs; the re-issue repeats beyond its thresholds.
    @Test
    void divergence_reissuesTheIdenticalGoalAtT_andAt2T_andNoMore() {
        var h = withTarget(1);
        var goal = h.model().desired(pair(N1, ORDERS)).or(() -> desired(SliceGoal.LOADED, gen(0)));
        var before = h.submits().size();

        h.advance(T_LOAD_MS - 1);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(reissues(h)).as("not before T").isZero();

        h.advance(2);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(reissues(h)).as("at T + 1 ms").isEqualTo(1);
        var reissue = h.submits().getLast();

        assertThat(h.submits().size()).isEqualTo(before + 1);
        assertThat(reissue.transaction().mutations()).singleElement().satisfies(mutation -> {
            assertThat(mutation.expected()).as("identical value, expecting itself").isEqualTo(mutation.replacement());
            assertThat(mutation.replacement()).isEqualTo(Option.some(goal));
        });

        h.advance(T_LOAD_MS);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(reissues(h)).as("again at 2T").isEqualTo(2);
        h.advance(T_LOAD_MS - 2_000L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(reissues(h)).as("never beyond 2T, up to 3T").isEqualTo(2);
    }

    // NT-100 (lost write arm)
    // Red when: a lost observed write is not repaired: after the re-issue the node answers and the pair settles.
    @Test
    void reissue_repairsALostObservedWrite() {
        var h = withTarget(1);
        var node = node(h, N1, 11);

        h.advance(T_LOAD_MS + 1);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(reissues(h)).isEqualTo(1);
        node.follows(ORDERS);
        h.drain();
        node.follows(ORDERS);
        h.drain();

        assertThat(h.model().divergent.contains(pair(N1, ORDERS))).isFalse();
        assertThat(h.model().desired(pair(N1, ORDERS)).map(SliceDesiredValue::goal)).isEqualTo(Option.some(SliceGoal.ACTIVE));
    }

    // NT-101
    // Red when: at 6 min + 1 ms there is no retraction plus unresponsive in one transaction; no event; no re-placement; n chosen while excluded; a retraction at 3T - 1 ms.
    @Test
    void divergence_retractsAtThreeT_inOneTransaction_andReplacesElsewhere() {
        var h = withTarget(1);
        var generation = h.model().desired(pair(N1, ORDERS)).map(SliceDesiredValue::generation).or(gen(0));
        var before = h.submits().size();

        h.advance(3 * T_LOAD_MS - 1);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.warns("slice-unresponsive")).as("not at 3T - 1 ms").isEmpty();
        assertThat(h.submits().size() - before).as("only the two re-issues").isEqualTo(2);

        h.advance(2);
        h.deliver(new ClusterInput.Reconcile());
        var retraction = h.submits().stream().filter(s -> s.transaction().mutations().stream().anyMatch(m -> m.key() instanceof ArtifactJudgmentKey)).toList();

        assertThat(retraction).as("one transaction").hasSize(1);
        var mutations = retraction.getFirst().transaction().mutations();

        assertThat(mutations.stream().filter(m -> m.key().equals(new SliceDesiredKey(N1, ORDERS))).findFirst().orElseThrow().replacement()
                            .map(v -> ((SliceDesiredValue) v).goal())).as("goal UNLOADED in the same transaction").isEqualTo(Option.some(SliceGoal.UNLOADED));
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS)).map(v -> ((ArtifactJudgmentValue) v).unresponsive().containsKey(N1))).isEqualTo(Option.some(true));
        assertThat(h.warns("slice-unresponsive")).hasSize(1);
        assertThat(mutations.stream().filter(m -> m.key().equals(new SliceDesiredKey(N1, ORDERS))).findFirst().orElseThrow().replacement()
                            .map(v -> ((SliceDesiredValue) v).generation().compareTo(generation) > 0)).as("fresh generation").isEqualTo(Option.some(true));

        h.advance(60_000L);
        assertThat(h.model().desired(pair(N1, ORDERS))).as("the excluded node is not chosen again").isEqualTo(Option.none());
        assertThat(h.model().desired(pair(CORE_2, ORDERS)).map(SliceDesiredValue::goal)).as("re-placed elsewhere").isEqualTo(Option.some(SliceGoal.LOADED));
    }

    // NT-102
    // Red when: a late ACTIVE for g triggers a goal or counts; control: UNLOADED for g clears unresponsive.
    @Test
    void lateAnswerAfterRetraction_drivesAndCountsNothing_andUnloadedClearsTheExclusion() {
        var h = withTarget(1);
        var node = node(h, N1, 11);
        var retracted = h.model().desired(pair(N1, ORDERS)).map(SliceDesiredValue::generation).or(gen(0));

        node.writes(ORDERS, SliceObservedState.LOADING);
        h.drain();
        h.advance(3 * T_LOAD_MS + 2);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS)).isPresent()).isTrue();
        var submits = h.submits().size();

        node.writes(ORDERS, SliceObservedState.ACTIVE, retracted);
        h.drain();
        assertThat(h.submits().stream().skip(submits).filter(s -> s.transaction().mutations().stream().anyMatch(m -> m.key() instanceof SliceDesiredKey)).toList()).as("no goal").isEmpty();
        assertThat(Rows.activeCount(h.model(), ORDERS)).as("not counted").isZero();
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS)).isPresent()).as("an ACTIVE answer does not clear it").isTrue();

        node.writes(ORDERS, SliceObservedState.UNLOADED, retracted);
        h.drain();
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS)).isPresent()).as("UNLOADED for g clears unresponsive").isFalse();
    }
}
