// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipDecision.NodeDecommissioned;
import org.pragmatica.consensus.topology.MembershipDecision.NodeJoined;
import org.pragmatica.consensus.topology.MembershipDecision.NodeRemoved;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.NodeHealth;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.net.NodeInfo.LABEL_HOSTNAME;
import static org.pragmatica.consensus.net.NodeInfo.LABEL_INSTANCE_TYPE;
import static org.pragmatica.consensus.net.NodeInfo.LABEL_ZONE;


record ClusterTopologyManagerRecord(TopologyObserver observer,
                                    NodeLifecycleManager lifecycleManager,
                                    AutoHealConfig autoHealConfig,
                                    DeploymentMap deploymentMap,
                                    GenerationSnapshotSource snapshotSource,
                                    Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                    Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                    Supplier<Map<ProvisioningSlotKey, ProvisioningSlotValue>> slotReader,
                                    Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                    DrainCoordinator drainCoordinator,
                                    LifecycleWriter lifecycleWriter,
                                    Supplier<ClusterPhase> phaseSupplier,
                                    AtomicReference<NodeReconcilerState> stateRef,
                                    AtomicBoolean active,
                                    ConcurrentHashMap<NodeId, Promise<?>> inFlightProvisions,
                                    ConcurrentHashMap<NodeId, ProvisioningSlotKey> slotKeyByNodeId,
                                    CancellableTask safetyNetTimer,
                                    AtomicLong realActualStableSinceMs,
                                    AtomicInteger lastObservedRealActual,
                                    AtomicInteger lastObservedHealthyOnDutyCount,
                                    AtomicInteger consecutiveProvisioningFailures,
                                    AtomicLong nextProvisioningAllowedMs,
                                    AtomicLong lastProvisioningFailureMs,
                                    AtomicBoolean autoHealEnabled,
                                    LongSupplier clock) implements ClusterTopologyManager {
    private static final Logger log = LoggerFactory.getLogger(ClusterTopologyManager.class);

    private static final int MINIMUM_CLUSTER_SIZE = 3;

    private static final int MAX_WAVE_SIZE = 5;

    private static final int UNINITIALIZED_REAL_ACTUAL = - 1;

    private static final long BOOTSTRAP_GRACE_MS = 60_000L;

    private static final int MAX_CONSECUTIVE_PROVISIONING_FAILURES = 3;

    private static final long PROVISIONING_BACKOFF_BASE_MS = 30_000L;

    private static final long PROVISIONING_BACKOFF_MAX_MS = 300_000L;

    private static final long PROVISIONING_AUTO_RESET_QUIESCENCE_MS = 3_600_000L;

    static ClusterTopologyManagerRecord clusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig config,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                                     Supplier<Map<ProvisioningSlotKey, ProvisioningSlotValue>> slotReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                                     DrainCoordinator drainCoordinator,
                                                                     LifecycleWriter lifecycleWriter,
                                                                     Supplier<ClusterPhase> phaseSupplier,
                                                                     LongSupplier clock) {
        return new ClusterTopologyManagerRecord(observer,
                                                lifecycleManager,
                                                config,
                                                deploymentMap,
                                                snapshotSource,
                                                clusterConfigReader,
                                                lifecycleReader,
                                                slotReader,
                                                commandApplier,
                                                drainCoordinator,
                                                lifecycleWriter,
                                                phaseSupplier,
                                                new AtomicReference<>(new NodeReconcilerState.Inactive("not yet activated")),
                                                new AtomicBoolean(false),
                                                new ConcurrentHashMap<>(),
                                                new ConcurrentHashMap<>(),
                                                CancellableTask.cancellableTask(),
                                                new AtomicLong(clock.getAsLong()),
                                                new AtomicInteger(UNINITIALIZED_REAL_ACTUAL),
                                                new AtomicInteger(UNINITIALIZED_REAL_ACTUAL),
                                                new AtomicInteger(0),
                                                new AtomicLong(0L),
                                                new AtomicLong(0L),
                                                new AtomicBoolean(true),
                                                clock);
    }

    private long nowMs() {
        return clock.getAsLong();
    }

    private Instant nowInstant() {
        return Instant.ofEpochMilli(nowMs());
    }

    @Override public NodeReconcilerState reconcilerState() {
        return stateRef.get();
    }

    @Override public Promise<Unit> setDesiredSize(int size) {
        if (size <MINIMUM_CLUSTER_SIZE) {return Causes.cause("Cluster size cannot be below " + MINIMUM_CLUSTER_SIZE + " (quorum requirement)")
                                                            .promise();}
        resetProvisioningCircuit("setDesiredSize=" + size);
        return writeDesiredCoreCount(size);
    }

    private Promise<Unit> writeDesiredCoreCount(int size) {
        var existing = clusterConfigReader.get();
        if (existing.isEmpty()) {return Causes.cause("ClusterConfigValue atom missing — bootstrap must seed it before scale operations are accepted")
                                                    .promise();}
        var updated = withCoreCount(existing.unwrap(), size);
        @SuppressWarnings("unchecked") var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(ClusterConfigKey.CURRENT,
                                                                                                                                    updated);
        return commandApplier.apply(List.of(command)).onFailure(cause -> log.warn("CTM: failed to write ClusterConfigValue coreCount={}: {}",
                                                                                  size,
                                                                                  cause.message()))
                                   .onSuccess(_ -> log.info("CTM: wrote ClusterConfigValue coreCount={} (configVersion={})",
                                                            size,
                                                            updated.configVersion()))
                                   .mapToUnit();
    }

    private ClusterConfigValue withCoreCount(ClusterConfigValue existing, int newCoreCount) {
        return new ClusterConfigValue(existing.tomlContent(),
                                      existing.clusterName(),
                                      existing.version(),
                                      newCoreCount,
                                      existing.coreMin(),
                                      existing.coreMax(),
                                      existing.deploymentType(),
                                      existing.configVersion() + 1,
                                      nowMs());
    }

    @Override public int desiredSize() {
        return snapshotDesiredCoreSize();
    }

    @Override public int configuredSize() {
        return snapshotDesiredCoreSize();
    }

    private int snapshotDesiredCoreSize() {
        return snapshotSource.currentMembershipView().map(MembershipView::desiredCoreSize)
                                                   .or(0);
    }

    private int snapshotHealthyOnDutyCount() {
        return snapshotSource.currentMembershipView().map(MembershipView::healthyOnDutyCount)
                                                   .or(0);
    }

    @Contract@Override public void onNodeReady(NodeId nodeId) {
        resetProvisioningCircuit("node " + nodeId + " reached ON_DUTY");
        deleteCompletedSlotAtomsForNode(nodeId);
        if (stateRef.get() instanceof NodeReconcilerState.Reconciling) {
            log.info("Node {} reached ON_DUTY, checking reconciliation progress", nodeId);
            reconcile();
        }
    }

    @Contract private void deleteCompletedSlotAtomsForNode(NodeId nodeId) {
        if (!active.get()) {return;}
        var allSlots = slotReader.get();
        if (allSlots.isEmpty()) {return;}
        var deletes = allSlots.entrySet().stream()
                                       .filter(e -> slotIsAssignedAndComplete(e.getValue()) || e.getValue().assignedNodeId()
                                                                                                         .map(nodeId::equals)
                                                                                                         .or(false))
                                       .map(e -> deleteSlotCommand(e.getKey()))
                                       .toList();
        if (deletes.isEmpty()) {return;}
        commandApplier.apply(deletes).onFailure(cause -> log.warn("CTM: failed to delete {} completed slot atom(s) for {}: {}",
                                                                  deletes.size(),
                                                                  nodeId,
                                                                  cause.message()))
                            .onSuccess(_ -> log.debug("CTM: deleted {} completed slot atom(s) on ON_DUTY arrival of {}",
                                                      deletes.size(),
                                                      nodeId));
        slotKeyByNodeId.remove(nodeId);
    }

    @Contract@Override public void onClusterConfigChanged() {
        if (!active.get()) {return;}
        var nowMs = nowMs();
        var windowMs = autoHealConfig.provisionStabilityWindow().millis();
        realActualStableSinceMs.set(nowMs - windowMs);
        log.debug("CTM: ClusterConfigKey changed, bypassing stability gate, triggering immediate reconciliation");
        reconcile();
    }

    @Contract@Override public void onMembershipDecision(MembershipDecision decision) {
        if (!active.get()) {return;}
        switch (decision){
            case NodeJoined joined -> handleNodeJoined(joined);
            case NodeRemoved removed -> handleNodeRemoved(removed);
            case NodeDecommissioned decommissioned -> handleNodeDecommissioned(decommissioned);
            // RC1 Step 2 lifecycle-projection variants: CTM owns provisioning lifecycle
            // through DRAINING / FAILED_DRAIN / SHUTTING_DOWN via internal pending-removal
            // accounting; it does not need the consensus-projected event for these
            // transitions because the slot-state is authoritative locally. JOINING is
            // observed separately via the slot-state ladder.
            case MembershipDecision.NodeJoining _,
                 MembershipDecision.NodeDraining _,
                 MembershipDecision.NodeFailedDrain _,
                 MembershipDecision.NodeShuttingDown _ -> {}
        }
    }

    @Contract@Override public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
        if (!active.get()) {return;}
        log.warn("CTM: Self-shutdown observed for {}, triggering immediate reconciliation", selfShutdown.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("self-shutdown " + selfShutdown.nodeId());
        reconcile();
    }

    @Contract@Override public void onClusterPhaseChanged(ClusterPhase newPhase) {
        if (newPhase == ClusterPhase.NORMAL) {
            cancelInFlightProvisions("phase transition to NORMAL — restart stability window");
            bumpRealActualStability("phase transition to NORMAL");
            resetProvisioningCircuit("phase transition to NORMAL");
            log.info("CTM: cluster phase transitioned to NORMAL — provisioning resumed (stability window restarted from zero)");
            if (active.get()) {reconcile();}
            return;
        }
        cancelInFlightProvisions("phase transition to " + newPhase + " — auto-heal suspended");
        log.info("CTM: cluster phase transitioned to {} — auto-heal suspended (no provisioning, no decommissioning)",
                 newPhase);
    }

    @Contract private void handleNodeJoined(NodeJoined joined) {
        log.info("CTM: Node {} joined, triggering reconciliation", joined.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("node-joined " + joined.nodeId());
        reconcile();
    }

    @Contract private void handleNodeRemoved(NodeRemoved removed) {
        log.info("CTM: Node {} removed, triggering reconciliation", removed.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("node-removed " + removed.nodeId());
        reconcile();
    }

    @Contract private void handleNodeDecommissioned(NodeDecommissioned decommissioned) {
        log.warn("CTM: Node {} decommissioned, triggering immediate reconciliation", decommissioned.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("node-decommissioned " + decommissioned.nodeId());
        reconcile();
    }

    @Contract private void maybeBumpAnchorOnHealthyOnDutyEdge(String reason) {
        var current = snapshotHealthyOnDutyCount();
        var previous = lastObservedHealthyOnDutyCount.getAndSet(current);
        if (previous == current) {
            log.debug("CTM: stability anchor unchanged ({}); count still {}", reason, current);
            return;
        }
        if (previous != UNINITIALIZED_REAL_ACTUAL && current <previous) {
            log.debug("CTM: stability anchor preserved on downward edge ({}); healthyOnDuty {} -> {}",
                      reason,
                      previous,
                      current);
            return;
        }
        var displayPrev = previous == UNINITIALIZED_REAL_ACTUAL
                         ? "<unset>"
                         : Integer.toString(previous);
        bumpRealActualStability(reason + " (healthyOnDuty " + displayPrev + " -> " + current + ")");
    }

    @Contract private void bumpRealActualStability(String reason) {
        var nowMs = nowMs();
        realActualStableSinceMs.set(nowMs);
        log.debug("CTM: stability anchor reset ({}), nowMs={}", reason, nowMs);
    }

    private boolean provisioningCircuitTripped() {
        if (consecutiveProvisioningFailures.get() <MAX_CONSECUTIVE_PROVISIONING_FAILURES) {return false;}
        var lastFailure = lastProvisioningFailureMs.get();
        if (lastFailure > 0L && nowMs() - lastFailure >= PROVISIONING_AUTO_RESET_QUIESCENCE_MS) {
            resetProvisioningCircuit("auto-reset after " + (PROVISIONING_AUTO_RESET_QUIESCENCE_MS / 60_000L) + "min quiescence since last failure");
            return false;
        }
        return true;
    }

    private boolean provisioningBackoffActive(long nowMs) {
        return nowMs <nextProvisioningAllowedMs.get();
    }

    @Contract private void recordProvisioningFailure(String reason) {
        var failures = consecutiveProvisioningFailures.incrementAndGet();
        var nowMs = nowMs();
        var backoffMs = computeProvisioningBackoffMs(failures);
        nextProvisioningAllowedMs.set(nowMs + backoffMs);
        lastProvisioningFailureMs.set(nowMs);
        if (failures >= MAX_CONSECUTIVE_PROVISIONING_FAILURES) {
            log.error("CTM: provisioning circuit breaker tripped — {} consecutive failure(s); reason: {}. Auto-heal halted until successful node arrival, phase NORMAL, or operator setDesiredSize.",
                      failures,
                      reason);
            return;
        }
        log.warn("CTM: provisioning failure {} of {} ({}); next attempt allowed in {}ms",
                 failures,
                 MAX_CONSECUTIVE_PROVISIONING_FAILURES,
                 reason,
                 backoffMs);
    }

    @Contract private void resetProvisioningCircuit(String reason) {
        var prev = consecutiveProvisioningFailures.getAndSet(0);
        nextProvisioningAllowedMs.set(0L);
        if (prev > 0) {log.info("CTM: provisioning circuit breaker reset ({}); cleared {} prior failure(s)",
                                reason,
                                prev);}
    }

    @Override public CircuitBreakerState circuitBreakerState() {
        var failures = consecutiveProvisioningFailures.get();
        return new CircuitBreakerState(failures,
                                       MAX_CONSECUTIVE_PROVISIONING_FAILURES,
                                       nextProvisioningAllowedMs.get(),
                                       failures >= MAX_CONSECUTIVE_PROVISIONING_FAILURES);
    }

    @Override public int resetCircuitBreaker(String reason) {
        var prev = consecutiveProvisioningFailures.get();
        resetProvisioningCircuit("operator: " + reason);
        if (active.get() && stateRef.get() instanceof NodeReconcilerState.Reconciling) {reconcile();}
        return prev;
    }

    @Override public boolean isAutoHealEnabled() {
        return autoHealEnabled.get();
    }

    @Override public boolean setAutoHealEnabled(boolean enabled, String reason) {
        var prev = autoHealEnabled.getAndSet(enabled);
        if (prev == enabled) {
            log.info("CTM: auto-heal already {} (reason: {}) — no-op", enabled
                                                                      ? "enabled"
                                                                      : "disabled", reason);
            return prev;
        }
        log.warn("CTM: auto-heal {} (operator: {}) — prior state was {}",
                 enabled
                 ? "ENABLED"
                 : "DISABLED",
                 reason,
                 prev
                 ? "enabled"
                 : "disabled");
        if (enabled && active.get() && stateRef.get() instanceof NodeReconcilerState.Reconciling) {reconcile();}
        return prev;
    }

    private static long computeProvisioningBackoffMs(int failureCount) {
        if (failureCount <= 0) {return 0L;}
        var shift = Math.min(failureCount - 1, 5);
        var raw = PROVISIONING_BACKOFF_BASE_MS<<shift;
        return Math.min(raw, PROVISIONING_BACKOFF_MAX_MS);
    }

    @Contract@Override public void activate() {
        if (!active.compareAndSet(false, true)) {return;}
        bumpRealActualStability("activate");
        resetProvisioningCircuit("activate (leader handoff)");
        var hadRehydratedSlots = rehydrateInFlightSlotsFromKV();
        if (!hadRehydratedSlots) {activateWithCurrentTopology();}
        scheduleSafetyNetPoll();
    }

    private boolean rehydrateInFlightSlotsFromKV() {
        var nowMs = nowMs();
        var allSlots = slotReader.get();
        if (allSlots.isEmpty()) {return false;}
        var deletes = new ArrayList<KVCommand<AetherKey>>();
        var aliveSlots = new ArrayList<NodeReconcilerState.ProvisioningSlot>();
        allSlots.forEach((key, value) -> classifySlotForRehydration(key, value, nowMs, deletes, aliveSlots));
        if (!deletes.isEmpty()) {commandApplier.apply(deletes).onFailure(cause -> log.warn("CTM: failed to clean up {} stale provisioning slot(s) on rehydrate: {}",
                                                                                           deletes.size(),
                                                                                           cause.message()))
                                                     .onSuccess(_ -> log.info("CTM: cleaned up {} stale provisioning slot(s) on rehydrate",
                                                                              deletes.size()));}
        if (aliveSlots.isEmpty()) {return false;}
        var configured = activationDesiredSize();
        var actual = snapshotHealthyOnDutyCount();
        var reconciling = new NodeReconcilerState.Reconciling(configured > 0
                                                              ? configured
                                                              : actual + aliveSlots.size(),
                                                              actual,
                                                              List.copyOf(aliveSlots),
                                                              List.of(),
                                                              nowInstant());
        stateRef.set(reconciling);
        log.info("CTM: rehydrated {} in-flight provisioning slot(s) from KV after leader handoff", aliveSlots.size());
        return true;
    }

    @Contract private void classifySlotForRehydration(ProvisioningSlotKey key,
                                                      ProvisioningSlotValue value,
                                                      long nowMs,
                                                      List<KVCommand<AetherKey>> deletes,
                                                      List<NodeReconcilerState.ProvisioningSlot> alive) {
        if (slotIsAssignedAndComplete(value)) {
            deletes.add(deleteSlotCommand(key));
            return;
        }
        if (value.deadlineMs() <nowMs) {
            deletes.add(deleteSlotCommand(key));
            return;
        }
        alive.add(new NodeReconcilerState.ProvisioningSlot(value.spawnedAtMs(), value.deadlineMs()));
        value.assignedNodeId().onPresent(nodeId -> slotKeyByNodeId.put(nodeId, key));
    }

    private boolean slotIsAssignedAndComplete(ProvisioningSlotValue value) {
        return value.assignedNodeId().fold(() -> false, this::nodeReachedOnDuty);
    }

    private boolean nodeReachedOnDuty(NodeId nodeId) {
        return lifecycleReader.apply(nodeId).map(lv -> lv.state() == NodeLifecycleState.ON_DUTY)
                                    .or(false);
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> deleteSlotCommand(ProvisioningSlotKey key) {
        return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<AetherKey>(key);
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> putSlotCommand(ProvisioningSlotKey key,
                                                                                      ProvisioningSlotValue value) {
        return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(key, value);
    }

    @Contract private void activateWithCurrentTopology() {
        var actual = observer.healthyActiveNodeCount();
        var desired = activationDesiredSize();
        var readyCount = observer.readyNodeCount();
        var effectiveActual = Math.max(actual, readyCount);
        var clusterWasFormed = readyCount > 0;
        log.info("CTM: Activated, desired={}, active={}, ready={}", desired, actual, readyCount);
        if (desired == 0) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Activated without desiredCoreSize (snapshot not yet projected); awaiting snapshot bump");
            return;
        }
        if (effectiveActual >= desired) {
            transitionTo(new NodeReconcilerState.Converged());
            anchorBootstrapGrace();
            log.info("CTM: Cluster at target size, skipping formation (bootstrap grace {}ms applied)",
                     BOOTSTRAP_GRACE_MS);
        } else if (clusterWasFormed && effectiveActual >= desired - 1) {activateWithLeaderFailover(effectiveActual,
                                                                                                   desired);} else {activateWithFormation();}
    }

    @Contract private void anchorBootstrapGrace() {
        var nowMs = nowMs();
        var windowMs = autoHealConfig.provisionStabilityWindow().millis();
        realActualStableSinceMs.set(nowMs + BOOTSTRAP_GRACE_MS - windowMs);
        log.debug("CTM: bootstrap grace anchored — provisioning gate held closed for {}ms", BOOTSTRAP_GRACE_MS);
    }

    private int activationDesiredSize() {
        var fromSnapshot = snapshotDesiredCoreSize();
        return fromSnapshot > 0
              ? fromSnapshot
              : observer.healthyActiveNodeCount();
    }

    @Contract private void activateWithLeaderFailover(int effectiveActual, int desired) {
        transitionTo(new NodeReconcilerState.Converged());
        log.info("CTM: Leader failover detected ({}/{}), enabling immediate reconciliation", effectiveActual, desired);
        handleDeficit(effectiveActual, desired);
    }

    @Contract private void activateWithFormation() {
        transitionTo(new NodeReconcilerState.Forming(nowInstant()));
        SharedScheduler.schedule(this::checkFormationComplete, autoHealConfig.startupCooldown());
    }

    @Contract@Override public void deactivate() {
        if (!active.compareAndSet(true, false)) {return;}
        cancelSafetyNetPoll();
        transitionTo(new NodeReconcilerState.Inactive("deactivated (not leader)"));
        log.info("CTM: Deactivated");
    }

    @Override public TopologyObserver observer() {
        return observer;
    }

    @Override public NodeInfo self() {
        return observer.self();
    }

    @Override public Option<NodeInfo> get(NodeId id) {
        return observer.get(id);
    }

    @Override public int clusterSize() {
        return observer.clusterSize();
    }

    @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
        return observer.reverseLookup(socketAddress);
    }

    @Override public Promise<Unit> start() {
        return observer.start();
    }

    @Override public Promise<Unit> stop() {
        deactivate();
        return observer.stop();
    }

    @Override public TimeSpan pingInterval() {
        return observer.pingInterval();
    }

    @Override public TimeSpan helloTimeout() {
        return observer.helloTimeout();
    }

    @Override public Option<TlsConfig> tls() {
        return observer.tls();
    }

    @Override public Option<NodeState> getState(NodeId id) {
        return observer.getState(id);
    }

    @Override public List<NodeId> topology() {
        return observer.topology();
    }

    @Contract private void transitionTo(NodeReconcilerState newState) {
        var previous = stateRef.getAndSet(newState);
        log.info("CTM state: {} -> {}",
                 stateName(previous),
                 stateName(newState));
    }

    @Contract private void checkFormationComplete() {
        if (!active.get()) {return;}
        if (! (stateRef.get() instanceof NodeReconcilerState.Forming)) {return;}
        var actual = observer.healthyActiveNodeCount();
        var desired = snapshotDesiredCoreSize();
        if (desired == 0) {return;}
        if (actual >= desired) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster formation complete ({}/{})", actual, desired);
        } else {handleFormationCooldownExpired(actual, desired);}
    }

    @Contract private void handleFormationCooldownExpired(int actual, int desired) {
        log.info("CTM: Formation cooldown expired, cluster at {}/{}, enabling reconciliation", actual, desired);
        transitionTo(new NodeReconcilerState.Converged());
        handleDeficit(actual, desired);
    }

    @Contract private void reconcile() {
        if (!active.get()) {return;}
        if (suspendedByPhase()) {return;}
        var currentState = stateRef.get();
        if (currentState instanceof NodeReconcilerState.Inactive) {return;}
        if (currentState instanceof NodeReconcilerState.Forming) {
            reconcileForming();
            return;
        }
        reconcileActive(currentState);
    }

    private boolean suspendedByPhase() {
        var phase = phaseSupplier.get();
        if (phase == ClusterPhase.NORMAL) {return false;}
        log.debug("CTM: reconcile suspended — cluster phase is {}", phase);
        return true;
    }

    @Contract private void reconcileForming() {
        var actual = observer.healthyActiveNodeCount();
        var configured = snapshotDesiredCoreSize();
        if (configured == 0) {return;}
        if (actual >= configured) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster formation complete ({}/{})", actual, configured);
        }
    }

    @Contract private void reconcileActive(NodeReconcilerState currentState) {
        var snapshot = snapshotSource.currentMembershipView();
        if (snapshot.isEmpty()) {return;}
        var view = snapshot.unwrap();
        var configured = view.desiredCoreSize();
        if (configured == 0) {return;}
        var actual = snapshotHealthyOnDutyCount();
        var deficit = configured - actual;
        log.debug("CTM reconcile: actual={} desired={} deficit={} hints={}",
                  actual,
                  configured,
                  deficit,
                  summarizeHealthHints(view));
        var effectiveState = currentState;
        if (effectiveState instanceof NodeReconcilerState.Reconciling reconciling && reconciling.targetSize() != configured) {
            log.info("CTM: reconcile target changed during Reconciling ({} → {}), resetting to Converged for re-dispatch",
                     reconciling.targetSize(),
                     configured);
            if (configured <actual) {cancelInFlightProvisions("target shrank to " + configured + " during Reconciling");}
            transitionTo(new NodeReconcilerState.Converged());
            effectiveState = stateRef.get();
            observeRealActualForStability(actual);
        }
        if (actual == configured) {
            if (effectiveState instanceof NodeReconcilerState.Converged) {log.debug("CTM converged: actual={} matches desired={}",
                                                                                    actual,
                                                                                    configured);} else {
                log.info("CTM converged: actual={} matches desired={}, transitioning to Converged", actual, configured);
                transitionTo(new NodeReconcilerState.Converged());
                deleteAllSlotAtoms("converged");
            }
            observeRealActualForStability(actual);
            return;
        }
        if (effectiveState instanceof NodeReconcilerState.Converged) {log.info("CTM deficit detected: actual={} desired={} deficit={} hints={}",
                                                                               actual,
                                                                               configured,
                                                                               deficit,
                                                                               summarizeHealthHints(view));}
        if (actual <configured) {handleDeficit(actual, configured);} else {handleSurplus(actual, configured);}
    }

    @Contract private void observeRealActualForStability(int actual) {
        var previous = lastObservedRealActual.getAndSet(actual);
        if (previous == UNINITIALIZED_REAL_ACTUAL) {return;}
        if (previous > actual) {return;}
        if (previous != actual) {bumpRealActualStability("realActual " + previous + " -> " + actual);}
    }

    private List<NodeReconcilerState.ProvisioningSlot> expireSlots(NodeReconcilerState.Reconciling reconciling) {
        var nowMs = nowMs();
        var alive = reconciling.inFlight().stream()
                                        .filter(slot -> slot.deadlineMs() >= nowMs)
                                        .toList();
        if (alive.size() == reconciling.inFlight().size()) {return reconciling.inFlight();}
        deleteExpiredSlotAtoms(nowMs);
        var expiredCount = reconciling.inFlight().size() - alive.size();
        var refreshed = new NodeReconcilerState.Reconciling(reconciling.targetSize(),
                                                            reconciling.currentSize(),
                                                            List.copyOf(alive),
                                                            reconciling.terminating(),
                                                            reconciling.startedAt());
        if (!stateRef.compareAndSet(reconciling, refreshed)) {
            log.debug("CTM: slot expiry CAS lost — observed={}, expected=Reconciling, expired={}",
                      stateRef.get(),
                      expiredCount);
            return alive;
        }
        log.info("CTM: expired {} stalled provisioning slot(s); {} slot(s) still in-flight", expiredCount, alive.size());
        for (var i = 0;i <expiredCount;i++) {recordProvisioningFailure("slot deadline expired (VM did not reach ON_DUTY in time)");}
        return alive;
    }

    private static String summarizeHealthHints(MembershipView view) {
        var coreCount = view.coreMemberIds().size();
        var onDutyCount = view.onDutyMemberIds().size();
        var healthy = view.healthyOnDutyCount();
        var onDutyUnhealthy = onDutyCount - healthy;
        var notOnDuty = coreCount - onDutyCount;
        return "{HEALTHY=" + healthy + ", ON_DUTY_UNHEALTHY=" + onDutyUnhealthy + ", NOT_ON_DUTY=" + notOnDuty + "}";
    }

    private boolean stabilityElapsed(long nowMs) {
        var anchor = realActualStableSinceMs.get();
        var elapsed = nowMs - anchor;
        return elapsed >= autoHealConfig.provisionStabilityWindow().millis();
    }

    @Contract private void handleDeficit(int actual, int desired) {
        if (!autoHealEnabled.get()) {
            log.debug("CTM: auto-heal disabled — skipping replacement provision (actual={}, desired={})",
                      actual,
                      desired);
            return;
        }
        var nowMs = nowMs();
        if (!stabilityElapsed(nowMs)) {
            var elapsed = nowMs - realActualStableSinceMs.get();
            log.info("CTM: stability window not yet elapsed (elapsed={}ms, required={}ms, actual={}, desired={}); deferring provisioning dispatch",
                     elapsed,
                     autoHealConfig.provisionStabilityWindow().millis(),
                     actual,
                     desired);
            return;
        }
        if (provisioningCircuitTripped()) {
            log.info("CTM: provisioning halted by circuit breaker ({} consecutive failures); skipping deficit handling. Reset on successful node arrival, phase NORMAL, or setDesiredSize.",
                     consecutiveProvisioningFailures.get());
            return;
        }
        if (provisioningBackoffActive(nowMs)) {
            log.info("CTM: provisioning backoff active ({}ms remaining after {} failure(s)); deferring dispatch",
                     nextProvisioningAllowedMs.get() - nowMs,
                     consecutiveProvisioningFailures.get());
            return;
        }
        var current = stateRef.get();
        if (current instanceof NodeReconcilerState.Reconciling reconciling) {
            handleDeficitDuringReconciling(reconciling, actual, desired);
            return;
        }
        handleDeficitFromConverged(current, actual, desired);
    }

    @Contract private void handleDeficitDuringReconciling(NodeReconcilerState.Reconciling reconciling,
                                                          int actual,
                                                          int desired) {
        var aliveSlots = expireSlots(reconciling);
        var inFlightCount = aliveSlots.size();
        var topupDeficit = desired - actual - inFlightCount;
        if (topupDeficit <= 0) {
            log.debug("CTM: reconciling wave still in-flight (real={}, inFlight={}, target={}); no top-up needed",
                      actual,
                      inFlightCount,
                      desired);
            return;
        }
        if (!lifecycleManager.isCloudManaged()) {
            log.debug("CTM: top-up deficit of {} but no ComputeProvider, cannot auto-provision", topupDeficit);
            return;
        }
        var batchSize = provisionBatchSize(topupDeficit);
        var current = stateRef.get();
        if (! (current instanceof NodeReconcilerState.Reconciling currentReconciling)) {
            log.debug("CTM: top-up dispatch aborted — state changed to {} during expiry", current);
            return;
        }
        var mergedSlots = mergeSlots(currentReconciling.inFlight(), batchSize);
        var next = new NodeReconcilerState.Reconciling(desired,
                                                       actual,
                                                       mergedSlots,
                                                       currentReconciling.terminating(),
                                                       currentReconciling.startedAt());
        if (!stateRef.compareAndSet(currentReconciling, next)) {
            log.warn("CTM: state CAS lost (deficit, top-up) — observed={}, expected={}, next={}",
                     stateRef.get(),
                     currentReconciling,
                     next);
            return;
        }
        observeRealActualForStability(actual);
        log.info("CTM: deficit={} (real={}, inFlight={}, target={}); provisioning {} more replacement(s)",
                 topupDeficit,
                 actual,
                 inFlightCount,
                 desired,
                 batchSize);
        provisionNodes(batchSize);
    }

    @Contract private void handleDeficitFromConverged(NodeReconcilerState current, int actual, int desired) {
        var deficit = desired - actual;
        if (!lifecycleManager.isCloudManaged()) {
            var next = new NodeReconcilerState.Reconciling(desired, actual, List.of(), List.of(), nowInstant());
            if (!stateRef.compareAndSet(current, next)) {
                log.warn("CTM: state CAS lost (deficit, no-cloud) — observed={}, expected={}, next={}",
                         stateRef.get(),
                         current,
                         next);
                return;
            }
            observeRealActualForStability(actual);
            log.debug("CTM: Cluster deficit of {} but no ComputeProvider, cannot auto-provision", deficit);
            return;
        }
        var batchSize = provisionBatchSize(deficit);
        var next = new NodeReconcilerState.Reconciling(desired,
                                                       actual,
                                                       buildInFlightList(batchSize),
                                                       List.of(),
                                                       nowInstant());
        if (!stateRef.compareAndSet(current, next)) {
            log.warn("CTM: state CAS lost (deficit, provision) — observed={}, expected={}, next={}",
                     stateRef.get(),
                     current,
                     next);
            return;
        }
        observeRealActualForStability(actual);
        log.info("CTM: Cluster at {}/{}, provisioning {} replacement(s)", actual, desired, batchSize);
        provisionNodes(batchSize);
    }

    @Contract private void handleSurplus(int actual, int configured) {
        var current = stateRef.get();
        if (current instanceof NodeReconcilerState.Reconciling) {
            log.debug("CTM: Already reconciling, waiting for in-flight terminations to complete");
            return;
        }
        var surplus = actual - configured;
        if (!lifecycleManager.isCloudManaged()) {
            log.info("CTM: Cluster has {} surplus nodes but no ComputeProvider, cannot auto-terminate", surplus);
            transitionTo(new NodeReconcilerState.Converged());
            return;
        }
        var nodesToTerminate = selectNodesForTermination(surplus);
        if (nodesToTerminate.isEmpty()) {
            log.warn("CTM: {} surplus nodes but no candidates for termination", surplus);
            return;
        }
        var next = new NodeReconcilerState.Reconciling(configured, actual, List.of(), nodesToTerminate, nowInstant());
        if (!stateRef.compareAndSet(current, next)) {
            log.warn("CTM: state CAS lost (surplus, terminate) — observed={}, expected={}, next={}",
                     stateRef.get(),
                     current,
                     next);
            return;
        }
        observeRealActualForStability(actual);
        log.info("CTM: Cluster at {}/{}, terminating {} surplus node(s): {}",
                 actual,
                 configured,
                 nodesToTerminate.size(),
                 nodesToTerminate);
        terminateNodes(nodesToTerminate);
    }

    private List<NodeId> selectNodesForTermination(int count) {
        var selfId = observer.self().id();
        var ctmOwned = ctmProvisionedNodeIds();
        var onDuty = snapshotSource.currentMembershipView().map(MembershipView::onDutyMemberIds)
                                                         .or(Set.of());
        var activeNodes = observer.topology().stream()
                                           .filter(id -> !id.equals(selfId))
                                           .filter(ctmOwned::contains)
                                           .filter(onDuty::contains)
                                           .toList();
        var emptyNodes = snapshotNodesWithoutSlices(activeNodes);
        var hostCounts = buildHostCounts(activeNodes);
        var sortedCandidates = activeNodes.stream().sorted(surplusNodeComparator(emptyNodes, hostCounts, ctmOwned))
                                                 .toList();
        return sortedCandidates.stream().limit(Math.min(count, MAX_WAVE_SIZE))
                                      .toList();
    }

    private Set<NodeId> ctmProvisionedNodeIds() {
        return snapshotSource.currentMembershipView().map(MembershipView::ctmProvisionedNodeIds)
                                                   .or(Set.of());
    }

    private Set<NodeId> snapshotNodesWithoutSlices(List<NodeId> activeNodes) {
        var fromSnapshot = snapshotSource.currentMembershipView().map(MembershipView::nodesWithoutSlices)
                                                               .or(Set.of());
        return activeNodes.stream().filter(fromSnapshot::contains)
                                 .collect(Collectors.toUnmodifiableSet());
    }

    private Map<String, Long> buildHostCounts(List<NodeId> activeNodes) {
        return activeNodes.stream().map(this::hostnameLabel)
                                 .collect(Collectors.groupingBy(h -> h,
                                                                Collectors.counting()));
    }

    private String hostnameLabel(NodeId nodeId) {
        return observer.get(nodeId).map(info -> info.labels().getOrDefault(LABEL_HOSTNAME, ""))
                           .or("");
    }

    private boolean isSpotInstance(NodeId nodeId) {
        return observer.get(nodeId).map(info -> "spot".equals(info.labels().getOrDefault(LABEL_INSTANCE_TYPE, "")))
                           .or(false);
    }

    private long hostCount(NodeId nodeId, Map<String, Long> hostCounts) {
        var hostname = hostnameLabel(nodeId);
        return hostname.isEmpty()
              ? 0L
              : hostCounts.getOrDefault(hostname, 0L);
    }

    private Comparator<NodeId> surplusNodeComparator(Set<NodeId> emptyNodes,
                                                     Map<String, Long> hostCounts,
                                                     Set<NodeId> ctmOwned) {
        return Comparator.<NodeId, Boolean>comparing(id -> !ctmOwned.contains(id))
                         .thenComparing(id -> !isSpotInstance(id))
                         .thenComparing(id -> hostCount(id, hostCounts),
                                        Comparator.reverseOrder())
                         .thenComparing(id -> !emptyNodes.contains(id))
                         .thenComparing(this::nodeJoinEpoch,
                                        Comparator.reverseOrder());
    }

    private Epoch nodeJoinEpoch(NodeId nodeId) {
        return lifecycleReader.apply(nodeId).map(NodeLifecycleValue::observedCoreEpoch)
                                    .or(Epoch.ZERO);
    }

    @Contract private void terminateNodes(List<NodeId> nodes) {
        for (var nodeId : nodes) {terminateSingleNode(nodeId);}
    }

    @Contract private void terminateSingleNode(NodeId nodeId) {
        writeDrainingAtom(nodeId);
        var timeout = autoHealConfig.provisioningTimeout();
        drainCoordinator.prepareDrain(nodeId, DrainReason.SCALE_DOWN).flatMap(_ -> drainCoordinator.awaitDrainAck(nodeId,
                                                                                                                  timeout))
                                     .onResult(result -> handleDrainResult(nodeId, result));
    }

    @Contract private void handleDrainResult(NodeId nodeId, Result<Unit> result) {
        result.onFailure(cause -> log.warn("CTM: drain ack for {} failed/timed out ({}); proceeding to terminate",
                                           nodeId,
                                           cause.message()))
        .onSuccess(_ -> log.debug("CTM: drain ack received for {}", nodeId));
        proceedToTerminate(nodeId);
    }

    @Contract private void proceedToTerminate(NodeId nodeId) {
        lifecycleManager.terminateNode(nodeId).onSuccess(_ -> handleTerminateSuccessWithDrainComplete(nodeId))
                                      .onFailure(cause -> log.warn("CTM: Node {} termination failed: {}",
                                                                   nodeId,
                                                                   cause.message()));
    }

    @Contract private void handleTerminateSuccessWithDrainComplete(NodeId nodeId) {
        drainCoordinator.markDrainComplete(nodeId);
        handleTerminationSuccess(nodeId);
    }

    @Contract private void writeDrainingAtom(NodeId nodeId) {
        lifecycleWriter.requestDrain(nodeId).onFailure(cause -> log.warn("CTM: failed to request DRAINING for {}: {}",
                                                                         nodeId,
                                                                         cause.message()))
                                    .onSuccess(_ -> log.info("CTM: requested DRAINING for {} via LifecycleWriter",
                                                             nodeId));
    }

    @Contract private void handleTerminationSuccess(NodeId nodeId) {
        log.info("CTM: Node {} terminated successfully", nodeId);
        writeDecommissionedAtom(nodeId);
        reconcile();
    }

    @Contract private void writeDecommissionedAtom(NodeId nodeId) {
        lifecycleWriter.requestDecommission(nodeId).onFailure(cause -> log.warn("CTM: failed to request DECOMMISSIONED for {}: {}",
                                                                                nodeId,
                                                                                cause.message()))
                                           .onSuccess(_ -> log.info("CTM: requested DECOMMISSIONED for {} via LifecycleWriter",
                                                                    nodeId));
    }

    @Contract private void provisionNodes(int count) {
        for (var i = 0;i <count;i++) {provisionSingleNode();}
    }

    @Contract private void provisionSingleNode() {
        var context = buildProvisionContext();
        if (context.peers().or("").isEmpty()) {
            log.warn("CTM: provisioning deferred — no healthy peers visible in observed topology (peers list empty). "
                     + "Spawning a new node without PEERS would cold-boot it in isolation and corrupt cluster views; "
                     + "next reconcile tick will retry once at least one peer is HEALTHY.");
            recordProvisioningFailure("no healthy peers visible in topology");
            return;
        }
        var baseSpec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "default", "core", context).unwrap();
        var spec = computePlacementHint().map(baseSpec::withPlacement).or(baseSpec);
        var localTag = NodeId.nodeId("ctm-inflight-" + System.nanoTime() + "-" + Math.abs(spec.hashCode())).unwrap();
        var slotKvKey = ProvisioningSlotKey.provisioningSlotKey(java.util.UUID.randomUUID().toString());
        writeProvisioningSlotAtom(slotKvKey);
        var promise = lifecycleManager.provisionNode(spec).onSuccess(_ -> log.info("CTM: Node provisioning succeeded"))
                                                    .onFailure(cause -> recordProvisioningFailure("API rejection: " + cause.message()));
        inFlightProvisions.put(localTag, promise);
        promise.onResult(_ -> inFlightProvisions.remove(localTag));
    }

    @Contract private void writeProvisioningSlotAtom(ProvisioningSlotKey slotKvKey) {
        var nowMs = nowMs();
        var deadlineMs = nowMs + autoHealConfig.provisioningTimeout().millis();
        var value = ProvisioningSlotValue.provisioningSlotValue(nowMs, deadlineMs);
        commandApplier.apply(List.of(putSlotCommand(slotKvKey, value))).onFailure(cause -> log.warn("CTM: failed to mirror provisioning slot {} to KV: {}",
                                                                                                    slotKvKey.slotId(),
                                                                                                    cause.message()))
                            .onSuccess(_ -> log.debug("CTM: mirrored provisioning slot {} to KV (deadlineMs={})",
                                                      slotKvKey.slotId(),
                                                      deadlineMs));
    }

    @Contract private void deleteExpiredSlotAtoms(long nowMs) {
        var snapshotSlots = slotReader.get();
        if (snapshotSlots.isEmpty()) {return;}
        var deletes = snapshotSlots.entrySet().stream()
                                            .filter(e -> e.getValue().deadlineMs() <nowMs)
                                            .map(e -> deleteSlotCommand(e.getKey()))
                                            .toList();
        if (deletes.isEmpty()) {return;}
        commandApplier.apply(deletes).onFailure(cause -> log.warn("CTM: failed to delete {} expired slot atom(s) from KV: {}",
                                                                  deletes.size(),
                                                                  cause.message()))
                            .onSuccess(_ -> log.debug("CTM: deleted {} expired slot atom(s) from KV",
                                                      deletes.size()));
    }

    @Contract private void cancelInFlightProvisions(String reason) {
        if (inFlightProvisions.isEmpty()) {return;}
        var size = inFlightProvisions.size();
        log.info("CTM: cancelling {} in-flight provision(s) ({})", size, reason);
        inFlightProvisions.values().forEach(Promise::cancel);
        inFlightProvisions.clear();
        deleteAllSlotAtoms("cancel: " + reason);
    }

    @Contract private void deleteAllSlotAtoms(String reason) {
        var allSlots = slotReader.get();
        if (allSlots.isEmpty()) {return;}
        var deletes = allSlots.keySet().stream()
                                     .map(ClusterTopologyManagerRecord::deleteSlotCommand)
                                     .toList();
        commandApplier.apply(deletes).onFailure(cause -> log.warn("CTM: failed to wipe {} slot atom(s) ({}): {}",
                                                                  deletes.size(),
                                                                  reason,
                                                                  cause.message()))
                            .onSuccess(_ -> log.info("CTM: wiped {} slot atom(s) ({})",
                                                     deletes.size(),
                                                     reason));
        slotKeyByNodeId.clear();
    }

    private ProvisionContext buildProvisionContext() {
        // Always include self as a fallback bootstrap target — the CTM runs on the leader, which
        // is alive by definition. Without this fallback, transient "no healthy remote peers"
        // windows during chaos (e.g., a leader has just decommissioned several SWIM-faulty peers
        // and the surviving peer's health entries are still propagating) would yield an empty
        // PEERS list, the new container would cold-boot in isolation, and `DockerComputeProvider.
        // preflightCheck` would defensively reject it. Including self guarantees the replacement
        // can always reach at least one live consensus peer.
        var selfEntry = formatPeerEntry(observer.self());
        var remoteEntries = observer.topology().stream()
                                                 .filter(this::isHealthyPeer)
                                                 .flatMap(nodeId -> observer.get(nodeId).stream())
                                                 .map(ClusterTopologyManagerRecord::formatPeerEntry)
                                                 .filter(entry -> !entry.equals(selfEntry));
        var peers = Stream.concat(Stream.of(selfEntry), remoteEntries)
                                                  .collect(Collectors.joining(","));
        var clusterName = clusterConfigReader.get().map(ClusterConfigValue::clusterName)
                                                 .or("");
        return ProvisionContext.provisionContext(clusterName,
                                                 "core",
                                                 "default",
                                                 Option.empty(),
                                                 Option.some(peers),
                                                 snapshotDesiredCoreSize(),
                                                 ProvisionContext.PROVISIONED_BY_CTM,
                                                 Map.of());
    }

    private boolean isHealthyPeer(NodeId nodeId) {
        return observer.getState(nodeId).map(state -> state.health() == NodeHealth.HEALTHY)
                                .or(false);
    }

    private static String formatPeerEntry(NodeInfo info) {
        var hostname = info.address().host();
        return info.id().id() + ":" + hostname + ":" + info.address().port();
    }

    private Option<PlacementHint> computePlacementHint() {
        var zoneCounts = observer.topology().stream()
                                          .map(this::zoneLabel)
                                          .filter(z -> !z.isEmpty())
                                          .collect(Collectors.groupingBy(z -> z,
                                                                         Collectors.counting()));
        if (zoneCounts.isEmpty()) {return Option.empty();}
        var minCount = zoneCounts.values().stream()
                                        .mapToLong(Long::longValue)
                                        .min()
                                        .orElse(0L);
        var underRepresented = zoneCounts.entrySet().stream()
                                                  .filter(e -> e.getValue() == minCount)
                                                  .map(Map.Entry::getKey)
                                                  .toList();
        if (underRepresented.size() == 1) {return Option.some(PlacementHint.zoneHint(underRepresented.getFirst()));}
        var overRepresented = zoneCounts.entrySet().stream()
                                                 .filter(e -> e.getValue() > minCount)
                                                 .map(Map.Entry::getKey)
                                                 .collect(Collectors.toSet());
        if (overRepresented.isEmpty()) {return Option.empty();}
        return Option.some(PlacementHint.antiAffinityHint(overRepresented));
    }

    private String zoneLabel(NodeId nodeId) {
        return observer.get(nodeId).map(info -> info.labels().getOrDefault(LABEL_ZONE, ""))
                           .or("");
    }

    @Contract private void scheduleSafetyNetPoll() {
        safetyNetTimer.set(SharedScheduler.scheduleAtFixedRate(this::reconcile, autoHealConfig.retryInterval()));
    }

    @Contract private void cancelSafetyNetPoll() {
        safetyNetTimer.cancel();
    }

    private static int provisionBatchSize(int deficit) {
        return switch (deficit){
            case 1 -> 1;
            case 2, 3 -> deficit;
            default -> Math.min(deficit, MAX_WAVE_SIZE);
        };
    }

    private List<NodeReconcilerState.ProvisioningSlot> buildInFlightList(int count) {
        var nowMs = nowMs();
        var deadlineMs = nowMs + autoHealConfig.provisioningTimeout().millis();
        var list = new ArrayList<NodeReconcilerState.ProvisioningSlot>(count);
        for (var i = 0;i <count;i++) {list.add(new NodeReconcilerState.ProvisioningSlot(nowMs, deadlineMs));}
        return List.copyOf(list);
    }

    private List<NodeReconcilerState.ProvisioningSlot> mergeSlots(List<NodeReconcilerState.ProvisioningSlot> existing,
                                                                  int count) {
        var nowMs = nowMs();
        var deadlineMs = nowMs + autoHealConfig.provisioningTimeout().millis();
        var merged = new ArrayList<NodeReconcilerState.ProvisioningSlot>(existing.size() + count);
        merged.addAll(existing);
        for (var i = 0;i <count;i++) {merged.add(new NodeReconcilerState.ProvisioningSlot(nowMs, deadlineMs));}
        return List.copyOf(merged);
    }

    private static String stateName(NodeReconcilerState state) {
        return switch (state){
            case NodeReconcilerState.Inactive inactive -> "Inactive(" + inactive.reason() + ")";
            case NodeReconcilerState.Forming _ -> "Forming";
            case NodeReconcilerState.Converged _ -> "Converged";
            case NodeReconcilerState.Reconciling r -> "Reconciling(" + r.currentSize() + "/" + r.targetSize() + ")";
        };
    }
}
