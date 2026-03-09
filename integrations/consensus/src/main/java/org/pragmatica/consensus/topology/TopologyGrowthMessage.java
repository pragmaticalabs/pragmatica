package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;

/// Messages for automatic topology growth decisions.
/// CDM sends these to joining nodes to assign their role in the cluster.
public sealed interface TopologyGrowthMessage extends Message.Local {
    /// CDM authorizes a joining node to participate in consensus as a core node.
    record ActivateConsensus(NodeId target) implements TopologyGrowthMessage {}

    /// CDM assigns a joining node as a passive worker (no consensus participation).
    record AssignWorkerRole(NodeId target) implements TopologyGrowthMessage {}
}
