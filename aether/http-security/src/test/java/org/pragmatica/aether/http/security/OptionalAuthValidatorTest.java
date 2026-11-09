// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.http.security.Hs256TestSupport.*;

/// #2946: `SecurityPolicy.optional()` authenticates if a credential is presented. No credential is anonymous (an empty
/// context); a credential that fails validation is refused as a 401 `invalid_token` (the #2926 mapping); a valid one
/// yields its validated context. `Public` keeps ignoring credentials; required policies keep refusing a missing one.
class OptionalAuthValidatorTest {
    private static final String HS256 = "{\"alg\":\"HS256\"}";
    private static final String KEY = "optional-test-key-0123456789";

    private static HttpRequestContext request(Map<String, List<String>> headers) {
        return HttpRequestContext.httpRequestContext("/test", "GET", Map.of(), headers, new byte[0], "req");
    }

    private static HttpRequestContext bearer(String token) {
        return request(Map.of("Authorization", List.of("Bearer " + token)));
    }

    private static SecurityContext served(JwtSecurityValidator validator, HttpRequestContext request, SecurityPolicy policy) {
        return validator.validate(request, policy).fold(cause -> fail("must be served: " + cause.message()), ctx -> ctx);
    }

    private static Cause refused(JwtSecurityValidator validator, HttpRequestContext request) {
        return validator.validate(request, SecurityPolicy.optional()).fold(cause -> cause, _ -> fail("must be refused"));
    }

