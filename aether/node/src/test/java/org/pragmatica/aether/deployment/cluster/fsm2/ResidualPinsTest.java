// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Properties the second mutation battery found unpinned (round 2 of v-2174): each test names the line whose removal it holds.
class ResidualPinsTest {
    private static final long TOKEN_2 = 0x2222L;
    private static final String APP = "org.example:app:1.0.0";

    private static MbWorld coreWorld() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 1L, TOKEN_2);
        w.admit(CORE_3, 1L, 0x3333L);

        return w.directive(CORE_2,
                           ActivationDirectiveValue.core())
                .target(ORDERS, 1, "CORE_ONLY")
                .instance(CORE_2,
                          ORDERS,
                          SliceGoal.ACTIVE,
                          SliceObservedState.ACTIVE,
                          gen(1),
                          TOKEN_2)
                .record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null);
    }

    /// Red when: the re-read of a failed departure drops `departing` on any change of the record, including an ADMITTED at the epoch
    /// the departure names (the admission of the very incarnation that is leaving): it must resubmit against the new record.
    @Test
    void reread_keepsADepartureWhoseRecordWasRewrittenAtItsOwnEpoch() {
        var w = coreWorld().activate();

        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.seed(new NodeDepartureKey(CORE_2),
                 new NodeDepartureValue(DepartureState.ADMITTED, 1L, process(TOKEN_2), Option.none(), w.h.wallMs + 5L));
        assertThat(w.h.model().isDeparting(CORE_2)).as("the notification at its own epoch keeps it").isTrue();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(31_000L);
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    /// Red when: a Rebuild-in-Active forgets the generations the old model had allocated but not yet committed: the next goal then gets
    /// a generation equal to the one still in flight (R-1.6.1: monotone).
    @Test
    void rebuildWhileActive_neverIssuesAnAllocatedGenerationAgain() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        var first = generationOf(h.submits(SubmitKind.GOALS).getFirst());

        h.held.clear();
        h.deliver(new ClusterInput.Rebuild(h.snapshot()));
        h.deliver(new ClusterInput.Reconcile());
        var later = generationOf(h.submits(SubmitKind.GOALS).getLast());

        assertThat(later.compareTo(first)).as("a fresh generation, strictly above the one allocated before the rebuild")
                  .isGreaterThan(0);
    }

    private static Generation generationOf(ClusterEffect.Submit submit) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .filter(mutation -> mutation.key() instanceof SliceDesiredKey)
                     .flatMap(mutation -> mutation.replacement()
                                                  .stream())
                     .map(value -> ((SliceDesiredValue) value).generation())
                     .findFirst()
                     .orElse(gen(0));
    }

    /// Two workers joining in one drain for a community that does not exist yet are directed in one round: the second mint joins the
    /// first transaction (no refusal round, §7.0). Red when the second is written alone, expecting the community absent, and refused.
    @Test
    void twoWorkerJoins_inOneDrain_needNoRefusalRound() {
        var w1 = node("worker-1");
        var w2 = node("worker-2");
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withCommunityPolicies(Map.of())
                                            .withMemberSource(Map.of(w1, "east", w2, "east")))
                       .activate();

        w.h.deliverTogether(new ClusterInput.WorkerJoin(w1, w.admit(w1, 1L, 21L)),
                            new ClusterInput.WorkerJoin(w2, w.admit(w2, 1L, 22L)));
        w.h.advance(60_000L);
        assertThat(w.present(new ActivationDirectiveKey(w2))).isTrue();
        assertThat(w.h.metricTotal("aether_deployment_fsm_submissions_total",
                                   tags -> tags.getOrDefault("outcome", "")
                                               .startsWith("refused"))).isZero();
    }

    /// Red when: a refused blueprint removal is not re-sent at the mirror's CURRENT values: the judgment moved after the first attempt
    /// was built, and the second attempt must remove it at its new value.
    @Test
    void refusedBlueprintRemoval_isResentAtTheValuesTheMirrorHasNow() {
        var h = deployed(1);
        var first = new ArtifactJudgmentValue(false, Map.of(CORE_1, 1), Map.of());
        var moved = new ArtifactJudgmentValue(false, Map.of(CORE_1, 2), Map.of());

        h.seed(new ArtifactJudgmentKey(ORDERS), first);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.clearLog();
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
        var attempt = h.submits(SubmitKind.BLUEPRINT_REMOVAL).getFirst();
        var before = h.submits(SubmitKind.BLUEPRINT_REMOVAL).size();

        h.held.clear();
        h.seed(new ArtifactJudgmentKey(ORDERS), moved);
        h.settleHeld(attempt, h.refused(attempt));
        assertThat(h.submits(SubmitKind.BLUEPRINT_REMOVAL)).hasSize(before + 1);
        assertThat(h.submits(SubmitKind.BLUEPRINT_REMOVAL)
                    .getLast()
                    .transaction()
                    .mutations()
                    .stream()
                    .filter(mutation -> mutation.key() instanceof ArtifactJudgmentKey)
                    .map(mutation -> mutation.expected())
                    .toList()).containsExactly(Option.some(moved));
    }

    /// Red when: the removal of a finished unload's desired key is batched into the draft that removes a departed node's record: a
    /// refusal of one would void the other. Both are queued in one drain here (a record collected by the reconcile, then an observed
    /// remove), the order that can reach the merge.
    @Test
    void desiredKeyRemoval_doesNotJoinTheRecordRemovalDraft() {
        var w = coreWorld().activate();
        var model = w.h.model();
        var record = new NodeDepartureValue(DepartureState.DEPARTED,
                                            1L,
                                            process(0x3333L),
                                            Option.some(DepartureCause.NODE_REMOVED),
                                            w.h.wallMs);

        Outbox.direct(model,
                      ClusterModel.TxDraft.draft(SubmitKind.GC,
                                                 new NodeDepartureKey(CORE_3),
                                                 List.of(DepartureFlow.removal(new NodeDepartureKey(CORE_3), record))));
        Gc.removeDesired(model, SlicePair.slicePair(CORE_2, ORDERS));
        assertThat(model.outbox.stream().filter(draft -> draft.kind() == SubmitKind.GC)).as("the record draft and the desired-key draft")
                  .hasSize(2);
    }

    private static boolean touches(ClusterEffect.Submit submit, Class<?> keyType) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(mutation -> keyType.isInstance(mutation.key()));
    }

    /// Red when: a goal write whose observed guard went stale within the drain is still sent in the artifact's failure transaction
    /// (the applier would refuse the whole transaction).
    @Test
    void staleGoalWrite_isLeftOutOfTheFailureTransaction() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, SlFixtures.G1);
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.LOADING, SlFixtures.G1, 11L);
        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.clearLog();
        var key = new SliceObservedKey(CORE_1, ORDERS);
        var failed = new SliceObservedValue(SliceObservedState.FAILED,
                                            9L,
                                            SlFixtures.G1,
                                            process(11L),
                                            Option.some("boom"),
                                            false,
                                            1,
                                            List.of(),
                                            h.wallMs);
        var recovered = new SliceObservedValue(SliceObservedState.LOADING,
                                               10L,
                                               SlFixtures.G1,
                                               process(11L),
                                               Option.none(),
                                               false,
                                               0,
                                               List.of(),
                                               h.wallMs);

        h.deliverTogether(new ClusterInput.KvPut(key, failed), new ClusterInput.KvPut(key, recovered));
        assertThat(h.submits()
                    .stream()
                    .flatMap(submit -> submit.transaction()
                                             .mutations()
                                             .stream())
                    .filter(mutation -> mutation.key()
                                                .equals(new SliceDesiredKey(CORE_1, ORDERS)))
                    .filter(mutation -> mutation.replacement()
                                                .map(value -> ((SliceDesiredValue) value).goal() == SliceGoal.UNLOADED)
                                                .or(false))).isEmpty();
    }
}
