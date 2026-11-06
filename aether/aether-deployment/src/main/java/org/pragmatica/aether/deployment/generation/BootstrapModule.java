// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

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

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.react.ReactExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface BootstrapModule {
    String CORE_PARTITION_ID = "core";
    String CORE_COMMUNITY_ID = "core";
    int BOOTSTRAP_MAX_ATTEMPTS = 3;

    /// Static cluster-config baseline carried from the node's `ClusterConfig`/`TopologyConfig`.
    /// Used to seed `ClusterConfigKey.CURRENT` on leader gain when it is absent — covering both
    /// the explicit-bootstrap path and the non-bootstrap / restart-with-empty-KV path. Sourced
    /// from configuration (not the live committed-topology member count), so a leader can seed a
    /// faithful baseline before presence-derived membership has converged.
    record ClusterConfigBaseline(int coreCount, int coreMin, int coreMax) {}

    @Contract
    void onLeaderGained();

    @Contract
    void onLeaderLost();

    /// Re-drives the core-partition seeding and the cluster-config seed. D1-react (KVStore dispatcher design §3.5.1 rows
    /// 66/67): the attempt and its `bootstrapAttempts` increment run as one reaction on the installed react executor,
    /// so a notification absorbed before an engine-only stop does not burn an attempt after it. Every caller (the
    /// membership tail, a Spokesman put, the restore-decision hook) shares that one seam.
    @Contract
    void retryIfNeeded();

    /// The executor `retryIfNeeded` runs its attempt on. A node installs its gated executor once, at assembly; until
    /// then (and in a host that installs none) the attempt runs inline, as before D1-react.
    default Unit installReactExecutor(ReactExecutor executor) {
        return Unit.unit();
    }

    /// RC1 Step 2: tail `MembershipDecision` events from `TopologyObserver` so the
    /// bootstrap module retries its core-partition seeding whenever the membership
    /// changes. The per-node `MembershipFsm` counted-members supplier (plus KV) is the
    /// source for actual seeding work — the subscription is the dirty signal
    /// (read-then-tail).
    @Contract
    void onMembershipDecision(MembershipDecision decision);

    static BootstrapModule bootstrapModule(BooleanSupplier isLeaderSupplier,
                                           Supplier<Epoch> generationEpochSupplier,
                                           Supplier<Option<Long>> leaderEpochSupplier,
                                           HlcClock hlcClock,
                                           Supplier<Set<NodeId>> coreMembersSupplier,
                                           Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                                           Supplier<NodeId> selfSupplier,
                                           Supplier<ClusterConfigBaseline> configBaselineSupplier,
                                           ClusterNode<KVCommand<AetherKey>> cluster) {
        return new BootstrapModuleRecord(isLeaderSupplier,
                                         generationEpochSupplier,
                                         leaderEpochSupplier,
                                         hlcClock,
                                         coreMembersSupplier,
                                         kvSnapshotSupplier,
                                         selfSupplier,
                                         configBaselineSupplier,
                                         Option.some(cluster),
                                         new AtomicBoolean(false),
                                         new AtomicInteger(),
                                         new AtomicReference<>(ReactExecutor.inline("BootstrapModule")));
    }
}

