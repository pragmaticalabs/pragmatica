// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


@SuppressWarnings("JBCT-RET-01")
public interface ClusterTopologyManager extends TopologyManager {
    NodeReconcilerState reconcilerState();
    /// Set the desired node count for one (source, role) — RFC-0017 C1.
    ///
    /// Replaces `setDesiredSize(int)`, which took a bare cluster-wide core count and so could not
    /// say which source a change applied to. The caller always knows both (`DiffAction.ScaleUp`
    /// carries `sourceName` and `role`), so the information existed and was being discarded.
    Promise<Unit> setDesiredCount(SourceName sourceName, NodeRole role, int count);
    int desiredSize();
    int configuredSize();
    void onNodeReady(NodeId nodeId);
    void onMembershipDecision(MembershipDecision decision);
    void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown);
    void onClusterConfigChanged();

    /// RFC-0017 stage 5 — reconcile ACTUAL worker/spot cloud inventory toward the desired
    /// per-(source, role) topology in cluster state. Leader-gated and serialized by the
    /// implementation; a no-op everywhere else. Poked on every `ClusterConfigKey` commit and on
    /// leader activation. Default no-op so test fakes and non-provisioning implementations are
    /// untouched.
    default void reconcileWorkerTopology() {}

    void onClusterPhaseChanged(ClusterPhase newPhase);
    void activate();
    void deactivate();
    TopologyObserver observer();

    record CircuitBreakerState(int consecutiveFailures, int trippedAt, long nextAllowedMs, boolean tripped) {}

    CircuitBreakerState circuitBreakerState();

    /// #336 observability — the most recent provisioning failure (`cause` message + the epoch-millis
    /// instant it was recorded), or empty when no provisioning failure has been recorded yet.
    record LastProvisionFailure(String cause, long atEpochMs) {}

    Option<LastProvisionFailure> lastProvisionFailure();
    int resetCircuitBreaker(String reason);
    /// #685 — the operator's auto-heal disable/enable is a durable cluster fact stored in consensus
    /// KV (`AetherKey.AutoHealStateKey`), never a leader's in-memory mood: a read reflects the log
    /// applied LOCALLY, so the flag becomes visible on a node when that node applies the committed
    /// Put — bounded by consensus latency, not zero; a node behind on apply answers the previous
    /// value until then. Absent key means enabled (the pre-#685 default).
    boolean isAutoHealEnabled();
    /// #685 — writes the durable KV record via the existing `commandApplier` channel and returns the
    /// PRIOR state once the write is applied. Same visibility caveat as [#isAutoHealEnabled]: other
    /// nodes (including a newly-elected leader) observe the change only after they apply the
    /// committed Put, not synchronously with this call's resolution.
    Promise<Boolean> setAutoHealEnabled(boolean enabled, String reason);

    /// Membership v2 / E2 — provision a replacement for a departed peer under the
    /// leader-supplied identity `newNodeId`.
    ///
    /// `newNodeId` is the placeholder identity the `LeaderReconciler` minted for this missing
    /// slot and tracks in its in-flight provisioning map. The provisioned node MUST boot under
    /// exactly this id: it is threaded into the `ProvisionContext.nodeId()` so the provider
    /// boundary injects it as the node's `AETHER_NODE_ID` (and, for Docker, the container name),
    /// and the node adopts it as `self`. Making the minted id the node's real identity is what
    /// lets membership presence (`newNodeId` appearing in `currentMembers()`) act as the
    /// authoritative provision-fulfillment signal back at the reconciler.
    ///
    /// Idempotent: if a replacement is observable via the current slot/membership state, the
    /// call is a no-op success. The new peer is provisioned with `clusterMembers` seeded as
    /// PEERS by the provider boundary. Returns a `Promise<ProvisionDisposition>` that resolves on
    /// the provision-request acceptance, NOT on the new node becoming present.
    ///
    /// The disposition distinguishes a real boot ([`ProvisionDisposition.Dispatched`] — a VM is
    /// coming) from a NO-BOOT deferral ([`ProvisionDisposition.Deferred`] — circuit-open or
    /// no-healthy-peers, nothing is coming). The `LeaderReconciler` uses this to decide whether to
    /// keep its in-flight placeholder: keeping a placeholder for a deferral would mask the deficit
    /// and wedge auto-heal. A genuine boot FAILURE stays in the `Promise` failure channel (so the
    /// existing breaker `onFailure(recordProvisioningFailure)` and the reconciler's placeholder
    /// removal keep working).
    ///
    /// At Phase 1 this delegates to the existing slot-reconcile path
    /// (`NodeLifecycleManager.provisionNode(ProvisionSpec)`). The `failedPeer` argument is
    /// observability-only at this layer.
    ///
    /// `intendedRole` (cluster-topology-overhaul spec, Wave 2 / W4) is the role the provisioned
    /// node is MEANT to carry, stamped explicitly end-to-end (ProvisionContext role → provider
    /// `AETHER_ROLE` env + `aether.role` label → the node's self-asserted SWIM role label →
    /// `MemberDescriptor.role`), never inherited from the provisioning host's environment or
    /// hardcoded provider-side. Auto-heal replacements pass `NodeRole.CORE`; the
    /// worker-topology reconcile pass (RFC-0017 stage 5) passes `WORKER`/`SPOT`.
    Promise<ProvisionDisposition> provisionReplacement(NodeId newNodeId,
                                                       Option<NodeId> failedPeer,
                                                       Set<NodeId> clusterMembers,
                                                       NodeRole intendedRole);

    /// Membership v2 / E2 — drain a specific node. Targets either the operator/scale-down
    /// flow or the overprovision-drain path. `reason` is observability-only at this layer.
    /// Returns a `Promise<Unit>` resolving once the drain has been initiated (the target node
    /// observes the `DRAIN` command on the leader↔node heartbeat and self-drains per spec §8).
    ///
    /// Drain is delivered as a heartbeat command (spec §7.5.4) and is heartbeat-reported /
    /// leader-cached — there is no KV drain record and no node-state KV write on this path.
    Promise<Unit> drainNode(NodeId targetNodeId, DrainReason reason);
    /// Membership v2 / E2 — reconcile current cluster membership against configured size
    /// (spec §7.4). Derives action from the SWIM-converged member count plus the KV
    /// configured count: shortfall → `provisionReplacement` per missing slot; surplus →
    /// `drainNode` per excess peer. Called from the periodic tick, NTT
    /// `TopologyUnhealthy` events, configured-size changes, and leader-activation.
    /// Idempotent — no-op when state already matches target.
    Promise<Unit> reconcile();

    /// Test/legacy factory overload.
    static ClusterTopologyManager clusterTopologyManager(TopologyObserver observer,
                                                         NodeLifecycleManager lifecycleManager,
                                                         AutoHealConfig config,
                                                         DeploymentMap deploymentMap,
                                                         GenerationSnapshotSource snapshotSource,
                                                         Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                         Supplier<ClusterPhase> phaseSupplier) {
        return ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                         lifecycleManager,
                                                                         config,
                                                                         deploymentMap,
                                                                         snapshotSource,
                                                                         clusterConfigReader,
                                                                         commandApplier,
                                                                         phaseSupplier,
                                                                         System::currentTimeMillis);
    }

    /// Membership v2 / B5b production factory. Wires the leader's DRAIN command channel:
    /// `drainCommandSink` enqueues a drain target into the `DrainCommandRegistry` (so the leader's
    /// cluster-sync ping carries the target in its global `drainNodes` set, and the target self-drains via its
    /// `DrainProcedure`); `drainCommandClear` removes the target after the CTM grace-terminate
    /// backstop reaps the container. `AetherNode` wires these to
    /// `DrainCommandRegistry::requestDrain` / `::clearDrain`.
    ///
    /// #685 review round 1 NOTE 4 — `autoHealStateReader` is REQUIRED here, not defaulted: a
    /// production wiring site that omitted it would silently get permanently-enabled auto-heal with
    /// no compile error. Only the 8-param overload above (no plausible production use — it also lacks
    /// drain-command wiring) defaults it, explicitly, at its own call site.
    static ClusterTopologyManager clusterTopologyManager(TopologyObserver observer,
                                                         NodeLifecycleManager lifecycleManager,
                                                         AutoHealConfig config,
                                                         DeploymentMap deploymentMap,
                                                         GenerationSnapshotSource snapshotSource,
                                                         Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                         Supplier<ClusterPhase> phaseSupplier,
                                                         Consumer<NodeId> drainCommandSink,
                                                         Consumer<NodeId> drainCommandClear,
                                                         Supplier<Option<AutoHealStateValue>> autoHealStateReader) {
        return ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                         lifecycleManager,
                                                                         config,
                                                                         deploymentMap,
                                                                         snapshotSource,
                                                                         clusterConfigReader,
                                                                         commandApplier,
                                                                         phaseSupplier,
                                                                         System::currentTimeMillis,
                                                                         drainCommandSink,
                                                                         drainCommandClear,
                                                                         autoHealStateReader);
    }

    /// #336 production factory — additionally wires the leader's OWN RESOLVED config as
    /// `resolvedLocalConfig`. The leader runs with its per-node overlay RESOLVED to literals (the CLI
    /// rendered it from the resolved config at bootstrap); the CTM render path substitutes a
    /// replacement's leaked `${env:...}` / `${secrets:...}` placeholders (inherited from the
    /// deliberately-unresolved persisted KV TOML) with the leader's literals at the same TOML path,
    /// so a CTM-provisioned scale-up / auto-heal node boots with resolved credentials instead of
    /// crashing on placeholders and never joining. `resolvedLocalConfig` returns [Option#none] for
    /// non-cloud / forge / tests, in which case the composed overlay passes through unchanged
    /// (prior behavior). `AetherNode` supplies a memoized supplier that parses the node's own config
    /// file once via `TomlParser.parseFile`.
    ///
    /// `autoHealStateReader` (#685) is the durable KV read for the operator's auto-heal
    /// enable/disable flag — a direct local lookup against `AetherKey.AutoHealStateKey.SINGLETON`,
    /// never a separately-maintained cache. `AetherNode` wires it to the production `KVStore`.
    static ClusterTopologyManager clusterTopologyManager(TopologyObserver observer,
                                                         NodeLifecycleManager lifecycleManager,
                                                         AutoHealConfig config,
                                                         DeploymentMap deploymentMap,
                                                         GenerationSnapshotSource snapshotSource,
                                                         Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                         Supplier<ClusterPhase> phaseSupplier,
                                                         Consumer<NodeId> drainCommandSink,
                                                         Consumer<NodeId> drainCommandClear,
                                                         Supplier<Option<TomlDocument>> resolvedLocalConfig,
                                                         Supplier<Option<AutoHealStateValue>> autoHealStateReader) {
        return ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                         lifecycleManager,
                                                                         config,
                                                                         deploymentMap,
                                                                         snapshotSource,
                                                                         clusterConfigReader,
                                                                         commandApplier,
                                                                         phaseSupplier,
                                                                         System::currentTimeMillis,
                                                                         drainCommandSink,
                                                                         drainCommandClear,
                                                                         resolvedLocalConfig,
                                                                         autoHealStateReader);
    }
}
