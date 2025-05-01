package org.pragmatica.cluster.net;

/// Node information: ID and address.
public interface NodeInfo {
    NodeId id();
    NodeAddress address();

    /// Create new node information.
    static NodeInfo create(NodeId id, NodeAddress address) {
        record nodeInfo(NodeId id, NodeAddress address) implements NodeInfo {}

        return new nodeInfo(id, address);
    }
}
