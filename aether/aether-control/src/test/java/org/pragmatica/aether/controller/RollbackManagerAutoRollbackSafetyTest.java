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
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.controller.RollbackManagerOverridePreservationTest.AlwaysLeaderManager;
import org.pragmatica.aether.controller.RollbackManagerGuardedRecordTest.HeldClusterNode;
import org.pragmatica.aether.controller.RollbackManagerOverridePreservationTest.CapturingClusterNode;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.blueprint.SliceSpec;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/// #1573 rollback safety, one property per test. Each negative case is armed by the positive
/// `insideBakeWindow_rollsBack_recordFirstInOneBatch`: the same fixture rolls back unless the one
/// condition under test holds.
@Timeout(value = 60, unit = SECONDS)
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
        RollbackManagerOverridePreservationTest.seedCommittedLeader(kvStore, SELF);
    }

    /// v1608 N1: the batch boundary itself is asserted — ONE applied command, a leader transaction whose
    /// mutations are the record and then the target. A capture that flattened batches could not see a split.
    @Test
    void insideBakeWindow_rollsBack_recordFirstInOneBatch() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).as("record and target commit in ONE command").hasSize(1);
        assertThat(clusterNode.appliedCommands.getFirst()).isInstanceOf(KVCommand.LeaderTransaction.class);
        List<Object> keys = ((KVCommand.LeaderTransaction<?, ?>) clusterNode.appliedCommands.getFirst()).mutations()
                                                                                                   .stream()
                                                                                                   .<Object>map(KVCommand.Mutation::key)
                                                                                                   .toList();

        assertThat(keys).containsExactly(PreviousVersionKey.previousVersionKey(BASE), SliceTargetKey.sliceTargetKey(BASE));
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

        assertThat(clusterNode.writtenValues()).hasSize(2);
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
                                                      reported::add, OperatorWarningSink.logOnly());

        manager.onAllInstancesFailed(failure(V2));
        assertThat(clusterNode.appliedCommands).as("disabled by the committed policy").isEmpty();

        policy.set(CONFIG);
        manager.onAllInstancesFailed(failure(V2));
        assertThat(clusterNode.writtenValues()).as("re-enabled without a restart").hasSize(2);
    }

    /// v1608 N1 (M14 left 0 red): `enabled = false` ALONE refuses. The trigger stays on and every other
    /// condition is the positive case's, so only the `enabled` check stands between this and a rollback.
    @Test
    void enabledFalse_withTheTriggerOn_neverRollsBack() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        var disabled = RollbackConfig.rollbackConfig(false,
                                                     true,
                                                     TimeSpan.timeSpan(5).minutes(),
                                                     2,
                                                     TimeSpan.timeSpan(15).minutes())
                                     .unwrap();

        RollbackManager.rollbackManager(SELF, () -> disabled, clusterNode, kvStore, new AlwaysLeaderManager(SELF), reported::add, OperatorWarningSink.logOnly())
                       .onAllInstancesFailed(failure(V2));

        assertThat(disabled.triggerOnAllInstancesFailed()).as("arming: the trigger is on").isTrue();
        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    /// #1573 N2: an operator disable committed between the decision and its write refuses the write — the
    /// rollback is fenced on the cluster config it was decided from.
    @Test
    void disableCommittedBetweenDecisionAndWrite_refusesTheRollback() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        seed(ClusterConfigKey.CURRENT, clusterConfig("[rollback]\nenabled = true\n", 1));
        var racing = new CapturingClusterNode(SELF,
                                              kvStore,
                                              () -> seed(ClusterConfigKey.CURRENT, clusterConfig("[rollback]\nenabled = false\n", 2)));

        RollbackManager.rollbackManager(SELF, () -> CONFIG, racing, kvStore, new AlwaysLeaderManager(SELF), reported::add, OperatorWarningSink.logOnly())
                       .onAllInstancesFailed(failure(V2));

        assertThat(racing.appliedCommands).as("arming: the rollback was decided and submitted").hasSize(1);
        assertThat(currentTargetVersion()).as("the disable won the race: the target never moved").isEqualTo(V2);
        assertThat(reported).as("a refused rollback is never reported as executed").isEmpty();
    }

    /// #1573 N2: a newer target committed between the decision and its write (an autoscaler or operator
    /// write) is never rolled back.
    @Test
    void newerTargetCommittedBetweenDecisionAndWrite_isNeverRolledBack() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        var racing = new CapturingClusterNode(SELF,
                                              kvStore,
                                              () -> seed(SliceTargetKey.sliceTargetKey(BASE), SliceTargetValue.sliceTargetValue(V3, 3)));

        RollbackManager.rollbackManager(SELF, () -> CONFIG, racing, kvStore, new AlwaysLeaderManager(SELF), reported::add, OperatorWarningSink.logOnly())
                       .onAllInstancesFailed(failure(V2));

        assertThat(racing.appliedCommands).as("arming: the rollback was decided and submitted").hasSize(1);
        assertThat(currentTargetVersion()).as("the newer target stands").isEqualTo(V3);
        assertThat(reported).isEmpty();
    }

    /// v1608 R05: the target already moved on BEFORE the commit read it (the manager's tracked state still
    /// names the failed version). The commit's own pre-check refuses; without it the transaction would carry
    /// the NEW target as its expected value, the store would accept it, and a newer target would be rolled back.
    @Test
    void targetMovedOnBeforeTheCommitReadsIt_isNeverRolledBack() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        var direct = new CapturingClusterNode(SELF, kvStore, () -> {});
        var manager = RollbackManager.rollbackManager(SELF, () -> CONFIG, direct, kvStore, new AlwaysLeaderManager(SELF), reported::add, OperatorWarningSink.logOnly());

        seed(SliceTargetKey.sliceTargetKey(BASE), SliceTargetValue.sliceTargetValue(V3, 3));
        manager.onAllInstancesFailed(failure(V2));

        assertThat(direct.appliedCommands).as("nothing is submitted against a target that already names V3").isEmpty();
        assertThat(currentTargetVersion()).isEqualTo(V3);
    }

    /// v1608 R06: a node the local leader manager still calls leader, but that is NOT the committed leader,
    /// never commits a rollback.
    @Test
    void notTheCommittedLeader_neverRollsBack() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        RollbackManagerOverridePreservationTest.seedCommittedLeader(kvStore, NodeId.nodeId("node-9").unwrap(), 2);

        assertThat(kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class).map(LeaderValue::leader))
            .as("arming: the committed leader is another node")
            .isEqualTo(Option.some(NodeId.nodeId("node-9").unwrap()));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    /// #1495 (owner ruling: the instance floor holds at runtime): a rollback that finds no committed target
    /// writes a fresh one at the floor, never at a single instance.
    @Test
    void rollbackWithNoCommittedTarget_writesAFreshTargetAtTheInstanceFloor() {
        kvStore.process(kvStore.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Remove<>(SliceTargetKey.sliceTargetKey(BASE)))));
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));

        manager().onAllInstancesFailed(failure(V2));

        assertThat(clusterNode.appliedCommands).as("the rollback must have committed, or the floor below is vacuous")
                                               .hasSize(1);
        var target = (SliceTargetValue) value(1);

        assertThat(target.currentVersion()).isEqualTo(V1);
        assertThat(target.targetInstances()).isEqualTo(SliceSpec.MIN_INSTANCES);
    }

    /// The store-backed control for the two races above: with no interleaved write the same fixture commits.
    @Test
    void noInterleavedWrite_storeBackedRollbackCommits() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        seed(ClusterConfigKey.CURRENT, clusterConfig("[rollback]\nenabled = true\n", 1));
        var direct = new CapturingClusterNode(SELF, kvStore, () -> {});

        RollbackManager.rollbackManager(SELF, () -> CONFIG, direct, kvStore, new AlwaysLeaderManager(SELF), reported::add, OperatorWarningSink.logOnly())
                       .onAllInstancesFailed(failure(V2));

        assertThat(currentTargetVersion()).isEqualTo(V1);
        assertThat(reported).hasSize(1);
    }

    private Version currentTargetVersion() {
        return kvStore.getTyped(SliceTargetKey.sliceTargetKey(BASE), SliceTargetValue.class)
                      .map(SliceTargetValue::currentVersion)
                      .unwrap();
    }

    private static ClusterConfigValue clusterConfig(String toml, long configVersion) {
        return new ClusterConfigValue(Option.some(toml), "test", "1.0.0", List.of(), 3, 3, "forge", configVersion, configVersion);
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

        RollbackManager.rollbackManager(SELF, () -> CONFIG, clusterNode, kvStore, new AlwaysLeaderManager(SELF), reported::add, OperatorWarningSink.logOnly())
                       .onAllInstancesFailed(trigger);

        assertThat(reported).hasSize(1);
        var executed = reported.getFirst();

        assertThat(executed.failedArtifact()).isEqualTo(Artifact.artifact(BASE, V2));
        assertThat(executed.targetVersion()).isEqualTo(V1);
        assertThat(executed.defectsPerHost()).isEqualTo(evidence);
        assertThat(executed.windowMs()).isEqualTo(30_000L);
    }

    /// D1-react (the mirror holds only committed values): the rollback's continuation runs after a later reset has committed and
    /// been delivered. The mirror's counters come only from delivered records, so the late continuation cannot write the
    /// rollback's older count over the reset; it records only the in-memory last-rollback details.
    @Test
    void lateRollbackContinuation_keepsTheLaterCommittedCount() {
        seed(PreviousVersionKey.previousVersionKey(BASE), record(V1, V2, now(), 0, 0, List.of()));
        var held = new HeldClusterNode(SELF, kvStore);
        var manager = RollbackManager.rollbackManager(SELF, CONFIG, held, kvStore, new AlwaysLeaderManager(SELF));

        manager.onAllInstancesFailed(failure(V2));
        assertThat(held.captured()).as("arming: the rollback is submitted and held").hasSize(1);
        var rolledBack = (PreviousVersionValue) ((KVCommand.LeaderTransaction<?, ?>) held.captured()
                                                                                          .getFirst()
                                                                                          .commands()
                                                                                          .getFirst()).mutations()
                                                                                                      .getFirst()
                                                                                                      .replacement()
                                                                                                      .unwrap();
        deliver(manager, rolledBack);
        deliver(manager, rolledBack.withRollbackCountReset());
        held.applyToStore(0);
        awaitLastRolledBackFrom(manager);

        assertThat(manager.getStats(BASE).flatMap(RollbackManager.RollbackStats::lastRolledBackFrom))
            .as("the continuation ran: the last-rollback details are recorded")
            .isEqualTo(Option.some(V2));
        assertThat(manager.getStats(BASE).map(RollbackManager.RollbackStats::rollbackCount))
            .as("the later committed reset survives the late continuation")
            .isEqualTo(Option.some(0));
    }

    private static void awaitLastRolledBackFrom(RollbackManager manager) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (manager.getStats(BASE).flatMap(RollbackManager.RollbackStats::lastRolledBackFrom).isEmpty()
               && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void deliver(RollbackManager manager, PreviousVersionValue record) {
        var key = PreviousVersionKey.previousVersionKey(record.artifactBase());

        manager.onPreviousVersionPut(new ValuePut<>(new KVCommand.Put<>(key, record), Option.none()));
    }

    private RollbackManager manager() {
        return RollbackManager.rollbackManager(SELF, CONFIG, clusterNode, kvStore, new AlwaysLeaderManager(SELF));
    }

    private AetherValue value(int index) {
        return clusterNode.writtenValues()
                          .get(index);
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
