// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
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
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;


@SuppressWarnings("JBCT-RET-01") public interface ClusterTopologyManager extends TopologyManager {
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

    record CircuitBreakerState(int consecutiveFailures, int trippedAt, long nextAllowedMs, boolean tripped){}

    CircuitBreakerState circuitBreakerState();
    int resetCircuitBreaker(String reason);
    boolean isAutoHealEnabled();
    boolean setAutoHealEnabled(boolean enabled, String reason);

    static ClusterTopologyManager clusterTopologyManager(TopologyObserver observer,
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
                                                         Supplier<ClusterPhase> phaseSupplier) {
        return ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
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
                                                                         System::currentTimeMillis);
    }
}
