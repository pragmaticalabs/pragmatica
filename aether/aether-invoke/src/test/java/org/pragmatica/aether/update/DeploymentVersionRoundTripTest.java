// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.CapturingRabiaNode;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.seed;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubDeserializer;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubSerializer;

/// #438 regression: the deployment writer (`DeploymentManagerImpl.addDeploymentCommand`) must persist
/// each [Version] in the canonical `major.minor.patch[-qualifier]` form that [Version#version(String)]
/// re-parses, not the record's default `toString()` (`Version[major=…]`), which the parser rejects.
/// The restore path (`activate` -> `restoreState` -> `restoreDeployment`) runs on every leader change
/// and would otherwise silently drop every active deployment persisted by a prior leader.
class DeploymentVersionRoundTripTest {
    static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    static final ArtifactBase BASE = Artifact.artifact("org.test:my-slice:2.0.0-rc3").unwrap().base();
    static final Version OLD_VERSION = Version.version("1.0.0").unwrap();
    static final Version NEW_VERSION = Version.version("2.0.0-rc3").unwrap();
    static final String DEPLOYMENT_ID = "deployment-1";

    private CapturingRabiaNode rabiaNode;
    private DeploymentManager manager;

    @BeforeEach
    void setUp() {
        rabiaNode = new CapturingRabiaNode(SELF);

        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                          stubSerializer(),
                                                          stubDeserializer());

        seed(kvStore, DeploymentKey.deploymentKey(DEPLOYMENT_ID), deployedDeploymentValue());

        manager = DeploymentManager.deploymentManager(rabiaNode, kvStore);
        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
    }

    @Test
    void activate_restoresQualifiedVersions_fromCanonicalFormat() {
        var restored = manager.status(DEPLOYMENT_ID)
                              .onEmpty(() -> Assertions.fail("active deployment was not restored"))
                              .unwrap();

        assertThat(restored.oldVersion()).isEqualTo(OLD_VERSION);
        assertThat(restored.newVersion()).isEqualTo(NEW_VERSION);
    }

    @Test
    void rollback_persistsReparseableVersions_forNextLeaderRestore() {
        rabiaNode.appliedCommands.clear();
        manager.rollback(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));

        var persisted = capturedDeploymentValues(rabiaNode.appliedCommands);

        assertThat(persisted).isNotEmpty();
        assertThat(persisted).allSatisfy(this::assertVersionsReparse);
    }

    private void assertVersionsReparse(DeploymentValue dv) {
        Version.version(dv.oldVersion())
               .onFailure(cause -> Assertions.fail("oldVersion not reparseable: " + cause.message()))
               .onSuccess(version -> assertThat(version).isEqualTo(OLD_VERSION));
        Version.version(dv.newVersion())
               .onFailure(cause -> Assertions.fail("newVersion not reparseable: " + cause.message()))
               .onSuccess(version -> assertThat(version).isEqualTo(NEW_VERSION));
    }

    private DeploymentValue deployedDeploymentValue() {
        var now = System.currentTimeMillis();

        return DeploymentValue.deploymentValue(DEPLOYMENT_ID,
                                               "org.test:my-slice:2.0.0-rc3",
                                               OLD_VERSION.withQualifier(),
                                               NEW_VERSION.withQualifier(),
                                               DeploymentStrategy.ROLLING.name(),
                                               DeploymentState.DEPLOYED.name(),
                                               VersionRouting.ALL_OLD.toString(),
                                               "",
                                               "",
                                               CleanupPolicy.GRACE_PERIOD.name(),
                                               BASE.asString(),
                                               3,
                                               now,
                                               now);
    }

    private static List<DeploymentValue> capturedDeploymentValues(List<KVCommand<AetherKey>> commands) {
        return commands.stream()
                       .filter(KVCommand.Put.class::isInstance)
                       .map(command -> ((KVCommand.Put<?, ?>) command).value())
                       .filter(DeploymentValue.class::isInstance)
                       .map(DeploymentValue.class::cast)
                       .toList();
    }
}
