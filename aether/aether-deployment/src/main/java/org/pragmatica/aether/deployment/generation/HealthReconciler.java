// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CommunitySummary;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationChangedNotice;
import org.pragmatica.aether.slice.generation.GenerationChangedSink;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.generation.OperatorIntent;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.function.UnaryOperator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot.empty;


/// Single-writer reconciler for cluster membership atoms. Leader-only — gated by `isLeader`.
///
/// Consumes `HealthSignal`s, decides which atoms to mutate per the spec §8 decision table,
/// commits them through `ClusterNode.apply(...)` in a single Rabia batch, and bumps the
/// ephemeral generation epoch counter.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §8.
///
/// Dormant in Commit 2 — wiring to bootstrap happens in Commit 4.
///
/// Thread-confinement contract:
///   - `onSignal` / `seedSnapshot` / `start` / `stop` / `reseedMembership` are driven
///     from a single thread at a time (leader-change activator serializes start/stop
///     transitions against signal processing).
///   - `requestReprojection(...)` is the single entry point for re-projection triggered
///     by KV-Store notifications. It is safe to call from arbitrary KV notification
///     threads: a dedicated single-thread executor owned by the reconciler serializes
///     the supplied projection calls and funnels their results into `reseedMembership`.
///     A dirty-bit collapses bursts so the queue cannot grow unbounded.
///
/// Internal maps are `ConcurrentHashMap` for safety against occasional out-of-order
/// reads by introspection APIs (e.g. tests, debug dumps), but the decision pipeline
/// itself is single-writer.
public interface HealthReconciler extends HealthSignalSink {
    int DEFAULT_SUSPECT_INTERVAL_THRESHOLD = 3;

    int DEFAULT_REMOVE_INTERVAL_THRESHOLD = 10;

    int LATE_SIGNAL_WINDOW = 2;

    @Contract void start(Epoch leaderEpoch);
    @Contract void stop(StopReason reason);
    @Contract boolean isActive();
    @Contract void onSignal(HealthSignal signal);
    ClusterGenerationSnapshot currentSnapshot();
    Epoch currentEpoch();
    NodeId self();
    @Contract void seedSnapshot(ClusterGenerationSnapshot snapshot);
    @Contract void reseedMembership(ClusterGenerationSnapshot freshProjection);
    @Contract void requestReprojection(Supplier<ClusterGenerationSnapshot> reprojectionSupplier, String reason);
    long consensusApplyFailedCount();

    @Contract@Override default void emit(HealthSignal signal) {
        onSignal(signal);
    }

    static HealthReconciler healthReconciler(NodeId self,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             ClusterGenerationProjector projector,
                                             HlcClock hlcClock,
                                             Supplier<Long> rabiaTermSupplier,
                                             AtomicBoolean isLeader,
                                             AutoHealConfig autoHealConfig) {
        return healthReconciler(self,
                                cluster,
                                projector,
                                hlcClock,
                                rabiaTermSupplier,
                                isLeader,
                                autoHealConfig,
                                GenerationChangedSink.noop());
    }

    static HealthReconciler healthReconciler(NodeId self,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             ClusterGenerationProjector projector,
                                             HlcClock hlcClock,
                                             Supplier<Long> rabiaTermSupplier,
                                             AtomicBoolean isLeader,
                                             AutoHealConfig autoHealConfig,
                                             GenerationChangedSink generationChangedSink) {
        return new HealthReconcilerRecord(self,
                                          cluster,
                                          projector,
                                          hlcClock,
                                          rabiaTermSupplier,
                                          isLeader,
                                          autoHealConfig,
                                          generationChangedSink,
                                          new AtomicReference<>(empty(rabiaTermSupplier.get())),
                                          new ConcurrentHashMap<>(),
                                          new ConcurrentHashMap<>(),
                                          ConcurrentHashMap.newKeySet(),
                                          new AtomicBoolean(false),
                                          new AtomicReference<>(Epoch.ZERO),
                                          new AtomicLong(),
                                          PeerObservationReducer.peerObservationReducer(),
                                          new AtomicReference<>(),
                                          new AtomicBoolean(false),
                                          new AtomicReference<>(Option.<Supplier<ClusterGenerationSnapshot>>none()));
    }
}

