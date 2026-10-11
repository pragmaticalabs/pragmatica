// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.connectionRefused;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.drainAccepted;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.notFound;

/// #2089: destroying a docker cluster from the operator's host.
///
/// A docker cluster is reached through its nodes' PUBLISHED host ports, while a node's cluster-transport address is its container name. Two
/// consequences, both seen at run5: (1) the serving node (the one behind the endpoint) could not be identified by host, was drained first,
/// and the endpoint died under every later request ("Connection refused"); (2) the destroy reported "destroyed successfully", exit 0, over
/// "2 of 2 drain operations failed".
@Timeout(value = 40, unit = TimeUnit.SECONDS)
class DockerDestroyTest {
    private static final String A = "aether-dock-node-a";
    private static final String B = "aether-dock-node-b";
    private static final String C = "aether-dock-node-c";
    private static final ClusterName CLUSTER = ClusterName.clusterName("dock").unwrap();

    private HttpOperations originalHttp;
    private String originalEndpoint;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private java.util.function.Function<BootstrapState, Result<org.pragmatica.lang.Unit>> originalResourceCleaner;
    private BiFunction<BootstrapState, ClusterName, Result<org.pragmatica.lang.Unit>> originalSshKeySweeper;
    private ByteArrayOutputStream err;
    private ByteArrayOutputStream out;
    private BiFunction<ClusterRegistry, ClusterName, Result<ClusterRegistry>> originalRemover;
    private Function<ClusterName, Result<Option<BootstrapState>>> originalLoader;
    private final List<String> removerCalls = new ArrayList<>();

