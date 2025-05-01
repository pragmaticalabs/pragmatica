package org.pragmatica.cluster.net;

import java.util.List;

/// Notifications for topology change: node added/removed/down.
public sealed interface TopologyChange {
    /// The node which added or removed. For `NodeDown` notification this is the own ID of the node.
    NodeId nodeId();

    /// Ordered list of the connected nodes in the cluster. The topology returned by this method
    /// is the topology AFTER the change, i.e., current topology state as node knows it.
    List<NodeId> topology();

    /// Node added notification
    record NodeAdded(NodeId nodeId, List<NodeId> topology) implements TopologyChange {}

    /// Node removed notification
    record NodeRemoved(NodeId nodeId, List<NodeId> topology) implements TopologyChange {}

    /// Node down notification. Topology is always empty in this notification.
    record NodeDown(NodeId nodeId, List<NodeId> topology) implements TopologyChange {}

    static NodeAdded nodeAdded(NodeId nodeId, List<NodeId> changedView) {
        return new NodeAdded(nodeId, changedView);
    }

    static NodeRemoved nodeRemoved(NodeId nodeId, List<NodeId> changedView) {
        return new NodeRemoved(nodeId, changedView);
    }

    static NodeDown nodeDown(NodeId nodeId) {
        return new NodeDown(nodeId, List.of());
    }
}
