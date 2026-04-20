// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.generation.PartitionOwner;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Wires the leader-only `HealthReconciler` into the message bus for Commit 3 activation.
///
/// Subscribes to:
///   - `LeaderChange` — toggles the `isLeader` gate and starts/stops the reconciler
///   - `GovernorAnnouncementKey` PUT — emits `GovernorAnnounced` or
///     `CommunityDissolved` depending on the dissolved flag
///   - `GovernorAnnouncementKey` REMOVE — emits `CommunityDissolved`
///   - `SpokesmanKey` PUT with `status == FAILED` — emits `SpokesmanAssignmentFailed`
///   - `NodeLifecycleKey` PUT with terminal state (DECOMMISSIONED) — emits an
///     internal re-project signal if the node was core (falls through
///     `HealthReconciler.handlePingTimeout`/partition transfer paths via
///     existing decision table)
///
/// In Commit 3 the activator writes only NEW atoms (`SpokesmanKey`,
/// `DhtPartitionOwnershipKey`) indirectly via the reconciler's decision table.
/// `NodeLifecycleKey = LEFT` writes for health-detected failures are NOT
/// issued here — deferred to Commit 4/5.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §8.
public interface HealthReconcilerActivator {
    String CORE_PARTITION_ID = "core";

    String CORE_COMMUNITY_ID = "core";

    int BOOTSTRAP_MAX_ATTEMPTS = 3;

    @Contract void onLeaderChange(LeaderChange change);
    @Contract void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> notification);
    @Contract void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> notification);
    @Contract void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification);
    @Contract void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> notification);
    @Contract void onClusterConfigPut(ValuePut<ClusterConfigKey, AetherValue.ClusterConfigValue> notification);
    HealthSignalSink sink();

    static Result<HealthReconcilerActivator> healthReconcilerActivator(HealthReconciler reconciler,
                                                                       AtomicBoolean isLeaderGate) {
        return HlcClock.hlcClock("activator-default")
                                .map(clock -> new HealthReconcilerActivatorRecord(reconciler,
                                                                                  isLeaderGate,
                                                                                  ClusterGenerationProjector.clusterGenerationProjector(),
                                                                                  Map::of,
                                                                                  () -> 0L,
                                                                                  clock,
                                                                                  Option.<ClusterNode<KVCommand<AetherKey>>>none(),
                                                                                  reconciler::self,
                                                                                  () -> 0,
                                                                                  new AtomicBoolean(false),
                                                                                  new AtomicInteger()));
    }

    static HealthReconcilerActivator healthReconcilerActivator(HealthReconciler reconciler,
                                                               AtomicBoolean isLeaderGate,
                                                               ClusterGenerationProjector projector,
                                                               Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                               Supplier<Long> rabiaTermSupplier,
                                                               HlcClock hlcClock) {
        return new HealthReconcilerActivatorRecord(reconciler,
                                                   isLeaderGate,
                                                   projector,
                                                   kvSnapshotSupplier,
                                                   rabiaTermSupplier,
                                                   hlcClock,
                                                   Option.<ClusterNode<KVCommand<AetherKey>>>none(),
                                                   reconciler::self,
                                                   () -> 0,
                                                   new AtomicBoolean(false),
                                                   new AtomicInteger());
    }

    static HealthReconcilerActivator healthReconcilerActivator(HealthReconciler reconciler,
                                                               AtomicBoolean isLeaderGate,
                                                               ClusterGenerationProjector projector,
                                                               Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                               Supplier<Long> rabiaTermSupplier,
                                                               HlcClock hlcClock,
                                                               ClusterNode<KVCommand<AetherKey>> cluster,
                                                               Supplier<NodeId> selfSupplier) {
        return new HealthReconcilerActivatorRecord(reconciler,
                                                   isLeaderGate,
                                                   projector,
                                                   kvSnapshotSupplier,
                                                   rabiaTermSupplier,
                                                   hlcClock,
                                                   Option.some(cluster),
                                                   selfSupplier,
                                                   () -> 0,
                                                   new AtomicBoolean(false),
                                                   new AtomicInteger());
    }

    static HealthReconcilerActivator healthReconcilerActivator(HealthReconciler reconciler,
                                                               AtomicBoolean isLeaderGate,
                                                               ClusterGenerationProjector projector,
                                                               Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                               Supplier<Long> rabiaTermSupplier,
                                                               HlcClock hlcClock,
                                                               ClusterNode<KVCommand<AetherKey>> cluster,
                                                               Supplier<NodeId> selfSupplier,
                                                               Supplier<Integer> initialCoreSizeSupplier) {
        return new HealthReconcilerActivatorRecord(reconciler,
                                                   isLeaderGate,
                                                   projector,
                                                   kvSnapshotSupplier,
                                                   rabiaTermSupplier,
                                                   hlcClock,
                                                   Option.some(cluster),
                                                   selfSupplier,
                                                   initialCoreSizeSupplier,
                                                   new AtomicBoolean(false),
                                                   new AtomicInteger());
    }
}

