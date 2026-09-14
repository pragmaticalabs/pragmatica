// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.RuntimeProfile;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.DEPLOY_RUNTIME;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
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
        return execute(ctx, healthCheck, sshExec, RemoteCommandRunner::scp, envLookup);
    }

    /// `scpExec(localPath, host, remotePath, sshConfig)` — the SSH source's config push, injectable
    /// like `sshExec` so `deploySshSource` is pinnable with captured commands (#1090).
    @SuppressWarnings("JBCT-PAT-01")
    static Result<BootstrapContext> execute(BootstrapContext ctx,
                                            Fn1<Result<String>, String> healthCheck,
                                            Fn3<Result<String>, String, String, SshConfig> sshExec,
                                            Fn4<Result<Unit>, String, String, String, SshConfig> scpExec,
                                            Fn1<String, String> envLookup) {
        ClusterBootstrapOrchestrator.logPhase(DEPLOY_RUNTIME,
                                              "Deploying runtime to %d node(s)",
                                              ctx.addresses().size());
        for (var entry : ctx.config().sources().entrySet()) {
            var sourceName = sourceNameOrDefault(entry.getKey());
            var source = entry.getValue();
            var deployResult = deploySource(ctx, source, sourceName, healthCheck, sshExec, scpExec, envLookup);

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
                                             SourceName sourceName,
                                             Fn1<Result<String>, String> healthCheck,
                                             Fn3<Result<String>, String, String, SshConfig> sshExec,
                                             Fn4<Result<Unit>, String, String, String, SshConfig> scpExec,
                                             Fn1<String, String> envLookup) {
        return switch (source.type()) {
            case CLOUD -> deployCloudSource(ctx, source, sourceName, healthCheck, sshExec, envLookup);
            case SSH -> deploySshSource(ctx, source, sourceName, sshExec, scpExec, envLookup);
            case FORGE -> deployForgeSource(sourceName);
            case DOCKER -> deployDockerSource(sourceName);
        };
    }

    @SuppressWarnings("JBCT-EX-01")
    static Result<Unit> deployCloudSource(BootstrapContext ctx,
                                          SourceProfile source,
                                          SourceName sourceName,
                                          Fn1<Result<String>, String> healthCheck) {
        Fn3<Result<String>, String, String, SshConfig> noopSsh = (host, command, config) -> Result.success("");
        Fn1<String, String> noopEnv = name -> "/dev/null";

        return deployCloudSource(ctx, source, sourceName, healthCheck, noopSsh, noopEnv);
    }

    @SuppressWarnings("JBCT-EX-01")
    static Result<Unit> deployCloudSource(BootstrapContext ctx,
                                          SourceProfile source,
                                          SourceName sourceName,
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
                                          SourceName sourceName,
                                          Fn1<Result<String>, String> healthCheck,
                                          Fn3<Result<String>, String, String, SshConfig> sshExec,
                                          Fn1<String, String> envLookup,
                                          long preflightTimeoutMs,
                                          long preflightPollMs) {
        // #296: attribution is exact on the id's source segment, so an id that does not parse would
        // belong to NO source and be skipped silently by every source's launch. That is an invariant
        // violation of this CLI's own minting, refused by name rather than dropped.
        var unparseable = ctx.nodes()
                             .stream()
                             .filter(n -> BootstrapPhaseProvision.parseNodeId(n.nodeId()).isEmpty())
                             .findFirst();

        if (unparseable.isPresent()) {
            return new BootstrapError.DeploymentFailed(unparseable.get().nodeId(),
                                                       "node id does not encode <source>-<core|worker|spot>-<index>, so it belongs to no source").result();
        }

        var sourceNodes = collectSourceNodes(ctx, sourceName);

        if (sourceNodes.isEmpty()) {
            System.out.printf("  [%s/cloud] No nodes to wait for%n", sourceName);

            return Result.unitResult();
        }
        // RFC-0017 stage 4 — discovery-based self-assembly. Cores discover their peers via the
        // provider API (label lookup) and workers were baked core seeds at create, so there is
        // nothing to push: no SSH preflight, no re-launch, and readiness is observed via the
        // provider API (C4) instead of polling each node's management port — which a firewalled
        // management port used to fail on HEALTHY nodes.
        if (discoveryAssembly(ctx.config())) {
            System.out.printf("  [%s/cloud] Discovery-based assembly: no SSH push; observing formation via provider labels%n",
                              sourceName);

            return awaitFormationViaLabels(ctx, source, sourceName);
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

    /// Discovery-based assembly engages when EXACTLY ONE source carries cores and it is a CLOUD
    /// source. Multi-core-source clusters keep the legacy SSH push: `discoverPeers` sees only its
    /// own provider account, so cores spread across providers cannot find each other by label —
    /// a structural limit of provider-native discovery, recorded in RFC-0017.
    static boolean discoveryAssembly(ClusterBootstrapConfig config) {
        var coreSources = config.sources().values().stream().filter(source -> coreCount(source) > 0).toList();

        return coreSources.size() == 1 && coreSources.getFirst()
                                                     .type() == SourceType.CLOUD;
    }

    private static int coreCount(SourceProfile source) {
        return Option.option(source.roles().get(NodeRole.CORE))
                     .flatMap(role -> role.count())
                     .or(0);
    }

    /// C4 — readiness without inbound ports: poll the provider API for instances carrying
    /// `aether-formed=true`, the label each core merges onto itself AFTER cluster formation
    /// succeeds (`AetherNode.tagMatchingInstance`). Counting those labels counts FORMED cores,
    /// through the same API bootstrap used to create the VMs — no SSH, no management port.
    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> awaitFormationViaLabels(BootstrapContext ctx,
                                                        SourceProfile source,
                                                        SourceName sourceName) {
        var clusterName = ctx.config().cluster().name();

        return ProviderResolver.resolveCloudCompute(source).flatMap(compute -> pollFormedLabels(filter -> compute.listInstances(filter)
                                                                                                                 .await()
                                                                                                                 .map(List::size),
                                                                                                ctx.config()
                                                                                                   .derivedCoreCount(),
                                                                                                clusterName,
                                                                                                sourceName,
                                                                                                ClusterBootstrapOrchestrator.parseDurationMs(ctx.config()
                                                                                                                                                .operations()
                                                                                                                                                .timeouts()
                                                                                                                                                .healthCheck()),
                                                                                                FORMED_LABEL_POLL_MS));
    }

    long FORMED_LABEL_POLL_MS = 5_000;

    @SuppressWarnings("JBCT-EX-01")
    static Result<Unit> pollFormedLabels(Fn1<Result<Integer>, Map<String, String>> formedCounter,
                                         int expected,
                                         ClusterName clusterName,
                                         SourceName sourceName,
                                         long timeoutMs,
                                         long pollMs) {
        var filter = Map.of("aether-cluster", clusterName.value(), "aether-formed", "true");
        var deadline = System.currentTimeMillis() + timeoutMs;
        var formed = 0;

        System.out.printf("  [%s/cloud] Waiting for %d core(s) to report formation via labels (timeout: %ds)%n",
                          sourceName,
                          expected,
                          timeoutMs / 1000);
        while (System.currentTimeMillis() < deadline) {
            formed = formedCounter.apply(filter)
                                  .onFailure(cause -> System.out.printf("  [%s/cloud] label poll failed: %s%n",
                                                                        sourceName,
                                                                        cause.message()))
                                  .or(formed);
            if (formed >= expected) {
                System.out.printf("  [%s/cloud] All %d core(s) formed%n", sourceName, expected);

                return Result.unitResult();
            }

            ClusterBootstrapOrchestrator.sleepQuietly(pollMs);
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }

        return new BootstrapError.DeploymentFailed(sourceName.value(),
                                                   "Only " + formed
                                                  + " of " + expected
                                                  + " cores reported formation (aether-formed label) within " + timeoutMs / 1000
                                                  + "s. Nodes self-assemble via discovery; check the VMs' cloud-init logs,"
                                                  + " that the aether-cluster/aether-node-id labels exist on all core VMs,"
                                                  + " and that [cloud.discovery] cluster_name reached the node config.").result();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> restartNodesWithFinalPeers(BootstrapContext ctx,
                                                           SourceProfile source,
                                                           SourceName sourceName,
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
            var roleResult = BootstrapPhaseProvision.nodeRole(node.nodeId(), sourceName);

            if (roleResult.isFailure()) {
                return new BootstrapError.DeploymentFailed(node.publicIp(), roleResult.fold(Cause::message, _ -> "")).result();
            }

            var role = roleResult.unwrap();
            var command = isJvm
                          ? buildJvmRestartCommand(node.nodeId(),
                                                   role,
                                                   clusterPort,
                                                   managementPort,
                                                   peers,
                                                   clusterSecret,
                                                   clusterName,
                                                   envLookup)
                          : buildRestartCommand(resolveContainerImage(ctx, source),
                                                clusterName,
                                                node.nodeId(),
                                                role,
                                                clusterPort,
                                                managementPort,
                                                peers,
                                                clusterSecret,
                                                envLookup);
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
                                            SourceName sourceName,
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
                                            SourceName sourceName,
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

            return new BootstrapError.DeploymentFailed(sourceName.value(),
                                                       "SSH preflight failed: " + unreachable.size()
                                                      + " host(s) unreachable after " + (timeoutMs / 1000)
                                                      + "s. Unreachable IPs: " + String.join(", ", ips)).result();
        }

        System.out.printf("  [%s/cloud] SSH reachable on all %d host(s)%n", sourceName, nodes.size());

        return Result.unitResult();
    }

    static String buildRestartCommand(String image,
                                      ClusterName clusterName,
                                      String nodeId,
                                      NodeRole role,
                                      int clusterPort,
                                      int managementPort,
                                      String peers,
                                      String clusterSecret) {
        return buildRestartCommand(image,
                                   clusterName,
                                   nodeId,
                                   role,
                                   clusterPort,
                                   managementPort,
                                   peers,
                                   clusterSecret,
                                   System::getenv);
    }

    /// Re-launch the container with the finalized PEERS. CRITICAL: this re-launch RECREATES the
    /// container that actually runs, so it MUST carry the SAME host-env-derived identity allow-list
    /// the cloud-init start emitted ([UserDataTemplate#emitIdentityEnv]) — otherwise
    /// AETHER_INSECURE_DEV_MODE and the rest of [ClusterIdentityEnv#IDENTITY_VARS] silently drop,
    /// the C2 security gate fails, and the health poll never succeeds. AETHER_CLUSTER_SECRET is
    /// emitted explicitly from the finalized `clusterSecret` param and EXCLUDED from the allow-list
    /// pass (`none()` ref) so it never appears twice. `envLookup` is injectable for unit testing.
    ///
    /// #296 — `role` is the node's OWN role, threaded from its id: the `aether-role` label is what
    /// operators filter tiers by, and the `AETHER_ROLE` the identity pass emits from the same value
    /// is the SWIM role label, the only worker classifier. A literal `core` here did not merely
    /// mislabel a non-core node, it reclassified it.
    static String buildRestartCommand(String image,
                                      ClusterName clusterName,
                                      String nodeId,
                                      NodeRole role,
                                      int clusterPort,
                                      int managementPort,
                                      String peers,
                                      String clusterSecret,
                                      Fn1<String, String> envLookup) {
        return "docker rm -f aether-node 2>/dev/null || true"
             + " && docker run -d --name aether-node --restart no --network host"
             + " -l aether-cluster=" + clusterName.value()
             + " -l aether-node-id=" + nodeId
             + " -l aether-role=" + role.value()
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
             + "\"" + identityEnvFlags(clusterName, role, envLookup)
             + " " + image;
    }

    /// Single-line `-e VAR="value"` fragment for the cluster-identity allow-list (minus
    /// AETHER_CLUSTER_SECRET, emitted explicitly by the caller). Mirrors the cloud-init start's
    /// emission so the re-launch keeps full env parity. Empty when no allow-list var is present
    /// (prod-safe: unset host env → nothing emitted).
    private static String identityEnvFlags(ClusterName clusterName, NodeRole role, Fn1<String, String> envLookup) {
        var sb = new StringBuilder();

        UserDataTemplate.emitIdentityEnv((name, value) -> appendRestartEnvFlag(sb, name, value),
                                         clusterName,
                                         role,
                                         Option.empty(),
                                         envLookup);

        return sb.toString();
    }

    private static Unit appendRestartEnvFlag(StringBuilder sb, String name, String value) {
        sb.append(" -e ").append(name).append("=\"").append(value).append("\"");

        return Unit.unit();
    }

    static String JVM_JAR_PATH = "/opt/aether/aether-node.jar";

    static String buildJvmRestartCommand(String nodeId,
                                         NodeRole role,
                                         int clusterPort,
                                         int managementPort,
                                         String peers,
                                         String clusterSecret,
                                         ClusterName clusterName) {
        return buildJvmRestartCommand(nodeId,
                                      role,
                                      clusterPort,
                                      managementPort,
                                      peers,
                                      clusterSecret,
                                      clusterName,
                                      System::getenv);
    }

    /// JVM re-launch with finalized PEERS. Same env-parity requirement as the container path
    /// ([#buildRestartCommand]): the cluster-identity allow-list (minus AETHER_CLUSTER_SECRET,
    /// written explicitly) is re-emitted so the relaunched JVM inherits the same identity + dev-mode
    /// posture the cloud-init start wrote. `envLookup` is injectable for unit testing.
    ///
    /// #1021 — this rewrites the node's systemd env file and restarts the unit. It used to
    /// `pkill -f '^java -jar /opt/aether/aether-node.jar'` and re-launch a bare `nohup java`, which
    /// had two defects beyond the missing supervisor:
    ///  - **It matched processes by command line.** The anchor `^java -jar <jar>` was added because
    ///    an unanchored pattern matched the SSH session's own argv, which carries the same string —
    ///    the shape of hazard that keeps recurring here. `systemctl restart` names the unit; nothing
    ///    is pattern-matched.
    ///  - **It left the running node OUTSIDE the unit.** With the unit installed at boot, a `pkill`
    ///    would take the unit to `failed` (`Restart=no` — correctly, it must not come back) while the
    ///    re-launched `nohup` process ran beside it, so `systemctl status aether-node` would report
    ///    `failed` for a node that was serving. Wiring the unit in without changing this would have
    ///    manufactured exactly the false signal #1021 exists to remove.
    ///
    /// The env file is rewritten whole rather than appended to, so a re-run cannot leave two
    /// AETHER_PEERS lines with the stale one last. `0600` is re-applied on every write: the file
    /// carries AETHER_CLUSTER_SECRET.
    static String buildJvmRestartCommand(String nodeId,
                                         NodeRole role,
                                         int clusterPort,
                                         int managementPort,
                                         String peers,
                                         String clusterSecret,
                                         ClusterName clusterName,
                                         Fn1<String, String> envLookup) {
        return "install -d -m 0755 " + NodeUserDataRenderer.JVM_ENV_DIR
             + " && touch " + NodeUserDataRenderer.JVM_ENV_FILE_PATH
             + " && chmod 600 " + NodeUserDataRenderer.JVM_ENV_FILE_PATH
             + " && printf '%s\\n'"
             + " 'AETHER_CLUSTER_SECRET=" + clusterSecret
             + "'" + identityEnvAssignments(clusterName, role, envLookup)
             + " 'AETHER_NODE_ID=" + nodeId
             + "'"
             + " 'AETHER_CLUSTER_PORT=" + clusterPort
             + "'"
             + " 'AETHER_MANAGEMENT_PORT=" + managementPort
             + "'"
             + " 'AETHER_PEERS=" + peers
             + "'"
             + " > " + NodeUserDataRenderer.JVM_ENV_FILE_PATH
             + " && systemctl restart " + NodeUserDataRenderer.JVM_UNIT_NAME;
    }

    /// Space-prefixed `'VAR=value'` printf operands for the cluster-identity allow-list (minus
    /// AETHER_CLUSTER_SECRET, written explicitly by the caller), one env-file line each.
    private static String identityEnvAssignments(ClusterName clusterName,
                                                 NodeRole role,
                                                 Fn1<String, String> envLookup) {
        var sb = new StringBuilder();

        UserDataTemplate.emitIdentityEnv((name, value) -> appendJvmEnvAssignment(sb, name, value),
                                         clusterName,
                                         role,
                                         Option.empty(),
                                         envLookup);

        return sb.toString();
    }

    private static Unit appendJvmEnvAssignment(StringBuilder sb, String name, String value) {
        sb.append(" '").append(name).append('=').append(value).append('\'');

        return Unit.unit();
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

    private static List<ProvisionedNode> collectSourceNodes(BootstrapContext ctx, SourceName sourceName) {
        return ctx.nodes()
                  .stream()
                  .filter(n -> BootstrapPhaseProvision.belongsTo(n.nodeId(),
                                                                 sourceName))
                  .toList();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> waitForCloudInit(List<ProvisionedNode> nodes,
                                                 int mgmtPort,
                                                 String scheme,
                                                 long timeoutMs,
                                                 Fn1<Result<String>, String> healthCheck,
                                                 SourceName sourceName) {
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
            // This gate polls the management API on each node's PUBLIC address; it does not inspect
            // cloud-init. Naming only cloud-init sent a 2026-08-05 live investigation to the wrong
            // place: the nodes had booted fine and the port was simply firewalled off.
            return new BootstrapError.DeploymentFailed(sourceName.value(),
                                                       unreachable.size()
                                                      + " node(s) never answered the management"
                                                      + " API on port " + mgmtPort
                                                      + ". Unreachable IPs: " + String.join(", ", ips)
                                                      + ". Most likely: ingress does not permit " + mgmtPort
                                                      + "/tcp from this host (a declared [source.<name>.firewall]"
                                                      + " is deny-by-default and Aether never opens the management"
                                                      + " port itself), or the runtime failed to start —"
                                                      + " check /var/log/cloud-init-output.log and the aether-node"
                                                      + " service on the host.").result();
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

    private static Result<Unit> deployDockerSource(SourceName sourceName) {
        System.out.printf("  [%s/docker] Containers already started during provisioning%n", sourceName);

        return Result.unitResult();
    }

    private static Result<Unit> deployForgeSource(SourceName sourceName) {
        System.out.printf("  [%s/forge] Ember cluster managed by forge binary — skipping runtime deploy%n", sourceName);
        System.out.println("  Ensure 'aether forge' is running before cluster formation begins");

        return Result.unitResult();
    }

    /// #1090 — the SSH source's launch, brought level with the cloud re-launch it used to be a
    /// hand-rolled copy of: only THIS source's hosts (exact id attribution, not every `ssh` node
    /// in the context), each with its OWN role (label + `AETHER_ROLE`, else the node classifies
    /// itself as CORE), the image the runtime profile resolves to (never `:latest`), and the
    /// identity allow-list the cloud path emits. The container is the only runtime this path can
    /// launch: a JVM or Ember profile on an SSH source is refused by name rather than silently
    /// run as a container — installing a JVM unit over SSH is the cloud-init script's job and has
    /// no SSH equivalent yet.
    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
    static Result<Unit> deploySshSource(BootstrapContext ctx,
                                        SourceProfile source,
                                        SourceName sourceName,
                                        Fn3<Result<String>, String, String, SshConfig> sshExec,
                                        Fn4<Result<Unit>, String, String, String, SshConfig> scpExec,
                                        Fn1<String, String> envLookup) {
        var sshConfig = buildSshConfig(source);
        var clusterName = ctx.config().cluster().name();
        var peersValue = String.join(",", buildThreePartPeers(ctx));
        var clusterSecret = ctx.clusterSecret();
        var clusterPort = ctx.config().operations().ports().cluster();
        var managementPort = ctx.config().operations().ports().management();
        var nodeIndex = 0;

        for (var node : ctx.nodes()) {
            if (!node.serverId().equals("ssh") || !BootstrapPhaseProvision.belongsTo(node.nodeId(), sourceName)) {
                nodeIndex++;
                continue;
            }

            var index = nodeIndex;
            var result = BootstrapPhaseProvision.nodeRole(node.nodeId(), sourceName)
                                                .flatMap(role -> sshContainerImage(ctx, source, role, node.nodeId())
                                                                    .flatMap(image -> NodeConfigBuilder.compose(ctx,
                                                                                                                source,
                                                                                                                index,
                                                                                                                role,
                                                                                                                Option.empty(),
                                                                                                                Option.some(clusterSecret))
                                                                                                       .flatMap(doc -> deploySshNode(node,
                                                                                                                                     TomlWriter.toToml(doc),
                                                                                                                                     sshConfig,
                                                                                                                                     buildSshStartCommand(image,
                                                                                                                                                          clusterName,
                                                                                                                                                          node.nodeId(),
                                                                                                                                                          role,
                                                                                                                                                          clusterPort,
                                                                                                                                                          managementPort,
                                                                                                                                                          peersValue,
                                                                                                                                                          clusterSecret,
                                                                                                                                                          envLookup),
                                                                                                                                     sshExec,
                                                                                                                                     scpExec))));

            if (result.isFailure()) {
                return result;
            }

            nodeIndex++;
        }

        System.out.printf("  [%s/ssh] Deployed runtime to SSH nodes%n", sourceName);

        return Result.unitResult();
    }

    /// The image for THIS role's runtime profile — the same resolution the cloud path makes, per
    /// role rather than for CORE only, and refusing a non-container runtime instead of ignoring it.
    private static Result<String> sshContainerImage(BootstrapContext ctx, SourceProfile source, NodeRole role, String nodeId) {
        var profile = Option.option(source.roles().get(role))
                            .map(RoleSubTable::runtimeRef)
                            .flatMap(ref -> Option.option(ctx.config().runtimes().get(ref)));
        var type = profile.map(RuntimeProfile::type).or(RuntimeType.CONTAINER);

        if (type != RuntimeType.CONTAINER) {
            return new BootstrapError.DeploymentFailed(nodeId,
                                                       "SSH source '" + sourceNameOf(source)
                                                      + "' declares runtime '" + profile.map(RuntimeProfile::name).or("?")
                                                      + "' of type " + type
                                                      + "; only CONTAINER can be launched over SSH in this release (#1090)").result();
        }

        return Result.success(profile.flatMap(RuntimeProfile::image).or(derivedImage(ctx)));
    }

    private static String sourceNameOf(SourceProfile source) {
        return source.name().value();
    }

    /// One launch line, built from the SAME builder the cloud re-launch uses ([#buildRestartCommand]),
    /// so role label, `AETHER_ROLE`, node id, identity allow-list and image are threaded once. The
    /// prefix creates the config dir (the scp before it needs it) and pulls the resolved image; the
    /// `docker rm -f … || true` inside the builder makes a re-run on the same host idempotent.
    static String buildSshStartCommand(String image,
                                       ClusterName clusterName,
                                       String nodeId,
                                       NodeRole role,
                                       int clusterPort,
                                       int managementPort,
                                       String peers,
                                       String clusterSecret,
                                       Fn1<String, String> envLookup) {
        return "mkdir -p /opt/aether/config && docker pull " + image
             + " && " + buildRestartCommand(image, clusterName, nodeId, role, clusterPort, managementPort, peers, clusterSecret, envLookup);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> deploySshNode(ProvisionedNode node,
                                              String nodeConfig,
                                              SshConfig sshConfig,
                                              String startCommand,
                                              Fn3<Result<String>, String, String, SshConfig> sshExec,
                                              Fn4<Result<Unit>, String, String, String, SshConfig> scpExec) {
        // The config dir must exist before the scp lands in it; the launch line recreates it harmlessly.
        return sshExec.apply(node.publicIp(), "mkdir -p /opt/aether/config", sshConfig)
                      .flatMap(_ -> writeNodeConfigToTemp(node.nodeId(), nodeConfig))
                      .flatMap(tempPath -> scpExec.apply(tempPath.toString(),
                                                         node.publicIp(),
                                                         "/opt/aether/config/aether.toml",
                                                         sshConfig))
                      .flatMap(_ -> sshExec.apply(node.publicIp(), startCommand, sshConfig))
                      .mapToUnit();
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

    private static SshConfig buildSshConfig(SourceProfile source) {
        var user = source.user().or("root");
        var keyPath = source.key().or("~/.ssh/id_rsa");
        var port = source.sshPort().or(22);

        return SshConfig.sshConfig(user, keyPath, port);
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

    static Result<TomlDocument> composeNodeConfig(BootstrapContext ctx,
                                                  SourceProfile source,
                                                  int nodeIndex,
                                                  NodeRole role,
                                                  Option<String> dockerGid,
                                                  Option<String> clusterSecret) {
        return NodeConfigBuilder.compose(ctx, source, nodeIndex, role, dockerGid, clusterSecret);
    }
}
