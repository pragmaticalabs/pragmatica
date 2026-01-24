package org.pragmatica.consensus.rabia.infrastructure;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.List;

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
    public Promise<Unit> start() {
        return Promise.unitPromise();
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.unitPromise();
    }

    @Override
    public TimeSpan pingInterval() {
        return TimeSpan.timeSpan(1).seconds();
    }

    @Override
    public TimeSpan helloTimeout() {
        return TimeSpan.timeSpan(5).seconds();
    }

    @Override
    public int activeClusterSize() {
        return clusterSize;
    }

    @Override
    public List<NodeId> fullTopology() {
        return List.of(self.id());
    }

    @Override
    public List<NodeId> activeTopology() {
        return fullTopology();
    }

    @Override
    public Option<NodeState> getState(NodeId id) {
        if (id.equals(self.id())) {
            return Option.option(NodeState.healthy(self, Instant.now()));
        }
        return Option.empty();
    }
}
