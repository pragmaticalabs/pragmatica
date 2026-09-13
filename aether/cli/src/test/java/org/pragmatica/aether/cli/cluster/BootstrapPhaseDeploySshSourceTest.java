// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.AutoHealSpec;
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
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1090 — the SSH source's launch, pinned the way #1085 pinned the cloud re-launch: through
/// `deploySshSource` with captured `ssh`/`scp` commands, asserting on what would reach the host.
/// Four defects on one path: `:latest` instead of the resolved image, no role (label or
/// `AETHER_ROLE`), the runtime profile ignored, and every SSH node of every SSH source deployed onto
/// each other's hosts.
class BootstrapPhaseDeploySshSourceTest {
    private static final ClusterName CLUSTER = clusterName("prod").unwrap();
    private static final String VERSION = "1.0.0";
    private static final String SECRET = "super-secret-token";

    private final Map<String, String> startCommands = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> scpTargets = new ConcurrentLinkedQueue<>();

    private final Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
        if (command.contains("docker run") || command.contains("systemctl")) {
            startCommands.put(host, command);
        }

        return Result.success("");
    };

    private final Fn4<Result<Unit>, String, String, String, SshConfig> scpExec = (local, host, remote, config) -> {
        scpTargets.add(host + ":" + remote);

        return Result.unitResult();
    };

    private static SourceProfile sshSource(String name, List<String> coreHosts, List<String> workerHosts, String runtimeRef) {
        var roles = workerHosts.isEmpty()
                    ? Map.of(NodeRole.CORE, RoleSubTable.roleSubTable(NodeRole.CORE, Option.empty(), Option.some(coreHosts), Option.empty(), Option.empty(), runtimeRef))
                    : Map.of(NodeRole.CORE, RoleSubTable.roleSubTable(NodeRole.CORE, Option.empty(), Option.some(coreHosts), Option.empty(), Option.empty(), runtimeRef),
                             NodeRole.WORKER, RoleSubTable.roleSubTable(NodeRole.WORKER, Option.empty(), Option.some(workerHosts), Option.empty(), Option.empty(), runtimeRef));

        return SourceProfile.sourceProfile(sourceNameOrDefault(name), SourceType.SSH, Option.empty(), Option.empty(), Option.empty(),
                                           Option.empty(), Option.some("aether"), Option.some("/home/op/.ssh/id"), Option.empty(),
                                           LoadBalancerMode.NONE, List.of(), Option.empty(), Map.of(), roles, List.of());
    }

    private static BootstrapContext context(Map<String, SourceProfile> sources,
                                            Map<String, RuntimeProfile> runtimes,
                                            List<ProvisionedNode> nodes) {
        var ops = OperationsConfig.operationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                                    TlsDeploymentConfig.defaultTlsConfig(),
                                                    TimeoutsConfig.timeoutsConfig("3s", "10s", "10s"),
                                                    PortMapping.defaultPortMapping());
        var config = ClusterBootstrapConfig.clusterBootstrapConfig(VERSION,
                                                                   ClusterIdentity.clusterIdentity(CLUSTER.value(), VERSION).unwrap(),
                                                                   CoreTopology.defaultCoreTopology(),
                                                                   sources,
                                                                   runtimes,
                                                                   InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                                   ops);
        var addresses = nodes.stream()
                             .map(n -> NodeAddress.nodeAddress(n.nodeId(), n.publicIp(), Option.empty()))
                             .toList();
        var state = BootstrapState.initialState(CLUSTER, "h", "now").withClusterSecret(SECRET);

        return BootstrapContext.bootstrapContext(config, state, nodes, addresses).withClusterSecret(SECRET);
    }

    private static ProvisionedNode ssh(String nodeId, String host) {
        return ProvisionedNode.provisionedNode(nodeId, "ssh", host);
    }

    private Result<Unit> deploy(BootstrapContext ctx, String sourceName) {
        return BootstrapPhaseDeploy.deploySshSource(ctx,
                                                    ctx.config().sources().get(sourceName),
                                                    sourceNameOrDefault(sourceName),
                                                    sshExec,
                                                    scpExec,
                                                    name -> null);
    }

    @Test
    void sshSource_launchesTheResolvedImage_neverLatest() {
        var runtimes = Map.of("pinned", RuntimeProfile.runtimeProfile("pinned", RuntimeType.CONTAINER, Option.some("ghcr.io/pragmaticalabs/aether-node:1.0.0-rc4"), Option.empty()));
        var ctx = context(Map.of("dc", sshSource("dc", List.of("10.0.0.1"), List.of(), "pinned")),
                          runtimes,
                          List.of(ssh("dc-core-0", "10.0.0.1")));

        var result = deploy(ctx, "dc");

        assertThat(result.isSuccess()).as(() -> "deploy must succeed: " + result).isTrue();
        var cmd = startCommands.get("10.0.0.1");
        assertThat(cmd).as("the host runs the image the operator bootstrapped, not whatever :latest points at")
                       .contains("aether-node:1.0.0-rc4")
                       .doesNotContain(":latest");
    }

    @Test
    void sshSource_labelsAndEnvsEachHostWithItsOwnRole() {
        var ctx = context(Map.of("dc", sshSource("dc", List.of("10.0.0.1"), List.of("10.0.0.2"), "default")),
                          Map.of(),
                          List.of(ssh("dc-core-0", "10.0.0.1"), ssh("dc-worker-0", "10.0.0.2")));

        var result = deploy(ctx, "dc");

        assertThat(result.isSuccess()).as(() -> "deploy must succeed: " + result).isTrue();
        assertThat(startCommands.get("10.0.0.1")).contains("-l aether-role=core").contains("-e AETHER_ROLE=\"core\"");
        assertThat(startCommands.get("10.0.0.2")).as("a host the operator declared as a worker must join as one — with no "
                                                   + "AETHER_ROLE the node classifies itself as CORE")
                                                 .contains("-l aether-role=worker")
                                                 .contains("-e AETHER_ROLE=\"worker\"")
                                                 .contains("-l aether-node-id=dc-worker-0")
                                                 .contains("-e AETHER_CLUSTER_NAME=\"prod\"");
    }

    @Test
    void sshSource_deploysOnlyItsOwnHosts_notAnotherSshSources() {
        var ctx = context(Map.of("dc", sshSource("dc", List.of("10.0.0.1"), List.of(), "default"),
                                 "lab", sshSource("lab", List.of("10.0.1.1"), List.of(), "default")),
                          Map.of(),
                          List.of(ssh("dc-core-0", "10.0.0.1"), ssh("lab-core-0", "10.0.1.1")));

        var result = deploy(ctx, "dc");

        assertThat(result.isSuccess()).as(() -> "deploy must succeed: " + result).isTrue();
        assertThat(startCommands.keySet()).as("`dc`'s deploy must not touch `lab`'s host")
                                          .containsExactly("10.0.0.1");
        assertThat(scpTargets).containsExactly("10.0.0.1:/opt/aether/config/aether.toml");
    }

    @Test
    void sshSource_nonContainerRuntime_isRefusedByName_notSilentlyRunAsAContainer() {
        var runtimes = Map.of("jvm", RuntimeProfile.runtimeProfile("jvm", RuntimeType.JVM, Option.empty(), Option.empty()));
        var ctx = context(Map.of("dc", sshSource("dc", List.of("10.0.0.1"), List.of(), "jvm")),
                          runtimes,
                          List.of(ssh("dc-core-0", "10.0.0.1")));

        var result = deploy(ctx, "dc");

        assertThat(result.isFailure()).as("a declared JVM runtime must not be silently replaced by a container").isTrue();
        assertThat(result.fold(Cause::message, _ -> "")).contains("jvm").contains("dc-core-0");
        assertThat(startCommands).as("nothing may be launched under the wrong runtime").isEmpty();
    }
}
