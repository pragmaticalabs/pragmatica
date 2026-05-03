// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlWriter;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.DEPLOY_RUNTIME;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface BootstrapPhaseDeploy {
    record unused() implements BootstrapPhaseDeploy{}

    @SuppressWarnings("JBCT-PAT-01") static Result<BootstrapContext> execute(BootstrapContext ctx) {
        return execute(ctx, ClusterBootstrapOrchestrator::httpGet);
    }

    @SuppressWarnings("JBCT-PAT-01") static Result<BootstrapContext> execute(BootstrapContext ctx,
                                                                             Fn1<Result<String>, String> healthCheck) {
        ClusterBootstrapOrchestrator.logPhase(DEPLOY_RUNTIME,
                                              "Deploying runtime to %d node(s)",
                                              ctx.addresses().size());
        for (var entry : ctx.config().sources()
                                   .entrySet()) {
            var sourceName = entry.getKey();
            var source = entry.getValue();
            var deployResult = deploySource(ctx, source, sourceName, healthCheck);
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
                                                                              Fn1<Result<String>, String> healthCheck) {
        return switch (source.type()){
            case CLOUD -> deployCloudSource(ctx, source, sourceName, healthCheck);
            case SSH -> deploySshSource(ctx, source, sourceName);
            case FORGE -> deployForgeSource(sourceName);
            case DOCKER -> deployDockerSource(sourceName);
        };
    }

    @SuppressWarnings("JBCT-EX-01") static Result<Unit> deployCloudSource(BootstrapContext ctx,
                                                                          SourceProfile source,
                                                                          String sourceName,
                                                                          Fn1<Result<String>, String> healthCheck) {
        var sourceNodes = collectSourceNodes(ctx, sourceName);
        if (sourceNodes.isEmpty()) {
            System.out.printf("  [%s/cloud] No nodes to wait for%n", sourceName);
            return Result.unitResult();
        }
        var mgmtPort = ctx.config().operations()
                                 .ports()
                                 .management();
        var timeoutMs = ClusterBootstrapOrchestrator.parseDurationMs(ctx.config().operations()
                                                                               .timeouts()
                                                                               .healthCheck());
        System.out.printf("  [%s/cloud] Waiting for %d node(s) to finish cloud-init (timeout: %ds)%n",
                          sourceName,
                          sourceNodes.size(),
                          timeoutMs / 1000);
        return waitForCloudInit(sourceNodes, mgmtPort, timeoutMs, healthCheck, sourceName);
    }

    private static List<ProvisionedNode> collectSourceNodes(BootstrapContext ctx, String sourceName) {
        return ctx.nodes().stream()
                        .filter(n -> n.nodeId().startsWith(sourceName + "-"))
                        .toList();
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> waitForCloudInit(List<ProvisionedNode> nodes,
                                                                                 int mgmtPort,
                                                                                 long timeoutMs,
                                                                                 Fn1<Result<String>, String> healthCheck,
                                                                                 String sourceName) {
        var deadline = System.currentTimeMillis() + timeoutMs;
        var unreachable = new ArrayList<>(nodes);
        while (System.currentTimeMillis() <deadline && !unreachable.isEmpty()) {
            unreachable.removeIf(node -> isHealthy(node, mgmtPort, healthCheck));
            if (unreachable.isEmpty()) {break;}
            ClusterBootstrapOrchestrator.sleepQuietly(ClusterBootstrapOrchestrator.POLL_INTERVAL_MS);
        }
        if (!unreachable.isEmpty()) {
            var ips = unreachable.stream().map(ProvisionedNode::publicIp)
                                        .toList();
            return new BootstrapError.DeploymentFailed(sourceName,
                                                       "Cloud-init did not finish on " + unreachable.size() + " node(s). Unreachable IPs: " + String.join(", ",
                                                                                                                                                          ips) + ". Investigate /var/log/cloud-init-output.log on the host.").result();
        }
        System.out.printf("  [%s/cloud] All nodes reported healthy%n", sourceName);
        return Result.unitResult();
    }

    private static boolean isHealthy(ProvisionedNode node, int mgmtPort, Fn1<Result<String>, String> healthCheck) {
        var url = "http://" + node.publicIp() + ":" + mgmtPort + "/health/live";
        return healthCheck.apply(url).isSuccess();
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
                                                                                                 String sourceName) {
        var sshConfig = buildSshConfig(source);
        var clusterName = ctx.config().cluster()
                                    .name();
        var peers = buildThreePartPeers(ctx);
        var peersValue = String.join(",", peers);
        var clusterSecret = ctx.clusterSecret();
        var clusterPort = ctx.config().operations()
                                    .ports()
                                    .cluster();
        var managementPort = ctx.config().operations()
                                       .ports()
                                       .management();
        var nodeIndex = 0;
        for (var node : ctx.nodes()) {
            if (!node.serverId().equals("ssh")) {
                nodeIndex++;
                continue;
            }
            var nodeIdValue = node.nodeId();
            var result = NodeConfigBuilder.compose(ctx,
                                                   source,
                                                   nodeIndex,
                                                   Option.empty(),
                                                   Option.some(clusterSecret))
            .flatMap(doc -> deploySshNode(node,
                                          TomlWriter.toToml(doc),
                                          sshConfig,
                                          clusterName,
                                          nodeIdValue,
                                          clusterPort,
                                          managementPort,
                                          peersValue,
                                          clusterSecret));
            if (result.isFailure()) {return result;}
            nodeIndex++;
        }
        System.out.printf("  [%s/ssh] Deployed runtime to SSH nodes%n", sourceName);
        return Result.unitResult();
    }

    static List<String> buildThreePartPeers(BootstrapContext ctx) {
        var nodes = ctx.nodes();
        var addresses = ctx.addresses();
        var clusterPort = ctx.config().operations()
                                    .ports()
                                    .cluster();
        var size = Math.min(nodes.size(), addresses.size());
        return IntStream.range(0, size).mapToObj(i -> nodes.get(i).nodeId() + ":" + addresses.get(i).publicIp() + ":" + (clusterPort + i))
                              .toList();
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> deploySshNode(ProvisionedNode node,
                                                                              String nodeConfig,
                                                                              SshConfig sshConfig,
                                                                              String clusterName,
                                                                              String nodeId,
                                                                              int clusterPort,
                                                                              int managementPort,
                                                                              String peers,
                                                                              String clusterSecret) {
        return writeNodeConfigToTemp(node.nodeId(),
                                     nodeConfig).flatMap(tempPath -> scpConfigToNode(tempPath,
                                                                                     node.publicIp(),
                                                                                     sshConfig))
                                    .flatMap(_ -> startRuntimeViaSsh(node.publicIp(),
                                                                     sshConfig,
                                                                     clusterName,
                                                                     nodeId,
                                                                     clusterPort,
                                                                     managementPort,
                                                                     peers,
                                                                     clusterSecret));
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
                                                   String nodeId,
                                                   int clusterPort,
                                                   int managementPort,
                                                   String peers,
                                                   String clusterSecret) {
        var peersEnv = peers.isEmpty()
                      ? ""
                      : " -e PEERS=\"" + peers + "\"";
        var startCommand = "mkdir -p /opt/aether/config" + " && docker pull ghcr.io/pragmaticalabs/aether-node:latest" + " && docker run -d --name aether-node --restart unless-stopped --network host" + " -l aether-cluster=" + clusterName + " -e NODE_ID=\"" + nodeId + "\"" + " -e CLUSTER_PORT=\"" + clusterPort + "\"" + " -e MANAGEMENT_PORT=\"" + managementPort + "\"" + peersEnv + " -e AETHER_CLUSTER_SECRET=\"" + clusterSecret + "\"" + " -v /opt/aether/config/aether.toml:/app/aether.toml:ro" + " ghcr.io/pragmaticalabs/aether-node:latest";
        return RemoteCommandRunner.ssh(host, startCommand, sshConfig).mapToUnit();
    }

    private static SshConfig buildSshConfig(SourceProfile source) {
        var user = source.user().or("root");
        var keyPath = source.key().or("~/.ssh/id_rsa");
        var port = source.sshPort().or(22);
        return SshConfig.sshConfig(user, keyPath, port);
    }

    static Result<TomlDocument> composeNodeConfig(BootstrapContext ctx,
                                                  SourceProfile source,
                                                  int nodeIndex,
                                                  Option<String> dockerGid,
                                                  Option<String> clusterSecret) {
        return NodeConfigBuilder.compose(ctx, source, nodeIndex, dockerGid, clusterSecret);
    }
}
