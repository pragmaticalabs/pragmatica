// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.update.DeploymentManagerImplStatusTest.committingNode;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.BASE;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.OWNER;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.V1;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.V2;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.seed;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.seedLeaderAndOwner;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubDeserializer;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubSerializer;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// `DeploymentManagerImpl.start` against a store the manager commits into, with no racing writer: what the one start transaction writes.
/// `SliceTargetWriterOrderingTest` pins the races; this pins the values. After a target's creation the requirement combiner is the only
/// writer of `targetInstances` (rc6 §3.1), so a start moves a committed target's version and keeps its count, while a target it creates
/// takes the deployment's count.
class DeploymentManagerImplStartTest {
    private static final ArtifactBase SECOND = Artifact.artifact("org.test:second-slice:1.0.0").unwrap().base();
    private static final SliceTargetKey BASE_TARGET = SliceTargetKey.sliceTargetKey(BASE);
    private static final SliceTargetKey SECOND_TARGET = SliceTargetKey.sliceTargetKey(SECOND);
    private static final int COMMITTED_COUNT = 7;
    private static final int DEPLOYMENT_COUNT = 5;

    private KVStore<AetherKey, AetherValue> store;
    private DeploymentManager manager;

    @BeforeEach
    void leaderOwnerAndCommittedTarget() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        seedLeaderAndOwner(store);
        seed(store, AppBlueprintKey.appBlueprintKey(OWNER), twoSliceBlueprint());
        seed(store, BASE_TARGET, SliceTargetValue.sliceTargetValue(V1, COMMITTED_COUNT, Option.some(OWNER)));
        manager = DeploymentManager.deploymentManager(committingNode(store), store);
    }

    private static AppBlueprintValue twoSliceBlueprint() {
        var first = ResolvedSlice.resolvedSlice(BASE.withVersion(V1), 3, false).unwrap();
        var second = ResolvedSlice.resolvedSlice(SECOND.withVersion(V1), 3, false).unwrap();

        return AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER, List.of(first, second)));
    }

    private Result<Deployment> start(String blueprint) {
        return manager.start(blueprint,
                             V2,
                             DeploymentStrategy.BLUE_GREEN,
                             new StrategyConfig.BlueGreenConfig(timeSpan(30).seconds()),
                             HealthThresholds.DEFAULT,
                             CleanupPolicy.GRACE_PERIOD,
                             DEPLOYMENT_COUNT);
    }

    private void activate() {
        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
    }

    private Option<SliceTargetValue> target(SliceTargetKey key) {
        return store.get(key).filter(SliceTargetValue.class::isInstance).map(SliceTargetValue.class::cast);
    }

    /// The target the deployment moves keeps the count the combiner committed; only its version changes.
    @Test
    void start_committedTarget_movesTheVersionAndKeepsTheCommittedCount() {
        activate();

        assertThat(start(OWNER.asString()).isSuccess()).isTrue();
        assertThat(target(BASE_TARGET).map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V2));
        assertThat(target(BASE_TARGET).map(SliceTargetValue::targetInstances)).isEqualTo(Option.some(COMMITTED_COUNT));
        assertThat(target(BASE_TARGET).map(SliceTargetValue::owningBlueprint)).isEqualTo(Option.some(Option.some(OWNER)));
    }

    /// A slice the blueprint declares but no target exists for is created at the new version and the deployment's count (witnessed by the blueprint, see SliceTargetWriterOrderingTest).
    @Test
    void start_absentTarget_isCreatedAtTheDeploymentsCount() {
        activate();

        assertThat(start(OWNER.asString()).isSuccess()).isTrue();
        assertThat(target(SECOND_TARGET).map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V2));
        assertThat(target(SECOND_TARGET).map(SliceTargetValue::targetInstances)).isEqualTo(Option.some(DEPLOYMENT_COUNT));
    }

    /// The same transaction records the deployment and routes every base of the blueprint.
    @Test
    void start_writesTheDeploymentRecordAndTheRoutingOfEveryBase() {
        activate();

        var deployment = start(OWNER.asString()).unwrap();

        assertThat(store.get(DeploymentKey.deploymentKey(deployment.deploymentId()))).isNotEqualTo(Option.none());
        assertThat(store.get(VersionRoutingKey.versionRoutingKey(BASE))).isNotEqualTo(Option.none());
        assertThat(store.get(VersionRoutingKey.versionRoutingKey(SECOND))).isNotEqualTo(Option.none());
        assertThat(manager.list()).extracting(Deployment::deploymentId).containsExactly(deployment.deploymentId());
    }

    /// A passive manager (STRATEGIES not assigned here) refuses and writes nothing.
    @Test
    void start_passiveManager_isRefusedAndWritesNothing() {
        var result = start(OWNER.asString());

        assertThat(result.isFailure()).isTrue();
        assertThat(target(BASE_TARGET).map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V1));
        assertThat(target(SECOND_TARGET)).isEqualTo(Option.none());
    }

    /// One active deployment per blueprint: a second start is refused and leaves the first one's writes alone.
    @Test
    void start_blueprintAlreadyDeploying_isRefusedAndLeavesTheTargetsAlone() {
        activate();
        start(OWNER.asString()).unwrap();

        var second = start(OWNER.asString());

        assertThat(second.isFailure()).isTrue();
        assertThat(manager.list()).hasSize(1);
        assertThat(target(BASE_TARGET).map(SliceTargetValue::targetInstances)).isEqualTo(Option.some(COMMITTED_COUNT));
    }

    /// A blueprint that is not committed is refused and writes nothing.
    @Test
    void start_unknownBlueprint_isRefusedAndWritesNothing() {
        activate();

        var result = start("org.test:other-app:1.0.0");

        assertThat(result.isFailure()).isTrue();
        assertThat(target(BASE_TARGET).map(SliceTargetValue::currentVersion)).isEqualTo(Option.some(V1));
        assertThat(manager.list()).isEmpty();
    }
}