    private static void assertInvalidToken401(Cause cause) {
        assertThat(cause).isInstanceOf(SecurityError.class);
        assertThat(((SecurityError) cause).httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(SecurityError.challengeError(cause)).isEqualTo(Option.some("invalid_token"));
    }

    @Test
    void jwt_noToken_servedAnonymously() {
        var ctx = served(hsValidator(), request(Map.of()), SecurityPolicy.optional());

        assertThat(ctx.isAuthenticated()).isFalse();
    }

    @Test
    void jwt_foreignAuthorizationScheme_refusedAs401InvalidToken() {
        assertInvalidToken401(refused(hsValidator(), request(Map.of("Authorization", List.of("Basic dXNlcjpwdw==")))));
    }

    @Test
    void jwt_bareBearerScheme_refusedAs401InvalidToken() {
        assertInvalidToken401(refused(hsValidator(), request(Map.of("Authorization", List.of("Bearer")))));
    }

    @Test
    void jwt_emptyAuthorizationHeader_refusedAs401InvalidToken() {
        assertInvalidToken401(refused(hsValidator(), request(Map.of("Authorization", List.of("")))));
    }

    @Test
    void jwt_lowercaseHeaderNameBareScheme_refusedAs401InvalidToken() {
        assertInvalidToken401(refused(hsValidator(), request(Map.of("authorization", List.of("Bearer")))));
    }

    @Test
    void jwt_garbageToken_refusedAs401InvalidToken() {
        assertInvalidToken401(refused(hsValidator(), bearer("not-a-jwt")));
    }

    @Test
    void jwt_emptyBearerToken_refusedAs401InvalidToken() {
        assertInvalidToken401(refused(hsValidator(), request(Map.of("Authorization", List.of("Bearer  ")))));
    }

    @Test
    void jwt_expiredToken_refusedAs401InvalidToken() {
        var token = hs256Token(HS256, "{\"sub\":\"svc-1\",\"exp\":" + (now() - 3600) + "}");

        assertInvalidToken401(refused(hsValidator(), bearer(token)));
    }

    @Test
    void jwt_badSignature_refusedAs401InvalidToken() {
        var token = hs256Token(HS256, payload(""));
        var tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertInvalidToken401(refused(hsValidator(), bearer(tampered)));
    }

    @Test
    void jwt_wrongAlgForTheConfiguredMode_refusedAs401InvalidToken() {
        var token = hs256Token("{\"alg\":\"RS256\"}", payload(""));

        assertInvalidToken401(refused(hsValidator(), bearer(token)));
    }

    @Test
    void jwt_jwksModeRejectsAnHs256Token_refusedAs401InvalidToken() {
        var pair = rsaKeyPair();

        assertInvalidToken401(refused(jwksValidator(pair), bearer(hs256Token("{\"alg\":\"HS256\",\"kid\":\"k1\"}", payload("")))));
    }

    @Test
    void jwt_jwksModeBadRsaSignature_refusedAs401InvalidToken() {
        var pair = rsaKeyPair();
        var other = rsaKeyPair();
        var forged = rs256Token(other, "{\"alg\":\"RS256\",\"kid\":\"k1\"}", payload(""));

        assertInvalidToken401(refused(jwksValidator(pair), bearer(forged)));
    }

    @Test
    void jwt_validToken_deliversTheValidatedContext() {
        var ctx = served(hsValidator(), bearer(hs256Token(HS256, payload(",\"role\":\"editor\""))), SecurityPolicy.optional());

        assertThat(ctx.isAuthenticated()).isTrue();
        assertThat(ctx.principal().value()).endsWith("svc-1");
        assertThat(ctx.hasRole("editor")).isTrue();
    }

    @Test
    void jwt_jwksModeValidToken_deliversTheValidatedContext() {
        var pair = rsaKeyPair();
        var ctx = served(jwksValidator(pair),
                         bearer(rs256Token(pair, "{\"alg\":\"RS256\",\"kid\":\"k1\"}", payload(""))),
                         SecurityPolicy.optional());

        assertThat(ctx.isAuthenticated()).isTrue();
    }

    @Test
    void jwt_publicRoute_stillIgnoresAPresentedToken() {
        var ctx = served(hsValidator(), bearer("not-a-jwt"), SecurityPolicy.publicRoute());

        assertThat(ctx.isAuthenticated()).isFalse();
    }

    @Test
    void jwt_requiredPolicies_stillRefuseAMissingToken() {
        var validator = hsValidator();

        for (var policy : List.of(SecurityPolicy.authenticated(), SecurityPolicy.bearerTokenRequired(), SecurityPolicy.roleRequired("editor"))) {
            assertThat(validator.validate(request(Map.of()), policy).isFailure()).as(policy.asString()).isTrue();
        }
    }

    @Test
    void apiKey_noKey_servedAnonymously() {
        var ctx = apiKeyValidator().validate(request(Map.of()), SecurityPolicy.optional()).fold(c -> fail(c.message()), c -> c);

        assertThat(ctx.isAuthenticated()).isFalse();
    }

    @Test
    void apiKey_wrongKey_refusedAs401InvalidToken() {
        var cause = apiKeyValidator().validate(request(Map.of("X-API-Key", List.of("wrong"))), SecurityPolicy.optional())
                                     .fold(c -> c, _ -> fail("must be refused"));

        assertInvalidToken401(cause);
    }

    @Test
    void apiKey_presentButEmptyKey_refusedAs401InvalidToken() {
        for (var value : List.of("", "   ")) {
            var cause = apiKeyValidator().validate(request(Map.of("X-API-Key", List.of(value))), SecurityPolicy.optional())
                                         .fold(c -> c, _ -> fail("must be refused"));

            assertInvalidToken401(cause);
        }
    }

    @Test
    void apiKey_validKey_deliversTheValidatedContext() {
        var ctx = apiKeyValidator().validate(request(Map.of("X-API-Key", List.of(KEY))), SecurityPolicy.optional())
                                   .fold(c -> fail(c.message()), c -> c);

        assertThat(ctx.isAuthenticated()).isTrue();
    }

    @Test
    void denyUnlessPublic_servesOptionalAnonymously_andStillRefusesRequiredPolicies() {
        var validator = HttpAuthenticator.denyUnlessPublicValidator();

        assertThat(validator.validate(request(Map.of()), SecurityPolicy.optional()).fold(_ -> fail("served"), c -> c).isAuthenticated()).isFalse();
        assertThat(validator.validate(request(Map.of()), SecurityPolicy.authenticated()).isFailure()).isTrue();
    }

    private static HttpAuthenticator apiKeyValidator() {
        return HttpAuthenticator.apiKeyValidator(Set.of(KEY));
    }
}
