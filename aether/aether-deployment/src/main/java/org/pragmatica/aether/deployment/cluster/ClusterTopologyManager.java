// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;


/// Manages cluster node count by converging actual topology to desired configuration.
/// Owns a TopologyObserver for tracking connections and health, and a NodeReconciler
/// state machine for provisioning/draining nodes.
///
/// Single action path for ALL node count changes:
/// - Auto-heal (node failure -> provision replacement)
/// - Manual scale (CLI/API -> adjust desired count)
/// - Control loop (future: auto-scale based on load)
///
/// Quorum safety: never scales below minimum quorum size (3 nodes).
///
/// All membership-size state is read from the snapshot via [GenerationSnapshotSource];
/// `setDesiredSize` is a thin write-through to the `ClusterConfigValue` atom.
@SuppressWarnings("JBCT-RET-01")
// Callback methods used by message routing framework
public interface ClusterTopologyManager extends TopologyManager {
    NodeReconcilerState reconcilerState();
    Result<Unit> setDesiredSize(int size);
    int desiredSize();
    int configuredSize();
    void onNodeReady(NodeId nodeId);
    void onTopologyChange(TopologyChangeNotification topologyChange);
    void activate();
    void deactivate();
    TopologyObserver observer();

    static ClusterTopologyManager clusterTopologyManager(TopologyObserver observer,
                                                         NodeLifecycleManager lifecycleManager,
                                                         AutoHealConfig config,
                                                         DeploymentMap deploymentMap,
                                                         GenerationSnapshotSource snapshotSource,
                                                         Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier) {
        return ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                         lifecycleManager,
                                                                         config,
                                                                         deploymentMap,
                                                                         snapshotSource,
                                                                         clusterConfigReader,
                                                                         commandApplier);
    }
}
