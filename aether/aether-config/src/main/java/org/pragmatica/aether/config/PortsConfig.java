package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Port configuration for cluster communication.
///
/// @param management Base port for management API (HTTP). Nodes use management, management+1, etc.
/// @param cluster    Base port for cluster communication. Nodes use cluster, cluster+1, etc.
public record PortsConfig(int management,
                          int cluster) {
    public static final int DEFAULT_MANAGEMENT_PORT = 8080;
    public static final int DEFAULT_CLUSTER_PORT = 8090;

    /// Factory method following JBCT naming convention.
    public static Result<PortsConfig> portsConfig(int management, int cluster) {
        return success(new PortsConfig(management, cluster));
    }

    /// Default ports configuration.
    public static PortsConfig portsConfig() {
        return portsConfig(DEFAULT_MANAGEMENT_PORT, DEFAULT_CLUSTER_PORT).unwrap();
    }

    /// Get management port for a specific node (0-indexed).
    public int managementPortFor(int nodeIndex) {
        return management + nodeIndex;
    }

    /// Get cluster port for a specific node (0-indexed).
    public int clusterPortFor(int nodeIndex) {
        return cluster + nodeIndex;
    }
}
