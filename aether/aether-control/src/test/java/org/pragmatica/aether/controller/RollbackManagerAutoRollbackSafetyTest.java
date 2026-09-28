// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.controller.RollbackManagerOverridePreservationTest.AlwaysLeaderManager;
import org.pragmatica.aether.controller.RollbackManagerOverridePreservationTest.CapturingClusterNode;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #1573 rollback safety, one property per test. Each negative case is armed by the positive
/// `insideBakeWindow_rollsBack_recordFirstInOneBatch`: the same fixture rolls back unless the one
/// condition under test holds.
class RollbackManagerAutoRollbackSafetyTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final ArtifactBase BASE = Artifact.artifact("org.test:my-slice:2.0.0").unwrap().base();
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();
    private static final Version V3 = Version.version("3.0.0").unwrap();
    private static final RollbackConfig CONFIG = RollbackConfig.rollbackConfig(true,
                                                                               true,
                                                                               TimeSpan.timeSpan(5).minutes(),
                                                                               2,
                                                                               TimeSpan.timeSpan(15).minutes())
                                                               .unwrap();

    private final List<RollbackEvent.AutoRollbackExecuted> reported = new CopyOnWriteArrayList<>();
    private CapturingClusterNode clusterNode;
    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        clusterNode = new CapturingClusterNode(SELF);
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        seed(SliceTargetKey.sliceTargetKey(BASE), SliceTargetValue.sliceTargetValue(V2, 3));
    }

    @Test
    void insideBakeWindow_rollsBack_recordFirstInOneBatch() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).hasSize(2);
        var first = value(0);
        var second = value(1);

        assertThat(first).as("the rollback record goes first, so the target put's notification sees it")
                         .isInstanceOf(PreviousVersionValue.class);
        assertThat(second).isInstanceOf(SliceTargetValue.class);
        var committed = (PreviousVersionValue) first;

        assertThat(committed.currentVersion()).isEqualTo(V1);
        assertThat(committed.rollbackCount()).isEqualTo(1);
        assertThat(committed.lastRollbackAt()).isPositive();
        assertThat(committed.failedVersions()).containsExactly(V2);
        assertThat(((SliceTargetValue) second).currentVersion()).isEqualTo(V1);
    }

    @Test
    void outsideBakeWindow_neverRollsBack() {
        seed(PreviousVersionKey.previousVersionKey(BASE),
             record(V1, V2, now() - TimeSpan.timeSpan(16).minutes().millis(), 0, 0, List.of()));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).as("an old version failing is an incident, not a bad deploy").isEmpty();
    }

    @Test
    void rollbackTargetPreviouslyFailed_neverRollsBack() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of(V1)));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).as("no oscillation onto a version that already failed").isEmpty();
    }

    @Test
    void persistedRollbackCount_enforcesMaxAcrossLeaderChange() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 2, 0, List.of()));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).as("a fresh manager (new leader) reads the committed count").isEmpty();
    }

    @Test
    void persistedLastRollback_enforcesCooldownAcrossLeaderChange() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 1, now(), List.of()));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    @Test
    void staleEvent_forANoLongerCurrentVersion_neverRollsBack() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));

        manager().onAllInstancesFailed(failure(V3));

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    @Test
    void activeManagedDeployment_ownsTheArtifact_neverRollsBack() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        seed(DeploymentKey.deploymentKey("dep-1"), deployment("ROUTING"));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).as("a canary/rolling deployment owns its own rollback").isEmpty();
    }

    @Test
    void terminalManagedDeployment_doesNotBlockRollback() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        seed(DeploymentKey.deploymentKey("dep-1"), deployment("COMPLETED"));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).hasSize(2);
    }

    @Test
    void triggerDisabled_neverRollsBack() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        var config = RollbackConfig.rollbackConfig(true, false, TimeSpan.timeSpan(5).minutes(), 2).unwrap();

        RollbackManager.rollbackManager(SELF, config, clusterNode, kvStore, new AlwaysLeaderManager(SELF))
                       .onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    /// #1573: the policy is read at every decision, so a committed `enabled = false` takes effect with no
    /// restart, and turning it back on works the same way.
    @Test
    void policyReadAtDecisionTime_disableThenEnable_withoutRebuildingTheManager() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        var policy = new AtomicReference<>(RollbackConfig.rollbackConfig(false));
        var manager = RollbackManager.rollbackManager(SELF,
                                                      policy::get,
                                                      clusterNode,
                                                      kvStore,
                                                      new AlwaysLeaderManager(SELF),
                                                      reported::add);

        manager.onAllInstancesFailed(failure(V2));
        assertThat(clusterNode.appliedCommands).as("disabled by the committed policy").isEmpty();

        policy.set(CONFIG);
        manager.onAllInstancesFailed(failure(V2));
        assertThat(clusterNode.appliedCommands).as("re-enabled without a restart").hasSize(2);
    }

    /// Every committed rollback is reported with the artifact, from→to and the per-host evidence.
    @Test
    void committedRollback_reportsArtifactVersionsAndEvidence() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        var evidence = Map.of(NodeId.nodeId("node-2").unwrap(), 4L, NodeId.nodeId("node-3").unwrap(), 5L);
        var trigger = SliceFailureEvent.AllInstancesFailed.allInstancesFailed("req-1",
                                                                              Artifact.artifact(BASE, V2),
                                                                              MethodName.methodName("doSomething").unwrap(),
                                                                              Option.none(),
                                                                              List.copyOf(evidence.keySet()),
                                                                              evidence,
                                                                              30_000L);

        RollbackManager.rollbackManager(SELF, () -> CONFIG, clusterNode, kvStore, new AlwaysLeaderManager(SELF), reported::add)
                       .onAllInstancesFailed(trigger);

        assertThat(reported).hasSize(1);
        var executed = reported.getFirst();

        assertThat(executed.failedArtifact()).isEqualTo(Artifact.artifact(BASE, V2));
        assertThat(executed.targetVersion()).isEqualTo(V1);
        assertThat(executed.defectsPerHost()).isEqualTo(evidence);
        assertThat(executed.windowMs()).isEqualTo(30_000L);
    }

    private RollbackManager manager() {
        return RollbackManager.rollbackManager(SELF, CONFIG, clusterNode, kvStore, new AlwaysLeaderManager(SELF));
    }

    private AetherValue value(int index) {
        return (AetherValue) ((KVCommand.Put<?, ?>) clusterNode.appliedCommands.get(index)).value();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static PreviousVersionValue record(Version previous,
                                               Version current,
                                               long updatedAt,
                                               int rollbackCount,
                                               long lastRollbackAt,
                                               List<Version> failed) {
        return new PreviousVersionValue(BASE, previous, current, updatedAt, rollbackCount, lastRollbackAt, failed);
    }

    private static DeploymentValue deployment(String state) {
        return DeploymentValue.deploymentValue("dep-1",
                                               "bp-1",
                                               V1.withQualifier(),
                                               V2.withQualifier(),
                                               "CANARY",
                                               state,
                                               "",
                                               "",
                                               "",
                                               "IMMEDIATE",
                                               BASE.asString(),
                                               1,
                                               now(),
                                               now());
    }

    private static SliceFailureEvent.AllInstancesFailed failure(Version version) {
        return SliceFailureEvent.AllInstancesFailed.allInstancesFailed("req-1",
                                                                       Artifact.artifact(BASE, version),
                                                                       MethodName.methodName("doSomething").unwrap(),
                                                                       Option.some(Causes.cause("all instances failed")),
                                                                       List.of(NodeId.nodeId("node-2").unwrap()));
    }

    private void seed(AetherKey key, AetherValue value) {
        kvStore.process(kvStore.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(key,
                                                                                                                    value))));
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
