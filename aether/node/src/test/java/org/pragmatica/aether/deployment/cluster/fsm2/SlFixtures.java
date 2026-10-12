// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;


/// Helpers of the slice/placement/blueprint tests: a harness with the leader committed and the cores counted, targets and
/// goals seeded as a previous leader left them, and the BlueprintService publish batch.
final class SlFixtures {
    private SlFixtures() {}

    static final String ATTEMPT = "attempt-1";
    static final Generation G1 = new Generation(1L, 1L);

    static FsmHarness harness(NodeId... cores) {
        var h = FsmHarness.harness();
        var set = Set.of(cores);

        h.commitLeader(FsmHarness.SELF, 1L);
        h.seams = Seams.UNWIRED.withCountedCores(set).withReady(set);

        return h;
    }

    static FsmHarness threeCores() {
        return harness(CORE_1, CORE_2, CORE_3);
    }

    static SliceTargetValue target(Artifact artifact, int instances, int min, String placement) {
        return new SliceTargetValue(artifact.version(),
                                    instances,
                                    min,
                                    Option.none(),
                                    placement,
                                    FsmHarness.START_WALL,
                                    Option.none(),
                                    Option.none(),
                                    Option.none());
    }

    static SliceTargetValue ownedTarget(Artifact artifact, int instances, int min, BlueprintId owner) {
        return new SliceTargetValue(artifact.version(),
                                    instances,
                                    min,
                                    Option.some(owner),
                                    "CORE_ONLY",
                                    FsmHarness.START_WALL,
                                    Option.none(),
                                    Option.none(),
                                    Option.none());
    }

    static void seedTarget(FsmHarness h, Artifact artifact, int instances, int min) {
        h.seedQuietly(new SliceTargetKey(artifact.base()), target(artifact, instances, min, "CORE_ONLY"));
    }

    static void seedGoal(FsmHarness h, NodeId node, Artifact artifact, SliceGoal goal, Generation generation) {
        h.seedQuietly(new SliceDesiredKey(node, artifact), Fixtures.desired(goal, generation));
    }

    static void seedRow(FsmHarness h,
                        NodeId node,
                        Artifact artifact,
                        SliceObservedState state,
                        Generation generation,
                        long token) {
        h.seedQuietly(new SliceObservedKey(node, artifact), Fixtures.row(state, 1L, generation, process(token)));
    }

    static void seedJudgment(FsmHarness h, Artifact artifact, ArtifactJudgmentValue value) {
        h.seedQuietly(new ArtifactJudgmentKey(artifact), value);
    }

    /// What `BlueprintService` writes in the publish batch (spec Q8): the IN_PROGRESS outcome, the apply record, the blueprint.
    static void publish(FsmHarness h, AppBlueprintValue value, Option<AppBlueprintValue> previous) {
        var id = value.blueprint().id();

        h.seed(new DeploymentOutcomeKey(id),
               DeploymentOutcomeValue.inProgress(h.wallMs, 1L, value.attemptId()));
        h.seed(new DeploymentApplyKey(id), new DeploymentApplyValue(value.attemptId(), previous, h.wallMs));
        h.seed(new AppBlueprintKey(id), value);
        h.drain();
    }

    static NodeSim node(FsmHarness h, NodeId node, long token) {
        return new NodeSim(h, node, process(token));
    }

    static SliceObservedState stateOf(FsmHarness h, NodeId node, Artifact artifact) {
        return h.model()
                .observed(SlicePair.slicePair(node, artifact))
                .map(row -> row.state())
                .or(SliceObservedState.UNKNOWN);
    }

    static Option<SliceGoal> goalOf(FsmHarness h, NodeId node, Artifact artifact) {
        return h.model()
                .desired(SlicePair.slicePair(node, artifact))
                .map(value -> value.goal());
    }

    static long countGoals(FsmHarness h, Artifact artifact) {
        return h.model().desired
                .entries()
                .stream()
                .filter(entry -> entry.getKey()
                                      .artifact()
                                      .equals(artifact))
                .count();
    }

    static List<NodeId> hosts(FsmHarness h, Artifact artifact) {
        return h.model().desired
                .entries()
                .stream()
                .filter(entry -> entry.getKey()
                                      .artifact()
                                      .equals(artifact))
                .filter(entry -> entry.getValue()
                                      .goal() != SliceGoal.UNLOADED)
                .map(entry -> entry.getKey()
                                   .node())
                .sorted()
                .toList();
    }
}
