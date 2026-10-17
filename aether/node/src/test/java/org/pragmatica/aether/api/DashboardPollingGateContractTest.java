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
/// Revised after #846 review (round one): the first cut probed bare `/health` only, which is what
/// Forge serves but NOT what the real node serves — the real node's Management API has migrated to
/// `/api/v1/health` (`ManagementRoute`, per `aether/docs/specs/management-api-versioning-spec.md`,
/// #300) with no bare `/health` route at all. Probing bare-only meant the gate silently never engaged
/// against a real node. The probe tries `/api/v1/health` FIRST, falling back to bare `/health` for
/// Forge.
///
/// Corrected again after #846 review (round two, a BLOCKING finding): round one's fail-open collapsed
/// two genuinely different failures into one `degraded = false`. `RestClient.get()` returns an
/// identical `null` for a 404 (server reachable, no route here) and for a fetch-level network
/// exception (server unreachable at all) — so a total outage looked exactly like a harmless missing
/// route, the gate cleared, and every OTHER poller resumed hammering a dead backend every 2-3s with
/// network-error toasts the 404 suppression never covered — #294's own toast storm, reintroduced in
/// the worst case. The gate now tracks three states, computed by the pure `decideHealthState()`
/// (top of `app.js`) from a dedicated `RestClient.probeHealth()` that reports reachability separately
/// from the parsed body:
///
///   - **`healthy`** — a probe answered with `status: "healthy"`, OR a probe answered at all but with
///     no usable health payload (a 404 on both paths: the server is reachable, it just doesn't
///     implement a health route here). Fails OPEN, same as round one.
///   - **`degraded`** — a probe answered with `status: "unhealthy"`. `degraded` has no literal wire
///     value to match: the real node's `HealthResponse.status` is only ever `"healthy"`/`"unhealthy"`
///     (`StatusRoutes.buildHealthResponse`); Forge's is hardcoded to always `"healthy"` and can never
///     signal degradation — an honest limit on this gate's real-world triggerability against Forge.
///   - **`unknown`** — BOTH the versioned and bare paths failed with a network-level error (refused,
///     timeout) — the server could not be reached at all, a different claim from "reached and either
///     healthy, unhealthy, or routeless". `cluster.healthUnknown` is a flag distinct from `degraded`;
///     `decideHealthState()`'s unknown branch returns `{unknown: true}` with no `degraded` key at
///     all, so `checkHealth()` has nothing to assign and a prior `degraded = true` verdict survives
///     untouched — an outage on top of a known-degraded cluster must not read back as healthy. It
///     clears the moment either probe answers anything, even a 404. While unknown, every poll timer
///     (both in `app.js`, and `requests.js`'s own) backs off to a shared slow 10s retry via
///     `cluster.unknownRetryDue()` instead of continuing at full 2-3s cadence, and `RestClient`
///     routes every `.catch()` network failure through `_reportNetworkFailure()`, which suppresses
///     the toast (logging once instead) for exactly as long as `healthUnknown` stays true.
///
/// No JS test runner exists anywhere in this repository — confirmed by grep for
/// `ScriptEngine|GraalJSScriptEngine|org.graalvm|javax.script|jdk.nashorn|polyglot` across every
/// `.xml`/`.java` source outside `target/`, zero matches — and adding one (e.g. a GraalJS Maven
/// dependency) is out of scope for this fix. `decideHealthState()` is extracted as a small pure
/// function so the classification is one reviewable unit and so "unknown never overwrites degraded"
/// is a structural property of its return shape, but it stays pinned the same way the rest of this
/// file already does: structural Java assertions on the extracted JS text, bounded to the smallest
/// span that makes the assertion mean something, disclosed here as exactly that rather than dressed
/// up as executed coverage.
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

    /// Drops `//`-to-end-of-line comments before a substring assertion needs to mean "the code
    /// does not do this" rather than "the code does not mention this" — checkHealth()'s own
    /// explanatory comments name `RestClient.get()` in prose (explaining exactly why it is NOT
    /// used), which would otherwise trip a naive `doesNotContain` on the raw body.
    private static String stripLineComments(String source) {
        return Pattern.compile("//[^\n]*")
                      .matcher(source)
                      .replaceAll("");
    }

    private static String decideHealthStateBody() {
        var appJs = resource("/dashboard/js/app.js").unwrap();
        var start = appJs.indexOf("function decideHealthState(");
        var end = appJs.indexOf("\n}", start);

        assertThat(start).as("decideHealthState() must exist at module scope in app.js").isNotNegative();
        assertThat(end).as("decideHealthState()'s closing brace must be boundable").isGreaterThan(start);

        return appJs.substring(start, end);
    }

    private static String checkHealthBody() {
        var appJs = resource("/dashboard/js/app.js").unwrap();
        var start = appJs.indexOf("async checkHealth() {");
        var end = appJs.indexOf("async pollStatus() {", start);

        assertThat(start).as("checkHealth() must exist in app.js").isNotNegative();
        assertThat(end).as("pollStatus() must follow checkHealth() so its body is boundable").isGreaterThan(start);

        return appJs.substring(start, end);
    }

    @Test
    void checkHealth_probesVersionedHealthFirst_thenBareHealthFallback_viaProbeHealthNotPlainGet() {
        var body = checkHealthBody();
        var versionedIndex = body.indexOf("RestClient.probeHealth('/api/v1/health')");
        var bareIndex = body.indexOf("RestClient.probeHealth('/health')");

        assertThat(versionedIndex).as("the versioned path must be probed FIRST — it's the ONLY health route "
                                     + "the real node serves (#300); probing bare-only never engages the gate "
                                     + "against a real node at all")
                  .isNotNegative();
        assertThat(bareIndex).as("bare '/health' must remain as the fallback for what Forge actually serves")
                  .isGreaterThan(versionedIndex);
        // Comments stripped: the method's own explanatory comments name `RestClient.get()` in
        // prose (to explain why it is NOT used here), which would otherwise trip this check even
        // though no actual call to it exists — asserting on code, not on what the code discusses.
        assertThat(stripLineComments(body)).as("checkHealth() must use the reachability-aware probeHealth(), "
                                              + "never the shared get() — get() collapses a 404 and a network exception to an "
                                              + "identical null, which is exactly the #846 round-two finding: round one's fail-open "
                                              + "could not tell a real outage apart from a harmless missing route")
                  .doesNotContain("RestClient.get(");
    }

    @Test
    void decideHealthState_reachableWithNoHealthJson_failsOpenToHealthy_neverToDegradedTrue() {
        var body = decideHealthStateBody();

        assertThat(body).as("a path that answered but returned no usable status (a 404 on both) must fail OPEN: "
                           + "the guard short-circuits false before the status comparison ever runs, so 'reachable, "
                           + "no route' can never read as degraded")
                  .contains("!!(health.json && health.json.status) && health.json.status !== 'healthy'");
    }

    @Test
    void decideHealthState_bothProbesUnreachable_omitsDegradedKeyEntirely_soCallerCannotOverwriteIt() {
        var body = decideHealthStateBody();
        var unknownBranchStart = body.indexOf("if (!reachable) {");
        var returnStatement = "return { unknown: true };";
        var returnIndex = body.indexOf(returnStatement, unknownBranchStart);

        assertThat(unknownBranchStart).as("the unreachable branch must exist").isNotNegative();
        assertThat(returnIndex).as("the unreachable branch's return statement must exist")
                  .isGreaterThan(unknownBranchStart);
        // Bounded past the return statement's OWN closing brace (part of the object literal) to the
        // if-block's closing brace — a naive indexOf("}", ...) from unknownBranchStart would stop at
        // the object literal's brace instead and truncate before "true }" ever appears.
        var ifBlockEnd = body.indexOf("}", returnIndex + returnStatement.length());
        var unknownBranch = body.substring(unknownBranchStart, ifBlockEnd);

        assertThat(unknownBranch).as("the unreachable branch's return value must literally omit `degraded` — "
                                    + "returning any boolean for it, even the prior value, would require the "
                                    + "caller to remember not to apply it. Omitting the key makes 'nothing to "
                                    + "overwrite' a property of the data, not caller discipline")
                  .contains("{ unknown: true }")
                  .doesNotContain("degraded");
    }

    @Test
    void checkHealth_unknownBranch_returnsBeforeDegradedAssignment_priorDegradedTrueSurvivesNetworkFailure() {
        var body = checkHealthBody();
        var unknownIf = body.indexOf("if (decision.unknown) {");
        var earlyReturn = body.indexOf("return;", unknownIf);
        var degradedAssignment = body.indexOf("cluster.degraded = decision.degraded;");

        assertThat(unknownIf).as("checkHealth() must branch on decision.unknown").isNotNegative();
        assertThat(earlyReturn).as("the unknown branch must return").isGreaterThan(unknownIf);
        assertThat(degradedAssignment).as("the ONLY assignment to cluster.degraded in this method must be "
                                         + "unreachable from the unknown branch — it must appear strictly AFTER "
                                         + "the branch's return, so a prior degraded=true is never overwritten "
                                         + "when a probe fails with a network error instead of answering")
                  .isGreaterThan(earlyReturn);
        assertThat(occurrences(body, "cluster.degraded =")).as("degraded must be assigned exactly once in checkHealth(), from decision.degraded — "
                                                              + "no separate code path may set it directly")
                  .isEqualTo(1);
    }

    @Test
    void checkHealth_healthUnknownFlag_assignedUnconditionally_clearsOnAnyAnswerIncluding404() {
        var body = checkHealthBody();
        var flagAssignment = body.indexOf("cluster.healthUnknown = decision.unknown;");
        var unknownIf = body.indexOf("if (decision.unknown) {");

        assertThat(flagAssignment).as("healthUnknown must be assigned from decision.unknown").isNotNegative();
        assertThat(unknownIf).as("the branch on decision.unknown must exist").isNotNegative();
        assertThat(flagAssignment).as("the assignment must run BEFORE the branch, i.e. unconditionally on every "
                                     + "call — so the moment either probe answers anything at all (decision.unknown "
                                     + "is false, a 404 included), healthUnknown clears on that same tick rather "
                                     + "than waiting for some separate reset path")
                  .isLessThan(unknownIf);
    }

    @Test
    void appJs_pollingTimers_skipWhenClusterDegraded_backOffToSlowRetryWhenHealthUnknown() {
        var appJs = resource("/dashboard/js/app.js").unwrap();

        assertThat(appJs).as("the primary and secondary poll timers must both check the degraded gate")
                  .contains("cluster.degraded) return;");
        assertThat(occurrences(appJs,
                               "cluster.healthUnknown && !cluster.unknownRetryDue()) return;")).as("the DATA-poll halves of both app.js timers (primary's post-checkHealth batch, and "
                                                                                                  + "secondary in full) must back off to the shared slow retry while health is unknown, "
                                                                                                  + "distinct from the degraded skip above. The primary timer's checkHealth() call itself "
                                                                                                  + "is gated separately, by its own dedicated throttle (#846 SHOULD-FIX) — see "
                                                                                                  + "appJs_checkHealthCall_gatedByDedicatedThrottle_notSharedUnknownRetryDue below")
                  .isEqualTo(2);
    }

    @Test
    void requestsJs_pollingTimer_skipsWhenDegraded_backsOffToSlowRetryWhenHealthUnknown() {
        var requestsJs = resource("/dashboard/js/stores/requests.js").unwrap();

        assertThat(requestsJs).contains("cluster.degraded) return;")
                  .as("requests.js's own 3s timer must back off to the same shared unknownRetryDue() throttle "
                     + "as app.js's DATA-poll timers, coordinated through cluster state rather than its own "
                     + "private clock. This is the data-poll throttle only — distinct from app.js's dedicated "
                     + "health re-probe throttle (healthProbeRetryDue())")
                  .contains("cluster.healthUnknown && !cluster.unknownRetryDue()) return;");
    }

    @Test
    void clusterJs_declaresDegradedFlag_defaultingFalse() {
        var clusterJs = resource("/dashboard/js/stores/cluster.js").unwrap();

        assertThat(clusterJs).as("a fresh dashboard load must never fabricate a degraded verdict before the first health probe returns")
                  .contains("degraded: false,");
    }

    @Test
    void clusterJs_declaresHealthUnknownFlag_defaultingFalse_withSharedTenSecondRetryThrottle() {
        var clusterJs = resource("/dashboard/js/stores/cluster.js").unwrap();
        var methodStart = clusterJs.indexOf("unknownRetryDue() {");
        var methodEnd = clusterJs.indexOf("},", methodStart);

        assertThat(clusterJs).as("healthUnknown must default false — a fresh load is neither known-degraded "
                                + "nor known-unreachable until the first probe returns")
                  .contains("healthUnknown: false,");
        assertThat(methodStart).as("unknownRetryDue() must exist as the shared throttle the DATA-poll timers "
                                  + "read (secondary timer, requests.js, and the primary timer's post-checkHealth "
                                  + "batch) — NOT the health re-probe itself, which has its own dedicated throttle; "
                                  + "see clusterJs_healthProbeRetryDue_isDistinctFrom_unknownRetryDue_backedBySeparateFields below")
                  .isNotNegative();
        var body = clusterJs.substring(methodStart, methodEnd);

        assertThat(body).as("the retry cadence during an outage must be the 10s the ruling specifies, and it "
                           + "must be a shared, mutated timestamp so every DATA-poll timer agrees on when the "
                           + "next attempt is due instead of each retrying independently")
                  .contains(">= 10000")
                  .contains("_lastUnknownRetryAt = now;");
    }

    /// #846 SHOULD-FIX (round three): unknownRetryDue() used to be the ONLY throttle, shared by all
    /// three poll timers for two different jobs — gating the primary timer's checkHealth() recovery
    /// re-probe, and gating the other two timers' (plus the primary timer's own post-checkHealth
    /// batch) data polls. Being a read-and-consume throttle (checking AND updating the timestamp in
    /// one call), whichever timer's tick called it FIRST in a ~10s window won the slot; the others,
    /// including possibly the primary timer's own checkHealth() gate, saw the slot already consumed
    /// and skipped their turn. When that starved checkHealth() specifically, the health re-probe that
    /// is the ONLY path back to `healthy` silently didn't run that cycle, pushing recovery detection
    /// past the intended 10s bound by an amount that depended on interval drift between the timers.
    /// The fix: healthProbeRetryDue(), a second throttle backed by its own field, read and consumed
    /// ONLY by the primary timer's checkHealth() gate — never by the data-poll timers, so it cannot be
    /// starved by their ticks. This test pins the two throttles as structurally distinct fields, not
    /// merely distinct method names that might still share one timestamp underneath.
    @Test
    void clusterJs_healthProbeRetryDue_isDistinctFrom_unknownRetryDue_backedBySeparateFields() {
        var clusterJs = resource("/dashboard/js/stores/cluster.js").unwrap();

        assertThat(clusterJs).as("the two throttles must be backed by two separately-declared timestamp "
                                + "fields, not one shared field read by two differently-named methods — a "
                                + "shared field would still let one throttle's consumption silently affect "
                                + "the other's due-ness, which is exactly the bug being fixed")
                  .contains("_lastUnknownRetryAt: 0,")
                  .contains("_lastHealthProbeRetryAt: 0,");
        var unknownStart = clusterJs.indexOf("unknownRetryDue() {");
        var unknownEnd = clusterJs.indexOf("},", unknownStart);
        var healthProbeStart = clusterJs.indexOf("healthProbeRetryDue() {");
        var healthProbeEnd = clusterJs.indexOf("},", healthProbeStart);

        assertThat(unknownStart).as("unknownRetryDue() must exist").isNotNegative();
        assertThat(healthProbeStart).as("healthProbeRetryDue() must exist as a genuinely separate method, "
                                       + "not a renamed unknownRetryDue()")
                  .isNotNegative();
        assertThat(healthProbeStart).as("the two methods must be textually distinct declarations")
                  .isNotEqualTo(unknownStart);
        var unknownBody = clusterJs.substring(unknownStart, unknownEnd);
        var healthProbeBody = clusterJs.substring(healthProbeStart, healthProbeEnd);

        assertThat(unknownBody).as("unknownRetryDue() must read/consume ONLY its own field — never the health "
                                  + "re-probe's field, or a data poll's tick could silently steal the health "
                                  + "re-probe's slot the same way the pre-fix shared throttle did")
                  .contains("_lastUnknownRetryAt")
                  .doesNotContain("_lastHealthProbeRetryAt");
        assertThat(healthProbeBody).as("healthProbeRetryDue() must read/consume ONLY its own field — never the "
                                      + "data-poll throttle's field. This is the load-bearing property: two "
                                      + "differently-named methods sharing one field would reproduce the exact "
                                      + "starvation this fix removes")
                  .contains("_lastHealthProbeRetryAt")
                  .doesNotContain("_lastUnknownRetryAt");
        var requestsJs = resource("/dashboard/js/stores/requests.js").unwrap();

        assertThat(requestsJs).as("the health re-probe throttle is exclusive to app.js's primary timer — it "
                                 + "must never leak into requests.js's data-poll gate, or that data poller "
                                 + "would start starving the health re-probe again from a different call site")
                  .doesNotContain("healthProbeRetryDue");
    }

    /// Pins the call-site half of the same #846 SHOULD-FIX: it is not enough for the two throttles to
    /// exist as distinct fields (previous test) — checkHealth() must actually be wired to the new one.
    @Test
    void appJs_checkHealthCall_gatedByDedicatedThrottle_notSharedUnknownRetryDue() {
        var appJs = resource("/dashboard/js/app.js").unwrap();
        var pollTimerStart = appJs.indexOf("this.pollTimer = setInterval(function() {");
        var pollTimerEnd = appJs.indexOf("}, 2000);", pollTimerStart);

        assertThat(pollTimerStart).as("the primary poll timer must exist").isNotNegative();
        assertThat(pollTimerEnd).as("the primary poll timer's body must be boundable").isGreaterThan(pollTimerStart);
        var body = appJs.substring(pollTimerStart, pollTimerEnd);
        var checkHealthCallIndex = body.indexOf("self.checkHealth();");
        var healthProbeGateIndex = body.indexOf("cluster.healthProbeRetryDue()");
        var dataPollGateIndex = body.indexOf("cluster.healthUnknown && !cluster.unknownRetryDue()) return;");

        assertThat(checkHealthCallIndex).as("checkHealth() must be called from the primary timer").isNotNegative();
        assertThat(healthProbeGateIndex).as("checkHealth() must be gated by the dedicated healthProbeRetryDue() "
                                           + "throttle, never left reading the data-poll timers' shared "
                                           + "unknownRetryDue() — sharing it was the #846 SHOULD-FIX: whichever "
                                           + "timer's tick consumed the shared, read-and-consume throttle first "
                                           + "in a window won the slot, and when it wasn't this one, checkHealth() "
                                           + "silently skipped an entire cycle, pushing recovery detection past "
                                           + "the intended 10s bound by an amount that depended on interval drift")
                  .isNotNegative();
        assertThat(healthProbeGateIndex).as("the healthProbeRetryDue() gate must run BEFORE the checkHealth() call")
                  .isLessThan(checkHealthCallIndex);
        assertThat(dataPollGateIndex).as("the data-poll gate (unknownRetryDue()) must run AFTER the checkHealth() "
                                        + "call, guarding only pollStatus()/events/alerts below it — never the "
                                        + "health re-probe above it, which is the whole point of the split")
                  .isGreaterThan(checkHealthCallIndex);
        assertThat(occurrences(appJs,
                               "cluster.healthProbeRetryDue()")).as("exactly one call site in the whole "
                                                                   + "file — the primary timer's checkHealth() gate. If the secondary timer or "
                                                                   + "requests.js ever called this too, it would reintroduce exactly the "
                                                                   + "starvation this throttle exists to prevent")
                  .isEqualTo(1);
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
    }

    @Test
    void restClientJs_catchBlocks_routeThroughReportNetworkFailure_notDirectToastPerOccurrence() {
        var restClientJs = resource("/dashboard/js/lib/rest-client.js").unwrap();

        assertThat(occurrences(restClientJs,
                               "self._reportNetworkFailure(")).as("all 4 network-exception .catch() sites (get/post/put/del) must route through the "
                                                                 + "shared, healthUnknown-aware reporter instead of toasting unconditionally — otherwise a "
                                                                 + "total outage still spams a toast per poller per tick even though the health gate knows "
                                                                 + "the server is unreachable")
                  .isEqualTo(4);
        assertThat(occurrences(restClientJs,
                               "Notifications.show(")).as("exactly 2 direct toast call sites should remain: _reportFailure's non-404 branch, and "
                                                         + "_reportNetworkFailure's own fallback for when the cluster is NOT in the unknown state "
                                                         + "(a genuine, isolated network blip while the server is otherwise known-reachable still "
                                                         + "toasts normally)")
                  .isEqualTo(2);
    }

    @Test
    void restClientJs_reportNetworkFailure_suppressesToastOnlyWhileHealthUnknown_warnsOnceInstead() {
        var restClientJs = resource("/dashboard/js/lib/rest-client.js").unwrap();
        var start = restClientJs.indexOf("_reportNetworkFailure: function(");
        var end = restClientJs.indexOf("\n    },", start);

        assertThat(start).as("_reportNetworkFailure() must exist").isNotNegative();
        var body = restClientJs.substring(start, end);

        assertThat(body).as("the suppression must be conditioned on the cluster's healthUnknown flag, not on "
                           + "the error itself — the same network error toasts normally once the server is known "
                           + "reachable again")
                  .contains("cluster.healthUnknown")
                  .as("suppressed failures must still be observable once, via console.warn, never silently dropped")
                  .contains("console.warn(")
                  .as("outside the unknown window, the failure must still toast")
                  .contains("Notifications.show(");
    }

    @Test
    void restClientJs_probeHealth_neverToasts_reachableMeansAnyHttpResponseNot404Only() {
        var restClientJs = resource("/dashboard/js/lib/rest-client.js").unwrap();
        var start = restClientJs.indexOf("probeHealth: function(");
        var end = restClientJs.indexOf("\n    },", start);

        assertThat(start).as("probeHealth() must exist as a dedicated probe distinct from get()").isNotNegative();
        var body = restClientJs.substring(start, end);

        assertThat(body).as("health probing runs on every poll tick by design; it must never toast regardless "
                           + "of outcome — checkHealth()/decideHealthState() own the response entirely")
                  .doesNotContain("Notifications.show(")
                  .as("any HTTP response at all, not just a 2xx, must mark the probe reachable — a 404 proves "
                     + "the server exists just as much as a 200 does")
                  .contains("{ reachable: true, json: null }")
                  .as("only a fetch-level exception marks the probe unreachable")
                  .contains("{ reachable: false, json: null }");
    }
}
