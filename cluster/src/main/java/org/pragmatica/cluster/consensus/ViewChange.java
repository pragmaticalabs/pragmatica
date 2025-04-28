package org.pragmatica.cluster.consensus;

import org.pragmatica.cluster.net.NodeId;

import java.util.List;

public sealed interface ViewChange {
    NodeId nodeId();

    List<NodeId> changedView();

    record NodeAdded(NodeId nodeId, List<NodeId> changedView) implements ViewChange {}

    record NodeRemoved(NodeId nodeId, List<NodeId> changedView) implements ViewChange {}

    record NodeDown(NodeId nodeId, List<NodeId> changedView) implements ViewChange {}

    static NodeAdded nodeAdded(NodeId nodeId, List<NodeId> changedView) {
        return new NodeAdded(nodeId, changedView);
    }

    static NodeRemoved nodeRemoved(NodeId nodeId, List<NodeId> changedView) {
        return new NodeRemoved(nodeId, changedView);
    }

    static NodeRemoved nodeDown(NodeId nodeId) {
        return new NodeRemoved(nodeId, List.of());
    }
}
