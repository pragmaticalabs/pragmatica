package org.pragmatica.aether.node;

import org.pragmatica.aether.http.RouterConfig;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider;
import org.pragmatica.cluster.consensus.rabia.ProtocolConfig;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.net.NodeInfo;
import org.pragmatica.cluster.topology.ip.TopologyConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider.furySerializerFactoryProvider;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Configuration for an Aether cluster node.
 *
 * @param topology        Cluster topology configuration
 * @param protocol        Consensus protocol configuration
 * @param sliceAction     Slice lifecycle configuration
 * @param managementPort  Port for HTTP management API (0 to disable)
 * @param httpRouter      HTTP router configuration (empty to disable)
 * @param artifactRepo    DHT configuration for artifact repository (replication factor, 0 = full)
 */
public record AetherNodeConfig(
        TopologyConfig topology,
        ProtocolConfig protocol,
        SliceActionConfig sliceAction,
        int managementPort,
        Option<RouterConfig> httpRouter,
        DHTConfig artifactRepo
) {
    public static final int DEFAULT_MANAGEMENT_PORT = 8080;
    public static final int MANAGEMENT_DISABLED = 0;

    private static SliceActionConfig defaultSliceConfig() {
        return SliceActionConfig.defaultConfiguration(furySerializerFactoryProvider());
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes) {
        return aetherNodeConfig(self, port, coreNodes, defaultSliceConfig(), DEFAULT_MANAGEMENT_PORT, Option.empty(), DHTConfig.DEFAULT);
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes,
                                                    SliceActionConfig sliceActionConfig) {
        return aetherNodeConfig(self, port, coreNodes, sliceActionConfig, DEFAULT_MANAGEMENT_PORT, Option.empty(), DHTConfig.DEFAULT);
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes,
                                                    SliceActionConfig sliceActionConfig,
                                                    int managementPort) {
        return aetherNodeConfig(self, port, coreNodes, sliceActionConfig, managementPort, Option.empty(), DHTConfig.DEFAULT);
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes,
                                                    SliceActionConfig sliceActionConfig,
                                                    int managementPort,
                                                    Option<RouterConfig> httpRouter) {
        return aetherNodeConfig(self, port, coreNodes, sliceActionConfig, managementPort, httpRouter, DHTConfig.DEFAULT);
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes,
                                                    SliceActionConfig sliceActionConfig,
                                                    int managementPort,
                                                    Option<RouterConfig> httpRouter,
                                                    DHTConfig artifactRepoConfig) {
        var topology = new TopologyConfig(
                self,
                timeSpan(5).seconds(),  // reconciliation interval
                timeSpan(1).seconds(),  // ping interval
                coreNodes
        );

        return new AetherNodeConfig(topology, ProtocolConfig.defaultConfig(), sliceActionConfig, managementPort, httpRouter, artifactRepoConfig);
    }

    public static AetherNodeConfig testConfig(NodeId self, int port, List<NodeInfo> coreNodes) {
        var topology = new TopologyConfig(
                self,
                timeSpan(500).millis(),
                timeSpan(100).millis(),
                coreNodes
        );

        // Use full replication for tests - simpler, and tests typically have few nodes
        return new AetherNodeConfig(topology, ProtocolConfig.testConfig(), defaultSliceConfig(), MANAGEMENT_DISABLED, Option.empty(), DHTConfig.FULL);
    }

    public NodeId self() {
        return topology.self();
    }
}
