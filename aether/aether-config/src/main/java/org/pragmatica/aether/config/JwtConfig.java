// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record JwtConfig(String jwksUrl,
                        Option<String> issuer,
                        Option<String> audience,
                        String roleClaim,
                        long cacheTtlSeconds,
                        long clockSkewSeconds) {
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
        return success(new JwtConfig(jwksUrl,
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
                                     : clockSkewSeconds));
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
