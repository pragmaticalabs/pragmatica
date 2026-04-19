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
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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

    @Contract void onLeaderChange(LeaderChange change);
    @Contract void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> notification);
    @Contract void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> notification);
    @Contract void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification);
    @Contract void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> notification);
    HealthSignalSink sink();

    static HealthReconcilerActivator healthReconcilerActivator(HealthReconciler reconciler,
                                                               AtomicBoolean isLeaderGate) {
        return new HealthReconcilerActivatorRecord(reconciler,
                                                   isLeaderGate,
                                                   ClusterGenerationProjector.clusterGenerationProjector(),
                                                   Map::of,
                                                   () -> 0L,
                                                   HlcClock.hlcClock("activator-default").unwrap(),
                                                   Option.<ClusterNode<KVCommand<AetherKey>>>none(),
                                                   reconciler::self);
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
                                                   reconciler::self);
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
                                                   selfSupplier);
    }
}

record HealthReconcilerActivatorRecord(HealthReconciler reconciler,
                                       AtomicBoolean isLeaderGate,
                                       ClusterGenerationProjector projector,
                                       Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                       Supplier<Long> rabiaTermSupplier,
                                       HlcClock hlcClock,
                                       Option<ClusterNode<KVCommand<AetherKey>>> cluster,
                                       Supplier<NodeId> selfSupplier) implements HealthReconcilerActivator {
    private static final Logger log = LoggerFactory.getLogger(HealthReconcilerActivatorRecord.class);

    @Contract@Override public void onLeaderChange(LeaderChange change) {
        isLeaderGate.set(change.localNodeIsLeader());
        if (change.localNodeIsLeader()) {
            log.info("HealthReconciler becoming leader — projecting from committed atoms, then starting reconciler");
            var seeded = projectFromCommittedAtoms();
            reconciler.seedSnapshot(seeded);
            reconciler.start();
            bootstrapCorePartitionOwnership(seeded);
        } else {
            log.info("HealthReconciler stepping down — stopping reconciler");
            reconciler.stop();
            reconciler.seedSnapshot(ClusterGenerationSnapshot.empty(reconciler.currentEpoch().rabiaTerm()));
        }
    }

    @Contract private void bootstrapCorePartitionOwnership(ClusterGenerationSnapshot seeded) {
        cluster.onPresent(clusterNode -> applyCoreBootstrap(clusterNode, seeded));
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
                             owner -> shouldRewriteCoreOwnership(owner, seeded, self)
                                     ? Option.some(buildRewrittenCorePartition(owner, seeded, self))
                                     : Option.none());
    }

    private static boolean shouldRewriteCoreOwnership(PartitionOwner owner,
                                                      ClusterGenerationSnapshot seeded,
                                                      NodeId self) {
        var recordedOwner = owner.ownerNodeId();
        if (recordedOwner.equals(self)) {return false;}
        var recordedMember = seeded.coreMembers().get(recordedOwner);
        if (recordedMember == null) {return true;}
        var state = recordedMember.lifecycle();
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
        clusterNode.apply(List.of(command))
                         .onFailure(cause -> log.error("Core DhtPartitionOwnership bootstrap failed: {}",
                                                       cause.message()));
    }

    private ClusterGenerationSnapshot projectFromCommittedAtoms() {
        var kv = kvSnapshotSupplier.get();
        var lifecycles = collectLifecycles(kv);
        var governors = collectGovernors(kv);
        var partitions = collectPartitions(kv);
        var spokesmen = collectSpokesmen(kv);
        var term = rabiaTermSupplier.get();
        var input = ClusterGenerationProjector.ProjectionInput.projectionInput(term,
                                                                               0L,
                                                                               lifecycles.size(),
                                                                               GenerationReason.LEADER_ELECTED,
                                                                               hlcClock.now(),
                                                                               lifecycles,
                                                                               governors,
                                                                               partitions,
                                                                               spokesmen,
                                                                               Map.<NodeId, Epoch>of(),
                                                                               Map.<String, Epoch>of(),
                                                                               Map.of());
        return projector.project(input);
    }

    private static Map<NodeId, NodeLifecycleValue> collectLifecycles(Map<AetherKey, AetherValue> kv) {
        var result = new LinkedHashMap<NodeId, NodeLifecycleValue>();
        kv.forEach((key, value) -> {
                       if (key instanceof NodeLifecycleKey nk && value instanceof NodeLifecycleValue nv) {result.put(nk.nodeId(),
                                                                                                                     nv);}
                   });
        return Map.copyOf(result);
    }

    private static Map<String, GovernorAnnouncementValue> collectGovernors(Map<AetherKey, AetherValue> kv) {
        var result = new LinkedHashMap<String, GovernorAnnouncementValue>();
        kv.forEach((key, value) -> {
                       if (key instanceof GovernorAnnouncementKey gk && value instanceof GovernorAnnouncementValue gv) {result.put(gk.communityId(),
                                                                                                                                   gv);}
                   });
        return Map.copyOf(result);
    }

    private static Map<String, DhtPartitionOwnershipValue> collectPartitions(Map<AetherKey, AetherValue> kv) {
        var result = new LinkedHashMap<String, DhtPartitionOwnershipValue>();
        kv.forEach((key, value) -> {
                       if (key instanceof DhtPartitionOwnershipKey pk && value instanceof DhtPartitionOwnershipValue pv) {result.put(pk.partitionId(),
                                                                                                                                     pv);}
                   });
        return Map.copyOf(result);
    }

    private static Map<NodeId, SpokesmanValue> collectSpokesmen(Map<AetherKey, AetherValue> kv) {
        var result = new LinkedHashMap<NodeId, SpokesmanValue>();
        kv.forEach((key, value) -> {
                       if (key instanceof SpokesmanKey sk && value instanceof SpokesmanValue sv) {result.put(sk.coreNodeId(),
                                                                                                             sv);}
                   });
        return Map.copyOf(result);
    }

    @Contract@Override public void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> notification) {
        if (!isLeaderGate.get()) {return;}
        var communityId = notification.cause().key()
                                            .communityId();
        var value = notification.cause().value();
        if (value.dissolved()) {
            reconciler.onSignal(new HealthSignal.CommunityDissolved(communityId));
            return;
        }
        reconciler.onSignal(new HealthSignal.GovernorAnnounced(communityId, value.governorId(), value.communityTerm()));
    }

    @Contract@Override public void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> notification) {
        if (!isLeaderGate.get()) {return;}
        reconciler.onSignal(new HealthSignal.CommunityDissolved(notification.cause().key()
                                                                                  .communityId()));
    }

    @Contract@Override public void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification) {
        if (!isLeaderGate.get()) {return;}
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

    @Contract@Override public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> notification) {
        if (!isLeaderGate.get()) {return;}
        var state = notification.cause().value()
                                      .state();
        if (state != NodeLifecycleState.DECOMMISSIONED) {return;}
        var nodeId = notification.cause().key()
                                       .nodeId();
        var snapshot = reconciler.currentSnapshot();
        var member = snapshot.coreMembers().get(nodeId);
        if (member == null) {return;}
        log.info("Core node {} transitioned to DECOMMISSIONED — reconciler will rebalance spokesmen on next signal",
                 nodeId);
    }
}
