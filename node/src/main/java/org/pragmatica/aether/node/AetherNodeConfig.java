package org.pragmatica.aether.node;

import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.TTMConfig;
import org.pragmatica.aether.http.RouterConfig;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.TlsConfig;

import java.util.List;

import static org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider.furySerializerFactoryProvider;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Configuration for an Aether cluster node.
 *
 * @param topology        Cluster topology configuration
 * @param protocol        Consensus protocol configuration
 * @param sliceAction     Slice lifecycle configuration
 * @param sliceConfig     Slice repository configuration (types to create at runtime)
 * @param managementPort  Port for HTTP management API (0 to disable)
 * @param httpRouter      HTTP router configuration (empty to disable)
 * @param artifactRepo    DHT configuration for artifact repository (replication factor, 0 = full)
 * @param tls             TLS configuration for secure connections (empty for plain TCP/HTTP)
 * @param ttm             TTM (Tiny Time Mixers) predictive scaling configuration
 * @param rollback        Automatic rollback configuration
 */
public record AetherNodeConfig(TopologyConfig topology,
                               ProtocolConfig protocol,
                               SliceActionConfig sliceAction,
                               SliceConfig sliceConfig,
                               int managementPort,
                               Option<RouterConfig> httpRouter,
                               DHTConfig artifactRepo,
                               Option<TlsConfig> tls,
                               TTMConfig ttm,
                               RollbackConfig rollback) {
    public static final int DEFAULT_MANAGEMENT_PORT = 8080;
    public static final int MANAGEMENT_DISABLED = 0;

    public static SliceActionConfig defaultSliceActionConfig() {
        return SliceActionConfig.defaultConfiguration(furySerializerFactoryProvider());
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes) {
        return aetherNodeConfig(self,
                                port,
                                coreNodes,
                                defaultSliceActionConfig(),
                                SliceConfig.defaults(),
                                DEFAULT_MANAGEMENT_PORT,
                                Option.empty(),
                                DHTConfig.DEFAULT);
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes,
                                                    SliceActionConfig sliceActionConfig) {
        return aetherNodeConfig(self,
                                port,
                                coreNodes,
                                sliceActionConfig,
                                SliceConfig.defaults(),
                                DEFAULT_MANAGEMENT_PORT,
                                Option.empty(),
                                DHTConfig.DEFAULT);
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes,
                                                    SliceActionConfig sliceActionConfig,
                                                    int managementPort) {
        return aetherNodeConfig(self,
                                port,
                                coreNodes,
                                sliceActionConfig,
                                SliceConfig.defaults(),
                                managementPort,
                                Option.empty(),
                                DHTConfig.DEFAULT);
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes,
                                                    SliceActionConfig sliceActionConfig,
                                                    int managementPort,
                                                    Option<RouterConfig> httpRouter) {
        return aetherNodeConfig(self,
                                port,
                                coreNodes,
                                sliceActionConfig,
                                SliceConfig.defaults(),
                                managementPort,
                                httpRouter,
                                DHTConfig.DEFAULT);
    }

    public static AetherNodeConfig aetherNodeConfig(NodeId self,
                                                    int port,
                                                    List<NodeInfo> coreNodes,
                                                    SliceActionConfig sliceActionConfig,
                                                    SliceConfig sliceConfig,
                                                    int managementPort,
                                                    Option<RouterConfig> httpRouter,
                                                    DHTConfig artifactRepoConfig) {
        var topology = new TopologyConfig(self, timeSpan(5)
                                                        .seconds(), timeSpan(1)
                                                                            .seconds(), coreNodes);
        return new AetherNodeConfig(topology,
                                    ProtocolConfig.defaultConfig(),
                                    sliceActionConfig,
                                    sliceConfig,
                                    managementPort,
                                    httpRouter,
                                    artifactRepoConfig,
                                    Option.empty(),
                                    TTMConfig.disabled(),
                                    RollbackConfig.defaults());
    }

    public static AetherNodeConfig testConfig(NodeId self, int port, List<NodeInfo> coreNodes) {
        var topology = new TopologyConfig(self, timeSpan(500)
                                                        .millis(), timeSpan(100)
                                                                           .millis(), coreNodes);
        // Use full replication for tests - simpler, and tests typically have few nodes
        return new AetherNodeConfig(topology,
                                    ProtocolConfig.testConfig(),
                                    defaultSliceActionConfig(),
                                    SliceConfig.defaults(),
                                    MANAGEMENT_DISABLED,
                                    Option.empty(),
                                    DHTConfig.FULL,
                                    Option.empty(),
                                    TTMConfig.disabled(),
                                    RollbackConfig.defaults());
    }

    /**
     * Create a new configuration with TLS enabled for all components (HTTP and cluster).
     */
    public AetherNodeConfig withTls(TlsConfig tlsConfig) {
        var tlsOption = Option.some(tlsConfig);
        // Update TopologyConfig with TLS for cluster communication
        var newTopology = new TopologyConfig(topology.self(),
                                             topology.reconciliationInterval(),
                                             topology.pingInterval(),
                                             topology.helloTimeout(),
                                             topology.coreNodes(),
                                             tlsOption);
        return new AetherNodeConfig(newTopology,
                                    protocol,
                                    sliceAction,
                                    sliceConfig,
                                    managementPort,
                                    httpRouter,
                                    artifactRepo,
                                    tlsOption,
                                    ttm,
                                    rollback);
    }

    /**
     * Create a new configuration with TTM enabled.
     */
    public AetherNodeConfig withTTM(TTMConfig ttmConfig) {
        return new AetherNodeConfig(topology,
                                    protocol,
                                    sliceAction,
                                    sliceConfig,
                                    managementPort,
                                    httpRouter,
                                    artifactRepo,
                                    tls,
                                    ttmConfig,
                                    rollback);
    }

    /**
     * Create a new configuration with rollback settings.
     */
    public AetherNodeConfig withRollback(RollbackConfig rollbackConfig) {
        return new AetherNodeConfig(topology,
                                    protocol,
                                    sliceAction,
                                    sliceConfig,
                                    managementPort,
                                    httpRouter,
                                    artifactRepo,
                                    tls,
                                    ttm,
                                    rollbackConfig);
    }

    /**
     * Create a new configuration with different slice configuration.
     */
    public AetherNodeConfig withSliceConfig(SliceConfig newSliceConfig) {
        return new AetherNodeConfig(topology,
                                    protocol,
                                    sliceAction,
                                    newSliceConfig,
                                    managementPort,
                                    httpRouter,
                                    artifactRepo,
                                    tls,
                                    ttm,
                                    rollback);
    }

    public NodeId self() {
        return topology.self();
    }

    /**
     * Validates the configuration.
     *
     * @return success if valid, failure with cause otherwise
     */
    public Result<Unit> validate() {
        if (managementPort < 0 || managementPort > 65535) {
            return Causes.cause("Invalid management port: " + managementPort)
                         .result();
        }
        if (managementPort != MANAGEMENT_DISABLED && topology.coreNodes()
                                                             .isEmpty()) {
            return Causes.cause("At least one core node required when management is enabled")
                         .result();
        }
        return Result.unitResult();
    }
}
