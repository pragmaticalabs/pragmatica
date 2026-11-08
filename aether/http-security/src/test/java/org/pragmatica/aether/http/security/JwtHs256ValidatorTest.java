// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AuthSchemes;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.JwtKeyMode;
import org.pragmatica.aether.config.JwtSecret;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #2880: HS256 shared-secret mode and algorithm-confusion refusal. The CONFIGURED mode picks the verification path;
/// the token header never does.
class JwtHs256ValidatorTest {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final String SECRET_TEXT = "a-test-only-shared-secret-of-32-bytes!";
    private static final byte[] SECRET = SECRET_TEXT.getBytes(StandardCharsets.UTF_8);
    private static final String HS256_HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private static JwtSecret secret(String text) {
        return JwtSecret.jwtSecret(text).unwrap();
    }

    private static JwtSecurityValidator hsValidator(Option<JwtSecret> secret) {
        var config = JwtConfig.jwtConfig("",
                                         Option.empty(),
                                         Option.empty(),
                                         "role",
                                         3600,
                                         30,
                                         AuthSchemes.DEFAULT,
                                         JwtKeyMode.HS256,
                                         secret)
                              .unwrap();

        return new JwtSecurityValidator(config);
    }

    private static JwtSecurityValidator hsValidator() {
        return hsValidator(Option.some(secret(SECRET_TEXT)));
    }

    private static Map<String, Object> claims() {
        return Map.of("sub", "svc-1", "exp", System.currentTimeMillis() / 1000 + 3600, "role", "ADMIN");
    }

    private static Cause refusal(JwtSecurityValidator validator, String token) {
        return validator.validate(request(token), SecurityPolicy.bearerTokenRequired())
                        .fold(cause -> cause, _ -> fail("token must be refused"));
    }

