// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.http.HttpOperations;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.drainAccepted;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.notFound;

/// #1720: the operator drain and shutdown routes refuse a request that would take a hosted slice below its
/// `minAvailable` floor, unless `force=true`. `cluster destroy` takes every slice below its floor by definition, so
/// it must pass `force` on BOTH its drain and its shutdown requests, or destroy would be refused on any cluster with a
/// blueprint deployed. `cluster drain` sends it only when the operator asks (`--override-floor`).
@Timeout(value = 40, unit = java.util.concurrent.TimeUnit.SECONDS)
class OperatorDrainForceFlagTest {
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

    @Test
    void destroy_passesForceOnTheDrainRequest() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        new ClusterDestroyCommand().drainAndShutdown(List.of(NODE));

        assertThat(http.requestsWithQuery()).contains("POST /api/v1/nodes/drain/" + NODE + "?force=true");
        assertThat(http.requestsWithQuery()).noneMatch(r -> r.startsWith("POST /api/v1/nodes/drain/" + NODE) && !r.endsWith("?force=true"));
    }

    @Test
    void destroy_passesForceOnTheShutdownRequest() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        new ClusterDestroyCommand().shutdownAllNodes(List.of(NODE));

        assertThat(http.requestsWithQuery()).containsExactly("POST /api/v1/nodes/shutdown/" + NODE + "?force=true");
    }

    @Test
    void clusterDrain_sendsNoForce_unlessTheOperatorOverridesTheFloor() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        new CommandLine(new ClusterDrainCommand()).execute(NODE, "--yes");

        assertThat(http.requestsWithQuery()).containsExactly("POST /api/v1/nodes/drain/" + NODE);
    }

    @Test
    void clusterDrain_overrideFloor_sendsForce() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        ClusterHttpClient.setEndpointOverride("http://10.255.255.1:8080");

        new CommandLine(new ClusterDrainCommand()).execute(NODE, "--yes", "--override-floor");

        assertThat(http.requestsWithQuery()).containsExactly("POST /api/v1/nodes/drain/" + NODE + "?force=true");
    }
}
