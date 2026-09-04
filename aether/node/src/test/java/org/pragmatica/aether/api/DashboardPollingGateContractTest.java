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
/// Revised after #846 review: the first cut probed bare `/health` only, which is what Forge serves
/// but NOT what the real node serves — the real node's Management API has migrated to `/api/v1/health`
/// (`ManagementRoute`, per `aether/docs/specs/management-api-versioning-spec.md`, #300) with no bare
/// `/health` route at all. Probing bare-only meant the gate silently never engaged against a real
/// node; it always 404'd, `degraded` stayed at its default, and the health check was inert exactly
/// where it mattered most. The probe now tries `/api/v1/health` FIRST, falling back to bare `/health`
/// for Forge — but a fallback still means both can fail (a node ahead of migration but with a
/// misbehaving proxy, or genuinely down). Two deliberate properties, both worth stating plainly:
///
///   - **A probe failure (both paths unreachable) fails OPEN to healthy, never wedges on
///     `degraded = true`.** Unknown health is not the same claim as degraded health; treating the
///     two as one would let a target that answers neither health path (e.g. a node's proxy dropping
///     both, or a moment during startup) permanently gate off every other poll with no path back,
///     since the very probe meant to detect recovery can never itself succeed. The failure is warned
///     once per session, not re-logged on every 2s tick.
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
    void restClient_probesVersionedHealthFirst_thenBareHealthFallback_semanticallyNotByLiteralDegradedString() {
        var appJs = resource("/dashboard/js/app.js").unwrap();
        var versionedIndex = appJs.indexOf("RestClient.get('/api/v1/health')");
        var bareIndex = appJs.indexOf("RestClient.get('/health')");

        assertThat(versionedIndex).as("the versioned path must be probed FIRST — it's the ONLY health route "
                                     + "the real node serves (#300); probing bare-only never engages the gate "
                                     + "against a real node at all")
                  .isNotNegative();
        assertThat(bareIndex).as("bare '/health' must remain as the fallback for what Forge actually serves")
                  .isGreaterThan(versionedIndex);
        assertThat(appJs).as("the gate must key on the semantic 'not healthy', not a nonexistent literal 'degraded' string")
                  .contains("health.status !== 'healthy'");
    }

    /// Correction after #846 review: a probe failure (both the versioned and bare health paths
    /// unreachable — the case on any node ahead of #300's dashboard migration but behind #294's own
    /// fix) must never set `degraded = true`. That would wedge every other poll behind a health
    /// check that can never succeed. Unknown health fails OPEN to healthy, and the failure is
    /// warned once — not re-toasted or re-logged on every 2s tick.
    @Test
    void checkHealth_bothProbesFail_failsOpen_neverSetsDegradedTrue() {
        var appJs = resource("/dashboard/js/app.js").unwrap();
        var start = appJs.indexOf("async checkHealth() {");
        var end = appJs.indexOf("async pollStatus() {", start);

        assertThat(start).as("checkHealth() must exist in app.js").isNotNegative();
        assertThat(end).as("pollStatus() must follow checkHealth() so its body is boundable").isGreaterThan(start);
        var checkHealthBody = appJs.substring(start, end);

        assertThat(checkHealthBody).as("an unreachable/unanswered probe must fail OPEN to healthy, never wedge on degraded=true")
                  .contains("Alpine.store('cluster').degraded = false;")
                  .as("the fail-open branch must warn, but only once per session — not on every poll tick")
                  .contains("_healthProbeUnreachableWarned");
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