record BootstrapModuleRecord(BooleanSupplier isLeaderSupplier,
                             Supplier<Epoch> generationEpochSupplier,
                             Supplier<Option<Long>> leaderEpochSupplier,
                             HlcClock hlcClock,
                             Supplier<Set<NodeId>> coreMembersSupplier,
                             Supplier<Map<AetherKey, AetherValue>> kvSnapshotSupplier,
                             Supplier<NodeId> selfSupplier,
                             Supplier<BootstrapModule.ClusterConfigBaseline> configBaselineSupplier,
                             Option<ClusterNode<KVCommand<AetherKey>>> cluster,
                             AtomicBoolean bootstrapComplete,
                             AtomicInteger bootstrapAttempts,
                             AtomicReference<ReactExecutor> react) implements BootstrapModule {
    private static final Logger log = LoggerFactory.getLogger(BootstrapModuleRecord.class);
    private static final int SEED_CORE_MIN = 3;
    private static final int SEED_CORE_MAX = 15;

    @Contract
    @Override
    public void onLeaderGained() {
        log.info("BootstrapModule becoming leader — reading core members from membership FSM, then planning bootstrap");
        bootstrapComplete.set(false);
        bootstrapAttempts.set(0);
        performLeaderChangeBootstrap(coreMembersSupplier.get());
    }

    @Contract
    @Override
    public void onLeaderLost() {
        log.info("BootstrapModule stepping down — resetting bootstrap state");
        bootstrapComplete.set(false);
        bootstrapAttempts.set(0);
    }

    @Contract
    @Override
    public void retryIfNeeded() {
        react.get().react("bootstrap-retry", this::retryBootstrapIfNeeded);
    }

    @Override
    public Unit installReactExecutor(ReactExecutor executor) {
        react.set(executor);

        return Unit.unit();
    }

    @Contract
    @Override
    public void onMembershipDecision(MembershipDecision decision) {
        log.debug("BootstrapModule received {}", decision);
        retryIfNeeded();
    }

    @Contract
    private void performLeaderChangeBootstrap(Set<NodeId> coreMembers) {
        cluster.onPresent(clusterNode -> applyLeaderChangeBootstrapBatch(clusterNode, coreMembers));
    }

    @Contract
    private void applyLeaderChangeBootstrapBatch(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                 Set<NodeId> coreMembers) {
        var capturedEpoch = leaderEpochSupplier.get();
        var batch = new ArrayList<KVCommand<AetherKey>>();
        var corePlan = planCoreBootstrap(coreMembers);
        var configPlan = planClusterConfigSeed();

        corePlan.onPresent(plan -> batch.add(plan.command()));
        if (batch.isEmpty() && configPlan.isEmpty()) {
            return;
        }

        if (leaderEpochChanged(capturedEpoch)) {
            log.info("Leader epoch changed during DHT bootstrap planning (captured={}, current={}); skipping partition write — next leader will retry",
                     capturedEpoch,
                     leaderEpochSupplier.get());

            return;
        }
        // #1533: the config seed is a cluster-state write, which a backup-enabled node refuses until its
        // restore decision commits; submitted apart from the runtime core-partition write so that refusal
        // never holds the DHT core bootstrap back. The decision's commit re-drives it via retryIfNeeded.
        configPlan.onPresent(plan -> seedClusterConfig(clusterNode, plan));
        if (batch.isEmpty()) {
            return;
        }

        corePlan.onPresent(this::logCoreBootstrap);
        var hasCore = corePlan.isPresent();
        var provisional = corePlan.filter(CoreBootstrapPlan::provisional).isPresent();

        if (hasCore) {
            bootstrapAttempts.incrementAndGet();
        }

        var commandCount = batch.size();

        clusterNode.apply(List.copyOf(batch))
                   .withFailure(cause -> log.warn("Leader-change bootstrap batch failed ({} commands, attempt {}/{}): {}",
                                                  commandCount,
                                                  bootstrapAttempts.get(),
                                                  BootstrapModule.BOOTSTRAP_MAX_ATTEMPTS,
                                                  cause.message()))
                   .withSuccess(_ -> onLeaderChangeBootstrapCommitted(hasCore, provisional, commandCount));
    }

    private boolean leaderEpochChanged(Option<Long> captured) {
        if (captured.isEmpty()) {
            return false;
        }

        var current = leaderEpochSupplier.get();

        if (current.isEmpty()) {
            return false;
        }

        return ! captured.unwrap()
                         .equals(current.unwrap());
    }

    @Contract
    private void onLeaderChangeBootstrapCommitted(boolean hasCore, boolean provisional, int commandCount) {
        if (hasCore) {
            onCoreBootstrapCommitted(provisional);
        }

        log.info("Leader-change bootstrap committed: {} commands", commandCount);
    }

    @Contract
    private void attemptBootstrap(Set<NodeId> coreMembers) {
        if (bootstrapComplete.get()) {
            return;
        }

        cluster.onPresent(clusterNode -> applyCoreBootstrapRetry(clusterNode, coreMembers));
    }

    @Contract
    private void applyCoreBootstrapRetry(ClusterNode<KVCommand<AetherKey>> clusterNode, Set<NodeId> coreMembers) {
        planCoreBootstrap(coreMembers).onPresent(plan -> writeCoreBootstrap(clusterNode, plan));
    }

    private Option<CoreBootstrapPlan> planCoreBootstrap(Set<NodeId> coreMembers) {
        if (bootstrapComplete.get()) {
            return Option.none();
        }
        // #2654, amended for this site by know 0cced4f7d6: the core partition is runtime state and #1533 never holds it
        // hostage to a blocked restore, which keeps the life's incarnation uncommitted. So while the base is refused (the
        // incarnation is not committed) the core record is minted PROVISIONALLY at incarnation 0 and the bootstrap is NOT
        // complete. The incarnation commit re-drives the plan (`ClusterBootstrapAssembly.onIncarnationCommit` calls
        // retryIfNeeded), which rewrites the provisional record at ownershipTerm + 1 under the committed incarnation, so the
        // record that completes the bootstrap never ranks below the committed life's epochs.
        var epoch = currentEpoch();
        var provisional = epoch.mintable()
                               .isFailure();
        var self = selfSupplier.get();
        var partitions = collectPartitions(kvSnapshotSupplier.get());
        var existing = Option.option(partitions.get(BootstrapModule.CORE_PARTITION_ID));

        return decideCoreOwnership(existing, coreMembers, self, epoch, provisional).map(command -> new CoreBootstrapPlan(command,
                                                                                                                         existing,
                                                                                                                         self,
                                                                                                                         provisional));
    }

    private Option<ClusterConfigSeedPlan> planClusterConfigSeed() {
        var existing = kvSnapshotSupplier.get().get(ClusterConfigKey.CURRENT);

        if (existing instanceof ClusterConfigValue) {
            return Option.none();
        }

        var baseline = configBaselineSupplier.get();
        var coreCount = baseline.coreCount();

        if (coreCount < 3) {
            log.debug("Skipping ClusterConfigValue seed: configured core count {} below quorum minimum", coreCount);

            return Option.none();
        }

        var coreMin = Math.max(SEED_CORE_MIN, baseline.coreMin());
        var coreMax = normalizeCoreMax(coreCount, baseline.coreMax());
        // Seed the cluster name from AETHER_CLUSTER_NAME so the KV-seeded ClusterConfigValue
        // and the env-sourced name agree (Main.verifyClusterNamePresent keys on the same env).
        // Empty remains the last-resort fallback for self-bootstrap paths with no env name.
        var seedClusterName = seedClusterName();
        // The self-bootstrap seed predates any source definition, so the topology entry carries an
        // empty source name. Bootstrap replaces it with the real per-source spec at formation.
        var seedTopology = List.of(new AetherValue.TopologyEntry("", AetherValue.TopologyEntry.CORE_ROLE, coreCount));
        var seed = ClusterConfigValue.bootstrapSeed(seedClusterName,
                                                    "1.0.0",
                                                    seedTopology,
                                                    coreMin,
                                                    coreMax,
                                                    "bootstrap-seed",
                                                    1L);
        KVCommand<AetherKey> command = new KVCommand.Put<AetherKey, AetherValue>(ClusterConfigKey.CURRENT, seed);

        return Option.some(new ClusterConfigSeedPlan(command, coreCount, coreMin, coreMax));
    }

    /// Normalize the seed `coreMax`: a configured `coreMax` of 0 means "unlimited" — fall back to
    /// the `SEED_CORE_MAX` ceiling. The result is clamped to be at least `coreCount` and forced
    /// odd to preserve quorum arithmetic.
    private static int normalizeCoreMax(int coreCount, int configuredCoreMax) {
        var ceiling = configuredCoreMax > 0
                      ? configuredCoreMax
                      : SEED_CORE_MAX;
        var coreMax = Math.max(coreCount, ceiling);

        return coreMax % 2 == 0
               ? coreMax + 1
               : coreMax;
    }

    /// Source the bootstrap-seed cluster name from `AETHER_CLUSTER_NAME` (empty when unset),
    /// so KV and the node-side env gate agree on the cluster identity.
    private static String seedClusterName() {
        return Option.option(System.getenv("AETHER_CLUSTER_NAME"))
                     .filter(s -> !s.isBlank())
                     .or("");
    }

    @Contract
    private void logClusterConfigSeed(ClusterConfigSeedPlan plan) {
        log.info("Seeding ClusterConfigValue with coreCount={}, coreMin={}, coreMax={}",
                 plan.coreCount(),
                 plan.coreMin(),
                 plan.coreMax());
    }

    @Contract
    private void logCoreBootstrap(CoreBootstrapPlan plan) {
        plan.existing()
            .onPresent(owner -> log.info("Rewriting DhtPartitionOwnershipKey(\"core\"): previous owner {} is not a live core member — {} takes over (ownershipTerm {})",
                                         owner.ownerNodeId(),
                                         plan.self(),
                                         owner.ownershipTerm() + 1L))
            .onEmpty(() -> log.info("Bootstrapping DhtPartitionOwnershipKey(\"core\") with owner {} (ownershipTerm 1)",
                                    plan.self()));
    }

    @Contract
    private void retryBootstrapIfNeeded() {
        if (!isLeaderSupplier.getAsBoolean()) {
            return;
        }

        if (!bootstrapComplete.get() && bootstrapAttempts.get() < BootstrapModule.BOOTSTRAP_MAX_ATTEMPTS) {
            attemptBootstrap(coreMembersSupplier.get());
        }

        retryConfigSeedIfNeeded();
    }

    @Contract
    private void retryConfigSeedIfNeeded() {
        cluster.onPresent(clusterNode -> planClusterConfigSeed().onPresent(plan -> seedClusterConfig(clusterNode, plan)));
    }

    @Contract
    private void seedClusterConfig(ClusterNode<KVCommand<AetherKey>> clusterNode, ClusterConfigSeedPlan plan) {
        logClusterConfigSeed(plan);
        clusterNode.apply(List.of(plan.command()))
                   .onFailure(cause -> log.warn("Config seed failed (retried on the next membership or restore-decision event): {}",
                                                cause.message()))
                   .onSuccess(_ -> log.info("Config seed applied"));
    }

    private Option<KVCommand<AetherKey>> decideCoreOwnership(Option<DhtPartitionOwnershipValue> existing,
                                                             Set<NodeId> coreMembers,
                                                             NodeId self,
                                                             Epoch epoch,
                                                             boolean provisional) {
        return existing.fold(() -> Option.some(buildInitialCorePartition(self, epoch)),
                             owner -> provisional
                                      ? rewriteProvisionalIfOwnerStale(owner, coreMembers, self, epoch)
                                      : rewriteIfProvisionalOrStale(owner, coreMembers, self, epoch));
    }

    /// Under an uncommitted incarnation only a provisional record is ever replaced: a record committed under an
    /// incarnation (an earlier life, read before this node observed the commit) waits for the commit, because a takeover at
    /// incarnation 0 would rank below it.
    private Option<KVCommand<AetherKey>> rewriteProvisionalIfOwnerStale(DhtPartitionOwnershipValue owner,
                                                                        Set<NodeId> coreMembers,
                                                                        NodeId self,
                                                                        Epoch epoch) {
        return isProvisional(owner)
               ? rewriteIfOwnerStale(owner, coreMembers, self, epoch)
               : Option.none();
    }

    /// A committed base supersedes a provisional record (minted at incarnation 0 while the incarnation was uncommitted) at
    /// ownershipTerm + 1. Its owner keeps the partition while it is a live core member; a stale one is taken over as before.
    private Option<KVCommand<AetherKey>> rewriteIfProvisionalOrStale(DhtPartitionOwnershipValue owner,
                                                                     Set<NodeId> coreMembers,
                                                                     NodeId self,
                                                                     Epoch epoch) {
        if (shouldRewriteCoreOwnership(owner, coreMembers, self)) {
            return Option.some(buildRewrittenCorePartition(owner, self, epoch));
        }
        return isProvisional(owner)
               ? Option.some(buildCorePartitionCommand(owner.ownerNodeId(), epoch, owner.ownershipTerm() + 1L))
               : Option.none();
    }

    private static boolean isProvisional(DhtPartitionOwnershipValue owner) {
        return owner.ownerEpoch()
                    .mintable()
                    .isFailure();
    }

    private Option<KVCommand<AetherKey>> rewriteIfOwnerStale(DhtPartitionOwnershipValue owner,
                                                             Set<NodeId> coreMembers,
                                                             NodeId self,
                                                             Epoch epoch) {
        return shouldRewriteCoreOwnership(owner, coreMembers, self)
               ? Option.some(buildRewrittenCorePartition(owner, self, epoch))
               : Option.none();
    }

    private static boolean shouldRewriteCoreOwnership(DhtPartitionOwnershipValue owner,
                                                      Set<NodeId> coreMembers,
                                                      NodeId self) {
        var recordedOwner = owner.ownerNodeId();

        if (recordedOwner.equals(self)) {
            return false;
        }
        // Presence-derived (membership-v2 finale): rewrite ownership when the recorded owner is
        // no longer a current core member. Absence from the FSM counted-members set IS the stale
        // signal — there is no synthetic STOPPED/DRAINING lifecycle state to consult anymore.
        return ! coreMembers.contains(recordedOwner);
    }

    private KVCommand<AetherKey> buildInitialCorePartition(NodeId self, Epoch epoch) {
        return buildCorePartitionCommand(self, epoch, 1L);
    }

    private KVCommand<AetherKey> buildRewrittenCorePartition(DhtPartitionOwnershipValue owner,
                                                             NodeId self,
                                                             Epoch epoch) {
        return buildCorePartitionCommand(self, epoch, owner.ownershipTerm() + 1L);
    }

    private Epoch currentEpoch() {
        return generationEpochSupplier.get();
    }

    private KVCommand<AetherKey> buildCorePartitionCommand(NodeId owner, Epoch committedEpoch, long ownershipTerm) {
        var value = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(owner,
                                                                          BootstrapModule.CORE_COMMUNITY_ID,
                                                                          ownerEpoch(committedEpoch, ownershipTerm),
                                                                          ownershipTerm,
                                                                          hlcClock.now());

        return new KVCommand.Put<AetherKey, AetherValue>(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(BootstrapModule.CORE_PARTITION_ID),
                                                         value);
    }

    /// The committed `ownerEpoch` (#345 DHT parity, mirroring the stream writer's
    /// `StreamPartitionOwnershipWriter.ownerEpoch`): the committed generation term (the dominant
    /// component, which advances on a leader re-election / governor handover) paired with the
    /// per-partition `ownershipTerm` as the local counter (which advances on EVERY owner change,
    /// including a same-term stale-owner takeover with no re-election). This couples
    /// `ownerEpoch.localCounter == ownershipTerm`, so a deposed-but-alive core owner — whose
    /// committed epoch carried the OLD `ownershipTerm` — is strictly dominated by its successor's
    /// epoch and is fenced, even when no leader change occurred (the same-term gap that minting
    /// `Epoch.epoch(incarnation, rabiaTerm, 0)` left open). Still a pure function of committed state (committed
    /// generation term + the takeover counter derived from the committed record), so two replicas
    /// presented identical committed state mint the IDENTICAL value. `ownershipTerm` (NOT
    /// `generationCounter`) is the local counter by design: it is committed state, whereas the
    /// generation counter is a per-node time-based value that would break determinism.
    private static Epoch ownerEpoch(Epoch committedEpoch, long ownershipTerm) {
        return committedEpoch.withCounter(ownershipTerm);
    }

    @Contract
    private void writeCoreBootstrap(ClusterNode<KVCommand<AetherKey>> clusterNode, CoreBootstrapPlan plan) {
        var capturedEpoch = leaderEpochSupplier.get();

        if (leaderEpochChanged(capturedEpoch)) {
            log.info("Leader epoch changed during DHT bootstrap retry (captured={}, current={}); skipping partition write",
                     capturedEpoch,
                     leaderEpochSupplier.get());

            return;
        }

        logCoreBootstrap(plan);
        bootstrapAttempts.incrementAndGet();
        clusterNode.apply(List.of(plan.command()))
                   .onFailure(cause -> log.warn("Core DhtPartitionOwnership bootstrap failed (attempt {}/{}): {}",
                                                bootstrapAttempts.get(),
                                                BootstrapModule.BOOTSTRAP_MAX_ATTEMPTS,
                                                cause.message()))
                   .withSuccess(_ -> onCoreBootstrapCommitted(plan.provisional()));
    }

    /// A provisional record owns the partition but does not complete the bootstrap: the incarnation commit must still
    /// supersede it.
    @Contract
    private void onCoreBootstrapCommitted(boolean provisional) {
        if (provisional) {
            log.info("Core DhtPartitionOwnership minted PROVISIONALLY (incarnation not committed); the incarnation commit rewrites it");

            return;
        }

        bootstrapComplete.set(true);
    }

    private static Map<String, DhtPartitionOwnershipValue> collectPartitions(Map<AetherKey, AetherValue> kv) {
        return kv.entrySet()
                 .stream()
                 .filter(entry -> entry.getKey() instanceof DhtPartitionOwnershipKey && entry.getValue() instanceof DhtPartitionOwnershipValue)
                 .collect(Collectors.toUnmodifiableMap(entry -> ((DhtPartitionOwnershipKey) entry.getKey()).partitionId(),
                                                       entry -> (DhtPartitionOwnershipValue) entry.getValue()));
    }

    private record CoreBootstrapPlan(KVCommand<AetherKey> command,
                                     Option<DhtPartitionOwnershipValue> existing,
                                     NodeId self,
                                     boolean provisional) {}

    private record ClusterConfigSeedPlan(KVCommand<AetherKey> command, int coreCount, int coreMin, int coreMax) {}
}
