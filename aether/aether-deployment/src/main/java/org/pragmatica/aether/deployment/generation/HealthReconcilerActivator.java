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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Wires the leader-only `HealthReconciler` into the message bus for Commit 3 activation.
///
/// Subscribes to:
///   - `LeaderChange` — starts/stops the reconciler based on the leader supplier
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
    /// Hook called after the leader-change bootstrap batch commits (or immediately if no
    /// bootstrap commands were needed). Used by `AetherNode` to chain `CTM.activate()` so
    /// that CTM does not start dispatching before the first snapshot has been published —
    /// closes the phantom-provision window between leader-gain and reconciler-ready.
    @Contract HealthReconcilerActivator onBootstrapCommitted(Runnable callback);
    @Contract void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> notification);
    @Contract void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> notification);
    @Contract void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification);
    @Contract void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> notification);
    @Contract void onClusterConfigPut(ValuePut<ClusterConfigKey, AetherValue.ClusterConfigValue> notification);
    HealthSignalSink sink();

    static Result<HealthReconcilerActivator> healthReconcilerActivator(HealthReconciler reconciler,
                                                                       BooleanSupplier isLeaderSupplier) {
        return HlcClock.hlcClock("activator-default")
                                .map(clock -> new HealthReconcilerActivatorRecord(reconciler,
                                                                                  isLeaderSupplier,
                                                                                  ClusterGenerationProjector.clusterGenerationProjector(),
                                                                                  Map::of,
                                                                                  () -> 0L,
                                                                                  clock,
                                                                                  Option.<ClusterNode<KVCommand<AetherKey>>>none(),
                                                                                  reconciler::self,
                                                                                  () -> 0,
                                                                                  new AtomicBoolean(false),
                                                                                  new AtomicInteger(),
                                                                                  new AtomicLong(0L),
                                                                                  new AtomicLong(0L),
                                                                                  new AtomicReference<>()));
    }

    static HealthReconcilerActivator healthReconcilerActivator(HealthReconciler reconciler,
                                                               BooleanSupplier isLeaderSupplier,
                                                               ClusterGenerationProjector projector,
                                                               Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                               Supplier<Long> rabiaTermSupplier,
                                                               HlcClock hlcClock) {
        return new HealthReconcilerActivatorRecord(reconciler,
                                                   isLeaderSupplier,
                                                   projector,
                                                   kvSnapshotSupplier,
                                                   rabiaTermSupplier,
                                                   hlcClock,
                                                   Option.<ClusterNode<KVCommand<AetherKey>>>none(),
                                                   reconciler::self,
                                                   () -> 0,
                                                   new AtomicBoolean(false),
                                                   new AtomicInteger(),
                                                   new AtomicLong(0L),
                                                   new AtomicLong(0L),
                                                   new AtomicReference<>());
    }

    static HealthReconcilerActivator healthReconcilerActivator(HealthReconciler reconciler,
                                                               BooleanSupplier isLeaderSupplier,
                                                               ClusterGenerationProjector projector,
                                                               Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                               Supplier<Long> rabiaTermSupplier,
                                                               HlcClock hlcClock,
                                                               ClusterNode<KVCommand<AetherKey>> cluster,
                                                               Supplier<NodeId> selfSupplier) {
        return new HealthReconcilerActivatorRecord(reconciler,
                                                   isLeaderSupplier,
                                                   projector,
                                                   kvSnapshotSupplier,
                                                   rabiaTermSupplier,
                                                   hlcClock,
                                                   Option.some(cluster),
                                                   selfSupplier,
                                                   () -> 0,
                                                   new AtomicBoolean(false),
                                                   new AtomicInteger(),
                                                   new AtomicLong(0L),
                                                   new AtomicLong(0L),
                                                   new AtomicReference<>());
    }

    static HealthReconcilerActivator healthReconcilerActivator(HealthReconciler reconciler,
                                                               BooleanSupplier isLeaderSupplier,
                                                               ClusterGenerationProjector projector,
                                                               Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                                               Supplier<Long> rabiaTermSupplier,
                                                               HlcClock hlcClock,
                                                               ClusterNode<KVCommand<AetherKey>> cluster,
                                                               Supplier<NodeId> selfSupplier,
                                                               Supplier<Integer> initialCoreSizeSupplier) {
        return new HealthReconcilerActivatorRecord(reconciler,
                                                   isLeaderSupplier,
                                                   projector,
                                                   kvSnapshotSupplier,
                                                   rabiaTermSupplier,
                                                   hlcClock,
                                                   Option.some(cluster),
                                                   selfSupplier,
                                                   initialCoreSizeSupplier,
                                                   new AtomicBoolean(false),
                                                   new AtomicInteger(),
                                                   new AtomicLong(0L),
                                                   new AtomicLong(0L),
                                                   new AtomicReference<>());
    }
}

