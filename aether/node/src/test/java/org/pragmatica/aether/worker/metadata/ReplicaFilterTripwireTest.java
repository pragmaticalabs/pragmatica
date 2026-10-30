// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.ReplicaFilter;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// Worker-replica tripwires TW-O1, TW-O2 and TW-O15 of the worker-replica-feed design (W-OBS). Each is an ENABLED
/// test of today's behaviour of the worker metadata projection (`WorkerMetadataIndex`, the only per-worker view of
/// the KV at this commit), with the real assertion of the target design `@Disabled` beside it.
///
/// The target is a worker holding a full KV replica minus an exclusion set: `ReplicaFilter` classifies every
/// `AetherKey` class INCLUDE or EXCLUDE, and the initial EXCLUDE set is the stream cursor checkpoints and the
/// cluster configuration. The filter exists (W1) and TW-O15 pins it; the worker replica does not exist yet, so the
/// real assertions of TW-O1/O2 are written against the projection, the only per-worker view: "the worker's view holds
/// the key" stands in for "the replica holds the key". The PR that lands the worker replica (W3/W4) enables them and
/// re-points them at the replica; the enabled halves are deleted then, because they pin behaviour that is meant to change.
class ReplicaFilterTripwireTest {
    private static final NodeId WORKER = new NodeId("worker");
    private static final NodeId CORE = new NodeId("core");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final ArtifactBase ARTIFACT_BASE = ArtifactBase.artifactBase("org.example:orders").unwrap();
    private static final BlueprintId BLUEPRINT = BlueprintId.blueprintId("org.example:blueprint:1.0.0").unwrap();
    private static final String OPAQUE = "opaque value: the unscoped arms of the projection never read it";

    /// The 20 `AetherKey` classes the projection gives NO scope (`WorkerMetadataIndex.scopesFor`, the arm that
    /// returns `Set.of()`), keyed by class name. `ClusterConfigKey` is the one the design keeps EXCLUDED.
    private static Map<String, StructuredKey> unscopedKeys() {
        var keys = new LinkedHashMap<String, StructuredKey>();

        keys.put("DeploymentOutcomeKey", new AetherKey.DeploymentOutcomeKey(BLUEPRINT));
        keys.put("ArtifactVersionsKey", new AetherKey.ArtifactVersionsKey(ARTIFACT_BASE));
        keys.put("ArtifactContentKey", new AetherKey.ArtifactContentKey(ARTIFACT_BASE, "1.0.0", "slice.jar"));
        keys.put("DeploymentKey", new AetherKey.DeploymentKey("deployment-1"));
        keys.put("AbTestKey", new AetherKey.AbTestKey("test-1"));
        keys.put("ApiKeyAuditKey", new AetherKey.ApiKeyAuditKey("entry-1"));
        keys.put("ClusterConfigKey", AetherKey.ClusterConfigKey.CURRENT);
        keys.put("ClusterIncarnationKey", new AetherKey.ClusterIncarnationKey());
        keys.put("DhtReplicationChangeKey", new AetherKey.DhtReplicationChangeKey());
        keys.put("DhtReplicationReportKey", new AetherKey.DhtReplicationReportKey(CORE));
        keys.put("StreamPartitionRecoveryKey", new AetherKey.StreamPartitionRecoveryKey("orders", 0));
        keys.put("CapacityLedgerKey", new AetherKey.CapacityLedgerKey());
        keys.put("CapacityReservationKey", new AetherKey.CapacityReservationKey(CORE));
        keys.put("NodeReplacementKey", new AetherKey.NodeReplacementKey(CORE));
        keys.put("UpgradeRunKey", new AetherKey.UpgradeRunKey());
        keys.put("UnconfirmedTerminationKey", new AetherKey.UnconfirmedTerminationKey(CORE));
        keys.put("CapacityAdmissionKey", new AetherKey.CapacityAdmissionKey(CORE));
        keys.put("CommunityPlacementAvailabilityKey", new AetherKey.CommunityPlacementAvailabilityKey("community-1", "source-1", Option.none()));
        keys.put("ProvisioningSlotKey", new AetherKey.ProvisioningSlotKey("slot-1"));
        keys.put("AutoHealStateKey", new AetherKey.AutoHealStateKey());

        return keys;
    }

