// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


@SuppressWarnings("JBCT-RET-01")
public interface ClusterTopologyManager extends TopologyManager {
    NodeReconcilerState reconcilerState();
    Promise<Unit> setDesiredSize(int size);
    int desiredSize();
    int configuredSize();
    void onNodeReady(NodeId nodeId);
    void onMembershipDecision(MembershipDecision decision);
    void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown);
    void onClusterConfigChanged();
    void onClusterPhaseChanged(ClusterPhase newPhase);
    void activate();
    void deactivate();
    TopologyObserver observer();

    record CircuitBreakerState(int consecutiveFailures, int trippedAt, long nextAllowedMs, boolean tripped) {}

    CircuitBreakerState circuitBreakerState();
    int resetCircuitBreaker(String reason);
    boolean isAutoHealEnabled();
    boolean setAutoHealEnabled(boolean enabled, String reason);

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
    /// hardcoded provider-side. Auto-heal replacements pass `NodeRole.CORE`; worker-pool
    /// provisioning (#241) will pass `WORKER` when it lands.
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
    static ClusterTopologyManager clusterTopologyManager(TopologyObserver observer,
                                                         NodeLifecycleManager lifecycleManager,
                                                         AutoHealConfig config,
                                                         DeploymentMap deploymentMap,
                                                         GenerationSnapshotSource snapshotSource,
                                                         Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                         Supplier<ClusterPhase> phaseSupplier,
                                                         Consumer<NodeId> drainCommandSink,
                                                         Consumer<NodeId> drainCommandClear) {
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
                                                                         drainCommandClear);
    }
}
