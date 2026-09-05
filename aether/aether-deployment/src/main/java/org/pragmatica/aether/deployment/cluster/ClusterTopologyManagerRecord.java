// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
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
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
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
                                    Supplier<Option<AutoHealStateValue>> autoHealStateReader,
                                    LongSupplier clock,
                                    Consumer<NodeId> drainCommandSink,
                                    Consumer<NodeId> drainCommandClear,
                                    Supplier<Option<TomlDocument>> resolvedLocalConfig,
                                    AtomicBoolean workerReconcileInFlight,
                                    AtomicBoolean workerReconcilePending) implements ClusterTopologyManager {
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
                                            Option::none,
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
                                            resolvedLocalConfig,
                                            Option::none);
    }

    /// Canonical factory. `autoHealStateReader` (#685) is the durable KV read backing
    /// [ClusterTopologyManager#isAutoHealEnabled] — defaulted to `Option::none` (absent key = enabled)
    /// by every overload above except `AetherNode`'s production wiring, which supplies a direct
    /// `KVStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class)` lookup.
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
                                                                     Supplier<Option<TomlDocument>> resolvedLocalConfig,
                                                                     Supplier<Option<AutoHealStateValue>> autoHealStateReader) {
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
                                                Option.option(autoHealStateReader).or((Supplier<Option<AutoHealStateValue>>) Option::none),
                                                clock,
                                                Option.option(drainCommandSink).or(_ -> {}),
                                                Option.option(drainCommandClear).or(_ -> {}),
                                                Option.option(resolvedLocalConfig).or((Supplier<Option<TomlDocument>>) Option::none),
                                                new AtomicBoolean(false),
                                                new AtomicBoolean(false));
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
    public Promise<Unit> setDesiredCount(SourceName sourceName, NodeRole role, int count) {
        // The quorum floor is a property of the CORE tier only; worker and spot tiers may legitimately
        // scale to zero.
        if (role == NodeRole.CORE && count < MINIMUM_CLUSTER_SIZE) {
            return Causes.cause("Cluster size cannot be below " + MINIMUM_CLUSTER_SIZE + " (quorum requirement)").promise();
        }

        resetProvisioningCircuit("setDesiredCount " + sourceName + "/" + role.value() + "=" + count);

        return applyDesiredCount(clusterConfigReader,
                                 commandApplier,
                                 sourceName.value(),
                                 role.value(),
                                 count,
                                 DESIRED_COUNT_CAS_ATTEMPTS);
    }

    static final int DESIRED_COUNT_CAS_ATTEMPTS = 3;

    /// Fenced read-modify-write of one `(source, role)` desired count (RFC-0018, #570).
    ///
    /// The applier's successor fence rejects a `Put` built on a stale read, so a concurrent writer
    /// (a scale racing an auto-heal) can no longer be silently overwritten — but the rejection is
    /// invisible in the apply result: under batch merging every submitter receives the FULL merged
    /// result list and cannot attribute an element to its own command. So this loop confirms
    /// semantically instead — after the apply resolves, the local state machine has applied the
    /// batch (the engine runs `process` before resolving the promise, and `setDesiredCount` is
    /// leader-gated so the reader is the applying node), and a re-read tells us whether the count
    /// we asked for landed. If it did not, we lost the race: recompute from the fresh committed
    /// value and retry, bounded. If the reader lags the commit, a retry recomputes the same version
    /// and loses again — burning an attempt but never lying.
    ///
    /// Static and collaborator-injected so the loop is testable without wiring the full CTM record.
    static Promise<Unit> applyDesiredCount(Supplier<Option<ClusterConfigValue>> reader,
                                           Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                           String sourceName,
                                           String role,
                                           int count,
                                           int attemptsLeft) {
        var existing = reader.get();

        if (existing.isEmpty()) {
            return Causes.cause("ClusterConfigValue atom missing — bootstrap must seed it before scale operations are accepted").promise();
        }

        var updated = existing.unwrap().withDesiredCount(sourceName, role, count);
        @SuppressWarnings("unchecked")
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(ClusterConfigKey.CURRENT,
                                                                                                     updated);

        return applier.apply(List.of(command))
                      .onFailure(cause -> log.warn("CTM: failed to write ClusterConfigValue {}/{}={}: {}",
                                                   sourceName,
                                                   role,
                                                   count,
                                                   cause.message()))
                      .flatMap(_ -> confirmOrRetry(reader,
                                                   applier,
                                                   sourceName,
                                                   role,
                                                   count,
                                                   attemptsLeft,
                                                   updated.configVersion()));
    }

    private static Promise<Unit> confirmOrRetry(Supplier<Option<ClusterConfigValue>> reader,
                                                Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                String sourceName,
                                                String role,
                                                int count,
                                                int attemptsLeft,
                                                long intendedVersion) {
        var landed = reader.get().map(fresh -> fresh.desiredCountFor(sourceName, role) == count).or(false);

        if (landed) {
            log.info("CTM: wrote ClusterConfigValue {}/{}={} (configVersion={})",
                     sourceName,
                     role,
                     count,
                     intendedVersion);

            return Promise.unitPromise();
        }

        log.warn("CTM: desired-count CAS lost — {}/{}={} did not land at configVersion={}, {} attempt(s) left",
                 sourceName,
                 role,
                 count,
                 intendedVersion,
                 attemptsLeft - 1);
        if (attemptsLeft <= 1) {
            return Causes.cause("Desired-count write for " + sourceName
                               + "/" + role
                               + "=" + count
                               + " lost the version race " + DESIRED_COUNT_CAS_ATTEMPTS
                               + " times — a concurrent writer keeps advancing the cluster config; re-issue the scale").promise();
        }

        return applyDesiredCount(reader, applier, sourceName, role, count, attemptsLeft - 1);
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
        lifecycleManager.terminateNode(departedNodeId)
                        .onFailure(cause -> log.debug("CTM: reap of departed node {} not actioned: {}",
                                                      departedNodeId,
                                                      cause.message()));
    }

    @Contract
    private void resetProvisioningCircuit(String reason) {
        var prev = consecutiveProvisioningFailures.getAndSet(0);

        nextProvisioningAllowedMs.set(0L);
        lifecycleManager.resetProvisionerState(resolveClusterName());
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

    /// #685 — durable read-through. Absent key (fresh/empty KV, pre-#685 clusters) means enabled:
    /// the operator has never disabled auto-heal, so the pre-#685 default holds. A read reflects the
    /// log applied LOCALLY; the disable becomes visible on a node when that node applies the
    /// committed Put — bounded by consensus latency, not zero; a node behind on apply answers the
    /// previous value until then.
    @Override
    public boolean isAutoHealEnabled() {
        return autoHealStateReader.get().map(AutoHealStateValue::enabled).or(true);
    }

    /// #685 — writes the operator's decision through the same consensus-backed KV channel as
    /// [#setDesiredCount], so every node — including one that never received this call directly —
    /// converges on it once it applies the committed Put. See [#isAutoHealEnabled] for the exact
    /// visibility guarantee.
    @Override
    public Promise<Boolean> setAutoHealEnabled(boolean enabled, String reason) {
        var prev = isAutoHealEnabled();

        if (prev == enabled) {
            log.info("CTM: auto-heal already {} (reason: {}) — no-op",
                     enabled
                     ? "enabled"
                     : "disabled",
                     reason);

            return Promise.success(prev);
        }

        @SuppressWarnings("unchecked")
        var command = (KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(AetherKey.AutoHealStateKey.SINGLETON,
                                                                                                       AutoHealStateValue.autoHealStateValue(enabled, reason));

        return commandApplier.apply(List.of(command))
                              .onFailure(cause -> log.warn("CTM: failed to write AutoHealStateValue enabled={} reason={}: {}",
                                                           enabled,
                                                           reason,
                                                           cause.message()))
                              .map(_ -> {
                                  log.warn("CTM: auto-heal {} (operator: {}) — prior state was {}",
                                           enabled
                                           ? "ENABLED"
                                           : "DISABLED",
                                           reason,
                                           prev
                                           ? "enabled"
                                           : "disabled");

                                  return prev;
                              });
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
    /// live set — instead of `observer.topology()` ∩ `isDiscoveredPeer` — keeps just-killed
    /// hostnames out of the PEERS list, preventing DOA replacements (dead-host PEERS →
    /// QUIC NPE). If `clusterMembers` is empty (cold paths), the seed falls back to
    /// `buildProvisionContext`'s topology-derived peers, which additionally intersects with
    /// `liveObservedPeer` (#678) so the cold-path fallback keeps the same dead-host protection
    /// this live-set path gets for free from `clusterMembers` itself. `failedPeer` is
    /// observability-only.
    ///
    /// The source profile name is resolved here from the persisted cluster config
    /// ([#replacementSourceName]) rather than hardcoded, so a core replacement is stamped with the
    /// `aether-source` label of the source that actually backs its role.
    @Override
    public Promise<ProvisionDisposition> provisionReplacement(NodeId newNodeId,
                                                              Option<NodeId> failedPeer,
                                                              Set<NodeId> clusterMembers,
                                                              NodeRole intendedRole) {
        return provisionReplacement(newNodeId,
                                    failedPeer,
                                    clusterMembers,
                                    intendedRole,
                                    replacementSourceName(intendedRole));
    }

    /// RFC-0017 stage 5 — the source-explicit form. `sourceName` becomes the provider's
    /// `aether-source` label, which is one third of the selector the worker reconcile pass lists
    /// ACTUAL inventory with, so it MUST be the name the desired-topology entry was published
    /// under. The worker path passes `entry.sourceName()` verbatim; the core auto-heal path above
    /// resolves it from the persisted cluster config.
    private Promise<ProvisionDisposition> provisionReplacement(NodeId newNodeId,
                                                               Option<NodeId> failedPeer,
                                                               Set<NodeId> clusterMembers,
                                                               NodeRole intendedRole,
                                                               SourceName sourceName) {
        log.info("CTM v2: provisionReplacement requested (newNodeId={}, failedPeer={}, clusterMembers.size={}, intendedRole={}, source={})",
                 newNodeId,
                 failedPeer,
                 clusterMembers.size(),
                 intendedRole.value(),
                 sourceName);
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

        var contextBase = buildProvisionContext(newNodeId, intendedRole, sourceName);
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

        var baseSpec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                                   roleInstanceType(intendedRole),
                                                   intendedRole.value(),
                                                   contextSeeded)
                                    .unwrap();
        var renderedSpec = renderReplacementUserData(contextSeeded, intendedRole).map(baseSpec::withUserData)
                                                    .or(baseSpec);

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

    /// RFC-0017 stage 5 — the instance type for a provision of `intendedRole`, resolved from the
    /// role's OWN sub-table in the persisted cluster TOML (same parse path as
    /// [#replacementZones]). The spec used to hardcode `"default"` with role string `"core"`,
    /// which was survivable while only core replacements flowed through here — a worker provision
    /// would have booted on the CORE's fallback size. Falls back to `"default"` (provider-level
    /// `[cloud.compute] server_type` then applies) when the TOML is unparseable or the role
    /// declares no instance type.
    private String roleInstanceType(NodeRole intendedRole) {
        return Option.option(clusterConfigReader.get().map(ClusterConfigValue::tomlContent).or(""))
                     .filter(toml -> !toml.isBlank())
                     .flatMap(ClusterTopologyManagerRecord::parseConfig)
                     .flatMap(config -> cloudSourceFor(config, intendedRole))
                     .flatMap(source -> Option.option(source.roles().get(intendedRole)))
                     .flatMap(role -> role.instanceType())
                     .or("default");
    }

    /// RFC-0017 stage 5 — the source profile NAME backing a provision of `intendedRole`, resolved
    /// from the persisted cluster TOML through the SAME [#cloudSourceFor] lookup that already
    /// resolves the role's zones and instance type, so all three ride one source profile. This is
    /// what the provider stamps as `aether-source`.
    ///
    /// Falls back to [ProvisionContext#DEFAULT_SOURCE_NAME] when no cloud source backs the role —
    /// the TOML is blank/unparseable (tests, forge) or the provider is non-cloud (Docker) and has
    /// no source concept at all. That fallback is honest rather than convenient: there is no source
    /// name to round-trip, and nothing lists those instances by a source-scoped selector. The
    /// worker reconcile path never relies on it — it passes the topology entry's own source name,
    /// which is authoritative and, under multi-source topologies, the only correct answer
    /// (`cloudSourceFor` returns the FIRST cloud source declaring the role).
    private SourceName replacementSourceName(NodeRole intendedRole) {
        return Option.option(clusterConfigReader.get().map(ClusterConfigValue::tomlContent).or(""))
                     .filter(toml -> !toml.isBlank())
                     .flatMap(ClusterTopologyManagerRecord::parseConfig)
                     .flatMap(config -> cloudSourceFor(config, intendedRole))
                     .map(SourceProfile::name)
                     .or(ProvisionContext.DEFAULT_SOURCE_NAME);
    }

    /// RFC-0017 stage 5 — reconcile ACTUAL worker/spot cloud inventory toward the desired
    /// per-(source, role) topology published in cluster state. The core tier is deliberately NOT
    /// touched here: core reconciliation lives in the hardened [LeaderReconciler]-driven path with
    /// its quorum-safety, cold-start and debounce machinery, none of which applies to workers — a
    /// worker deficit is never quorum-ambiguous and a worker surplus never threatens consensus.
    ///
    /// ACTUAL is the provider's label inventory (`aether-cluster`/`aether-source`/`aether-role`),
    /// not SWIM membership: for create/destroy decisions, "the VM exists" is the honest ground
    /// truth — a created-but-not-yet-joined worker must not be double-provisioned.
    ///
    /// Leader-gated by `active` (the same guard the membership actuator path uses) and serialized
    /// by `workerReconcileInFlight` — config commits can arrive faster than a pass completes, and
    /// two concurrent passes would both see the same deficit and double-provision. A trigger that
    /// arrives mid-pass is NOT dropped: it is recorded in `workerReconcilePending` and replayed as
    /// exactly one follow-up pass when the in-flight one releases. Replay cannot self-perpetuate —
    /// the follow-up consumes the flag before it runs, so only a genuinely new external trigger can
    /// set it again. Deferred provisions (open circuit, no peers) surface on the next commit or
    /// leader activation, matching the deferral semantics of [#provisionReplacement].
    @Contract
    @Override
    public void reconcileWorkerTopology() {
        if (!active.get()) {
            return;
        }

        if (!workerReconcileInFlight.compareAndSet(false, true)) {
            // The in-flight pass may already have read the state this trigger is about, so it
            // cannot stand in for it. Record the miss and let the completing pass replay it.
            workerReconcilePending.set(true);
            log.debug("CTM: worker reconcile already in flight — deferring trigger to a follow-up pass");

            return;
        }

        runWorkerReconcilePass().onResult(_ -> completeWorkerReconcilePass());
    }

    /// Release the serialization flag FIRST, then replay a missed trigger. In that order a trigger
    /// racing the release either wins the CAS and runs its own pass, or loses it and re-arms
    /// `workerReconcilePending` for whichever pass is now in flight — no interleaving drops it. The
    /// replay re-enters [#reconcileWorkerTopology], so it is leader-gated exactly like any other
    /// trigger.
    @Contract
    private void completeWorkerReconcilePass() {
        workerReconcileInFlight.set(false);
        if (workerReconcilePending.compareAndSet(true, false)) {
            log.debug("CTM: replaying worker reconcile trigger deferred during the previous pass");
            reconcileWorkerTopology();
        }
    }

    private Promise<Unit> runWorkerReconcilePass() {
        return clusterConfigReader.get()
                                  .fold(Promise::unitPromise, this::reconcileWorkerEntries);
    }

    private Promise<Unit> reconcileWorkerEntries(ClusterConfigValue config) {
        var entries = config.desiredTopology().stream().filter(entry -> !entry.isCore()).toList();
        var pass = Promise.unitPromise();

        for (var entry : entries) {
            pass = pass.flatMap(_ -> reconcileWorkerEntry(ClusterName.maybeClusterName(config.clusterName()),
                                                          entry));
        }

        return pass;
    }

    private Promise<Unit> reconcileWorkerEntry(Option<ClusterName> clusterName, AetherValue.TopologyEntry entry) {
        // Renders an unresolvable persisted name as the EMPTY selector value, byte-identical to the
        // historical `.or("")` — the KV `ClusterConfigValue.clusterName` is a `String` written by the
        // bootstrap parser (which validated it), so the empty case is unreachable in practice and is
        // kept only so this pass cannot change which instances it counts.
        var filter = Map.of("aether-cluster",
                            clusterName.map(ClusterName::value).or(""),
                            "aether-source",
                            entry.sourceName(),
                            "aether-role",
                            entry.role());

        return lifecycleManager.listInstances(filter)
                               .flatMap(actual -> applyWorkerDelta(entry, actual))
                               .onFailure(cause -> log.warn("CTM: worker reconcile for {}/{} failed: {}",
                                                            entry.sourceName(),
                                                            entry.role(),
                                                            cause.message()))
                               .fold(_ -> Promise.unitPromise());
    }

    private Promise<Unit> applyWorkerDelta(AetherValue.TopologyEntry entry, List<InstanceInfo> actual) {
        var delta = entry.count() - actual.size();

        if (delta == 0) {
            return Promise.unitPromise();
        }

        log.info("CTM: worker topology {}/{} — desired={}, actual={}, {} {}",
                 entry.sourceName(),
                 entry.role(),
                 entry.count(),
                 actual.size(),
                 delta > 0
                 ? "provisioning"
                 : "terminating",
                 Math.abs(delta));

        return delta > 0
               ? provisionWorkers(entry, delta)
               : terminateSurplusWorkers(actual, -delta);
    }

    private Promise<Unit> provisionWorkers(AetherValue.TopologyEntry entry, int deficit) {
        var role = NodeRole.nodeRole(entry.role()).option().or(NodeRole.WORKER);
        var members = observer.coreNodes();
        var pass = Promise.unitPromise();

        for (int i = 0; i < deficit; i++) {
            var minted = mintWorkerNodeId(entry, i);

            pass = pass.flatMap(_ -> provisionReplacement(minted, Option.none(), members, role, workerSourceName(entry)).mapToUnit());
        }

        return pass;
    }

    /// The `aether/slice` boundary conversion for the worker path. [AetherValue.TopologyEntry] keeps a
    /// `String` source name by design — that module does not depend on this layer — so the deployment
    /// layer types it here, exactly as it already does for `role`. The TOTAL conversion is used rather
    /// than the validating factory because a topology entry is minted from a parsed config's source key
    /// and cannot be blank, and because a blank one must keep reaching the provider's fail-closed
    /// firewall check (which names this very fallback) instead of aborting the whole reconcile pass.
    private static SourceName workerSourceName(AetherValue.TopologyEntry entry) {
        return SourceName.sourceNameOrDefault(entry.sourceName());
    }

    /// Base36 leader-clock suffix: unique across passes, and LEXICOGRAPHICALLY LATER than any
    /// bootstrap-minted `<source>-<role>-<index>` sibling (digits sort before `r`), so
    /// newest-first surplus termination reaps cluster-provisioned workers before bootstrap ones.
    /// Leader-side only — never evaluated in the consensus applier, so the wall clock is safe here.
    private NodeId mintWorkerNodeId(AetherValue.TopologyEntry entry, int ordinal) {
        return NodeId.nodeId(entry.sourceName()
                            + "-" + entry.role()
                            + "-r" + Long.toString(clock.getAsLong(), 36)
                            + "-" + ordinal).unwrap();
    }

    /// Newest first (REQ-SCALE-03 ordering by id: minted `-r<base36-clock>` ids sort after
    /// bootstrap `-<index>` ids, and later mints sort after earlier ones). An instance without a
    /// node-id label cannot be terminated through the node-id path and is skipped — pre-#579
    /// orphans are the cloud reaper's job, not the reconciler's.
    private Promise<Unit> terminateSurplusWorkers(List<InstanceInfo> actual, int surplus) {
        var victims = actual.stream()
                            .flatMap(instance -> instance.nodeId()
                                                         .stream())
                            .sorted(Comparator.<String> naturalOrder().reversed())
                            .limit(surplus)
                            .toList();
        var pass = Promise.unitPromise();

        for (var victim : victims) {
            pass = pass.flatMap(_ -> lifecycleManager.terminateNode(NodeId.nodeId(victim).unwrap())
                                                     .onFailure(cause -> log.warn("CTM: worker termination of {} failed: {}",
                                                                                  victim,
                                                                                  cause.message()))
                                                     .fold(_ -> Promise.unitPromise()));
        }

        return pass;
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
                                                     intendedRole,
                                                     clusterSecretFromEnv(),
                                                     leaderSshKeyIds())
                                            .option()
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
                     .flatMap(result -> result.option()
                                              .stream())
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
        var remoteEntries = clusterMembers.stream()
                                          .flatMap(nodeId -> observer.get(nodeId)
                                                                     .stream())
                                          .map(ClusterTopologyManagerRecord::formatPeerEntry)
                                          .filter(entry -> !entry.equals(selfEntry));

        return Stream.concat(Stream.of(selfEntry),
                             remoteEntries)
                     .distinct()
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
        lifecycleManager.terminateNode(targetNodeId)
                        .onFailure(cause -> log.warn("CTM v2: grace-terminate of {} failed: {}",
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
        // RFC-0017 stage 5 — leader gain is a worker-convergence point: a scale committed under the
        // previous leader may have died mid-provisioning, and only the active CTM acts on it.
        reconcileWorkerTopology();
    }

    @Contract
    private void activateWithCurrentTopology() {
        var actual = observer.reportedActiveNodeCount();
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

        var actual = observer.reportedActiveNodeCount();
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

    /// The cluster this CTM heals, parsed from the KV `ClusterConfigValue`. The KV record keeps a
    /// `String` (the `aether/slice` module deliberately does not depend on these layers), so the
    /// conversion happens HERE, once, and both consumers — the provisioner-state reset sweep and the
    /// replacement provisioning context — read the same [Option]. Empty means the cluster config is
    /// not yet seeded; a provider then declines to stamp or sweep rather than guessing a scope.
    private Option<ClusterName> resolveClusterName() {
        return clusterConfigReader.get()
                                  .map(ClusterConfigValue::clusterName)
                                  .flatMap(ClusterName::maybeClusterName);
    }

    private ProvisionContext buildProvisionContext(NodeId newNodeId, NodeRole intendedRole, SourceName sourceName) {
        // Always include self as a fallback bootstrap target — the CTM runs on the leader, which
        // is alive by definition. Without this fallback, transient "no healthy remote peers"
        // windows during chaos (e.g., a leader has just decommissioned several SWIM-faulty peers
        // and the surviving peer's health entries are still propagating) would yield an empty
        // PEERS list, the new container would cold-boot in isolation, and `DockerComputeProvider.
        // preflightCheck` would defensively reject it. Including self guarantees the replacement
        // can always reach at least one live consensus peer.
        var selfEntry = formatPeerEntry(observer.self());
        var isDiscoveredAndLive = ((Predicate<NodeId>) this::isDiscoveredPeer).and(liveObservedPeer());
        var remoteEntries = observer.topology()
                                    .stream()
                                    .filter(isDiscoveredAndLive)
                                    .flatMap(nodeId -> observer.get(nodeId)
                                                               .stream())
                                    .map(ClusterTopologyManagerRecord::formatPeerEntry)
                                    .filter(entry -> !entry.equals(selfEntry));
        var peers = Stream.concat(Stream.of(selfEntry), remoteEntries).collect(Collectors.joining(","));
        var clusterName = resolveClusterName();
        // Thread the leader-minted identity through so the provisioned node boots under exactly
        // this id (provider injects it as AETHER_NODE_ID / Docker container name; the node adopts
        // it as `self`). NodeId.id() is `node-<lowercase-ULID>` — alphanumeric + hyphen, a valid
        // Docker container name and cluster boot identity. The intended role (Wave 2 / W4) rides
        // the same context so the provider stamps AETHER_ROLE / aether.role explicitly instead of
        // inheriting the provisioning host's env, and `sourceName` rides it so the provider's
        // `aether-source` label round-trips with the reconcile pass's inventory selector.
        return ProvisionContext.forReplacement(clusterName,
                                               intendedRole.value(),
                                               sourceName,
                                               newNodeId.id(),
                                               peers,
                                               snapshotDesiredCoreSize());
    }

    /// Discovery, NOT liveness — and the name now says so (#558).
    ///
    /// This was `isHealthyPeer`, reading `state.health() == NodeHealth.HEALTHY`. Nothing ever drove
    /// a node out of HEALTHY, so the predicate was constant-true for every discovered node and the
    /// filter at its call site was an identity function. Deleting the dead health vocabulary makes
    /// that explicit rather than changing it: the behaviour here is unchanged. Liveness is now a
    /// SEPARATE filter — see [#liveObservedPeer] — applied alongside this one at the call site
    /// (#678), not folded into this predicate's meaning.
    private boolean isDiscoveredPeer(NodeId nodeId) {
        return observer.getState(nodeId)
                       .isPresent();
    }

    /// #678 — the real liveness gate `isDiscoveredPeer` never was. `buildProvisionContext` is the
    /// COLD-PATH PEERS fallback used when the reconciler's live `clusterMembers` set is empty (see
    /// [#provisionReplacement]'s class docstring); before this, its only filter was discovery
    /// (`isDiscoveredPeer`), which admits a peer the instant SWIM gossip first mentions it and never
    /// removes it — a just-killed host stays "discovered" forever, so a cold-path replacement could
    /// be seeded with a dead peer, contradicting the class docstring's claim that seeding from a live
    /// set "keeps just-killed hostnames out of the PEERS list."
    ///
    /// Sources the SAME observed-reachability projection the observer's own quorum arithmetic
    /// trusts: `snapshotSource.currentMembershipView()` is, in production
    /// (`PresenceGenerationSnapshotSource`), backed by `MembershipFsm.coreObservedMembers` — core
    /// members narrowed to first-hand reachability evidence (a completed QUIC handshake or a SWIM
    /// ALIVE observation), plus self. `coreMemberIds()` on that view IS that projection (`#557`,
    /// see the docstring on `TopologyObserver.Manager.knownCorePeerCount`). No new dependency: this
    /// module already holds `snapshotSource` for `resolveClusterName` reads elsewhere.
    ///
    /// Before any snapshot exists (BOOTING — no reachability evidence has been latched yet, e.g. a
    /// fresh leader that has not completed a single QUIC handshake), there is nothing to narrow by;
    /// this returns "everyone discovered passes", matching every other BOOTING/NORMAL fallback in
    /// this file (`resolveClusterName`, `healthyActivePeerCount`) rather than inventing a stricter
    /// cold-start behaviour nothing else in the class uses.
    private Predicate<NodeId> liveObservedPeer() {
        return snapshotSource.currentMembershipView()
                             .map(view -> (Predicate<NodeId>) view.coreMemberIds()::contains)
                             .or(() -> _ -> true);
    }

    private static String formatPeerEntry(NodeInfo info) {
        var hostname = info.address().host();

        return info.id()
                   .id() + ":" + hostname + ":" + info.address()
                                                      .port();
    }

    private Option<PlacementHint> computePlacementHint() {
        var zoneCounts = observer.topology()
                                 .stream()
                                 .map(this::zoneLabel)
                                 .filter(z -> !z.isEmpty())
                                 .collect(Collectors.groupingBy(z -> z,
                                                                Collectors.counting()));

        if (zoneCounts.isEmpty()) {
            return Option.empty();
        }

        var minCount = zoneCounts.values().stream().mapToLong(Long::longValue).min().orElse(0L);
        var underRepresented = zoneCounts.entrySet()
                                         .stream()
                                         .filter(e -> e.getValue() == minCount)
                                         .map(Map.Entry::getKey)
                                         .toList();

        if (underRepresented.size() == 1) {
            return Option.some(PlacementHint.zoneHint(underRepresented.getFirst()));
        }

        var overRepresented = zoneCounts.entrySet()
                                        .stream()
                                        .filter(e -> e.getValue() > minCount)
                                        .map(Map.Entry::getKey)
                                        .collect(Collectors.toSet());

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
