// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// NT-27, NT-80, NT-81, NT-82, NT-83, NT-84, NT-108, NT-113: the durable judgments (spec section 4), produced by the FSM itself
/// through the real applier and read back by a new incarnation.
class DurableJudgmentTest {
    private static final String APP = "org.example:app:1.0.0";

    private static Option<ArtifactJudgmentValue> judgment(FsmHarness h) {
        return h.store.get(new ArtifactJudgmentKey(ORDERS))
                      .map(v -> (ArtifactJudgmentValue) v);
    }

    /// One transient failure cycle on CORE_1 and the unload that follows it.
    private static void transientCycle(FsmHarness h, NodeSim node) {
        h.advance(30_000L);
        node.writes(ORDERS, SliceObservedState.LOADING).fails(ORDERS, false);
        h.drain();
        node.follows(ORDERS);
        h.drain();
    }

    /// A second incarnation: the same node leads again under a higher viewSequence and rebuilds from the store alone.
    private static void leaderChange(FsmHarness h, long viewSequence) {
        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, viewSequence);
        h.drain();
        h.activate();
    }

    // NT-81
    // Red when: 3 + 3 failures across a leader change do not exhaust at report 6.
    @Test
    void threePlusThreeFailuresAcrossALeaderChange_exhaustAtReportSix() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);
        var node = node(h, CORE_1, 11);

        h.activate();
        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
        for (var i = 0; i < 3; i++) {
            transientCycle(h, node);
        }

        assertThat(judgment(h).map(j -> j.retries()
                                         .get(CORE_1))).as("durable: three reports").isEqualTo(Option.some(3));
        leaderChange(h, 2L);
        for (var i = 0; i < 2; i++) {
            transientCycle(h, node);
        }

        assertThat(judgment(h).map(j -> j.retries()
                                         .get(CORE_1))).as("five reports in total").isEqualTo(Option.some(5));
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).isEqualTo(Option.some(false));
        transientCycle(h, node);
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("report 6 exhausts, the apply being outstanding")
                  .isEqualTo(Option.some(true));
        assertThat(judgment(h).map(j -> j.retries()
                                         .containsKey(CORE_1))).as("the spent counter is dropped")
                  .isEqualTo(Option.some(false));
        h.advance(300_000L);
        assertThat(h.model().desired(pair(CORE_1, ORDERS)).map(SliceDesiredValue::goal)).as("and it is never placed again")
                  .isNotEqualTo(Option.some(SliceGoal.LOADED));
    }

    // NT-82
    // Red when: a new leader allocates a permanently failed artifact (or its intake re-drive clears the permanent mark).
    @Test
    void newLeader_neverAllocatesAPermanentlyFailedArtifact_evenWhileItsApplyIsReDriven() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1, CORE_2);
        var value = blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of()));

        h.seedQuietly(new DeploymentOutcomeKey(value.blueprint().id()),
                      DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.seedQuietly(new DeploymentApplyKey(value.blueprint().id()),
                      new org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue(ATTEMPT,
                                                                                               Option.none(),
                                                                                               h.wallMs));
        h.seedQuietly(new AppBlueprintKey(value.blueprint().id()),
                      value);
        h.seedQuietly(new ArtifactJudgmentKey(ORDERS),
                      new ArtifactJudgmentValue(true, Map.of(), Map.of()));
        h.activate();
        h.advance(300_000L);
        assertThat(h.submits(SubmitKind.TARGETS)).as("the missing target IS re-driven").isNotEmpty();
        assertThat(h.store.get(new SliceTargetKey(ORDERS.base())).isPresent()).isTrue();
        assertThat(h.mutations(org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey.class)).as("but nothing is allocated")
                  .isEmpty();
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).as("and the re-drive keeps the mark")
                  .isEqualTo(Option.some(true));
    }

    // NT-83
    // Red when: a new leader does not submit the missing target batch for an open apply.
    @Test
    void newLeader_submitsTheMissingTargetBatch_forAnOpenApply() {
        var h = leader();
        var value = blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of()), slice(BILLING, 1, 1, Set.of()));
        var id = value.blueprint().id();

        h.seedQuietly(new DeploymentOutcomeKey(id), DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.seedQuietly(new DeploymentApplyKey(id),
                      new org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue(ATTEMPT,
                                                                                               Option.none(),
                                                                                               h.wallMs));
        h.seedQuietly(new AppBlueprintKey(id), value);
        h.activate();
        var batch = h.submits(SubmitKind.TARGETS);

        assertThat(batch).isNotEmpty();
        assertThat(batch.getFirst().transaction().mutations().stream().map(m -> m.key()).toList()).as("one transaction with every target")
                  .containsExactlyInAnyOrder(new SliceTargetKey(ORDERS.base()),
                                             new SliceTargetKey(BILLING.base()));
    }

    // NT-84
    // Red when: a restore Put is tracked as an apply, or the FSM Puts an apply record.
    @Test
    void rollback_restoresThePreviousBlueprint_andNeverPutsAnApplyRecord() {
        var h = active();
        var first = blueprint(APP, false, "attempt-1", slice(ORDERS, 1, 1, Set.of()));
        var second = blueprint(APP, false, "attempt-2", slice(ORDERS_V2, 1, 1, Set.of()));
        var node = node(h, CORE_1, 11);

        publish(h, first);
        node.follows(ORDERS);
        h.drain();
        node.follows(ORDERS);
        h.drain();
        assertThat(h.store.get(new DeploymentApplyKey(first.blueprint().id())).isPresent()).as("control: deployed and settled")
                  .isFalse();
        publish(h, second, Option.some(first));
        node.writes(ORDERS_V2, SliceObservedState.LOADING).fails(ORDERS_V2, true);
        h.drain();
        var rollback = h.submits(SubmitKind.ROLLBACK);

        assertThat(rollback).as("a fatal failure rolls back").hasSize(1);
        assertThat(h.store.get(new AppBlueprintKey(first.blueprint().id()))).as("the previous value is restored")
                  .isNotEqualTo(Option.some(second));
        h.advance(60_000L);
        assertThat(h.submits()
                    .stream()
                    .flatMap(s -> s.transaction()
                                   .mutations()
                                   .stream())
                    .filter(m -> m.key() instanceof DeploymentApplyKey)
                    .filter(m -> m.replacement()
                                  .isPresent())).as("the FSM never Puts an apply record")
                  .isEmpty();
        assertThat(h.model().applies.isEmpty()).as("the restore is not tracked as an apply").isTrue();
        assertThat(h.store.get(new DeploymentOutcomeKey(first.blueprint().id()))
                          .map(v -> ((DeploymentOutcomeValue) v).status())).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
    }

    // NT-27
    // Red when: a republish lacks the judgment remove, or does not re-allocate.
    @Test
    void republish_removesTheJudgment_andReAllocates() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT, 0.5, CORE_1);
        var node = node(h, CORE_1, 11);

        h.activate();
        publish(h, blueprint(APP, false, "attempt-1", slice(ORDERS, 1, 1, Set.of())));
        node.writes(ORDERS, SliceObservedState.LOADING).fails(ORDERS, true);
        h.drain();
        node.follows(ORDERS);
        h.drain();
        assertThat(judgment(h).map(ArtifactJudgmentValue::permanentFailure)).isEqualTo(Option.some(true));
        var generation = h.model().desired(pair(CORE_1, ORDERS)).map(SliceDesiredValue::generation).or(gen(0));
        var before = h.submits().size();

        publish(h, blueprint(APP, false, "attempt-2", slice(ORDERS, 1, 1, Set.of())));
        h.advance(5_000L);
        assertThat(h.submits()
                    .stream()
                    .skip(before)
                    .flatMap(s -> s.transaction()
                                   .mutations()
                                   .stream())
                    .anyMatch(m -> m.key() instanceof ArtifactJudgmentKey && m.replacement()
                                                                              .isEmpty())).as("the judgment remove")
                  .isTrue();
        assertThat(judgment(h)).isEqualTo(Option.none());
        assertThat(h.model()
                    .desired(pair(CORE_1, ORDERS))
                    .map(d -> d.goal() == SliceGoal.LOADED && d.generation()
                                                               .compareTo(generation) > 0)).as("re-allocated at a fresh generation")
                  .isEqualTo(Option.some(true));
    }

    // NT-80
    // Red when: SUCCEEDED lacks apply GC.
    @Test
    void succeeded_carriesTheApplyGc_inOneTransaction() {
        var h = deployed(1);
        var node = node(h, CORE_1, 11);

        node.follows(ORDERS);
        h.drain();
        node.follows(ORDERS);
        h.drain();
        var keys = h.submits(SubmitKind.SUCCEEDED)
                    .getFirst()
                    .transaction()
                    .mutations()
                    .stream()
                    .map(m -> m.key())
                    .toList();

        assertThat(keys).containsExactlyInAnyOrder(new DeploymentOutcomeKey(blueprintId(APP)),
                                                   new DeploymentApplyKey(blueprintId(APP)));
    }

    // NT-80
    // Red when: a removal batch lacks the judgment and apply removes.
    @Test
    void blueprintRemoval_removesTargetsApplyAndJudgment() {
        var h = deployed(1);

        h.seed(new ArtifactJudgmentKey(ORDERS),
               new ArtifactJudgmentValue(false, Map.of(CORE_1, 2), Map.of()));
        h.drain();
        h.clearLog();
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
        var removed = h.submits()
                       .stream()
                       .flatMap(s -> s.transaction()
                                      .mutations()
                                      .stream())
                       .filter(m -> m.replacement()
                                     .isEmpty())
                       .map(m -> m.key())
                       .toList();

        assertThat(removed).contains(new SliceTargetKey(ORDERS.base()),
                                     new DeploymentApplyKey(blueprintId(APP)),
                                     new ArtifactJudgmentKey(ORDERS));
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS)).isPresent()).isFalse();
        assertThat(h.store.get(new DeploymentApplyKey(blueprintId(APP))).isPresent()).isFalse();
    }

    // NT-108
    // Red when: 6 FAILED in one drain produce more than one judgment mutation, a deferred goal, or more than one round.
    @Test
    void sixFailuresInOneDrain_makeOneJudgmentMutation_oneTransaction_noDeferredGoal() {
        var cores = new org.pragmatica.consensus.NodeId[6];

        for (var i = 0; i < 6; i++) {
            cores[i] = node("core-" + (i + 1));
        }

        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5, cores);

        h.activate();
        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), 6, 1));
        h.drain();
        for (var i = 0; i < 6; i++) {
            node(h, cores[i], 20 + i).writes(ORDERS, SliceObservedState.LOADING);
        }

        h.drain();
        h.clearLog();
        for (var i = 0; i < 6; i++) {
            node(h, cores[i], 20 + i).fails(ORDERS, false);
        }

        h.drain();
        var failure = h.submits(SubmitKind.FAILURE);

        assertThat(failure).as("one transaction, one round").hasSize(1);
        assertThat(failure.getFirst()
                          .transaction()
                          .mutations()
                          .stream()
                          .filter(m -> m.key() instanceof ArtifactJudgmentKey)).as("one judgment mutation")
                  .hasSize(1);
        assertThat(failure.getFirst()
                          .transaction()
                          .mutations()
                          .stream()
                          .filter(m -> m.key() instanceof org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey)).as("all six goals ride it")
                  .hasSize(6);
        assertThat(h.submits(SubmitKind.GOALS)).as("no deferred goal").isEmpty();
        assertThat(judgment(h).map(j -> j.retries()
                                         .size())).isEqualTo(Option.some(6));
    }

    // NT-113
    // Red when: unresponsive survives both 10T terms; no expiry event; still excluded after expiry.
    @Test
    void unresponsive_expiresAfterTenT_withTheEvent_andTheNodeIsChosenAgain() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5, CORE_1);

        h.activate();
        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();
        h.advance(3 * 120_000L + 31_000L);
        assertThat(judgment(h).map(j -> j.unresponsive()
                                         .containsKey(CORE_1))).isEqualTo(Option.some(true));
        var retractedAt = h.wallMs;

        h.advance(10 * 120_000L - 60_000L);
        assertThat(h.warns("slice-unresponsive-expired")).as("not before 10T").isEmpty();
        h.advance(2 * 60_000L);
        assertThat(h.wallMs - retractedAt).isGreaterThan(10 * 120_000L - 60_000L);
        assertThat(h.warns("slice-unresponsive-expired")).as("the expiry event").hasSize(1);
        assertThat(judgment(h)).as("no longer excluded").isEqualTo(Option.none());
        assertThat(h.model().effectiveGoal(pair(CORE_1, ORDERS))).as("chosen again")
                  .isEqualTo(Option.some(SliceGoal.LOADED));
    }

    // NT-113 (new leader: both terms)
    // Red when: only the wall term is consulted: a new leader expires an exclusion it has not watched for 10T.
    @Test
    void unresponsive_afterALeaderChange_needsTenTOfTheNewLeadersOwnClock() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5, CORE_1);

        h.activate();
        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();
        h.advance(3 * 120_000L + 31_000L);
        h.deliver(new ClusterInput.Deactivate());
        h.advance(20 * 120_000L);
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.activate();
        h.advance(9 * 120_000L);
        assertThat(judgment(h).map(j -> j.unresponsive()
                                         .containsKey(CORE_1))).as("old on the wall clock, young on the monotonic one")
                  .isEqualTo(Option.some(true));
        h.advance(2 * 120_000L);
        assertThat(judgment(h)).isEqualTo(Option.none());
    }

    // NT-113 (departure)
    // Red when: unresponsive survives the node's departure.
    @Test
    void unresponsive_endsWithTheNodesDeparture() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5, CORE_1, CORE_2);

        h.activate();
        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();
        h.advance(3 * 120_000L + 31_000L);
        assertThat(judgment(h).map(j -> j.unresponsive()
                                         .containsKey(CORE_1))).isEqualTo(Option.some(true));
        h.deliver(new ClusterInput.NodeRemoved(CORE_1));
        assertThat(judgment(h).map(j -> j.unresponsive()
                                         .containsKey(CORE_1)).or(false)).isFalse();
    }
}
