// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapPhaseDeployCloudSshRestartTest {

    private static final String CLUSTER_NAME = "prod";

    private static final String CLUSTER_VERSION = "1.0.0";

    private static final String CLUSTER_SECRET = "super-secret-token";

    private static SourceProfile cloudSource() {
        return SourceProfile.sourceProfile("eu-1",
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
        return SourceProfile.sourceProfile("eu-1",
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
        return SourceProfile.sourceProfile("eu-1",
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

    private static ClusterBootstrapConfig configWithShortTimeout(SourceProfile source,
                                                                 Map<String, RuntimeProfile> runtimes) {
        var timeouts = TimeoutsConfig.timeoutsConfig("3s", "10s", "10s");
        var ports = PortMapping.defaultPortMapping();
        var ops = OperationsConfig.operationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                                    TlsDeploymentConfig.defaultTlsConfig(),
                                                    timeouts,
                                                    ports);
        return ClusterBootstrapConfig.clusterBootstrapConfig(CLUSTER_VERSION,
                                                             ClusterIdentity.clusterIdentity(CLUSTER_NAME, CLUSTER_VERSION),
                                                             CoreTopology.defaultCoreTopology(),
                                                             Map.of("eu-1", source),
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
                                                            "eu-1",
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
                                                      "eu-1",
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
                                                      "eu-1",
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
                                                      "eu-1",
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
            assertTrue(cmd.contains("--restart unless-stopped"),
                       () -> "Container must auto-restart on failure: " + cmd);
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                           CLUSTER_SECRET);
        assertTrue(cmd.contains("docker rm -f aether-node"), cmd);
        assertTrue(cmd.contains("docker run -d --name aether-node --restart unless-stopped --network host"), cmd);
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
                                                      "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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
                                                            "eu-1",
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

    @Test
    void deployCloudSource_jvmRuntime_emitsPkillAndNohupJava_notDocker() {
        var ctx = contextWithJvmRuntime(cloudSource());
        var commands = new ConcurrentHashMap<String, String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            // Track only the restart leg (preflight is "cloud-init status --wait").
            if (!"cloud-init status --wait".equals(command)) { commands.put(host, command); }
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            "eu-1",
                                                            alwaysHealthy(),
                                                            sshExec,
                                                            envWithKey("/home/op/.ssh/aether_id_ed25519"));

        assertTrue(result.isSuccess(), () -> "JVM cloud deploy must succeed; got: " + result);
        assertEquals(3, commands.size(), "Each JVM node must receive a single restart command");
        for (var cmd : commands.values()) {
            assertTrue(cmd.contains("pkill -f /opt/aether/aether-node.jar"),
                       () -> "JVM restart MUST kill the previous JVM by jar path: " + cmd);
            assertTrue(cmd.contains("pkill -9 -f /opt/aether/aether-node.jar"),
                       () -> "JVM restart MUST escalate to SIGKILL after SIGTERM: " + cmd);
            assertTrue(cmd.contains("nohup java -jar /opt/aether/aether-node.jar"),
                       () -> "JVM restart MUST relaunch via nohup java -jar: " + cmd);
            assertTrue(cmd.contains("--config=/opt/aether/config/aether.toml"),
                       () -> "JVM restart MUST pass --config=: " + cmd);
            assertTrue(cmd.contains("--node-id="),
                       () -> "JVM restart MUST pass --node-id=: " + cmd);
            assertTrue(cmd.contains("--port="),
                       () -> "JVM restart MUST pass --port=: " + cmd);
            assertTrue(cmd.contains("--management-port="),
                       () -> "JVM restart MUST pass --management-port=: " + cmd);
            assertTrue(cmd.contains("--peers="),
                       () -> "JVM restart MUST pass --peers=: " + cmd);
            assertTrue(cmd.contains("AETHER_CLUSTER_SECRET=\"" + CLUSTER_SECRET + "\""),
                       () -> "JVM restart MUST inline AETHER_CLUSTER_SECRET as env var: " + cmd);
            assertTrue(cmd.contains("disown"),
                       () -> "JVM restart MUST disown so the process survives the SSH session: " + cmd);
            assertTrue(cmd.contains("/var/log/aether-node.log"),
                       () -> "JVM restart MUST redirect stdout/stderr to a log file: " + cmd);
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
                                                      "eu-1",
                                                      alwaysHealthy(),
                                                      sshExec,
                                                      envWithKey("/home/op/.ssh/aether_id_ed25519"));

        var expectedPeers = String.join(",", BootstrapPhaseDeploy.buildThreePartPeers(ctx));
        for (var cmd : commands.values()) {
            assertTrue(cmd.contains("--peers=\"" + expectedPeers + "\""),
                       () -> "Each JVM restart command must export the finalized PEERS via CLI flag: " + cmd);
        }
        var cmd0 = commands.get("203.0.113.10");
        assertTrue(cmd0.contains("--node-id=\"eu-1-core-0\""), "Per-node node-id: " + cmd0);
        var cmd1 = commands.get("203.0.113.11");
        assertTrue(cmd1.contains("--node-id=\"eu-1-core-1\""), "Per-node node-id: " + cmd1);
    }

    @Test
    void deployCloudSource_jvmRuntime_runsHealthPollAfterRestart_sameAsContainerPath() {
        var ctx = contextWithJvmRuntime(cloudSource());
        var order = new ConcurrentLinkedQueue<String>();
        Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
            var kind = command.startsWith("nohup") || command.contains("pkill") ? "restart" : "preflight";
            order.add(kind + ":" + host);
            return Result.success("");
        };
        Fn1<Result<String>, String> healthCheck = url -> {
            order.add("poll:" + url);
            return Result.success("OK");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            "eu-1",
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
            if (failingHost.equals(host) && command.contains("nohup java")) {
                return new TestError("ssh: connect refused").result();
            }
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            "eu-1",
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
                                                              CLUSTER_SECRET);
        assertTrue(cmd.contains("pkill -f /opt/aether/aether-node.jar"), cmd);
        assertTrue(cmd.contains("pkill -9 -f /opt/aether/aether-node.jar"), cmd);
        assertTrue(cmd.contains("AETHER_CLUSTER_SECRET=\"" + CLUSTER_SECRET + "\""), cmd);
        assertTrue(cmd.contains("nohup java -jar /opt/aether/aether-node.jar"), cmd);
        assertTrue(cmd.contains("--config=/opt/aether/config/aether.toml"), cmd);
        assertTrue(cmd.contains("--node-id=\"eu-1-core-0\""), cmd);
        assertTrue(cmd.contains("--port=\"8090\""), cmd);
        assertTrue(cmd.contains("--management-port=\"8091\""), cmd);
        assertTrue(cmd.contains("--peers=\"eu-1-core-0:1.2.3.4:8090,eu-1-core-1:1.2.3.5:8090\""), cmd);
        assertTrue(cmd.contains("/var/log/aether-node.log 2>&1"), cmd);
        assertTrue(cmd.endsWith("disown"), "Command must end with 'disown' to detach: " + cmd);
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
                                                            "eu-1",
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
