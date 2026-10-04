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
/// with a relayed 404 before it that must NOT end the wait. Destroy and `drain --wait` poll through the cluster
/// endpoint (a member other than the target), where the lifecycle GET's 404 is membership's committed departure;
/// the serving node itself is drained last and completes on its own address refusing. The `@Timeout` is the red
/// signal: with the old wait the site blocks for its whole 120 s budget and the test is killed long before.
///
/// Sites reached here: all four `WaveExecutor` sites (scale-down, SSH rolling restart, compute rolling restart,
/// `drainOldNodes`), `ClusterDestroyCommand`, `ClusterDrainCommand --wait`.
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

    /// #1720: in a rolling restart the second drain is commonly asked for before the first node's displaced instance
    /// is ACTIVE again, and the floor refuses it. The refusal is transient, so the wave waits it out rather than
    /// ending the upgrade on the first 409.
    @Test
    void rollingRestart_aTransientSliceFloorRefusal_isWaitedOut() {
        var http = drainsThenHalts().withDrainSequence(ScriptedDrainHttp.sliceFloorRefused(NODE), drainAccepted(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        var config = sshConfig(UNRESOLVABLE_HOST);
        var plan = DiffPlan.diffPlan(List.of(),
                                     List.of(new DiffAction.RuntimeChange(sourceNameOrDefault("dc"), NodeRole.CORE, "a", "b")),
                                     List.of(),
                                     List.of());

        var result = WaveExecutor.execute(plan, config, config);

        assertThat(http.drainPosts()).as("refused once, then re-requested and admitted").isEqualTo(2);
        assertThat(http.lifecycleGets()).as("and the drain wait then ran to the halt").isEqualTo(3);
        assertThat(failureText(result)).doesNotContain("minAvailable").doesNotContain("did not complete drain");
    }

    /// A refusal that outlasts the bound still ends the drain, naming the slice.
    @Test
    void sliceFloorRefusal_thatPersists_endsAtTheBound_namingTheSlice() {
        var http = drainsThenHalts().withDrainSequence(ScriptedDrainHttp.sliceFloorRefused(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);

        var result = ClusterHttpClient.drainNodeWhenFloorAllows("http", UNRESOLVABLE_HOST, 5150, NODE, 200, 20);

        assertThat(failureText(result)).contains("minAvailable");
        assertThat(http.drainPosts()).as("it was re-requested while waiting").isGreaterThan(1);
    }

    /// Only the floor refusal is waited out: any other failure returns at once.
    @Test
    void anyOtherDrainRefusal_isNotRetried() {
        var http = new ScriptedDrainHttp(new ScriptedDrainHttp.Step.Reply(409, "{\"status\":409,\"detail\":\"disruption budget exhausted\"}"),
                                         lifecycle(NODE, "ON_DUTY"));
        ClusterHttpClient.HTTP_OPS_REF.set(http);

        var result = ClusterHttpClient.drainNodeWhenFloorAllows("http", UNRESOLVABLE_HOST, 5150, NODE, 200, 20);

        assertThat(result.isFailure()).isTrue();
        assertThat(http.drainPosts()).isEqualTo(1);
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

    /// `unknown` (503) is the answer for a LIVE member missing from the soft readiness view (a transient evict, a new
    /// leader): it must not end the wait. Only the committed departure (404) does.
    @Test
    void drainCommandWait_completesOnlyOnTheCommittedDeparture_notOnAnUnknown503() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE),
                                         lifecycle(NODE, "DRAINING"),
                                         new ScriptedDrainHttp.Step.Reply(503, "{}"),
                                         notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        var exit = new CommandLine(new ClusterDrainCommand()).execute(NODE, "--wait", "--yes", "--timeout", "30");

        assertThat(exit).isEqualTo(ExitCode.SUCCESS);
        assertThat(http.lifecycleGets()).as("polled past DRAINING and the 503, to the 404").isEqualTo(3);
    }

    @Test
    void drainCommandWait_aRefusedConnectionOnTheClusterEndpoint_isNotDeparture() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), connectionRefused(), notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        var exit = new CommandLine(new ClusterDrainCommand()).execute(NODE, "--wait", "--yes", "--timeout", "30");

        assertThat(exit).isEqualTo(ExitCode.SUCCESS);
        assertThat(http.lifecycleGets()).as("a refusal from some other member says nothing about the target").isEqualTo(2);
    }

    /// B: every drain and shutdown is relayed through the current endpoint, so draining THAT node first strands all
    /// later requests. The serving node is found by matching the endpoint host to a node's transport host, and goes
    /// last; a non-serving node completes on the committed departure (404), the serving node on its own address
    /// refusing; nodes whose departure was observed get no shutdown request.
    @Test
    void destroy_drainsTheServingNodeLast_andSendsNoShutdownToDepartedNodes() {
        var http = new ScriptedDrainHttp(drainAccepted("core-0"), notFound("core-1"), notFound("core-2"), connectionRefused())
            .withTransportAddress("core-0", "10.255.255.1:5000")
            .withTransportAddress("core-1", "10.255.255.2:5000")
            .withTransportAddress("core-2", "10.255.255.3:5000");
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        var outcome = new ClusterDestroyCommand().drainAndShutdown(List.of("core-0", "core-1", "core-2"));

        assertThat(http.drainOrder()).as("core-0 serves the requests, so it is drained last").containsExactly("core-1", "core-2", "core-0");
        assertThat(outcome.drains()).allMatch(ClusterDestroyCommand.NodeResult::success);
        assertThat(http.requests()).as("a departed node is not sent a shutdown through a possibly-halted endpoint")
                                   .noneMatch(r -> r.startsWith("POST /api/v1/nodes/shutdown/"));
    }

    /// The usual AWS case: the transport address is a private IP, the endpoint a public one, so nothing matches. The
    /// fallback (enumerated order) is kept, but never silently.
    @Test
    void destroy_noHostMatch_warnsLoudly_andKeepsTheEnumeratedOrder() {
        var http = new ScriptedDrainHttp(drainAccepted("core-0"), notFound("core-0"), notFound("core-1"))
            .withTransportAddress("core-0", "172.31.0.10:5000")
            .withTransportAddress("core-1", "172.31.0.11:5000");
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://203.0.113.7:8080");
        var err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

        new ClusterDestroyCommand().drainAndShutdown(List.of("core-0", "core-1"));

        assertThat(err.toString(StandardCharsets.UTF_8)).contains("WARNING").contains("could not be identified").contains("deletes the VMs");
        assertThat(http.drainOrder()).containsExactly("core-0", "core-1");
    }

    @Test
    void destroy_aMatchedServingNode_emitsNoUnidentifiedWarning() {
        var http = new ScriptedDrainHttp(drainAccepted("core-0"), notFound("core-1"), connectionRefused())
            .withTransportAddress("core-0", "10.255.255.1:5000")
            .withTransportAddress("core-1", "10.255.255.2:5000");
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");
        var err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

        new ClusterDestroyCommand().drainAndShutdown(List.of("core-0", "core-1"));

        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("could not be identified");
    }

    @Test
    void destroy_servingNodeLast_leavesTheOrderAloneWhenTheServingNodeIsUnknown() {
        assertThat(ClusterDestroyCommand.servingNodeLast(List.of("a", "b", "c"), org.pragmatica.lang.Option.none()))
            .containsExactly("a", "b", "c");
        assertThat(ClusterDestroyCommand.servingNodeLast(List.of("a", "b", "c"), org.pragmatica.lang.Option.some("a")))
            .containsExactly("b", "c", "a");
    }

    @Test
    void destroyDrain_aRefusedDrain_isReportedAsRefused() {
        var http = new ScriptedDrainHttp(new ScriptedDrainHttp.Step.Reply(409, "{\"detail\":\"must be READY\"}"), notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        var results = new ClusterDestroyCommand().drainAllNodes(List.of(NODE));

        assertThat(results.getFirst().success()).isFalse();
        assertThat(results.getFirst().reason()).contains("refused with HTTP 409");
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
