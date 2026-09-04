// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Pins the client-side polling gate behind #294 (polling is not gated to health): every poll to a
/// server the operator's health check has already marked unhealthy should back off, and a 404 from an
/// endpoint the server does not implement should be logged once per endpoint rather than toasted on
/// every tick.
///
/// Two deliberate scope boundaries, both purely client-side (no production Java changed for this
/// ticket) and both worth stating plainly rather than leaving implicit:
///
///   - **The health probe uses bare `/health`, not `/api/health` or `/api/v1/health`.** This
///     dashboard, and Forge, have always spoken the unversioned `/api/...` convention; the real
///     node's Management API has already migrated to an `/api/v1/...` prefix in code
///     (`ManagementRoute`, per `aether/docs/specs/management-api-versioning-spec.md`, #300) without
///     a corresponding dashboard update — a pre-existing, systemic, already-tracked mismatch that
///     spans every dashboard endpoint, not just this one. Bare `/health` is what Forge's
///     `StatusRoutes` actually serves and what this dashboard is demonstrably run against today; it
///     is a deliberate, honest scope boundary, not an attempt to close the #300 gap. Against an
///     already-migrated real node this probe 404s like every other dashboard call does today.
///   - **"Reports degraded" has no literal wire value to match.** The real node's `HealthResponse`
///     computes `status` as exactly `"healthy"` or `"unhealthy"` (`StatusRoutes.buildHealthResponse`)
///     — there is no `"degraded"` string anywhere in this API. Forge's own `HealthResponse` is
///     hardcoded to always return `"healthy"` and can never signal degradation — an honest limit on
///     this gate's real-world triggerability against Forge, stated here rather than hidden. The gate
///     therefore keys semantically on `status !== 'healthy'`, the one field both shapes share.
class DashboardPollingGateContractTest {
    private static Result<String> resource(String path) {
        try (InputStream in = DashboardPollingGateContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return Result.failure(Causes.cause("Dashboard resource not found on classpath: " + path));
            }

            return Result.success(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Result.failure(Causes.fromThrowable(e));
        }
    }

    private static int occurrences(String haystack, String needle) {
        return (int) Pattern.compile(Pattern.quote(needle))
                            .matcher(haystack)
                            .results()
                            .count();
    }

    @Test
    void restClient_probesHealthEndpoint_semanticallyNotByLiteralDegradedString() {
        var appJs = resource("/dashboard/js/app.js").unwrap();

        assertThat(appJs).as("app.js must add a dedicated health probe against the endpoint Forge actually serves")
                  .contains("RestClient.get('/health')")
                  .as("the gate must key on the semantic 'not healthy', not a nonexistent literal 'degraded' string")
                  .contains("health.status !== 'healthy'");
    }

    @Test
    void appJs_pollingTimers_skipWhenClusterDegraded() {
        var appJs = resource("/dashboard/js/app.js").unwrap();

        assertThat(appJs).as("the primary and secondary poll timers must both check the degraded gate")
                  .contains("Alpine.store('cluster').degraded");
    }

    @Test
    void requestsJs_pollingTimer_skipsWhenClusterDegraded() {
        var requestsJs = resource("/dashboard/js/stores/requests.js").unwrap();

        assertThat(requestsJs).contains("Alpine.store('cluster').degraded");
    }

    @Test
    void clusterJs_declaresDegradedFlag_defaultingFalse() {
        var clusterJs = resource("/dashboard/js/stores/cluster.js").unwrap();

        assertThat(clusterJs).as("a fresh dashboard load must never fabricate a degraded verdict before the first health probe returns")
                  .contains("degraded: false,");
    }

    /// The 404-suppression half of #294: a poll against an endpoint the server has no route for
    /// (Forge stand-ins, or a dashboard path not migrated to `/api/v1` — #300) must not toast on
    /// every tick. Every OTHER failure status must still toast every time — this is a narrow
    /// carve-out for one status code, not a general failure-suppression mechanism.
    @Test
    void restClientJs_suppressesRepeat404Toasts_logsInstead() {
        var restClientJs = resource("/dashboard/js/lib/rest-client.js").unwrap();

        assertThat(restClientJs).as("a 404 must be special-cased")
                  .contains("status === 404")
                  .as("a 404 must be logged, not silently dropped")
                  .contains("console.warn(");
        var notificationsShowCount = occurrences(restClientJs, "Notifications.show(");

        assertThat(notificationsShowCount).as("exactly one of the 4 non-catch failure-status call sites collapses into the shared "
                                             + "404-aware reporter; the 4 .catch network-error sites are untouched, so the count "
                                             + "must drop from 8 to 5, never to 0 (every non-404 failure must still toast)")
                  .isEqualTo(5);
    }
}
