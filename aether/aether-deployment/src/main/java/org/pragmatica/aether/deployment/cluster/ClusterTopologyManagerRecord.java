// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
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
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.net.NodeInfo.LABEL_ZONE;
import static org.pragmatica.lang.Unit.unit;


record ClusterTopologyManagerRecord(TopologyObserver observer,
                                    NodeLifecycleManager lifecycleManager,
                                    AutoHealConfig autoHealConfig,
                                    DeploymentMap deploymentMap,
                                    GenerationSnapshotSource snapshotSource,
                                    Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                    Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                    Supplier<ClusterPhase> phaseSupplier,
                                    BooleanSupplier inQuorum,
                                    AtomicReference<NodeReconcilerState> stateRef,
                                    AtomicBoolean active,
                                    ConcurrentHashMap<NodeId, Promise<?>> inFlightProvisions,
                                    ConcurrentHashMap<Integer, Long> inFlightSlotIndices,
                                    CancellableTask safetyNetTimer,
                                    AtomicLong realActualStableSinceMs,
                                    AtomicInteger lastObservedRealActual,
                                    AtomicInteger lastObservedHealthyOnDutyCount,
                                    AtomicInteger consecutiveProvisioningFailures,
                                    AtomicLong nextProvisioningAllowedMs,
                                    AtomicLong lastProvisioningFailureMs,
                                    AtomicLong formationAnchorMs,
                                    AtomicBoolean autoHealEnabled,
                                    LongSupplier clock,
                                    Consumer<NodeId> drainCommandSink,
                                    Consumer<NodeId> drainCommandClear) implements ClusterTopologyManager {
    private static final Logger log = LoggerFactory.getLogger(ClusterTopologyManager.class);
    private static final int MINIMUM_CLUSTER_SIZE = 3;
    private static final int UNINITIALIZED_REAL_ACTUAL = -1;
    private static final long BOOTSTRAP_GRACE_MS = 60_000L;
    private static final int MAX_CONSECUTIVE_PROVISIONING_FAILURES = 3;

    static ClusterTopologyManagerRecord clusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig config,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                                     Supplier<ClusterPhase> phaseSupplier,
                                                                     BooleanSupplier inQuorum,
                                                                     LongSupplier clock) {
        return clusterTopologyManagerRecord(observer,
                                            lifecycleManager,
                                            config,
                                            deploymentMap,
                                            snapshotSource,
                                            clusterConfigReader,
                                            commandApplier,
                                            phaseSupplier,
                                            inQuorum,
                                            clock,
                                            _ -> {},
                                            _ -> {});
    }

    /// Membership v2 / B5b — production factory wiring the leader's DRAIN command channel.
    /// `drainCommandSink` enqueues the target into the `DrainCommandRegistry` (so the leader's
    /// outbound ping carries the target in its global `drainNodes` set and the target self-drains via its
    /// `DrainProcedure`); `drainCommandClear` removes the target after the grace-terminate
    /// backstop reaps the container. Both default to no-op via the overload above for tests and
    /// legacy callers.
    static ClusterTopologyManagerRecord clusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig config,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                                     Supplier<ClusterPhase> phaseSupplier,
                                                                     BooleanSupplier inQuorum,
                                                                     LongSupplier clock,
                                                                     Consumer<NodeId> drainCommandSink,
                                                                     Consumer<NodeId> drainCommandClear) {
        return new ClusterTopologyManagerRecord(observer,
                                                lifecycleManager,
                                                config,
                                                deploymentMap,
                                                snapshotSource,
                                                clusterConfigReader,
                                                commandApplier,
                                                phaseSupplier,
                                                inQuorum,
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
                                                new AtomicLong(clock.getAsLong()),
                                                new AtomicBoolean(true),
                                                clock,
                                                drainCommandSink == null
                                                ? _ -> {}
                                                : drainCommandSink,
                                                drainCommandClear == null
                                                ? _ -> {}
                                                : drainCommandClear);
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
        if (size < MINIMUM_CLUSTER_SIZE) {
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
    // Slot-occupancy model retired (CTM v2): the internal slot-reconcile loop is OFF. CTM is now
    // a pure actuator driven by the LeaderReconciler (spec §7). The ProvisioningSlotKey/
    // ProvisioningSlotValue KV types remain alive (deleted in a later phase).
    // ---------------------------------------------------------------------------------------
    @Contract
    @Override
    public void onNodeReady(NodeId nodeId) {
        resetProvisioningCircuit("node " + nodeId + " became present");
    }

    @Contract
    @Override
    public void onClusterConfigChanged() {
        if (!active.get()) {
            return;
        }

        var nowMs = nowMs();
        var windowMs = autoHealConfig.provisionStabilityWindow().millis();

        realActualStableSinceMs.set(nowMs - windowMs);
        log.debug("CTM: ClusterConfigKey changed, bypassing stability gate");
    }

    @Contract
    @Override
    public void onMembershipDecision(MembershipDecision decision) {
        if (!active.get()) {
            return;
        }

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
        if (!active.get()) {
            return;
        }

        log.warn("CTM: Self-shutdown observed for {}", selfShutdown.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("self-shutdown " + selfShutdown.nodeId());
    }

    @Contract
    @Override
    public void onClusterPhaseChanged(ClusterPhase newPhase) {
        if (newPhase == ClusterPhase.NORMAL) {
            cancelInFlightProvisions("phase transition to NORMAL — restart stability window");
            bumpRealActualStability("phase transition to NORMAL");
            resetProvisioningCircuit("phase transition to NORMAL");
            log.info("CTM: cluster phase transitioned to NORMAL (stability window restarted from zero)");

            return;
        }

        cancelInFlightProvisions("phase transition to " + newPhase + " — auto-heal suspended");
        log.info("CTM: cluster phase transitioned to {}", newPhase);
    }

    @Contract
    private void handleNodeJoined(NodeJoined joined) {
        log.info("CTM: Node {} joined", joined.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("node-joined " + joined.nodeId());
    }

    @Contract
    private void handleNodeRemoved(NodeRemoved removed) {
        log.info("CTM: Node {} removed", removed.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("node-removed " + removed.nodeId());
    }

    @Contract
    private void handleNodeDecommissioned(NodeDecommissioned decommissioned) {
        log.warn("CTM: Node {} decommissioned", decommissioned.nodeId());
        maybeBumpAnchorOnHealthyOnDutyEdge("node-decommissioned " + decommissioned.nodeId());
    }

    @Contract
    private void maybeBumpAnchorOnHealthyOnDutyEdge(String reason) {
        var current = snapshotHealthyOnDutyCount();
        var previous = lastObservedHealthyOnDutyCount.getAndSet(current);

        if (previous == current) {
            log.debug("CTM: stability anchor unchanged ({}); count still {}", reason, current);

            return;
        }

        if (previous != UNINITIALIZED_REAL_ACTUAL && current < previous) {
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

        return prev;
    }

    /// Membership v2 / E2 — provision a replacement, PURE ACTUATOR.
    ///
    /// The `LeaderReconciler` (spec §7) owns shortage derivation and calls this per missing
    /// slot with the placeholder identity `newNodeId` it minted and tracks in-flight. The
    /// provisioned node boots under exactly that id: `newNodeId.id()` is threaded into the
    /// `ProvisionContext.nodeId()` (via `buildProvisionContext`) so the provider boundary
    /// injects it as `AETHER_NODE_ID` / the Docker container name, and the node adopts it as
    /// `self`. This is what lets the reconciler treat the id's appearance in membership as the
    /// authoritative fulfillment signal. CTM no longer runs its own slot machinery here: it
    /// builds a `ProvisionSpec` whose PEERS are seeded from the LIVE member set `clusterMembers`
    /// passed in by the reconciler (the freshest "who is in the cluster right now" signal from
    /// `PresenceSampler.currentMembers`). Each member id is resolved to its
    /// `nodeId:host:port` entry via the same `observer.get(id)` → `formatPeerEntry` mechanism
    /// `buildProvisionContext` uses; ids that do not resolve are dropped, and `self` is always
    /// included (the CTM runs on the leader, which is alive by definition). Seeding from the
    /// live set — instead of `observer.topology()` ∩ `isHealthyPeer` — keeps just-killed
    /// hostnames out of the PEERS list, preventing DOA replacements (dead-host PEERS →
    /// QUIC NPE). If `clusterMembers` is empty (cold paths), the seed falls back to
    /// `buildProvisionContext`'s topology-derived peers. `failedPeer` is observability-only.
    @Override
    public Promise<Unit> provisionReplacement(NodeId newNodeId, Option<NodeId> failedPeer, Set<NodeId> clusterMembers) {
        log.info("CTM v2: provisionReplacement requested (newNodeId={}, failedPeer={}, clusterMembers.size={})",
                 newNodeId,
                 failedPeer,
                 clusterMembers.size());
        var contextBase = buildProvisionContext(newNodeId);
        var memberPeers = liveMemberPeers(clusterMembers);
        var contextSeeded = memberPeers.isEmpty()
                            ? contextBase
                            : contextBase.withPeers(memberPeers);

        if (contextSeeded.peers().or("").isEmpty()) {
            log.warn("CTM v2: provisionReplacement deferred — no healthy peers visible (peers list empty); "
                    + "returning success so the LeaderReconciler retries on its next tick.");

            return Promise.success(unit());
        }

        var baseSpec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "default", "core", contextSeeded).unwrap();
        var spec = computePlacementHint().map(baseSpec::withPlacement).or(baseSpec);

        return lifecycleManager.provisionNode(spec)
                               .mapToUnit();
    }

    /// Build the PEERS string from the LIVE member set: `self` first (always present — the
    /// leader is alive by definition), then each resolvable member's `nodeId:host:port` entry.
    /// Members that fail to resolve via `observer.get` (e.g., a just-killed hostname whose
    /// `NodeInfo` is gone) are dropped, and `self` is de-duplicated. An empty result signals
    /// the caller to fall back to the topology-derived seed.
    private String liveMemberPeers(Set<NodeId> clusterMembers) {
        if (clusterMembers.isEmpty()) {
            return "";
        }

        var selfEntry = formatPeerEntry(observer.self());
        var remoteEntries = clusterMembers.stream().flatMap(nodeId -> observer.get(nodeId)
                                                                              .stream()).map(ClusterTopologyManagerRecord::formatPeerEntry).filter(entry -> !entry.equals(selfEntry));

        return Stream.concat(Stream.of(selfEntry),
                             remoteEntries).distinct()
                            .collect(Collectors.joining(","));
    }

    /// Membership v2 / B5b — drain a specific node via the graceful v2 DRAIN-command path.
    ///
    /// Enqueues `targetNodeId` into the leader's `DrainCommandRegistry` (`drainCommandSink`) so
    /// the leader's outbound cluster-sync ping carries the target in its global `drainNodes` set,
    /// which self-drains (finishes in-flight requests) via its `DrainProcedure`. A grace-terminate
    /// backstop is scheduled after `autoHealConfig.provisioningTimeout()`: it calls
    /// `lifecycleManager.terminateNode(target)` to reap the container (prevents Docker
    /// restart-loop / cloud lingering when the target never self-exits) AND clears the target from
    /// the registry (`drainCommandClear`). `reason` is observability-only. Returns on the enqueue
    /// (the drain itself proceeds asynchronously via the heartbeat + backstop).
    @Override
    public Promise<Unit> drainNode(NodeId targetNodeId, DrainReason reason) {
        log.info("CTM v2: drainNode requested (target={}, reason={}) — enqueuing DRAIN command", targetNodeId, reason);
        drainCommandSink.accept(targetNodeId);
        scheduleGraceTerminate(targetNodeId);

        return Promise.success(unit());
    }

    /// Backstop reaper: after the grace period, terminate the container and clear the DRAIN
    /// command. Idempotent — `terminateNode` is safe to call on an already-exited node, and
    /// `drainCommandClear` no-ops on an absent target.
    @Contract
    private void scheduleGraceTerminate(NodeId targetNodeId) {
        SharedScheduler.schedule(() -> graceTerminate(targetNodeId), autoHealConfig.provisioningTimeout());
    }

    @Contract
    private void graceTerminate(NodeId targetNodeId) {
        log.info("CTM v2: drain grace expired for {} — reaping container + clearing DRAIN command", targetNodeId);
        drainCommandClear.accept(targetNodeId);
        lifecycleManager.terminateNode(targetNodeId).onFailure(cause -> log.warn("CTM v2: grace-terminate of {} failed: {}",
                                                                                 targetNodeId,
                                                                                 cause.message()));
    }

    /// Membership v2 / E2 — public reconcile. CTM no longer drives a slot loop; the
    /// `LeaderReconciler` (spec §7) owns state-derived reconciliation and actuates CTM via
    /// `provisionReplacement`/`drainNode`. No-op success.
    @Override
    public Promise<Unit> reconcile() {
        log.debug("CTM v2: reconcile requested — no-op (LeaderReconciler owns reconciliation)");

        return Promise.success(unit());
    }

    @Contract
    @Override
    public void activate() {
        if (!active.compareAndSet(false, true)) {
            return;
        }

        bumpRealActualStability("activate");
        resetProvisioningCircuit("activate (leader handoff)");
        formationAnchorMs.set(nowMs());
        // CTM v2: the internal slot-reconcile loop is OFF. The LeaderReconciler (spec §7) owns
        // state-derived reconciliation and actuates CTM via provisionReplacement/drainNode. CTM
        // only establishes its reconciler state for observability — it neither seeds slots nor
        // schedules a safety-net reconcile poll.
        activateWithCurrentTopology();
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
        } else {
            activateWithFormation();
        }
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
        log.info("CTM: Leader failover detected ({}/{})", effectiveActual, desired);
    }

    @Contract
    private void activateWithFormation() {
        transitionTo(new NodeReconcilerState.Forming(nowInstant()));
        SharedScheduler.schedule(this::checkFormationComplete, autoHealConfig.startupCooldown());
    }

    @Contract
    @Override
    public void deactivate() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

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
        if (!active.get()) {
            return;
        }

        if (! (stateRef.get() instanceof NodeReconcilerState.Forming)) {
            return;
        }

        var actual = observer.healthyActiveNodeCount();
        var desired = snapshotDesiredCoreSize();

        if (desired == 0) {
            return;
        }

        if (actual >= desired) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster formation complete ({}/{})", actual, desired);
        } else {
            handleFormationCooldownExpired(actual, desired);
        }
    }

    @Contract
    private void handleFormationCooldownExpired(int actual, int desired) {
        log.info("CTM: Formation cooldown expired, cluster at {}/{}", actual, desired);
        transitionTo(new NodeReconcilerState.Converged());
    }

    @Contract
    private void cancelInFlightProvisions(String reason) {
        if (inFlightProvisions.isEmpty()) {
            return;
        }

        var size = inFlightProvisions.size();

        log.info("CTM: cancelling {} in-flight provision(s) ({})", size, reason);
        inFlightProvisions.values().forEach(Promise::cancel);
        inFlightProvisions.clear();
        inFlightSlotIndices.clear();
    }

    private ProvisionContext buildProvisionContext(NodeId newNodeId) {
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
        // Thread the leader-minted identity through so the provisioned node boots under exactly
        // this id (provider injects it as AETHER_NODE_ID / Docker container name; the node adopts
        // it as `self`). NodeId.id() is `node-<lowercase-ULID>` — alphanumeric + hyphen, a valid
        // Docker container name and cluster boot identity.
        return ProvisionContext.forReplacement(clusterName, newNodeId.id(), peers, snapshotDesiredCoreSize());
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

        if (zoneCounts.isEmpty()) {
            return Option.empty();
        }

        var minCount = zoneCounts.values().stream().mapToLong(Long::longValue).min().orElse(0L);
        var underRepresented = zoneCounts.entrySet().stream().filter(e -> e.getValue() == minCount).map(Map.Entry::getKey).toList();

        if (underRepresented.size() == 1) {
            return Option.some(PlacementHint.zoneHint(underRepresented.getFirst()));
        }

        var overRepresented = zoneCounts.entrySet().stream().filter(e -> e.getValue() > minCount).map(Map.Entry::getKey).collect(Collectors.toSet());

        if (overRepresented.isEmpty()) {
            return Option.empty();
        }

        return Option.some(PlacementHint.antiAffinityHint(overRepresented));
    }

    private String zoneLabel(NodeId nodeId) {
        return observer.get(nodeId)
                       .map(info -> info.labels()
                                        .getOrDefault(LABEL_ZONE, ""))
                       .or("");
    }

    @Contract
    private void cancelSafetyNetPoll() {
        safetyNetTimer.cancel();
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
