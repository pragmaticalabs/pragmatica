// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.AutoHealSpec;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.PortMapping;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.RuntimeProfile;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.aether.config.cluster.TimeoutsConfig;
import org.pragmatica.aether.config.cluster.TlsDeploymentConfig;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

class BootstrapPhaseDeployCloudSshRestartTest {

    private static final ClusterName CLUSTER_NAME = clusterName("prod").unwrap();

    private static final String CLUSTER_VERSION = "1.0.0";

    private static final String CLUSTER_SECRET = "super-secret-token";

    private static SourceProfile cloudSource() {
        return SourceProfile.sourceProfile(sourceNameOrDefault("eu-1"),
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(3),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    private static SourceProfile cloudSourceWithKey(String keyPath) {
        return SourceProfile.sourceProfile(sourceNameOrDefault("eu-1"),
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.some("aether"),
                                           Option.some(keyPath),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(3),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    private static SourceProfile cloudSourceWithUser(String user) {
        return SourceProfile.sourceProfile(sourceNameOrDefault("eu-1"),
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.some(user),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(3),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    private static ClusterBootstrapConfig configWithShortTimeout(SourceProfile source) {
        return configWithShortTimeout(source, Map.of());
    }

    /// A second core-bearing source keeps `discoveryAssembly` FALSE, so these tests keep pinning the
    /// LEGACY SSH-push path (RFC-0017 stage 4 routes the single-cloud-core-source shape through
    /// label-based formation observation with no SSH push at all).
    private static SourceProfile legacyGateSshSource() {
        return SourceProfile.sourceProfile(sourceNameOrDefault("dc-1"),
                                           SourceType.SSH,
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(2),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    private static ClusterBootstrapConfig configWithShortTimeout(SourceProfile source,
                                                                 Map<String, RuntimeProfile> runtimes) {
        var timeouts = TimeoutsConfig.timeoutsConfig("3s", "10s", "10s");
        var ports = PortMapping.defaultPortMapping();
        var ops = OperationsConfig.operationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                                    TlsDeploymentConfig.defaultTlsConfig(),
                                                    timeouts,
                                                    ports);
        return ClusterBootstrapConfig.clusterBootstrapConfig(CLUSTER_VERSION,
                                                             ClusterIdentity.clusterIdentity(CLUSTER_NAME.value(), CLUSTER_VERSION).unwrap(),
                                                             CoreTopology.defaultCoreTopology(),
                                                             Map.of("eu-1", source, "dc-1", legacyGateSshSource()),
                                                             runtimes,
                                                             InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                             ops);
    }

    private static BootstrapContext contextWithRuntimeImage(SourceProfile source, String image) {
        var runtimes = Map.of("default",
                              RuntimeProfile.runtimeProfile("default",
                                                            RuntimeType.CONTAINER,
                                                            Option.some(image),
                                                            Option.empty()));
        var config = configWithShortTimeout(source, runtimes);
        var nodes = List.of(
            ProvisionedNode.provisionedNode("eu-1-core-0", "100", "203.0.113.10"),
            ProvisionedNode.provisionedNode("eu-1-core-1", "101", "203.0.113.11"),
            ProvisionedNode.provisionedNode("eu-1-core-2", "102", "203.0.113.12"));
        var addresses = List.of(
            NodeAddress.nodeAddress("eu-1-core-0", "203.0.113.10", Option.empty()),
            NodeAddress.nodeAddress("eu-1-core-1", "203.0.113.11", Option.empty()),
            NodeAddress.nodeAddress("eu-1-core-2", "203.0.113.12", Option.empty()));
        var state = BootstrapState.initialState(CLUSTER_NAME, "h", "now").withClusterSecret(CLUSTER_SECRET);
        return BootstrapContext.bootstrapContext(config, state, nodes, addresses)
                               .withClusterSecret(CLUSTER_SECRET);
    }

    private static BootstrapContext contextWithJvmRuntime(SourceProfile source) {
        var runtimes = Map.of("default",
                              RuntimeProfile.runtimeProfile("default",
                                                            RuntimeType.JVM,
                                                            Option.empty(),
                                                            Option.empty()));
        var config = configWithShortTimeout(source, runtimes);
        var nodes = List.of(
            ProvisionedNode.provisionedNode("eu-1-core-0", "100", "203.0.113.10"),
            ProvisionedNode.provisionedNode("eu-1-core-1", "101", "203.0.113.11"),
            ProvisionedNode.provisionedNode("eu-1-core-2", "102", "203.0.113.12"));
        var addresses = List.of(
            NodeAddress.nodeAddress("eu-1-core-0", "203.0.113.10", Option.empty()),
            NodeAddress.nodeAddress("eu-1-core-1", "203.0.113.11", Option.empty()),
            NodeAddress.nodeAddress("eu-1-core-2", "203.0.113.12", Option.empty()));
        var state = BootstrapState.initialState(CLUSTER_NAME, "h", "now").withClusterSecret(CLUSTER_SECRET);
        return BootstrapContext.bootstrapContext(config, state, nodes, addresses)
                               .withClusterSecret(CLUSTER_SECRET);
    }

    private static BootstrapContext contextWithThreeCloudNodes(SourceProfile source) {
        var config = configWithShortTimeout(source);
        var nodes = List.of(
            ProvisionedNode.provisionedNode("eu-1-core-0", "100", "203.0.113.10"),
            ProvisionedNode.provisionedNode("eu-1-core-1", "101", "203.0.113.11"),
            ProvisionedNode.provisionedNode("eu-1-core-2", "102", "203.0.113.12"));
        var addresses = List.of(
            NodeAddress.nodeAddress("eu-1-core-0", "203.0.113.10", Option.empty()),
            NodeAddress.nodeAddress("eu-1-core-1", "203.0.113.11", Option.empty()),
            NodeAddress.nodeAddress("eu-1-core-2", "203.0.113.12", Option.empty()));
        var state = BootstrapState.initialState(CLUSTER_NAME, "h", "now").withClusterSecret(CLUSTER_SECRET);
        return BootstrapContext.bootstrapContext(config, state, nodes, addresses)
                               .withClusterSecret(CLUSTER_SECRET);
    }

    private record SshInvocation(String host, String command, SshConfig config) {}

    private record TestError(String detail) implements Cause {
        @Override public String message() { return detail; }
    }

    private static Fn1<Result<String>, String> alwaysHealthy() {
        return url -> Result.success("OK");
    }

    private static Fn1<String, String> envWithKey(String keyPath) {
        return name -> SshKeyResolver.AETHER_SSH_KEY_ENV.equals(name) ? keyPath : null;
    }

    private static Fn1<String, String> emptyEnv() {
        return name -> null;
    }

    @Test
    void deployCloudSource_invokesSshExec_oncePerNode_withDockerRestartCommand() {
        // Bug 16-D: each node now gets TWO sshExec calls (preflight + docker-restart).
        // Bug 17: preflight command is 'cloud-init status --wait' (not 'true') so it blocks until
        // cloud-init has finished installing docker. This test focuses on the docker-restart leg.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var invocations = new ConcurrentLinkedQueue<SshInvocation>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            invocations.add(new SshInvocation(host, command, config));
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "Cloud deploy must succeed when SSH and health-poll succeed; got: " + result);
        var dockerInvocations = invocations.stream().filter(i -> i.command().startsWith("docker")).toList();
        assertEquals(3, dockerInvocations.size(), "Docker-restart SSH must be invoked exactly once per cloud node");
        var hostsSeen = dockerInvocations.stream().map(SshInvocation::host).toList();
        assertTrue(hostsSeen.contains("203.0.113.10"), "Must SSH-back to node 0; saw: " + hostsSeen);
        assertTrue(hostsSeen.contains("203.0.113.11"), "Must SSH-back to node 1; saw: " + hostsSeen);
        assertTrue(hostsSeen.contains("203.0.113.12"), "Must SSH-back to node 2; saw: " + hostsSeen);
    }

    @Test
    void deployCloudSource_passesFinalThreePartPeers_inDockerRunCommand() {
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var commands = new ConcurrentHashMap<String, String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            commands.put(host, command);
            return Result.success("");
        };

        var _ = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                      ctx.config().sources().get("eu-1"),
                                                      sourceNameOrDefault("eu-1"),
                                                      alwaysHealthy(),
                                                      sshExec,
                                                      envWithKey("/home/op/.ssh/aether_id_ed25519"));

        var expectedPeers = String.join(",", BootstrapPhaseDeploy.buildThreePartPeers(ctx));
        // Sanity: peers are nodeId:host:port format
        assertTrue(expectedPeers.contains("eu-1-core-0:203.0.113.10:"),
                   "buildThreePartPeers must produce nodeId:host:port; got: " + expectedPeers);
        // Bug 16-C: all peers MUST advertise the SAME cluster port (each VM is a separate host with --network host).
        var clusterPort = ctx.config().operations().ports().cluster();
        assertTrue(expectedPeers.contains("eu-1-core-0:203.0.113.10:" + clusterPort)
                   && expectedPeers.contains("eu-1-core-1:203.0.113.11:" + clusterPort)
                   && expectedPeers.contains("eu-1-core-2:203.0.113.12:" + clusterPort),
                   "All peers must advertise the same cluster port (no +i offset); got: " + expectedPeers);
        for (var cmd : commands.values()) {
            assertTrue(cmd.contains("-e PEERS=\"" + expectedPeers + "\""),
                       () -> "Each docker-run command must export the finalized PEERS env var: " + cmd);
        }
    }

    @Test
    void deployCloudSource_threadsAllPerNodeEnvVars_intoDockerRunCommand() {
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var commands = new ConcurrentHashMap<String, String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            commands.put(host, command);
            return Result.success("");
        };

        var _ = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                      ctx.config().sources().get("eu-1"),
                                                      sourceNameOrDefault("eu-1"),
                                                      alwaysHealthy(),
                                                      sshExec,
                                                      envWithKey("/home/op/.ssh/aether_id_ed25519"));

        var clusterPort = ctx.config().operations().ports().cluster();
        var mgmtPort = ctx.config().operations().ports().management();

        var cmd0 = commands.get("203.0.113.10");
        assertTrue(cmd0.contains("-e NODE_ID=\"eu-1-core-0\""), "NODE_ID must be set per node: " + cmd0);
        assertTrue(cmd0.contains("-e CLUSTER_PORT=\"" + clusterPort + "\""), "CLUSTER_PORT must be set: " + cmd0);
        assertTrue(cmd0.contains("-e MANAGEMENT_PORT=\"" + mgmtPort + "\""), "MANAGEMENT_PORT must be set: " + cmd0);
        assertTrue(cmd0.contains("-e AETHER_CLUSTER_SECRET=\"" + CLUSTER_SECRET + "\""),
                   "AETHER_CLUSTER_SECRET must be threaded through: " + cmd0);

        var cmd1 = commands.get("203.0.113.11");
        assertTrue(cmd1.contains("-e NODE_ID=\"eu-1-core-1\""), "NODE_ID must be node-specific: " + cmd1);
    }

    @Test
    void deployCloudSource_dockerRunCommand_alwaysIncludesRmFAndConfigBindMount() {
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var commands = new ConcurrentHashMap<String, String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            commands.put(host, command);
            return Result.success("");
        };

        var _ = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                      ctx.config().sources().get("eu-1"),
                                                      sourceNameOrDefault("eu-1"),
                                                      alwaysHealthy(),
                                                      sshExec,
                                                      envWithKey("/home/op/.ssh/aether_id_ed25519"));

        for (var cmd : commands.values()) {
            assertTrue(cmd.contains("docker rm -f aether-node"),
                       () -> "Restart command MUST tear down the previous container: " + cmd);
            assertTrue(cmd.contains("docker run -d"),
                       () -> "Restart command MUST start a new container in detached mode: " + cmd);
            assertTrue(cmd.contains("-v /opt/aether/config/aether.toml:/app/aether.toml:ro"),
                       () -> "Bind-mount of composed aether.toml MUST be preserved (Bug 13): " + cmd);
            assertTrue(cmd.contains("--restart no"),
                       () -> "Container must NOT auto-restart — CTM owns recovery (deployment-recovery.md): " + cmd);
            assertTrue(cmd.contains("ghcr.io/pragmaticalabs/aether-node:" + CLUSTER_VERSION),
                       () -> "Image must fall back to derived (cluster.version) when no [runtime.default] image set: " + cmd);
        }
    }

