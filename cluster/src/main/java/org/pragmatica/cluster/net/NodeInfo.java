package org.pragmatica.cluster.net;

public interface NodeInfo {
    NodeId id();
    NodeAddress address();

    static NodeInfo create(NodeId id, NodeAddress address) {
        record nodeInfo(NodeId id, NodeAddress address) implements NodeInfo {}

        return new nodeInfo(id, address);
    }
}
