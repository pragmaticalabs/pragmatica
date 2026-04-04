package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterManagementConfig;
import org.pragmatica.aether.config.cluster.PortMapping;


/// Generates a Docker Compose YAML file from cluster configuration.
///
/// Designed for single-host testing scenarios where all nodes run on the same machine.
/// Each node gets sequential host port offsets: node-1 on base ports, node-2 on base+1, etc.
/// Uses bridge networking with Docker DNS for inter-node discovery.
sealed interface DockerComposeGenerator {
    record unused() implements DockerComposeGenerator{}

    static String generate(ClusterManagementConfig config, String apiKey) {
        var sb = new StringBuilder();
        appendHeader(sb);
        appendNetworks(sb);
        appendServicesHeader(sb);
        var coreCount = config.cluster().core()
                                      .count();
        for (int i = 0;i <coreCount;i++) {appendNodeService(sb, config, i, coreCount, apiKey);}
        return sb.toString();
    }

    private static void appendHeader(StringBuilder sb) {
        sb.append("version: \"3.9\"\n");
    }

    private static void appendNetworks(StringBuilder sb) {
        sb.append("networks:\n");
        sb.append("  aether-network:\n");
        sb.append("    driver: bridge\n");
    }

    private static void appendServicesHeader(StringBuilder sb) {
        sb.append("services:\n");
    }

    private static void appendNodeService(StringBuilder sb,
                                          ClusterManagementConfig config,
                                          int index,
                                          int totalNodes,
                                          String apiKey) {
        var nodeNum = index + 1;
        var nodeId = "node-" + nodeNum;
        var hostname = "aether-node-" + nodeNum;
        var image = resolveImage(config);
        var ports = config.deployment().ports();
        var clusterName = config.cluster().name();
        var peers = buildComposePeers(clusterName, totalNodes, ports.cluster());
        var clusterSecret = resolveClusterSecret(config);
        sb.append("  ").append(nodeId)
                 .append(":\n");
        sb.append("    image: ").append(image)
                 .append('\n');
        sb.append("    container_name: aether-").append(nodeId)
                 .append('\n');
        sb.append("    hostname: ").append(hostname)
                 .append('\n');
        appendEnvironment(sb, nodeId, ports, clusterSecret, peers);
        appendPorts(sb, ports, index);
        appendNetworkRef(sb);
        appendHealthcheck(sb, ports.management());
        sb.append('\n');
    }

    private static void appendEnvironment(StringBuilder sb,
                                          String nodeId,
                                          PortMapping ports,
                                          String clusterSecret,
                                          String peers) {
        sb.append("    environment:\n");
        sb.append("      NODE_ID: \"").append(nodeId)
                 .append("\"\n");
        sb.append("      CLUSTER_PORT: \"").append(ports.cluster())
                 .append("\"\n");
        sb.append("      MANAGEMENT_PORT: \"").append(ports.management())
                 .append("\"\n");
        sb.append("      SWIM_PORT: \"").append(ports.swim())
                 .append("\"\n");
        sb.append("      AETHER_CLUSTER_SECRET: \"").append(clusterSecret)
                 .append("\"\n");
        sb.append("      PEERS: \"").append(peers)
                 .append("\"\n");
    }

    private static void appendPorts(StringBuilder sb, PortMapping ports, int index) {
        sb.append("    ports:\n");
        sb.append("      - \"").append(ports.management() + index)
                 .append(':')
                 .append(ports.management())
                 .append("\"\n");
        sb.append("      - \"").append(ports.appHttp() + index)
                 .append(':')
                 .append(ports.appHttp())
                 .append("\"\n");
        sb.append("      - \"").append(ports.cluster() + index)
                 .append(':')
                 .append(ports.cluster())
                 .append("/udp\"\n");
        sb.append("      - \"").append(ports.swim() + index)
                 .append(':')
                 .append(ports.swim())
                 .append("/udp\"\n");
    }

    private static void appendNetworkRef(StringBuilder sb) {
        sb.append("    networks:\n");
        sb.append("      - aether-network\n");
    }

    private static void appendHealthcheck(StringBuilder sb, int managementPort) {
        sb.append("    healthcheck:\n");
        sb.append("      test: [\"CMD\", \"wget\", \"--spider\", \"-q\", \"http://localhost:").append(managementPort)
                 .append("/health/live\"]\n");
        sb.append("      interval: 5s\n");
        sb.append("      timeout: 3s\n");
        sb.append("      retries: 20\n");
        sb.append("      start_period: 30s\n");
    }

    private static String buildComposePeers(String clusterName, int totalNodes, int clusterPort) {
        var sb = new StringBuilder();
        for (int i = 0;i <totalNodes;i++) {
            if (i > 0) {sb.append(',');}
            var nodeNum = i + 1;
            sb.append("node-").append(nodeNum)
                     .append(":aether-node-")
                     .append(nodeNum)
                     .append(':')
                     .append(clusterPort);
        }
        return sb.toString();
    }

    private static String resolveImage(ClusterManagementConfig config) {
        return config.deployment().runtime()
                                .image()
                                .or("ghcr.io/pragmaticalabs/aether-node:" + config.cluster().version());
    }

    private static String resolveClusterSecret(ClusterManagementConfig config) {
        return config.deployment().tls()
                                .flatMap(tls -> tls.clusterSecret())
                                .or("auto-generated-compose-secret");
    }
}
