// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.aether.config.cluster.PlaceholderConfigResolver;
import org.pragmatica.aether.config.cluster.ReplacementNodeConfigComposer;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshDeploymentConfig;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.config.toml.TomlDocument;
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

import java.net.SocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
                                    AtomicReference<NodeReconcilerState> stateRef,
                                    AtomicBoolean active,
                                    ConcurrentHashMap<NodeId, Promise<?>> inFlightProvisions,
                                    AtomicInteger consecutiveProvisioningFailures,
                                    AtomicLong nextProvisioningAllowedMs,
                                    AtomicLong lastProvisioningFailureMs,
                                    AtomicReference<LastProvisionFailure> lastProvisionFailureRef,
                                    AtomicLong formationAnchorMs,
                                    AtomicBoolean autoHealEnabled,
                                    LongSupplier clock,
                                    Consumer<NodeId> drainCommandSink,
                                    Consumer<NodeId> drainCommandClear,
                                    Supplier<Option<TomlDocument>> resolvedLocalConfig) implements ClusterTopologyManager {
    private static final Logger log = LoggerFactory.getLogger(ClusterTopologyManager.class);
    private static final int MINIMUM_CLUSTER_SIZE = 3;
    private static final int MAX_CONSECUTIVE_PROVISIONING_FAILURES = 3;
    private static final String AETHER_CLUSTER_SECRET_ENV = "AETHER_CLUSTER_SECRET";

    static ClusterTopologyManagerRecord clusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig config,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                                     Supplier<ClusterPhase> phaseSupplier,
                                                                     LongSupplier clock) {
        return clusterTopologyManagerRecord(observer,
                                            lifecycleManager,
                                            config,
                                            deploymentMap,
                                            snapshotSource,
                                            clusterConfigReader,
                                            commandApplier,
                                            phaseSupplier,
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
                                                                     LongSupplier clock,
                                                                     Consumer<NodeId> drainCommandSink,
                                                                     Consumer<NodeId> drainCommandClear) {
        return clusterTopologyManagerRecord(observer,
                                            lifecycleManager,
                                            config,
                                            deploymentMap,
                                            snapshotSource,
                                            clusterConfigReader,
                                            commandApplier,
                                            phaseSupplier,
                                            clock,
                                            drainCommandSink,
                                            drainCommandClear,
                                            Option::none);
    }

    /// #336 production factory overload — additionally wires the leader's OWN RESOLVED config as
    /// `resolvedLocalConfig`. The leader runs with its per-node overlay RESOLVED to literals (the
    /// CLI rendered it from the resolved config); the CTM render path uses it to substitute the
    /// literal `${env:...}` / `${secrets:...}` placeholders left in a replacement's composed overlay
    /// (which is composed from the deliberately-unresolved persisted KV TOML), so a CTM-provisioned
    /// scale-up / auto-heal node boots with resolved credentials instead of crashing on placeholders.
    /// Defaults to `Option::none` (placeholders pass through unchanged, preserving prior behavior)
    /// via the overload above for tests, non-cloud, and legacy callers; only `AetherNode` supplies
    /// the real value.
    static ClusterTopologyManagerRecord clusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig config,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                                     Supplier<ClusterPhase> phaseSupplier,
                                                                     LongSupplier clock,
                                                                     Consumer<NodeId> drainCommandSink,
                                                                     Consumer<NodeId> drainCommandClear,
                                                                     Supplier<Option<TomlDocument>> resolvedLocalConfig) {
        return new ClusterTopologyManagerRecord(observer,
                                                lifecycleManager,
                                                config,
                                                deploymentMap,
                                                snapshotSource,
                                                clusterConfigReader,
                                                commandApplier,
                                                phaseSupplier,
                                                new AtomicReference<>(new NodeReconcilerState.Inactive("not yet activated")),
                                                new AtomicBoolean(false),
                                                new ConcurrentHashMap<>(),
                                                new AtomicInteger(0),
                                                new AtomicLong(0L),
                                                new AtomicLong(0L),
                                                new AtomicReference<>(),
                                                new AtomicLong(clock.getAsLong()),
                                                new AtomicBoolean(true),
                                                clock,
                                                drainCommandSink == null
                                                ? _ -> {}
                                                : drainCommandSink,
                                                drainCommandClear == null
                                                ? _ -> {}
                                                : drainCommandClear,
                                                resolvedLocalConfig == null
                                                ? Option::none
                                                : resolvedLocalConfig);
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

        log.debug("CTM: ClusterConfigKey changed");
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
    }

    @Contract
    @Override
    public void onClusterPhaseChanged(ClusterPhase newPhase) {
        if (newPhase == ClusterPhase.NORMAL) {
            cancelInFlightProvisions("phase transition to NORMAL — restart stability window");
            resetProvisioningCircuit("phase transition to NORMAL");
            log.info("CTM: cluster phase transitioned to NORMAL");

            return;
        }

        cancelInFlightProvisions("phase transition to " + newPhase + " — auto-heal suspended");
        log.info("CTM: cluster phase transitioned to {}", newPhase);
    }

    /// A confirmed CORE join is provisioning-success evidence — symmetric to how a confirmed
    /// departure ([#handleNodeRemoved]) actuates the reap. `NodeJoined` is emitted by the
    /// `MembershipDeltaProjector` (the SOLE `MembershipDecision` emitter) exactly once per a
    /// member's FIRST promotion into MEMBER (FSM `everJoined` pairing), core-role only,
    /// quorum-gated, and idempotent — never on SUSPECT, a flap recovery, or transient churn. So
    /// resetting the #148 provisioning circuit breaker here cannot be tripped by non-join noise:
    /// a genuine replacement reaching live membership clears the consecutive-failure count and the
    /// backoff window, un-stalling auto-heal after a rapid multi-node loss (which the
    /// single-node-heal case never trips). Repeated resets on repeated real joins are harmless —
    /// [#onNodeReady]/[#resetProvisioningCircuit] are idempotent. Leader-owned: the `active.get()`
    /// guard in [#onMembershipDecision] already gates this to the active (leader) CTM.
    @Contract
    private void handleNodeJoined(NodeJoined joined) {
        log.info("CTM: Node {} joined", joined.nodeId());
        onNodeReady(joined.nodeId());
    }

    /// #166 — on confirmed membership-view removal the leader actively reaps the departed node's
    /// container/instance. `NodeRemoved` is emitted only on co-confirmed death (`swimFaulty ∧
    /// livenessGone`) or graceful departure — never on a transient SWIM flap (which stays SUSPECT),
    /// so the reap is safe. `terminateNode` is idempotent: a no-op on an already-exited node, and
    /// the authoritative kill for a phantom whose container is restart-looping at an unreachable
    /// address (e.g. `localhost:6000`) and would otherwise re-appear HEALTHY in SWIM. Without this
    /// reap the phantom self-evicts only by SWIM's `suspectTimeout × 3` residency clock — and not at
    /// all if its container keeps re-registering — leaving `coreCount > coreMax`. Pruning is
    /// leader-owned: the `active.get()` guard in `onMembershipDecision` already gates this to the
    /// active (leader) CTM, satisfying the single-writer rule.
    @Contract
    private void handleNodeRemoved(NodeRemoved removed) {
        log.info("CTM: Node {} removed — reaping container to prevent phantom resurrection", removed.nodeId());
        reapDepartedNode(removed.nodeId());
    }

    /// #166 — a decommissioned node is permanently leaving; reap its container for the same
    /// phantom-prevention reason as [#handleNodeRemoved].
    @Contract
    private void handleNodeDecommissioned(NodeDecommissioned decommissioned) {
        log.warn("CTM: Node {} decommissioned — reaping container", decommissioned.nodeId());
        reapDepartedNode(decommissioned.nodeId());
    }

    /// Idempotent best-effort container reap for a departed node. The `NodeLifecycleManager`
    /// routes through the active `ComputeProvider`; when no provider is configured (non-cloud /
    /// test) the terminate resolves as an unsupported-operation failure that is logged and
    /// swallowed — the failure channel is owned here, never propagated, so this stays a pure
    /// notification side effect of the membership-decision handler.
    @Contract
    private void reapDepartedNode(NodeId departedNodeId) {
        lifecycleManager.terminateNode(departedNodeId).onFailure(cause -> log.debug("CTM: reap of departed node {} not actioned: {}",
                                                                                    departedNodeId,
                                                                                    cause.message()));
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
    public Option<LastProvisionFailure> lastProvisionFailure() {
        return Option.option(lastProvisionFailureRef.get());
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
    public Promise<ProvisionDisposition> provisionReplacement(NodeId newNodeId,
                                                              Option<NodeId> failedPeer,
                                                              Set<NodeId> clusterMembers,
                                                              NodeRole intendedRole) {
        log.info("CTM v2: provisionReplacement requested (newNodeId={}, failedPeer={}, clusterMembers.size={}, intendedRole={})",
                 newNodeId,
                 failedPeer,
                 clusterMembers.size(),
                 intendedRole.value());
        // #148 — runaway-provisioning cap. If a provisioned node crash-loops (boots, fails to
        // become a member, the LeaderReconciler re-derives the same deficit and calls back here),
        // the consecutive-failure counter trips this breaker and provisioning is suspended for a
        // backoff window. Without the gate the auto-heal loop creates containers endlessly (the
        // crash-loop container storm). Return a DEFERRED disposition (not a failure, not a phantom
        // dispatched) so the reconciler removes its in-flight placeholder — nothing was booted, so
        // the raw deficit must stay visible for the next tick to re-poke once the window clears —
        // symmetric with the "no healthy peers" deferral below. `onNodeReady`/`activate`/
        // phase→NORMAL/`setDesiredSize` reset the breaker the moment a replacement actually joins
        // or an operator intervenes.
        if (provisioningCircuitOpen()) {
            log.warn("CTM v2: provisionReplacement suppressed — provisioning circuit OPEN ({} consecutive failures, backoff until {}ms); "
                    + "deferring until the backoff window clears or a node joins.",
                     consecutiveProvisioningFailures.get(),
                     nextProvisioningAllowedMs.get());

            return Promise.success(ProvisionDisposition.deferred(ProvisionDisposition.DeferralReason.CIRCUIT_OPEN));
        }

        var contextBase = buildProvisionContext(newNodeId, intendedRole);
        var memberPeers = liveMemberPeers(clusterMembers);
        var contextSeeded = memberPeers.isEmpty()
                            ? contextBase
                            : contextBase.withPeers(memberPeers);

        if (contextSeeded.peers().or("").isEmpty()) {
            log.warn("CTM v2: provisionReplacement deferred — no healthy peers visible (peers list empty); "
                    + "returning a DEFERRED disposition so the LeaderReconciler removes the in-flight "
                    + "placeholder and retries on its next tick.");

            return Promise.success(ProvisionDisposition.deferred(ProvisionDisposition.DeferralReason.NO_HEALTHY_PEERS));
        }

        var baseSpec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "default", "core", contextSeeded).unwrap();
        var renderedSpec = renderReplacementUserData(contextSeeded, intendedRole).map(baseSpec::withUserData).or(baseSpec);

        return provisionWithZoneRotation(renderedSpec,
                                         replacementZones(intendedRole)).onFailure(this::recordProvisioningFailure)
                                        .map(ClusterTopologyManagerRecord::asDispatched);
    }

    /// A successful boot is a real DISPATCH — a VM is genuinely coming, so the reconciler keeps its
    /// in-flight placeholder. A boot FAILURE stays in the `Promise` failure channel (handled by the
    /// `onFailure(recordProvisioningFailure)` above plus the reconciler's placeholder removal).
    private static ProvisionDisposition asDispatched(InstanceInfo instanceInfo) {
        return ProvisionDisposition.dispatched();
    }

    /// #334 — auto-heal zone rotation. Mirrors the bootstrap rotation (`BootstrapPhaseProvision`):
    /// attempt each configured zone in order, pinning the spec to that zone via
    /// [PlacementHint#zoneHint]; on [EnvironmentError.CapacityUnavailable] advance to the next zone,
    /// on any OTHER failure propagate immediately (non-retryable), and when the list is exhausted
    /// fail with a clear cause. When `zones` is EMPTY the cloud zone rotation does not apply: we
    /// fall back to the existing zone-BALANCING [#computePlacementHint] path with a SINGLE attempt
    /// (backward-compatible — non-cloud Docker/forge providers ignore placement entirely). The
    /// `onFailure(recordProvisioningFailure)` in the caller fires only on the FINAL failure of this
    /// fold. Fully async — no blocking `await`.
    private Promise<InstanceInfo> provisionWithZoneRotation(ProvisionSpec renderedSpec, List<String> zones) {
        if (zones.isEmpty()) {
            var placedSpec = computePlacementHint().map(renderedSpec::withPlacement).or(renderedSpec);

            return lifecycleManager.provisionNode(placedSpec);
        }

        return attemptZone(renderedSpec, zones, 0);
    }

    private Promise<InstanceInfo> attemptZone(ProvisionSpec renderedSpec, List<String> zones, int index) {
        if (index >= zones.size()) {
            return zonesExhausted(zones).promise();
        }

        var zone = zones.get(index);
        var zonedSpec = renderedSpec.withPlacement(PlacementHint.zoneHint(zone));

        return lifecycleManager.provisionNode(zonedSpec)
                               .fold(result -> routeZoneAttempt(result, renderedSpec, zones, index, zone));
    }

    private Promise<InstanceInfo> routeZoneAttempt(Result<InstanceInfo> result,
                                                   ProvisionSpec renderedSpec,
                                                   List<String> zones,
                                                   int index,
                                                   String zone) {
        return result.fold(cause -> rotateOrFail(renderedSpec, zones, index, zone, cause), Promise::success);
    }

    private Promise<InstanceInfo> rotateOrFail(ProvisionSpec renderedSpec,
                                               List<String> zones,
                                               int index,
                                               String zone,
                                               Cause cause) {
        if (!isCapacityUnavailable(cause)) {
            return cause.promise();
        }

        logZoneRotation(zone, nextZoneLabel(zones, index));

        return attemptZone(renderedSpec, zones, index + 1);
    }

    private static boolean isCapacityUnavailable(Cause cause) {
        return cause instanceof EnvironmentError.CapacityUnavailable;
    }

    private static String nextZoneLabel(List<String> zones, int currentIndex) {
        var next = currentIndex + 1;

        return next < zones.size()
               ? zones.get(next)
               : "(no more zones)";
    }

    @Contract
    private void logZoneRotation(String fromZone, String toZone) {
        log.warn("CTM v2: provisionReplacement zone '{}' capacity-unavailable — rotating to '{}'", fromZone, toZone);
    }

    private static Cause zonesExhausted(List<String> zones) {
        return Causes.cause("CTM v2: provisionReplacement exhausted all configured zones on capacity unavailability: " + String.join(", ",
                                                                                                                                     zones));
    }

    /// #334 — the ordered zone list to rotate over for a replacement of `intendedRole`, reusing the
    /// SAME parse path as [#renderReplacementUserData]: the persisted cluster TOML re-parsed via
    /// [ClusterBootstrapConfigParser], the cloud [SourceProfile] backing the role, and its
    /// [SourceProfile#effectiveZones]. Empty (single-attempt, no zone pin) when the persisted TOML
    /// is blank/unparseable, no cloud source backs the role, or that source declares no zones.
    private List<String> replacementZones(NodeRole intendedRole) {
        return Option.option(clusterConfigReader.get().map(ClusterConfigValue::tomlContent).or(""))
                     .filter(toml -> !toml.isBlank())
                     .flatMap(ClusterTopologyManagerRecord::parseConfig)
                     .flatMap(config -> cloudSourceFor(config, intendedRole))
                     .map(SourceProfile::effectiveZones)
                     .or(List.of());
    }

    private static Option<ClusterBootstrapConfig> parseConfig(String toml) {
        return ClusterBootstrapConfigParser.parse(toml).option();
    }

    /// Render the replacement node's cloud-init user-data so a CTM-provisioned (cloud) replacement
    /// boots with the SAME identity a bootstrap-minted node receives — the new node-id, the LIVE
    /// PEERS list (not the dead seed peers baked into the static `[cloud...] user_data` blob), the
    /// cluster name + secret, the [ClusterIdentityEnv] allow-list and dev-mode posture — and the
    /// runtime payload for the node's runtime profile. WITHOUT this the Hetzner provider falls back
    /// to the static bootstrap blob (now-dead seed PEERS + stale identity) and the replacement
    /// never joins — the cloud-only auto-heal defect this method exists to close.
    ///
    /// Uses the SHARED [NodeUserDataRenderer] (also used by the CLI bootstrap path) so the two
    /// scripts cannot drift. The renderer inputs are reconstructed from the persisted cluster
    /// config (`ClusterConfigValue.tomlContent()`, re-parsed via [ClusterBootstrapConfigParser]):
    /// the cloud [SourceProfile] backing `intendedRole` and the composed `aether.toml`. The
    /// cluster secret rides the [ClusterIdentityEnv] allow-list from the running node's env exactly
    /// as the renderer's `emitIdentityEnv` does, so it is not threaded here.
    ///
    /// Degrades to [Option#empty] (NO user-data → provider keeps its existing `config.userData()`
    /// fallback) when the persisted TOML is blank/unparseable or no cloud source backs the role —
    /// non-cloud (Docker/forge) providers inject identity from the [ProvisionContext] directly and
    /// never consult user-data, so an absent render is correct there.
    private Option<String> renderReplacementUserData(ProvisionContext context, NodeRole intendedRole) {
        return Option.option(clusterConfigReader.get().map(ClusterConfigValue::tomlContent).or(""))
                     .filter(toml -> !toml.isBlank())
                     .flatMap(toml -> renderFromToml(toml, context, intendedRole));
    }

    private Option<String> renderFromToml(String toml, ProvisionContext context, NodeRole intendedRole) {
        return ClusterBootstrapConfigParser.parse(toml)
                                           .option()
                                           .flatMap(config -> renderFromConfig(config, context, intendedRole));
    }

    private Option<String> renderFromConfig(ClusterBootstrapConfig config,
                                            ProvisionContext context,
                                            NodeRole intendedRole) {
        return cloudSourceFor(config, intendedRole).flatMap(source -> renderFromSource(config,
                                                                                       source,
                                                                                       context,
                                                                                       intendedRole));
    }

    private Option<String> renderFromSource(ClusterBootstrapConfig config,
                                            SourceProfile source,
                                            ProvisionContext context,
                                            NodeRole intendedRole) {
        return ReplacementNodeConfigComposer.compose(config,
                                                     source,
                                                     clusterSecretFromEnv(),
                                                     leaderSshKeyIds()).option()
                                                    .map(this::resolvePlaceholders)
                                                    .map(composed -> NodeUserDataRenderer.render(config,
                                                                                                 source,
                                                                                                 intendedRole,
                                                                                                 context.nodeId().or(""),
                                                                                                 0,
                                                                                                 clusterSecretFromEnv().or(""),
                                                                                                 config.cluster().name(),
                                                                                                 composed,
                                                                                                 authorizedKeysFrom(config),
                                                                                                 peersList(context)));
    }

    /// #442 — the operator SSH key ids the LEADER itself was provisioned with, read from its OWN
    /// resolved `[cloud.compute] ssh_key_ids` (the same `resolvedLocalConfig` the #336 placeholder
    /// resolution uses). Threaded into the replacement's composed config so a replacement that later
    /// becomes leader inherits the keys and provisions ITS replacements from config — extending the
    /// bootstrap-seeded inheritance across replacement generations WITHOUT persisting the ids in the
    /// KV cluster config (a stored-format change). Empty for non-cloud / tests where no resolved
    /// local config is available, in which case the provider's name-prefix fallback still applies.
    private List<Long> leaderSshKeyIds() {
        return resolvedLocalConfig.get()
                                  .flatMap(toml -> toml.getString("cloud.compute", "ssh_key_ids"))
                                  .map(ClusterTopologyManagerRecord::parseSshKeyIds)
                                  .or(List.of());
    }

    private static List<Long> parseSshKeyIds(String raw) {
        return Arrays.stream(raw.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .map(Number::parseLong)
                     .flatMap(result -> result.option().stream())
                     .toList();
    }

    /// #336 — substitute the literal `${env:...}` / `${secrets:...}` placeholders the composed
    /// overlay inherited from the deliberately-unresolved persisted KV TOML with the leader's OWN
    /// resolved values at the same TOML path (via [PlaceholderConfigResolver]). The leader runs with
    /// its per-node overlay RESOLVED to literals, so a CTM-provisioned replacement boots with real
    /// credentials instead of crashing on placeholders. Passes `composed` through UNCHANGED when no
    /// resolved local config is available (non-cloud / forge / tests) — preserving prior behavior —
    /// or when the resolution itself reports a failure (best-effort: a partial render that still
    /// carries a placeholder is no worse than the pre-fix behavior the renderer already handled).
    private TomlDocument resolvePlaceholders(TomlDocument composed) {
        return resolvedLocalConfig.get()
                                  .map(resolvedSource -> PlaceholderConfigResolver.resolve(composed, resolvedSource).or(composed))
                                  .or(composed);
    }

    /// Operator SSH public keys persisted into the cluster config TOML at bootstrap formation
    /// (`[infrastructure.ssh] authorized_keys`). Threaded into the SHARED [NodeUserDataRenderer] so a
    /// CTM auto-heal replacement injects them into its cloud-init `authorized_keys` — giving the
    /// replacement VM the SAME operator SSH access a bootstrap-minted node receives, by the SAME
    /// user-data mechanism. Empty when the persisted TOML carries no keys (no `[infrastructure.ssh]`
    /// section or no `authorized_keys`), which leaves the rendered script free of an SSH block exactly
    /// as a keyless bootstrap would.
    private static List<String> authorizedKeysFrom(ClusterBootstrapConfig config) {
        return config.infrastructure()
                     .ssh()
                     .map(SshDeploymentConfig::authorizedKeys)
                     .or(List.of());
    }

    /// The cloud [SourceProfile] backing the given role: the first `CLOUD`-type source whose role
    /// table declares the role. Only cloud replacements consume user-data, so non-cloud sources are
    /// skipped (the empty result degrades the render to a no-op above).
    private static Option<SourceProfile> cloudSourceFor(ClusterBootstrapConfig config, NodeRole role) {
        return Option.from(config.sources()
                                 .values()
                                 .stream()
                                 .filter(source -> source.type() == SourceType.CLOUD)
                                 .filter(source -> source.roles()
                                                         .containsKey(role))
                                 .findFirst());
    }

    /// The cluster secret as the running (leader) node sees it in its own environment — the same
    /// source the renderer's `emitIdentityEnv` reads it from, so the secret baked into the
    /// replacement's `AETHER_CLUSTER_SECRET` matches the live cluster's.
    private static Option<String> clusterSecretFromEnv() {
        return Option.option(System.getenv(AETHER_CLUSTER_SECRET_ENV)).filter(s -> !s.isBlank());
    }

    private static List<String> peersList(ProvisionContext context) {
        return context.peers()
                      .filter(peers -> !peers.isBlank())
                      .map(peers -> List.of(peers.split(",")))
                      .or(List.<String> of());
    }

    /// #148 — the provisioning circuit is OPEN when the consecutive-failure count has reached the
    /// cap AND the backoff window has not yet elapsed. A `0` backoff stamp (never-tripped / reset)
    /// can never gate. The window is `nowMs() < nextProvisioningAllowedMs`, so once it elapses a
    /// single probe provision is allowed; a fresh failure re-arms the window, a success (node
    /// joins → `onNodeReady` → `resetProvisioningCircuit`) clears it.
    private boolean provisioningCircuitOpen() {
        return consecutiveProvisioningFailures.get() >= MAX_CONSECUTIVE_PROVISIONING_FAILURES && nowMs() < nextProvisioningAllowedMs.get();
    }

    /// #148 — record a provisioning failure: bump the consecutive-failure counter and, once the
    /// cap is reached, arm the backoff window (`provisioningTimeout`) during which further
    /// provisioning is suppressed. The counter is reset on the next real node-join / activation /
    /// phase-NORMAL / operator reset via [#resetProvisioningCircuit].
    @Contract
    private void recordProvisioningFailure(Cause cause) {
        var failures = consecutiveProvisioningFailures.incrementAndGet();

        lastProvisionFailureRef.set(new LastProvisionFailure(cause.message(), nowMs()));
        if (failures >= MAX_CONSECUTIVE_PROVISIONING_FAILURES) {
            nextProvisioningAllowedMs.set(nowMs() + autoHealConfig.provisioningTimeout().millis());
            log.warn("CTM v2: provisioning circuit TRIPPED after {} consecutive failures — suspending provisioning for {}ms (last: {})",
                     failures,
                     autoHealConfig.provisioningTimeout().millis(),
                     cause.message());
        } else {
            log.warn("CTM v2: provisioning failure {}/{} (last: {})",
                     failures,
                     MAX_CONSECUTIVE_PROVISIONING_FAILURES,
                     cause.message());
        }
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
            log.info("CTM: Cluster at target size, skipping formation");
        } else if (clusterWasFormed && effectiveActual >= desired - 1) {
            activateWithLeaderFailover(effectiveActual, desired);
        } else {
            activateWithFormation();
        }
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
    }

    private ProvisionContext buildProvisionContext(NodeId newNodeId, NodeRole intendedRole) {
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
        // Docker container name and cluster boot identity. The intended role (Wave 2 / W4) rides
        // the same context so the provider stamps AETHER_ROLE / aether.role explicitly instead of
        // inheriting the provisioning host's env.
        return ProvisionContext.forReplacement(clusterName,
                                               intendedRole.value(),
                                               newNodeId.id(),
                                               peers,
                                               snapshotDesiredCoreSize());
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

    private static String stateName(NodeReconcilerState state) {
        return switch (state) {
            case NodeReconcilerState.Inactive inactive -> "Inactive(" + inactive.reason() + ")";
            case NodeReconcilerState.Forming _ -> "Forming";
            case NodeReconcilerState.Converged _ -> "Converged";
            case NodeReconcilerState.Reconciling r -> "Reconciling(" + r.currentSize() + "/" + r.targetSize() + ")";
        };
    }
}
