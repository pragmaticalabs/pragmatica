// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.config.cluster.AutoHealSpec;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.PortMapping;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.TimeoutsConfig;
import org.pragmatica.aether.config.cluster.TlsDeploymentConfig;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.drainAccepted;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.lifecycle;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.notFound;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1868 — every CLI drain wait must end when the server's drain ends. The wait used to be keyed on a state
/// the server never emits, so each of these call sites timed out (120 s) and aborted the operation behind it.
/// Each test drives one site against a scripted endpoint whose lifecycle entry disappears (404) after the
/// drain, and asserts the site went PAST the wait. The `@Timeout` is the red signal: with the old wait the
/// site blocks for its whole 120 s budget and the test is killed long before.
///
/// Sites reached here: `WaveExecutor.drainAndStopSshNodes` (scale-down) and `WaveExecutor.drainSshNode`
/// (rolling restart), `ClusterDestroyCommand.drainSingleNode`, `ClusterDrainCommand --wait`.
/// NOT reached by a test: `drainAndDestroyComputeNode` and `drainOldNodes`, which sit behind cloud
/// provisioning; they share `ClusterHttpClient.drainNodeAndAwait`, the only drain-then-wait entry point.
@Timeout(value = 40, unit = TimeUnit.SECONDS)
class DrainWaitCallSitesTest {
    private static final String UNRESOLVABLE_HOST = "drain-test.invalid";
    private static final String NODE = "core-2";

    private HttpOperations originalHttp;
    private String originalEndpoint;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void stubStreamsAndHttp() {
        originalHttp = ClusterHttpClient.HTTP_OPS_REF.get();
        originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        ClusterHttpClient.HTTP_OPS_REF.set(originalHttp);
        ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
    }

    private static ScriptedDrainHttp drainsThenDisappears() {
        return new ScriptedDrainHttp(drainAccepted(NODE), lifecycle(NODE, "DRAINING"), notFound(NODE));
    }

    @Test
    void scaleDownOfSshNodes_proceedsPastTheDrainWait_toTheSshStop() {
        var http = drainsThenDisappears();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        var config = sshConfig(UNRESOLVABLE_HOST, "other-host.invalid");
        var plan = DiffPlan.diffPlan(List.of(),
                                     List.of(),
                                     List.of(new DiffAction.ScaleDown(sourceNameOrDefault("dc"), NodeRole.CORE, 2, 1)),
                                     List.of());

        var result = WaveExecutor.execute(plan, config, config);

        assertThat(http.drainPosts()).as("the drain was requested").isEqualTo(1);
        assertThat(http.lifecycleGets()).as("and the wait ran to the 404").isEqualTo(2);
        assertThat(failureText(result)).as("the only thing left to fail is the unreachable ssh host").doesNotContain("did not complete drain");
    }

    @Test
    void rollingRestartOfSshNodes_proceedsPastTheDrainWait_toTheSshStop() {
        var http = drainsThenDisappears();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        var config = sshConfig(UNRESOLVABLE_HOST);
        var plan = DiffPlan.diffPlan(List.of(),
                                     List.of(new DiffAction.RuntimeChange(sourceNameOrDefault("dc"), NodeRole.CORE, "a", "b")),
                                     List.of(),
                                     List.of());

        var result = WaveExecutor.execute(plan, config, config);

        assertThat(http.drainPosts()).isEqualTo(1);
        assertThat(http.lifecycleGets()).isEqualTo(2);
        assertThat(failureText(result)).doesNotContain("did not complete drain");
    }

    @Test
    void destroyDrain_completesWhenTheLifecycleEntryDisappears() {
        var http = drainsThenDisappears();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        var results = new ClusterDestroyCommand().drainAllNodes(List.of(NODE));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().success()).as("a drained node must not be reported as timed out").isTrue();
        assertThat(http.lifecycleGets()).isEqualTo(2);
    }

    @Test
    void drainCommandWait_completesWhenTheLifecycleEntryDisappears() {
        var http = drainsThenDisappears();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        var exit = new CommandLine(new ClusterDrainCommand()).execute(NODE, "--wait", "--yes", "--timeout", "30");

        assertThat(exit).as("drain --wait must exit 0 once the node has gone, not ExitCode.TIMEOUT").isEqualTo(ExitCode.SUCCESS);
        assertThat(http.lifecycleGets()).isEqualTo(2);
    }

    private static String failureText(Result<?> result) {
        return result.fold(cause -> cause.message(), _ -> "");
    }

    private static ClusterBootstrapConfig sshConfig(String... hosts) {
        var role = RoleSubTable.roleSubTable(NodeRole.CORE,
                                             Option.some(hosts.length),
                                             Option.some(List.of(hosts)),
                                             Option.empty(),
                                             Option.empty(),
                                             "default");
        var source = SourceProfile.sourceProfile(sourceNameOrDefault("dc"),
                                                 SourceType.SSH,
                                                 Option.empty(),
                                                 Option.empty(),
                                                 Option.empty(),
                                                 Option.empty(),
                                                 Option.some("aether"),
                                                 Option.some("/nonexistent/key"),
                                                 Option.empty(),
                                                 LoadBalancerMode.NONE,
                                                 List.of(),
                                                 Option.empty(),
                                                 Map.of(),
                                                 Map.of(NodeRole.CORE, role),
                                                 List.of());
        var ops = OperationsConfig.operationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                                    TlsDeploymentConfig.defaultTlsConfig(),
                                                    TimeoutsConfig.timeoutsConfig("3s", "10s", "10s"),
                                                    PortMapping.defaultPortMapping());

        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                             ClusterIdentity.clusterIdentity("prod", "1.0.0").unwrap(),
                                                             CoreTopology.defaultCoreTopology(),
                                                             Map.of("dc", source),
                                                             Map.of(),
                                                             InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                             ops,
                                                             Map.of());
    }
}
