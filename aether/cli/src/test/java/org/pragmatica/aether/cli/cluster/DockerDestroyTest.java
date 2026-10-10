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
import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

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
    private ByteArrayOutputStream err;
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
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        originalRemover = ClusterDestroyCommand.registryRemover;
        originalLoader = ClusterDestroyCommand.stateLoader;
        ClusterDestroyCommand.registryRemover = (registry, name) -> {
            removerCalls.add(name.value());

            return Result.success(registry);
        };
        ClusterDestroyCommand.stateLoader = name -> Result.success(Option.none());
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
        DockerHostPorts.override = null;
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

        var outcome = new ClusterDestroyCommand().drainAndShutdown(List.of(A, B, C));

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

        new ClusterDestroyCommand().drainAndShutdown(List.of(A, B));

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
}
