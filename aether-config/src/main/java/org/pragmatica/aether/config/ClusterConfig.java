package org.pragmatica.aether.config;
/**
 * Cluster-level configuration.
 *
 * @param environment Target deployment environment
 * @param nodes       Number of nodes in the cluster (must be odd: 3, 5, 7)
 * @param tls         Enable TLS for cluster communication
 * @param ports       Port configuration
 */
public record ClusterConfig(
 Environment environment,
 int nodes,
 boolean tls,
 PortsConfig ports) {
    /**
     * Create cluster config with environment defaults.
     */
    public static ClusterConfig forEnvironment(Environment env) {
        return new ClusterConfig(
        env, env.defaultNodes(), env.defaultTls(), PortsConfig.defaults());
    }

    /**
     * Create with custom node count.
     */
    public ClusterConfig withNodes(int nodes) {
        return new ClusterConfig(environment, nodes, tls, ports);
    }

    /**
     * Create with TLS enabled/disabled.
     */
    public ClusterConfig withTls(boolean tls) {
        return new ClusterConfig(environment, nodes, tls, ports);
    }

    /**
     * Create with custom ports.
     */
    public ClusterConfig withPorts(PortsConfig ports) {
        return new ClusterConfig(environment, nodes, tls, ports);
    }
}
