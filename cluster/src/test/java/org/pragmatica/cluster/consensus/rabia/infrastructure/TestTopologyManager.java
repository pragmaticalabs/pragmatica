package org.pragmatica.consensus.rabia.infrastructure;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.net.SocketAddress;

public record TestTopologyManager(int clusterSize, NodeInfo self) implements TopologyManager {
    @Override
    public Option<NodeInfo> get(NodeId id) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public TimeSpan pingInterval() {
        return TimeSpan.timeSpan(1).seconds();
    }
}