record HealthReconcilerActivatorRecord(HealthReconciler reconciler,
                                       BooleanSupplier isLeaderSupplier,
                                       ClusterGenerationProjector projector,
                                       Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                       Supplier<Long> rabiaTermSupplier,
                                       HlcClock hlcClock,
                                       Option<ClusterNode<KVCommand<AetherKey>>> cluster,
                                       Supplier<NodeId> selfSupplier,
                                       Supplier<Integer> initialCoreSizeSupplier,
                                       AtomicBoolean bootstrapComplete,
                                       AtomicInteger bootstrapAttempts,
                                       AtomicLong leaderBootstrapTimeMs,
                                       AtomicLong lastSeedAttemptMs,
                                       AtomicReference<Runnable> bootstrapCommittedCallback) implements HealthReconcilerActivator {
    private static final Logger log = LoggerFactory.getLogger(HealthReconcilerActivatorRecord.class);

    /// Maximum time after leader gain to defer the `ClusterConfigValue` seed when the
    /// observed lifecycle count is still climbing toward `initialCoreSize`. After this
    /// grace expires the seed proceeds even if not all core nodes have joined yet —
    /// quorum (≥3 lifecycles) is still required separately. See Theme B.
    private static final long SEED_GRACE_MS = 60_000L;

    /// Minimum interval between seed-attempt evaluations during the grace window.
    /// Prevents hot-looping when a flurry of KV notifications drives `applyLeaderChangeBootstrapBatch`
    /// in rapid succession before the lifecycle count converges.
    private static final long SEED_ATTEMPT_THROTTLE_MS = 5_000L;

    @Contract@Override public void onLeaderChange(LeaderChange change) {
        if (change.localNodeIsLeader()) {
            log.info("HealthReconciler becoming leader — projecting from committed atoms, then starting reconciler");
            reconciler.stop(StopReason.LEADER_LOST);
            bootstrapComplete.set(false);
            bootstrapAttempts.set(0);
            leaderBootstrapTimeMs.set(System.currentTimeMillis());
            lastSeedAttemptMs.set(0L);
            var seeded = projectFromCommittedAtoms();
            reconciler.seedSnapshot(seeded);
            reconciler.start();
            performLeaderChangeBootstrap(seeded);
        } else {
            log.info("HealthReconciler stepping down — stopping reconciler");
            reconciler.stop(StopReason.LEADER_LOST);
            reconciler.seedSnapshot(ClusterGenerationSnapshot.empty(reconciler.currentEpoch().rabiaTerm()));
            bootstrapComplete.set(false);
            bootstrapAttempts.set(0);
            leaderBootstrapTimeMs.set(0L);
            lastSeedAttemptMs.set(0L);
        }
    }

    @Contract private void performLeaderChangeBootstrap(ClusterGenerationSnapshot seeded) {
        cluster.onPresent(clusterNode -> applyLeaderChangeBootstrapBatch(clusterNode, seeded));
        // Theme B Item 2: if there is no cluster (test-only paths) the bootstrap-committed
        // callback still must fire, otherwise CTM activation would never be chained.
        if (cluster.isEmpty()) {fireBootstrapCommitted();}
    }

    @Contract private void applyLeaderChangeBootstrapBatch(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                           ClusterGenerationSnapshot seeded) {
        var batch = new ArrayList<KVCommand<AetherKey>>();
        var corePlan = planCoreBootstrap(seeded);
        var configPlan = planClusterConfigSeed();
        corePlan.onPresent(plan -> batch.add(plan.command()));
        configPlan.onPresent(plan -> batch.add(plan.command()));
        if (batch.isEmpty()) {
            // Nothing to commit — invariants already satisfied. Fire the bootstrap-committed
            // callback immediately so CTM activation is chained without an empty batch round-trip.
            fireBootstrapCommitted();
            return;
        }
        corePlan.onPresent(this::logCoreBootstrap);
        configPlan.onPresent(this::logClusterConfigSeed);
        var hasCore = corePlan.isPresent();
        if (hasCore) {bootstrapAttempts.incrementAndGet();}
        var commandCount = batch.size();
        clusterNode.apply(List.copyOf(batch))
                   .onFailure(cause -> log.warn("Leader-change bootstrap batch failed ({} commands, attempt {}/{}): {}",
                                                commandCount,
                                                bootstrapAttempts.get(),
                                                HealthReconcilerActivator.BOOTSTRAP_MAX_ATTEMPTS,
                                                cause.message()))
                   .onSuccess(_ -> onLeaderChangeBootstrapCommitted(hasCore, commandCount));
    }

    @Contract private void onLeaderChangeBootstrapCommitted(boolean hasCore, int commandCount) {
        if (hasCore) {bootstrapComplete.set(true);}
        log.info("Leader-change bootstrap committed: {} commands", commandCount);
        // Theme B Item 2: chain CTM activation here. Subscribers registered via
        // `onBootstrapCommitted(...)` run AFTER the bootstrap batch commits — they observe a
        // reconciler that has already seeded its first snapshot, eliminating the phantom-
        // provision window.
        fireBootstrapCommitted();
    }

    @Contract@Override public HealthReconcilerActivator onBootstrapCommitted(Runnable callback) {
        bootstrapCommittedCallback.set(callback);
        return this;
    }

    @Contract private void fireBootstrapCommitted() {
        var callback = bootstrapCommittedCallback.get();
        if (callback == null) {return;}
        Result.lift(callback::run).onFailure(cause -> log.warn("Bootstrap-committed callback threw: {}",
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
        var existing = Option.option(seeded.partitions().get(CORE_PARTITION_ID));
        return decideCoreOwnership(existing, seeded, self).map(command -> new CoreBootstrapPlan(command, existing, self));
    }

    Option<ClusterConfigSeedPlan> planClusterConfigSeed() {
        var existing = kvSnapshotSupplier.get().get(ClusterConfigKey.CURRENT);
        if (existing instanceof ClusterConfigValue) {return Option.none();}
        var initialSize = initialCoreSizeSupplier.get();
        if (initialSize < 3) {
            log.debug("Skipping ClusterConfigValue seed: initial core size {} below quorum minimum", initialSize);
            return Option.none();
        }
        if (!seedGraceElapsedOrConverged(initialSize)) {return Option.none();}
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

    /// Returns true if the seed should proceed: either the lifecycle count has converged to
    /// `initialSize` OR the seed grace window (`SEED_GRACE_MS`) has elapsed since leader gain.
    /// Throttles re-evaluations to once every `SEED_ATTEMPT_THROTTLE_MS` to avoid hot-looping
    /// when a rapid burst of KV notifications drives the activator before the cluster forms.
    private boolean seedGraceElapsedOrConverged(int initialSize) {
        var lifecycleCount = countLifecycleAtoms();
        if (lifecycleCount >= initialSize) {return true;}
        var nowMs = System.currentTimeMillis();
        var bootstrapTime = leaderBootstrapTimeMs.get();
        if (bootstrapTime == 0L) {
            // Defensive: leader-change time wasn't recorded — proceed (legacy/test path).
            return true;
        }
        var elapsed = nowMs - bootstrapTime;
        if (elapsed >= SEED_GRACE_MS) {
            log.info("ClusterConfigValue seed grace expired ({}ms ≥ {}ms) with lifecycle count {} of expected {} — seeding anyway",
                     elapsed,
                     SEED_GRACE_MS,
                     lifecycleCount,
                     initialSize);
            return true;
        }
        var lastAttempt = lastSeedAttemptMs.get();
        if (lastAttempt > 0L && nowMs - lastAttempt < SEED_ATTEMPT_THROTTLE_MS) {return false;}
        lastSeedAttemptMs.set(nowMs);
        log.debug("Deferring ClusterConfigValue seed: lifecycle count {} < initialSize {}, grace elapsed {}ms / {}ms",
                  lifecycleCount,
                  initialSize,
                  elapsed,
                  SEED_GRACE_MS);
        return false;
    }

    private int countLifecycleAtoms() {
        return (int) kvSnapshotSupplier.get().keySet().stream()
                                          .filter(NodeLifecycleKey.class::isInstance)
                                          .count();
    }

    @Contract private void logClusterConfigSeed(ClusterConfigSeedPlan plan) {
        log.info("Seeding ClusterConfigValue with coreCount={}, coreMin={}, coreMax={}",
                 plan.initialSize(),
                 SEED_CORE_MIN,
                 plan.coreMax());
    }

    @Contract private void logCoreBootstrap(CoreBootstrapPlan plan) {
        plan.existing()
            .onPresent(owner -> log.info("Rewriting DhtPartitionOwnershipKey(\"core\"): previous owner {} is not a live core member — {} takes over (ownershipTerm {})",
                                         owner.ownerNodeId(),
                                         plan.self(),
                                         owner.ownershipTerm() + 1L))
            .onEmpty(() -> log.info("Bootstrapping DhtPartitionOwnershipKey(\"core\") with owner {} (ownershipTerm 1)",
                                    plan.self()));
    }

    private record CoreBootstrapPlan(KVCommand<AetherKey> command, Option<PartitionOwner> existing, NodeId self) {}

    record ClusterConfigSeedPlan(KVCommand<AetherKey> command, int initialSize, int coreMax) {}

    private static final int SEED_CORE_MIN = 3;

    private static final int SEED_CORE_MAX = 15;

    @Contract private void retryBootstrapIfNeeded() {
        if (bootstrapComplete.get() || !isLeaderSupplier.getAsBoolean()) {return;}
        if (bootstrapAttempts.get() >= HealthReconcilerActivator.BOOTSTRAP_MAX_ATTEMPTS) {return;}
        attemptBootstrap(reconciler.currentSnapshot());
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
                                              CoreBootstrapPlan plan) {
        logCoreBootstrap(plan);
        bootstrapAttempts.incrementAndGet();
        clusterNode.apply(List.of(plan.command())).onFailure(cause -> log.warn("Core DhtPartitionOwnership bootstrap failed (attempt {}/{}): {}",
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
        // Carry leader-side `swimHints` into the projection so peers detected as FAULTY by
        // SWIM/QUIC ping-miss escalation are reflected in CoreMember.healthHint() — and thus
        // visible to MembershipView.healthyOnDutyCount() — before the slow eviction path
        // (>=10 misses) writes DECOMMISSIONED. Empty on followers/dormant nodes.
        var swimHints = reconciler.swimHintsView();
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
                                                                               swimHints);
        return projector.project(input);
    }

    private static Option<Integer> collectDesiredCoreSize(Map<AetherKey, AetherValue> kv) {
        return Option.option(kv.get(AetherKey.ClusterConfigKey.CURRENT)).filter(v -> v instanceof AetherValue.ClusterConfigValue)
                            .map(v -> ((AetherValue.ClusterConfigValue) v).coreCount());
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

    @Contract@Override public void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> notification) {
        if (!isLeaderSupplier.getAsBoolean()) {return;}
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
        if (!isLeaderSupplier.getAsBoolean()) {return;}
        reconciler.onSignal(new HealthSignal.CommunityDissolved(notification.cause().key()
                                                                                  .communityId()));
    }

    @Contract@Override public void onSpokesmanPut(ValuePut<SpokesmanKey, SpokesmanValue> notification) {
        if (!isLeaderSupplier.getAsBoolean()) {return;}
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
        if (!isLeaderSupplier.getAsBoolean()) {return;}
        retryBootstrapIfNeeded();
        reconciler.requestReprojection(this::projectFromCommittedAtoms, "cluster-config-put");
    }

    @Contract@Override public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> notification) {
        if (!isLeaderSupplier.getAsBoolean()) {return;}
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
