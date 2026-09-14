// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.assertj.core.api.Assertions.assertThat;


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
    // Every ssh/scp call in arrival order: `ssh:<host>:<command>` (the launch line abbreviated to
    // `launch`) and `scp:<host>` — so the ORDER of what reaches a host is assertable, not only the set.
    private final ConcurrentLinkedQueue<String> calls = new ConcurrentLinkedQueue<>();

    private final Fn3<Result<String>, String, String, SshConfig> sshExec = (host, command, config) -> {
        if (command.contains("docker run") || command.contains("systemctl")) {
            startCommands.put(host, command);
            calls.add("ssh:" + host + ":launch");
        } else {
            calls.add("ssh:" + host + ":" + command);
        }

        return Result.success("");
    };

    private final Fn4<Result<Unit>, String, String, String, SshConfig> scpExec = (local, host, remote, config) -> {
        scpTargets.add(host + ":" + remote);
        calls.add("scp:" + host);

        return Result.unitResult();
    };

    private static SourceProfile sshSource(String name,
                                           List<String> coreHosts,
                                           List<String> workerHosts,
                                           String runtimeRef) {
        var roles = workerHosts.isEmpty()
                    ? Map.of(NodeRole.CORE,
                             RoleSubTable.roleSubTable(NodeRole.CORE,
                                                       Option.empty(),
                                                       Option.some(coreHosts),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       runtimeRef))
                    : Map.of(NodeRole.CORE,
                             RoleSubTable.roleSubTable(NodeRole.CORE,
                                                       Option.empty(),
                                                       Option.some(coreHosts),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       runtimeRef),
                             NodeRole.WORKER,
                             RoleSubTable.roleSubTable(NodeRole.WORKER,
                                                       Option.empty(),
                                                       Option.some(workerHosts),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       runtimeRef));

        return SourceProfile.sourceProfile(sourceNameOrDefault(name),
                                           SourceType.SSH,
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.some("aether"),
                                           Option.some("/home/op/.ssh/id"),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           roles,
                                           List.of());
    }

    private static BootstrapContext context(Map<String, SourceProfile> sources,
                                            Map<String, RuntimeProfile> runtimes,
                                            List<ProvisionedNode> nodes) {
        var ops = OperationsConfig.operationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                                    TlsDeploymentConfig.defaultTlsConfig(),
                                                    TimeoutsConfig.timeoutsConfig("3s", "10s", "10s"),
                                                    PortMapping.defaultPortMapping());
        var config = ClusterBootstrapConfig.clusterBootstrapConfig(VERSION,
                                                                   ClusterIdentity.clusterIdentity(CLUSTER.value(),
                                                                                                   VERSION)
                                                                                  .unwrap(),
                                                                   CoreTopology.defaultCoreTopology(),
                                                                   sources,
                                                                   runtimes,
                                                                   InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                                   ops);
        var addresses = nodes.stream()
                             .map(n -> NodeAddress.nodeAddress(n.nodeId(),
                                                               n.publicIp(),
                                                               Option.empty()))
                             .toList();
        var state = BootstrapState.initialState(CLUSTER, "h", "now").withClusterSecret(SECRET);

        return BootstrapContext.bootstrapContext(config, state, nodes, addresses).withClusterSecret(SECRET);
    }

    private static ProvisionedNode ssh(String nodeId, String host) {
        return ProvisionedNode.provisionedNode(nodeId, "ssh", host);
    }

    private Result<Unit> deploy(BootstrapContext ctx, String sourceName) {
        return deploy(ctx, sourceName, name -> null);
    }

    private Result<Unit> deploy(BootstrapContext ctx, String sourceName, Fn1<String, String> envLookup) {
        return BootstrapPhaseDeploy.deploySshSource(ctx,
                                                    ctx.config().sources().get(sourceName),
                                                    sourceNameOrDefault(sourceName),
                                                    sshExec,
                                                    scpExec,
                                                    envLookup);
    }

    @Test
    void sshSource_launchesTheResolvedImage_neverLatest() {
        var runtimes = Map.of("pinned",
                              RuntimeProfile.runtimeProfile("pinned",
                                                            RuntimeType.CONTAINER,
                                                            Option.some("ghcr.io/pragmaticalabs/aether-node:1.0.0-rc4"),
                                                            Option.empty()));
        var ctx = context(Map.of("dc",
                                 sshSource("dc", List.of("10.0.0.1"), List.of(), "pinned")),
                          runtimes,
                          List.of(ssh("dc-core-0", "10.0.0.1")));
        var result = deploy(ctx, "dc");

        assertThat(result.isSuccess()).as(() -> "deploy must succeed: " + result).isTrue();
        var cmd = startCommands.get("10.0.0.1");

        assertThat(cmd).as("the host runs the image the operator bootstrapped, not whatever :latest points at")
                  .contains("aether-node:1.0.0-rc4")
                  .doesNotContain(":latest");
    }

    /// Review SF-1: the case #1090 was filed on — `[source.x.core] hosts = […]` with no `[runtime.*]`
    /// table at all. The image is then derived from the cluster version; it must never be `:latest`.
    @Test
    void sshSource_withoutARuntimeProfile_launchesTheVersionDerivedImage_neverLatest() {
        var ctx = context(Map.of("dc",
                                 sshSource("dc", List.of("10.0.0.1"), List.of(), "default")),
                          Map.of(),
                          List.of(ssh("dc-core-0", "10.0.0.1")));
        var result = deploy(ctx, "dc");

        assertThat(result.isSuccess()).as(() -> "deploy must succeed: " + result).isTrue();
        assertThat(startCommands.get("10.0.0.1")).as("no profile → the tag is the version being bootstrapped")
                  .contains("docker pull ghcr.io/pragmaticalabs/aether-node:" + VERSION + " ")
                  .contains(" ghcr.io/pragmaticalabs/aether-node:" + VERSION)
                  .doesNotContain(":latest");
    }

    /// Review N-1: the config dir is created by its own ssh call BEFORE the scp that lands in it —
    /// the launch line's `mkdir -p` prefix runs too late to help the scp.
    @Test
    void sshSource_createsTheConfigDirBeforeTheScpLandsInIt() {
        var ctx = context(Map.of("dc",
                                 sshSource("dc", List.of("10.0.0.1"), List.of("10.0.0.2"), "default")),
                          Map.of(),
                          List.of(ssh("dc-core-0", "10.0.0.1"), ssh("dc-worker-0", "10.0.0.2")));
        var result = deploy(ctx, "dc");

        assertThat(result.isSuccess()).as(() -> "deploy must succeed: " + result).isTrue();
        assertThat(calls).containsExactly("ssh:10.0.0.1:mkdir -p /opt/aether/config",
                                          "scp:10.0.0.1",
                                          "ssh:10.0.0.1:launch",
                                          "ssh:10.0.0.2:mkdir -p /opt/aether/config",
                                          "scp:10.0.0.2",
                                          "ssh:10.0.0.2:launch");
    }

    /// Review N-2: the "identity allow-list" is `ClusterIdentityEnv.IDENTITY_VARS` — env-var NAMES
    /// forwarded from the operator's host env into the container. A listed name present on the host
    /// reaches the launch line; an unlisted one never does.
    @Test
    void sshSource_forwardsListedIdentityEnvFromTheOperatorHost_andNothingElse() {
        var hostEnv = Map.of("AETHER_API_KEYS", "k1,k2", "NOT_ON_THE_LIST", "leak");
        var ctx = context(Map.of("dc",
                                 sshSource("dc", List.of("10.0.0.1"), List.of(), "default")),
                          Map.of(),
                          List.of(ssh("dc-core-0", "10.0.0.1")));
        var result = deploy(ctx, "dc", hostEnv::get);

        assertThat(result.isSuccess()).as(() -> "deploy must succeed: " + result).isTrue();
        assertThat(startCommands.get("10.0.0.1")).contains("-e AETHER_API_KEYS=\"k1,k2\"")
                                                 .doesNotContain("NOT_ON_THE_LIST");
    }

    @Test
    void sshSource_labelsAndEnvsEachHostWithItsOwnRole() {
        var ctx = context(Map.of("dc",
                                 sshSource("dc", List.of("10.0.0.1"), List.of("10.0.0.2"), "default")),
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
        var ctx = context(Map.of("dc",
                                 sshSource("dc", List.of("10.0.0.1"), List.of(), "default"),
                                 "lab",
                                 sshSource("lab", List.of("10.0.1.1"), List.of(), "default")),
                          Map.of(),
                          List.of(ssh("dc-core-0", "10.0.0.1"), ssh("lab-core-0", "10.0.1.1")));
        var result = deploy(ctx, "dc");

        assertThat(result.isSuccess()).as(() -> "deploy must succeed: " + result).isTrue();
        assertThat(startCommands.keySet()).as("`dc`'s deploy must not touch `lab`'s host").containsExactly("10.0.0.1");
        assertThat(scpTargets).containsExactly("10.0.0.1:/opt/aether/config/aether.toml");
    }

    @Test
    void sshSource_nonContainerRuntime_isRefusedByName_notSilentlyRunAsAContainer() {
        var runtimes = Map.of("jvm",
                              RuntimeProfile.runtimeProfile("jvm", RuntimeType.JVM, Option.empty(), Option.empty()));
        var ctx = context(Map.of("dc",
                                 sshSource("dc", List.of("10.0.0.1"), List.of(), "jvm")),
                          runtimes,
                          List.of(ssh("dc-core-0", "10.0.0.1")));
        var result = deploy(ctx, "dc");

        assertThat(result.isFailure()).as("a declared JVM runtime must not be silently replaced by a container")
                  .isTrue();
        assertThat(result.fold(Cause::message, _ -> "")).contains("jvm").contains("dc-core-0");
        assertThat(startCommands).as("nothing may be launched under the wrong runtime").isEmpty();
    }
}
