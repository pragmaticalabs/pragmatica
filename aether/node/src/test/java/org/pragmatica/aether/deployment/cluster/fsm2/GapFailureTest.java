// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.ATTEMPT;
import static org.assertj.core.api.Assertions.assertThat;


/// Failure handling beyond the NT rows: the exhaustion discriminator (#922), BEST_EFFORT outcome merging, the rollback to a prior
/// version under another id, one rollback per drain, and idempotent handling of a repeated report.
class GapFailureTest {
    private FsmHarness leader(DeploymentAtomicity atomicity) {
        return LcSupport.leader(atomicity, 0.5d, CORE_1, CORE_2, CORE_3);
    }

    /// Red when: the sixth transient failure of an artifact with NO apply outstanding condemns it cluster-wide (#922: a transient on
    /// a workload whose deployment already reached its terminal must keep converging), or its counter is not cleared, or the
    /// operator is not told.
    @Test
    void exhaustion_withNoApplyOutstanding_isNotCondemned() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, SlFixtures.G1);
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.LOADING, SlFixtures.G1, 11L);
        SlFixtures.seedJudgment(h,
                                ORDERS,
                                new ArtifactJudgmentValue(false, Map.of(CORE_1, 5), Map.of()));
        h.activate();
        node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(h.warns("retry-exhausted-unsettled")).as("the sixth failure is reported, not condemned").hasSize(1);
        assertThat(h.effects(ClusterEffect.Route.class)).anyMatch(route -> route.event() instanceof RoutedEvent.DeploymentFailed);
        assertThat(h.model().judgment(ORDERS)).as("the counter is cleared and there is no permanent mark: nothing is left to record")
                  .isEqualTo(Option.none());
    }

    private static NodeSim node(FsmHarness h, NodeId node, long token) {
        return new NodeSim(h, node, process(token));
    }

    /// Red when: two artifacts of one blueprint failing fatally in one drain under BEST_EFFORT write two competing outcome
    /// records (one would lose the other's slice) instead of one FAILED record that names both.
    @Test
    void bestEffort_twoFailuresOneDrain_mergeIntoOneOutcomeRecord() {
        var h = leader(DeploymentAtomicity.BEST_EFFORT);

        h.activate();
        LcSupport.publish(h,
                          blueprint("org.example:app:1.0.0",
                                    false,
                                    ATTEMPT,
                                    slice(ORDERS, 1, 1, Set.of()),
                                    slice(BILLING, 1, 1, Set.of())));
        for (var artifact : new Artifact[]{ORDERS, BILLING}) {
            for (var node : h.model().desiredByArtifact.get(artifact).or(PSet.<NodeId> empty()).toList()) {
                node(h, node, 11L).writes(artifact, SliceObservedState.LOADING);
            }
        }

        h.drain();
        h.clearLog();
        for (var artifact : new Artifact[]{ORDERS, BILLING}) {
            for (var node : h.model().desiredByArtifact.get(artifact).or(PSet.<NodeId> empty()).toList()) {
                node(h, node, 11L).fails(artifact, true);
            }
        }

        h.drain();
        var outcomes = h.mutations(DeploymentOutcomeKey.class);

        assertThat(outcomes).as("one outcome mutation").hasSize(1);
        var merged = (DeploymentOutcomeValue) outcomes.getFirst().replacement().or((AetherValue) null);

        assertThat(merged.status()).isEqualTo(DeploymentOutcomeStatus.FAILED);
        assertThat(merged.failingSlices()).containsExactlyInAnyOrder(ORDERS.asString(), BILLING.asString());
    }

    /// Red when: a fatal failure with a prior version recorded under ANOTHER blueprint id restores it by overwriting the failed id
    /// (the failed blueprint stays) instead of putting the prior id and removing the failed one in the same transaction.
    @Test
    void rollback_toAPriorVersionUnderAnotherId_putsItAndRemovesTheFailedOne() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING);
        var prior = blueprint("org.example:app:0.9.0", false, "attempt-0", slice(ORDERS, 1, 1, Set.of()));

        h.seed(new AppBlueprintKey(prior.blueprint().id()),
               prior);
        h.activate();
        var next = blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS_V2, 1, 1, Set.of()));
        var nextId = next.blueprint().id();

        h.seed(new DeploymentOutcomeKey(nextId), DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.seed(new DeploymentApplyKey(nextId), new DeploymentApplyValue(ATTEMPT, Option.some(prior), h.wallMs));
        h.seed(new AppBlueprintKey(nextId), next);
        h.drain();
        var sim = node(h, CORE_1, 11L);

        sim.follows(ORDERS_V2);
        h.drain();
        sim.fails(ORDERS_V2, true);
        h.drain();
        assertThat(h.store.get(new AppBlueprintKey(nextId)).isPresent()).as("the failed blueprint is gone").isFalse();
        assertThat(h.store.get(new AppBlueprintKey(prior.blueprint().id()))).as("the prior one is restored under its own id")
                  .isEqualTo(Option.some((AetherValue) prior));
        assertThat(h.store.get(new DeploymentOutcomeKey(nextId)).map(value -> ((DeploymentOutcomeValue) value).status())).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
        assertThat(h.store.get(new DeploymentApplyKey(nextId)).isPresent()).as("the apply record goes with the rollback")
                  .isFalse();
    }

    /// Red when: two fatal failures of one blueprint's artifacts in one drain submit two rollbacks (the second would be refused
    /// as a whole by the first one's fence and burn the six-submission budget).
    @Test
    void twoFatalFailures_oneDrain_submitOneRollback() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING);

        h.activate();
        LcSupport.publish(h,
                          blueprint("org.example:app:1.0.0",
                                    false,
                                    ATTEMPT,
                                    slice(ORDERS, 1, 1, Set.of()),
                                    slice(BILLING, 1, 1, Set.of())));
        for (var artifact : new Artifact[]{ORDERS, BILLING}) {
            for (var node : h.model().desiredByArtifact.get(artifact).or(PSet.<NodeId> empty()).toList()) {
                node(h, node, 11L).writes(artifact, SliceObservedState.LOADING);
            }
        }

        h.drain();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.clearLog();
        for (var artifact : new Artifact[]{ORDERS, BILLING}) {
            for (var node : h.model().desiredByArtifact.get(artifact).or(PSet.<NodeId> empty()).toList()) {
                node(h, node, 11L).fails(artifact, true);
            }
        }

        h.drain();
        assertThat(h.submits(ClusterModel.SubmitKind.ROLLBACK)).hasSize(1);
    }

    /// Red when: the same FAILED notification delivered twice in one drain writes two UNLOADED goals (two generations) for the pair.
    @Test
    void repeatedFailedReport_isHandledOnce() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        h.activate();
        node(h, CORE_1, 11L).follows(ORDERS);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.clearLog();
        node(h, CORE_1, 11L).fails(ORDERS, false);
        h.mailbox.addLast(h.mailbox.peekFirst());
        h.drain();
        assertThat(h.mutations(SliceDesiredKey.class)).as("one UNLOADED goal").hasSize(1);
        assertThat(h.model().desired(SlicePair.slicePair(CORE_1, ORDERS)).map(value -> value.goal())).as("held: not committed")
                  .isEqualTo(Option.some(SliceGoal.ACTIVE));
    }
}
