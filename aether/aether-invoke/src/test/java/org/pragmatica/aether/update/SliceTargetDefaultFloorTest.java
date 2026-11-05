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

/// #1497 — `DeploymentManagerImpl.addSliceTargetCommand` used to write a slice target through the creation
/// factory when none existed, and the floor of that first write was ruled `ceil(instances/2)` (owner
/// ruling 2026-09-25). A rollback whose slice had no committed target was the path that reached it.
///
/// #2662 removed that write: the target of a rolled-out blueprint slice is absent only once its blueprint
/// was deleted (or before it committed one), and an unowned target the rollback created then would outlive
/// the blueprint, because a blueprint delete removes only the targets it owns. The rollback restores
/// nothing for such a slice and still ends. The #1497 floor stays pinned where a target is still created:
/// `SliceRoutesScaleFloorTest` and `SliceTargetValueTest`.
class SliceTargetDefaultFloorTest {
    private static final String DEPLOYMENT_ID = "deployment-1497";
    private static final int INSTANCES = 4;

    @Test
    void rollback_targetNotCommitted_writesNoTarget_andTheDeploymentEnds() {
        var rabiaNode = new SliceTargetOverridePreservationTest.CapturingRabiaNode(SELF);
        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        SliceTargetOverridePreservationTest.seedLeaderAndOwner(kvStore);
        seed(kvStore, DeploymentKey.deploymentKey(DEPLOYMENT_ID), deployedValue());

        var manager = DeploymentManager.deploymentManager(rabiaNode, kvStore);

        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));

        var rolledBack = manager.rollback(DEPLOYMENT_ID)
                                .fold(cause -> Assertions.<Deployment>fail(cause.message()), deployment -> deployment);

        assertThat(capturedSliceTargets(rabiaNode.appliedCommands)).as("no target is created for a slice that has none")
                                                                   .isEmpty();
        assertThat(rolledBack.state()).as("the rollback still ends the deployment")
                                      .isEqualTo(DeploymentState.ROLLED_BACK);
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
