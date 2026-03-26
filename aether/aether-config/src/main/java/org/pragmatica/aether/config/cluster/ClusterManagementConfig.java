package org.pragmatica.aether.config.cluster;
/// Top-level configuration for declarative cluster management.
/// Represents a parsed `aether-cluster.toml` file.
///
/// @param deployment HOW to provision (cloud, instance types, zones, ports, TLS)
/// @param cluster WHAT to provision (node count, roles, distribution, auto-heal)
public record ClusterManagementConfig(DeploymentSpec deployment,
                                      ClusterSpec cluster) {
    /// Factory method.
    public static ClusterManagementConfig clusterManagementConfig(DeploymentSpec deployment,
                                                                  ClusterSpec cluster) {
        return new ClusterManagementConfig(deployment, cluster);
    }
}
