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


/// Pins #703: the dashboard's API-key overlay must be gated on the SERVER's posture, learned by
/// probing it, never on the mere absence of a key in `sessionStorage`. Before the fix
/// `AetherAuth.init()` showed the overlay whenever no key was stored and "validated" whatever was
/// typed by calling `GET /api/nodes/status` with it as a header — a request that returns 200 for ANY
/// value when management security is off (`ManagementServer.handleRequest` only runs
/// `validateManagementSecurity` when `securityEnabled`; Forge's dashboard port has no gate at all).
/// So the overlay accepted any non-empty string and taught operators that a credential existed and
/// was checked, when neither was true.
///
/// The posture is learned from the gate itself rather than from a declared field: the SAME request
/// the overlay already uses to validate a key, sent WITHOUT one. `2xx` means the server serves the
/// dashboard's data unauthenticated (posture `open`, no overlay); `401`/`403` means it refuses
/// (posture `key`, overlay exactly as before); anything else leaves the posture `unknown` and falls
/// back to asking, as before. A field claiming "security is off" could disagree with the gate; the
/// gate cannot disagree with itself.
///
/// Same instrument as `DashboardPollingGateContractTest`, same disclosure: no JS runner exists in this
/// repository, so these are structural assertions on the extracted JS text, bounded to the smallest
/// span that makes each assertion mean something — not executed coverage.
class DashboardAuthPostureContractTest {
    private static Result<String> resource(String path) {
        try (InputStream in = DashboardAuthPostureContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return Result.failure(Causes.cause("Dashboard resource not found on classpath: " + path));
            }

            return Result.success(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Result.failure(Causes.fromThrowable(e));
        }
    }

    private static String stripLineComments(String source) {
        return Pattern.compile("//[^\n]*")
                      .matcher(source)
                      .replaceAll("");
    }

    private static String aetherAuthBody() {
        var html = resource("/dashboard/index.html").unwrap();
        var start = html.indexOf("window.AetherAuth = {");
        var end = html.indexOf("\n    };", start);

        assertThat(start).as("AetherAuth must be declared in index.html").isNotNegative();
        assertThat(end).as("AetherAuth's closing brace must be boundable").isGreaterThan(start);

        return html.substring(start, end);
    }

    private static String method(String body, String name) {
        var start = body.indexOf(name + ": function(");
        var end = body.indexOf("\n        }", start);

        assertThat(start).as("AetherAuth." + name + "() must exist").isNotNegative();
        assertThat(end).as("AetherAuth." + name + "()'s closing brace must be boundable").isGreaterThan(start);

        return stripLineComments(body.substring(start, end));
    }

    @Test
    void init_neverShowsOverlayOnMissingKeyAlone_probesServerPostureInstead() {
        var init = method(aetherAuthBody(), "init");

        assertThat(init).as("a missing key alone must not show the overlay — that is the #703 defect: the "
                           + "overlay appeared against servers that require no credential")
                  .doesNotContain("this.show()");
        assertThat(init).as("with no stored key, init must ask the server whether it needs one")
                  .contains("this.probePosture()");
    }

    @Test
    void probePosture_sendsNoCredential_andClassifiesByTheGatesOwnAnswer() {
        var probe = method(aetherAuthBody(), "probePosture");
        var okBranch = probe.indexOf("if (r.ok)");
        var refusedBranch = probe.indexOf("if (r.status === 401 || r.status === 403)");

        assertThat(probe).as("the probe must be the SAME request the overlay validates a key with, so it "
                            + "measures the gate the key would face — sent bare, with no headers at all")
                  .contains("fetch(this.STATUS_PATH)")
                  .doesNotContain("headers");
        assertThat(okBranch).as("a 2xx without a credential IS the open posture: the server served the "
                               + "dashboard's data to an unauthenticated caller")
                  .isNotNegative();
        assertThat(probe.substring(okBranch, probe.indexOf("}", okBranch))).as("open posture starts the app without any overlay")
                  .contains("self.posture = 'open'")
                  .contains("aether-auth-success")
                  .doesNotContain("self.show()");
        assertThat(refusedBranch).as("only the gate's own refusal (401 missing credential, 403 refused) "
                                    + "means a key is required — `ManagementServer.resolveSecurityErrorStatus`")
                  .isGreaterThan(okBranch);
        assertThat(probe.substring(refusedBranch, probe.indexOf("}", refusedBranch))).as("key-required posture shows the overlay exactly as before")
                  .contains("self.posture = 'key'")
                  .contains("self.show()");
    }

    @Test
    void probePosture_undeterminedAnswer_fallsBackToAsking_neverToOpen() {
        var probe = method(aetherAuthBody(), "probePosture");
        var refusedBranch = probe.indexOf("if (r.status === 401 || r.status === 403)");
        var tail = probe.substring(probe.indexOf("}", refusedBranch));

        assertThat(tail).as("a 404/5xx/network failure says nothing about the gate; the fallback is the "
                           + "pre-#703 behaviour (ask), never a silent 'open' — the ticket forbids fixing "
                           + "this by accepting a blank key")
                  .contains("self.show()")
                  .doesNotContain("'open'");
    }

    @Test
    void isReady_isKeyHeldOrPostureOpen_andAppGatesOnIt() {
        var body = aetherAuthBody();
        var ready = method(body, "isReady");
        var appJs = stripLineComments(resource("/dashboard/js/app.js").unwrap());

        assertThat(ready).contains("this.hasValidKey() || this.posture === 'open'");
        assertThat(body).as("posture starts unknown: the app must not start before the server answered")
                  .contains("posture: 'unknown'");
        assertThat(appJs).as("app.js must gate startup on readiness (key held OR no key needed), not on "
                            + "key presence alone")
                  .contains("!window.AetherAuth.isReady()")
                  .doesNotContain("AetherAuth.hasValidKey()");
    }

    @Test
    void onUnauthorized_recordsKeyRequiredPosture() {
        var unauthorized = method(aetherAuthBody(), "onUnauthorized");

        assertThat(unauthorized).as("a 401 mid-session is the gate speaking: the posture is key-required "
                                   + "from then on, whatever the probe said at load")
                  .contains("this.posture = 'key'");
    }
}
