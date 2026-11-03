// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The production binding of the stamp (PR C obligation 1, v-2204 F3): `DeploymentWiring.deploymentWiring(config, worker, store, ...)` hands the
/// routes the store's OWN `notificationRevision` and `isReplaying`. Every other test builds the wiring with accessors it passes itself; this one goes
/// through the factory `AetherNode` calls, on a real `KVStore`, so a binding to a constant is red here and not only in a cluster run.
class DeploymentWiringProductionBindingTest {
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();

    private static void openAndEnter(Fsm2Harness harness) {
        harness.commitLeader();
        harness.leaderPut(AetherKey.DeploymentEngineKey.deploymentEngineKey(), new AetherValue.DeploymentEngineValue(DeploymentEngine.FSM2, Fsm2Harness.SELF, 1L));
        harness.awaitThat(harness.guard::isOpen);
        harness.router.route(ClusterStateNotification.active());
        harness.awaitThat(() -> harness.system.map(system -> system.nodeInterpreter().model().active()).or(false));
    }

    private static AetherValue.SliceObservedValue observed(long version) {
        return new AetherValue.SliceObservedValue(AetherValue.SliceObservedState.ACTIVE,
                                                  version,
                                                  new AetherValue.Generation(1L, 1L),
                                                  new AetherValue.ProcessIncarnation(1L, 5L),
                                                  Option.none(),
                                                  false,
                                                  0,
                                                  List.of(),
                                                  1L);
    }

    /// The revision stamp: facts committed after the node's snapshot are stepped, none is dropped as "inside" it. A binding to `0` stamps every fact
    /// with a revision at or below the snapshot's, so all of them would be dropped.
    @Test
    void theFactoryBindsTheStoresRevision_soLaterFactsAreStepped_notDropped() {
        try (var harness = Fsm2Harness.production(DeploymentEngine.FSM2).assembled()) {
            openAndEnter(harness);
            var before = harness.count("ObservedPut", "applied", "node");

            harness.put(SliceObservedKey.sliceObservedKey(Fsm2Harness.PEER, ORDERS), observed(1L));
            harness.put(SliceObservedKey.sliceObservedKey(Fsm2Harness.PEER, ORDERS), observed(2L));
            harness.awaitThat(() -> harness.count("ObservedPut", "applied", "node") >= before + 2);

            assertThat(harness.count("ObservedPut", "dropped_revision", "node")).as("none mistaken for a fact inside the snapshot").isZero();
        }
    }

    /// The replay flag: a replayed view is dropped by the core rule, not applied as fact. A binding to `false` would apply it.
    @Test
    void theFactoryBindsTheStoresReplayFlag_soAReplayedViewIsDropped() {
        try (var harness = Fsm2Harness.production(DeploymentEngine.FSM2).assembled()) {
            openAndEnter(harness);
            harness.put(SliceObservedKey.sliceObservedKey(Fsm2Harness.PEER, ORDERS), observed(1L));
            harness.awaitThat(() -> harness.count("ObservedPut", "applied", "node") >= 1);
            var applied = harness.count("ObservedPut", "applied", "node");

            harness.store.replayNotifications();

            harness.awaitThat(() -> harness.count("ObservedPut", "dropped_replay", "node") >= 1);
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> harness.count("ObservedPut", "applied", "node") == applied);
        }
    }
}