    private static HttpRequestContext request(String token) {
        return HttpRequestContext.httpRequestContext("/test",
                                                     "GET",
                                                     Map.of(),
                                                     Map.of("Authorization", List.of("Bearer " + token)),
                                                     new byte[0],
                                                     "req");
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String hmacToken(String headerJson, Map<String, Object> claims, byte[] key, String jdkMac) {
        try {
            var signed = B64.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(json(claims).getBytes(StandardCharsets.UTF_8));
            var mac = Mac.getInstance(jdkMac);

            mac.init(new SecretKeySpec(key, jdkMac));

            return signed + "." + B64.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String hs256Token(Map<String, Object> claims, byte[] key) {
        return hmacToken(HS256_HEADER, claims, key, "HmacSHA256");
    }

    private static String json(Map<String, Object> map) {
        var out = new StringBuilder("{");

        map.forEach((k, v) -> out.append(out.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v instanceof Number ? v.toString() : "\"" + v + "\""));

        return out.append('}').toString();
    }

    @Test
    void validate_succeeds_forValidHs256Token() {
        hsValidator().validate(request(hs256Token(claims(), SECRET)), SecurityPolicy.bearerTokenRequired())
                     .onFailure(cause -> fail("must verify: " + cause.message()))
                     .onSuccess(ctx -> assertThat(ctx.principal().value()).isEqualTo("user:svc-1"));
    }

    @Test
    void validate_refuses_hs256TokenSignedWithAWrongSecret() {
        var wrong = "another-test-only-shared-secret-32by!".getBytes(StandardCharsets.UTF_8);

        assertThat(refusal(hsValidator(), hs256Token(claims(), wrong))).isInstanceOf(SecurityError.SignatureInvalid.class);
    }

    @Test
    void validate_refuses_expiredHs256Token() {
        var expired = Map.<String, Object>of("sub", "svc-1", "exp", System.currentTimeMillis() / 1000 - 3600);

        assertThat(refusal(hsValidator(), hs256Token(expired, SECRET))).isInstanceOf(SecurityError.TokenExpired.class);
    }

    @Test
    void validate_refuses_tamperedPayload() {
        var token = hs256Token(claims(), SECRET).split("\\.");
        var forged = Map.<String, Object>of("sub", "attacker", "exp", System.currentTimeMillis() / 1000 + 3600, "role", "ADMIN");
        var tampered = token[0] + "." + B64.encodeToString(json(forged).getBytes(StandardCharsets.UTF_8)) + "." + token[2];

        assertThat(refusal(hsValidator(), tampered)).isInstanceOf(SecurityError.SignatureInvalid.class);
    }

    @Test
    void validate_refuses_noneAlgorithmInHs256Mode() {
        var token = B64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                    + B64.encodeToString(json(claims()).getBytes(StandardCharsets.UTF_8)) + ".";

        assertThat(refusal(hsValidator(), token)).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void validate_refuses_otherHmacAlgorithmInHs256Mode_evenWithAValidMac() {
        var token = hmacToken("{\"alg\":\"HS384\"}", claims(), SECRET, "HmacSHA384");

        assertThat(refusal(hsValidator(), token)).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void validate_refuses_lowerCaseAlgorithmInHs256Mode() {
        var token = hmacToken("{\"alg\":\"hs256\"}", claims(), SECRET, "HmacSHA256");

        assertThat(refusal(hsValidator(), token)).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void validate_refuses_missingAlgInHs256Mode_evenWithAValidMac() {
        var token = hmacToken("{\"typ\":\"JWT\"}", claims(), SECRET, "HmacSHA256");

        assertThat(refusal(hsValidator(), token)).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    @SuppressWarnings("JBCT-EX-01")
    void validate_refuses_rs256TokenInHs256Mode() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");

        kpg.initialize(2048);

        var pair = kpg.generateKeyPair();
        var signed = B64.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k\"}".getBytes(StandardCharsets.UTF_8)) + "."
                     + B64.encodeToString(json(claims()).getBytes(StandardCharsets.UTF_8));
        var sig = Signature.getInstance("SHA256withRSA");

        sig.initSign(pair.getPrivate());
        sig.update(signed.getBytes(StandardCharsets.US_ASCII));

        assertThat(refusal(hsValidator(), signed + "." + B64.encodeToString(sig.sign()))).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void validate_refuses_everyToken_whenHs256ModeHasNoSecret() {
        assertThat(refusal(hsValidator(Option.empty()), hs256Token(claims(), SECRET))).isInstanceOf(SecurityError.KeyNotFound.class);
    }

    /// The classic confusion: the verifier is configured for RS256 with the real public key, and the attacker signs an
    /// HS256 token using that public key's bytes as the HMAC secret. JWKS mode must refuse on the algorithm, BEFORE a
    /// key lookup: the store here is empty and its URL unreachable, so a lookup would fail as KeyNotFound or
    /// JwksFetchFailed instead of InvalidCredentials.
    @Test
    @SuppressWarnings("JBCT-EX-01")
    void validate_refuses_hs256TokenSignedWithThePublicKey_inJwksMode_beforeAnyKeyLookup() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");

        kpg.initialize(2048);

        var publicKey = kpg.generateKeyPair().getPublic();
        var config = JwtConfig.jwtConfig("https://auth.example.com/.well-known/jwks.json").unwrap();
        var jwksMode = new JwtSecurityValidator(config, new JwksKeyStore("https://127.0.0.1:1/jwks", 3600));
        var forged = hmacToken("{\"alg\":\"HS256\",\"kid\":\"test-key-1\"}", claims(), publicKey.getEncoded(), "HmacSHA256");

        assertThat(refusal(jwksMode, forged)).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void validate_refuses_noneAlgorithm_inJwksMode_beforeAnyKeyLookup() {
        var config = JwtConfig.jwtConfig("https://auth.example.com/.well-known/jwks.json").unwrap();
        var jwksMode = new JwtSecurityValidator(config, new JwksKeyStore("https://127.0.0.1:1/jwks", 3600));
        var token = B64.encodeToString("{\"alg\":\"none\",\"kid\":\"k\"}".getBytes(StandardCharsets.UTF_8)) + "."
                    + B64.encodeToString(json(claims()).getBytes(StandardCharsets.UTF_8)) + ".";

        assertThat(refusal(jwksMode, token)).isInstanceOf(SecurityError.InvalidCredentials.class);
    }

    @Test
    void jwtConfig_toString_neverPrintsTheSecret() {
        var config = JwtConfig.jwtConfig("",
                                         Option.empty(),
                                         Option.empty(),
                                         "role",
                                         3600,
                                         30,
                                         AuthSchemes.DEFAULT,
                                         JwtKeyMode.HS256,
                                         Option.some(secret(SECRET_TEXT)))
                              .unwrap();

        assertThat(config.toString()).doesNotContain(SECRET_TEXT).contains("redacted");
    }
}
