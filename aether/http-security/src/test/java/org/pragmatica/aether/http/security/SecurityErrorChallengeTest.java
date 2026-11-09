// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #2926: which refusals carry an RFC 6750 section 3.1 `error` code in their `WWW-Authenticate` challenge.
class SecurityErrorChallengeTest {
    @Test
    void presentedButRefusedCredentials_carryInvalidToken() {
        for (var cause : new SecurityError[] {SecurityError.INVALID_API_KEY,
                                              SecurityError.MALFORMED_TOKEN,
                                              new SecurityError.InvalidCredentials("x"),
                                              new SecurityError.TokenExpired("x"),
                                              new SecurityError.SignatureInvalid("x"),
                                              new SecurityError.IssuerMismatch("x"),
                                              new SecurityError.AudienceMismatch("x"),
                                              new SecurityError.KeyNotFound("x")}) {
            assertThat(cause.challengeError()).as(cause.toString()).isEqualTo(Option.some("invalid_token"));
        }
    }

    @Test
    void absentCredentialsAndNonTokenFailures_carryNoErrorCode() {
        for (var cause : new SecurityError[] {SecurityError.MISSING_API_KEY,
                                              SecurityError.MISSING_BEARER_TOKEN,
                                              SecurityError.NO_VALIDATOR_CONFIGURED,
                                              SecurityError.UNRESOLVED_POLICY,
                                              SecurityError.UNENFORCEABLE_POLICY,
                                              new SecurityError.AccessDenied("x"),
                                              new SecurityError.InsufficientRole("x"),
                                              new SecurityError.JwksFetchFailed("x")}) {
            assertThat(cause.challengeError()).as(cause.toString()).isEqualTo(Option.none());
        }
    }

    @Test
    void challenge_isSchemeRealmAndOptionalErrorCode_andNothingForAForeignCause() {
        assertThat(SecurityError.challenge("Bearer", "Aether", SecurityError.MALFORMED_TOKEN))
            .isEqualTo("Bearer realm=\"Aether\", error=\"invalid_token\"");
        assertThat(SecurityError.challenge("Bearer", "Aether", SecurityError.MISSING_BEARER_TOKEN)).isEqualTo("Bearer realm=\"Aether\"");
        assertThat(SecurityError.challenge("Token", "Terra", Causes.cause("boom"))).isEqualTo("Token realm=\"Terra\"");
    }

    @Test
    void validators_nameTheirChallengeScheme() {
        assertThat(HttpAuthenticator.apiKeyValidator(java.util.Set.of("k")).challengeScheme()).isEqualTo(Option.some("ApiKey"));
        assertThat(HttpAuthenticator.denyUnlessPublicValidator().challengeScheme()).isEqualTo(Option.none());
    }

    @Test
    void jwksFetchFailure_isServiceUnavailable_theOnlyServerSideCase() {
        assertThat(new SecurityError.JwksFetchFailed("x").httpStatus()).isEqualTo(org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE);
    }
}
