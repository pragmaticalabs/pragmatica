package org.pragmatica.cluster.node.rabia;

import java.util.Set;

import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.JoinGenerationGuard;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.io.TimeSpan;


public interface NodeConfig {
    ProtocolConfig protocol();
    TopologyConfig topology();

    default boolean activationGated() {
        return false;
    }

    /// Bound on waiting for core members to handshake before the wire-generation join guard decides (#2981 step 6).
    /// Production keeps the default; tests that boot with a member intentionally down shorten it.
    default TimeSpan joinGenerationWait() {
        return JoinGenerationGuard.JOIN_GENERATION_WAIT;
    }

    /// The labels whose values every core member must share (#3525): a node refuses to start when a handshaken core peer's value of
    /// any of them differs from its own (an absent label differs from a present one), and casts no ballot until a quorum of the
    /// core has handshaken. Consensus names none of them; the assembly that stamps a label into the node's Hello labels names it
    /// here. The empty default requires nothing, so the join guard waits for nobody beyond the wire-generation bound.
    default Set<String> agreeingLabels() {
        return Set.of();
    }

    default ClusterFormationConfig clusterFormation() {
        return ClusterFormationConfig.defaults();
    }

    static NodeConfig nodeConfig(ProtocolConfig protocol, TopologyConfig topology) {
        record nodeConfig(ProtocolConfig protocol, TopologyConfig topology) implements NodeConfig {}

        return new nodeConfig(protocol, topology);
    }

    static NodeConfig nodeConfig(ProtocolConfig protocol, TopologyConfig topology, boolean activationGated) {
        record gatedNodeConfig(ProtocolConfig protocol, TopologyConfig topology, boolean activationGated) implements NodeConfig {}

        return new gatedNodeConfig(protocol, topology, activationGated);
    }

    static NodeConfig nodeConfig(ProtocolConfig protocol,
                                 TopologyConfig topology,
                                 boolean activationGated,
                                 ClusterFormationConfig clusterFormation) {
        record fullNodeConfig(ProtocolConfig protocol,
                              TopologyConfig topology,
                              boolean activationGated,
                              ClusterFormationConfig clusterFormation) implements NodeConfig {}

        return new fullNodeConfig(protocol, topology, activationGated, clusterFormation);
    }
}
