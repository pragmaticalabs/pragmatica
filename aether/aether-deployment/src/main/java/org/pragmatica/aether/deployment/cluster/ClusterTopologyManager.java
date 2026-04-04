package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


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
                                                         DeploymentMap deploymentMap) {
        return ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                         lifecycleManager,
                                                                         config,
                                                                         deploymentMap);
    }
}
