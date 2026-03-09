package org.pragmatica.cluster.node.rabia;

import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;

public interface NodeConfig {
    ProtocolConfig protocol();

    TopologyConfig topology();

    default boolean activationGated() {
        return false;
    }

    static NodeConfig nodeConfig(ProtocolConfig protocol, TopologyConfig topology) {
        record nodeConfig(ProtocolConfig protocol, TopologyConfig topology) implements NodeConfig {}
        return new nodeConfig(protocol, topology);
    }

    static NodeConfig nodeConfig(ProtocolConfig protocol, TopologyConfig topology, boolean activationGated) {
        record gatedNodeConfig(ProtocolConfig protocol, TopologyConfig topology, boolean activationGated) implements NodeConfig {}
        return new gatedNodeConfig(protocol, topology, activationGated);
    }
}