    @BeforeEach
    void stub() {
        originalHttp = ClusterHttpClient.HTTP_OPS_REF.get();
        originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
        originalOut = System.out;
        originalErr = System.err;
        err = new ByteArrayOutputStream();
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        originalRemover = ClusterDestroyCommand.registryRemover;
        originalLoader = ClusterDestroyCommand.stateLoader;
        ClusterDestroyCommand.registryRemover = (registry, name) -> {
            removerCalls.add(name.value());

            return Result.success(registry);
        };
        ClusterDestroyCommand.stateLoader = name -> Result.success(Option.none());
        originalResourceCleaner = ClusterDestroyCommand.resourceCleaner;
        originalSshKeySweeper = ClusterDestroyCommand.sshKeySweeper;
        ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();
        ClusterDestroyCommand.sshKeySweeper = (state, name) -> Result.unitResult();
        DockerHostPorts.override = container -> Result.success(switch (container) {
            case A -> 38911;
            case B -> 38913;
            default -> 38915;
        });
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        ClusterHttpClient.HTTP_OPS_REF.set(originalHttp);
        ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
        ClusterDestroyCommand.registryRemover = originalRemover;
        ClusterDestroyCommand.stateLoader = originalLoader;
        ClusterDestroyCommand.resourceCleaner = originalResourceCleaner;
        ClusterDestroyCommand.sshKeySweeper = originalSshKeySweeper;
        DockerHostPorts.override = null;
        DockerHostPorts.nameOverride = null;
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static ClusterRegistry registry() {
        return ClusterRegistry.clusterRegistry(Path.of("unused-in-test.toml"),
                                               Option.some(CLUSTER.value()),
                                               List.of(new ClusterRegistry.ClusterEntry(CLUSTER.value(), "http://127.0.0.1:38911", Option.none())));
    }

    /// The endpoint is node a's PUBLISHED port; every node's transport address is its container name, so no host can match. The serving node
    /// is identified by the published port and drained LAST, completing on its own address refusing.
    @Test
    void destroy_dockerEndpoint_identifiesTheServingNodeByItsPublishedPort_andDrainsItLast() {
        var http = new ScriptedDrainHttp(drainAccepted(A), notFound(B), notFound(C), connectionRefused())
            .withTransportAddress(A, A + ":6000")
            .withTransportAddress(B, B + ":6000")
            .withTransportAddress(C, C + ":6000");
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:38911");

        var outcome = new ClusterDestroyCommand().drainAndShutdown(List.of(A, B, C), true);

        assertThat(http.drainOrder()).as("a serves the requests (its port is the endpoint's), so it is drained last").containsExactly(B, C, A);
        assertThat(outcome.drains()).allMatch(ClusterDestroyCommand.NodeResult::success);
        assertThat(stderr()).doesNotContain("could not be identified");
    }

    @Test
    void destroy_loopbackEndpointNoNodePublishesItsPort_staysUnidentified_andSaysSo() {
        var http = new ScriptedDrainHttp(drainAccepted(A), notFound(A), notFound(B))
            .withTransportAddress(A, A + ":6000")
            .withTransportAddress(B, B + ":6000");
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:49999");

        new ClusterDestroyCommand().drainAndShutdown(List.of(A, B), true);

        assertThat(http.drainOrder()).as("enumerated order, never a guess").containsExactly(A, B);
        assertThat(stderr()).contains("could not be identified");
    }

    @Test
    void destroy_nonLoopbackEndpoint_neverAsksDockerForPorts() {
        DockerHostPorts.override = container -> {
            throw new AssertionError("a remote endpoint must not consult the local docker daemon: " + container);
        };
        var http = new ScriptedDrainHttp(drainAccepted(A), notFound(A), notFound(B))
            .withTransportAddress(A, "172.31.0.10:5000")
            .withTransportAddress(B, "172.31.0.11:5000");
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://203.0.113.7:8080");

        new ClusterDestroyCommand().drainAndShutdown(List.of(A, B));

        assertThat(http.drainOrder()).containsExactly(A, B);
    }

    /// A failed drain is not a destroyed cluster: nothing is deleted, the registry entry stays, the exit is non-zero, and the way through is named.
    @Test
    void destroy_aFailedDrain_refusesBeforeDeleting_andNamesForceUndrained() {
        ClusterHttpClient.HTTP_OPS_REF.set(new ScriptedDrainHttp(connectionRefused(), notFound(A)));
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:49999");

        var result = new ClusterDestroyCommand().destroyEnumerated(registry(), CLUSTER, List.of(A));

        assertThat(result.<Integer> fold(cause -> -1, code -> code)).isEqualTo(ExitCode.ERROR);
        assertThat(removerCalls).as("nothing was deleted and the registry entry is kept").isEmpty();
        assertThat(stderr()).contains("REFUSING").contains("NOTHING has been deleted").contains("--force-undrained").contains(A);
    }

    @Test
    void destroy_aFailedDrain_withForceUndrained_proceeds_andNamesTheUndrainedNodes() {
        ClusterHttpClient.HTTP_OPS_REF.set(new ScriptedDrainHttp(connectionRefused(), notFound(A)));
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:49999");
        ClusterDestroyCommand.stateLoader = name -> Result.success(Option.some(BootstrapState.initialState(CLUSTER, "h", "now")));
        var command = new ClusterDestroyCommand();

        command.setForceUndrained(true);
        var result = command.destroyEnumerated(registry(), CLUSTER, List.of(A));

        assertThat(result.<Integer> fold(cause -> -1, code -> code)).isEqualTo(ExitCode.SUCCESS);
        assertThat(removerCalls).as("the teardown went ahead, as requested").containsExactly(CLUSTER.value());
        assertThat(stderr()).contains("NOT DRAINED").contains(A);
    }

    @Test
    void destroy_cleanDrain_withoutForce_stillSucceeds() {
        ClusterHttpClient.HTTP_OPS_REF.set(new ScriptedDrainHttp(drainAccepted(A), notFound(A)));
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:49999");
        ClusterDestroyCommand.stateLoader = name -> Result.success(Option.some(BootstrapState.initialState(CLUSTER, "h", "now")));

        var result = new ClusterDestroyCommand().destroyEnumerated(registry(), CLUSTER, List.of(A));

        assertThat(result.<Integer> fold(cause -> -1, code -> code)).isEqualTo(ExitCode.SUCCESS);
        assertThat(removerCalls).containsExactly(CLUSTER.value());
        assertThat(stderr()).doesNotContain("REFUSING").doesNotContain("NOT DRAINED");
    }

    /// The sibling fallback for a docker cluster: the ledger holds published `127.0.0.1:<port>` addresses, each already carrying its own port.
    @Test
    void siblingEndpoints_forPublishedPortAddresses_useEachAddressAsIs() {
        var state = BootstrapState.initialState(CLUSTER, "h", "now")
                                  .withCollectedAddresses(List.of("127.0.0.1:38911", "127.0.0.1:38913", "127.0.0.1:38915"));

        var siblings = ClusterDestroyCommand.siblingEndpoints(Result.success("http://127.0.0.1:38911"), Option.some(state));

        assertThat(siblings).containsExactly("http://127.0.0.1:38913", "http://127.0.0.1:38915");
    }

    /// A loopback endpoint alone proves nothing: a Forge or local cluster is on loopback and is not docker, and a hung daemon would add its
    /// timeout per node. `docker port` runs only for a cluster the ledger records as docker.
    @Test
    void destroy_loopbackNonDockerCluster_invokesNoDockerCommand() {
        DockerHostPorts.override = container -> {
            throw new AssertionError("a non-docker cluster must not run docker port: " + container);
        };
        var http = new ScriptedDrainHttp(drainAccepted(A), notFound(A), notFound(B))
            .withTransportAddress(A, "localnode-a:6000")
            .withTransportAddress(B, "localnode-b:6000");
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:38911");

        new ClusterDestroyCommand().drainAndShutdown(List.of(A, B), false);

        assertThat(http.drainOrder()).containsExactly(A, B);
    }

    @Test
    void isDockerCluster_isTrueOnlyWhenTheLedgerRecordsDockerContainers() {
        var empty = BootstrapState.initialState(CLUSTER, "h", "now");
        var docker = empty.withResource(CreatedResource.DockerContainer.dockerContainer("abc", "primary"));

        assertThat(ClusterDestroyCommand.isDockerCluster(Option.some(docker))).isTrue();
        assertThat(ClusterDestroyCommand.isDockerCluster(Option.some(empty))).isFalse();
        assertThat(ClusterDestroyCommand.isDockerCluster(Option.none())).isFalse();
    }

    @Test
    void destroyEnumerated_readsTheDockerNatureFromTheLedger() {
        var docker = BootstrapState.initialState(CLUSTER, "h", "now")
                                   .withResource(CreatedResource.DockerContainer.dockerContainer(A, "primary"));
        ClusterDestroyCommand.stateLoader = name -> Result.success(Option.some(docker));
        ClusterHttpClient.HTTP_OPS_REF.set(new ScriptedDrainHttp(drainAccepted(A), notFound(A), notFound(B), connectionRefused())
                                               .withTransportAddress(A, A + ":6000")
                                               .withTransportAddress(B, B + ":6000"));
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:38911");

        new ClusterDestroyCommand().destroyEnumerated(registry(), CLUSTER, List.of(A, B));

        assertThat(((ScriptedDrainHttp) ClusterHttpClient.HTTP_OPS_REF.get()).drainOrder()).as("a is the serving node: drained last").containsExactly(B, A);
    }

    /// The persisted form is produced by one formatter (`NodeAddress#persisted`, which brackets an IPv6 host), so it is parsed back by one rule.
    @Test
    void nodeAddress_persistedForm_roundTripsIpv6_andIpv4_andBareHosts() {
        var v6 = NodeAddress.nodeAddress("n", "::1", Option.none(), Option.some(38915));

        assertThat(v6.persisted()).isEqualTo("[::1]:38915");
        assertThat(NodeAddress.fromPersisted("n", "[::1]:38915")).isEqualTo(v6);
        assertThat(NodeAddress.fromPersisted("n", "::1").managementPort().isPresent()).as("bare v6 carries no port").isFalse();
        assertThat(NodeAddress.fromPersisted("n", "::1").publicIp()).isEqualTo("::1");
        assertThat(NodeAddress.fromPersisted("n", "2001:db8::1").managementPort().isPresent()).as("trailing group is not a port").isFalse();
        assertThat(NodeAddress.fromPersisted("n", "127.0.0.1:38911").managementPort().or(0)).isEqualTo(38911);
        assertThat(v6.managementHostPort(8080)).isEqualTo("[::1]:38915");
    }

    @Test
    void siblingEndpoints_ipv6Addresses_areBracketed() {
        var state = BootstrapState.initialState(CLUSTER, "h", "now")
                                  .withCollectedAddresses(List.of("[::1]:38915", "2001:db8::1"));

        var siblings = ClusterDestroyCommand.siblingEndpoints(Result.success("http://127.0.0.1:38911"), Option.some(state));

        assertThat(siblings).containsExactly("http://[::1]:38915", "http://[2001:db8::1]:38911");
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private Result<Integer> destroyWith(ScriptedDrainHttp http, List<String> nodes) {
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:49999");
        ClusterDestroyCommand.stateLoader = name -> Result.success(Option.some(BootstrapState.initialState(CLUSTER, "h", "now")));

        return new ClusterDestroyCommand().destroyEnumerated(registry(), CLUSTER, nodes);
    }

    private static int code(Result<Integer> result) {
        return result.<Integer> fold(cause -> -1, value -> value);
    }

    /// A healthy 3-core destroy: the disruption budget (voters/2+1) admits the first core and refuses the second, forever. That refusal is
    /// EXPECTED, not a failure: exit 0, counted and named as held for quorum, no refusal, and the held node is not sent a shutdown.
    @Test
    void destroy_aCoreRefusedByTheDisruptionBudget_isHeldForQuorum_andDoesNotRefuse() {
        var http = new ScriptedDrainHttp(drainAccepted(A), notFound(A)).withDrainSequence(drainAccepted(A), ScriptedDrainHttp.budgetRefused(B));

        var result = destroyWith(http, List.of(A, B));

        assertThat(code(result)).isEqualTo(ExitCode.SUCCESS);
        assertThat(removerCalls).as("the teardown went ahead").containsExactly(CLUSTER.value());
        assertThat(stderr()).doesNotContain("REFUSING");
        assertThat(stdout()).contains("Outcome: drained 1, held for quorum 1 (" + B + "), failed 0");
        assertThat(http.requests()).as("a held core is not sent a shutdown").noneMatch(r -> r.equals("POST /api/v1/nodes/shutdown/" + B));
    }

    @Test
    void destroy_aNonBudgetConflict_stillRefuses() {
        var http = new ScriptedDrainHttp(drainAccepted(A), notFound(A)).withDrainSequence(drainAccepted(A), ScriptedDrainHttp.otherConflict(B));

        assertThat(code(destroyWith(http, List.of(A, B)))).isEqualTo(ExitCode.ERROR);
        assertThat(removerCalls).isEmpty();
        assertThat(stderr()).contains("REFUSING").contains(B);
    }

    @Test
    void destroy_aServerError_stillRefuses() {
        var http = new ScriptedDrainHttp(drainAccepted(A), notFound(A)).withDrainSequence(drainAccepted(A), ScriptedDrainHttp.serverError());

        assertThat(code(destroyWith(http, List.of(A, B)))).isEqualTo(ExitCode.ERROR);
        assertThat(removerCalls).isEmpty();
    }

    /// The exact refusal text: no stray separator after the failure list.
    @Test
    void refusalMessage_hasNoStraySeparator() {
        var http = new ScriptedDrainHttp(connectionRefused(), notFound(A));

        destroyWith(http, List.of(A));

        assertThat(stderr()).contains("REFUSING to delete the VMs of 'dock': 1 of 1 drain operations failed (" + A
                                      + ": error: Connection failed: Connection refused). NOTHING has been deleted and the registry entry is kept.");
    }

    private static BootstrapState threeNodeLedger() {
        return BootstrapState.initialState(CLUSTER, "h", "now")
                             .withResource(CreatedResource.ProvisionedVm.provisionedVm("docker", "id-a", "primary", "core"))
                             .withResource(CreatedResource.ProvisionedVm.provisionedVm("docker", "id-b", "primary", "core"))
                             .withResource(CreatedResource.ProvisionedVm.provisionedVm("docker", "id-c", "primary", "core"));
    }

    /// The lifecycle list is the leader's view of its peers and can omit a node (run8 listed 2 of 3). That node is deleted with the VMs without
    /// a drain, so it is named and counted, never silently omitted; the counts sum to the ledger.
    @Test
    void destroy_aLedgerNodeMissingFromTheLifecycleList_isNamed_andTheCountsSumToTheLedger() {
        DockerHostPorts.nameOverride = id -> Result.success(switch (id) {
            case "id-a" -> A;
            case "id-b" -> B;
            default -> C;
        });
        var ledger = threeNodeLedger();
        ClusterHttpClient.HTTP_OPS_REF.set(new ScriptedDrainHttp(drainAccepted(A), notFound(A)));
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:49999");
        ClusterDestroyCommand.stateLoader = name -> Result.success(Option.some(ledger));

        var result = new ClusterDestroyCommand().destroyEnumerated(registry(), CLUSTER, List.of(A, B));

        assertThat(code(result)).as("an unlisted node alone does not refuse").isEqualTo(ExitCode.SUCCESS);
        assertThat(stdout()).contains("Not listed by the cluster: " + C + " — removed without drain.");
        assertThat(stdout()).contains("drained 2, held for quorum 0, failed 0, not listed by the cluster 1; accounted 3 of 3 recorded in the bootstrap ledger");
    }

    @Test
    void destroy_anUnlistedNodeWhoseNameCannotBeRecovered_isStillCounted() {
        DockerHostPorts.nameOverride = id -> Causes.cause("docker unavailable").result();
        var ledger = threeNodeLedger();
        ClusterHttpClient.HTTP_OPS_REF.set(new ScriptedDrainHttp(drainAccepted(A), notFound(A)));
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:49999");
        ClusterDestroyCommand.stateLoader = name -> Result.success(Option.some(ledger));

        new ClusterDestroyCommand().destroyEnumerated(registry(), CLUSTER, List.of(A, B));

        assertThat(stdout()).contains("1 node(s) recorded in the bootstrap ledger were not listed by the cluster");
        assertThat(stdout()).contains("accounted 3 of 3");
    }

    /// E3: a NON-docker cluster has a recorded ledger too (its VMs); that ledger must not open the docker gate on a loopback endpoint.
    @Test
    void destroy_nonDockerClusterWithARecordedLedger_keepsTheDockerGateClosed() {
        DockerHostPorts.override = container -> {
            throw new AssertionError("a non-docker cluster must not run docker port: " + container);
        };
        var cloud = BootstrapState.initialState(CLUSTER, "h", "now")
                                  .withResource(CreatedResource.ProvisionedVm.provisionedVm("hetzner", "vm-1", "primary", "core"));
        ClusterDestroyCommand.stateLoader = name -> Result.success(Option.some(cloud));
        ClusterHttpClient.HTTP_OPS_REF.set(new ScriptedDrainHttp(drainAccepted(A), notFound(A), notFound(B))
                                               .withTransportAddress(A, "cloud-a:6000")
                                               .withTransportAddress(B, "cloud-b:6000"));
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:38911");

        new ClusterDestroyCommand().destroyEnumerated(registry(), CLUSTER, List.of(A, B));

        assertThat(ClusterDestroyCommand.isDockerCluster(Option.some(cloud))).isFalse();
    }

    /// E9: an IPv6 endpoint host with NO port (`http://[::1]`): the sibling address is bracketed too, and no port is invented.
    @Test
    void siblingEndpoints_ipv6EndpointWithoutAPort_bracketsTheSibling_andInventsNoPort() {
        var state = BootstrapState.initialState(CLUSTER, "h", "now")
                                  .withCollectedAddresses(List.of("2001:db8::2"));

        var siblings = ClusterDestroyCommand.siblingEndpoints(Result.success("http://[::1]"), Option.some(state));

        assertThat(siblings).containsExactly("http://[2001:db8::2]");
    }

    /// G13: a SHUTDOWN answered with the disruption-budget 409 is held for quorum, not a failure; any other refusal of a shutdown is a failure.
    /// (The drain is refused first by a non-budget conflict so the node is still there to be sent a shutdown.)
    @Test
    void shutdown_answeredWithTheBudget409_isHeld_andANonBudgetRefusal_isFailed() {
        var budget = new ScriptedDrainHttp(ScriptedDrainHttp.otherConflict(A), notFound(A))
            .withShutdownResponse(ScriptedDrainHttp.budgetRefused(A));
        ClusterHttpClient.HTTP_OPS_REF.set(budget);
        ClusterHttpClient.setEndpointOverride("http://127.0.0.1:49999");

        var held = new ClusterDestroyCommand().drainAndShutdown(List.of(A), false).shutdowns().getFirst();

        assertThat(held.heldForQuorum()).as("budget 409 on shutdown").isTrue();
        assertThat(held.failure()).isFalse();

        var other = new ScriptedDrainHttp(ScriptedDrainHttp.otherConflict(A), notFound(A))
            .withShutdownResponse(ScriptedDrainHttp.otherConflict(A));
        ClusterHttpClient.HTTP_OPS_REF.set(other);

        var failed = new ClusterDestroyCommand().drainAndShutdown(List.of(A), false).shutdowns().getFirst();

        assertThat(failed.failure()).as("non-budget refusal on shutdown").isTrue();
        assertThat(failed.heldForQuorum()).isFalse();
    }

    /// E12b: every drain succeeded and one SHUTDOWN failed (non-budget): that is a failure and the destroy must refuse. A held shutdown is not.
    @Test
    void hasFailures_seesAFailedShutdown_evenWhenEveryDrainSucceeded() {
        var drained = List.of(ClusterDestroyCommand.NodeResult.succeeded(A), ClusterDestroyCommand.NodeResult.succeeded(B));

        assertThat(ClusterDestroyCommand.hasFailures(drained, List.of(ClusterDestroyCommand.NodeResult.failed(A, "refused with HTTP 500")))).isTrue();
        assertThat(ClusterDestroyCommand.hasFailures(drained, List.of(ClusterDestroyCommand.NodeResult.held(A, "held")))).isFalse();
        assertThat(ClusterDestroyCommand.hasFailures(drained, List.of(ClusterDestroyCommand.NodeResult.succeeded(A)))).isFalse();
    }
}
