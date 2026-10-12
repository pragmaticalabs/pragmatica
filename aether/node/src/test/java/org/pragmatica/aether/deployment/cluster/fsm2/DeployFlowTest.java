// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// A whole deploy on the real applier: publish, targets, placement, the node chain, the dependency gate, the verdict.
class DeployFlowTest {
    private static final String ATTEMPT = "attempt-1";

    private FsmHarness harness() {
        var h = FsmHarness.harness();

        h.commitLeader(FsmHarness.SELF, 1L);
        h.seams = Seams.UNWIRED.withCountedCores(Set.of(CORE_1, CORE_2, CORE_3))
                               .withReady(Set.of(CORE_1, CORE_2, CORE_3));
        return h;
    }

    /// What `BlueprintService` writes in the publish batch (spec Q8).
    private void publish(FsmHarness h, org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue value) {
        var id = value.blueprint().id();

        h.seed(new DeploymentOutcomeKey(id), DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.seed(new DeploymentApplyKey(id), new DeploymentApplyValue(ATTEMPT, Option.none(), h.wallMs));
        h.seed(new AppBlueprintKey(id), value);
        h.drain();
    }

    @Test
    void deploy_reachesSucceeded_withTheApplyRecordRemoved() {
        var h = harness();

        h.activate();
        publish(h, blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, 2, 1, Set.of())));

        assertThat(h.store.get(new SliceTargetKey(ORDERS.base())).isPresent()).as("the target batch committed").isTrue();
        var goals = h.model().desired.entries();
        assertThat(goals).as("two instances placed, least-loaded first, ties by id").hasSize(2);
        assertThat(h.model().desired(SlicePair.slicePair(CORE_1, ORDERS)).map(SliceDesiredValue::goal)).isEqualTo(Option.some(SliceGoal.LOADED));
        assertThat(h.model().desired(SlicePair.slicePair(CORE_2, ORDERS)).map(SliceDesiredValue::goal)).isEqualTo(Option.some(SliceGoal.LOADED));

        new NodeSim(h, CORE_1, process(11)).follows(ORDERS);
        h.drain();
        assertThat(h.model().desired(SlicePair.slicePair(CORE_1, ORDERS)).map(SliceDesiredValue::goal)).as("LOADED answers the goal; the gate passes").isEqualTo(Option.some(SliceGoal.ACTIVE));

        new NodeSim(h, CORE_1, process(11)).follows(ORDERS);
        h.drain();

        var outcome = h.store.get(new DeploymentOutcomeKey(blueprintId("org.example:app:1.0.0")));
        assertThat(outcome.map(value -> ((DeploymentOutcomeValue) value).status())).isEqualTo(Option.some(DeploymentOutcomeStatus.SUCCEEDED));
        assertThat(h.store.get(new DeploymentApplyKey(blueprintId("org.example:app:1.0.0"))).isPresent()).as("SUCCEEDED and the apply GC are one transaction").isFalse();
    }
}
