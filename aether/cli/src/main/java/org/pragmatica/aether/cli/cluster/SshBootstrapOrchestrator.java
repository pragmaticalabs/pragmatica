package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.ClusterConfigValidator;
import org.pragmatica.aether.config.cluster.ClusterManagementConfig;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Result.success;


/// SSH-based bootstrap orchestrator for on-premises Docker deployments.
///
/// Uses a Docker bridge network with container hostnames for peer discovery,
/// matching the pattern from docker/scaling-test/docker-compose.yml.
/// All configuration is passed via environment variables — no config file mounting.
@SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01", "JBCT-RET-01", "JBCT-EX-01"}) sealed interface SshBootstrapOrchestrator {
    record unused() implements SshBootstrapOrchestrator{}

    Duration SSH_WAIT_TIMEOUT = Duration.ofSeconds(120);

    String DOCKER_NETWORK = "aether-network";

    static Result<List<BootstrapOrchestrator.ProvisionedNode>> bootstrap(ClusterManagementConfig config,
                                                                         String clusterSecret) {
        var deployment = config.deployment();
        var sshConfig = deployment.ssh().toResult(new ClusterConfigError.MissingSshConfig("on-premises"));
        if (sshConfig.isFailure()) {return sshConfig.flatMap(_ -> success(List.of()));}
        var ssh = sshConfig.fold(_ -> null, v -> v);
        var coreNodesValue = deployment.nodes().flatMap(nodes -> org.pragmatica.lang.Option.option(nodes.get("core")))
                                             .or("");
        var nodeIps = ClusterConfigValidator.parseNodeList(coreNodesValue);
        if (nodeIps.isEmpty()) {return new ClusterConfigError.MissingNodeInventory("on-premises").result();}
        return provisionAllNodes(config, nodeIps, ssh, clusterSecret);
    }

    private static Result<List<BootstrapOrchestrator.ProvisionedNode>> provisionAllNodes(ClusterManagementConfig config,
                                                                                         List<String> nodeIps,
                                                                                         SshConfig ssh,
                                                                                         String clusterSecret) {
        var nodes = new ArrayList<BootstrapOrchestrator.ProvisionedNode>();
        var clusterName = config.cluster().name();
        var ports = config.deployment().ports();
        var peers = buildPeers(clusterName, nodeIps.size(), ports.cluster());
        var firstHost = nodeIps.getFirst();
        var setupResult = RemoteCommandRunner.waitForSsh(firstHost, ssh, SSH_WAIT_TIMEOUT).flatMap(_ -> installDocker(firstHost,
                                                                                                                      ssh,
                                                                                                                      config))
                                                        .flatMap(_ -> createDockerNetwork(firstHost, ssh));
        if (setupResult.isFailure()) {return setupResult.flatMap(_ -> success(List.of()));}
        for (int i = 0;i <nodeIps.size();i++) {
            var ip = nodeIps.get(i);
            var nodeId = clusterName + "-" + (i + 1);
            var containerName = "aether-" + clusterName + "-" + (i + 1);
            var hostname = containerName;
            System.out.printf("  Provisioning node %d/%d (%s at %s)...%n", i + 1, nodeIps.size(), nodeId, ip);
            var result = provisionSingleNode(config, ip, nodeId, containerName, hostname, i, ssh, clusterSecret, peers);
            if (result.isFailure()) {return result.flatMap(_ -> success(List.of()));}
            nodes.add(new BootstrapOrchestrator.ProvisionedNode(nodeId, String.valueOf(i + 1), ip));
        }
        return success(List.copyOf(nodes));
    }

    private static Result<Unit> provisionSingleNode(ClusterManagementConfig config,
                                                    String host,
                                                    String nodeId,
                                                    String containerName,
                                                    String hostname,
                                                    int nodeIndex,
                                                    SshConfig ssh,
                                                    String clusterSecret,
                                                    String peers) {
        return ensureImage(config, host, ssh).flatMap(_ -> runContainer(host,
                                                                        nodeId,
                                                                        containerName,
                                                                        hostname,
                                                                        resolveImage(config),
                                                                        clusterSecret,
                                                                        peers,
                                                                        nodeIndex,
                                                                        ssh,
                                                                        config));
    }

    private static Result<Unit> installDocker(String host, SshConfig ssh, ClusterManagementConfig config) {
        if (config.deployment().runtime()
                             .type() != RuntimeType.CONTAINER) {return installJvm(host, ssh, config);}
        System.out.printf("    Ensuring Docker is installed on %s...%n", host);
        return RemoteCommandRunner.ssh(host,
                                       "command -v docker || (curl -fsSL https://get.docker.com | sh)",
                                       ssh)
        .mapToUnit();
    }

    private static Result<Unit> installJvm(String host, SshConfig ssh, ClusterManagementConfig config) {
        System.out.printf("    Ensuring Java is installed on %s...%n", host);
        return RemoteCommandRunner.ssh(host,
                                       "command -v java || (apt-get update -qq && apt-get install -y -qq openjdk-21-jre-headless)",
                                       ssh)
        .mapToUnit();
    }

    private static Result<Unit> createDockerNetwork(String host, SshConfig ssh) {
        System.out.printf("    Ensuring Docker network '%s' exists...%n", DOCKER_NETWORK);
        return RemoteCommandRunner.ssh(host,
                                       "docker network inspect " + DOCKER_NETWORK + " >/dev/null 2>&1 || docker network create " + DOCKER_NETWORK,
                                       ssh)
        .mapToUnit();
    }

    private static Result<Unit> ensureImage(ClusterManagementConfig config, String host, SshConfig ssh) {
        var image = resolveImage(config);
        System.out.printf("    Ensuring image %s on %s...%n", image, host);
        return RemoteCommandRunner.ssh(host,
                                       "docker image inspect " + image + " >/dev/null 2>&1 || docker pull " + image,
                                       ssh)
        .mapToUnit();
    }

    private static Result<Unit> runContainer(String host,
                                             String nodeId,
                                             String containerName,
                                             String hostname,
                                             String image,
                                             String clusterSecret,
                                             String peers,
                                             int nodeIndex,
                                             SshConfig ssh,
                                             ClusterManagementConfig config) {
        var ports = config.deployment().ports();
        var mgmtHostPort = ports.management() + nodeIndex;
        var appHostPort = ports.appHttp() + nodeIndex;
        var runCmd = "docker run -d" + " --name " + containerName + " --hostname " + hostname + " --restart unless-stopped" + " --network " + DOCKER_NETWORK + " -e NODE_ID=" + nodeId + " -e CLUSTER_PORT=" + ports.cluster() + " -e MANAGEMENT_PORT=" + ports.management() + " -e PEERS=" + peers + " -e JAVA_OPTS='-Xmx256m -XX:+UseZGC'" + " -p " + mgmtHostPort + ":" + ports.management() + " -p " + appHostPort + ":" + ports.appHttp() + " " + image;
        System.out.printf("    Starting container %s (mgmt:%d, app:%d)...%n", containerName, mgmtHostPort, appHostPort);
        return RemoteCommandRunner.ssh(host, runCmd, ssh).mapToUnit();
    }

    private static Result<Unit> startJvmNode(ClusterManagementConfig config, String host, SshConfig ssh) {
        var jvmArgs = config.deployment().runtime()
                                       .jvmArgs()
                                       .or("-Xmx4g -XX:+UseZGC");
        var cmd = "nohup java " + jvmArgs + " -jar $HOME/aether/aether-node.jar --config=$HOME/aether/config/aether.toml > /var/log/aether.log 2>&1 &";
        System.out.printf("    Starting JVM node on %s...%n", host);
        return RemoteCommandRunner.ssh(host, cmd, ssh).mapToUnit();
    }

    private static String buildPeers(String clusterName, int nodeCount, int clusterPort) {
        return IntStream.rangeClosed(1, nodeCount).mapToObj(i -> clusterName + "-" + i + ":aether-" + clusterName + "-" + i + ":" + clusterPort)
                                    .collect(Collectors.joining(","));
    }

    private static String resolveImage(ClusterManagementConfig config) {
        return config.deployment().runtime()
                                .image()
                                .or("ghcr.io/pragmaticalabs/aether-node:" + config.cluster().version());
    }
}
