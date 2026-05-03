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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression tests for Bug 19: post-bootstrap `--wait` polled the wrong cluster-status route
/// (`/api/health`) and matched the wrong success token (`"healthy"`) instead of reading
/// `clusterPhase` from `/api/status`. These tests pin the corrected behavior:
///   - Poll route is `CLUSTER_STATUS` (resolves to `/api/status`).
///   - JSON field consulted is `clusterPhase`.
///   - Success token is `"NORMAL"` (matches `ClusterPhase` enum, NOT a fabricated `"CONVERGED"`).
class ClusterBootstrapCommandPollTest {

    private static PrintStream silentStream() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Test
    void waitForClusterPhase_succeeds_whenStatusReturnsNormalPhase() {
        var routeSeen = new AtomicReference<ManagementRoute>();
        var attempts = new AtomicInteger();
        var fetcher = (Function<ManagementRoute, Result<String>>) route -> {
            routeSeen.set(route);
            var n = attempts.incrementAndGet();
            return Result.success("{\"clusterPhase\":\"" + (n == 1 ? "BOOTING" : "NORMAL") + "\"}");
        };

        var rc = ClusterBootstrapCommand.waitForClusterPhase(fetcher, 5, 1, silentStream(), silentStream());

        assertAll("converges on NORMAL via CLUSTER_STATUS",
                  () -> assertEquals(ExitCode.SUCCESS, rc),
                  () -> assertSame(ManagementRoute.CLUSTER_STATUS, routeSeen.get(), "must poll /api/status, not /api/health"),
                  () -> assertTrue(attempts.get() >= 2, "should have polled at least twice"));
    }

    @Test
    void waitForClusterPhase_timesOut_whenPhaseStaysBooting() {
        var fetcher = (Function<ManagementRoute, Result<String>>) _ ->
                Result.success("{\"clusterPhase\":\"BOOTING\"}");

        var rc = ClusterBootstrapCommand.waitForClusterPhase(fetcher, 0, 1, silentStream(), silentStream());

        assertEquals(ExitCode.TIMEOUT, rc);
    }

    @Test
    void queryClusterPhase_returnsClusterPhaseField_notStatusField() {
        // Defensive: HealthResponse uses field "status" with value "healthy"; StatusResponse uses
        // field "clusterPhase" with enum-name values. Verify we do NOT regress to reading "status".
        var fetcher = (Function<ManagementRoute, Result<String>>) _ ->
                Result.success("{\"status\":\"healthy\",\"clusterPhase\":\"RECOVERING\"}");

        var phase = ClusterBootstrapCommand.queryClusterPhase(fetcher);

        assertAll("reads clusterPhase, not status",
                  () -> assertEquals("RECOVERING", phase),
                  () -> assertNotEquals("healthy", phase));
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
                Result.success("{\"otherField\":\"NORMAL\"}");

        assertEquals("UNKNOWN", ClusterBootstrapCommand.queryClusterPhase(fetcher));
    }

    @Test
    void isReady_acceptsNormalCaseInsensitive() {
        assertAll(() -> assertTrue(ClusterBootstrapCommand.isReady("NORMAL")),
                  () -> assertTrue(ClusterBootstrapCommand.isReady("normal")),
                  () -> assertTrue(ClusterBootstrapCommand.isReady("Normal")));
    }

    @Test
    void isReady_rejectsLegacyHealthyToken() {
        // Old (buggy) implementation matched "healthy". After fix, only NORMAL counts.
        assertAll(() -> assertEquals(false, ClusterBootstrapCommand.isReady("healthy")),
                  () -> assertEquals(false, ClusterBootstrapCommand.isReady("HEALTHY")),
                  () -> assertEquals(false, ClusterBootstrapCommand.isReady("BOOTING")),
                  () -> assertEquals(false, ClusterBootstrapCommand.isReady("RECOVERING")),
                  () -> assertEquals(false, ClusterBootstrapCommand.isReady("UNKNOWN")));
    }

    @Test
    void apiKeyOverride_isApplied_whenWaiting() {
        // The override is process-global; capture and restore to avoid cross-test bleed.
        var saved = readApiKeyOverride();
        try {
            ClusterHttpClient.setApiKeyOverride(""); // reset to known empty
            var result = new ClusterBootstrapOrchestrator.BootstrapResult(
                    "test-cluster", "http://localhost:9090", "k-secret-xyz",
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
                    "test-cluster", "http://localhost:9090", "  ",
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
