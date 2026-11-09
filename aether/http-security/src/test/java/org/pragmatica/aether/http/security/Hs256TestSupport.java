// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.pragmatica.aether.config.AuthSchemes;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.JwtKeyMode;
import org.pragmatica.aether.config.JwtSecret;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.fail;

/// Token and validator builders shared by the #2880 hardening tests: HS256 and JWKS validators with a 30 s clock skew,
/// tokens signed with HMAC-SHA256 or RS256, and the refusal a validator answers.
final class Hs256TestSupport {
    static final String SECRET_TEXT = "a-test-only-shared-secret-of-32-bytes!";
    static final byte[] SECRET = SECRET_TEXT.getBytes(StandardCharsets.UTF_8);
    static final String KID = "k1";

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private Hs256TestSupport() {}

    static JwtSecurityValidator hsValidator() {
        var config = JwtConfig.jwtConfig("", Option.empty(), Option.empty(), "role", 3600, 30, AuthSchemes.DEFAULT,
                                         JwtKeyMode.HS256, Option.some(JwtSecret.jwtSecret(SECRET_TEXT).unwrap()))
                              .unwrap();

        return new JwtSecurityValidator(config);
    }

    /// A JWKS-mode validator whose store already holds `pair`'s public key under [#KID] (no network).
    static JwtSecurityValidator jwksValidator(KeyPair pair) {
        var config = JwtConfig.jwtConfig("https://auth.example.com/.well-known/jwks.json").unwrap();
        var store = new JwksKeyStore("https://127.0.0.1:1/jwks", 3600);

        store.keyCache.put(KID, pair.getPublic());
        store.lastFetchTime.set(System.currentTimeMillis());

        return new JwtSecurityValidator(config, store);
    }

    @SuppressWarnings("JBCT-EX-01")
    static KeyPair rsaKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");

            generator.initialize(2048);

            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static String payload(String extraClaims) {
        return "{\"sub\":\"svc-1\",\"exp\":" + (now() + 3600) + extraClaims + "}";
    }

    static long now() {
        return System.currentTimeMillis() / 1000;
    }

    @SuppressWarnings("JBCT-EX-01")
    static String hs256Token(String headerJson, String payloadJson) {
        try {
            var signed = signedContent(headerJson, payloadJson);
            var mac = Mac.getInstance("HmacSHA256");

            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));

            return signed + "." + B64.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    static String rs256Token(KeyPair pair, String headerJson, String payloadJson) {
        try {
            var signed = signedContent(headerJson, payloadJson);
            var signature = Signature.getInstance("SHA256withRSA");

            signature.initSign(pair.getPrivate());
            signature.update(signed.getBytes(StandardCharsets.US_ASCII));

            return signed + "." + B64.encodeToString(signature.sign());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String signedContent(String headerJson, String payloadJson) {
        return B64.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
    }

    static boolean accepts(JwtSecurityValidator validator, String token) {
        return validator.validate(request(token), SecurityPolicy.bearerTokenRequired()).isSuccess();
    }

    static Cause refusal(JwtSecurityValidator validator, String token) {
        return validator.validate(request(token), SecurityPolicy.bearerTokenRequired())
                        .fold(cause -> cause, _ -> fail("token must be refused"));
    }

    private static HttpRequestContext request(String token) {
        return HttpRequestContext.httpRequestContext("/test", "GET", Map.of(),
                                                     Map.of("Authorization", List.of("Bearer " + token)),
                                                     new byte[0], "req");
    }
}
