// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.lang.Option;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// Helpers shared by the lifecycle / write-path / judgment tests (fork LC).
final class LcSupport {
    static final String ATTEMPT = "attempt-1";

    private LcSupport() {}

    /// A harness whose committed leader is the FSM's own node and whose three cores are ready.
    static FsmHarness leader() {
        var h = FsmHarness.harness();

        h.commitLeader(FsmHarness.SELF, 1L);
        h.seams = Seams.UNWIRED.withCountedCores(Set.of(CORE_1, CORE_2, CORE_3))
                               .withReady(Set.of(CORE_1, CORE_2, CORE_3));
        return h;
    }

    static FsmHarness active() {
        return leader().activate();
    }

    /// What `BlueprintService` writes in the publish batch (Q8): outcome IN_PROGRESS, the apply record, the blueprint.
    static void publish(FsmHarness h, AppBlueprintValue value) {
        publish(h, value, Option.none());
    }

    static void publish(FsmHarness h, AppBlueprintValue value, Option<AppBlueprintValue> previous) {
        var id = value.blueprint().id();

        var version = h.store.get(new DeploymentOutcomeKey(id))
                             .filter(DeploymentOutcomeValue.class::isInstance)
                             .map(DeploymentOutcomeValue.class::cast)
                             .map(outcome -> outcome.outcomeVersion() + 1)
                             .or(1L);

        h.seed(new DeploymentOutcomeKey(id), DeploymentOutcomeValue.inProgress(h.wallMs, version, value.attemptId()));
        h.seed(new DeploymentApplyKey(id), new DeploymentApplyValue(value.attemptId(), previous, h.wallMs));
        h.seed(new AppBlueprintKey(id), value);
        h.drain();
    }

    /// A harness with ORDERS (`instances`) deployed to the point where goals are committed.
    static FsmHarness deployed(int instances) {
        var h = active();

        publish(h, blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, instances, 1, Set.of())));
        return h;
    }

    /// A leader harness for \`atomicity\`, \`cores\` cores ready, jitter fixed at \`jitter\`.
    static FsmHarness leader(org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity atomicity,
                             double jitter,
                             org.pragmatica.consensus.NodeId... cores) {
        var settings = Settings.settings(FsmHarness.SELF, atomicity, Set.of(), Fixtures.bounds())
                               .withJitter(_ -> jitter);
        var h = new FsmHarness(settings);

        h.commitLeader(FsmHarness.SELF, 1L);
        h.seams = Seams.UNWIRED.withCountedCores(Set.of(cores)).withReady(Set.of(cores));
        return h;
    }

    static NodeSim node(FsmHarness h, org.pragmatica.consensus.NodeId node, long token) {
        return new NodeSim(h, node, Fixtures.process(token));
    }

    static SlicePair pair(org.pragmatica.consensus.NodeId node, org.pragmatica.aether.artifact.Artifact artifact) {
        return SlicePair.slicePair(node, artifact);
    }
}
