// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression tests for Bug 19 (corrected by Bug 19a): post-bootstrap `--wait` queries the
/// cluster-deployment-status endpoint to determine readiness. These tests pin the behavior:
///   - Poll route is `CLUSTER_STATUS` (resolves to `/api/cluster/status`).
///   - JSON field consulted is `state` (the `ClusterDeploymentState` enum name on the wire).
///   - Success token is `"CONVERGED"`.
/// (Earlier Bug 19 fix used `/api/status` + `clusterPhase` + `NORMAL`; that endpoint exposes
/// per-node uptime and a node summary but no cluster-deployment phase, so the poll never
/// converged in production. Empirical verification on Hetzner: `/api/cluster/status` returns
/// `{"state":"CONVERGED",...}` once the cluster reaches steady state.)
class ClusterBootstrapCommandPollTest {

    private static PrintStream silentStream() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Test
    void waitForClusterPhase_succeeds_whenStateReturnsConverged() {
        var routeSeen = new AtomicReference<ManagementRoute>();
        var attempts = new AtomicInteger();
        var fetcher = (Function<ManagementRoute, Result<String>>) route -> {
            routeSeen.set(route);
            var n = attempts.incrementAndGet();
            return Result.success("{\"state\":\"" + (n == 1 ? "PROVISIONING" : "CONVERGED") + "\"}");
        };

        var rc = ClusterBootstrapCommand.waitForClusterPhase(fetcher, 5, 1, silentStream(), silentStream());

        assertAll("converges on CONVERGED via CLUSTER_STATUS",
                  () -> assertEquals(ExitCode.SUCCESS, rc),
                  () -> assertSame(ManagementRoute.CLUSTER_STATUS, routeSeen.get(), "must poll /api/cluster/status, not /api/nodes/status or /api/health"),
                  () -> assertTrue(attempts.get() >= 2, "should have polled at least twice"));
    }

    @Test
    void waitForClusterPhase_timesOut_whenStateStaysProvisioning() {
        var fetcher = (Function<ManagementRoute, Result<String>>) _ ->
                Result.success("{\"state\":\"PROVISIONING\"}");

        var rc = ClusterBootstrapCommand.waitForClusterPhase(fetcher, 0, 1, silentStream(), silentStream());

        assertEquals(ExitCode.TIMEOUT, rc);
    }

    @Test
    void queryClusterPhase_returnsStateField_notStatusField() {
        // Defensive: /api/health returns {"status": "UP"|"DOWN"}; /api/cluster/status returns
        // {"state": <ClusterDeploymentState>}. Verify we read `state`, not `status`.
        var fetcher = (Function<ManagementRoute, Result<String>>) _ ->
                Result.success("{\"status\":\"UP\",\"state\":\"SCALING\"}");

        var phase = ClusterBootstrapCommand.queryClusterPhase(fetcher);

        assertAll("reads state, not status",
                  () -> assertEquals("SCALING", phase),
                  () -> assertNotEquals("UP", phase));
    }

    @Test
    void queryClusterPhase_returnsUnknown_whenFetcherFails() {
        var fetcher = (Function<ManagementRoute, Result<String>>) _ ->
                new TestCause().<String>result();

        assertEquals("UNKNOWN", ClusterBootstrapCommand.queryClusterPhase(fetcher));
    }

    @Test
    void queryClusterPhase_returnsUnknown_whenFieldMissing() {
        var fetcher = (Function<ManagementRoute, Result<String>>) _ ->
                Result.success("{\"otherField\":\"CONVERGED\"}");

        assertEquals("UNKNOWN", ClusterBootstrapCommand.queryClusterPhase(fetcher));
    }

    @Test
    void isReady_acceptsConvergedCaseInsensitive() {
        assertAll(() -> assertTrue(ClusterBootstrapCommand.isReady("CONVERGED")),
                  () -> assertTrue(ClusterBootstrapCommand.isReady("converged")),
                  () -> assertTrue(ClusterBootstrapCommand.isReady("Converged")));
    }

    @Test
    void isReady_rejectsNonConvergedTokens() {
        // Only CONVERGED counts as ready; transient deployment states do not.
        assertAll(() -> assertEquals(false, ClusterBootstrapCommand.isReady("healthy")),
                  () -> assertEquals(false, ClusterBootstrapCommand.isReady("HEALTHY")),
                  () -> assertEquals(false, ClusterBootstrapCommand.isReady("NORMAL")),
                  () -> assertEquals(false, ClusterBootstrapCommand.isReady("PROVISIONING")),
                  () -> assertEquals(false, ClusterBootstrapCommand.isReady("SCALING")),
                  () -> assertEquals(false, ClusterBootstrapCommand.isReady("UNKNOWN")));
    }

    @Test
    void apiKeyOverride_isApplied_whenWaiting() {
        // The override is process-global; capture and restore to avoid cross-test bleed.
        var saved = readApiKeyOverride();
        try {
            ClusterHttpClient.setApiKeyOverride(""); // reset to known empty
            var result = new ClusterBootstrapOrchestrator.BootstrapResult(
                    clusterName("test-cluster").unwrap(), "http://localhost:9090", "k-secret-xyz",
                    List.of(), "AETHER_TEST_CLUSTER_API_KEY");

            invokeApplyApiKeyOverride(result);

            assertEquals("k-secret-xyz", readApiKeyOverride(),
                         "BootstrapResult.apiKey() must be propagated to ClusterHttpClient.API_KEY_OVERRIDE");
        } finally {
            ClusterHttpClient.setApiKeyOverride(saved == null ? "" : saved);
        }
    }

    @Test
    void apiKeyOverride_skipped_whenApiKeyBlank() {
        var saved = readApiKeyOverride();
        try {
            ClusterHttpClient.setApiKeyOverride("preexisting");
            var result = new ClusterBootstrapOrchestrator.BootstrapResult(
                    clusterName("test-cluster").unwrap(), "http://localhost:9090", "  ",
                    List.of(), "AETHER_TEST_CLUSTER_API_KEY");

            invokeApplyApiKeyOverride(result);

            assertEquals("preexisting", readApiKeyOverride(),
                         "blank apiKey must not clobber an existing override");
        } finally {
            ClusterHttpClient.setApiKeyOverride(saved == null ? "" : saved);
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String readApiKeyOverride() {
        return ClusterHttpClient.API_KEY_OVERRIDE.get();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void invokeApplyApiKeyOverride(ClusterBootstrapOrchestrator.BootstrapResult result) {
        try {
            var m = ClusterBootstrapCommand.class.getDeclaredMethod("applyApiKeyOverride",
                                                                    ClusterBootstrapOrchestrator.BootstrapResult.class);
            m.setAccessible(true);
            var _ = m.invoke(null, result);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private record TestCause() implements Cause {
        @Override public String message() {
            return "synthetic test failure";
        }
    }
}
