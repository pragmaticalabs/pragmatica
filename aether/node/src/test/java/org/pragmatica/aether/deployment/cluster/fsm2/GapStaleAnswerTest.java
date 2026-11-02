// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// An answer is acted on only at the generation of the goal it answers (C10): the arm the retraction test does not reach, because
/// there the goal is UNLOADED and nothing downstream would act on an ACTIVE answer anyway.
class GapStaleAnswerTest {
    /// Red when: an ACTIVE answer for an older generation than the current ACTIVE goal is acted on: it would clear the node's retry
    /// count, which counts failures of the current goal. Control: the answer for the current generation does clear it.
    @Test
    void activeAnswerForAnOlderGeneration_clearsNothing_theCurrentOneClearsTheRetries() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, gen(2));
        SlFixtures.seedJudgment(h,
                                ORDERS,
                                new ArtifactJudgmentValue(false, Map.of(CORE_1, 2), Map.of()));
        h.activate();
        new NodeSim(h, CORE_1, process(11L)).writes(ORDERS, SliceObservedState.ACTIVE, gen(1));
        h.drain();
        assertThat(retriesOf(h)).as("the older generation's answer leaves the count alone")
                  .isEqualTo(Option.some(Map.of(CORE_1, 2)));
        new NodeSim(h, CORE_1, process(11L)).writes(ORDERS, SliceObservedState.ACTIVE, gen(2));
        h.drain();
        assertThat(retriesOf(h)).as("control: the current generation's answer clears it").isEqualTo(Option.none());
    }

    private static Option<Map<NodeId, Integer>> retriesOf(FsmHarness h) {
        return h.store.get(new ArtifactJudgmentKey(ORDERS))
                      .map(value -> ((ArtifactJudgmentValue) value).retries());
    }
}
