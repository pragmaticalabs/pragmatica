// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceInfo;
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
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
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
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
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
import java.util.function.BooleanSupplier;
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
                                    BooleanSupplier inQuorum,
                                    AtomicReference<NodeReconcilerState> stateRef,
                                    AtomicBoolean active,
                                    ConcurrentHashMap<NodeId, Promise<?>> inFlightProvisions,
                                    ConcurrentHashMap<NodeId, ProvisioningSlotKey> slotKeyByNodeId,
                                    ConcurrentHashMap<Integer, Long> inFlightSlotIndices,
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

    /// Slot-occupancy classification (slot-based-membership-convergence-spec §5.1). Each of the
    /// `clusterSize` durable slots resolves to exactly one of these against the current
    /// `MembershipView` + lifecycle KV.
    private enum SlotOccupancy {
        /// Occupant present, lifecycle ON_DUTY, healthHint HEALTHY.
        HEALTHY,
        /// Occupant present and JOINING, OR a provision is in flight (FILLING marker live, no occupant yet).
        FILLING,
        /// Occupant present but lifecycle STOPPED / detected-dead.
        DEAD,
        /// No occupant and no live in-flight provision.
        EMPTY
    }

    /// A durable slot keyed by stable integer index `0..S-1` together with its current KV value.
    private record IndexedSlot(int index, ProvisioningSlotKey key, ProvisioningSlotValue value) {}
    private static final int UNINITIALIZED_REAL_ACTUAL = -1;
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
                                                                     BooleanSupplier inQuorum,
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
                                                inQuorum,
                                                new AtomicReference<>(new NodeReconcilerState.Inactive("not yet activated")),
                                                new AtomicBoolean(false),
                                                new ConcurrentHashMap<>(),
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

    @Override
    public NodeReconcilerState reconcilerState() {
        return stateRef.get();
    }

    @Override
    public Promise<Unit> setDesiredSize(int size) {
        if (size <MINIMUM_CLUSTER_SIZE) {
            return Causes.cause("Cluster size cannot be below " + MINIMUM_CLUSTER_SIZE + " (quorum requirement)").promise();
        }

        resetProvisioningCircuit("setDesiredSize=" + size);

        return writeDesiredCoreCount(size);
    }

    private Promise<Unit> writeDesiredCoreCount(int size) {
        var existing = clusterConfigReader.get();

        if (existing.isEmpty()) {
            return Causes.cause("ClusterConfigValue atom missing — bootstrap must seed it before scale operations are accepted").promise();
        }

        var updated = withCoreCount(existing.unwrap(), size);
        @SuppressWarnings("unchecked")
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(ClusterConfigKey.CURRENT,
                                                                                                     updated);

        return commandApplier.apply(List.of(command))
                             .onFailure(cause -> log.warn("CTM: failed to write ClusterConfigValue coreCount={}: {}",
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

    @Override
    public int desiredSize() {
        return snapshotDesiredCoreSize();
    }

    @Override
    public int configuredSize() {
        return snapshotDesiredCoreSize();
    }

    private int snapshotDesiredCoreSize() {
        return snapshotSource.currentMembershipView()
                             .map(MembershipView::desiredCoreSize)
                             .or(0);
    }

    private int snapshotHealthyOnDutyCount() {
        // Fall back to configured cluster size when no generation snapshot exists yet
        // (cold start / leader just elected before first snapshot is published).
        return snapshotSource.currentMembershipView()
                             .map(MembershipView::healthyOnDutyCount)
                             .or(observer.clusterSize());
    }

    // ---------------------------------------------------------------------------------------
    // Slot-occupancy model (slot-based-membership-convergence-spec D1 §3.1, §5.1).
    // The durable slot set is keyed by stable integer index "0".."S-1". CTM is the single
    // writer; it addresses "slot N" idempotently across waves and leader handovers.
    // ---------------------------------------------------------------------------------------

    private static ProvisioningSlotKey slotKeyForIndex(int index) {
        return ProvisioningSlotKey.provisioningSlotKey(Integer.toString(index));
    }

    private static Option<Integer> slotIndexOf(ProvisioningSlotKey key) {
        return parseSlotIndex(key.slotId());
    }

    private static Option<Integer> parseSlotIndex(String slotId) {
        return Number.parseInt(slotId).option().filter(i -> i >= 0);
    }

    /// Reads the durable slot map from KV and projects it onto stable integer indices. Legacy
    /// UUID-keyed slots (slotId not parseable as a non-negative integer) are ignored here — they
    /// are wiped by the activation reseed (§7.3). Returns slots ordered by ascending index.
    private List<IndexedSlot> indexedSlots() {
        return slotReader.get()
                         .entrySet()
                         .stream()
                         .flatMap(entry -> slotIndexOf(entry.getKey())
                                                   .map(index -> new IndexedSlot(index, entry.getKey(), entry.getValue()))
                                                   .stream())
                         .sorted(Comparator.comparingInt(IndexedSlot::index))
                         .toList();
    }

    /// Classifies a slot against the lifecycle KV (DECIDE plane). The occupant's committed
    /// lifecycle state is the single source of truth (§5.1, OQ6) — NOT the SWIM-derived
    /// `view.onDutyMemberIds()` (SENSE plane), which lags/differs from the sovereign-FSM KV.
    private SlotOccupancy classifyOccupancy(ProvisioningSlotValue slot, MembershipView view, long nowMs) {
        return slot.assignedNodeId()
                   .fold(() -> classifyEmptyOrFilling(slot, nowMs),
                         this::classifyOccupied);
    }

    private SlotOccupancy classifyEmptyOrFilling(ProvisioningSlotValue slot, long nowMs) {
        return slot.deadlineMs() >= nowMs && slot.spawnedAtMs() > 0L
               ? SlotOccupancy.FILLING
               : SlotOccupancy.EMPTY;
    }

    /// Classifies an OCCUPIED slot on the DECIDE plane (`lifecycleReader` — the sovereign-FSM
    /// committed lifecycle, the SAME source `occupantStopped` reads). CTM is an actuator on the
    /// FSM's committed lifecycle, NOT a SENSE-plane reader: an occupant that the FSM committed
    /// ON_DUTY is HEALTHY even while it still lags the SWIM-derived `onDutyMemberIds()`. Mapping:
    /// `STOPPED→DEAD`, `ON_DUTY→HEALTHY`, `JOINING/DRAINING→FILLING`, absent→FILLING (occupied,
    /// awaiting terminal). A genuinely stuck occupant is reclaimed by the FILLING-marker deadline
    /// (provision expiry → reset to EMPTY), never by CTM deciding liveness.
    private SlotOccupancy classifyOccupied(NodeId occupant) {
        return lifecycleReader.apply(occupant)
                              .map(lv -> classifyLifecycleState(lv.state()))
                              .or(SlotOccupancy.FILLING);
    }

    private static SlotOccupancy classifyLifecycleState(NodeLifecycleState state) {
        return switch (state) {
            case STOPPED -> SlotOccupancy.DEAD;
            case ON_DUTY -> SlotOccupancy.HEALTHY;
            case JOINING, DRAINING -> SlotOccupancy.FILLING;
        };
    }

    private boolean occupantStopped(NodeId occupant) {
        return lifecycleReader.apply(occupant)
                              .map(lv -> lv.state() == NodeLifecycleState.STOPPED)
                              .or(false);
    }

    @Contract
    @Override
    public void onNodeReady(NodeId nodeId) {
        resetProvisioningCircuit("node " + nodeId + " reached ON_DUTY");
        // Durable slots (D1): the slot is NOT deleted when its occupant reaches ON_DUTY — it
        // simply reclassifies HEALTHY. The next reconcile observes the filled slot.
        if (stateRef.get() instanceof NodeReconcilerState.Reconciling) {
            log.info("Node {} reached ON_DUTY, checking reconciliation progress", nodeId);
            reconcile();
        }
    }

    @Contract
    @Override
    public void onClusterConfigChanged() {
        if (!active.get()) {return;}

        var nowMs = nowMs();
        var windowMs = autoHealConfig.provisionStabilityWindow().millis();
        realActualStableSinceMs.set(nowMs - windowMs);
        log.debug("CTM: ClusterConfigKey changed, bypassing stability gate, triggering immediate reconciliation");
        reconcile();
    }

    @Contract
    @Override
    public void onMembershipDecision(MembershipDecision decision) {
        if (!active.get()) {return;}
        switch (decision) {
            case NodeJoined joined -> handleNodeJoined(joined);
            case NodeRemoved removed -> handleNodeRemoved(removed);
            case NodeDecommissioned decommissioned -> handleNodeDecommissioned(decommissioned);
            case MembershipDecision.NodeJoining _, MembershipDecision.NodeDraining _, MembershipDecision.NodeFailedDrain _, MembershipDecision.NodeShuttingDown _ -> {}
        }
    }

    @Contract
    @Override
    public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
        if (!active.get()) {return;}

        log.warn("CTM: Self-shutdown observed for {}, triggering immediate reconciliation", selfShutdown.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("self-shutdown " + selfShutdown.nodeId());
        reconcile();
    }

    @Contract
    @Override
    public void onClusterPhaseChanged(ClusterPhase newPhase) {
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

    @Contract
    private void handleNodeJoined(NodeJoined joined) {
        log.info("CTM: Node {} joined, triggering reconciliation", joined.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("node-joined " + joined.nodeId());
        reconcile();
    }

    @Contract
    private void handleNodeRemoved(NodeRemoved removed) {
        log.info("CTM: Node {} removed, triggering reconciliation", removed.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("node-removed " + removed.nodeId());
        reconcile();
    }

    @Contract
    private void handleNodeDecommissioned(NodeDecommissioned decommissioned) {
        log.warn("CTM: Node {} decommissioned, triggering immediate reconciliation", decommissioned.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("node-decommissioned " + decommissioned.nodeId());
        reconcile();
    }

    @Contract
    private void maybeBumpAnchorOnHealthyOnDutyEdge(String reason) {
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

    @Contract
    private void bumpRealActualStability(String reason) {
        var nowMs = nowMs();
        realActualStableSinceMs.set(nowMs);
        log.debug("CTM: stability anchor reset ({}), nowMs={}", reason, nowMs);
    }

    private boolean provisioningCircuitTripped() {
        if (consecutiveProvisioningFailures.get() <MAX_CONSECUTIVE_PROVISIONING_FAILURES) {return false;}

        var lastFailure = lastProvisioningFailureMs.get();

        if (lastFailure > 0L && nowMs() - lastFailure >= PROVISIONING_AUTO_RESET_QUIESCENCE_MS) {
            resetProvisioningCircuit("auto-reset after " + (PROVISIONING_AUTO_RESET_QUIESCENCE_MS / 60_000L)
                                    + "min quiescence since last failure");
            return false;
        }

        return true;
    }

    private boolean provisioningBackoffActive(long nowMs) {
        return nowMs <nextProvisioningAllowedMs.get();
    }

    @Contract
    private void recordProvisioningFailure(String reason) {
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

    @Contract
    private void resetProvisioningCircuit(String reason) {
        var prev = consecutiveProvisioningFailures.getAndSet(0);
        nextProvisioningAllowedMs.set(0L);
        var clusterName = clusterConfigReader.get().map(ClusterConfigValue::clusterName).or("");
        lifecycleManager.resetProvisionerState(clusterName);

        if (prev > 0) {
            log.info("CTM: provisioning circuit breaker reset ({}); cleared {} prior failure(s)", reason, prev);
        }
    }

    @Override
    public CircuitBreakerState circuitBreakerState() {
        var failures = consecutiveProvisioningFailures.get();

        return new CircuitBreakerState(failures,
                                       MAX_CONSECUTIVE_PROVISIONING_FAILURES,
                                       nextProvisioningAllowedMs.get(),
                                       failures >= MAX_CONSECUTIVE_PROVISIONING_FAILURES);
    }

    @Override
    public int resetCircuitBreaker(String reason) {
        var prev = consecutiveProvisioningFailures.get();
        resetProvisioningCircuit("operator: " + reason);

        if (active.get() && stateRef.get() instanceof NodeReconcilerState.Reconciling) {reconcile();}

        return prev;
    }

    @Override
    public boolean isAutoHealEnabled() {
        return autoHealEnabled.get();
    }

    @Override
    public boolean setAutoHealEnabled(boolean enabled, String reason) {
        var prev = autoHealEnabled.getAndSet(enabled);

        if (prev == enabled) {
            log.info("CTM: auto-heal already {} (reason: {}) — no-op",
                     enabled
                     ? "enabled"
                     : "disabled",
                     reason);
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

    @Contract
    @Override
    public void activate() {
        if (!active.compareAndSet(false, true)) {return;}

        bumpRealActualStability("activate");
        resetProvisioningCircuit("activate (leader handoff)");
        // Establish the reconciler state (Forming/Converged) FIRST so the activation reconcile —
        // chained onto the reseed commit inside seedOrReseedSlots — reaches reconcileActive rather
        // than early-returning on Inactive.
        activateWithCurrentTopology();
        // seedOrReseedSlots writes the COMPLETE clusterSize slot set (bound occupants + empties) in
        // one write-set and chains the activation reconcile onto its commit — so reconcile (and its
        // maintainSlotSetSize) reads a slot map that already reflects the bindings, never a stale
        // pre-commit view that would re-seed bound indices EMPTY (the activation double-seed clobber).
        seedOrReseedSlots();
        scheduleSafetyNetPoll();
    }

    /// Wipe-and-reseed on leader activation (slot-based-membership-convergence-spec §7.3, OQ4).
    /// Legacy UUID-keyed (and any stale index-keyed) slots are wiped — they are transient and
    /// carry no durable truth. The durable membership truth is the `NodeLifecycleKey` ON_DUTY
    /// entries, from which the reseed reconstructs occupancy: the `S` occupants with the LOWEST
    /// `observedCoreEpoch` (oldest first; tie-break NodeId lexical for determinism) bind to slots
    /// `0..S-1`; surplus occupants are left unbound and reaped occupancy-aware (the
    /// convergence-collapse point that squeezes out an existing over-count). Remaining indices are
    /// seeded EMPTY for the subsequent reconcile pass to fill.
    @Contract
    private void seedOrReseedSlots() {
        var configured = activationDesiredSize();

        if (configured <= 0) {
            log.info("CTM: reseed skipped — no configured cluster size at activation; awaiting snapshot bump");
            reconcile();

            return;
        }

        wipeAllSlotAtoms();

        // Bind only occupants whose committed lifecycle is NOT already STOPPED. A peer that is
        // STOPPED in the lifecycle but still lingers in the ON_DUTY snapshot at activation (the
        // SENSE plane lags the DECIDE plane) must NOT be re-bound — re-binding re-PUTs its slot
        // with assignedNodeId=some(dead-peer), which fires a SlotClaimed for a dead occupant.
        // Leave its slot EMPTY so normal convergence fills it with a fresh provider-allocated node.
        var occupants = onDutyOccupantsBySeniority().stream()
                                                    .filter(occupant -> !occupantStopped(occupant))
                                                    .toList();
        var bound = occupants.stream().limit(configured).toList();
        var surplus = occupants.stream().skip(configured).toList();
        var puts = buildReseedPuts(bound, configured);
        commandApplier.apply(puts)
                      .onFailure(cause -> log.warn("CTM: failed to reseed {} slot(s): {}", puts.size(), cause.message()))
                      .onSuccess(_ -> log.info("CTM: reseeded {} slot(s) ({} bound to live occupants, {} empty) for configured={}",
                                               puts.size(),
                                               bound.size(),
                                               puts.size() - bound.size(),
                                               configured))
                      // Run the activation reconcile only AFTER the complete reseed write-set has
                      // committed, so maintainSlotSetSize observes the bound slots (not a stale
                      // pre-commit map) and does not clobber them with EMPTY seeds.
                      .onResult(_ -> reconcile());

        // Reseed-surplus reaping is a lifecycle mutation — suppress it while the cluster phase is
        // not NORMAL (COLD_BOOT / RECOVERING), matching the reconcile-loop phase gate. The surplus
        // stays bound until the phase normalizes, when the reconcile loop reaps it.
        if (!surplus.isEmpty() && phaseSupplier.get() == ClusterPhase.NORMAL) {reapReseedSurplus(surplus);}
    }

    /// ON_DUTY occupants (from the generation snapshot) sorted by seniority: `observedCoreEpoch`
    /// ascending (oldest first), tie-break NodeId lexical (determinism only — NOT a time order).
    private List<NodeId> onDutyOccupantsBySeniority() {
        return snapshotSource.currentMembershipView()
                             .map(MembershipView::onDutyMemberIds)
                             .or(Set.of())
                             .stream()
                             .sorted(Comparator.comparing(this::nodeJoinEpoch)
                                               .thenComparing(NodeId::id))
                             .toList();
    }

    private List<KVCommand<AetherKey>> buildReseedPuts(List<NodeId> bound, int configured) {
        var nowMs = nowMs();
        var deadlineMs = nowMs + autoHealConfig.provisioningTimeout().millis();
        var puts = new ArrayList<KVCommand<AetherKey>>(configured);

        for (var index = 0;index <configured;index++) {
            puts.add(reseedSlotPut(index, bound, nowMs, deadlineMs));
        }

        return puts;
    }

    private KVCommand<AetherKey> reseedSlotPut(int index, List<NodeId> bound, long nowMs, long deadlineMs) {
        var key = slotKeyForIndex(index);

        if (index >= bound.size()) {return putSlotCommand(key, emptySlotValue());}

        var occupant = bound.get(index);
        slotKeyByNodeId.put(occupant, key);

        return putSlotCommand(key, new ProvisioningSlotValue(nowMs, deadlineMs, Option.some(occupant), 1L, Option.none()));
    }

    @Contract
    private void reapReseedSurplus(List<NodeId> surplus) {
        var ctmOwned = ctmProvisionedNodeIds();
        var reapable = surplus.stream()
                              .filter(occupant -> ctmOwned.contains(occupant) || occupantStopped(occupant))
                              .toList();
        var protectedCount = surplus.size() - reapable.size();

        if (protectedCount > 0) {
            log.warn("CTM: reseed surplus — {} non-CTM-provisioned occupant(s) NOT auto-reaped (operator owns removal)",
                     protectedCount);
        }

        log.warn("CTM: reseed surplus — reaping {} occupant(s) beyond configured size occupancy-aware: {}",
                 reapable.size(),
                 reapable);
        reapable.forEach(occupant -> reapOccupantOccupancyAware(occupant, occupantStopped(occupant)
                                                                          ? SlotOccupancy.DEAD
                                                                          : SlotOccupancy.HEALTHY));
    }

    private Set<NodeId> ctmProvisionedNodeIds() {
        return snapshotSource.currentMembershipView()
                             .map(MembershipView::ctmProvisionedNodeIds)
                             .or(Set.of());
    }

    @Contract
    private void wipeAllSlotAtoms() {
        var allSlots = slotReader.get();

        if (allSlots.isEmpty()) {return;}

        var deletes = allSlots.keySet().stream().map(ClusterTopologyManagerRecord::deleteSlotCommand).toList();
        commandApplier.apply(deletes)
                      .onFailure(cause -> log.warn("CTM: failed to wipe {} legacy slot atom(s) on reseed: {}", deletes.size(), cause.message()))
                      .onSuccess(_ -> log.info("CTM: wiped {} legacy slot atom(s) on reseed", deletes.size()));
        slotKeyByNodeId.clear();
        // Reseed rebuilds the entire slot set from scratch — drop any stale per-index in-flight
        // claims so the post-reseed reconcile can re-target freshly EMPTY indices.
        inFlightSlotIndices.clear();
    }

    @SuppressWarnings("unchecked")
    private static KVCommand<AetherKey> deleteSlotCommand(ProvisioningSlotKey key) {
        return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<AetherKey>(key);
    }

    @SuppressWarnings("unchecked")
    private static KVCommand<AetherKey> putSlotCommand(ProvisioningSlotKey key, ProvisioningSlotValue value) {
        return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(key, value);
    }

    @Contract
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
        } else if (clusterWasFormed && effectiveActual >= desired - 1) {
            activateWithLeaderFailover(effectiveActual, desired);
        } else {activateWithFormation();}
    }

    @Contract
    private void anchorBootstrapGrace() {
        var nowMs = nowMs();
        var windowMs = autoHealConfig.provisionStabilityWindow().millis();
        realActualStableSinceMs.set(nowMs + BOOTSTRAP_GRACE_MS - windowMs);
        log.debug("CTM: bootstrap grace anchored — provisioning gate held closed for {}ms", BOOTSTRAP_GRACE_MS);
    }

    private int activationDesiredSize() {
        var fromSnapshot = snapshotDesiredCoreSize();

        if (fromSnapshot > 0) {
            return fromSnapshot;
        }
        // clusterSize() includes SWIM-faulty nodes, returning pre-kill count during the snapshot-gap after failover
        return clusterConfigReader.get()
                                  .map(ClusterConfigValue::coreCount)
                                  .or(() -> observer.clusterSize());
    }

    @Contract
    private void activateWithLeaderFailover(int effectiveActual, int desired) {
        transitionTo(new NodeReconcilerState.Converged());
        // Cold-boot guard: when the cluster has not yet declared itself NORMAL, the leader-failover
        // path is reached because baseline nodes are still booting (clusterWasFormed = readyCount>0
        // becomes true as soon as ANY node is ready). Provisioning here races still-booting
        // compose-baseline nodes and creates a ghost. Defer to phase=NORMAL; onClusterPhaseChanged
        // will fire reconcile() at that moment and the normal cycle (with stability window) will
        // detect any genuine deficit.
        if (phaseSupplier.get() == ClusterPhase.COLD_BOOT) {
            log.info("CTM: Leader failover path entered during COLD_BOOT ({}/{}); deferring to phase=NORMAL",
                     effectiveActual,
                     desired);
            return;
        }

        log.info("CTM: Leader failover detected ({}/{}), enabling immediate reconciliation", effectiveActual, desired);
        reconcile();
    }

    @Contract
    private void activateWithFormation() {
        transitionTo(new NodeReconcilerState.Forming(nowInstant()));
        SharedScheduler.schedule(this::checkFormationComplete, autoHealConfig.startupCooldown());
    }

    @Contract
    @Override
    public void deactivate() {
        if (!active.compareAndSet(true, false)) {return;}

        cancelSafetyNetPoll();
        transitionTo(new NodeReconcilerState.Inactive("deactivated (not leader)"));
        log.info("CTM: Deactivated");
    }

    @Override
    public TopologyObserver observer() {
        return observer;
    }

    @Override
    public NodeInfo self() {
        return observer.self();
    }

    @Override
    public Option<NodeInfo> get(NodeId id) {
        return observer.get(id);
    }

    @Override
    public int clusterSize() {
        return observer.clusterSize();
    }

    @Override
    public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
        return observer.reverseLookup(socketAddress);
    }

    @Override
    public Promise<Unit> start() {
        return observer.start();
    }

    @Override
    public Promise<Unit> stop() {
        deactivate();

        return observer.stop();
    }

    @Override
    public TimeSpan pingInterval() {
        return observer.pingInterval();
    }

    @Override
    public TimeSpan helloTimeout() {
        return observer.helloTimeout();
    }

    @Override
    public Option<TlsConfig> tls() {
        return observer.tls();
    }

    @Override
    public Option<NodeState> getState(NodeId id) {
        return observer.getState(id);
    }

    @Override
    public List<NodeId> topology() {
        return observer.topology();
    }

    @Contract
    private void transitionTo(NodeReconcilerState newState) {
        var previous = stateRef.getAndSet(newState);
        log.info("CTM state: {} -> {}",
                 stateName(previous),
                 stateName(newState));
    }

    @Contract
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

    @Contract
    private void handleFormationCooldownExpired(int actual, int desired) {
        log.info("CTM: Formation cooldown expired, cluster at {}/{}, enabling reconciliation", actual, desired);
        transitionTo(new NodeReconcilerState.Converged());
        reconcile();
    }

    @Contract
    private void reconcile() {
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

    @Contract
    private void reconcileForming() {
        var actual = observer.healthyActiveNodeCount();
        var configured = snapshotDesiredCoreSize();

        if (configured == 0) {return;}
        if (actual >= configured) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster formation complete ({}/{})", actual, configured);
        }
    }

    @Contract
    private void reconcileActive(NodeReconcilerState currentState) {
        var snapshot = snapshotSource.currentMembershipView();

        if (snapshot.isEmpty()) {return;}

        var view = snapshot.unwrap();
        var configured = view.desiredCoreSize();

        if (configured == 0) {return;}

        // Slot-set maintenance (D1 §5.4): bring the durable slot set to exactly `configured`
        // BEFORE classifying occupancy. Scale-up adds empty slots; scale-down drains+removes the
        // highest-index slots (occupant reaped occupancy-aware). Folded into the idempotent
        // reconcile loop so activation-seed, config-change, and cold-start all converge uniformly.
        maintainSlotSetSize(view, configured);
        convergeSlotOccupancy(currentState, view, configured);
    }

    /// Slot-occupancy convergence (slot-based-membership-convergence-spec §5.1/§5.2). Frees DEAD
    /// slots (D2, no drain), computes post-free EMPTY occupancy in memory, then provisions into
    /// empty slots not already FILLING — bounded by `MAX_WAVE_SIZE` and the retained
    /// stability/circuit/backoff gates.
    @Contract
    private void convergeSlotOccupancy(NodeReconcilerState currentState, MembershipView view, int configured) {
        var nowMs = nowMs();
        var slots = indexedSlots();
        var freedIndices = freeDeadSlots(slots, view, nowMs);
        var healthy = countHealthy(slots, view, nowMs);
        var emptyToFill = selectEmptySlotsToFill(slots, view, nowMs, freedIndices);
        observeRealActualForStability(healthy);
        log.info("CTM reconcile(slot): configured={} healthy={} freedDead={} emptyToFill={} occupancy={}",
                  configured,
                  healthy,
                  freedIndices.size(),
                  emptyToFill.size(),
                  summarizeOccupancy(slots, view, nowMs));

        if (emptyToFill.isEmpty() && freedIndices.isEmpty()) {
            settleConverged(currentState, healthy, configured);

            return;
        }

        fillEmptySlots(currentState, emptyToFill, healthy, configured);
    }

    @Contract
    private void settleConverged(NodeReconcilerState currentState, int healthy, int configured) {
        if (currentState instanceof NodeReconcilerState.Converged) {
            log.debug("CTM converged(slot): healthy={} matches configured={}", healthy, configured);

            return;
        }

        log.info("CTM converged(slot): healthy={} configured={}, transitioning to Converged", healthy, configured);
        transitionTo(new NodeReconcilerState.Converged());
    }

    /// Provisions into the supplied empty slots, gated by stability/circuit/backoff. Marks the
    /// reconciler `Reconciling` (a wave was dispatched) so the safety-net poll and surplus/
    /// termination accounting continue to observe in-flight state.
    @Contract
    private void fillEmptySlots(NodeReconcilerState currentState,
                                List<IndexedSlot> emptyToFill,
                                int healthy,
                                int configured) {
        if (!autoHealEnabled.get()) {
            log.debug("CTM: auto-heal disabled — skipping fill of {} empty slot(s) (healthy={}, configured={})",
                      emptyToFill.size(),
                      healthy,
                      configured);
            return;
        }
        if (!provisioningGatesPass(healthy, configured)) {return;}
        if (!lifecycleManager.isCloudManaged()) {
            log.debug("CTM: {} empty slot(s) but no ComputeProvider, cannot auto-provision", emptyToFill.size());

            return;
        }
        if (emptyToFill.isEmpty()) {return;}

        var wave = emptyToFill.stream()
                              .limit(MAX_WAVE_SIZE)
                              .toList();
        markReconciling(currentState, healthy, configured, wave.size());
        log.info("CTM: filling {} empty slot(s) toward configured={} (healthy={})", wave.size(), configured, healthy);
        wave.forEach(this::provisionIntoSlot);
    }

    /// Retained gate chain (slot-based-membership-convergence-spec §5.2): stability window,
    /// provisioning circuit breaker, and backoff. Unchanged from the deficit-era `handleDeficit`.
    private boolean provisioningGatesPass(int healthy, int configured) {
        var nowMs = nowMs();

        // Quorum gate (anti-flood): below committed-healthy quorum the cluster is a minority
        // partition. Provisioning replacements here is a runaway flood (5→28+ nodes) that never
        // re-establishes quorum. Stop provisioning and let SelfDrainCoordinator dissolve the
        // minority side. Gates on TopologyObserver.inQuorum() (committed-healthy bit) — NOT on
        // transport connectedPeers, which the flood inflates.
        if (!inQuorum.getAsBoolean()) {
            log.info("CTM: below quorum — deferring provisioning to SelfDrainCoordinator (healthy={}, configured={})",
                     healthy,
                     configured);
            return false;
        }
        if (!stabilityElapsed(nowMs)) {
            log.info("CTM: stability window not yet elapsed (elapsed={}ms, required={}ms, healthy={}, configured={}); deferring fill",
                     nowMs - realActualStableSinceMs.get(),
                     autoHealConfig.provisionStabilityWindow().millis(),
                     healthy,
                     configured);
            return false;
        }
        if (provisioningCircuitTripped()) {
            log.info("CTM: provisioning halted by circuit breaker ({} consecutive failures); skipping fill.",
                     consecutiveProvisioningFailures.get());
            return false;
        }
        if (provisioningBackoffActive(nowMs)) {
            log.info("CTM: provisioning backoff active ({}ms remaining); deferring fill",
                     nextProvisioningAllowedMs.get() - nowMs);
            return false;
        }

        return true;
    }

    @Contract
    private void markReconciling(NodeReconcilerState currentState, int healthy, int configured, int waveSize) {
        var next = new NodeReconcilerState.Reconciling(configured,
                                                       healthy,
                                                       buildInFlightList(waveSize),
                                                       currentState instanceof NodeReconcilerState.Reconciling r
                                                       ? r.terminating()
                                                       : List.of(),
                                                       nowInstant());

        if (!stateRef.compareAndSet(currentState, next)) {
            log.debug("CTM: markReconciling CAS lost — observed={}, expected={}", stateRef.get(), currentState);
        }
    }

    /// Counts HEALTHY-classified slots (§5.1). By construction `0 <= headcount <= clusterSize`.
    private int countHealthy(List<IndexedSlot> slots, MembershipView view, long nowMs) {
        return (int) slots.stream()
                          .filter(slot -> classifyOccupancy(slot.value(), view, nowMs) == SlotOccupancy.HEALTHY)
                          .count();
    }

    /// Selects EMPTY slots eligible to fill: classified EMPTY now, OR just-freed this pass (the
    /// async clear-occupant write has not yet round-tripped, so we treat freed indices as EMPTY
    /// in memory — same-tick free-then-fill).
    ///
    /// In-flight guard: a slot whose index is claimed is normally skipped (a concurrent reconcile
    /// owns the fill, or a provision is in flight in the TOCTOU window where the FILLING reservation
    /// has not yet round-tripped to KV). BUT a claim whose recorded deadline has lapsed is STALE —
    /// the provision was abandoned (e.g. a container that never booted/joined, or a hung provider) —
    /// so the claim is reaped here and the slot is included for refill. The claim deadline equals
    /// the FILLING-marker `provisioningTimeout`, so this is the FILLING-deadline-expiry release path
    /// for the in-memory claim, mirroring the classification-based expiry that resets the slot to
    /// EMPTY.
    private List<IndexedSlot> selectEmptySlotsToFill(List<IndexedSlot> slots,
                                                     MembershipView view,
                                                     long nowMs,
                                                     Set<Integer> freedIndices) {
        return slots.stream()
                    .filter(slot -> isEmptyToFill(slot, view, nowMs, freedIndices))
                    .filter(slot -> claimAvailableForRefill(slot.index(), nowMs))
                    .toList();
    }

    /// True when the slot index may be (re)filled: not currently claimed, OR the claim's deadline
    /// has lapsed (stale leftover from an abandoned provision — reaped here so the slot can refill).
    /// Returns false only while a genuine in-flight provision still owns the index (claim deadline
    /// in the future) — the EMPTY-in-KV TOCTOU window between selection and reservation commit.
    private boolean claimAvailableForRefill(int index, long nowMs) {
        var claimDeadline = inFlightSlotIndices.get(index);

        if (claimDeadline == null) {return true;}
        if (nowMs <claimDeadline) {return false;}

        log.info("CTM: reaping stale in-flight claim on slot {} — claim deadline lapsed (abandoned provision); allowing refill",
                 index);
        inFlightSlotIndices.remove(index);

        return true;
    }

    private boolean isEmptyToFill(IndexedSlot slot, MembershipView view, long nowMs, Set<Integer> freedIndices) {
        return freedIndices.contains(slot.index())
               || classifyOccupancy(slot.value(), view, nowMs) == SlotOccupancy.EMPTY;
    }

    private String summarizeOccupancy(List<IndexedSlot> slots, MembershipView view, long nowMs) {
        return slots.stream()
                    .collect(Collectors.groupingBy(slot -> classifyOccupancy(slot.value(), view, nowMs),
                                                   Collectors.counting()))
                    .toString();
    }

    @Contract
    private void observeRealActualForStability(int actual) {
        var previous = lastObservedRealActual.getAndSet(actual);

        if (previous == UNINITIALIZED_REAL_ACTUAL) {return;}
        if (previous > actual) {return;}
        if (previous != actual) {bumpRealActualStability("realActual " + previous + " -> " + actual);}
    }

    // ---------------------------------------------------------------------------------------
    // Slot-set size maintenance (D1 §5.4) + dead-slot fast-free (D2 §3.2/§5.2) + fill (§5.3).
    // ---------------------------------------------------------------------------------------

    /// Brings the durable slot set to exactly `configured` entries keyed `0..configured-1`.
    /// Scale-up writes empty slots at the missing low indices; scale-down drains+removes the
    /// highest-index slots (occupant reaped occupancy-aware). Idempotent: a no-op when the set
    /// already matches.
    @Contract
    private void maintainSlotSetSize(MembershipView view, int configured) {
        var slots = indexedSlots();
        var present = slots.stream()
                           .map(IndexedSlot::index)
                           .collect(Collectors.toUnmodifiableSet());
        var missing = java.util.stream.IntStream.range(0, configured)
                                                .filter(i -> !present.contains(i))
                                                .boxed()
                                                .toList();

        if (!missing.isEmpty()) {seedEmptySlots(missing);}

        var surplusSlots = slots.stream()
                                .filter(slot -> slot.index() >= configured)
                                .toList();

        if (!surplusSlots.isEmpty()) {removeSurplusSlots(surplusSlots, view);}
    }

    @Contract
    private void seedEmptySlots(List<Integer> indices) {
        var puts = indices.stream()
                          .map(index -> putSlotCommand(slotKeyForIndex(index), emptySlotValue()))
                          .toList();
        commandApplier.apply(puts)
                      .onFailure(cause -> log.warn("CTM: failed to seed {} empty slot(s): {}", puts.size(), cause.message()))
                      .onSuccess(_ -> log.info("CTM: seeded {} empty slot(s) {} to track configured size", puts.size(), indices));
    }

    /// Scale-down: structurally remove the highest-index REAPABLE slots (§5.4 + Option B safety
    /// filter). A slot is reapable when it is EMPTY/FILLING (no live occupant) OR its occupant is
    /// DEAD OR its occupant is CTM-provisioned. A surplus slot holding a non-CTM-provisioned
    /// (MANUAL/UNKNOWN-source) occupant is NEVER reaped — CTM must not auto-terminate an
    /// operator-seeded node; the slot is left in place (logged) and the operator owns its removal.
    @Contract
    private void removeSurplusSlots(List<IndexedSlot> surplusSlots, MembershipView view) {
        var nowMs = nowMs();
        var ctmOwned = ctmProvisionedNodeIds();
        var reapable = surplusSlots.stream()
                                   .filter(slot -> slotReapable(slot, ctmOwned))
                                   .toList();
        logProtectedSurplusSlots(surplusSlots, reapable);

        if (reapable.isEmpty()) {return;}

        reapable.forEach(slot -> reapSurplusOccupant(slot, view, nowMs));
        var removes = reapable.stream()
                              .map(slot -> deleteSlotCommand(slot.key()))
                              .toList();
        commandApplier.apply(removes)
                      .onFailure(cause -> log.warn("CTM: failed to remove {} surplus slot(s): {}", removes.size(), cause.message()))
                      .onSuccess(_ -> log.info("CTM: removed {} surplus slot(s) on scale-down", removes.size()));
    }

    /// A surplus slot may be reaped iff it has no live occupant, or its occupant is CTM-provisioned
    /// (CTM owns it), or its occupant is already STOPPED (dead — safe to free regardless of source).
    private boolean slotReapable(IndexedSlot slot, Set<NodeId> ctmOwned) {
        return slot.value()
                   .assignedNodeId()
                   .fold(() -> true,
                         occupant -> ctmOwned.contains(occupant) || occupantStopped(occupant));
    }

    @Contract
    private void logProtectedSurplusSlots(List<IndexedSlot> surplusSlots, List<IndexedSlot> reapable) {
        var protectedCount = surplusSlots.size() - reapable.size();

        if (protectedCount > 0) {
            log.warn("CTM: {} surplus slot(s) hold non-CTM-provisioned occupants — NOT auto-terminated (operator owns removal); cluster may stay above target until operator acts",
                     protectedCount);
        }
    }

    @Contract
    private void reapSurplusOccupant(IndexedSlot slot, MembershipView view, long nowMs) {
        slot.value()
            .assignedNodeId()
            .onPresent(occupant -> reapOccupantOccupancyAware(occupant, classifyOccupancy(slot.value(), view, nowMs)));
    }

    /// Occupancy-aware reap (slot-based-membership-convergence-spec §5.4 + reseed §7.3): DEAD
    /// occupants are fast-freed (no drain); genuinely HEALTHY/other occupants drain gracefully.
    @Contract
    private void reapOccupantOccupancyAware(NodeId occupant, SlotOccupancy occupancy) {
        if (occupancy == SlotOccupancy.DEAD) {
            fastFreeDeadOccupant(occupant);

            return;
        }

        terminateSingleNode(occupant);
    }

    /// Frees DEAD slots in place (D2 §5.2): clears the occupant, records it as `supersededNodeId`
    /// so the same-tick refill keeps lineage, and best-effort cloud-reaps WITHOUT a drain ack.
    /// CTM never writes STOPPED (OQ6) — the reducer already made the occupant terminal. Returns
    /// the set of freed slot indices so the caller can treat them as EMPTY this same pass.
    private Set<Integer> freeDeadSlots(List<IndexedSlot> slots, MembershipView view, long nowMs) {
        var dead = slots.stream()
                        .filter(slot -> classifyOccupancy(slot.value(), view, nowMs) == SlotOccupancy.DEAD)
                        .toList();

        if (dead.isEmpty()) {return Set.of();}

        dead.forEach(this::freeSlot);

        return dead.stream()
                   .map(IndexedSlot::index)
                   .collect(Collectors.toUnmodifiableSet());
    }

    @Contract
    private void freeSlot(IndexedSlot slot) {
        var nowMs = nowMs();
        var deadOccupant = slot.value().assignedNodeId();
        var freed = new ProvisioningSlotValue(nowMs,
                                              nowMs,
                                              Option.none(),
                                              slot.value().occupantEpoch(),
                                              deadOccupant);
        commandApplier.apply(List.of(putSlotCommand(slot.key(), freed)))
                      .onFailure(cause -> log.warn("CTM: failed to free DEAD slot {}: {}", slot.index(), cause.message()))
                      .onSuccess(_ -> log.info("CTM: freed DEAD slot {} (superseded={})", slot.index(), deadOccupant));
        // Release any lingering per-index claim: the slot is now free and selectEmptySlotsToFill
        // treats freed indices as EMPTY this same pass, so the index must be claimable for refill.
        inFlightSlotIndices.remove(slot.index());
        deadOccupant.onPresent(this::fastFreeDeadOccupant);
    }

    /// Best-effort cloud reap of a dead occupant with NO drain ack (D2). Reuses the no-drain
    /// shape of `tombstoneAssignedNodeOnExpiry`; CTM issues no STOPPED write (the reducer owns it).
    @Contract
    private void fastFreeDeadOccupant(NodeId occupant) {
        slotKeyByNodeId.remove(occupant);
        inFlightProvisions.remove(occupant);
        lifecycleManager.terminateNode(occupant)
                        .onFailure(cause -> log.debug("CTM: best-effort terminate of dead occupant {} returned {}",
                                                      occupant,
                                                      cause.message()));
    }

    /// Fills one EMPTY slot (§5.3) under the reserve-then-provision contract. The provider call is
    /// chained INSIDE the FILLING-reservation commit: `lifecycleManager.provisionNode` runs ONLY
    /// after `commandApplier.apply(FILLING)` commits, so no container is ever spawned without a
    /// committed slot reservation. A reservation failure records a provisioning failure (feeding
    /// the circuit-breaker/backoff) and spawns nothing.
    ///
    /// A slot-index in-flight guard (`inFlightSlotIndices`) is claimed atomically before the
    /// reservation: if the index is already claimed by an overlapping reconcile this pass skips it
    /// (the other reconcile owns it). The claim is released on EVERY terminal outcome — reservation
    /// failure, provider failure, provider-reports-no-id (slot left FILLING to expire), and after
    /// the occupant is bound (assignOccupant terminal). A genuinely stuck FILLING slot whose
    /// container never joins expires on its `deadlineMs`, reclassifies EMPTY, and — its index long
    /// since released here — refills on a later reconcile tick.
    @Contract
    private void provisionIntoSlot(IndexedSlot slot) {
        var nowMs = nowMs();
        var deadlineMs = nowMs + autoHealConfig.provisioningTimeout().millis();

        if (inFlightSlotIndices.putIfAbsent(slot.index(), deadlineMs) != null) {
            log.debug("CTM: slot {} already in-flight (claimed by a concurrent reconcile) — skipping this pass", slot.index());

            return;
        }

        var contextBase = buildProvisionContext();

        if (contextBase.peers().or("").isEmpty()) {
            log.warn("CTM: fill of slot {} deferred — no healthy peers visible in topology (peers list empty); "
                    + "next reconcile tick retries once at least one peer is HEALTHY.",
                     slot.index());
            recordProvisioningFailure("no healthy peers visible in topology");
            inFlightSlotIndices.remove(slot.index());

            return;
        }

        var baseSpec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "default", "core", contextBase).unwrap();
        var spec = computePlacementHint().map(baseSpec::withPlacement).or(baseSpec);
        var filling = new ProvisioningSlotValue(nowMs,
                                                deadlineMs,
                                                Option.none(),
                                                slot.value().occupantEpoch() + 1L,
                                                slot.value().supersededNodeId());
        var fillingSlot = new IndexedSlot(slot.index(), slot.key(), filling);
        commandApplier.apply(List.of(putSlotCommand(slot.key(), filling)))
                      .onFailure(cause -> onReservationFailed(fillingSlot, cause))
                      .onSuccess(_ -> onReservationCommitted(fillingSlot, spec));
    }

    /// Reservation commit failed (e.g. consensus down): record the failure so the circuit-breaker
    /// /backoff observes it, release the slot-index claim, and spawn NOTHING.
    @Contract
    private void onReservationFailed(IndexedSlot slot, Cause cause) {
        log.warn("CTM: failed to commit FILLING reservation on slot {} ({}); not provisioning", slot.index(), cause.message());
        recordProvisioningFailure("FILLING reservation commit failed: " + cause.message());
        inFlightSlotIndices.remove(slot.index());
    }

    /// Reservation committed: NOW spawn the container, chained to the committed reservation.
    @Contract
    private void onReservationCommitted(IndexedSlot slot, ProvisionSpec spec) {
        log.info("CTM: committed FILLING reservation on slot {} (epoch={}, superseded={}) — provisioning",
                 slot.index(),
                 slot.value().occupantEpoch(),
                 slot.value().supersededNodeId());
        lifecycleManager.provisionNode(spec)
                        .onSuccess(info -> bindSlotToRealNode(slot, info))
                        .onFailure(cause -> onProvisionFailed(slot, cause));
    }

    /// Provider rejected/failed the spawn: record the failure and release the slot-index claim. The
    /// FILLING marker is left to expire on its deadline and reclassify EMPTY for a later refill.
    @Contract
    private void onProvisionFailed(IndexedSlot slot, Cause cause) {
        recordProvisioningFailure("API rejection: " + cause.message());
        inFlightSlotIndices.remove(slot.index());
    }

    /// Provider-owns-identity completion: assign the provider-allocated id to the FILLING slot,
    /// preserving the slot's `occupantEpoch` and `supersededNodeId`. When the provider reports no
    /// id the slot is left FILLING to expire and reset to EMPTY — no ghost JOINING is written — and
    /// the slot-index claim is released so the expired slot can be re-filled later.
    @Contract
    private void bindSlotToRealNode(IndexedSlot slot, InstanceInfo info) {
        info.nodeId()
            .flatMap(idStr -> NodeId.nodeId(idStr).option())
            .apply(() -> onBindNoProviderId(slot, info),
                   realId -> assignOccupant(slot, realId));
    }

    @Contract
    private void onBindNoProviderId(IndexedSlot slot, InstanceInfo info) {
        log.warn("CTM: fill of slot {} returned no node id ({}); leaving slot FILLING to expire and reset",
                 slot.index(),
                 info.id().value());
        inFlightSlotIndices.remove(slot.index());
    }

    @Contract
    private void assignOccupant(IndexedSlot slot, NodeId realId) {
        var assigned = new ProvisioningSlotValue(slot.value().spawnedAtMs(),
                                                 slot.value().deadlineMs(),
                                                 Option.some(realId),
                                                 slot.value().occupantEpoch(),
                                                 slot.value().supersededNodeId());
        slotKeyByNodeId.put(realId, slot.key());
        commandApplier.apply(List.of(putSlotCommand(slot.key(), assigned)))
                      .onFailure(cause -> log.warn("CTM: failed to assign slot {} to {}: {}", slot.index(), realId, cause.message()))
                      .onSuccess(_ -> log.info("CTM: assigned slot {} to provider-allocated node {} (epoch={})",
                                               slot.index(),
                                               realId,
                                               assigned.occupantEpoch()))
                      .onResult(_ -> inFlightSlotIndices.remove(slot.index()));
    }

    private static ProvisioningSlotValue emptySlotValue() {
        return new ProvisioningSlotValue(0L, 0L, Option.none(), 0L, Option.none());
    }

    private boolean stabilityElapsed(long nowMs) {
        var anchor = realActualStableSinceMs.get();
        var elapsed = nowMs - anchor;

        return elapsed >= autoHealConfig.provisionStabilityWindow()
                                        .millis();
    }

    private Epoch nodeJoinEpoch(NodeId nodeId) {
        return lifecycleReader.apply(nodeId)
                              .map(NodeLifecycleValue::observedCoreEpoch)
                              .or(Epoch.ZERO);
    }

    @Contract
    private void terminateSingleNode(NodeId nodeId) {
        writeDrainingAtom(nodeId);
        var timeout = autoHealConfig.provisioningTimeout();
        // writeDrainingAtom routes ForceDrain through the sovereign FSM, whose InvokeDrain effect
        // starts the drain protocol (DrainCoordinator.prepareDrain) — so CTM no longer triggers
        // prepareDrain itself; it only awaits the drain ack before terminating the instance.
        drainCoordinator.awaitDrainAck(nodeId, timeout).onResult(result -> handleDrainResult(nodeId, result));
    }

    @Contract
    private void handleDrainResult(NodeId nodeId, Result<Unit> result) {
        result.onFailure(cause -> log.warn("CTM: drain ack for {} failed/timed out ({}); proceeding to terminate",
                                           nodeId,
                                           cause.message())).onSuccess(_ -> log.debug("CTM: drain ack received for {}",
                                                                                      nodeId));
        proceedToTerminate(nodeId);
    }

    @Contract
    private void proceedToTerminate(NodeId nodeId) {
        lifecycleManager.terminateNode(nodeId).onSuccess(_ -> handleTerminateSuccessWithDrainComplete(nodeId)).onFailure(cause -> log.warn("CTM: Node {} termination failed: {}",
                                                                                                                                           nodeId,
                                                                                                                                           cause.message()));
    }

    @Contract
    private void handleTerminateSuccessWithDrainComplete(NodeId nodeId) {
        drainCoordinator.markDrainComplete(nodeId);
        handleTerminationSuccess(nodeId);
    }

    @Contract
    private void writeDrainingAtom(NodeId nodeId) {
        lifecycleWriter.requestDrain(nodeId).onFailure(cause -> log.warn("CTM: failed to request DRAINING for {}: {}",
                                                                         nodeId,
                                                                         cause.message())).onSuccess(_ -> log.info("CTM: requested DRAINING for {} via LifecycleWriter",
                                                                                                                   nodeId));
    }

    @Contract
    private void handleTerminationSuccess(NodeId nodeId) {
        log.info("CTM: Node {} terminated successfully", nodeId);
        writeDecommissionedAtom(nodeId);
        reconcile();
    }

    @Contract
    private void writeDecommissionedAtom(NodeId nodeId) {
        var command = new ForceDecommission(nodeId,
                                            StopReason.FORCED,
                                            Causes.cause("CTM: terminate-success decommission for " + nodeId),
                                            HlcTimestamp.ZERO);
        lifecycleWriter.applyCommand(command).onFailure(cause -> log.warn("CTM: failed to request DECOMMISSIONED for {}: {}",
                                                                          nodeId,
                                                                          cause.message())).onSuccess(_ -> log.info("CTM: requested DECOMMISSIONED for {} via applyCommand(FORCED)",
                                                                                                                    nodeId));
    }

    @Contract
    private void cancelInFlightProvisions(String reason) {
        if (inFlightProvisions.isEmpty()) {return;}

        var size = inFlightProvisions.size();
        log.info("CTM: cancelling {} in-flight provision(s) ({})", size, reason);
        inFlightProvisions.values().forEach(Promise::cancel);
        inFlightProvisions.clear();
        // Release every slot-index claim: a cancelled provisionNode Promise may never resolve its
        // chained onFailure/onSuccess, so the per-index claim would leak and permanently block that
        // slot from being refilled. The FILLING slots themselves are durable (D1) and expire to
        // EMPTY on their own deadline, then refill on the next eligible reconcile tick.
        inFlightSlotIndices.clear();
        // Durable slots (D1) are NOT wiped here — only the transient in-flight provision promises
        // are cancelled. FILLING slots whose provider call was cancelled expire to EMPTY on their
        // own deadline and refill on the next eligible reconcile tick.
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
        var remoteEntries = observer.topology().stream().filter(this::isHealthyPeer).flatMap(nodeId -> observer.get(nodeId)
                                                                                                               .stream()).map(ClusterTopologyManagerRecord::formatPeerEntry).filter(entry -> !entry.equals(selfEntry));
        var peers = Stream.concat(Stream.of(selfEntry), remoteEntries).collect(Collectors.joining(","));
        var clusterName = clusterConfigReader.get().map(ClusterConfigValue::clusterName).or("");

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
        return observer.getState(nodeId)
                       .map(state -> state.health() == NodeHealth.HEALTHY)
                       .or(false);
    }

    private static String formatPeerEntry(NodeInfo info) {
        var hostname = info.address().host();

        return info.id()
                   .id() + ":" + hostname + ":" + info.address()
                                                      .port();
    }

    private Option<PlacementHint> computePlacementHint() {
        var zoneCounts = observer.topology().stream().map(this::zoneLabel).filter(z -> !z.isEmpty()).collect(Collectors.groupingBy(z -> z,
                                                                                                                                   Collectors.counting()));

        if (zoneCounts.isEmpty()) {return Option.empty();}

        var minCount = zoneCounts.values().stream().mapToLong(Long::longValue).min().orElse(0L);
        var underRepresented = zoneCounts.entrySet().stream().filter(e -> e.getValue() == minCount).map(Map.Entry::getKey).toList();

        if (underRepresented.size() == 1) {return Option.some(PlacementHint.zoneHint(underRepresented.getFirst()));}

        var overRepresented = zoneCounts.entrySet().stream().filter(e -> e.getValue() > minCount).map(Map.Entry::getKey).collect(Collectors.toSet());

        if (overRepresented.isEmpty()) {return Option.empty();}

        return Option.some(PlacementHint.antiAffinityHint(overRepresented));
    }

    private String zoneLabel(NodeId nodeId) {
        return observer.get(nodeId)
                       .map(info -> info.labels()
                                        .getOrDefault(LABEL_ZONE, ""))
                       .or("");
    }

    @Contract
    private void scheduleSafetyNetPoll() {
        safetyNetTimer.set(SharedScheduler.scheduleAtFixedRate(this::reconcile, autoHealConfig.retryInterval()));
    }

    @Contract
    private void cancelSafetyNetPoll() {
        safetyNetTimer.cancel();
    }

    private List<NodeReconcilerState.ProvisioningSlot> buildInFlightList(int count) {
        var nowMs = nowMs();
        var deadlineMs = nowMs + autoHealConfig.provisioningTimeout().millis();
        var list = new ArrayList<NodeReconcilerState.ProvisioningSlot>(count);

        for (var i = 0;i <count;i++) {list.add(new NodeReconcilerState.ProvisioningSlot(nowMs, deadlineMs));}

        return List.copyOf(list);
    }

    private static String stateName(NodeReconcilerState state) {
        return switch (state) {
            case NodeReconcilerState.Inactive inactive -> "Inactive(" + inactive.reason() + ")";
            case NodeReconcilerState.Forming _ -> "Forming";
            case NodeReconcilerState.Converged _ -> "Converged";
            case NodeReconcilerState.Reconciling r -> "Reconciling(" + r.currentSize() + "/" + r.targetSize() + ")";
        };
    }
}
