// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.update.DeploymentVersionRoundTripTest.BASE;
import static org.pragmatica.aether.update.DeploymentVersionRoundTripTest.DEPLOYMENT_ID;
import static org.pragmatica.aether.update.DeploymentVersionRoundTripTest.NEW_VERSION;
import static org.pragmatica.aether.update.DeploymentVersionRoundTripTest.OLD_VERSION;
import static org.pragmatica.aether.update.DeploymentVersionRoundTripTest.SELF;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.CapturingRabiaNode;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.seed;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubDeserializer;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubSerializer;

/// #1533 (B) — a fresh cluster activates its rollout manager at leader election, BEFORE the KV restore
/// lands, so activation sees no rollouts. When the restore decision commits, the node's restore hook calls
/// [DeploymentManager#reloadRestoredState]; the restored in-flight rollout is then resumed exactly as after
/// a leader failover, and the operator's handles on it work.
class DeploymentRestoreReloadTest {
    private CapturingRabiaNode rabiaNode;
    private KVStore<AetherKey, AetherValue> kvStore;
    private DeploymentManager manager;

    @BeforeEach
    void setUp() {
        rabiaNode = new CapturingRabiaNode(SELF);
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        manager = DeploymentManager.deploymentManager(rabiaNode, kvStore);
        manager.activate()
               .await()
               .onFailure(cause -> Assertions.fail(cause.message()));
    }

    @Test
    void reloadRestoredState_resumesARolloutRestoredAfterActivation_soRollbackRemovesItsRouting() {
        seed(kvStore, DeploymentKey.deploymentKey(DEPLOYMENT_ID), inFlightRollout());

        assertThat(manager.status(DEPLOYMENT_ID)
                          .isEmpty()).as("activation ran before the restore").isTrue();

        manager.reloadRestoredState();

        assertThat(manager.status(DEPLOYMENT_ID)
                          .isPresent()).as("the restored rollout is resumed").isTrue();
        rabiaNode.appliedCommands.clear();
        manager.rollback(DEPLOYMENT_ID)
               .onFailure(cause -> Assertions.fail("rollback of the restored rollout: " + cause.message()));

        assertThat(rabiaNode.appliedCommands).as("the rollback removes the rollout's routing")
                                             .anyMatch(command -> command instanceof KVCommand.Remove<?> remove
                                                                  && remove.key()
                                                                           .equals(VersionRoutingKey.versionRoutingKey(BASE)));
    }

    @Test
    void reloadRestoredState_isANoOp_whileInactive() {
        manager.deactivate()
               .await();
        seed(kvStore, DeploymentKey.deploymentKey(DEPLOYMENT_ID), inFlightRollout());

        manager.reloadRestoredState();

        assertThat(manager.status(DEPLOYMENT_ID)
                          .isEmpty()).isTrue();
    }

    /// A rolling update caught mid-shift: 30% of traffic on the new version.
    private static DeploymentValue inFlightRollout() {
        var now = System.currentTimeMillis();

        return DeploymentValue.deploymentValue(DEPLOYMENT_ID,
                                               "org.test:my-slice:2.0.0-rc3",
                                               OLD_VERSION.withQualifier(),
                                               NEW_VERSION.withQualifier(),
                                               DeploymentStrategy.ROLLING.name(),
                                               DeploymentState.ROUTING.name(),
                                               VersionRouting.versionRouting("3:7")
                                                             .unwrap()
                                                             .toString(),
                                               "",
                                               "",
                                               CleanupPolicy.GRACE_PERIOD.name(),
                                               BASE.asString(),
                                               3,
                                               now,
                                               now);
    }
}
