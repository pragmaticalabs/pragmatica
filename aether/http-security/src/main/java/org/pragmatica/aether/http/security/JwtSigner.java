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
import org.pragmatica.lang.utils.Causes;


/// Issues HS256 login tokens (#2895) the HS256 verifier of #2880 accepts. It is built from the SAME [JwtConfig] the
/// verifier reads: the shared secret, the issuer and the audience, so a token is valid for the verifier configured
/// beside it by construction.
///
/// Guarantees per operation: [#sign] yields a compact JWS with header `{"alg":"HS256","typ":"JWT"}` and claims `sub`,
/// `iat` (the clock now), `exp` (now plus the lifetime), `iss` and `aud` when the config names them, plus the caller's
/// extra claims; the MAC is HMAC-SHA256 over the signed content with the shared secret, the primitive the verifier
/// checks with. A token is NOT revocable: it stays valid until `exp`.
public record JwtSigner(JwtConfig config, JwtSecret secret, Clock clock) {
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Cause NO_SECRET = Causes.cause("No HS256 shared secret is configured: cannot sign");
    private static final Cause NOT_HS256 = Causes.cause("JWT signing needs HS256 mode: the configured mode is JWKS");

    /// The compact JWS for `claims`, or a cause when the claims cannot be serialized or the MAC cannot be computed.
    public Result<String> sign(JwtClaims claims) {
        return JsonMapper.defaultJsonMapper()
                         .writeAsString(payload(claims))
                         .flatMap(this::signContent);
    }

    /// A signer on the system clock; see [#jwtSigner(JwtConfig, Clock)].
    public static Result<JwtSigner> jwtSigner(JwtConfig config) {
        return jwtSigner(config, Clock.systemUTC());
    }

    /// A signer for an HS256-mode config that carries its secret, or a cause: signing under JWKS mode, or without a
    /// secret, is refused here instead of producing a token nothing would accept.
    public static Result<JwtSigner> jwtSigner(JwtConfig config, Clock clock) {
        return requireHs256(config).flatMap(_ -> config.sharedSecret()
                                                       .toResult(NO_SECRET))
                           .map(secret -> new JwtSigner(config, secret, clock));
    }

    private static Result<JwtConfig> requireHs256(JwtConfig config) {
        return config.keyMode() == JwtKeyMode.HS256
               ? Result.success(config)
               : NOT_HS256.result();
    }

    private Result<String> signContent(String payloadJson) {
        var signed = encode(HEADER_JSON) + "." + encode(payloadJson);

        return Result.lift(JwtSigner::signingFailed,
                           () -> JwtSignatureVerifier.macOf(signed, secret))
                     .map(mac -> signed + "." + BASE64URL.encodeToString(mac));
    }

    private Map<String, Object> payload(JwtClaims claims) {
        var iat = clock.instant().getEpochSecond();
        var registered = Stream.of(Option.some(Map.entry("sub",
                                                         (Object) claims.subject())),
                                   Option.some(Map.entry("iat",
                                                         (Object) iat)),
                                   Option.some(Map.entry("exp",
                                                         (Object)(iat + claims.lifetime().toSeconds()))),
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

    private static Cause signingFailed(Throwable t) {
        return Causes.cause("JWT signing failed: " + t.getMessage());
    }
}
