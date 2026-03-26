package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;

import java.util.Map;

/// Deployment specification describing HOW to provision the cluster.
///
/// @param type deployment provider type
/// @param instances role to instance type mapping (must have "core" entry)
/// @param runtime runtime configuration
/// @param zones logical to physical zone mapping
/// @param ports port mapping
/// @param tls TLS configuration
/// @param nodes static node inventory (on-premises only)
/// @param ssh SSH configuration for on-premises bootstrap
public record DeploymentSpec(DeploymentType type,
                             Map<String, String> instances,
                             RuntimeConfig runtime,
                             Map<String, String> zones,
                             PortMapping ports,
                             Option<TlsDeploymentConfig> tls,
                             Option<Map<String, String>> nodes,
                             Option<SshConfig> ssh) {
    /// Factory method with all fields.
    public static DeploymentSpec deploymentSpec(DeploymentType type,
                                                Map<String, String> instances,
                                                RuntimeConfig runtime,
                                                Map<String, String> zones,
                                                PortMapping ports,
                                                Option<TlsDeploymentConfig> tls,
                                                Option<Map<String, String>> nodes,
                                                Option<SshConfig> ssh) {
        return new DeploymentSpec(type,
                                  Map.copyOf(instances),
                                  runtime,
                                  Map.copyOf(zones),
                                  ports,
                                  tls,
                                  nodes.map(Map::copyOf),
                                  ssh);
    }

    /// Backward-compatible factory method without SSH config.
    public static DeploymentSpec deploymentSpec(DeploymentType type,
                                                Map<String, String> instances,
                                                RuntimeConfig runtime,
                                                Map<String, String> zones,
                                                PortMapping ports,
                                                Option<TlsDeploymentConfig> tls,
                                                Option<Map<String, String>> nodes) {
        return deploymentSpec(type, instances, runtime, zones, ports, tls, nodes, Option.none());
    }
}
