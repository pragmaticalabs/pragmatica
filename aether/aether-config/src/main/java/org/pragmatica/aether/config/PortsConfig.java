package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Port and protocol configuration for cluster communication.
///
/// @param management             Base port for management API (HTTP). Nodes use management, management+1, etc.
/// @param cluster                Base port for cluster communication. Nodes use cluster, cluster+1, etc.
/// @param managementHttpProtocol HTTP protocol for management server (H1, H3, BOTH) — default H1
public record PortsConfig(int management, int cluster, HttpProtocol managementHttpProtocol) {
    public static final int DEFAULT_MANAGEMENT_PORT = 8080;

    public static final int DEFAULT_CLUSTER_PORT = 8090;

    public static Result<PortsConfig> portsConfig(int management, int cluster, HttpProtocol managementHttpProtocol) {
        return success(new PortsConfig(management, cluster, managementHttpProtocol));
    }

    public static Result<PortsConfig> portsConfig(int management, int cluster) {
        return success(new PortsConfig(management, cluster, HttpProtocol.H1));
    }

    public static PortsConfig portsConfig() {
        return portsConfig(DEFAULT_MANAGEMENT_PORT, DEFAULT_CLUSTER_PORT, HttpProtocol.H1).unwrap();
    }

    public int managementPortFor(int nodeIndex) {
        return management + nodeIndex;
    }

    public int clusterPortFor(int nodeIndex) {
        return cluster + nodeIndex;
    }
}
