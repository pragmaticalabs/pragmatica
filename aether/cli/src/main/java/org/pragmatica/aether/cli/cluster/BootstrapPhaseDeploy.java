// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RuntimeProfile;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlWriter;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
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


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapPhaseDeploy {
    record unused() implements BootstrapPhaseDeploy {}

    @SuppressWarnings("JBCT-PAT-01")
    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        return execute(ctx, ClusterBootstrapOrchestrator::httpGet);
    }

    @SuppressWarnings("JBCT-PAT-01")
    static Result<BootstrapContext> execute(BootstrapContext ctx, Fn1<Result<String>, String> healthCheck) {
        return execute(ctx, healthCheck, RemoteCommandRunner::ssh, System::getenv);
    }

    @SuppressWarnings("JBCT-PAT-01")
    static Result<BootstrapContext> execute(BootstrapContext ctx,
                                            Fn1<Result<String>, String> healthCheck,
                                            Fn3<Result<String>, String, String, SshConfig> sshExec,
                                            Fn1<String, String> envLookup) {
        ClusterBootstrapOrchestrator.logPhase(DEPLOY_RUNTIME,
                                              "Deploying runtime to %d node(s)",
                                              ctx.addresses().size());
        for (var entry : ctx.config().sources().entrySet()) {
            var sourceName = entry.getKey();
            var source = entry.getValue();
            var deployResult = deploySource(ctx, source, sourceName, healthCheck, sshExec, envLookup);

            if (deployResult.isFailure()) {
                return deployResult.map(_ -> ctx);
            }
        }

        return verifyForgeReachable(ctx).map(_ -> ctx);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> verifyForgeReachable(BootstrapContext ctx) {
        var hasForge = ctx.config().sources().values().stream().anyMatch(s -> s.type() == SourceType.FORGE);

        if (!hasForge) {
            return Result.unitResult();
        }

        var mgmtPort = ctx.config().operations().ports().management();
        var url = "http://127.0.0.1:" + mgmtPort + "/health/live";

        System.out.printf("  Verifying forge is reachable at %s%n", url);
        var deadline = System.currentTimeMillis() + 10_000;

        while (System.currentTimeMillis() < deadline) {
            if (ClusterBootstrapOrchestrator.httpGet(url).isSuccess()) {
                System.out.println("  Forge is reachable");

                return Result.unitResult();
            }

            ClusterBootstrapOrchestrator.sleepQuietly(1000);
        }

        return new BootstrapError.DeploymentFailed("forge",
                                                   "Forge source detected but not reachable at " + url
                                                  + ". Ensure 'aether forge' is running.").result();
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<Unit> deploySource(BootstrapContext ctx,
                                             SourceProfile source,
                                             String sourceName,
                                             Fn1<Result<String>, String> healthCheck,
                                             Fn3<Result<String>, String, String, SshConfig> sshExec,
                                             Fn1<String, String> envLookup) {
        return switch (source.type()) {
            case CLOUD -> deployCloudSource(ctx, source, sourceName, healthCheck, sshExec, envLookup);
            case SSH -> deploySshSource(ctx, source, sourceName);
            case FORGE -> deployForgeSource(sourceName);
            case DOCKER -> deployDockerSource(sourceName);
        };
    }

    @SuppressWarnings("JBCT-EX-01")
    static Result<Unit> deployCloudSource(BootstrapContext ctx,
                                          SourceProfile source,
                                          String sourceName,
                                          Fn1<Result<String>, String> healthCheck) {
        Fn3<Result<String>, String, String, SshConfig> noopSsh = (host, command, config) -> Result.success("");
        Fn1<String, String> noopEnv = name -> "/dev/null";

        return deployCloudSource(ctx, source, sourceName, healthCheck, noopSsh, noopEnv);
    }

    @SuppressWarnings("JBCT-EX-01")
    static Result<Unit> deployCloudSource(BootstrapContext ctx,
                                          SourceProfile source,
                                          String sourceName,
                                          Fn1<Result<String>, String> healthCheck,
                                          Fn3<Result<String>, String, String, SshConfig> sshExec,
                                          Fn1<String, String> envLookup) {
        return deployCloudSource(ctx,
                                 source,
                                 sourceName,
                                 healthCheck,
                                 sshExec,
                                 envLookup,
                                 SSH_PREFLIGHT_TIMEOUT_MS,
                                 SSH_PREFLIGHT_POLL_MS);
    }

    @SuppressWarnings("JBCT-EX-01")
    static Result<Unit> deployCloudSource(BootstrapContext ctx,
                                          SourceProfile source,
                                          String sourceName,
                                          Fn1<Result<String>, String> healthCheck,
                                          Fn3<Result<String>, String, String, SshConfig> sshExec,
                                          Fn1<String, String> envLookup,
                                          long preflightTimeoutMs,
                                          long preflightPollMs) {
        var sourceNodes = collectSourceNodes(ctx, sourceName);

        if (sourceNodes.isEmpty()) {
            System.out.printf("  [%s/cloud] No nodes to wait for%n", sourceName);

            return Result.unitResult();
        }

        var restartResult = restartNodesWithFinalPeers(ctx,
                                                       source,
                                                       sourceName,
                                                       sourceNodes,
                                                       sshExec,
                                                       envLookup,
                                                       preflightTimeoutMs,
                                                       preflightPollMs);

        if (restartResult.isFailure()) {
            return restartResult;
        }

        var mgmtPort = ctx.config().operations().ports().management();
        var scheme = ctx.config().operations().tls().autoGenerate()
                     ? "https"
                     : "http";
        var timeoutMs = ClusterBootstrapOrchestrator.parseDurationMs(ctx.config().operations().timeouts().healthCheck());

        System.out.printf("  [%s/cloud] Waiting for %d node(s) to become healthy (timeout: %ds)%n",
                          sourceName,
                          sourceNodes.size(),
                          timeoutMs / 1000);

        return waitForCloudInit(sourceNodes, mgmtPort, scheme, timeoutMs, healthCheck, sourceName);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> restartNodesWithFinalPeers(BootstrapContext ctx,
                                                           SourceProfile source,
                                                           String sourceName,
                                                           List<ProvisionedNode> sourceNodes,
                                                           Fn3<Result<String>, String, String, SshConfig> sshExec,
                                                           Fn1<String, String> envLookup,
                                                           long preflightTimeoutMs,
                                                           long preflightPollMs) {
        var sshConfigResult = buildCloudSshConfig(source, envLookup);

        if (sshConfigResult.isFailure()) {
            return sshConfigResult.map(_ -> Unit.unit());
        }

        var sshConfig = sshConfigResult.unwrap();
        var preflight = waitForSshReachable(sourceNodes,
                                            sshConfig,
                                            sourceName,
                                            sshExec,
                                            preflightTimeoutMs,
                                            preflightPollMs);

        if (preflight.isFailure()) {
            return preflight;
        }

        var peers = String.join(",", buildThreePartPeers(ctx));
        var clusterPort = ctx.config().operations().ports().cluster();
        var managementPort = ctx.config().operations().ports().management();
        var clusterSecret = ctx.clusterSecret();
        var clusterName = ctx.config().cluster().name();
        var isJvm = isJvmRuntime(ctx, source);
        var runtimeLabel = isJvm
                           ? "JVMs"
                           : "containers";

        System.out.printf("  [%s/cloud] Re-launching aether-node %s on %d host(s) with finalized PEERS=%s%n",
                          sourceName,
                          runtimeLabel,
                          sourceNodes.size(),
                          peers);
        for (var node : sourceNodes) {
            var command = isJvm
                          ? buildJvmRestartCommand(node.nodeId(), clusterPort, managementPort, peers, clusterSecret)
                          : buildRestartCommand(resolveContainerImage(ctx, source),
                                                clusterName,
                                                node.nodeId(),
                                                clusterPort,
                                                managementPort,
                                                peers,
                                                clusterSecret);
            var result = sshExec.apply(node.publicIp(), command, sshConfig);

            if (result.isFailure()) {
                return new BootstrapError.DeploymentFailed(node.publicIp(), failureReason(isJvm, result)).result();
            }
        }

        var doneLabel = isJvm
                        ? "JVM(s)"
                        : "container(s)";

        System.out.printf("  [%s/cloud] All %d %s restarted with finalized PEERS%n",
                          sourceName,
                          sourceNodes.size(),
                          doneLabel);

        return Result.unitResult();
    }

    private static String failureReason(boolean isJvm, Result<String> result) {
        var prefix = isJvm
                     ? "Failed to restart aether-node JVM with finalized PEERS: "
                     : "Failed to restart aether-node container with finalized PEERS: ";

        return prefix + result.fold(c -> c.message(), v -> v);
    }

    static boolean isJvmRuntime(BootstrapContext ctx, SourceProfile source) {
        return resolveRuntimeProfile(ctx, source).map(p -> p.type() == RuntimeType.JVM)
                                    .or(false);
    }

    long SSH_PREFLIGHT_TIMEOUT_MS = 300_000;
    long SSH_PREFLIGHT_POLL_MS = 5_000;

    @SuppressWarnings("JBCT-EX-01")
    static Result<Unit> waitForSshReachable(List<ProvisionedNode> nodes,
                                            SshConfig sshConfig,
                                            String sourceName,
                                            Fn3<Result<String>, String, String, SshConfig> sshExec) {
        return waitForSshReachable(nodes,
                                   sshConfig,
                                   sourceName,
                                   sshExec,
                                   SSH_PREFLIGHT_TIMEOUT_MS,
                                   SSH_PREFLIGHT_POLL_MS);
    }

    @SuppressWarnings("JBCT-EX-01")
    static Result<Unit> waitForSshReachable(List<ProvisionedNode> nodes,
                                            SshConfig sshConfig,
                                            String sourceName,
                                            Fn3<Result<String>, String, String, SshConfig> sshExec,
                                            long timeoutMs,
                                            long pollIntervalMs) {
        if (nodes.isEmpty()) {
            return Result.unitResult();
        }

        System.out.printf("  [%s/cloud] Waiting up to %ds for SSH to become reachable on %d host(s)%n",
                          sourceName,
                          timeoutMs / 1000,
                          nodes.size());
        var deadline = System.currentTimeMillis() + timeoutMs;
        var unreachable = new ArrayList<>(nodes);

        while (System.currentTimeMillis() < deadline && !unreachable.isEmpty()) {
            unreachable.removeIf(node -> sshExec.apply(node.publicIp(),
                                                       "cloud-init status --wait",
                                                       sshConfig)
                                                .isSuccess());
            if (unreachable.isEmpty()) {
                break;
            }

            ClusterBootstrapOrchestrator.sleepQuietly(pollIntervalMs);
        }

        if (!unreachable.isEmpty()) {
            var ips = unreachable.stream().map(ProvisionedNode::publicIp).toList();

            return new BootstrapError.DeploymentFailed(sourceName,
                                                       "SSH preflight failed: " + unreachable.size()
                                                      + " host(s) unreachable after " + (timeoutMs / 1000)
                                                      + "s. Unreachable IPs: " + String.join(", ", ips)).result();
        }

        System.out.printf("  [%s/cloud] SSH reachable on all %d host(s)%n", sourceName, nodes.size());

        return Result.unitResult();
    }

    static String buildRestartCommand(String image,
                                      String clusterName,
                                      String nodeId,
                                      int clusterPort,
                                      int managementPort,
                                      String peers,
                                      String clusterSecret) {
        return "docker rm -f aether-node 2>/dev/null || true"
             + " && docker run -d --name aether-node --restart no --network host"
             + " -l aether-cluster=" + clusterName
             + " -l aether-node-id=" + nodeId
             + " -l aether-role=core"
             + " -v /opt/aether/config/aether.toml:/app/aether.toml:ro"
             + " -e NODE_ID=\"" + nodeId
             + "\""
             + " -e CLUSTER_PORT=\"" + clusterPort
             + "\""
             + " -e MANAGEMENT_PORT=\"" + managementPort
             + "\""
             + " -e PEERS=\"" + peers
             + "\""
             + " -e AETHER_CLUSTER_SECRET=\"" + clusterSecret
             + "\""
             + " " + image;
    }

    static String JVM_JAR_PATH = "/opt/aether/aether-node.jar";
    static String JVM_LOG_PATH = "/var/log/aether-node.log";
    static String JVM_PKILL_PATTERN = "^java -jar " + JVM_JAR_PATH;

    static String buildJvmRestartCommand(String nodeId,
                                         int clusterPort,
                                         int managementPort,
                                         String peers,
                                         String clusterSecret) {
        return "pkill -f '" + JVM_PKILL_PATTERN
             + "' 2>/dev/null || true"
             + "; sleep 1"
             + "; pkill -9 -f '" + JVM_PKILL_PATTERN
             + "' 2>/dev/null || true"
             + "; sleep 1"
             + "; AETHER_CLUSTER_SECRET=\"" + clusterSecret
             + "\" nohup java -jar " + JVM_JAR_PATH
             + " --config=/opt/aether/config/aether.toml"
             + " --node-id=\"" + nodeId
             + "\""
             + " --port=\"" + clusterPort
             + "\""
             + " --management-port=\"" + managementPort
             + "\""
             + " --peers=\"" + peers
             + "\""
             + " > " + JVM_LOG_PATH
             + " 2>&1 & disown";
    }

    static String resolveContainerImage(BootstrapContext ctx, SourceProfile source) {
        return resolveRuntimeProfile(ctx, source).flatMap(RuntimeProfile::image)
                                    .or(derivedImage(ctx));
    }

    private static String derivedImage(BootstrapContext ctx) {
        return "ghcr.io/pragmaticalabs/aether-node:" + ctx.config()
                                                          .cluster()
                                                          .version();
    }

    private static Option<RuntimeProfile> resolveRuntimeProfile(BootstrapContext ctx, SourceProfile source) {
        var roleTable = Option.option(source.roles().get(NodeRole.CORE));

        return roleTable.map(rt -> rt.runtimeRef())
                        .flatMap(ref -> Option.option(ctx.config().runtimes().get(ref)));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<SshConfig> buildCloudSshConfig(SourceProfile source, Fn1<String, String> envLookup) {
        var user = source.user().or("root");
        var port = source.sshPort().or(22);
        var keyFromSource = source.key().filter(s -> !s.isBlank());

        if (keyFromSource.isPresent()) {
            return Result.success(SshConfig.sshConfig(user, keyFromSource.unwrap(), port));
        }

        var envKey = envLookup.apply(SshKeyResolver.AETHER_SSH_KEY_ENV);

        if (envKey == null || envKey.isBlank()) {
            return new BootstrapError.DeploymentFailed("cloud",
                                                       "Cannot SSH-back to cloud nodes: no private key configured. "
                                                      + "Set " + SshKeyResolver.AETHER_SSH_KEY_ENV
                                                      + " env var, or [sources.<name>] key = \"<path>\" in TOML.").result();
        }

        return Result.success(SshConfig.sshConfig(user, envKey, port));
    }

    private static List<ProvisionedNode> collectSourceNodes(BootstrapContext ctx, String sourceName) {
        return ctx.nodes()
                  .stream()
                  .filter(n -> n.nodeId()
                                .startsWith(sourceName + "-"))
                  .toList();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> waitForCloudInit(List<ProvisionedNode> nodes,
                                                 int mgmtPort,
                                                 String scheme,
                                                 long timeoutMs,
                                                 Fn1<Result<String>, String> healthCheck,
                                                 String sourceName) {
        var deadline = System.currentTimeMillis() + timeoutMs;
        var unreachable = new ArrayList<>(nodes);

        while (System.currentTimeMillis() < deadline && !unreachable.isEmpty()) {
            unreachable.removeIf(node -> isHealthy(node, mgmtPort, scheme, healthCheck));
            if (unreachable.isEmpty()) {
                break;
            }

            ClusterBootstrapOrchestrator.sleepQuietly(ClusterBootstrapOrchestrator.POLL_INTERVAL_MS);
        }

        if (!unreachable.isEmpty()) {
            var ips = unreachable.stream().map(ProvisionedNode::publicIp).toList();

            return new BootstrapError.DeploymentFailed(sourceName,
                                                       "Cloud-init did not finish on " + unreachable.size()
                                                      + " node(s). Unreachable IPs: " + String.join(", ", ips)
                                                      + ". Investigate /var/log/cloud-init-output.log on the host.").result();
        }

        System.out.printf("  [%s/cloud] All nodes reported healthy%n", sourceName);

        return Result.unitResult();
    }

    private static boolean isHealthy(ProvisionedNode node,
                                     int mgmtPort,
                                     String scheme,
                                     Fn1<Result<String>, String> healthCheck) {
        var url = scheme + "://" + node.publicIp() + ":" + mgmtPort + "/health/live";

        return healthCheck.apply(url)
                          .isSuccess();
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

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
    private static Result<Unit> deploySshSource(BootstrapContext ctx, SourceProfile source, String sourceName) {
        var sshConfig = buildSshConfig(source);
        var clusterName = ctx.config().cluster().name();
        var peers = buildThreePartPeers(ctx);
        var peersValue = String.join(",", peers);
        var clusterSecret = ctx.clusterSecret();
        var clusterPort = ctx.config().operations().ports().cluster();
        var managementPort = ctx.config().operations().ports().management();
        var nodeIndex = 0;

        for (var node : ctx.nodes()) {
            if (!node.serverId().equals("ssh")) {
                nodeIndex++;
                continue;
            }

            var nodeIdValue = node.nodeId();
            var result = NodeConfigBuilder.compose(ctx, source, nodeIndex, Option.empty(), Option.some(clusterSecret)).flatMap(doc -> deploySshNode(node,
                                                                                                                                                    TomlWriter.toToml(doc),
                                                                                                                                                    sshConfig,
                                                                                                                                                    clusterName,
                                                                                                                                                    nodeIdValue,
                                                                                                                                                    clusterPort,
                                                                                                                                                    managementPort,
                                                                                                                                                    peersValue,
                                                                                                                                                    clusterSecret));

            if (result.isFailure()) {
                return result;
            }

            nodeIndex++;
        }

        System.out.printf("  [%s/ssh] Deployed runtime to SSH nodes%n", sourceName);

        return Result.unitResult();
    }

    static List<String> buildThreePartPeers(BootstrapContext ctx) {
        var nodes = ctx.nodes();
        var addresses = ctx.addresses();
        var clusterPort = ctx.config().operations().ports().cluster();
        var size = Math.min(nodes.size(), addresses.size());

        return IntStream.range(0, size)
                        .mapToObj(i -> nodes.get(i)
                                            .nodeId() + ":" + addresses.get(i)
                                                                       .publicIp() + ":" + clusterPort)
                        .toList();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> deploySshNode(ProvisionedNode node,
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
        // #287: the temp aether.toml carries cluster_secret before it is scp'd to the node — create
        // it owner-only (0600) on the CLI host.
        return Result.lift(e -> tempConfigFailure(nodeId,
                                                  e.getMessage()),
                           () -> Files.createTempFile("aether-" + nodeId, ".toml"))
                     .flatMap(tempFile -> SecureFiles.writeSecure(tempFile, content)
                                                     .map(_ -> tempFile)
                                                     .mapError(cause -> tempConfigFailure(nodeId,
                                                                                          cause.message())));
    }

    private static BootstrapError.DeploymentFailed tempConfigFailure(String nodeId, String message) {
        return new BootstrapError.DeploymentFailed(nodeId, "Failed to write temp config: " + message);
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
        var startCommand = "mkdir -p /opt/aether/config"
                         + " && docker pull ghcr.io/pragmaticalabs/aether-node:latest"
                         + " && docker run -d --name aether-node --restart no --network host"
                         + " -l aether-cluster=" + clusterName
                         + " -e NODE_ID=\"" + nodeId
                         + "\""
                         + " -e CLUSTER_PORT=\"" + clusterPort
                         + "\""
                         + " -e MANAGEMENT_PORT=\"" + managementPort
                         + "\"" + peersEnv
                         + " -e AETHER_CLUSTER_SECRET=\"" + clusterSecret
                         + "\""
                         + " -v /opt/aether/config/aether.toml:/app/aether.toml:ro"
                         + " ghcr.io/pragmaticalabs/aether-node:latest";

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
