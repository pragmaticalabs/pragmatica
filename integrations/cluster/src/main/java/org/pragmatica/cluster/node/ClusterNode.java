package org.pragmatica.cluster.node;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface ClusterNode<C extends Command> {
    NodeId self();
    TopologyManager topologyManager();
    Promise<Unit> start();
    Promise<Unit> stop();
    <R> Promise<List<R>> apply(List<C> commands);
}
