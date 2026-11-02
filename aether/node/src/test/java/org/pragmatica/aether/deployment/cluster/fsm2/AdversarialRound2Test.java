// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.config.cluster.CommunityPlacement.Location;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The author's own adversarial pass over the classes the verifier found (lost and suspended writes, refusals that leave an event-only
/// intent behind). Each test is a scenario that was tried before the fix it names was made.
class AdversarialRound2Test {
    private static MbWorld workerWorld(NodeId worker) {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withCommunityPolicies(Map.of())
                                           .withMemberSource(Map.of(worker, "east")))
                      .activate();
    }

    /// Red when: a worker's DIRECTIVE transaction that failed (lost) is never written again: J5 runs on the join input only, and nothing
    /// else derives the directive, so the worker stays undirected until it rejoins.
    @Test
    void failedDirectiveWrite_isWrittenAgain() {
        var worker = node("worker-1");
        var w = workerWorld(worker);

        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, 21L)));
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).isNotEmpty();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(120_000L);
        assertThat(w.present(new ActivationDirectiveKey(worker))).as("directed after the lost write").isTrue();
    }

    /// Red when: a worker's DIRECTIVE transaction refused because the community it mints appeared meanwhile is dropped: the worker stays
    /// undirected.
    @Test
    void refusedDirectiveWrite_afterTheCommunityAppeared_isWrittenAgain() {
        var worker = node("worker-1");
        var w = workerWorld(worker);

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, 21L)));
        var first = w.h.submits(SubmitKind.DIRECTIVE).getFirst();
        var community = first.transaction()
                             .mutations()
                             .stream()
                             .filter(mutation -> mutation.key() instanceof CommunityKey)
                             .findFirst();

        assertThat(community).as("the first write mints the community").isPresent();
        w.h.held.clear();
        community.get().replacement().onPresent(value -> w.h.seed(community.get().key(),
                                                                  value));
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.settleHeld(first, w.h.refused(first));
        w.h.advance(120_000L);
        assertThat(w.present(new ActivationDirectiveKey(worker))).as("directed after the refusal").isTrue();
    }

    /// Red when: a transient failure decided while the leader is suspended is counted twice after the resume: the parked operation
    /// and the failure sweep's own decision (only the operations that answer an event are parked).
    @Test
    void transientFailure_decidedWhileSuspended_isCountedOnce() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, SlFixtures.G1);
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.LOADING, SlFixtures.G1, 11L);
        h.activate();
        h.deliver(new ClusterInput.WitnessStale());
        new NodeSim(h, CORE_1, process(11L)).fails(ORDERS, false);
        h.drain();
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.advance(60_000L);
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS))
                          .map(value -> ((ArtifactJudgmentValue) value).retries()
                                                                       .get(CORE_1))).isEqualTo(Option.some(1));
    }

    /// Red when: a worker whose source matches no configured community policy is dropped silently (no directive and no warning).
    @Test
    void workerMatchingNoPolicy_isReportedNotSilentlyDropped() {
        var worker = node("worker-1");
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withCommunityPolicies(Map.of("p",
                                                                          new CommunityPlacement("p",
                                                                                                 4,
                                                                                                 List.of(new Location("other-source",
                                                                                                                      Option.none(),
                                                                                                                      0,
                                                                                                                      1)))))
                                            .withMemberSource(Map.of(worker, "east")))
                       .activate();

        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, 21L)));
        assertThat(w.h.warns("worker-no-community-policy")).hasSize(1);
        assertThat(w.present(new ActivationDirectiveKey(worker))).isFalse();
    }

    /// Red when: a settlement for a submission the FSM no longer holds is dropped without a trace (it is counted as `settled_unknown`).
    @Test
    void settlementOfAnUnknownSubmission_isCounted() {
        var h = LcSupport.active();

        h.deliver(new ClusterInput.WriteSettled("c1-999", 1L, new ClusterInput.Settlement.Accepted()));
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total",
                                 tags -> "settled_unknown".equals(tags.get("outcome")))).isEqualTo(1L);
    }

    /// Red when: the removal of a blueprint whose write failed is never written again: the blueprint is already unindexed, so no
    /// reconcile step derives its target, apply and judgment removals.
    @Test
    void blueprintRemoval_whoseWriteFailed_isWrittenAgain() {
        var h = LcSupport.deployed(1);

        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        h.seedRemove(new AppBlueprintKey(blueprintId("org.example:app:1.0.0")));
        h.drain();
        assertThat(h.store.get(new SliceTargetKey(ORDERS.base())).isPresent()).as("the first write failed").isTrue();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advance(120_000L);
        assertThat(h.store.get(new SliceTargetKey(ORDERS.base())).isPresent()).as("removed by the retry").isFalse();
    }

    /// Red when: the removal of a blueprint whose write was lost (never settled) is never written again.
    @Test
    void blueprintRemoval_whoseWriteWasLost_isWrittenAgain() {
        var h = LcSupport.deployed(1);

        h.policy = FsmHarness.SettlePolicy.LOSE;
        h.seedRemove(new AppBlueprintKey(blueprintId("org.example:app:1.0.0")));
        h.drain();
        assertThat(h.store.get(new SliceTargetKey(ORDERS.base())).isPresent()).as("the first write was lost").isTrue();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advance(120_000L);
        assertThat(h.store.get(new SliceTargetKey(ORDERS.base())).isPresent()).as("removed by the retry").isFalse();
    }

    /// Red when: an exclusion whose clearing (the node answered UNLOADED) was in a transaction that failed stays recorded: the clearing
    /// is decided from an event, and no sweep decides it again.
    @Test
    void clearedExclusion_whoseWriteFailed_isWrittenAgain() {
        var h = LcSupport.active();

        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();
        var retracted = h.model()
                         .desired(SlicePair.slicePair(CORE_1, ORDERS))
                         .map(value -> value.generation())
                         .or(gen(0));
        var node = new NodeSim(h, CORE_1, process(11L));

        node.writes(ORDERS, SliceObservedState.LOADING);
        h.drain();
        h.advance(3 * 120_000L + 2_000L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS)).isPresent()).as("the node is excluded").isTrue();
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        node.writes(ORDERS, SliceObservedState.UNLOADED, retracted);
        h.drain();
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS)).isPresent()).as("the clearing write failed").isTrue();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advance(120_000L);
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS)).isPresent()).as("cleared by the retry").isFalse();
    }

    /// Red when: a stalled migration (MIGRATING, no lock) found at Rebuild while the leader value is not yet committed is reset only
    /// by a later Rebuild: the reset is a write, dropped by the suspended flush, and nothing derives it on the resume.
    @Test
    void stalledMigration_foundWhileSuspended_isResetOnTheResume() {
        var h = FsmHarness.harness();
        var owner = blueprintId("org.example:app:1.0.0");

        h.seams = Seams.UNWIRED.withCountedCores(Set.of(CORE_1)).withReady(Set.of(CORE_1));
        h.seedQuietly(new SchemaVersionKey("ds"),
                      new SchemaVersionValue("ds",
                                             1,
                                             "m1",
                                             SchemaStatus.MIGRATING,
                                             "g:a:1",
                                             owner,
                                             2,
                                             FsmHarness.START_WALL));
        h.activate();
        assertThat(h.submits(SubmitKind.SCHEMA)).as("suspended: nothing is written").isEmpty();
        h.commitLeader(FsmHarness.SELF, 1L);
        h.drain();
        assertThat(h.submits(SubmitKind.SCHEMA)).as("reset on the resume").hasSize(1);
    }
}
