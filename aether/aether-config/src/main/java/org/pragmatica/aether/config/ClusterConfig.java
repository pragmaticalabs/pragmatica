package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Cluster-level configuration.
///
/// @param environment Target deployment environment
/// @param nodes       Number of nodes in the cluster (must be odd: 3, 5, 7)
/// @param tls         Enable TLS for cluster communication
/// @param ports       Port configuration
/// @param coreMax     Maximum number of core consensus nodes (0 = unlimited)
public record ClusterConfig( Environment environment,
                             int nodes,
                             boolean tls,
                             PortsConfig ports,
                             int coreMax) {
    /// Factory method following JBCT naming convention.
    public static Result<ClusterConfig> clusterConfig(Environment environment,
                                                      int nodes,
                                                      boolean tls,
                                                      PortsConfig ports,
                                                      int coreMax) {
        return success(new ClusterConfig(environment, nodes, tls, ports, coreMax));
    }

    /// Factory method with default coreMax (0 = unlimited).
    public static Result<ClusterConfig> clusterConfig(Environment environment,
                                                      int nodes,
                                                      boolean tls,
                                                      PortsConfig ports) {
        return clusterConfig(environment, nodes, tls, ports, 0);
    }

    /// Create cluster config with environment defaults.
    public static ClusterConfig clusterConfig(Environment env) {
        return clusterConfig(env, env.defaultNodes(), env.defaultTls(), PortsConfig.portsConfig(), 0).unwrap();
    }

    /// Create with custom node count.
    public ClusterConfig withNodes(int nodes) {
        return clusterConfig(environment, nodes, tls, ports, coreMax).unwrap();
    }

    /// Create with TLS enabled/disabled.
    public ClusterConfig withTls(boolean tls) {
        return clusterConfig(environment, nodes, tls, ports, coreMax).unwrap();
    }

    /// Create with custom ports.
    public ClusterConfig withPorts(PortsConfig ports) {
        return clusterConfig(environment, nodes, tls, ports, coreMax).unwrap();
    }

    /// Create with custom coreMax.
    public ClusterConfig withCoreMax(int coreMax) {
        return clusterConfig(environment, nodes, tls, ports, coreMax).unwrap();
    }
}
