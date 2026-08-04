// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/// #573 — "no validator configured" must mean NO authority, never ALL authority.
///
/// The management plane selected a validator that granted `ADMIN + SERVICE` to every caller whenever
/// `[app-http] enabled` was false — the DEFAULT. `ConfigLoader` defaults `securityMode` to `API_KEY`
/// ("secure by default", #290), but `Main` discards the whole parsed `AppHttpConfig` when app-HTTP is
/// disabled and substitutes a `SecurityMode.NONE` default, so the secure default never took effect.
///
/// These tests pin the two validators APART. The permissive one still exists and is still correct for
/// callers that have already decided authentication is unwanted (Forge dev websockets; `AppHttpServer`'s
/// NONE arm, which refuses auth-requiring routes before consulting a validator). What must never happen
/// again is the management plane reaching for it.
class SecurityValidatorAuthorityTest {
    private static final HttpRequestContext REQUEST =
        HttpRequestContext.httpRequestContext("/api/test", "GET", Map.of(), Map.<String, List<String>> of(), new byte[0], "req-573");

    /// THE regression. A denying validator must refuse a role-bearing route rather than grant it.
    @Test
    void denyUnlessPublic_roleRequiredRoute_isRefused() {
        var result = SecurityValidator.denyUnlessPublicValidator()
                                      .validate(REQUEST, new SecurityPolicy.RoleRequired(Role.ADMIN.value()));

        assertThat(result.isFailure()).as("an unconfigured validator must not authorize a privileged route")
                  .isTrue();
    }

    @Test
    void denyUnlessPublic_apiKeyRequiredRoute_isRefused() {
        assertThat(SecurityValidator.denyUnlessPublicValidator()
                                    .validate(REQUEST, new SecurityPolicy.ApiKeyRequired())
                                    .isFailure()).isTrue();
    }

    /// Public routes must still work — health/liveness endpoints are public and must not require
    /// credentials just because none are configured.
    @Test
    void denyUnlessPublic_publicRoute_passesWithEmptyContext() {
        SecurityValidator.denyUnlessPublicValidator()
                         .validate(REQUEST, new SecurityPolicy.Public())
                         .onFailure(cause -> Assertions.fail("public route must pass: "
                                                                                   + cause.message()))
                         .onSuccess(ctx -> assertThat(ctx.roles()).as("a public caller carries NO roles")
                                                     .isEmpty());
    }

    /// Failing (not succeeding-with-empty) is load-bearing: `KvStoreApiKeyValidator` consults its
    /// delegate first and RETURNS on success, so an unconditionally-succeeding delegate would
    /// short-circuit the cluster's KV-held bootstrap admin keys. A failure lets that path run.
    @Test
    void denyUnlessPublic_nonPublicRoute_failsRatherThanSucceedingEmpty_soKvKeysAreStillConsulted() {
        var result = SecurityValidator.denyUnlessPublicValidator()
                                      .validate(REQUEST, new SecurityPolicy.Authenticated());

        assertThat(result.isSuccess()).as("succeeding here would bypass the KV bootstrap-admin-key path")
                  .isFalse();
    }

    /// The permissive validator is retained deliberately and still grants — pinned so its behaviour is
    /// explicit rather than incidental, and so the two are never conflated again.
    @Test
    void permitAll_stillGrantsAdmin_forCallersThatDeliberatelyWantNoAuth() {
        SecurityValidator.permitAllValidator()
                         .validate(REQUEST, new SecurityPolicy.RoleRequired(Role.ADMIN.value()))
                         .onFailure(cause -> Assertions.fail("permitAll must grant: "
                                                                                   + cause.message()))
                         .onSuccess(ctx -> assertThat(ctx.roles()).contains(Role.ADMIN));
    }
}
