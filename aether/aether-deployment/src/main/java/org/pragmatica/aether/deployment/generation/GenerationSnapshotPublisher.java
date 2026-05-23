// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GenerationSnapshotKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GenerationSnapshotValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class GenerationSnapshotPublisher {
    private static final Logger log = LoggerFactory.getLogger(GenerationSnapshotPublisher.class);

    private final AtomicReference<PublisherState> state = new AtomicReference<>(PublisherState.Disabled.INSTANCE);

    private final BooleanSupplier isLeader;
    private final Supplier<Long> rabiaTermSupplier;
    private final HlcClock hlcClock;
    private final ClusterGenerationProjector projector;
    private final SwimHintsRegistry swimHints;
    private final Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final Executor executor;

    private GenerationSnapshotPublisher(BooleanSupplier isLeader,
                                        Supplier<Long> rabiaTermSupplier,
                                        HlcClock hlcClock,
                                        ClusterGenerationProjector projector,
                                        SwimHintsRegistry swimHints,
                                        Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                        KVStore<AetherKey, AetherValue> kvStore,
                                        ClusterNode<KVCommand<AetherKey>> cluster,
                                        Executor executor) {
        this.isLeader = isLeader;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.hlcClock = hlcClock;
        this.projector = projector;
        this.swimHints = swimHints;
        this.kvSnapshotSupplier = kvSnapshotSupplier;
        this.kvStore = kvStore;
        this.cluster = cluster;
        this.executor = executor;
    }

    public static GenerationSnapshotPublisher generationSnapshotPublisher(BooleanSupplier isLeader,
                                                                          Supplier<Long> rabiaTermSupplier,
                                                                          HlcClock hlcClock,
                                                                          ClusterGenerationProjector projector,
                                                                          SwimHintsRegistry swimHints,
                                                                          Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                                          KVStore<AetherKey, AetherValue> kvStore,
                                                                          ClusterNode<KVCommand<AetherKey>> cluster,
                                                                          Executor executor) {
        return new GenerationSnapshotPublisher(isLeader,
                                               rabiaTermSupplier,
                                               hlcClock,
                                               projector,
                                               swimHints,
                                               kvSnapshotSupplier,
                                               kvStore,
                                               cluster,
                                               executor);
    }

    @Contract
    public void onLeaderGained() {
        dispatch(PublisherEvent.LeaderGained.INSTANCE);
    }

    @Contract
    public void onLeaderLost() {
        dispatch(PublisherEvent.LeaderLost.INSTANCE);
    }

    @Contract
    public void markDirty() {
        dispatch(PublisherEvent.Mark.INSTANCE);
    }

    /// RC1 Step 2: receive `MembershipDecision` events from the canonical
    /// `TopologyObserver` emitter. Membership changes alter the lifecycle map that
    /// `projectFromKv` consumes, so every decision flips the publisher into the
    /// Mark/Publishing path. The KV snapshot supplier is still used to actually project
    /// the next generation snapshot — the subscription only tells the publisher *when*
    /// the projection must re-run (snapshot-then-tail: initialise from KV snapshot at
    /// construction; tail MembershipDecision for dirty signalling).
    @Contract
    public void onMembershipDecision(MembershipDecision decision) {
        log.debug("GenerationSnapshotPublisher received {}", decision);
        markDirty();
    }

    PublisherState currentState() {
        return state.get();
    }

    @Contract
    private void dispatch(PublisherEvent event) {
        while (true) {
            var prev = state.get();
            var next = transition(prev, event);

            if (state.compareAndSet(prev, next)) {
                onEnter(prev, next);

                return;
            }
        }
    }

    static PublisherState transition(PublisherState prev, PublisherEvent event) {
        return switch (prev) {
            case PublisherState.Disabled _ -> switch (event) {
                case PublisherEvent.LeaderGained _ -> PublisherState.Idle.INSTANCE;
                case PublisherEvent.LeaderLost _, PublisherEvent.Mark _, PublisherEvent.ApplyDone _ -> prev;
            };
            case PublisherState.Idle _ -> switch (event) {
                case PublisherEvent.Mark _ -> PublisherState.Publishing.INSTANCE;
                case PublisherEvent.LeaderLost _ -> PublisherState.Disabled.INSTANCE;
                case PublisherEvent.LeaderGained _, PublisherEvent.ApplyDone _ -> prev;
            };
            case PublisherState.Publishing _ -> switch (event) {
                case PublisherEvent.Mark _ -> PublisherState.PublishingDirty.INSTANCE;
                case PublisherEvent.ApplyDone _ -> PublisherState.Idle.INSTANCE;
                case PublisherEvent.LeaderLost _ -> PublisherState.Disabled.INSTANCE;
                case PublisherEvent.LeaderGained _ -> prev;
            };
            case PublisherState.PublishingDirty _ -> switch (event) {
                case PublisherEvent.ApplyDone _ -> PublisherState.Publishing.INSTANCE;
                case PublisherEvent.LeaderLost _ -> PublisherState.Disabled.INSTANCE;
                case PublisherEvent.Mark _, PublisherEvent.LeaderGained _ -> prev;
            };
        };
    }

    @Contract
    private void onEnter(PublisherState prev, PublisherState next) {
        if (next instanceof PublisherState.Idle && prev instanceof PublisherState.Disabled) {
            executor.execute(() -> dispatch(PublisherEvent.Mark.INSTANCE));

            return;
        }
        if (next instanceof PublisherState.Publishing && !(prev instanceof PublisherState.Publishing)) {executor.execute(this::runApply);}
    }

    @Contract
    private void runApply() {
        if (!isLeader.getAsBoolean()) {
            dispatch(PublisherEvent.LeaderLost.INSTANCE);

            return;
        }

        var current = readCurrentFromKv();
        var projected = projectFromKv(current);
        cluster.apply(List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(GenerationSnapshotKey.SINGLETON,
                                                                         GenerationSnapshotValue.generationSnapshotValue(projected)))).onResult(result -> {
                                                                                                                                                    result.onFailure(cause -> log.warn("snapshot publish failed (will retry on next mark): {}",
                                                                                                                                                                                       cause.message()));
                                                                                                                                                    dispatch(PublisherEvent.ApplyDone.INSTANCE);
                                                                                                                                                });
    }

    private Option<ClusterGenerationSnapshot> readCurrentFromKv() {
        return kvStore.getTyped(GenerationSnapshotKey.SINGLETON, GenerationSnapshotValue.class)
                      .map(GenerationSnapshotValue::snapshot);
    }

    private ClusterGenerationSnapshot projectFromKv(Option<ClusterGenerationSnapshot> currentInKv) {
        var kv = kvSnapshotSupplier.get();
        var lifecycles = collectLifecycles(kv);
        var governors = collectGovernors(kv);
        var partitions = collectPartitions(kv);
        var spokesmen = collectSpokesmen(kv);
        var nodesWithArtifacts = collectNodesWithArtifacts(kv);
        var term = rabiaTermSupplier.get();
        var counter = currentInKv.map(s -> s.epoch()
                                            .localCounter() + 1L).or(0L);
        var desiredCoreSize = collectDesiredCoreSize(kv).or(lifecycles.size());
        var hints = swimHints.currentTtlFiltered();
        var input = ClusterGenerationProjector.ProjectionInput.projectionInput(term,
                                                                               counter,
                                                                               desiredCoreSize,
                                                                               GenerationReason.LEADER_ELECTED,
                                                                               hlcClock.now(),
                                                                               lifecycles,
                                                                               governors,
                                                                               partitions,
                                                                               spokesmen,
                                                                               Map.<NodeId, Epoch> of(),
                                                                               Map.<String, Epoch> of(),
                                                                               Map.of(),
                                                                               nodesWithArtifacts,
                                                                               hints);

        return projector.project(input);
    }

    private static Option<Integer> collectDesiredCoreSize(Map<AetherKey, AetherValue> kv) {
        return Option.option(kv.get(ClusterConfigKey.CURRENT))
                     .filter(v -> v instanceof ClusterConfigValue)
                     .map(v -> ((ClusterConfigValue) v).coreCount());
    }

    private static Map<NodeId, NodeLifecycleValue> collectLifecycles(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet()
                 .stream()
                 .filter(entry -> entry.getKey() instanceof NodeLifecycleKey && entry.getValue() instanceof NodeLifecycleValue)
                 .collect(Collectors.toUnmodifiableMap(entry -> ((NodeLifecycleKey) entry.getKey()).nodeId(),
                                                       entry -> (NodeLifecycleValue) entry.getValue()));
    }

    private static Map<String, GovernorAnnouncementValue> collectGovernors(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet()
                 .stream()
                 .filter(entry -> entry.getKey() instanceof GovernorAnnouncementKey && entry.getValue() instanceof GovernorAnnouncementValue)
                 .collect(Collectors.toUnmodifiableMap(entry -> ((GovernorAnnouncementKey) entry.getKey()).communityId(),
                                                       entry -> (GovernorAnnouncementValue) entry.getValue()));
    }

    private static Map<String, DhtPartitionOwnershipValue> collectPartitions(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet()
                 .stream()
                 .filter(entry -> entry.getKey() instanceof DhtPartitionOwnershipKey && entry.getValue() instanceof DhtPartitionOwnershipValue)
                 .collect(Collectors.toUnmodifiableMap(entry -> ((DhtPartitionOwnershipKey) entry.getKey()).partitionId(),
                                                       entry -> (DhtPartitionOwnershipValue) entry.getValue()));
    }

    private static Map<NodeId, SpokesmanValue> collectSpokesmen(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet()
                 .stream()
                 .filter(entry -> entry.getKey() instanceof SpokesmanKey && entry.getValue() instanceof SpokesmanValue)
                 .collect(Collectors.toUnmodifiableMap(entry -> ((SpokesmanKey) entry.getKey()).coreNodeId(),
                                                       entry -> (SpokesmanValue) entry.getValue()));
    }

    private static Set<NodeId> collectNodesWithArtifacts(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet()
                 .stream()
                 .filter(entry -> entry.getKey() instanceof NodeArtifactKey)
                 .map(entry -> ((NodeArtifactKey) entry.getKey()).nodeId())
                 .collect(Collectors.toUnmodifiableSet());
    }
}
