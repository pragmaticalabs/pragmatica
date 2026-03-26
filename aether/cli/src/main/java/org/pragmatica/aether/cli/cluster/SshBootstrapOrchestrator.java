package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.ClusterConfigValidator;
import org.pragmatica.aether.config.cluster.ClusterManagementConfig;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.pragmatica.lang.Result.success;

/// SSH-based bootstrap orchestrator for on-premises Docker deployments.
///
/// For each node in `deployment.nodes.core`:
/// 1. Verify SSH connectivity
/// 2. Install Docker if not present
/// 3. Create config directory
/// 4. Generate and upload node config
/// 5. Pull and run the container
@SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01", "JBCT-RET-01", "JBCT-EX-01"})
sealed interface SshBootstrapOrchestrator {
    record unused() implements SshBootstrapOrchestrator {}

    Duration SSH_WAIT_TIMEOUT = Duration.ofSeconds(120);

    /// Bootstrap on-premises nodes via SSH.
    static Result<List<BootstrapOrchestrator.ProvisionedNode>> bootstrap(ClusterManagementConfig config,
                                                                         String clusterSecret) {
        var deployment = config.deployment();
        var sshConfig = deployment.ssh()
                                  .toResult(new ClusterConfigError.MissingSshConfig("on-premises"));
        if (sshConfig.isFailure()) {
            return sshConfig.flatMap(_ -> success(List.of()));
        }
        var ssh = sshConfig.fold(_ -> null, v -> v);
        var coreNodesValue = deployment.nodes()
                                       .flatMap(nodes -> org.pragmatica.lang.Option.option(nodes.get("core")))
                                       .or("");
        var nodeIps = ClusterConfigValidator.parseNodeList(coreNodesValue);
        if (nodeIps.isEmpty()) {
            return new ClusterConfigError.MissingNodeInventory("on-premises").result();
        }
        return provisionAllNodes(config, nodeIps, ssh, clusterSecret);
    }

    private static Result<List<BootstrapOrchestrator.ProvisionedNode>> provisionAllNodes(ClusterManagementConfig config,
                                                                                         List<String> nodeIps,
                                                                                         SshConfig ssh,
                                                                                         String clusterSecret) {
        var nodes = new ArrayList<BootstrapOrchestrator.ProvisionedNode>();
        var clusterName = config.cluster()
                                .name();
        for (int i = 0; i < nodeIps.size(); i++) {
            var ip = nodeIps.get(i);
            var nodeId = clusterName + "-" + (i + 1);
            System.out.printf("  Provisioning node %d/%d (%s at %s)...%n", i + 1, nodeIps.size(), nodeId, ip);
            var result = provisionSingleNode(config, ip, nodeId, i, ssh, clusterSecret, nodeIps);
            if (result.isFailure()) {
                return result.flatMap(_ -> success(List.of()));
            }
            nodes.add(new BootstrapOrchestrator.ProvisionedNode(nodeId, String.valueOf(i + 1), ip));
        }
        return success(List.copyOf(nodes));
    }

    private static Result<Unit> provisionSingleNode(ClusterManagementConfig config,
                                                    String host,
                                                    String nodeId,
                                                    int nodeIndex,
                                                    SshConfig ssh,
                                                    String clusterSecret,
                                                    List<String> allNodeIps) {
        return RemoteCommandRunner.waitForSsh(host, ssh, SSH_WAIT_TIMEOUT)
                                  .flatMap(_ -> installDocker(host, ssh, config))
                                  .flatMap(_ -> createConfigDir(host, ssh))
                                  .flatMap(_ -> uploadNodeConfig(config,
                                                                 host,
                                                                 nodeId,
                                                                 nodeIndex,
                                                                 ssh,
                                                                 clusterSecret,
                                                                 allNodeIps))
                                  .flatMap(_ -> pullAndRunContainer(config, host, nodeId, ssh, clusterSecret));
    }

