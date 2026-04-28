// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.NodeHealth;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.NodeState;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.net.NodeInfo.LABEL_HOSTNAME;
import static org.pragmatica.consensus.net.NodeInfo.LABEL_INSTANCE_TYPE;
import static org.pragmatica.consensus.net.NodeInfo.LABEL_ZONE;
import static org.pragmatica.lang.Unit.unit;


@SuppressWarnings("JBCT-RET-01") record ClusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig autoHealConfig,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                                     Supplier<Map<ProvisioningSlotKey, ProvisioningSlotValue>> slotReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                                     DrainCoordinator drainCoordinator,
                                                                     AtomicReference<NodeReconcilerState> stateRef,
                                                                     AtomicBoolean active,
                                                                     ConcurrentHashMap<NodeId, Promise<?>> inFlightProvisions,
                                                                     ConcurrentHashMap<NodeId, ProvisioningSlotKey> slotKeyByNodeId,
                                                                     CancellableTask safetyNetTimer,
                                                                     AtomicLong realActualStableSinceMs,
                                                                     AtomicInteger lastObservedRealActual,
                                                                     LongSupplier clock) implements ClusterTopologyManager {
    private static final Logger log = LoggerFactory.getLogger(ClusterTopologyManager.class);

    private static final int MINIMUM_CLUSTER_SIZE = 3;

    private static final int MAX_WAVE_SIZE = 5;

    private static final int UNINITIALIZED_REAL_ACTUAL = - 1;

    private static final long BOOTSTRAP_GRACE_MS = 60_000L;

    static ClusterTopologyManagerRecord clusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig config,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                                     DrainCoordinator drainCoordinator) {
        return clusterTopologyManagerRecord(observer,
                                            lifecycleManager,
                                            config,
                                            deploymentMap,
                                            snapshotSource,
                                            clusterConfigReader,
                                            lifecycleReader,
                                            Map::of,
                                            commandApplier,
                                            drainCoordinator,
                                            System::currentTimeMillis);
    }

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
                                                new AtomicReference<>(new NodeReconcilerState.Inactive("not yet activated")),
                                                new AtomicBoolean(false),
                                                new ConcurrentHashMap<>(),
                                                new ConcurrentHashMap<>(),
                                                CancellableTask.cancellableTask(),
                                                new AtomicLong(clock.getAsLong()),
                                                new AtomicInteger(UNINITIALIZED_REAL_ACTUAL),
                                                clock);
    }

    static ClusterTopologyManagerRecord clusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig config,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier) {
        return clusterTopologyManagerRecord(observer,
                                            lifecycleManager,
                                            config,
                                            deploymentMap,
                                            snapshotSource,
                                            clusterConfigReader,
                                            lifecycleReader,
                                            commandApplier,
                                            new NoOpDrainCoordinator());
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

    @Override public Result<Unit> setDesiredSize(int size) {
        if (size <MINIMUM_CLUSTER_SIZE) {return Causes.cause("Cluster size cannot be below " + MINIMUM_CLUSTER_SIZE + " (quorum requirement)")
                                                            .result();}
        return writeDesiredCoreCount(size);
    }

    private Result<Unit> writeDesiredCoreCount(int size) {
        var existing = clusterConfigReader.get();
        if (existing.isEmpty()) {return Causes.cause("ClusterConfigValue atom missing — bootstrap must seed it before scale operations are accepted")
                                                    .result();}
        var updated = withCoreCount(existing.unwrap(), size);
        @SuppressWarnings("unchecked") var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(ClusterConfigKey.CURRENT,
                                                                                                                                    updated);
        commandApplier.apply(List.of(command)).onFailure(cause -> log.warn("CTM: failed to write ClusterConfigValue coreCount={}: {}",
                                                                           size,
                                                                           cause.message()))
                            .onSuccess(_ -> log.info("CTM: wrote ClusterConfigValue coreCount={} (configVersion={})",
                                                     size,
                                                     updated.configVersion()));
        return Result.success(unit());
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

    @Override public void onNodeReady(NodeId nodeId) {
        deleteCompletedSlotAtomsForNode(nodeId);
        if (stateRef.get() instanceof NodeReconcilerState.Reconciling) {
            log.info("Node {} reached ON_DUTY, checking reconciliation progress", nodeId);
            reconcile();
        }
    }

    private void deleteCompletedSlotAtomsForNode(NodeId nodeId) {
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

    @SuppressWarnings("JBCT-RET-01") @Override public void onClusterConfigChanged() {
        if (!active.get()) {return;}
        var nowMs = nowMs();
        var windowMs = autoHealConfig.provisionStabilityWindow().millis();
        realActualStableSinceMs.set(nowMs - windowMs);
        log.debug("CTM: ClusterConfigKey changed, bypassing stability gate, triggering immediate reconciliation");
        reconcile();
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onTopologyChange(TopologyChangeNotification topologyChange) {
        if (!active.get()) {return;}
        switch (topologyChange){
            case NodeAdded added -> handleNodeAdded(added);
            case NodeRemoved removed -> handleNodeRemoved(removed);
            case NodeDown down -> handleNodeDown(down);
            default -> {}
        }
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onQuicPeerJoined(NodeId peerId) {
        if (!active.get()) {return;}
        bumpRealActualStability("quic-peer-joined " + peerId);
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onQuicPeerLeft(NodeId peerId) {
        if (!active.get()) {return;}
        // Bump the anchor on peer-left to dampen the pre-existing QUIC reconnection storm:
        // peers can oscillate connected/disconnected once per second after a chaos kill, and
        // without this bump CTM provisions phantom replacements during transient flap (Step F
        // experiment produced ghost container aether-core-node-1-ea4b072c4 that destabilized
        // consensus). The QUIC storm itself is a separate post-RC1 issue.
        bumpRealActualStability("quic-peer-left " + peerId);
    }

    private void handleNodeAdded(NodeAdded added) {
        bumpRealActualStability("node-added " + added.nodeId());
        log.info("CTM: Node {} added, triggering reconciliation", added.nodeId());
        reconcile();
    }

    private void handleNodeRemoved(NodeRemoved removed) {
        bumpRealActualStability("node-removed " + removed.nodeId());
        log.info("CTM: Node {} removed, triggering reconciliation", removed.nodeId());
        reconcile();
    }

    private void handleNodeDown(NodeDown down) {
        bumpRealActualStability("node-down " + down.nodeId());
        log.warn("CTM: Node {} is down, triggering immediate reconciliation", down.nodeId());
        reconcile();
    }

    private void bumpRealActualStability(String reason) {
        var nowMs = nowMs();
        realActualStableSinceMs.set(nowMs);
        log.debug("CTM: stability anchor reset ({}), nowMs={}", reason, nowMs);
    }

    @Override public void activate() {
        if (!active.compareAndSet(false, true)) {return;}
        bumpRealActualStability("activate");
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

    private void classifySlotForRehydration(ProvisioningSlotKey key,
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

    private void activateWithCurrentTopology() {
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

    private void anchorBootstrapGrace() {
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

    private void activateWithLeaderFailover(int effectiveActual, int desired) {
        transitionTo(new NodeReconcilerState.Converged());
        log.info("CTM: Leader failover detected ({}/{}), enabling immediate reconciliation", effectiveActual, desired);
        handleDeficit(effectiveActual, desired);
    }

    private void activateWithFormation() {
        transitionTo(new NodeReconcilerState.Forming(nowInstant()));
        SharedScheduler.schedule(this::checkFormationComplete, autoHealConfig.startupCooldown());
    }

    @Override public void deactivate() {
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

    private void transitionTo(NodeReconcilerState newState) {
        var previous = stateRef.getAndSet(newState);
        log.info("CTM state: {} -> {}",
                 stateName(previous),
                 stateName(newState));
    }

    private void checkFormationComplete() {
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

    private void handleFormationCooldownExpired(int actual, int desired) {
        log.info("CTM: Formation cooldown expired, cluster at {}/{}, enabling reconciliation", actual, desired);
        transitionTo(new NodeReconcilerState.Converged());
        handleDeficit(actual, desired);
    }

    private void reconcile() {
        if (!active.get()) {return;}
        var currentState = stateRef.get();
        if (currentState instanceof NodeReconcilerState.Inactive) {return;}
        if (currentState instanceof NodeReconcilerState.Forming) {
            reconcileForming();
            return;
        }
        reconcileActive(currentState);
    }

    private void reconcileForming() {
        var actual = observer.healthyActiveNodeCount();
        var configured = snapshotDesiredCoreSize();
        if (configured == 0) {return;}
        if (actual >= configured) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster formation complete ({}/{})", actual, configured);
        }
    }

    private void reconcileActive(NodeReconcilerState currentState) {
        var snapshot = snapshotSource.currentMembershipView();
        if (snapshot.isEmpty()) {return;}
        var view = snapshot.unwrap();
        var configured = view.desiredCoreSize();
        if (configured == 0) {return;}
        var actual = snapshotHealthyOnDutyCount();
        observeRealActualForStability(actual);
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
        }
        if (actual == configured) {
            if (effectiveState instanceof NodeReconcilerState.Converged) {log.debug("CTM converged: actual={} matches desired={}",
                                                                                    actual,
                                                                                    configured);} else {
                log.info("CTM converged: actual={} matches desired={}, transitioning to Converged", actual, configured);
                transitionTo(new NodeReconcilerState.Converged());
                deleteAllSlotAtoms("converged");
            }
            return;
        }
        if (effectiveState instanceof NodeReconcilerState.Converged) {log.info("CTM deficit detected: actual={} desired={} deficit={} hints={}",
                                                                               actual,
                                                                               configured,
                                                                               deficit,
                                                                               summarizeHealthHints(view));}
        if (actual <configured) {handleDeficit(actual, configured);} else {handleSurplus(actual, configured);}
    }

    private void observeRealActualForStability(int actual) {
        var previous = lastObservedRealActual.getAndSet(actual);
        if (previous == UNINITIALIZED_REAL_ACTUAL) {return;}
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

    private void handleDeficit(int actual, int desired) {
        var nowMs = nowMs();
        var quicLive = observer.healthyActiveNodeCount();
        if (quicLive >= desired) {
            log.info("CTM: snapshot-derived deficit (actual={}, desired={}) but QUIC observer reports {} live peers — suppressing provisioning",
                     actual,
                     desired,
                     quicLive);
            return;
        }
        if (!stabilityElapsed(nowMs)) {
            var elapsed = nowMs - realActualStableSinceMs.get();
            log.info("CTM: stability window not yet elapsed (elapsed={}ms, required={}ms, actual={}, desired={}); deferring provisioning dispatch",
                     elapsed,
                     autoHealConfig.provisionStabilityWindow().millis(),
                     actual,
                     desired);
            return;
        }
        var current = stateRef.get();
        if (current instanceof NodeReconcilerState.Reconciling reconciling) {
            handleDeficitDuringReconciling(reconciling, actual, desired);
            return;
        }
        handleDeficitFromConverged(current, actual, desired);
    }

    private void handleDeficitDuringReconciling(NodeReconcilerState.Reconciling reconciling, int actual, int desired) {
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
        log.info("CTM: deficit={} (real={}, inFlight={}, target={}); provisioning {} more replacement(s)",
                 topupDeficit,
                 actual,
                 inFlightCount,
                 desired,
                 batchSize);
        provisionNodes(batchSize);
    }

    private void handleDeficitFromConverged(NodeReconcilerState current, int actual, int desired) {
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
        log.info("CTM: Cluster at {}/{}, provisioning {} replacement(s)", actual, desired, batchSize);
        provisionNodes(batchSize);
    }

    private void handleSurplus(int actual, int configured) {
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

    private void terminateNodes(List<NodeId> nodes) {
        for (var nodeId : nodes) {terminateSingleNode(nodeId);}
    }

    private void terminateSingleNode(NodeId nodeId) {
        writeDrainingAtom(nodeId);
        var timeout = autoHealConfig.provisioningTimeout();
        drainCoordinator.prepareDrain(nodeId, DrainReason.SCALE_DOWN).flatMap(_ -> drainCoordinator.awaitDrainAck(nodeId,
                                                                                                                  timeout))
                                     .onResult(result -> handleDrainResult(nodeId, result));
    }

    private void handleDrainResult(NodeId nodeId, Result<Unit> result) {
        result.onFailure(cause -> log.warn("CTM: drain ack for {} failed/timed out ({}); proceeding to terminate",
                                           nodeId,
                                           cause.message()))
        .onSuccess(_ -> log.debug("CTM: drain ack received for {}", nodeId));
        proceedToTerminate(nodeId);
    }

    private void proceedToTerminate(NodeId nodeId) {
        lifecycleManager.terminateNode(nodeId).onSuccess(_ -> handleTerminateSuccessWithDrainComplete(nodeId))
                                      .onFailure(cause -> log.warn("CTM: Node {} termination failed: {}",
                                                                   nodeId,
                                                                   cause.message()));
    }

    private void handleTerminateSuccessWithDrainComplete(NodeId nodeId) {
        drainCoordinator.markDrainComplete(nodeId);
        handleTerminationSuccess(nodeId);
    }

    private void writeDrainingAtom(NodeId nodeId) {
        var prior = lifecycleReader.apply(nodeId);
        var value = buildDrainingAtom(nodeId, prior);
        @SuppressWarnings("unchecked") var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(NodeLifecycleKey.nodeLifecycleKey(nodeId),
                                                                                                                                    value);
        commandApplier.apply(List.of(command)).onFailure(cause -> log.warn("CTM: failed to write DRAINING atom for {}: {}",
                                                                           nodeId,
                                                                           cause.message()))
                            .onSuccess(_ -> log.info("CTM: wrote DRAINING atom for {} ({}:{}, epoch={}, source={})",
                                                     nodeId,
                                                     value.host(),
                                                     value.port(),
                                                     value.observedCoreEpoch(),
                                                     value.provisioningSource()));
    }

    private NodeLifecycleValue buildDrainingAtom(NodeId nodeId, Option<NodeLifecycleValue> prior) {
        if (prior.isEmpty()) {
            log.warn("CTM: no prior NodeLifecycleValue for {} when writing DRAINING — falling back to topology observer for host/port",
                     nodeId);
            var info = observer.get(nodeId);
            var host = info.map(i -> i.address().host()).or("");
            var port = info.map(i -> i.address().port()).or(0);
            return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, host, port, Epoch.ZERO);
        }
        var p = prior.unwrap();
        return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING,
                                                     nowMs(),
                                                     p.host(),
                                                     p.port(),
                                                     p.observedCoreEpoch(),
                                                     p.transitionedAt(),
                                                     p.provisioningSource());
    }

    private void handleTerminationSuccess(NodeId nodeId) {
        log.info("CTM: Node {} terminated successfully", nodeId);
        writeDecommissionedAtom(nodeId);
        reconcile();
    }

    private void writeDecommissionedAtom(NodeId nodeId) {
        var prior = lifecycleReader.apply(nodeId);
        var value = buildDecommissionedAtom(nodeId, prior);
        @SuppressWarnings("unchecked") var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(NodeLifecycleKey.nodeLifecycleKey(nodeId),
                                                                                                                                    value);
        commandApplier.apply(List.of(command)).onFailure(cause -> log.warn("CTM: failed to write DECOMMISSIONED atom for {}: {}",
                                                                           nodeId,
                                                                           cause.message()))
                            .onSuccess(_ -> log.info("CTM: wrote DECOMMISSIONED atom for {} ({}:{}, epoch={}, source={})",
                                                     nodeId,
                                                     value.host(),
                                                     value.port(),
                                                     value.observedCoreEpoch(),
                                                     value.provisioningSource()));
    }

    private NodeLifecycleValue buildDecommissionedAtom(NodeId nodeId, Option<NodeLifecycleValue> prior) {
        if (prior.isEmpty()) {
            log.warn("CTM: no prior NodeLifecycleValue for {} when writing DECOMMISSIONED — writing default empty metadata (defensive fallback)",
                     nodeId);
            return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED,
                                                         "",
                                                         0,
                                                         ProvisioningSource.CTM);
        }
        var p = prior.unwrap();
        return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED,
                                                     nowMs(),
                                                     p.host(),
                                                     p.port(),
                                                     p.observedCoreEpoch(),
                                                     p.transitionedAt(),
                                                     p.provisioningSource());
    }

    private void provisionNodes(int count) {
        for (var i = 0;i <count;i++) {provisionSingleNode();}
    }

    private void provisionSingleNode() {
        var baseSpec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                                   "default",
                                                   "core",
                                                   buildProvisionTags())
        .unwrap();
        var spec = computePlacementHint().map(baseSpec::withPlacement).or(baseSpec);
        var localTag = NodeId.nodeId("ctm-inflight-" + System.nanoTime() + "-" + Math.abs(spec.hashCode())).unwrap();
        var slotKvKey = ProvisioningSlotKey.provisioningSlotKey(java.util.UUID.randomUUID().toString());
        writeProvisioningSlotAtom(slotKvKey);
        var promise = lifecycleManager.provisionNode(spec).onSuccess(_ -> log.info("CTM: Node provisioning succeeded"))
                                                    .onFailure(cause -> log.warn("CTM: Node provisioning failed: {}",
                                                                                 cause.message()));
        inFlightProvisions.put(localTag, promise);
        promise.onResult(_ -> inFlightProvisions.remove(localTag));
    }

    private void writeProvisioningSlotAtom(ProvisioningSlotKey slotKvKey) {
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

    private void deleteExpiredSlotAtoms(long nowMs) {
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

    private void cancelInFlightProvisions(String reason) {
        if (inFlightProvisions.isEmpty()) {return;}
        var size = inFlightProvisions.size();
        log.info("CTM: cancelling {} in-flight provision(s) ({})", size, reason);
        inFlightProvisions.values().forEach(Promise::cancel);
        inFlightProvisions.clear();
        deleteAllSlotAtoms("cancel: " + reason);
    }

    private void deleteAllSlotAtoms(String reason) {
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

    private Map<String, String> buildProvisionTags() {
        var peers = observer.topology().stream()
                                     .filter(this::isHealthyPeer)
                                     .flatMap(nodeId -> observer.get(nodeId).stream())
                                     .map(ClusterTopologyManagerRecord::formatPeerEntry)
                                     .collect(Collectors.joining(","));
        return Map.of("aether.peers",
                      peers,
                      "aether.core-max",
                      String.valueOf(snapshotDesiredCoreSize()),
                      "aether.provisioned-by",
                      "ctm");
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

    private void scheduleSafetyNetPoll() {
        safetyNetTimer.set(SharedScheduler.scheduleAtFixedRate(this::reconcile, autoHealConfig.retryInterval()));
    }

    private void cancelSafetyNetPoll() {
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
