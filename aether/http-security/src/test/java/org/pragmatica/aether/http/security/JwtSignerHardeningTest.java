// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AuthSchemes;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.JwtKeyMode;
import org.pragmatica.aether.config.JwtSecret;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2895 hardening: typed [SecurityError] failures, the lifetime bound, the factory-only construction, and pins on the
/// header, the reserved claims, the defensive copy and the merge order that the first suite left unkilled.
class JwtSignerHardeningTest {
    private static final String SECRET = "a-test-only-shared-secret-of-32-bytes!";
    private static final Clock FIXED = Clock.fixed(Instant.ofEpochSecond(1_000_000), ZoneOffset.UTC);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private static JwtConfig hsConfig(JwtSecret secret) {
        return JwtConfig.jwtConfig("", Option.some("iss-1"), Option.some("aud-1"), "role", 3600, 30, AuthSchemes.DEFAULT,
                                   JwtKeyMode.HS256, Option.some(secret))
                        .unwrap();
    }

    private static JwtSigner signer() {
        return JwtSigner.jwtSigner(hsConfig(JwtSecret.jwtSecret(SECRET).unwrap()), FIXED).unwrap();
    }

    private static String payloadOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static void assertSecurityError(Result<?> result, Class<? extends SecurityError> type, String text) {
        var cause = result.fold(c -> c, _ -> (Cause) null);

        assertThat(cause).isInstanceOf(type);
        assertThat(cause.message()).contains(text);
    }

    @Test
    void header_isExactlyAlgHs256AndTypJwt() {
        var token = signer().sign(JwtClaims.jwtClaims("u", MINUTE, Map.of()).unwrap()).unwrap();

        assertThat(new String(Base64.getUrlDecoder().decode(token.split("\\.")[0])))
            .isEqualTo("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
    }

    @Test
    void everyReservedClaim_isRefusedInExtras_asInvalidCredentials() {
        assertThat(JwtClaims.RESERVED).containsExactlyInAnyOrder("iss", "aud", "sub", "iat", "exp", "nbf");
        JwtClaims.RESERVED.forEach(name -> assertSecurityError(JwtClaims.jwtClaims("u", MINUTE, Map.of(name, "x")),
                                                              SecurityError.InvalidCredentials.class, name));
    }

    @Test
    void claimsFailures_areTypedSecurityErrors() {
        assertSecurityError(JwtClaims.jwtClaims(" ", MINUTE, Map.of()), SecurityError.InvalidCredentials.class, "subject");
        assertSecurityError(JwtClaims.jwtClaims("u", Duration.ZERO, Map.of()), SecurityError.InvalidCredentials.class, "lifetime");
        assertSecurityError(JwtClaims.jwtClaims("u", MINUTE.negated(), Map.of()), SecurityError.InvalidCredentials.class, "lifetime");
    }

    @Test
    void hugeLifetime_isRefusedAtClaimsConstruction_andTheBoundItselfIsAccepted() {
        assertSecurityError(JwtClaims.jwtClaims("u", Duration.ofSeconds(Long.MAX_VALUE), Map.of()),
                            SecurityError.InvalidCredentials.class, "lifetime");
        assertSecurityError(JwtClaims.jwtClaims("u", JwtClaims.MAX_LIFETIME.plusSeconds(1), Map.of()),
                            SecurityError.InvalidCredentials.class, "lifetime");
        assertThat(JwtClaims.jwtClaims("u", JwtClaims.MAX_LIFETIME, Map.of()).isSuccess()).isTrue();
    }

    @Test
    void overflowingExp_viaThePublicRecordConstructor_isATypedFailure_notAWrappedToken() {
        var bypass = new JwtClaims("u", Duration.ofSeconds(Long.MAX_VALUE), Map.of());

        assertSecurityError(signer().sign(bypass), SecurityError.InvalidCredentials.class, "payload");
    }

    @Test
    void nullExtraValue_isATypedFailure_notAnEscapedException() {
        var extra = new HashMap<String, Object>();

        extra.put("k", null);

        assertSecurityError(signer().sign(new JwtClaims("u", MINUTE, extra)), SecurityError.InvalidCredentials.class, "payload");
    }

    @Test
    void signerFactoryFailures_areTypedSecurityErrors_andJwksModeIsNamedAsSuch() {
        var jwks = JwtConfig.jwtConfig("https://auth.example.com/jwks.json").unwrap();
        var noSecret = JwtConfig.jwtConfig("", Option.empty(), Option.empty(), "role", 3600, 30, AuthSchemes.DEFAULT,
                                           JwtKeyMode.HS256, Option.empty()).unwrap();

        assertSecurityError(JwtSigner.jwtSigner(jwks), SecurityError.KeyNotFound.class, "HS256 mode");
        assertSecurityError(JwtSigner.jwtSigner(noSecret), SecurityError.KeyNotFound.class, "No HS256 shared secret");
    }

    @Test
    void factory_refusesAShortSecret_thatTheJwtSecretConstructorLetThrough() {
        var shortSecret = new JwtSecret(new byte[5]);

        assertSecurityError(JwtSigner.jwtSigner(hsConfig(shortSecret), FIXED), SecurityError.KeyNotFound.class, "shorter than");
        assertThat(JwtSigner.jwtSigner(hsConfig(new JwtSecret(new byte[JwtSecret.MIN_BYTES])), FIXED).isSuccess()).isTrue();
    }

    @Test
    void claims_copyTheCallersMap_andExposeAnUnmodifiableOne() {
        var source = new HashMap<String, Object>(Map.of("a", "1"));
        var claims = JwtClaims.jwtClaims("u", MINUTE, source).unwrap();

        source.put("b", "2");

        assertThat(claims.extra()).containsOnlyKeys("a");
        assertThatThrownBy(() -> claims.extra().put("c", "3"))
                                       .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void signerOwnedClaims_winOverExtras_evenWhenTheGuardIsBypassed() {
        var bypass = new JwtClaims("real-sub", MINUTE, Map.of("exp", 1L, "iss", "evil", "sub", "evil-sub"));
        var payload = payloadOf(signer().sign(bypass).unwrap());

        assertThat(payload).contains("\"exp\":1000060", "\"iss\":\"iss-1\"", "\"sub\":\"real-sub\"");
        assertThat(payload).doesNotContain("evil");
    }
}
