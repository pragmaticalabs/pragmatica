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
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.connectionRefused;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.drainAccepted;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.lifecycle;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.notFound;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1868 — every CLI drain wait must end when the server's drain ends. The wait used to be keyed on a state
/// the server never emits, so each of these call sites timed out (120 s) and aborted the operation behind it.
/// Each test drives one site against a scripted endpoint and asserts the site went PAST the wait. The
/// WaveExecutor sites poll the drained node ITSELF, so their script ends in a refused connection (the halt),
/// with a relayed 404 before it that must NOT end the wait; destroy and `drain --wait` have no target address,
/// so after an accepted drain they report "not observable" and make no poll. The `@Timeout` is the red signal: with the old wait the
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

    /// Cluster-endpoint polls: the leader's view drops the node (404).
    private static ScriptedDrainHttp drainsThenDisappears() {
        return new ScriptedDrainHttp(drainAccepted(NODE), lifecycle(NODE, "DRAINING"), notFound(NODE));
    }

    /// Target-node polls: a 404 relayed by the still-live target, then its port refuses (it halted).
    private static ScriptedDrainHttp drainsThenHalts() {
        return new ScriptedDrainHttp(drainAccepted(NODE), lifecycle(NODE, "DRAINING"), notFound(NODE), connectionRefused());
    }

    @Test
    void scaleDownOfSshNodes_proceedsPastTheDrainWait_toTheSshStop() {
        var http = drainsThenHalts();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        var config = sshConfig(UNRESOLVABLE_HOST, "other-host.invalid");
        var plan = DiffPlan.diffPlan(List.of(),
                                     List.of(),
                                     List.of(new DiffAction.ScaleDown(sourceNameOrDefault("dc"), NodeRole.CORE, 2, 1)),
                                     List.of());

        var result = WaveExecutor.execute(plan, config, config);

        assertThat(http.drainPosts()).as("the drain was requested").isEqualTo(1);
        assertThat(http.lifecycleGets()).as("and the wait ran past the relayed 404 to the refusal").isEqualTo(3);
        assertThat(failureText(result)).as("the only thing left to fail is the unreachable ssh host").doesNotContain("did not complete drain");
    }

    @Test
    void rollingRestartOfSshNodes_proceedsPastTheDrainWait_toTheSshStop() {
        var http = drainsThenHalts();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        var config = sshConfig(UNRESOLVABLE_HOST);
        var plan = DiffPlan.diffPlan(List.of(),
                                     List.of(new DiffAction.RuntimeChange(sourceNameOrDefault("dc"), NodeRole.CORE, "a", "b")),
                                     List.of(),
                                     List.of());

        var result = WaveExecutor.execute(plan, config, config);

        assertThat(http.drainPosts()).isEqualTo(1);
        assertThat(http.lifecycleGets()).isEqualTo(3);
        assertThat(failureText(result)).doesNotContain("did not complete drain");
    }

    /// Through the cluster endpoint there is no sound completion signal (the leader's 404 is soft state; a
    /// refusal there is another member's), so after an ACCEPTED drain destroy reports "accepted, not observable"
    /// at once instead of polling for a verdict it cannot reach. The stale 404 that would have ended the old wait
    /// (and the refusal that would have ended the previous one) must change nothing: no lifecycle poll is made.
    @Test
    void destroyDrain_afterAnAcceptedDrain_reportsNotObservable_withoutPolling() {
        var http = drainsThenDisappears();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        var results = new ClusterDestroyCommand().drainAllNodes(List.of(NODE));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().success()).as("an unobserved drain must not be reported as a completed one").isFalse();
        assertThat(results.getFirst().reason()).contains("not observable");
        assertThat(http.drainPosts()).isEqualTo(1);
        assertThat(http.lifecycleGets()).as("no soft-state verdict is sought").isZero();
    }

    @Test
    void destroyDrain_aRefusedDrain_isReportedAsRefused_notAsUnobservable() {
        var http = new ScriptedDrainHttp(new ScriptedDrainHttp.Step.Reply(409, "{\"detail\":\"must be READY\"}"), notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        var results = new ClusterDestroyCommand().drainAllNodes(List.of(NODE));

        assertThat(results.getFirst().success()).isFalse();
        assertThat(results.getFirst().reason()).contains("refused with HTTP 409");
    }

    @Test
    void drainCommandWait_afterAnAcceptedDrain_failsTypedNotObservable_withoutPolling() {
        var http = drainsThenDisappears();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");
        var err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

        var exit = new CommandLine(new ClusterDrainCommand()).execute(NODE, "--wait", "--yes");

        assertThat(exit).as("--wait cannot be honoured, so it must not exit 0").isNotEqualTo(ExitCode.SUCCESS);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("cannot be observed");
        assertThat(http.lifecycleGets()).isZero();
    }

    @Test
    void drainCommandWithoutWait_afterAnAcceptedDrain_succeeds() {
        var http = drainsThenDisappears();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        var exit = new CommandLine(new ClusterDrainCommand()).execute(NODE, "--yes");

        assertThat(exit).isEqualTo(ExitCode.SUCCESS);
        assertThat(http.lifecycleGets()).isZero();
    }

    /// `drainAndDestroyComputeNode`: a CLOUD source with no provider configured fails at the destroy
    /// dispatch (`NO_PROVIDER`, before any network), which is reached only if the drain wait returned.
    @Test
    void rollingRestartOfComputeNodes_proceedsPastTheDrainWait_toTheDestroy() {
        var http = drainsThenHalts();
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        var config = config(SourceType.CLOUD, "unused-host");
        var plan = DiffPlan.diffPlan(List.of(),
                                     List.of(new DiffAction.RuntimeChange(sourceNameOrDefault("dc"), NodeRole.CORE, "a", "b")),
                                     List.of(),
                                     List.of());

        var result = WaveExecutor.execute(plan, config, config);

        assertThat(http.drainPosts()).isEqualTo(1);
        assertThat(http.lifecycleGets()).as("the wait ran to the refusal before the destroy was dispatched").isEqualTo(3);
        assertThat(failureText(result)).doesNotContain("did not complete drain");
    }

    /// `drainOldNodes` (replace-before-retire) sits behind provisioning, so it is driven directly.
    @Test
    void drainOldNodes_waitsForTheDrainToCompleteBeforeReturning() {
        var http = drainsThenHalts();
        ClusterHttpClient.HTTP_OPS_REF.set(http);

        var result = WaveExecutor.drainOldNodes(sourceNameOrDefault("dc"),
                                                NodeRole.CORE,
                                                1,
                                                config(SourceType.SSH, UNRESOLVABLE_HOST));

        assertThat(failureText(result)).isEmpty();
        assertThat(http.drainPosts()).isEqualTo(1);
        assertThat(http.lifecycleGets()).as("the old node's wait ran to the refusal").isEqualTo(3);
    }

    private static String failureText(Result<?> result) {
        return result.fold(cause -> cause.message(), _ -> "");
    }

    private static ClusterBootstrapConfig sshConfig(String... hosts) {
        return config(SourceType.SSH, hosts);
    }

    private static ClusterBootstrapConfig config(SourceType type, String... hosts) {
        var role = RoleSubTable.roleSubTable(NodeRole.CORE,
                                             Option.some(hosts.length),
                                             Option.some(List.of(hosts)),
                                             Option.empty(),
                                             Option.empty(),
                                             "default");
        var source = SourceProfile.sourceProfile(sourceNameOrDefault("dc"),
                                                 type,
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
