package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Cluster-level configuration.
///
/// @param environment Target deployment environment
/// @param nodes       Number of nodes in the cluster (must be odd: 3, 5, 7)
/// @param tls         Enable TLS for cluster communication
/// @param ports       Port configuration
public record ClusterConfig(Environment environment,
                            int nodes,
                            boolean tls,
                            PortsConfig ports) {
    /// Factory method following JBCT naming convention.
    public static Result<ClusterConfig> clusterConfig(Environment environment,
                                                      int nodes,
                                                      boolean tls,
                                                      PortsConfig ports) {
        return success(new ClusterConfig(environment, nodes, tls, ports));
    }

    /// Create cluster config with environment defaults.
    public static ClusterConfig clusterConfig(Environment env) {
        return clusterConfig(env, env.defaultNodes(), env.defaultTls(), PortsConfig.portsConfig()).unwrap();
    }

    /// Create with custom node count.
    public ClusterConfig withNodes(int nodes) {
        return clusterConfig(environment, nodes, tls, ports).unwrap();
    }

    /// Create with TLS enabled/disabled.
    public ClusterConfig withTls(boolean tls) {
        return clusterConfig(environment, nodes, tls, ports).unwrap();
    }

    /// Create with custom ports.
    public ClusterConfig withPorts(PortsConfig ports) {
        return clusterConfig(environment, nodes, tls, ports).unwrap();
    }
}
