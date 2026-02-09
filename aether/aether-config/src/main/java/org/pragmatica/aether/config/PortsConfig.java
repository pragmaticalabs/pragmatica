package org.pragmatica.aether.config;
/// Port configuration for cluster communication.
///
/// @param management Base port for management API (HTTP). Nodes use management, management+1, etc.
/// @param cluster    Base port for cluster communication. Nodes use cluster, cluster+1, etc.
public record PortsConfig(int management,
                          int cluster) {
    public static final int DEFAULT_MANAGEMENT_PORT = 8080;
    public static final int DEFAULT_CLUSTER_PORT = 8090;

    /// Factory method following JBCT naming convention.
    public static PortsConfig portsConfig(int management, int cluster) {
        return new PortsConfig(management, cluster);
    }

    public static PortsConfig defaultConfig() {
        return portsConfig(DEFAULT_MANAGEMENT_PORT, DEFAULT_CLUSTER_PORT);
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