record HealthReconcilerActivatorRecord(HealthReconciler reconciler,
                                       AtomicBoolean isLeaderGate,
                                       ClusterGenerationProjector projector,
                                       Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                       Supplier<Long> rabiaTermSupplier,
                                       HlcClock hlcClock,
                                       Option<ClusterNode<KVCommand<AetherKey>>> cluster,
                                       Supplier<NodeId> selfSupplier,
                                       Supplier<Integer> initialCoreSizeSupplier,
                                       AtomicBoolean bootstrapComplete,
                                       AtomicInteger bootstrapAttempts) implements HealthReconcilerActivator {
    private static final Logger log = LoggerFactory.getLogger(HealthReconcilerActivatorRecord.class);

    @Contract@Override public void onLeaderChange(LeaderChange change) {
        if (change.localNodeIsLeader()) {
            log.info("HealthReconciler becoming leader — projecting from committed atoms, then starting reconciler");
            reconciler.stop(StopReason.LEADER_LOST);
            isLeaderGate.set(true);
            bootstrapComplete.set(false);
            bootstrapAttempts.set(0);
            var seeded = projectFromCommittedAtoms();
            reconciler.seedSnapshot(seeded);
            reconciler.start(Epoch.epoch(rabiaTermSupplier.get(), 0L));
            attemptBootstrap(seeded);
            seedClusterConfigIfMissing();
        } else {
            log.info("HealthReconciler stepping down — stopping reconciler");
            isLeaderGate.set(false);
            reconciler.stop(StopReason.LEADER_LOST);
            reconciler.seedSnapshot(ClusterGenerationSnapshot.empty(reconciler.currentEpoch().rabiaTerm()));
            bootstrapComplete.set(false);
            bootstrapAttempts.set(0);
        }
    }

    @Contract private void attemptBootstrap(ClusterGenerationSnapshot seeded) {
        if (bootstrapComplete.get()) {return;}
        cluster.onPresent(clusterNode -> applyCoreBootstrap(clusterNode, seeded));
    }

    @Contract private void seedClusterConfigIfMissing() {
        var existing = kvSnapshotSupplier.get().get(ClusterConfigKey.CURRENT);
        if (existing instanceof ClusterConfigValue) {return;}
        var initialSize = initialCoreSizeSupplier.get();
        if (initialSize <3) {
            log.debug("Skipping ClusterConfigValue seed: initial core size {} below quorum minimum", initialSize);
            return;
        }
        cluster.onPresent(clusterNode -> writeClusterConfigSeed(clusterNode, initialSize));
    }

    @Contract private void writeClusterConfigSeed(ClusterNode<KVCommand<AetherKey>> clusterNode, int initialSize) {
        var coreMax = Math.max(initialSize, SEED_CORE_MAX);
        if (coreMax % 2 == 0) {coreMax += 1;}
        var seed = ClusterConfigValue.clusterConfigValue("",
                                                         "",
                                                         "1.0.0",
                                                         initialSize,
                                                         SEED_CORE_MIN,
                                                         coreMax,
                                                         "bootstrap-seed",
                                                         1L);
        KVCommand<AetherKey> command = new KVCommand.Put<AetherKey, AetherValue>(ClusterConfigKey.CURRENT, seed);
        log.info("Seeding ClusterConfigValue with coreCount={}, coreMin={}, coreMax={}",
                 initialSize,
                 SEED_CORE_MIN,
                 coreMax);
        clusterNode.apply(List.of(command))
                         .onFailure(cause -> log.warn("ClusterConfigValue seed failed: {}",
                                                      cause.message()));
    }

    private static final int SEED_CORE_MIN = 3;

    private static final int SEED_CORE_MAX = 15;

    @Contract private void retryBootstrapIfNeeded() {
        if (bootstrapComplete.get() || !isLeaderGate.get()) {return;}
        if (bootstrapAttempts.get() >= HealthReconcilerActivator.BOOTSTRAP_MAX_ATTEMPTS) {return;}
        attemptBootstrap(reconciler.currentSnapshot());
    }

    @Contract private void applyCoreBootstrap(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                              ClusterGenerationSnapshot seeded) {
        var self = selfSupplier.get();
        var existing = Option.option(seeded.partitions().get(CORE_PARTITION_ID));
        var decision = decideCoreOwnership(existing, seeded, self);
        decision.onPresent(command -> writeCoreBootstrap(clusterNode, command, existing, self));
    }

    private Option<KVCommand<AetherKey>> decideCoreOwnership(Option<PartitionOwner> existing,
                                                             ClusterGenerationSnapshot seeded,
                                                             NodeId self) {
        return existing.fold(() -> Option.some(buildInitialCorePartition(seeded, self)),
                             owner -> rewriteIfOwnerStale(owner, seeded, self));
    }

    private Option<KVCommand<AetherKey>> rewriteIfOwnerStale(PartitionOwner owner,
                                                             ClusterGenerationSnapshot seeded,
                                                             NodeId self) {
        return shouldRewriteCoreOwnership(owner, seeded, self)
              ? Option.some(buildRewrittenCorePartition(owner, seeded, self))
              : Option.none();
    }

    private static boolean shouldRewriteCoreOwnership(PartitionOwner owner,
                                                      ClusterGenerationSnapshot seeded,
                                                      NodeId self) {
        var recordedOwner = owner.ownerNodeId();
        if (recordedOwner.equals(self)) {return false;}
        return Option.option(seeded.coreMembers().get(recordedOwner)).map(member -> isStaleOwnerState(member.lifecycle()))
                            .or(true);
    }

    private static boolean isStaleOwnerState(NodeLifecycleState state) {
        return state == NodeLifecycleState.DECOMMISSIONED || state == NodeLifecycleState.SHUTTING_DOWN || state == NodeLifecycleState.DRAINING;
    }

    private KVCommand<AetherKey> buildInitialCorePartition(ClusterGenerationSnapshot seeded, NodeId self) {
        return buildCorePartitionCommand(self, seeded.epoch(), 1L);
    }

    private KVCommand<AetherKey> buildRewrittenCorePartition(PartitionOwner owner,
                                                             ClusterGenerationSnapshot seeded,
                                                             NodeId self) {
        return buildCorePartitionCommand(self, seeded.epoch(), owner.ownershipTerm() + 1L);
    }

    private KVCommand<AetherKey> buildCorePartitionCommand(NodeId owner, Epoch epoch, long ownershipTerm) {
        var value = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(owner,
                                                                          CORE_COMMUNITY_ID,
                                                                          epoch,
                                                                          ownershipTerm,
                                                                          hlcClock.now());
        return new KVCommand.Put<AetherKey, AetherValue>(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(CORE_PARTITION_ID),
                                                         value);
    }

    @Contract private void writeCoreBootstrap(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                              KVCommand<AetherKey> command,
                                              Option<PartitionOwner> existing,
                                              NodeId self) {
        existing.onPresent(owner -> log.info("Rewriting DhtPartitionOwnershipKey(\"core\"): previous owner {} is not a live core member — {} takes over (ownershipTerm {})",
                                             owner.ownerNodeId(),
                                             self,
                                             owner.ownershipTerm() + 1L))
        .onEmpty(() -> log.info("Bootstrapping DhtPartitionOwnershipKey(\"core\") with owner {} (ownershipTerm 1)",
                                self));
        bootstrapAttempts.incrementAndGet();
        clusterNode.apply(List.of(command)).onFailure(cause -> log.warn("Core DhtPartitionOwnership bootstrap failed (attempt {}/{}): {}",
                                                                        bootstrapAttempts.get(),
                                                                        HealthReconcilerActivator.BOOTSTRAP_MAX_ATTEMPTS,
                                                                        cause.message()))
                         .onSuccess(_ -> bootstrapComplete.set(true));
    }

    private ClusterGenerationSnapshot projectFromCommittedAtoms() {
        var kv = kvSnapshotSupplier.get();
        var lifecycles = collectLifecycles(kv);
        var governors = collectGovernors(kv);
        var partitions = collectPartitions(kv);
        var spokesmen = collectSpokesmen(kv);
        var nodesWithArtifacts = collectNodesWithArtifacts(kv);
        var term = rabiaTermSupplier.get();
        var desiredCoreSize = collectDesiredCoreSize(kv).or(lifecycles.size());
        var input = ClusterGenerationProjector.ProjectionInput.projectionInput(term,
                                                                               0L,
                                                                               desiredCoreSize,
                                                                               GenerationReason.LEADER_ELECTED,
                                                                               hlcClock.now(),
                                                                               lifecycles,
                                                                               governors,
                                                                               partitions,
                                                                               spokesmen,
                                                                               Map.<NodeId, Epoch>of(),
                                                                               Map.<String, Epoch>of(),
                                                                               Map.of(),
                                                                               nodesWithArtifacts);
        return projector.project(input);
    }

    private static Option<Integer> collectDesiredCoreSize(Map<AetherKey, AetherValue> kv) {
        return Option.option(kv.get(AetherKey.ClusterConfigKey.CURRENT)).filter(v -> v instanceof AetherValue.ClusterConfigValue)
                            .map(v -> ((AetherValue.ClusterConfigValue) v).coreCount());
    }

    // FIXME(ssot): migrate the five collect*/collectDesiredCoreSize helpers to KVStore.forEach
    // (integrations/cluster/.../KVStore.java#forEach(Class,Class,BiConsumer)) to remove the
    // erasure-dependent `entry.getKey() instanceof X` cast pattern. Blocked on changing
    // kvSnapshotSupplier from Supplier<Map<AetherKey,AetherValue>> to a KVStore-shaped API
    // across four factory overloads and all call sites — deferred to a non-hotfix window.
    // Rationale: the KV store holds mixed key types (AetherKey + LeaderKey from consensus layer).
    // Iterating via entrySet erases the element type to Map.Entry so `instanceof` does not trigger
    // an implicit `checkcast AetherKey` on the stream element, which would throw ClassCastException
    // for any non-AetherKey entry (see regression test HealthReconcilerActivatorMixedKeyProjectionTest).
    private static Map<NodeId, NodeLifecycleValue> collectLifecycles(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet().stream()
                          .filter(entry -> entry.getKey() instanceof NodeLifecycleKey && entry.getValue() instanceof NodeLifecycleValue)
                          .collect(Collectors.toUnmodifiableMap(entry -> ((NodeLifecycleKey) entry.getKey()).nodeId(),
                                                                entry -> (NodeLifecycleValue) entry.getValue()));
    }

    private static Map<String, GovernorAnnouncementValue> collectGovernors(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet().stream()
                          .filter(entry -> entry.getKey() instanceof GovernorAnnouncementKey && entry.getValue() instanceof GovernorAnnouncementValue)
                          .collect(Collectors.toUnmodifiableMap(entry -> ((GovernorAnnouncementKey) entry.getKey()).communityId(),
                                                                entry -> (GovernorAnnouncementValue) entry.getValue()));
    }

    private static Map<String, DhtPartitionOwnershipValue> collectPartitions(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet().stream()
                          .filter(entry -> entry.getKey() instanceof DhtPartitionOwnershipKey && entry.getValue() instanceof DhtPartitionOwnershipValue)
                          .collect(Collectors.toUnmodifiableMap(entry -> ((DhtPartitionOwnershipKey) entry.getKey()).partitionId(),
                                                                entry -> (DhtPartitionOwnershipValue) entry.getValue()));
    }

    private static Map<NodeId, SpokesmanValue> collectSpokesmen(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet().stream()
                          .filter(entry -> entry.getKey() instanceof SpokesmanKey && entry.getValue() instanceof SpokesmanValue)
                          .collect(Collectors.toUnmodifiableMap(entry -> ((SpokesmanKey) entry.getKey()).coreNodeId(),
                                                                entry -> (SpokesmanValue) entry.getValue()));
    }

    private static Set<NodeId> collectNodesWithArtifacts(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet().stream()
                          .filter(entry -> entry.getKey() instanceof NodeArtifactKey)
                          .map(entry -> ((NodeArtifactKey) entry.getKey()).nodeId())
                          .collect(Collectors.toUnmodifiableSet());
    }

    @Contract@Override public void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> notification) {
        if (!isLeaderGate.get()) {return;}
        retryBootstrapIfNeeded();
        var communityId = notification.cause().key()
                                            .communityId();
        var value = notification.cause().value();
        if (value.dissolved()) {
            reconciler.onSignal(new HealthSignal.CommunityDissolved(communityId));
            reconciler.requestReprojection(this::projectFromCommittedAtoms, "governor-announcement");
            return;
        }
        reconciler.onSignal(new HealthSignal.GovernorAnnounced(communityId, value.governorId(), value.communityTerm()));
        reconciler.requestReprojection(this::projectFromCommittedAtoms, "governor-announcement");
    }

    @Contract@Override public void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> notification) {
        if (!isLeaderGate.get()) {return;}
        reconciler.onSignal(new HealthSignal.CommunityDissolved(notification.cause().key()
                                                                                  .communityId()));
    }

    @Contract@Override public void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification) {
        if (!isLeaderGate.get()) {return;}
        retryBootstrapIfNeeded();
        var value = notification.cause().value();
        if (value.status() != SpokesmanStatus.FAILED) {return;}
        var coreNodeId = notification.cause().key()
                                           .coreNodeId();
        reconciler.onSignal(new HealthSignal.SpokesmanAssignmentFailed(coreNodeId,
                                                                       value.communities(),
                                                                       value.failureReason()));
    }

    @Override public HealthSignalSink sink() {
        return reconciler;
    }

    @Contract@Override public void onClusterConfigPut(ValuePut<ClusterConfigKey, AetherValue.ClusterConfigValue> notification) {
        if (!isLeaderGate.get()) {return;}
        retryBootstrapIfNeeded();
        reconciler.requestReprojection(this::projectFromCommittedAtoms, "cluster-config-put");
    }

    @Contract@Override public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> notification) {
        if (!isLeaderGate.get()) {return;}
        retryBootstrapIfNeeded();
        reconciler.requestReprojection(this::projectFromCommittedAtoms, "node-lifecycle-put");
        var state = notification.cause().value()
                                      .state();
        if (state != NodeLifecycleState.DECOMMISSIONED) {return;}
        var nodeId = notification.cause().key()
                                       .nodeId();
        Option.option(reconciler.currentSnapshot().coreMembers()
                                                .get(nodeId))
        .onPresent(_ -> log.info("Core node {} transitioned to DECOMMISSIONED — reconciler will rebalance spokesmen on next signal",
                                 nodeId));
    }
}