    /// Every key and value the worker's projection would carry: the union of the scopes a worker selects.
    private static List<StructuredKey> workerView(WorkerMetadataIndex index) {
        return index.scopesForWorker(WORKER)
                    .stream()
                    .flatMap(scope -> index.snapshot(scope)
                                           .keySet()
                                           .stream())
                    .toList();
    }

    @Test
    void unscopedKeyClasses_enterNoWorkerScope_today() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var keys = unscopedKeys();

        // Positive control: the instrument CAN see a scoped key. A scoped put raises the index revision and the key
        // lands in the worker's view; without this, "revision did not move" would also hold for a dead index.
        var control = new AetherKey.LogLevelKey("org.example");

        index.put(control, OPAQUE);
        assertThat(index.revision()).as("control: a scoped key moves the revision").isPositive();
        assertThat(workerView(index)).as("control: a scoped key reaches the worker's view").contains(control);

        var before = index.revision();

        keys.forEach((name, key) -> index.put(key, OPAQUE));

        assertThat(keys).as("the 20 key classes the projection leaves unscoped (TW-O1)").hasSize(20);
        assertThat(index.revision()).as("TW-O1: an unscoped key moves no scope revision, so none of the 20 is in any scope").isEqualTo(before);
        assertThat(workerView(index)).as("TW-O1: none of the 20 reaches a worker today; W1 replaces this with the ReplicaFilter assertion (enable replicaHoldsEveryKeyOutsideTheFilter)")
                                     .doesNotContainAnyElementsOf(keys.values());
    }

    @Disabled("enable at W3/W4 (worker replica): when the worker holds a full replica minus the exclusion set, re-point workerView at the filtered replica")
    @Test
    void replicaHoldsEveryKeyOutsideTheFilter_andNoneInsideIt() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var keys = unscopedKeys();

        keys.forEach((name, key) -> index.put(key, OPAQUE));

        var view = workerView(index);
        var excluded = Map.of("ClusterConfigKey", keys.get("ClusterConfigKey"));
        var included = keys.entrySet()
                           .stream()
                           .filter(entry -> !excluded.containsKey(entry.getKey()))
                           .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(view).as("every key class outside ReplicaFilter's EXCLUDE set is in the replica: %s", included.keySet())
                        .containsAll(included.values());
        assertThat(view).as("ClusterConfigKey (may carry credentials in its TOML) is excluded by key")
                        .doesNotContainAnyElementsOf(excluded.values());
    }

    private static List<StructuredKey> streamScopedWorkerView(WorkerMetadataIndex index) {
        var method = MethodName.methodName("onOrder").unwrap();

        index.put(new AetherKey.NodeArtifactKey(WORKER, ARTIFACT), OPAQUE);
        index.put(new AetherKey.StreamRegistrationKey("orders", "orders-config", ARTIFACT, method), OPAQUE);
        index.put(new AetherKey.StreamCursorCheckpointKey("orders", 0, "billing"), OPAQUE);

        return workerView(index);
    }

    @Test
    void streamCursorCheckpoint_reachesAWorkerThroughItsStreamScope_today() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var view = streamScopedWorkerView(index);

        assertThat(view).as("control: the stream registration reaches the worker through its artifact")
                        .anyMatch(key -> key instanceof AetherKey.StreamRegistrationKey);
        assertThat(view).as("TW-O2: the cursor checkpoint reaches a worker through stream:orders, so an exclusion has to be introduced, it is not the default")
                        .anyMatch(key -> key instanceof AetherKey.StreamCursorCheckpointKey);
    }

    @Disabled("enable at W3/W4 (worker replica): when the worker's view is the filtered replica, which leaves out StreamCursorCheckpointKey")
    @Test
    void replicaHoldsNoStreamCursorCheckpoint() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var view = streamScopedWorkerView(index);

        assertThat(view).as("cursor checkpoints are excluded from the worker replica (owner ruling): declarative consumers are core-only, programmatic ones read through forwarded batches")
                        .noneMatch(key -> key instanceof AetherKey.StreamCursorCheckpointKey);
        assertThat(view).as("the consumer assignment and the registration stay in the replica")
                        .anyMatch(key -> key instanceof AetherKey.StreamRegistrationKey);
    }

    /// TW-O15, flipped by W1. The filter is an exhaustive `switch` over the sealed `AetherKey` with no default, so a new key class does not
    /// compile until it is classified: that property is the compiler's, and a reflection test cannot add to it. What the test pins is the
    /// classification itself, on the classes the worker projection never saw (the 20 that had no scope) plus the excluded ones, and the
    /// fail-closed rule for a key type the filter does not know.
    @Test
    void theFilterClassifiesTheKeyClasses_andFailsClosedOnAnUnknownKeyType() {
        var filter = ReplicaFilter.replicaFilter();
        var excluded = unscopedKeys().entrySet().stream().filter(entry -> filter.excludes(entry.getValue())).map(Map.Entry::getKey).toList();

        assertThat(excluded).as("of the 20 classes the projection left unscoped only the cluster configuration is EXCLUDED (credentials in its TOML)").containsExactly("ClusterConfigKey");
        assertThat(filter.excludes(new AetherKey.StreamCursorCheckpointKey("orders", 0, "billing"))).as("cursor checkpoints are EXCLUDED").isTrue();
        assertThat(filter.excludes(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE)).as("the leader key is INCLUDED").isFalse();

        var before = filter.foreignKeyExclusions();

        assertThat(filter.excludes(new StructuredKey() {})).as("a key family the filter does not know is EXCLUDED").isTrue();
        assertThat(filter.foreignKeyExclusions()).as("and counted").isEqualTo(before + 1);
    }

    /// Every concrete `AetherKey` class, found by walking the sealed hierarchy from `AetherKey.getPermittedSubclasses()`.
    private static List<Class<?>> everyAetherKeyClass() {
        var classes = new java.util.ArrayList<Class<?>>();

        collectConcrete(AetherKey.class, classes);

        return classes;
    }

    private static void collectConcrete(Class<?> type, List<Class<?>> classes) {
        if (!type.isSealed()) {
            classes.add(type);

            return;
        }

        for (var permitted : type.getPermittedSubclasses()) {
            collectConcrete(permitted, classes);
        }
    }

    /// An instance of a key class with no constructor run: the filter's switch reads only the runtime class, so the components do not matter.
    private static AetherKey instanceOf(Class<?> keyClass) {
        try {
            var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");

            field.setAccessible(true);
            var unsafe = field.get(null);

            return (AetherKey) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, keyClass);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot instantiate " + keyClass.getName(), e);
        }
    }

    /// TW-O15, the full classification: every `AetherKey` class in the sealed hierarchy, not a sample, is classified, and the EXCLUDE set is
    /// exactly the stream cursor checkpoints, the cluster configuration and the five deployment-rewrite keys (EXCLUDED until P3-D). Mutation: move any other class (say `SliceTargetKey`) to EXCLUDE,
    /// and the set has a third member.
    @Test
    void everyAetherKeyClass_isIncluded_exceptTheCheckpointsTheConfigurationAndTheDeploymentRewriteKeys() {
        var classes = everyAetherKeyClass();
        var excluded = classes.stream()
                              .filter(keyClass -> ReplicaFilter.replicaFilter().excludes(instanceOf(keyClass)))
                              .map(Class::getSimpleName)
                              .collect(Collectors.toSet());

        assertThat(classes).as("control: the walk reaches the whole hierarchy, the 20 classes of TW-O1 among them")
                           .hasSizeGreaterThan(60)
                           .extracting(Class::getSimpleName)
                           .containsAll(unscopedKeys().keySet());
        assertThat(excluded).as("the EXCLUDE set, over every AetherKey class").containsExactlyInAnyOrder("StreamCursorCheckpointKey", "ClusterConfigKey", "SliceDesiredKey", "SliceObservedKey", "DeploymentApplyKey", "ArtifactJudgmentKey", "NodeDepartureKey", "NodeIdentityKey");
    }

    /// The ring's byte measure sizes a key by `asString()` (R-07). `StructuredKey` gives a default for key families with no canonical text;
    /// `AetherKey` re-declares it abstract, so every `AetherKey` class implements its own and none reaches that default.
    @Test
    void everyAetherKeyClass_declaresItsOwnAsString() {
        var inheriting = everyAetherKeyClass().stream()
                                              .filter(keyClass -> !declaresAsString(keyClass))
                                              .map(Class::getSimpleName)
                                              .toList();

        assertThat(inheriting).as("AetherKey classes without their own asString()").isEmpty();
    }

    private static boolean declaresAsString(Class<?> keyClass) {
        try {
            return !java.lang.reflect.Modifier.isAbstract(keyClass.getDeclaredMethod("asString").getModifiers());
        } catch (NoSuchMethodException _) {
            return false;
        }
    }
}
