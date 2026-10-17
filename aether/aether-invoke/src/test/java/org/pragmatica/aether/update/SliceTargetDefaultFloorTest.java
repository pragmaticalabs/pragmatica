// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.BASE;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.SELF;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.V1;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.V2;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.capturedSliceTargets;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.seed;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubDeserializer;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubSerializer;

/// #1497 — `DeploymentManagerImpl.addSliceTargetCommand` writes a slice target through the creation
/// factory when none exists yet, and it used to write `minInstances == instances`. The #1488 drain guard
/// then could never drain an owner of that slice. Owner ruling (2026-09-25): the default is the blueprint
/// floor, `ceil(instances/2)`.
///
/// A rollback whose slice has no current target is the path that reaches that first write: the manager
/// restores the old version at the deployment's instance count (4 here, so the default 2 is
/// distinguishable from the count).
class SliceTargetDefaultFloorTest {
    private static final String DEPLOYMENT_ID = "deployment-1497";
    private static final int INSTANCES = 4;

    @Test
    void rollback_firstTargetWrite_takesTheDefaultFloor_notTheInstanceCount() {
        var rabiaNode = new SliceTargetOverridePreservationTest.CapturingRabiaNode(SELF);
        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        seed(kvStore, DeploymentKey.deploymentKey(DEPLOYMENT_ID), deployedValue());

        var manager = DeploymentManager.deploymentManager(rabiaNode, kvStore);

        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        manager.rollback(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));

        var written = capturedSliceTargets(rabiaNode.appliedCommands).stream()
                                                                     .filter(target -> target.currentVersion()
                                                                                             .equals(V1))
                                                                     .toList();

        assertThat(written).as("the rollback must write the restored target").hasSize(1);
        assertThat(written.getFirst().targetInstances()).isEqualTo(INSTANCES);
        assertThat(written.getFirst().minInstances()).as("#1497: ceil(4/2), not the instance count")
                                                     .isEqualTo(2);
    }

    private static DeploymentValue deployedValue() {
        var now = System.currentTimeMillis();

        return DeploymentValue.deploymentValue(DEPLOYMENT_ID,
                                               "org.test:my-slice:2.0.0",
                                               V1.bareVersion(),
                                               V2.bareVersion(),
                                               DeploymentStrategy.ROLLING.name(),
                                               DeploymentState.DEPLOYED.name(),
                                               VersionRouting.ALL_OLD.toString(),
                                               "",
                                               "",
                                               CleanupPolicy.GRACE_PERIOD.name(),
                                               BASE.asString(),
                                               INSTANCES,
                                               now,
                                               now);
    }
}
