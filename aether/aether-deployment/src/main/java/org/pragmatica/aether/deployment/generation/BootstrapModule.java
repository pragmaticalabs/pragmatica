// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
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
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface BootstrapModule {
    String CORE_PARTITION_ID = "core";

    String CORE_COMMUNITY_ID = "core";

    int BOOTSTRAP_MAX_ATTEMPTS = 3;

    @Contract void onLeaderGained();
    @Contract void onLeaderLost();
    @Contract void retryIfNeeded();
    @Contract BootstrapModule onBootstrapCommitted(Runnable callback);
    /// RC1 Step 2: tail `MembershipDecision` events from `TopologyObserver` so the
    /// bootstrap module retries its core-partition seeding whenever the membership
    /// projection changes. The committed-atom KV snapshot supplier remains the source
    /// for actual seeding work — the subscription is the dirty signal
    /// (snapshot-then-tail).
    @Contract void onMembershipDecision(MembershipDecision decision);

    static BootstrapModule bootstrapModule(BooleanSupplier isLeaderSupplier,
                                           Supplier<Long> rabiaTermSupplier,
                                           Supplier<Option<Long>> leaderEpochSupplier,
                                           HlcClock hlcClock,
                                           ClusterGenerationProjector projector,
                                           Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                           Supplier<NodeId> selfSupplier,
                                           Supplier<Integer> initialCoreSizeSupplier,
                                           ClusterNode<KVCommand<AetherKey>> cluster) {
        return new BootstrapModuleRecord(isLeaderSupplier,
                                         rabiaTermSupplier,
                                         leaderEpochSupplier,
                                         hlcClock,
                                         projector,
                                         kvSnapshotSupplier,
                                         selfSupplier,
                                         initialCoreSizeSupplier,
                                         Option.some(cluster),
                                         new AtomicBoolean(false),
                                         new AtomicInteger(),
                                         new AtomicReference<>());
    }
}