    @Test
    void deployCloudSource_failsAndNamesIp_whenSshUnreachable() {
        // Bug 16-D: SSH-back failure during the docker-restart loop must surface the failing IP and
        // the reason. Preflight passes (sshExec succeeds for the cloud-init wait), but the restart
        // command fails.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var failingHost = "203.0.113.11";
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            // Preflight uses command="cloud-init status --wait"; restart uses "docker rm -f ...".
            // Only fail the restart.
            if (failingHost.equals(host) && command.startsWith("docker")) {
                return new TestError("ssh: connect refused").result();
            }
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"),
                                                            100L,
                                                            10L);

        assertTrue(result.isFailure(), "Phase must fail when SSH-back fails on at least one node");
        var msg = result.fold(c -> c.message(), v -> "<unexpected success: " + v + ">");
        assertTrue(msg.contains(failingHost),
                   () -> "Failure message must name the unreachable IP. Got: " + msg);
        assertTrue(msg.contains("Failed to restart aether-node"),
                   () -> "Failure message must explain *what* failed. Got: " + msg);
    }

    @Test
    void deployCloudSource_failsFastWithoutHealthPoll_whenSshFails() {
        // With Bug 16-D: persistent SSH failure is now caught by the preflight (before docker-restart).
        // Health poll still must not run.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> new TestError("denied").result();
        var pollCount = new AtomicInteger();
        Fn1<Result<String>, String> healthCheck = url -> {
            pollCount.incrementAndGet();
            return Result.success("OK");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            healthCheck,
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"),
                                                            100L,
                                                            10L);

        assertTrue(result.isFailure(), "SSH failure must abort the phase before health-poll");
        assertEquals(0, pollCount.get(),
                     "Health poll MUST NOT run when SSH-back failed — that would be a false positive");
    }

    @Test
    void deployCloudSource_failsWithGuidance_whenNoSshKeyAvailable() {
        // No source.key() and no env var → can't SSH back → must fail with actionable message.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> Result.success("");

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            emptyEnv());

        assertTrue(result.isFailure(), "Must fail when no SSH private key is available for cloud restart");
        var msg = result.fold(c -> c.message(), v -> "<unexpected success: " + v + ">");
        assertTrue(msg.contains(SshKeyResolver.AETHER_SSH_KEY_ENV),
                   () -> "Failure message must point operator to the env var: " + msg);
    }

    @Test
    void deployCloudSource_resolvesSshKey_fromSourceProfile_whenProvided() {
        var keyPath = "/operator/keys/cluster_id_ed25519";
        var source = cloudSourceWithKey(keyPath);
        var ctx = contextWithThreeCloudNodes(source);
        var configsSeen = new ConcurrentLinkedQueue<SshConfig>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            configsSeen.add(config);
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            emptyEnv());

        assertTrue(result.isSuccess(), () -> "Source-provided key must be sufficient; got: " + result);
        for (var c : configsSeen) {
            assertEquals(keyPath, c.keyPath(), "SshConfig must use source.key() when present");
            assertEquals("aether", c.user(), "SshConfig user must come from source.user()");
        }
    }

    @Test
    void deployCloudSource_resolvesSshKey_fromEnvVar_whenSourceHasNoKey() {
        var envKey = "/home/operator/.ssh/aether_bootstrap_key";
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var configsSeen = new ConcurrentLinkedQueue<SshConfig>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            configsSeen.add(config);
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey(envKey));

        assertTrue(result.isSuccess(), () -> "Env-var key must be a valid fallback; got: " + result);
        for (var c : configsSeen) {
            assertEquals(envKey, c.keyPath(), "SshConfig must use AETHER_SSH_KEY env var when source.key() absent");
        }
    }

    @Test
    void deployCloudSource_skipsSshAndPoll_whenNoCloudNodesProvisioned() {
        var emptyCtx = BootstrapContext.bootstrapContext(configWithShortTimeout(cloudSource()),
                                                         BootstrapState.initialState(CLUSTER_NAME, "h", "now"),
                                                         List.of(),
                                                         List.of()).withClusterSecret(CLUSTER_SECRET);
        var sshCount = new AtomicInteger();
        var pollCount = new AtomicInteger();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            sshCount.incrementAndGet();
            return Result.success("");
        };
        Fn1<Result<String>, String> healthCheck = url -> {
            pollCount.incrementAndGet();
            return Result.success("OK");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(emptyCtx,
                                                            emptyCtx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            healthCheck,
                                                            sshExec,
                                                            envWithKey("/some/key"));

        assertTrue(result.isSuccess(), "Empty node list must be a no-op success");
        assertEquals(0, sshCount.get(), "No SSH attempts when no nodes were provisioned");
        assertEquals(0, pollCount.get(), "No polling when no nodes were provisioned");
    }

    @Test
    void buildRestartCommand_includesAllRequiredEnvVarsAndImage_inExpectedOrder() {
        // Mutation guard: any future refactor that drops/renames an env var should fail here.
        var cmd = BootstrapPhaseDeploy.buildRestartCommand("ghcr.io/pragmaticalabs/aether-node:" + CLUSTER_VERSION,
                                                           CLUSTER_NAME,
                                                           "eu-1-core-0",
                                                           8090,
                                                           8091,
                                                           "eu-1-core-0:1.2.3.4:8090,eu-1-core-1:1.2.3.5:8091",
                                                           CLUSTER_SECRET,
                                                           emptyEnv());
        assertTrue(cmd.contains("docker rm -f aether-node"), cmd);
        assertTrue(cmd.contains("docker run -d --name aether-node --restart no --network host"), cmd);
        assertTrue(cmd.contains("-l aether-cluster=" + CLUSTER_NAME), cmd);
        assertTrue(cmd.contains("-l aether-node-id=eu-1-core-0"), cmd);
        assertTrue(cmd.contains("-v /opt/aether/config/aether.toml:/app/aether.toml:ro"), cmd);
        assertTrue(cmd.contains("-e NODE_ID=\"eu-1-core-0\""), cmd);
        assertTrue(cmd.contains("-e CLUSTER_PORT=\"8090\""), cmd);
        assertTrue(cmd.contains("-e MANAGEMENT_PORT=\"8091\""), cmd);
        assertTrue(cmd.contains("-e PEERS=\"eu-1-core-0:1.2.3.4:8090,eu-1-core-1:1.2.3.5:8091\""), cmd);
        assertTrue(cmd.contains("-e AETHER_CLUSTER_SECRET=\"" + CLUSTER_SECRET + "\""), cmd);
        assertTrue(cmd.endsWith("ghcr.io/pragmaticalabs/aether-node:" + CLUSTER_VERSION), cmd);
        assertFalse(cmd.contains(":latest"), "Image tag must follow cluster.version, not :latest. Got: " + cmd);
    }

    // --- Env-parity regression: the finalized-PEERS re-launch MUST carry the SAME
    // host-env-derived identity allow-list + isolated AETHER_INSECURE_DEV_MODE that the
    // cloud-init start emits, or the C2 security gate refuses to serve /health/live. ---

    private static Fn1<String, String> envOf(Map<String, String> map) {
        return name -> map.get(name);
    }

    @Test
    void buildRestartCommand_emitsInsecureDevMode_whenPresentInInjectedEnv() {
        var cmd = BootstrapPhaseDeploy.buildRestartCommand("img:1",
                                                           CLUSTER_NAME,
                                                           "eu-1-core-0",
                                                           8090,
                                                           8091,
                                                           "eu-1-core-0:1.2.3.4:8090",
                                                           CLUSTER_SECRET,
                                                           envOf(Map.of("AETHER_INSECURE_DEV_MODE", "true")));
        assertTrue(cmd.contains("-e AETHER_INSECURE_DEV_MODE=\"true\""),
                   () -> "Re-launch MUST carry AETHER_INSECURE_DEV_MODE from the host env (C2 gate): " + cmd);
    }

    @Test
    void buildRestartCommand_omitsInsecureDevMode_whenAbsentFromInjectedEnv() {
        var cmd = BootstrapPhaseDeploy.buildRestartCommand("img:1",
                                                           CLUSTER_NAME,
                                                           "eu-1-core-0",
                                                           8090,
                                                           8091,
                                                           "eu-1-core-0:1.2.3.4:8090",
                                                           CLUSTER_SECRET,
                                                           emptyEnv());
        assertFalse(cmd.contains("AETHER_INSECURE_DEV_MODE"),
                    () -> "Unset dev-mode MUST NOT be emitted (no empty -e VAR=\"\"; prod-safe): " + cmd);
    }

    @Test
    void buildRestartCommand_emitsPresentIdentityVar_andOmitsAbsentOnes() {
        var cmd = BootstrapPhaseDeploy.buildRestartCommand("img:1",
                                                           CLUSTER_NAME,
                                                           "eu-1-core-0",
                                                           8090,
                                                           8091,
                                                           "eu-1-core-0:1.2.3.4:8090",
                                                           CLUSTER_SECRET,
                                                           envOf(Map.of("AETHER_API_KEY", "ak-123")));
        assertTrue(cmd.contains("-e AETHER_API_KEY=\"ak-123\""),
                   () -> "Present identity allow-list var MUST be threaded into the re-launch: " + cmd);
        assertFalse(cmd.contains("AETHER_PROVISIONED_BY"),
                    () -> "Absent identity var MUST NOT be emitted as empty -e VAR=\"\": " + cmd);
        assertFalse(cmd.contains("-e VAR=\"\""), cmd);
    }

    @Test
    void buildRestartCommand_clusterSecretAppearsExactlyOnce_evenWhenInIdentityAllowList() {
        // AETHER_CLUSTER_SECRET is BOTH in ClusterIdentityEnv.IDENTITY_VARS AND passed explicitly.
        // The allow-list pass must exclude it so it is never emitted twice. Inject it into the
        // host-env lookup too, to prove the exclusion holds regardless of host env.
        var cmd = BootstrapPhaseDeploy.buildRestartCommand("img:1",
                                                           CLUSTER_NAME,
                                                           "eu-1-core-0",
                                                           8090,
                                                           8091,
                                                           "eu-1-core-0:1.2.3.4:8090",
                                                           CLUSTER_SECRET,
                                                           envOf(Map.of("AETHER_CLUSTER_SECRET", "host-env-secret")));
        var occurrences = cmd.split("-e AETHER_CLUSTER_SECRET=", -1).length - 1;
        assertEquals(1, occurrences,
                     () -> "AETHER_CLUSTER_SECRET must appear exactly once (explicit param, not duplicated "
                           + "from the identity allow-list). Got " + occurrences + " in: " + cmd);
        assertTrue(cmd.contains("-e AETHER_CLUSTER_SECRET=\"" + CLUSTER_SECRET + "\""),
                   () -> "The single AETHER_CLUSTER_SECRET must carry the finalized param value: " + cmd);
        assertFalse(cmd.contains("host-env-secret"),
                    () -> "Host-env AETHER_CLUSTER_SECRET must NOT leak in via the allow-list pass: " + cmd);
    }

    @Test
    void buildRestartCommand_identityEnvFlags_useSingleLineForm_noLineContinuation() {
        // SSH-exec'd single line: env flags must be ' -e VAR="value"' with NO trailing backslash.
        var cmd = BootstrapPhaseDeploy.buildRestartCommand("img:1",
                                                           CLUSTER_NAME,
                                                           "eu-1-core-0",
                                                           8090,
                                                           8091,
                                                           "eu-1-core-0:1.2.3.4:8090",
                                                           CLUSTER_SECRET,
                                                           envOf(Map.of("AETHER_INSECURE_DEV_MODE", "true",
                                                                        "AETHER_API_KEY", "ak-1")));
        assertFalse(cmd.contains("\\\n"), () -> "Single-line SSH command must not contain line continuations: " + cmd);
        assertFalse(cmd.contains("\n"), () -> "Restart command must be a single line: " + cmd);
        assertTrue(cmd.endsWith("img:1"), () -> "Identity flags must precede the image; image stays last: " + cmd);
    }

    @Test
    void buildJvmRestartCommand_inlinesInsecureDevMode_whenPresentInInjectedEnv_secretOnce() {
        var cmd = BootstrapPhaseDeploy.buildJvmRestartCommand("eu-1-core-0",
                                                             8090,
                                                             8091,
                                                             "eu-1-core-0:1.2.3.4:8090",
                                                             CLUSTER_SECRET,
                                                             CLUSTER_NAME,
                                                             envOf(Map.of("AETHER_INSECURE_DEV_MODE", "true")));
        assertTrue(cmd.contains("'AETHER_INSECURE_DEV_MODE=true'"),
                   () -> "JVM re-launch MUST write AETHER_INSECURE_DEV_MODE from host env into the unit's env file (C2 gate): " + cmd);
        var secretOccurrences = cmd.split("AETHER_CLUSTER_SECRET=", -1).length - 1;
        assertEquals(1, secretOccurrences,
                     () -> "AETHER_CLUSTER_SECRET must appear exactly once in the JVM command: " + cmd);
        assertTrue(cmd.indexOf("AETHER_INSECURE_DEV_MODE") < cmd.indexOf("systemctl restart"),
                   () -> "every env line must be written BEFORE the unit is restarted, or the restart reads the old file: " + cmd);
        assertTrue(cmd.contains("systemctl restart aether-node.service"),
                   () -> "JVM re-launch must restart the unit: " + cmd);
        assertFalse(cmd.contains("docker"), () -> "JVM command must NOT mention docker: " + cmd);
    }

    @Test
    void buildJvmRestartCommand_omitsIdentityVars_whenInjectedEnvEmpty() {
        var cmd = BootstrapPhaseDeploy.buildJvmRestartCommand("eu-1-core-0",
                                                             8090,
                                                             8091,
                                                             "eu-1-core-0:1.2.3.4:8090",
                                                             CLUSTER_SECRET,
                                                             CLUSTER_NAME,
                                                             emptyEnv());
        assertFalse(cmd.contains("AETHER_INSECURE_DEV_MODE"),
                    () -> "Unset dev-mode MUST NOT be inlined in the JVM command: " + cmd);
        assertFalse(cmd.contains("AETHER_API_KEY"),
                    () -> "Unset identity var MUST NOT be inlined in the JVM command: " + cmd);
    }

    @Test
    void deployCloudSource_runsSshBackBeforeHealthPoll_notAfter() {
        // Ordering guarantee: SSH-back must happen first, otherwise an empty-PEERS container
        // could falsely appear "healthy" on /health/live and we'd skip the restart entirely.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var order = new ConcurrentLinkedQueue<String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            order.add("ssh:" + host);
            return Result.success("");
        };
        Fn1<Result<String>, String> healthCheck = url -> {
            order.add("poll:" + url);
            return Result.success("OK");
        };

        var _ = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                      ctx.config().sources().get("eu-1"),
                                                      sourceNameOrDefault("eu-1"),
                                                      healthCheck,
                                                      sshExec,
                                                      envWithKey("/home/op/.ssh/aether_id_ed25519"));

        var events = order.stream().toList();
        var firstPoll = events.indexOf(events.stream().filter(e -> e.startsWith("poll:")).findFirst().orElse(""));
        var lastSsh = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith("ssh:")) { lastSsh = i; }
        }
        assertTrue(lastSsh >= 0, "At least one SSH must have happened: " + events);
        assertTrue(firstPoll == -1 || lastSsh < firstPoll,
                   () -> "All SSH-back events must precede the first health poll. Events: " + events);
    }

    // --- Bug 16-A: SSH user defaults to "root" for cloud sources ---

    @Test
    void deployCloudSource_sshUserDefaultsToRoot_whenSourceHasNoUser() {
        // Bug 16-A: cloud-init runs as root and 'aether' user has no docker group access.
        // Cloud SSH-back must default to root unless the operator explicitly overrides source.user.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var configsSeen = new ConcurrentLinkedQueue<SshConfig>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            configsSeen.add(config);
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "Cloud deploy must succeed; got: " + result);
        assertFalse(configsSeen.isEmpty(), "At least one SSH invocation must have been recorded");
        for (var c : configsSeen) {
            assertEquals("root", c.user(),
                         "Cloud source without explicit user MUST default to 'root' (Bug 16-A); got: " + c.user());
        }
    }

    @Test
    void deployCloudSource_sshUser_honoursExplicitOverride() {
        // Operator's explicit source.user wins over the root default.
        var ctx = contextWithThreeCloudNodes(cloudSourceWithUser("ubuntu"));
        var configsSeen = new ConcurrentLinkedQueue<SshConfig>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            configsSeen.add(config);
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "Cloud deploy must succeed; got: " + result);
        for (var c : configsSeen) {
            assertEquals("ubuntu", c.user(),
                         "Explicit source.user MUST win over the root default; got: " + c.user());
        }
    }

    // --- Bug 16-B: image comes from RuntimeProfile when set, falls back to derived otherwise ---

    @Test
    void deployCloudSource_imageFromRuntimeProfile_whenConfigured() {
        // Bug 16-B: image must come from [runtime.default].image, not derived from cluster.version.
        var configuredImage = "ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1-candidate";
        var ctx = contextWithRuntimeImage(cloudSource(), configuredImage);
        var commands = new ConcurrentHashMap<String, String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            commands.put(host + "|" + command.split(" ", 2)[0], command);
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "Cloud deploy must succeed; got: " + result);
        var dockerCommands = commands.values().stream().filter(c -> c.startsWith("docker")).toList();
        assertFalse(dockerCommands.isEmpty(), "At least one docker-restart command must have been issued");
        for (var cmd : dockerCommands) {
            assertTrue(cmd.endsWith(configuredImage),
                       () -> "Docker run MUST use image from [runtime.default].image verbatim. Got: " + cmd);
            assertFalse(cmd.contains("aether-node:" + CLUSTER_VERSION + " "),
                        () -> "Configured image MUST override derived (cluster.version). Got: " + cmd);
        }
    }

    @Test
    void deployCloudSource_imageFallsBackToDerived_whenRuntimeProfileHasNoImage() {
        // No [runtime.default] entry → fall back to the cluster.version-derived tag.
        var ctx = contextWithThreeCloudNodes(cloudSource()); // empty runtimes map
        var commands = new ConcurrentHashMap<String, String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            commands.put(host, command);
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "Cloud deploy must succeed; got: " + result);
        for (var cmd : commands.values()) {
            if (!cmd.startsWith("docker")) { continue; }
            assertTrue(cmd.endsWith("ghcr.io/pragmaticalabs/aether-node:" + CLUSTER_VERSION),
                       () -> "Without runtime.image config, image MUST fall back to cluster.version. Got: " + cmd);
        }
    }

    // --- Bug 16-C: peers list emits the SAME cluster port for all nodes ---

    @Test
    void buildThreePartPeers_emitsSameClusterPort_forAllNodes() {
        // Bug 16-C: previous +i offset broke multi-host clouds where every VM binds the same port.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var clusterPort = ctx.config().operations().ports().cluster();

        var peers = BootstrapPhaseDeploy.buildThreePartPeers(ctx);

        assertEquals(3, peers.size(), "All three nodes must appear in peers list");
        assertEquals("eu-1-core-0:203.0.113.10:" + clusterPort, peers.get(0),
                     "Peer 0 must use the cluster port (no +i offset)");
        assertEquals("eu-1-core-1:203.0.113.11:" + clusterPort, peers.get(1),
                     "Peer 1 must use the SAME cluster port as peer 0 (was port+1, regression)");
        assertEquals("eu-1-core-2:203.0.113.12:" + clusterPort, peers.get(2),
                     "Peer 2 must use the SAME cluster port as peers 0 and 1");
    }

    // --- Bug 16-D: SSH preflight polls until reachable, fails after budget ---

    @Test
    void deployCloudSource_sshPreflight_retriesUntilReachable_thenProceeds() {
        // Bug 16-D: timing race — cloud-init may still be installing docker when DEPLOY_RUNTIME starts.
        // Preflight must retry and proceed once SSH becomes reachable.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var attempts = new ConcurrentHashMap<String, AtomicInteger>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            // Preflight uses 'cloud-init status --wait'; restart uses 'docker ...'. Track preflight per host.
            if ("cloud-init status --wait".equals(command)) {
                var n = attempts.computeIfAbsent(host, _ -> new AtomicInteger()).incrementAndGet();
                if (n < 3) { return new TestError("ssh: connection timed out").result(); }
            }
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"),
                                                            10_000L,
                                                            10L);

        assertTrue(result.isSuccess(),
                   () -> "Preflight must retry until reachable, then proceed; got: " + result);
        for (var counter : attempts.values()) {
            assertTrue(counter.get() >= 3,
                       "Each host's preflight must have been retried at least 3 times. Got: " + counter.get());
        }
    }

    @Test
    void deployCloudSource_sshPreflight_failsAndNamesUnreachableIp_afterBudget() {
        // Persistent SSH failure → preflight times out → phase fails naming the unreachable IP.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var failingHost = "203.0.113.12";
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            if (failingHost.equals(host) && "cloud-init status --wait".equals(command)) {
                return new TestError("ssh: connect timeout").result();
            }
            return Result.success("");
        };
        var pollCount = new AtomicInteger();
        Fn1<Result<String>, String> healthCheck = url -> {
            pollCount.incrementAndGet();
            return Result.success("OK");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            healthCheck,
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"),
                                                            100L,
                                                            10L);

        assertTrue(result.isFailure(), "Preflight timeout must fail the phase");
        var msg = result.fold(c -> c.message(), v -> "<unexpected success: " + v + ">");
        assertTrue(msg.contains("SSH preflight failed"),
                   () -> "Failure message must explain the failure mode. Got: " + msg);
        assertTrue(msg.contains(failingHost),
                   () -> "Failure message must name the unreachable IP. Got: " + msg);
        assertEquals(0, pollCount.get(),
                     "Health poll MUST NOT run when preflight failed");
    }

    @Test
    void deployCloudSource_sshPreflight_runsBeforeDockerRestartLoop() {
        // Ordering: preflight (command="cloud-init status --wait") for ALL nodes must precede the first docker-restart command.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var order = new ConcurrentLinkedQueue<String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            var kind = command.startsWith("docker") ? "restart" : "preflight";
            order.add(kind + ":" + host);
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "Cloud deploy must succeed; got: " + result);
        var events = order.stream().toList();
        var firstRestart = -1;
        var lastPreflight = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith("preflight:")) { lastPreflight = i; }
            if (events.get(i).startsWith("restart:") && firstRestart == -1) { firstRestart = i; }
        }
        assertTrue(lastPreflight >= 0, "At least one preflight event expected: " + events);
        assertTrue(firstRestart >= 0, "At least one restart event expected: " + events);
        assertTrue(lastPreflight < firstRestart,
                   () -> "All preflight checks must precede the first docker-restart. Events: " + events);
    }

    // --- Bug 17: SSH preflight must wait for cloud-init to finish, not just for SSH ---

    // --- Bug 20: JVM runtime profile produces JVM SSH-back commands, not docker ---

    /// #1021 — this used to assert the ANCHORED `pkill -f '^java -jar <JAR>'` pattern that Bug 20a
    /// introduced. The anchor existed because `pkill -f` matches the full command line and the SSH
    /// session carrying this very command has `java -jar <JAR>` in its own argv, so an unanchored
    /// pattern self-killed the session.
    ///
    /// The re-launch now restarts the systemd unit BY NAME, which removes that hazard class rather
    /// than guarding it: `systemctl restart aether-node.service` cannot match a command line because
    /// it does not look at command lines. So the assertions invert — the pattern must be ABSENT — and
    /// the test keeps its original job of proving the JVM path is not the docker path.
    @Test
    void deployCloudSource_jvmRuntime_restartsTheUnitByName_notDockerAndNotByProcessPattern() {
        var ctx = contextWithJvmRuntime(cloudSource());
        var commands = new ConcurrentHashMap<String, String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            // Track only the restart leg (preflight is "cloud-init status --wait").
            if (!"cloud-init status --wait".equals(command)) { commands.put(host, command); }
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "JVM cloud deploy must succeed; got: " + result);
        assertEquals(3, commands.size(), "Each JVM node must receive a single restart command");
        for (var cmd : commands.values()) {
            assertTrue(cmd.contains("systemctl restart aether-node.service"),
                       () -> "JVM restart MUST drive the systemd unit by name: " + cmd);
            assertFalse(cmd.contains("pkill"),
                        () -> "Bug 20a's hazard class is removed, not re-guarded: `pkill -f` matches the SSH "
                              + "session's own argv, which is why it needed anchoring at all. A unit name "
                              + "matches nothing. Got: " + cmd);
            assertFalse(cmd.contains("nohup") || cmd.contains("disown"),
                        () -> "#1021: the node must run UNDER the unit. A detached re-launch beside a unit "
                              + "that Restart=no has correctly left `failed` makes `systemctl status` report "
                              + "failure for a node that is serving. Got: " + cmd);
            // Every launch parameter the old `nohup java` line carried as a CLI flag is now an env-file
            // line the unit's EnvironmentFile supplies; the SAME four values must still reach the node.
            assertTrue(cmd.contains("'AETHER_NODE_ID="),
                       () -> "JVM restart MUST carry the node id: " + cmd);
            assertTrue(cmd.contains("'AETHER_CLUSTER_PORT="),
                       () -> "JVM restart MUST carry the cluster port: " + cmd);
            assertTrue(cmd.contains("'AETHER_MANAGEMENT_PORT="),
                       () -> "JVM restart MUST carry the management port: " + cmd);
            assertTrue(cmd.contains("'AETHER_PEERS="),
                       () -> "JVM restart MUST carry the finalized peers: " + cmd);
            assertTrue(cmd.contains("'AETHER_CLUSTER_SECRET=" + CLUSTER_SECRET + "'"),
                       () -> "JVM restart MUST carry AETHER_CLUSTER_SECRET so the C2 gate passes: " + cmd);
            assertTrue(cmd.contains("chmod 600 /etc/aether/node.env"),
                       () -> "the env file carries the cluster secret and must be owner-only: " + cmd);
            // `--config=` and the `/var/log/aether-node.log` redirect are deliberately NOT asserted
            // here any more, and neither property was dropped — both moved OUT of the restart command:
            // the config path is baked into the launcher that the unit's ExecStart runs (pinned by
            // UserDataTemplatePeersTest.render_launchesTheJvmUnderASystemdUnit_thatDoesNotRestartIt),
            // and node output now goes to the journal via the unit's StandardOutput/StandardError
            // (pinned by SystemdUnitTemplateTest), which is what makes `journalctl -u aether-node`
            // work — the operator-queryable trace #1021 exists to provide.
            assertFalse(cmd.contains("docker run"),
                        () -> "JVM restart MUST NOT use docker run: " + cmd);
            assertFalse(cmd.contains("docker rm"),
                        () -> "JVM restart MUST NOT use docker rm: " + cmd);
        }
    }

    @Test
    void deployCloudSource_jvmRuntime_threadsFinalizedPeers_intoJvmCli() {
        var ctx = contextWithJvmRuntime(cloudSource());
        var commands = new ConcurrentHashMap<String, String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            if (!"cloud-init status --wait".equals(command)) { commands.put(host, command); }
            return Result.success("");
        };

        var _ = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                      ctx.config().sources().get("eu-1"),
                                                      sourceNameOrDefault("eu-1"),
                                                      alwaysHealthy(),
                                                      sshExec,
                                                      envWithKey("/home/op/.ssh/aether_id_ed25519"));

        // #1021 — same property, new carrier: finalized PEERS and the per-node id now reach the JVM
        // through the unit's EnvironmentFile rather than as CLI flags on a nohup line.
        var expectedPeers = String.join(",", BootstrapPhaseDeploy.buildThreePartPeers(ctx));
        for (var cmd : commands.values()) {
            assertTrue(cmd.contains("'AETHER_PEERS=" + expectedPeers + "'"),
                       () -> "Each JVM restart command must write the finalized PEERS into the unit env file: " + cmd);
        }
        var cmd0 = commands.get("203.0.113.10");
        assertTrue(cmd0.contains("'AETHER_NODE_ID=eu-1-core-0'"), "Per-node node-id: " + cmd0);
        var cmd1 = commands.get("203.0.113.11");
        assertTrue(cmd1.contains("'AETHER_NODE_ID=eu-1-core-1'"), "Per-node node-id: " + cmd1);
    }

    @Test
    void deployCloudSource_jvmRuntime_runsHealthPollAfterRestart_sameAsContainerPath() {
        var ctx = contextWithJvmRuntime(cloudSource());
        var order = new ConcurrentLinkedQueue<String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            var kind = command.contains("systemctl restart") ? "restart" : "preflight";
            order.add(kind + ":" + host);
            return Result.success("");
        };
        Fn1<Result<String>, String> healthCheck = url -> {
            order.add("poll:" + url);
            return Result.success("OK");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            healthCheck,
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "JVM cloud deploy must succeed; got: " + result);
        var events = order.stream().toList();
        var firstPoll = -1;
        var lastRestart = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith("poll:") && firstPoll == -1) { firstPoll = i; }
            if (events.get(i).startsWith("restart:")) { lastRestart = i; }
        }
        assertTrue(lastRestart >= 0, "At least one JVM restart event expected: " + events);
        assertTrue(firstPoll >= 0, "Health poll MUST run after JVM restart for jvm runtime: " + events);
        assertTrue(lastRestart < firstPoll,
                   () -> "JVM restart MUST precede the first health poll. Events: " + events);
    }

    @Test
    void deployCloudSource_jvmRuntime_failsAndNamesIp_whenSshRestartFails() {
        var ctx = contextWithJvmRuntime(cloudSource());
        var failingHost = "203.0.113.11";
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            if (failingHost.equals(host) && command.contains("systemctl restart")) {
                return new TestError("ssh: connect refused").result();
            }
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"),
                                                            100L,
                                                            10L);

        assertTrue(result.isFailure(), "JVM SSH-back failure must fail the phase");
        var msg = result.fold(c -> c.message(), v -> "<unexpected success: " + v + ">");
        assertTrue(msg.contains(failingHost),
                   () -> "Failure message must name the unreachable IP. Got: " + msg);
        assertTrue(msg.contains("Failed to restart aether-node JVM"),
                   () -> "Failure message must clarify the JVM restart failed (not container): " + msg);
    }

    @Test
    void buildJvmRestartCommand_includesAllRequiredCliFlagsAndEnv_inExpectedOrder() {
        // Mutation guard: any future refactor that drops/renames a CLI flag should fail here.
        var cmd = BootstrapPhaseDeploy.buildJvmRestartCommand("eu-1-core-0",
                                                              8090,
                                                              8091,
                                                              "eu-1-core-0:1.2.3.4:8090,eu-1-core-1:1.2.3.5:8090",
                                                              CLUSTER_SECRET,
                                                              CLUSTER_NAME);
        // #1021 — the re-launch drives the systemd unit instead of pattern-matching the process.
        // `pkill -f` is FORBIDDEN here for two independent reasons: it matched the SSH session's own
        // argv (Bug 20a, which is why it had to be anchored '^java -jar <JAR>'), and with the unit
        // installed at boot it would take the unit to `failed` while a bare nohup process ran beside
        // it — a node that serves while `systemctl status` reports failure, the exact false signal
        // #1021 exists to remove.
        assertFalse(cmd.contains("pkill"),
                    "#1021: the JVM re-launch must name the unit, never pattern-match a command line. Got: " + cmd);
        assertFalse(cmd.contains("nohup") || cmd.contains("disown"),
                    "#1021: the node must run UNDER the unit, not beside it as a detached process. Got: " + cmd);
        assertTrue(cmd.contains("systemctl restart aether-node.service"),
                   "#1021: the re-launch must restart the unit by name. Got: " + cmd);
        assertTrue(cmd.contains("AETHER_CLUSTER_SECRET=" + CLUSTER_SECRET), cmd);
        assertTrue(cmd.contains("AETHER_NODE_ID=eu-1-core-0"), cmd);
        assertTrue(cmd.contains("AETHER_CLUSTER_PORT=8090"), cmd);
        assertTrue(cmd.contains("AETHER_MANAGEMENT_PORT=8091"), cmd);
        assertTrue(cmd.contains("AETHER_PEERS=eu-1-core-0:1.2.3.4:8090,eu-1-core-1:1.2.3.5:8090"), cmd);
        assertTrue(cmd.contains("> /etc/aether/node.env"),
                   "the env file must be REWRITTEN whole, so a re-run cannot leave a stale AETHER_PEERS line last: " + cmd);
        assertTrue(cmd.contains("chmod 600 /etc/aether/node.env"),
                   "the env file carries AETHER_CLUSTER_SECRET and must be owner-only: " + cmd);
        assertFalse(cmd.contains("docker"), "JVM command must NOT mention docker: " + cmd);
    }

    @Test
    void isJvmRuntime_isTrue_forJvmProfile_andFalse_forContainerOrAbsent() {
        // Branching test: a runtime profile of type JVM yields true; container or absent yields false.
        var jvmCtx = contextWithJvmRuntime(cloudSource());
        var containerCtx = contextWithRuntimeImage(cloudSource(), "ghcr.io/example/aether:1");
        var absentCtx = contextWithThreeCloudNodes(cloudSource()); // no [runtime.default] entry

        assertTrue(BootstrapPhaseDeploy.isJvmRuntime(jvmCtx, jvmCtx.config().sources().get("eu-1")),
                   "JVM runtime profile MUST select the JVM branch");
        assertFalse(BootstrapPhaseDeploy.isJvmRuntime(containerCtx, containerCtx.config().sources().get("eu-1")),
                    "Container runtime profile MUST NOT select the JVM branch");
        assertFalse(BootstrapPhaseDeploy.isJvmRuntime(absentCtx, absentCtx.config().sources().get("eu-1")),
                    "Absent runtime profile MUST default to the container branch (existing behaviour)");
    }

    @Test
    void deployCloudSource_sshPreflight_usesCloudInitStatusWaitProbe_notBareTrue() {
        // Bug 17: 'true' as the probe returns success the moment SSH accepts a session, which can
        // happen before cloud-init has installed docker. The probe MUST be 'cloud-init status --wait'
        // so a successful preflight implies docker is present and the initial container is up.
        var ctx = contextWithThreeCloudNodes(cloudSource());
        var preflightCommands = new ConcurrentLinkedQueue<String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            if (!command.startsWith("docker")) { preflightCommands.add(command); }
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "Cloud deploy must succeed; got: " + result);
        assertFalse(preflightCommands.isEmpty(), "At least one preflight probe must have been issued");
        for (var cmd : preflightCommands) {
            assertEquals("cloud-init status --wait", cmd,
                         "SSH preflight probe MUST be 'cloud-init status --wait' (Bug 17), not '" + cmd + "'. "
                         + "Bare 'true' returns the moment SSH accepts a session, before docker is installed.");
        }
    }
}
