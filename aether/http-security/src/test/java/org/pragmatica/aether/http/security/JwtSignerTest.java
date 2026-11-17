// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AuthSchemes;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.JwtKeyMode;
import org.pragmatica.aether.config.JwtSecret;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #2895: tokens the signer issues, checked against the REAL HS256 `JwtSecurityValidator` (#2880), never a copy of it.
class JwtSignerTest {
    private static final String SECRET = "a-test-only-shared-secret-of-32-bytes!";
    private static final String OTHER_SECRET = "a-different-shared-secret-of-32-bytes!";
    private static final String ISS = "https://auth.example.com";
    private static final String AUD = "orders-api";

    private static JwtConfig config(String secret, String iss, String aud) {
        return JwtConfig.jwtConfig("", Option.option(iss), Option.option(aud), "role", 3600, 30, AuthSchemes.DEFAULT,
                                   JwtKeyMode.HS256, Option.some(JwtSecret.jwtSecret(secret).unwrap()))
                        .unwrap();
    }

    private static JwtClaims claims(Duration lifetime) {
        return JwtClaims.jwtClaims("user-7", lifetime, Map.of("role", "ADMIN", "tenant", "t1")).unwrap();
    }

    private static String issue(JwtConfig config, Clock clock, Duration lifetime) {
        return JwtSigner.jwtSigner(config, clock).unwrap().sign(claims(lifetime)).unwrap();
    }

    private static boolean verifies(JwtConfig config, String token) {
        var request = HttpRequestContext.httpRequestContext("/t", "GET", Map.of(),
                                                            Map.of("Authorization", List.of("Bearer " + token)),
                                                            new byte[0], "req");

        return new JwtSecurityValidator(config).validate(request, SecurityPolicy.bearerTokenRequired()).isSuccess();
    }

    @Test
    void issuedToken_verifiesUnderTheSameConfig_andCarriesCallerClaims() {
        var config = config(SECRET, ISS, AUD);
        var token = issue(config, Clock.systemUTC(), Duration.ofMinutes(5));
        var request = HttpRequestContext.httpRequestContext("/t", "GET", Map.of(),
                                                            Map.of("Authorization", List.of("Bearer " + token)),
                                                            new byte[0], "req");
        var context = new JwtSecurityValidator(config).validate(request, SecurityPolicy.bearerTokenRequired()).unwrap();

        assertThat(context.claims()).containsEntry("tenant", "t1");
    }

    @Test
    void issuedToken_hasHs256HeaderAndAllRegisteredClaims() {
        var token = issue(config(SECRET, ISS, AUD), Clock.fixed(java.time.Instant.ofEpochSecond(1_000_000), java.time.ZoneOffset.UTC),
                          Duration.ofSeconds(60));
        var parts = token.split("\\.");
        var header = new String(Base64.getUrlDecoder().decode(parts[0]));
        var payload = new String(Base64.getUrlDecoder().decode(parts[1]));

        assertThat(parts).hasSize(3);
        assertThat(token).doesNotContain("=", "+", "/");
        assertThat(header).contains("\"alg\":\"HS256\"");
        assertThat(payload).contains("\"iat\":1000000", "\"exp\":1000060", "\"iss\":\"" + ISS + "\"", "\"aud\":\"" + AUD + "\"", "\"sub\":\"user-7\"");
    }

    @Test
    void issuedToken_failsUnderADifferentSecret() {
        var token = issue(config(SECRET, ISS, AUD), Clock.systemUTC(), Duration.ofMinutes(5));

        assertThat(verifies(config(SECRET, ISS, AUD), token)).isTrue();
        assertThat(verifies(config(OTHER_SECRET, ISS, AUD), token)).isFalse();
    }

    @Test
    void expiredToken_failsVerification() {
        var past = Clock.offset(Clock.systemUTC(), Duration.ofHours(-1));
        var token = issue(config(SECRET, ISS, AUD), past, Duration.ofMinutes(1));

        assertThat(verifies(config(SECRET, ISS, AUD), token)).isFalse();
    }

    @Test
    void wrongIssuer_orWrongAudience_failVerification() {
        var token = issue(config(SECRET, ISS, AUD), Clock.systemUTC(), Duration.ofMinutes(5));

        assertThat(verifies(config(SECRET, "https://evil.example.com", AUD), token)).isFalse();
        assertThat(verifies(config(SECRET, ISS, "other-api"), token)).isFalse();
    }

    @Test
    void signer_refusesJwksModeConfig_andHs256ConfigWithoutSecret() {
        var jwks = JwtConfig.jwtConfig("https://auth.example.com/jwks.json").unwrap();
        var noSecret = JwtConfig.jwtConfig("", Option.empty(), Option.empty(), "role", 3600, 30, AuthSchemes.DEFAULT,
                                           JwtKeyMode.HS256, Option.empty()).unwrap();

        assertThat(JwtSigner.jwtSigner(jwks).isFailure()).isTrue();
        assertThat(JwtSigner.jwtSigner(noSecret).isFailure()).isTrue();
    }

    @Test
    void claims_refuseBlankSubject_nonPositiveLifetime_andRegisteredExtras() {
        assertThat(JwtClaims.jwtClaims(" ", Duration.ofMinutes(1), Map.of()).isFailure()).isTrue();
        assertThat(JwtClaims.jwtClaims("u", Duration.ZERO, Map.of()).isFailure()).isTrue();
        assertThat(JwtClaims.jwtClaims("u", Duration.ofMinutes(-1), Map.of()).isFailure()).isTrue();
        assertThat(JwtClaims.jwtClaims("u", Duration.ofMinutes(1), Map.of("exp", 1L)).isFailure()).isTrue();
        assertThat(JwtClaims.jwtClaims("u", Duration.ofMinutes(1), Map.of("aud", "x")).isFailure()).isTrue();
    }
}
