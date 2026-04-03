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
public record ClusterConfig(Environment environment, int nodes, boolean tls, PortsConfig ports, int coreMax) {
    public static Result<ClusterConfig> clusterConfig(Environment environment,
                                                      int nodes,
                                                      boolean tls,
                                                      PortsConfig ports,
                                                      int coreMax) {
        return success(new ClusterConfig(environment, nodes, tls, ports, coreMax));
    }

    public static Result<ClusterConfig> clusterConfig(Environment environment,
                                                      int nodes,
                                                      boolean tls,
                                                      PortsConfig ports) {
        return clusterConfig(environment, nodes, tls, ports, 0);
    }

    public static ClusterConfig clusterConfig(Environment env) {
        return clusterConfig(env, env.defaultNodes(), env.defaultTls(), PortsConfig.portsConfig(), 0).unwrap();
    }

    public ClusterConfig withNodes(int nodes) {
        return clusterConfig(environment, nodes, tls, ports, coreMax).unwrap();
    }

    public ClusterConfig withTls(boolean tls) {
        return clusterConfig(environment, nodes, tls, ports, coreMax).unwrap();
    }

    public ClusterConfig withPorts(PortsConfig ports) {
        return clusterConfig(environment, nodes, tls, ports, coreMax).unwrap();
    }

    public ClusterConfig withCoreMax(int coreMax) {
        return clusterConfig(environment, nodes, tls, ports, coreMax).unwrap();
    }
}