record BootstrapModuleRecord(BooleanSupplier isLeaderSupplier,
                             Supplier<Long> rabiaTermSupplier,
                             Supplier<Option<Long>> leaderEpochSupplier,
                             HlcClock hlcClock,
                             ClusterGenerationProjector projector,
                             Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                             Supplier<NodeId> selfSupplier,
                             Supplier<Integer> initialCoreSizeSupplier,
                             Option<ClusterNode<KVCommand<AetherKey>>> cluster,
                             AtomicBoolean bootstrapComplete,
                             AtomicInteger bootstrapAttempts,
                             AtomicReference<Runnable> bootstrapCommittedCallback) implements BootstrapModule {
    private static final Logger log = LoggerFactory.getLogger(BootstrapModuleRecord.class);

    private static final int SEED_CORE_MIN = 3;

    private static final int SEED_CORE_MAX = 15;

    @Contract@Override public void onLeaderGained() {
        log.info("BootstrapModule becoming leader — projecting from committed atoms, then planning bootstrap");
        bootstrapComplete.set(false);
        bootstrapAttempts.set(0);
        var seeded = projectFromCommittedAtoms();
        performLeaderChangeBootstrap(seeded);
    }

    @Contract@Override public void onLeaderLost() {
        log.info("BootstrapModule stepping down — resetting bootstrap state");
        bootstrapComplete.set(false);
        bootstrapAttempts.set(0);
    }

    @Contract@Override public void retryIfNeeded() {
        retryBootstrapIfNeeded();
    }

    @Contract@Override public BootstrapModule onBootstrapCommitted(Runnable callback) {
        bootstrapCommittedCallback.set(callback);
        return this;
    }

    @Contract@Override public void onMembershipDecision(MembershipDecision decision) {
        log.debug("BootstrapModule received {}", decision);
        retryIfNeeded();
    }

    @Contract private void performLeaderChangeBootstrap(ClusterGenerationSnapshot seeded) {
        cluster.onPresent(clusterNode -> applyLeaderChangeBootstrapBatch(clusterNode, seeded));
        if (cluster.isEmpty()) {fireBootstrapCommitted();}
    }

    @Contract private void applyLeaderChangeBootstrapBatch(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                           ClusterGenerationSnapshot seeded) {
        var capturedEpoch = leaderEpochSupplier.get();
        var batch = new ArrayList<KVCommand<AetherKey>>();
        var corePlan = planCoreBootstrap(seeded);
        var configPlan = planClusterConfigSeed();
        corePlan.onPresent(plan -> batch.add(plan.command()));
        configPlan.onPresent(plan -> batch.add(plan.command()));
        if (batch.isEmpty()) {
            fireBootstrapCommitted();
            return;
        }
        if (leaderEpochChanged(capturedEpoch)) {
            log.info("Leader epoch changed during DHT bootstrap planning (captured={}, current={}); skipping partition write — next leader will retry",
                     capturedEpoch,
                     leaderEpochSupplier.get());
            return;
        }
        corePlan.onPresent(this::logCoreBootstrap);
        configPlan.onPresent(this::logClusterConfigSeed);
        var hasCore = corePlan.isPresent();
        if (hasCore) {bootstrapAttempts.incrementAndGet();}
        var commandCount = batch.size();
        clusterNode.apply(List.copyOf(batch)).onFailure(cause -> log.warn("Leader-change bootstrap batch failed ({} commands, attempt {}/{}): {}",
                                                                          commandCount,
                                                                          bootstrapAttempts.get(),
                                                                          BootstrapModule.BOOTSTRAP_MAX_ATTEMPTS,
                                                                          cause.message()))
                         .onSuccess(_ -> onLeaderChangeBootstrapCommitted(hasCore, commandCount));
    }

    private boolean leaderEpochChanged(Option<Long> captured) {
        if (captured.isEmpty()) {return false;}
        var current = leaderEpochSupplier.get();
        if (current.isEmpty()) {return false;}
        return ! captured.unwrap().equals(current.unwrap());
    }

    @Contract private void onLeaderChangeBootstrapCommitted(boolean hasCore, int commandCount) {
        if (hasCore) {bootstrapComplete.set(true);}
        log.info("Leader-change bootstrap committed: {} commands", commandCount);
        fireBootstrapCommitted();
    }

    @Contract private void fireBootstrapCommitted() {
        var callback = bootstrapCommittedCallback.get();
        if (callback == null) {return;}
        Result.lift(callback::run)
                   .onFailure(cause -> log.warn("Bootstrap-committed callback threw: {}",
                                                cause.message()));
    }

    @Contract private void attemptBootstrap(ClusterGenerationSnapshot seeded) {
        if (bootstrapComplete.get()) {return;}
        cluster.onPresent(clusterNode -> applyCoreBootstrapRetry(clusterNode, seeded));
    }

    @Contract private void applyCoreBootstrapRetry(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                   ClusterGenerationSnapshot seeded) {
        planCoreBootstrap(seeded).onPresent(plan -> writeCoreBootstrap(clusterNode, plan));
    }

    private Option<CoreBootstrapPlan> planCoreBootstrap(ClusterGenerationSnapshot seeded) {
        if (bootstrapComplete.get()) {return Option.none();}
        var self = selfSupplier.get();
        var existing = Option.option(seeded.partitions().get(BootstrapModule.CORE_PARTITION_ID));
        return decideCoreOwnership(existing, seeded, self).map(command -> new CoreBootstrapPlan(command, existing, self));
    }

    private Option<ClusterConfigSeedPlan> planClusterConfigSeed() {
        var existing = kvSnapshotSupplier.get().get(ClusterConfigKey.CURRENT);
        if (existing instanceof ClusterConfigValue) {return Option.none();}
        var initialSize = initialCoreSizeSupplier.get();
        if (initialSize <3) {
            log.debug("Skipping ClusterConfigValue seed: initial core size {} below quorum minimum", initialSize);
            return Option.none();
        }
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
        return Option.some(new ClusterConfigSeedPlan(command, initialSize, coreMax));
    }

    private int countLifecycleAtoms() {
        var count = 0;
        Map<?, ?> raw = kvSnapshotSupplier.get();
        for (Object k : raw.keySet()) {if (k instanceof NodeLifecycleKey) {count++;}}
        return count;
    }

    @Contract private void logClusterConfigSeed(ClusterConfigSeedPlan plan) {
        log.info("Seeding ClusterConfigValue with coreCount={}, coreMin={}, coreMax={}",
                 plan.initialSize(),
                 SEED_CORE_MIN,
                 plan.coreMax());
    }

    @Contract private void logCoreBootstrap(CoreBootstrapPlan plan) {
        plan.existing().onPresent(owner -> log.info("Rewriting DhtPartitionOwnershipKey(\"core\"): previous owner {} is not a live core member — {} takes over (ownershipTerm {})",
                                                    owner.ownerNodeId(),
                                                    plan.self(),
                                                    owner.ownershipTerm() + 1L))
                     .onEmpty(() -> log.info("Bootstrapping DhtPartitionOwnershipKey(\"core\") with owner {} (ownershipTerm 1)",
                                             plan.self()));
    }

    @Contract private void retryBootstrapIfNeeded() {
        if (!isLeaderSupplier.getAsBoolean()) {return;}
        if (!bootstrapComplete.get() && bootstrapAttempts.get() < BootstrapModule.BOOTSTRAP_MAX_ATTEMPTS) {
            attemptBootstrap(projectFromCommittedAtoms());
        }
        retryConfigSeedIfNeeded();
    }

    @Contract private void retryConfigSeedIfNeeded() {
        cluster.onPresent(clusterNode -> planClusterConfigSeed().onPresent(plan -> {
            logClusterConfigSeed(plan);
            clusterNode.apply(List.of(plan.command()))
                       .onFailure(cause -> log.warn("Config seed retry failed: {}", cause.message()))
                       .onSuccess(_ -> log.info("Config seed applied on retry"));
        }));
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
                                                                          BootstrapModule.CORE_COMMUNITY_ID,
                                                                          epoch,
                                                                          ownershipTerm,
                                                                          hlcClock.now());
        return new KVCommand.Put<AetherKey, AetherValue>(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(BootstrapModule.CORE_PARTITION_ID),
                                                         value);
    }

    @Contract private void writeCoreBootstrap(ClusterNode<KVCommand<AetherKey>> clusterNode, CoreBootstrapPlan plan) {
        var capturedEpoch = leaderEpochSupplier.get();
        if (leaderEpochChanged(capturedEpoch)) {
            log.info("Leader epoch changed during DHT bootstrap retry (captured={}, current={}); skipping partition write",
                     capturedEpoch,
                     leaderEpochSupplier.get());
            return;
        }
        logCoreBootstrap(plan);
        bootstrapAttempts.incrementAndGet();
        clusterNode.apply(List.of(plan.command())).onFailure(cause -> log.warn("Core DhtPartitionOwnership bootstrap failed (attempt {}/{}): {}",
                                                                               bootstrapAttempts.get(),
                                                                               BootstrapModule.BOOTSTRAP_MAX_ATTEMPTS,
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
                                                                               nodesWithArtifacts,
                                                                               Map.of());
        return projector.project(input);
    }

    private static Option<Integer> collectDesiredCoreSize(Map<AetherKey, AetherValue> kv) {
        return Option.option(kv.get(ClusterConfigKey.CURRENT)).filter(v -> v instanceof ClusterConfigValue)
                            .map(v -> ((ClusterConfigValue) v).coreCount());
    }

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

    private record CoreBootstrapPlan(KVCommand<AetherKey> command, Option<PartitionOwner> existing, NodeId self){}

    private record ClusterConfigSeedPlan(KVCommand<AetherKey> command, int initialSize, int coreMax){}
}
