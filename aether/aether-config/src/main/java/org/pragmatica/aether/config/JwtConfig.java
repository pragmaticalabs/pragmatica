// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;


public record JwtConfig(String jwksUrl,
                        Option<String> issuer,
                        Option<String> audience,
                        String roleClaim,
                        long cacheTtlSeconds,
                        long clockSkewSeconds,
                        List<String> authSchemes) {
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

    /// #2156: the full form, naming the `Authorization` schemes JWT mode accepts. The list is checked by
    /// [AuthSchemes#authSchemes]; every shorter form uses [AuthSchemes#DEFAULT], which is `["Bearer"]`.
    public static Result<JwtConfig> jwtConfig(String jwksUrl,
                                              Option<String> issuer,
                                              Option<String> audience,
                                              String roleClaim,
                                              long cacheTtlSeconds,
                                              long clockSkewSeconds,
                                              List<String> authSchemes) {
        return AuthSchemes.authSchemes(authSchemes).map(schemes -> normalized(jwksUrl,
                                                                              issuer,
                                                                              audience,
                                                                              roleClaim,
                                                                              cacheTtlSeconds,
                                                                              clockSkewSeconds,
                                                                              schemes));
    }

    private static JwtConfig normalized(String jwksUrl,
                                        Option<String> issuer,
                                        Option<String> audience,
                                        String roleClaim,
                                        long cacheTtlSeconds,
                                        long clockSkewSeconds,
                                        List<String> authSchemes) {
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
                             authSchemes);
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
