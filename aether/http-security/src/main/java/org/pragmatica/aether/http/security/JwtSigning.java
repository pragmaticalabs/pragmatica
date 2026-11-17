// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.JwtKeyMode;
import org.pragmatica.aether.config.JwtSecret;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// The steps behind [JwtSigner], package-private so the public surface is the interface and its factory alone.
final class JwtSigning {
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    static final Cause NO_SECRET = new SecurityError.KeyNotFound("No HS256 shared secret is configured: cannot sign");

    static final Cause NOT_HS256 = new SecurityError.KeyNotFound("JWT signing needs HS256 mode: the configured mode is JWKS");

    static final Cause SECRET_TOO_SHORT = new SecurityError.KeyNotFound("HS256 shared secret is shorter than " + JwtSecret.MIN_BYTES
                                                                       + " bytes: cannot sign");

    private JwtSigning() {}

    static Result<JwtConfig> requireHs256(JwtConfig config) {
        return config.keyMode() == JwtKeyMode.HS256
               ? Result.success(config)
               : NOT_HS256.result();
    }

    static Result<JwtSecret> requireLongEnough(JwtSecret secret) {
        return secret.value().length >= JwtSecret.MIN_BYTES
               ? Result.success(secret)
               : SECRET_TOO_SHORT.result();
    }

    static Result<String> sign(JwtConfig config, JwtSecret secret, Clock clock, JwtClaims claims) {
        return payload(config, clock, claims).flatMap(JwtSigning::toJson)
                      .flatMap(json -> signContent(json, secret));
    }

    private static Result<String> toJson(Map<String, Object> payload) {
        return JsonMapper.defaultJsonMapper()
                         .writeAsString(payload)
                         .mapError(JwtSigning::serializationFailed);
    }

    private static Result<String> signContent(String payloadJson, JwtSecret secret) {
        var signed = encode(HEADER_JSON) + "." + encode(payloadJson);

        return Result.lift(JwtSigning::signingFailed,
                           () -> JwtSignatureVerifier.macOf(signed, secret))
                     .map(mac -> signed + "." + BASE64URL.encodeToString(mac));
    }

    private static Result<Map<String, Object>> payload(JwtConfig config, Clock clock, JwtClaims claims) {
        return Result.lift(JwtSigning::payloadFailed, () -> buildPayload(config, clock, claims));
    }

    private static Map<String, Object> buildPayload(JwtConfig config, Clock clock, JwtClaims claims) {
        var iat = clock.instant().getEpochSecond();
        var registered = Stream.of(Option.some(Map.entry("sub",
                                                         (Object) claims.subject())),
                                   Option.some(Map.entry("iat",
                                                         (Object) iat)),
                                   Option.some(Map.entry("exp",
                                                         (Object) Math.addExact(iat,
                                                                                claims.lifetime().toSeconds()))),
                                   config.issuer().map(iss -> Map.entry("iss",
                                                                        (Object) iss)),
                                   config.audience().map(aud -> Map.entry("aud",
                                                                          (Object) aud)))
                               .flatMap(Option::stream);

        return Stream.concat(claims.extra().entrySet().stream(),
                             registered)
                     .collect(Collectors.toMap(Map.Entry::getKey,
                                               Map.Entry::getValue,
                                               (_, later) -> later,
                                               LinkedHashMap::new));
    }

    private static String encode(String json) {
        return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Cause serializationFailed(Cause cause) {
        return new SecurityError.InvalidCredentials("JWT claims cannot be serialized: " + cause.message());
    }

    private static Cause payloadFailed(Throwable t) {
        return new SecurityError.InvalidCredentials("JWT claims cannot form a payload: " + t.getMessage());
    }

    private static Cause signingFailed(Throwable t) {
        return new SecurityError.SignatureInvalid("JWT signing failed: " + t.getMessage());
    }
}
