// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/// #2926: the app-HTTP status and client text of EVERY [SecurityError] case. Direct and forwarded refusals share this
/// one mapping. 401 = unauthenticated, 403 = authenticated but lacking a role; the text never says which check failed.
class AppHttpServerSecurityErrorStatusTest {
    @Test
    void everyPresentedButRefusedCredential_is401_andTheTextHidesWhichCheckFailed() {
        var refused = new SecurityError[] {SecurityError.INVALID_API_KEY,
                                           SecurityError.MALFORMED_TOKEN,
                                           new SecurityError.SignatureInvalid("sig detail"),
                                           new SecurityError.IssuerMismatch("iss detail"),
                                           new SecurityError.AudienceMismatch("aud detail"),
                                           new SecurityError.KeyNotFound("kid detail")};

        for (var cause : refused) {
            var mapped = AppHttpServerAdapter.mapSecurityError(cause);

            assertThat(mapped.status()).as(cause.toString()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(mapped.clientMessage()).as(cause.toString()).doesNotContain("detail");
        }
    }

    @Test
    void expiredToken_is401() {
        assertThat(AppHttpServerAdapter.mapSecurityError(new SecurityError.TokenExpired("x")).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingCredentialsAndUnenforceablePolicies_are401() {
        for (var cause : new SecurityError[] {SecurityError.MISSING_API_KEY,
                                              SecurityError.MISSING_BEARER_TOKEN,
                                              SecurityError.NO_VALIDATOR_CONFIGURED,
                                              SecurityError.UNRESOLVED_POLICY,
                                              SecurityError.UNENFORCEABLE_POLICY}) {
            assertThat(AppHttpServerAdapter.mapSecurityError(cause).status()).as(cause.toString()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void authorizationDenials_are403() {
        assertThat(AppHttpServerAdapter.mapSecurityError(new SecurityError.InsufficientRole("role 'admin' required")).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(AppHttpServerAdapter.mapSecurityError(new SecurityError.AccessDenied("x")).status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void jwksFetchFailure_isAServerSideFailureAnswered401WithoutAnErrorCode() {
        var mapped = AppHttpServerAdapter.mapSecurityError(new SecurityError.JwksFetchFailed("x"));

        assertThat(mapped.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(SecurityError.challengeError(new SecurityError.JwksFetchFailed("x")).isEmpty()).isTrue();
    }
}
