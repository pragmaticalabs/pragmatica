package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.DEPLOY_RUNTIME;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface BootstrapPhaseDeploy {
    record unused() implements BootstrapPhaseDeploy{}

    @SuppressWarnings("JBCT-PAT-01") static Result<BootstrapContext> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(DEPLOY_RUNTIME,
                                              "Deploying runtime to %d node(s)",
                                              ctx.addresses().size());
        var clusterSecret = ClusterBootstrapOrchestrator.generateClusterSecret();
        for (var entry : ctx.config().sources()
                                   .entrySet()) {
            var sourceName = entry.getKey();
            var source = entry.getValue();
            var deployResult = deploySource(ctx, source, sourceName, clusterSecret);
            if (deployResult.isFailure()) {return deployResult.map(_ -> ctx);}
        }
        return verifyForgeReachable(ctx).map(_ -> ctx);
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> verifyForgeReachable(BootstrapContext ctx) {
        var hasForge = ctx.config().sources()
                                 .values()
                                 .stream()
                                 .anyMatch(s -> s.type() == SourceType.FORGE);
        if (!hasForge) {return Result.unitResult();}
        var mgmtPort = ctx.config().operations()
                                 .ports()
                                 .management();
        var url = "http://127.0.0.1:" + mgmtPort + "/health/live";
        System.out.printf("  Verifying forge is reachable at %s%n", url);
        var deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() <deadline) {
            if (ClusterBootstrapOrchestrator.httpGet(url).isSuccess()) {
                System.out.println("  Forge is reachable");
                return Result.unitResult();
            }
            ClusterBootstrapOrchestrator.sleepQuietly(1000);
        }
        return new BootstrapError.DeploymentFailed("forge",
                                                   "Forge source detected but not reachable at " + url + ". Ensure 'aether forge' is running.").result();
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<Unit> deploySource(BootstrapContext ctx,
                                                                              SourceProfile source,
                                                                              String sourceName,
                                                                              String clusterSecret) {
        return switch (source.type()){
            case CLOUD -> deployCloudSource(sourceName);
            case SSH -> deploySshSource(ctx, source, sourceName, clusterSecret);
            case FORGE -> deployForgeSource(sourceName);
            case DOCKER -> deployDockerSource(sourceName);
        };
    }

    private static Result<Unit> deployCloudSource(String sourceName) {
        System.out.printf("  [%s/cloud] Cloud-init already applied during provisioning%n", sourceName);
        return Result.unitResult();
    }

    private static Result<Unit> deployDockerSource(String sourceName) {
        System.out.printf("  [%s/docker] Containers already started during provisioning%n", sourceName);
        return Result.unitResult();
    }

    private static Result<Unit> deployForgeSource(String sourceName) {
        System.out.printf("  [%s/forge] Ember cluster managed by forge binary — skipping runtime deploy%n", sourceName);
        System.out.println("  Ensure 'aether forge' is running before cluster formation begins");
        return Result.unitResult();
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"}) private static Result<Unit> deploySshSource(BootstrapContext ctx,
                                                                                                 SourceProfile source,
                                                                                                 String sourceName,
                                                                                                 String clusterSecret) {
        var sshConfig = buildSshConfig(source);
        var allNodeIds = ctx.nodes().stream()
                                  .map(ProvisionedNode::nodeId)
                                  .toList();
        var allNodeIps = ctx.addresses().stream()
                                      .map(NodeAddress::publicIp)
                                      .toList();
        var clusterName = ctx.config().cluster()
                                    .name();
        var nodeIndex = 0;
        for (var node : ctx.nodes()) {
            if (!node.serverId().equals("ssh")) {
                nodeIndex++;
                continue;
            }
            var nodeConfig = NodeConfigTemplate.render(ctx.config(),
                                                       node.nodeId(),
                                                       nodeIndex,
                                                       clusterSecret,
                                                       allNodeIds,
                                                       allNodeIps);
            var result = deploySshNode(node, nodeConfig, sshConfig, clusterName);
            if (result.isFailure()) {return result;}
            nodeIndex++;
        }
        System.out.printf("  [%s/ssh] Deployed runtime to SSH nodes%n", sourceName);
        return Result.unitResult();
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> deploySshNode(ProvisionedNode node,
                                                                              String nodeConfig,
                                                                              SshConfig sshConfig,
                                                                              String clusterName) {
        return writeNodeConfigToTemp(node.nodeId(),
                                     nodeConfig).flatMap(tempPath -> scpConfigToNode(tempPath,
                                                                                     node.publicIp(),
                                                                                     sshConfig))
                                    .flatMap(_ -> startRuntimeViaSsh(node.publicIp(),
                                                                     sshConfig,
                                                                     clusterName,
                                                                     node.nodeId()));
    }

    private static Result<Path> writeNodeConfigToTemp(String nodeId, String content) {
        return Result.lift(e -> new BootstrapError.DeploymentFailed(nodeId,
                                                                    "Failed to write temp config: " + e.getMessage()),
                           () -> {
                               var tempFile = Files.createTempFile("aether-" + nodeId, ".toml");
                               Files.writeString(tempFile, content);
                               return tempFile;
                           });
    }

    private static Result<Unit> scpConfigToNode(Path localPath, String host, SshConfig sshConfig) {
        return RemoteCommandRunner.scp(localPath.toString(), host, "/opt/aether/config/aether.toml", sshConfig);
    }

    private static Result<Unit> startRuntimeViaSsh(String host,
                                                   SshConfig sshConfig,
                                                   String clusterName,
                                                   String nodeId) {
        var startCommand = "mkdir -p /opt/aether/config && docker pull ghcr.io/pragmaticalabs/aether-node:latest" + " && docker run -d --name aether-node --restart unless-stopped --network host" + " -e AETHER_NODE_ID=" + nodeId + " -l aether-cluster=" + clusterName + " -v /opt/aether/config:/config:ro" + " ghcr.io/pragmaticalabs/aether-node:latest --config /config/aether.toml";
        return RemoteCommandRunner.ssh(host, startCommand, sshConfig).mapToUnit();
    }

    private static SshConfig buildSshConfig(SourceProfile source) {
        var user = source.user().or("root");
        var keyPath = source.key().or("~/.ssh/id_rsa");
        var port = source.sshPort().or(22);
        return SshConfig.sshConfig(user, keyPath, port);
    }

    private static String buildPeerList(List<NodeAddress> addresses) {
        return String.join(",",
                           addresses.stream().map(NodeAddress::publicIp)
                                           .toList());
    }
}
