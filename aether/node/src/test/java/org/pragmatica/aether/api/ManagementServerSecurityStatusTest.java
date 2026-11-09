// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/// #2926: the management plane's status and challenge for EVERY [SecurityError] case. 401 = unauthenticated (retry
/// with new credentials), 403 = authenticated but not allowed; `error="invalid_token"` only when a credential was
/// presented and refused.
class ManagementServerSecurityStatusTest {
    private static final String BASE = "ApiKey realm=\"Aether\"";
    private static final String INVALID = BASE + ", error=\"invalid_token\"";

    @Test
    void missingCredentials_is401_withoutAnErrorCode() {
        assertThat(ManagementServerImpl.resolveSecurityErrorStatus(SecurityError.MISSING_API_KEY)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ManagementServerImpl.authenticateChallenge(SecurityError.MISSING_API_KEY)).isEqualTo(BASE);
    }

    @Test
    void everyPresentedButRefusedCredential_is401_withInvalidToken() {
        var refused = new SecurityError[] {SecurityError.INVALID_API_KEY,
                                           SecurityError.MALFORMED_TOKEN,
                                           new SecurityError.TokenExpired("x"),
                                           new SecurityError.SignatureInvalid("x"),
                                           new SecurityError.IssuerMismatch("x"),
                                           new SecurityError.AudienceMismatch("x"),
                                           new SecurityError.KeyNotFound("x")};

        for (var cause : refused) {
            assertThat(ManagementServerImpl.resolveSecurityErrorStatus(cause)).as(cause.toString()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(ManagementServerImpl.authenticateChallenge(cause)).as(cause.toString()).isEqualTo(INVALID);
        }
    }

    @Test
    void authorizationDenials_are403_withoutAChallenge() {
        for (var cause : new SecurityError[] {new SecurityError.AccessDenied("x"), new SecurityError.InsufficientRole("x")}) {
            assertThat(ManagementServerImpl.resolveSecurityErrorStatus(cause)).as(cause.toString()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(SecurityError.challengeError(cause)).as(cause.toString()).isEqualTo(org.pragmatica.lang.Option.none());
        }
    }

    @Test
    void unenforceableAndUnresolvedPolicies_are401_withoutAnErrorCode() {
        for (var cause : new SecurityError[] {SecurityError.NO_VALIDATOR_CONFIGURED,
                                              SecurityError.UNRESOLVED_POLICY,
                                              SecurityError.UNENFORCEABLE_POLICY}) {
            assertThat(ManagementServerImpl.resolveSecurityErrorStatus(cause)).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(ManagementServerImpl.authenticateChallenge(cause)).isEqualTo(BASE);
        }
    }

    /// #2926 (CTO ruling): a key-fetch failure is the server failing -- 503; the 401-only challenge branch is not taken.
    @Test
    void jwksFetchFailure_is503_andNeverChallenged() {
        var cause = new SecurityError.JwksFetchFailed("x");

        assertThat(ManagementServerImpl.resolveSecurityErrorStatus(cause)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(SecurityError.challengeError(cause)).isEqualTo(org.pragmatica.lang.Option.none());
    }
}