/// Leader-gated reconciler implementation. All membership-affecting atom writes flow through
/// `apply(...)`. When `isLeader.get() == false` every `onSignal` is a no-op.
record HealthReconcilerRecord(NodeId self,
                              ClusterNode<KVCommand<AetherKey>> cluster,
                              ClusterGenerationProjector projector,
                              HlcClock hlcClock,
                              Supplier<Long> rabiaTermSupplier,
                              AtomicBoolean isLeader,
                              AutoHealConfig autoHealConfig,
                              GenerationChangedSink generationChangedSink,
                              AtomicReference<ClusterGenerationSnapshot> snapshotRef,
                              Map<NodeId, Integer> consecutivePingMisses,
                              Map<NodeId, HealthHint> swimHints,
                              Set<NodeId> pendingRemovals,
                              AtomicBoolean started,
                              AtomicReference<Epoch> startEpoch,
                              AtomicLong consensusApplyFailed,
                              PeerObservationReducer peerObservationReducer,
                              AtomicReference<ExecutorService> reprojectionExecutorRef,
                              AtomicBoolean reprojectionDirty,
                              AtomicReference<Option<Supplier<ClusterGenerationSnapshot>>> reprojectionSupplierRef) implements HealthReconciler {
    private static final Logger log = LoggerFactory.getLogger(HealthReconcilerRecord.class);

    private static final String CORE_COMMUNITY_ID = "core";

    @Contract@Override public void start(Epoch leaderEpoch) {
        startEpoch.set(leaderEpoch);
        started.set(true);
        ensureReprojectionExecutor();
        log.debug("HealthReconciler started at epoch {}", leaderEpoch);
    }

    @Contract@Override public void stop(StopReason reason) {
        started.set(false);
        startEpoch.set(Epoch.ZERO);
        consecutivePingMisses.clear();
        swimHints.clear();
        pendingRemovals.clear();
        reprojectionDirty.set(false);
        reprojectionSupplierRef.set(Option.<Supplier<ClusterGenerationSnapshot>>none());
        shutdownReprojectionExecutor();
        log.debug("HealthReconciler stopped (reason={})", reason);
    }

    private void ensureReprojectionExecutor() {
        if (reprojectionExecutorRef.get() != null) {return;}
        var executor = Executors.newSingleThreadExecutor(reprojectionThreadFactory(self));
        if (!reprojectionExecutorRef.compareAndSet(null, executor)) {executor.shutdownNow();}
    }

    private static ThreadFactory reprojectionThreadFactory(NodeId self) {
        return runnable -> {
            var thread = new Thread(runnable, "aether-reconciler-" + self.id());
            thread.setDaemon(true);
            return thread;
        };
    }

    private void shutdownReprojectionExecutor() {
        var executor = reprojectionExecutorRef.getAndSet(null);
        if (executor == null) {return;}
        executor.shutdown();
        awaitReprojectionExecutorTermination(executor);
    }

    @Contract private static void awaitReprojectionExecutorTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {executor.shutdownNow();}
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Contract@Override public boolean isActive() {
        return started.get();
    }

    @Override public ClusterGenerationSnapshot currentSnapshot() {
        return snapshotRef.get();
    }

    @Override public Epoch currentEpoch() {
        return snapshotRef.get().epoch();
    }

    @Contract@Override public void seedSnapshot(ClusterGenerationSnapshot snapshot) {
        snapshotRef.set(snapshot);
    }

    @Contract@Override public void requestReprojection(Supplier<ClusterGenerationSnapshot> reprojectionSupplier,
                                                       String reason) {
        if (reprojectionSupplier == null) {return;}
        if (!isLeader.get() || !started.get()) {return;}
        reprojectionSupplierRef.set(Option.some(reprojectionSupplier));
        reprojectionDirty.set(true);
        var executor = reprojectionExecutorRef.get();
        if (executor == null) {return;}
        submitReprojectionDrain(executor, reason);
    }

    @Contract private void submitReprojectionDrain(ExecutorService executor, String reason) {
        try {
            executor.execute(() -> drainReprojection(reason));
        } catch (RejectedExecutionException _) {
            log.trace("Reprojection request rejected (executor shut down) reason={}", reason);
        }
    }

    @Contract private void drainReprojection(String reason) {
        while (reprojectionDirty.compareAndSet(true, false)) {
            if (!isLeader.get() || !started.get()) {return;}
            var supplier = reprojectionSupplierRef.get();
            supplier.onPresent(fn -> runOneReprojection(fn, reason));
        }
    }

    @Contract private void runOneReprojection(Supplier<ClusterGenerationSnapshot> supplier, String reason) {
        try {
            var fresh = supplier.get();
            if (fresh == null) {return;}
            reseedMembership(fresh);
        } catch (RuntimeException e) {
            log.warn("Reprojection failed (reason={}): {}", reason, e.getMessage());
        }
    }

    @Contract@Override public void reseedMembership(ClusterGenerationSnapshot freshProjection) {
        if (!isLeader.get() || !started.get()) {return;}
        var current = snapshotRef.get();
        if (current.coreMembers().equals(freshProjection.coreMembers()) && current.desiredCoreSize() == freshProjection.desiredCoreSize()) {return;}
        var reason = freshProjection.coreMembers().size() >= current.coreMembers().size()
                    ? GenerationReason.MEMBER_ADDED
                    : GenerationReason.MEMBER_REMOVED;
        updateAndBump(s -> s.withCoreMembers(freshProjection.coreMembers())
                                            .withDesiredCoreSize(freshProjection.desiredCoreSize()),
                      reason);
    }

    @Contract@Override public void onSignal(HealthSignal signal) {
        if (!isLeader.get() || !started.get()) {return;}
        if (isFencedOut(signal)) {return;}
        reconcileLeaderTermIfChanged();
        switch (signal){
            case HealthSignal.PingTimeout ping -> handlePingTimeout(ping);
            case HealthSignal.SwimHint swim -> handleSwimHint(swim);
            case HealthSignal.QuicDisconnect quic -> handleQuicDisconnect(quic);
            case HealthSignal.DrainCompleted drain -> handleDrainCompleted(drain);
            case HealthSignal.GovernorAnnounced announced -> handleGovernorAnnounced(announced);
            case HealthSignal.CommunityDissolved dissolved -> handleCommunityDissolved(dissolved);
            case HealthSignal.SpokesmanAssignmentFailed failed -> handleSpokesmanAssignmentFailed(failed);
            case HealthSignal.OperatorAction action -> handleOperatorAction(action.intent());
            case HealthSignal.RemoteSwimHint remote -> handleRemoteSwimHint(remote);
            case HealthSignal.RemoteConnectivity remote -> handleRemoteConnectivity(remote);
        }
    }

    private boolean isFencedOut(HealthSignal signal) {
        var observedAt = signal.observedAt();
        if (observedAt.equals(Epoch.ZERO)) {return false;}
        var startTerm = startEpoch.get().rabiaTerm();
        if (observedAt.rabiaTerm() <startTerm) {
            log.trace("Dropping pre-leader-change signal {} observedAt={} startEpoch={}",
                      signal.getClass().getSimpleName(),
                      observedAt,
                      startEpoch.get());
            return true;
        }
        var current = snapshotRef.get().epoch();
        if (observedAt.rabiaTerm() == current.rabiaTerm() && observedAt.localCounter() <current.localCounter() - HealthReconciler.LATE_SIGNAL_WINDOW) {
            log.trace("Dropping stale-counter signal {} observedAt={} currentEpoch={}",
                      signal.getClass().getSimpleName(),
                      observedAt,
                      current);
            return true;
        }
        return false;
    }

    @Contract private void handleRemoteSwimHint(HealthSignal.RemoteSwimHint remote) {
        var current = snapshotRef.get();
        if (!current.coreMembers().containsKey(remote.peer())) {return;}
        peerObservationReducer.recordHint(remote.observer(), remote.peer(), remote.hint(), remote.observedAtEpoch());
        var totalObservers = current.coreMembers().size();
        var resolved = peerObservationReducer.resolvedHint(remote.peer(), totalObservers);
        var currentHint = Option.option(current.coreMembers().get(remote.peer())).map(CoreMember::healthHint)
                                       .or(HealthHint.HEALTHY);
        if (resolved == currentHint) {return;}
        handleSwimHint(new HealthSignal.SwimHint(remote.peer(), resolved, remote.observedAtEpoch()));
    }

    @Contract private void handleRemoteConnectivity(HealthSignal.RemoteConnectivity remote) {
        if (!snapshotRef.get().coreMembers()
                            .containsKey(remote.peer())) {return;}
        switch (remote.state()){
            case DISCONNECTED, STALE -> handleQuicDisconnect(new HealthSignal.QuicDisconnect(remote.peer(),
                                                                                             remote.observedAtEpoch()));
            case CONNECTED -> {}
        }
    }

    @Contract private void reconcileLeaderTermIfChanged() {
        var currentTerm = rabiaTermSupplier.get();
        var currentSnapshot = snapshotRef.get();
        if (currentSnapshot.rabiaTerm() <currentTerm) {
            log.info("HealthReconciler detected new Rabia term {} (was {}); resetting to (term,0)",
                     currentTerm,
                     currentSnapshot.rabiaTerm());
            snapshotRef.set(empty(currentTerm));
            consecutivePingMisses.clear();
            swimHints.clear();
            pendingRemovals.clear();
            return;
        }
        pruneMapsAgainstCore(currentSnapshot.coreMembers().keySet());
    }

    private void pruneMapsAgainstCore(Set<NodeId> liveCore) {
        consecutivePingMisses.keySet().retainAll(liveCore);
        swimHints.keySet().retainAll(liveCore);
        pendingRemovals.retainAll(liveCore);
    }

    @Contract private void handlePingTimeout(HealthSignal.PingTimeout ping) {
        var nodeId = ping.nodeId();
        var missed = consecutivePingMisses.merge(nodeId, 1, Integer::sum);
        var current = snapshotRef.get();
        Option.option(current.coreMembers().get(nodeId)).filter(_ -> !pendingRemovals.contains(nodeId))
                     .onPresent(member -> applyPingTimeoutDecision(nodeId, member, missed));
    }

    private void applyPingTimeoutDecision(NodeId nodeId, CoreMember member, int missed) {
        if (shouldEvict(missed, member, nodeId)) {
            evictNode(nodeId, member, GenerationReason.MEMBER_REMOVED);
            return;
        }
        if (shouldMarkSuspected(missed, member)) {markSuspectedInMemory(nodeId);}
    }

    private boolean shouldEvict(int missed, CoreMember member, NodeId nodeId) {
        return missed >= HealthReconciler.DEFAULT_REMOVE_INTERVAL_THRESHOLD && swimHints.getOrDefault(nodeId,
                                                                                                      HealthHint.HEALTHY) == HealthHint.FAULTY && member.lifecycle() == NodeLifecycleState.ON_DUTY;
    }

    private boolean shouldMarkSuspected(int missed, CoreMember member) {
        return missed >= HealthReconciler.DEFAULT_SUSPECT_INTERVAL_THRESHOLD && member.lifecycle() == NodeLifecycleState.ON_DUTY;
    }

    @Contract private void handleSwimHint(HealthSignal.SwimHint swim) {
        swimHints.put(swim.nodeId(), swim.state());
        switch (swim.state()){
            case SUSPECTED, FAULTY -> markSuspectedInMemory(swim.nodeId());
            case HEALTHY -> clearSuspectedInMemory(swim.nodeId());
        }
    }

    @Contract private void handleQuicDisconnect(HealthSignal.QuicDisconnect quic) {
        if (!snapshotRef.get().coreMembers()
                            .containsKey(quic.nodeId())) {return;}
        var missed = consecutivePingMisses.merge(quic.nodeId(), 1, Integer::sum);
        log.debug("QUIC disconnect from {} (counted as advisory miss {})", quic.nodeId(), missed);
    }

    @Contract private void handleDrainCompleted(HealthSignal.DrainCompleted drain) {
        var current = snapshotRef.get();
        Option.option(current.coreMembers().get(drain.nodeId())).filter(member -> member.lifecycle() != NodeLifecycleState.DECOMMISSIONED)
                     .onPresent(member -> performDrainCompletion(drain.nodeId(),
                                                                 member))
                     .onEmpty(() -> log.debug("DrainCompleted({}) ignored — member absent or already decommissioned",
                                              drain.nodeId()));
    }

    private void performDrainCompletion(NodeId nodeId, CoreMember member) {
        log.info("DrainCompleted({}) — writing DECOMMISSIONED via single-writer reconciler", nodeId);
        evictNode(nodeId, member, GenerationReason.MEMBER_REMOVED);
    }

    @Contract private void handleGovernorAnnounced(HealthSignal.GovernorAnnounced announced) {
        var current = snapshotRef.get();
        if (current.communities().containsKey(announced.communityId())) {
            bumpCounter(GenerationReason.HEALTH_CHANGE);
            return;
        }
        assignNewCommunity(announced.communityId());
    }

    @Contract private void handleCommunityDissolved(HealthSignal.CommunityDissolved dissolved) {
        var current = snapshotRef.get();
        Option.option(current.communities().get(dissolved.communityId()))
                     .onPresent(community -> dissolveCommunity(dissolved.communityId(),
                                                               community,
                                                               current));
    }

    private void dissolveCommunity(String communityId, CommunitySummary community, ClusterGenerationSnapshot current) {
        var survivors = coreNodesFromSnapshot(current);
        if (survivors.isEmpty()) {
            log.warn("CommunityDissolved({}) — no surviving core nodes to absorb partitions", communityId);
            return;
        }
        var commands = buildDissolveCommands(communityId, community.partitions(), survivors);
        applyCommandsAndBump(commands, GenerationReason.COMMUNITY_DISSOLVED);
    }

    @Contract private void handleSpokesmanAssignmentFailed(HealthSignal.SpokesmanAssignmentFailed failed) {
        var current = snapshotRef.get();
        var survivors = coreNodesFromSnapshot(current).stream()
                                             .filter(id -> !id.equals(failed.coreNodeId()))
                                             .toList();
        if (survivors.isEmpty()) {
            log.warn("SpokesmanAssignmentFailed({}, {}) — no surviving core nodes",
                     failed.coreNodeId(),
                     failed.affectedCommunities());
            return;
        }
        var commands = buildReassignCommands(failed.coreNodeId(), failed.affectedCommunities(), survivors);
        applyCommandsAndBump(commands, GenerationReason.SPOKESMAN_REBALANCED);
    }

    @Contract private void handleOperatorAction(OperatorIntent intent) {
        switch (intent){
            case OperatorIntent.RemoveMember remove -> operatorRemove(remove.nodeId());
            case OperatorIntent.SetDesiredSize resize -> operatorSetDesiredSize(resize.size());
            case OperatorIntent.DrainMember drain -> operatorDrain(drain.nodeId());
        }
    }

    @Contract private void operatorRemove(NodeId nodeId) {
        Option.option(snapshotRef.get().coreMembers()
                                     .get(nodeId))
        .onPresent(member -> applyOperatorRemoveDecision(nodeId, member));
    }

    private void applyOperatorRemoveDecision(NodeId nodeId, CoreMember member) {
        if (isDrainingOrTerminal(member)) {
            log.debug("operatorRemove({}) ignored — already {} (await DrainCompleted)", nodeId, member.lifecycle());
            return;
        }
        writeDrainingAtom(nodeId, member, GenerationReason.MEMBER_REMOVED);
    }

    private static boolean isDrainingOrTerminal(CoreMember member) {
        var state = member.lifecycle();
        return state == NodeLifecycleState.DRAINING || state == NodeLifecycleState.DECOMMISSIONED || state == NodeLifecycleState.SHUTTING_DOWN;
    }

    @Contract private void operatorSetDesiredSize(int newSize) {
        var current = snapshotRef.get();
        if (current.desiredCoreSize() == newSize) {return;}
        updateAndBump(s -> s.withDesiredCoreSize(newSize), GenerationReason.CLUSTER_SIZE_CHANGED);
    }

    @Contract private void operatorDrain(NodeId nodeId) {
        Option.option(snapshotRef.get().coreMembers()
                                     .get(nodeId))
        .onPresent(member -> applyOperatorDrainDecision(nodeId, member));
    }

    private void applyOperatorDrainDecision(NodeId nodeId, CoreMember member) {
        if (isDrainingOrTerminal(member)) {
            log.debug("operatorDrain({}) ignored — already {}", nodeId, member.lifecycle());
            return;
        }
        writeDrainingAtom(nodeId, member, GenerationReason.HEALTH_CHANGE);
    }

    @Contract private void writeDrainingAtom(NodeId nodeId, CoreMember member, GenerationReason reason) {
        var draining = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING,
                                                             System.currentTimeMillis(),
                                                             member.host(),
                                                             member.port(),
                                                             snapshotRef.get().epoch(),
                                                             hlcClock.now(),
                                                             member.provisioningSource());
        var commands = List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(NodeLifecycleKey.nodeLifecycleKey(nodeId),
                                                                                               draining));
        applyCommandsAndBump(commands, reason);
    }

    @Contract private void markSuspectedInMemory(NodeId nodeId) {
        var current = snapshotRef.get();
        Option.option(current.coreMembers().get(nodeId)).filter(member -> member.healthHint() != HealthHint.SUSPECTED)
                     .onPresent(member -> applyHealthHintChange(current,
                                                                nodeId,
                                                                member.withHealthHint(HealthHint.SUSPECTED)));
    }

    @Contract private void clearSuspectedInMemory(NodeId nodeId) {
        var current = snapshotRef.get();
        Option.option(current.coreMembers().get(nodeId)).filter(member -> member.healthHint() != HealthHint.HEALTHY)
                     .onPresent(member -> applyClearSuspected(current, nodeId, member));
    }

    private void applyClearSuspected(ClusterGenerationSnapshot current, NodeId nodeId, CoreMember member) {
        applyHealthHintChange(current, nodeId, member.withHealthHint(HealthHint.HEALTHY));
        consecutivePingMisses.remove(nodeId);
    }

    private void applyHealthHintChange(ClusterGenerationSnapshot current, NodeId nodeId, CoreMember replacement) {
        var updatedMap = replaceMember(current.coreMembers(), nodeId, replacement);
        updateAndBump(s -> s.withCoreMembers(updatedMap), GenerationReason.HEALTH_CHANGE);
    }

    @Contract private void evictNode(NodeId nodeId, CoreMember member, GenerationReason reason) {
        pendingRemovals.add(nodeId);
        var leftValue = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED,
                                                              System.currentTimeMillis(),
                                                              member.host(),
                                                              member.port(),
                                                              snapshotRef.get().epoch(),
                                                              hlcClock.now(),
                                                              member.provisioningSource());
        var commands = new ArrayList<KVCommand<AetherKey>>();
        commands.add(new KVCommand.Put<AetherKey, AetherValue>(NodeLifecycleKey.nodeLifecycleKey(nodeId), leftValue));
        commands.addAll(handlePartitionsOf(nodeId));
        applyCommandsWithAttemptTracking(commands, reason, Set.of(nodeId));
    }

    private List<KVCommand<AetherKey>> handlePartitionsOf(NodeId departedNode) {
        var current = snapshotRef.get();
        var survivors = coreNodesFromSnapshot(current).stream()
                                             .filter(id -> !id.equals(departedNode))
                                             .toList();
        if (survivors.isEmpty()) {return List.of();}
        var commands = new ArrayList<KVCommand<AetherKey>>();
        var partitionsOwnedByDeparted = current.partitions().values()
                                                          .stream()
                                                          .filter(p -> departedNode.equals(p.ownerNodeId()))
                                                          .toList();
        var pointer = 0;
        for (var partition : partitionsOwnedByDeparted) {
            var target = survivors.get(pointer % survivors.size());
            var value = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(target,
                                                                              CORE_COMMUNITY_ID,
                                                                              current.epoch(),
                                                                              partition.ownershipTerm() + 1,
                                                                              hlcClock.now());
            commands.add(new KVCommand.Put<AetherKey, AetherValue>(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(partition.partitionId()),
                                                                   value));
            pointer++;
        }
        return commands;
    }

    @Contract private void assignNewCommunity(String communityId) {
        var current = snapshotRef.get();
        var survivors = coreNodesFromSnapshot(current);
        if (survivors.isEmpty()) {
            log.warn("GovernorAnnounced({}) — no surviving core nodes; deferring spokesman assignment", communityId);
            return;
        }
        var loads = computeSpokesmanLoad(current);
        var target = selectLeastLoadedCoreNode(survivors, loads);
        var existing = existingSpokesmanCommunities(current, target);
        var value = SpokesmanValue.spokesmanValue(appendIfAbsent(existing, communityId),
                                                  current.epoch(),
                                                  hlcClock.now(),
                                                  loads.getOrDefault(target, 0) + 1L);
        var commands = List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(target),
                                                                                               value));
        applyCommandsAndBump(commands, GenerationReason.COMMUNITY_FORMED);
    }

    private List<KVCommand<AetherKey>> buildDissolveCommands(String communityId,
                                                             Set<String> partitionIds,
                                                             List<NodeId> survivors) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        var current = snapshotRef.get();
        var sortedPartitions = new ArrayList<>(partitionIds);
        sortedPartitions.sort(Comparator.naturalOrder());
        var pointer = 0;
        for (var partitionId : sortedPartitions) {
            var target = survivors.get(pointer % survivors.size());
            var partition = current.partitions().get(partitionId);
            var nextTerm = partition == null
                          ? 1L
                          : partition.ownershipTerm() + 1;
            var value = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(target,
                                                                              CORE_COMMUNITY_ID,
                                                                              current.epoch(),
                                                                              nextTerm,
                                                                              hlcClock.now());
            commands.add(new KVCommand.Put<AetherKey, AetherValue>(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(partitionId),
                                                                   value));
            pointer++;
        }
        commands.addAll(removeCommunityFromAllSpokesmen(communityId));
        return commands;
    }

    private List<KVCommand<AetherKey>> removeCommunityFromAllSpokesmen(String communityId) {
        var current = snapshotRef.get();
        var commands = new ArrayList<KVCommand<AetherKey>>();
        for (var coreNodeId : coreNodesFromSnapshot(current)) {
            var existing = existingSpokesmanCommunities(current, coreNodeId);
            if (!existing.contains(communityId)) {continue;}
            var remaining = existing.stream().filter(id -> !id.equals(communityId))
                                           .toList();
            var value = SpokesmanValue.spokesmanValue(remaining, current.epoch(), hlcClock.now(), 0L);
            commands.add(new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(coreNodeId), value));
        }
        return commands;
    }

    private List<KVCommand<AetherKey>> buildReassignCommands(NodeId failedCoreNode,
                                                             List<String> affectedCommunities,
                                                             List<NodeId> survivors) {
        var current = snapshotRef.get();
        var loads = computeSpokesmanLoad(current);
        var pointer = 0;
        var accumulated = new LinkedHashMap<NodeId, List<String>>();
        for (var communityId : affectedCommunities) {
            var target = survivors.get(pointer % survivors.size());
            var existingForTarget = accumulated.computeIfAbsent(target,
                                                                id -> new ArrayList<>(existingSpokesmanCommunities(current,
                                                                                                                   id)));
            if (!existingForTarget.contains(communityId)) {
                existingForTarget.add(communityId);
                loads.merge(target, 1, Integer::sum);
            }
            pointer++;
        }
        var commands = new ArrayList<KVCommand<AetherKey>>();
        accumulated.forEach((coreNodeId, communities) -> commands.add(spokesmanPutCommand(coreNodeId,
                                                                                          communities,
                                                                                          current.epoch())));
        commands.add(clearFailedFlag(failedCoreNode, current));
        return commands;
    }

    private KVCommand<AetherKey> spokesmanPutCommand(NodeId coreNodeId, List<String> communities, Epoch epoch) {
        var value = SpokesmanValue.spokesmanValue(List.copyOf(communities), epoch, hlcClock.now(), 1L);
        return new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(coreNodeId), value);
    }

    private KVCommand<AetherKey> clearFailedFlag(NodeId failedCoreNode, ClusterGenerationSnapshot current) {
        var reset = SpokesmanValue.spokesmanValue(List.of(),
                                                  current.epoch(),
                                                  hlcClock.now(),
                                                  1L)
        .withStatus(SpokesmanStatus.ASSIGNED);
        return new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(failedCoreNode), reset);
    }

    @Contract private void applyCommandsAndBump(List<KVCommand<AetherKey>> commands, GenerationReason reason) {
        applyCommandsWithAttemptTracking(commands, reason, Set.of());
    }

    @Contract private void applyCommandsWithAttemptTracking(List<KVCommand<AetherKey>> commands,
                                                            GenerationReason reason,
                                                            Set<NodeId> attemptedNodeIds) {
        if (commands.isEmpty()) {
            bumpCounter(reason);
            return;
        }
        cluster.apply(commands).onFailure(cause -> recordConsensusApplyFailure(cause, attemptedNodeIds))
                     .onSuccess(_ -> bumpCounter(reason));
    }

    private void recordConsensusApplyFailure(Cause cause, Set<NodeId> attemptedNodeIds) {
        consensusApplyFailed.incrementAndGet();
        attemptedNodeIds.forEach(pendingRemovals::remove);
        log.warn("HealthReconciler consensus apply failed (attempted nodes={}): {}", attemptedNodeIds, cause.message());
    }

    @Override public long consensusApplyFailedCount() {
        return consensusApplyFailed.get();
    }

    @Contract private void bumpCounter(GenerationReason reason) {
        updateAndBump(UnaryOperator.identity(), reason);
    }

    @Contract private void updateAndBump(UnaryOperator<ClusterGenerationSnapshot> transform, GenerationReason reason) {
        var previous = snapshotRef.get();
        var bumped = snapshotRef.updateAndGet(s -> transform.apply(s).withBumpedCounter(reason));
        generationChangedSink.emit(GenerationChangedNotice.generationChangedNotice(previous.epoch(),
                                                                                   bumped.epoch(),
                                                                                   reason));
    }

    private List<NodeId> coreNodesFromSnapshot(ClusterGenerationSnapshot snapshot) {
        return snapshot.coreMembers().values()
                                   .stream()
                                   .filter(m -> m.lifecycle() != NodeLifecycleState.DECOMMISSIONED)
                                   .map(CoreMember::nodeId)
                                   .sorted()
                                   .toList();
    }

    private static Map<NodeId, CoreMember> replaceMember(Map<NodeId, CoreMember> source,
                                                         NodeId nodeId,
                                                         CoreMember replacement) {
        var copy = new LinkedHashMap<>(source);
        copy.put(nodeId, replacement);
        return Map.copyOf(copy);
    }

    private Map<NodeId, Integer> computeSpokesmanLoad(ClusterGenerationSnapshot snapshot) {
        var loads = new HashMap<NodeId, Integer>();
        coreNodesFromSnapshot(snapshot).forEach(id -> loads.put(id, 0));
        snapshot.communities().values()
                            .forEach(c -> c.assignedSpokesman().onPresent(id -> loads.merge(id, 1, Integer::sum)));
        return loads;
    }

    private NodeId selectLeastLoadedCoreNode(List<NodeId> survivors, Map<NodeId, Integer> loads) {
        return survivors.stream().min(Comparator.<NodeId, Integer>comparing(id -> loads.getOrDefault(id, 0))
                                                .thenComparing(Comparator.naturalOrder()))
                               .orElse(survivors.getFirst());
    }

    private List<String> existingSpokesmanCommunities(ClusterGenerationSnapshot snapshot, NodeId coreNodeId) {
        return snapshot.communities().values()
                                   .stream()
                                   .filter(c -> isAssignedTo(c.assignedSpokesman(),
                                                             coreNodeId))
                                   .map(c -> c.communityId())
                                   .toList();
    }

    private static boolean isAssignedTo(Option<NodeId> assigned, NodeId coreNodeId) {
        return assigned.filter(coreNodeId::equals).isPresent();
    }

    private static List<String> appendIfAbsent(List<String> source, String item) {
        if (source.contains(item)) {return source;}
        var copy = new ArrayList<>(source);
        copy.add(item);
        return List.copyOf(copy);
    }
}
