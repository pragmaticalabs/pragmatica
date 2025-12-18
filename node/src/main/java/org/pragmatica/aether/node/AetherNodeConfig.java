package org.pragmatica.aether.node;

import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.cluster.consensus.rabia.ProtocolConfig;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.net.NodeInfo;
import org.pragmatica.cluster.topology.ip.TopologyConfig;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Configuration for an Aether cluster node.
 */
public record AetherNodeConfig(
        TopologyConfig topology,
        ProtocolConfig protocol,
        SliceActionConfig sliceAction
) {
    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes) {
        return aetherNodeConfig(self, port, coreNodes, SliceActionConfig.defaultConfiguration());
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes,
                                                    SliceActionConfig sliceActionConfig) {
        var topology = new TopologyConfig(
                self,
                timeSpan(5).seconds(),  // reconciliation interval
                timeSpan(1).seconds(),  // ping interval
                coreNodes
        );

        return new AetherNodeConfig(topology, ProtocolConfig.defaultConfig(), sliceActionConfig);
    }

    public static AetherNodeConfig testConfig(NodeId self, int port, List<NodeInfo> coreNodes) {
        var topology = new TopologyConfig(
                self,
                timeSpan(500).millis(),
                timeSpan(100).millis(),
                coreNodes
        );

        return new AetherNodeConfig(topology, ProtocolConfig.testConfig(), SliceActionConfig.defaultConfiguration());
    }

    public NodeId self() {
        return topology.self();
    }
}
