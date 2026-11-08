// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;


public record JwtConfig(String jwksUrl,
                        Option<String> issuer,
                        Option<String> audience,
                        String roleClaim,
                        long cacheTtlSeconds,
                        long clockSkewSeconds,
                        List<String> authSchemes,
                        JwtKeyMode keyMode,
                        Option<JwtSecret> sharedSecret) {
    public static final String DEFAULT_ROLE_CLAIM = "role";
    public static final long DEFAULT_CACHE_TTL_SECONDS = 3600;
    public static final long DEFAULT_CLOCK_SKEW_SECONDS = 30;

    public static Result<JwtConfig> jwtConfig(String jwksUrl,
                                              Option<String> issuer,
                                              Option<String> audience,
                                              String roleClaim,
                                              long cacheTtlSeconds) {
        return jwtConfig(jwksUrl, issuer, audience, roleClaim, cacheTtlSeconds, DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public static Result<JwtConfig> jwtConfig(String jwksUrl,
                                              Option<String> issuer,
                                              Option<String> audience,
                                              String roleClaim,
                                              long cacheTtlSeconds,
                                              long clockSkewSeconds) {
        return jwtConfig(jwksUrl, issuer, audience, roleClaim, cacheTtlSeconds, clockSkewSeconds, AuthSchemes.DEFAULT);
    }

    /// #2156: naming the `Authorization` schemes JWT mode accepts. The list is checked by
    /// [AuthSchemes#authSchemes]; every shorter form uses [AuthSchemes#DEFAULT], which is `["Bearer"]`. JWKS mode.
    public static Result<JwtConfig> jwtConfig(String jwksUrl,
                                              Option<String> issuer,
                                              Option<String> audience,
                                              String roleClaim,
                                              long cacheTtlSeconds,
                                              long clockSkewSeconds,
                                              List<String> authSchemes) {
        return jwtConfig(jwksUrl,
                         issuer,
                         audience,
                         roleClaim,
                         cacheTtlSeconds,
                         clockSkewSeconds,
                         authSchemes,
                         JwtKeyMode.JWKS,
                         Option.empty());
    }

    /// #2880: the full form, naming how signatures are verified. [JwtKeyMode#JWKS] takes a `jwksUrl` and no secret;
    /// [JwtKeyMode#HS256] takes a secret (possibly not yet supplied, see [#hasKey]) and no `jwksUrl`, because HS mode
    /// never reads a JWKS and a URL beside it is a configuration that says two things.
    public static Result<JwtConfig> jwtConfig(String jwksUrl,
                                              Option<String> issuer,
                                              Option<String> audience,
                                              String roleClaim,
                                              long cacheTtlSeconds,
                                              long clockSkewSeconds,
                                              List<String> authSchemes,
                                              JwtKeyMode keyMode,
                                              Option<JwtSecret> sharedSecret) {
        return AuthSchemes.authSchemes(authSchemes)
                          .flatMap(schemes -> checkKeySource(jwksUrl, keyMode, sharedSecret).map(_ -> normalized(jwksUrl,
                                                                                                                 issuer,
                                                                                                                 audience,
                                                                                                                 roleClaim,
                                                                                                                 cacheTtlSeconds,
                                                                                                                 clockSkewSeconds,
                                                                                                                 schemes,
                                                                                                                 keyMode,
                                                                                                                 sharedSecret)));
    }

    private static Result<Unit> checkKeySource(String jwksUrl, JwtKeyMode keyMode, Option<JwtSecret> sharedSecret) {
        return switch (keyMode) {
            case JWKS -> sharedSecret.isPresent()
                         ? Causes.cause("a JWT shared secret is configured but the JWT mode is JWKS: set jwt_algorithm = \"HS256\" or remove the secret")
                                 .result()
                         : Result.unitResult();
            case HS256 -> jwksUrl.isBlank()
                          ? Result.unitResult()
                          : Causes.cause("jwks_url is set but jwt_algorithm is HS256: HS256 mode never reads a JWKS, remove jwks_url")
                                  .result();
        };
    }

    /// Whether a token can be verified at all: JWKS mode always has its endpoint; HS256 mode needs its secret.
    public boolean hasKey() {
        return keyMode == JwtKeyMode.JWKS || sharedSecret.isPresent();
    }

    private static JwtConfig normalized(String jwksUrl,
                                        Option<String> issuer,
                                        Option<String> audience,
                                        String roleClaim,
                                        long cacheTtlSeconds,
                                        long clockSkewSeconds,
                                        List<String> authSchemes,
                                        JwtKeyMode keyMode,
                                        Option<JwtSecret> sharedSecret) {
        return new JwtConfig(jwksUrl.trim(),
                             issuer,
                             audience,
                             roleClaim.isBlank()
                             ? DEFAULT_ROLE_CLAIM
                             : roleClaim,
                             cacheTtlSeconds <= 0
                             ? DEFAULT_CACHE_TTL_SECONDS
                             : cacheTtlSeconds,
                             clockSkewSeconds < 0
                             ? DEFAULT_CLOCK_SKEW_SECONDS
                             : clockSkewSeconds,
                             authSchemes,
                             keyMode,
                             sharedSecret);
    }

    /// The scheme a `WWW-Authenticate` challenge advertises: the first configured one.
    public String challengeScheme() {
        return authSchemes.getFirst();
    }

    public static Result<JwtConfig> jwtConfig(String jwksUrl) {
        return jwtConfig(jwksUrl,
                         Option.empty(),
                         Option.empty(),
                         DEFAULT_ROLE_CLAIM,
                         DEFAULT_CACHE_TTL_SECONDS,
                         DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public static Result<JwtConfig> jwtConfig(String jwksUrl, String issuer, String audience) {
        return jwtConfig(jwksUrl,
                         option(issuer),
                         option(audience),
                         DEFAULT_ROLE_CLAIM,
                         DEFAULT_CACHE_TTL_SECONDS,
                         DEFAULT_CLOCK_SKEW_SECONDS);
    }
}
