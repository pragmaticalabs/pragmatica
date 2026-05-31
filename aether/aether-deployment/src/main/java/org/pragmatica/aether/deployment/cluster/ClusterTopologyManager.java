// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

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
import java.util.function.BooleanSupplier;
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

    /// Membership v2 / E2 — provision a replacement for a departed peer.
    ///
    /// Idempotent: if `failedPeer` (when present) is already in the in-flight provisioning
    /// set, OR a replacement is observable via the current slot/membership state, the call
    /// is a no-op success. The new peer is provisioned ULID-named with `clusterMembers`
    /// seeded as PEERS by the provider boundary. Returns a `Promise<Unit>` that resolves on
    /// the provision-request acceptance (consensus commit of the FILLING reservation), NOT
    /// on the new node becoming present.
    ///
    /// At Phase 1 this delegates to the existing slot-reconcile path
    /// (`NodeLifecycleManager.provisionNode(ProvisionSpec)` chained from a FILLING slot
    /// reservation). The `failedPeer` argument is observability-only at this layer; the
    /// slot-reconciler picks the EMPTY/DEAD slot to fill independently.
    Promise<Unit> provisionReplacement(Option<NodeId> failedPeer, Set<NodeId> clusterMembers);

    /// Membership v2 / E2 — drain a specific node. Targets either the operator/scale-down
    /// flow or the overprovision-drain path. `reason` is observability-only at this layer.
    /// Returns a `Promise<Unit>` resolving on drain-request commit (the target node observes
    /// the directive and self-drains per spec §8).
    ///
    /// At Phase 1 this routes through the existing
    /// `NodeLifecycleManager.terminateNode(NodeId)` path. A KV-record-driven `DrainRequestKey`
    /// surface (spec §8.5) is deferred to Phase 2.
    Promise<Unit> drainNode(NodeId targetNodeId, DrainReason reason);

    /// Membership v2 / E2 — reconcile current cluster membership against configured size
    /// (spec §7.4). Derives action from the SWIM-converged member count plus the KV
    /// configured count: shortfall → `provisionReplacement` per missing slot; surplus →
    /// `drainNode` per excess peer. Called from the periodic tick, NTT
    /// `TopologyUnhealthy` events, configured-size changes, and leader-activation.
    /// Idempotent — no-op when state already matches target.
    Promise<Unit> reconcile();

    /// Test/legacy factory overload. `inQuorum` defaults to a permanently-true supplier so
    /// existing call sites that do not gate on quorum remain quorate-by-assumption. Production
    /// (`AetherNode`) MUST use the `BooleanSupplier`-taking overload below wired to
    /// `TopologyObserver.inQuorum()` so a minority partition stops provisioning.
    static ClusterTopologyManager clusterTopologyManager(TopologyObserver observer,
                                                         NodeLifecycleManager lifecycleManager,
                                                         AutoHealConfig config,
                                                         DeploymentMap deploymentMap,
                                                         GenerationSnapshotSource snapshotSource,
                                                         Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                         Supplier<ClusterPhase> phaseSupplier) {
        return clusterTopologyManager(observer,
                                      lifecycleManager,
                                      config,
                                      deploymentMap,
                                      snapshotSource,
                                      clusterConfigReader,
                                      commandApplier,
                                      phaseSupplier,
                                      () -> true);
    }

    /// Production factory. `inQuorum` is the committed-healthy quorum bit (wire
    /// `TopologyObserver.inQuorum()`). When it reports `false` the CTM stops provisioning
    /// replacements and defers to `SelfDrainCoordinator` to dissolve the minority partition.
    static ClusterTopologyManager clusterTopologyManager(TopologyObserver observer,
                                                         NodeLifecycleManager lifecycleManager,
                                                         AutoHealConfig config,
                                                         DeploymentMap deploymentMap,
                                                         GenerationSnapshotSource snapshotSource,
                                                         Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                         Supplier<ClusterPhase> phaseSupplier,
                                                         BooleanSupplier inQuorum) {
        return ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                         lifecycleManager,
                                                                         config,
                                                                         deploymentMap,
                                                                         snapshotSource,
                                                                         clusterConfigReader,
                                                                         commandApplier,
                                                                         phaseSupplier,
                                                                         inQuorum,
                                                                         System::currentTimeMillis);
    }

    /// Membership v2 / B5b production factory. Wires the leader's DRAIN command channel:
    /// `drainCommandSink` enqueues a drain target into the `DrainCommandRegistry` (so the leader's
    /// cluster-sync ping carries `NodePingCommand.DRAIN` to the target, which self-drains via its
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
                                                         BooleanSupplier inQuorum,
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
                                                                         inQuorum,
                                                                         System::currentTimeMillis,
                                                                         drainCommandSink,
                                                                         drainCommandClear);
    }
}