    private static Result<Unit> installDocker(String host, SshConfig ssh, ClusterManagementConfig config) {
        if (config.deployment()
                  .runtime()
                  .type() != RuntimeType.CONTAINER) {
            return installJvm(host, ssh, config);
        }
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

    private static Result<Unit> createConfigDir(String host, SshConfig ssh) {
        return RemoteCommandRunner.ssh(host, "mkdir -p /opt/aether/config", ssh)
                                  .mapToUnit();
    }

    private static Result<Unit> uploadNodeConfig(ClusterManagementConfig config,
                                                 String host,
                                                 String nodeId,
                                                 int nodeIndex,
                                                 SshConfig ssh,
                                                 String clusterSecret,
                                                 List<String> allNodeIps) {
        System.out.printf("    Uploading config to %s...%n", host);
        var configContent = NodeConfigTemplate.render(config, nodeId, nodeIndex, clusterSecret, allNodeIps);
        return writeAndUploadConfig(configContent, host, ssh);
    }

    private static Result<Unit> writeAndUploadConfig(String configContent, String host, SshConfig ssh) {
        try{
            var tempFile = Files.createTempFile("aether-config-", ".toml");
            Files.writeString(tempFile, configContent);
            var result = RemoteCommandRunner.scp(tempFile.toString(), host, "/opt/aether/config/aether.toml", ssh);
            Files.deleteIfExists(tempFile);
            return result;
        } catch (IOException e) {
            return new RemoteCommandRunner.RemoteCommandError.CommandException("write temp config", e).result();
        }
    }

    private static Result<Unit> pullAndRunContainer(ClusterManagementConfig config,
                                                    String host,
                                                    String nodeId,
                                                    SshConfig ssh,
                                                    String clusterSecret) {
        var image = resolveImage(config);
        var ports = config.deployment()
                          .ports();
        if (config.deployment()
                  .runtime()
                  .type() != RuntimeType.CONTAINER) {
            return startJvmNode(config, host, ssh);
        }
        System.out.printf("    Pulling image %s on %s...%n", image, host);
        return RemoteCommandRunner.ssh(host, "docker pull " + image, ssh)
                                  .flatMap(_ -> runContainer(host, nodeId, image, clusterSecret, ssh, config));
    }

    private static Result<Unit> runContainer(String host,
                                             String nodeId,
                                             String image,
                                             String clusterSecret,
                                             SshConfig ssh,
                                             ClusterManagementConfig config) {
        var ports = config.deployment()
                          .ports();
        var runCmd = "docker run -d" + " --name aether-" + nodeId + " --restart unless-stopped" + " --network host"
                     + " -e NODE_ID=" + nodeId + " -e AETHER_CLUSTER_SECRET=" + clusterSecret
                     + " -v /opt/aether/config:/config:ro" + " " + image + " --config /config/aether.toml";
        System.out.printf("    Starting container on %s...%n", host);
        return RemoteCommandRunner.ssh(host, runCmd, ssh)
                                  .mapToUnit();
    }

    private static Result<Unit> startJvmNode(ClusterManagementConfig config, String host, SshConfig ssh) {
        var jvmArgs = config.deployment()
                            .runtime()
                            .jvmArgs()
                            .or("-Xmx4g -XX:+UseZGC -XX:+ZGenerational");
        var cmd = "nohup java " + jvmArgs
                  + " -jar /opt/aether/aether-node.jar --config=/opt/aether/config/aether.toml > /var/log/aether.log 2>&1 &";
        System.out.printf("    Starting JVM node on %s...%n", host);
        return RemoteCommandRunner.ssh(host, cmd, ssh)
                                  .mapToUnit();
    }

    private static String resolveImage(ClusterManagementConfig config) {
        return config.deployment()
                     .runtime()
                     .image()
                     .or("ghcr.io/pragmaticalabs/aether-node:" + config.cluster()
                                                                      .version());
    }
}
