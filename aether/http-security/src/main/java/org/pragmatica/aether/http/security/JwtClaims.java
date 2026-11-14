// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

/// The claims a login token is issued for (#2895): who it is for, how long it lives, and the caller's own claims.
/// `iss`, `aud`, `iat` and `exp` are NOT here: the signer owns them (issuer and audience from the configuration the
/// verifier uses, times from its clock), so a caller can neither forge nor omit them. Build it with [#jwtClaims];
/// the canonical constructor is public only because a record's must be.
public record JwtClaims(String subject, Duration lifetime, Map<String, Object> extra) {
    static final Set<String> RESERVED = Set.of("iss", "aud", "sub", "iat", "exp", "nbf");

    private static final Cause BLANK_SUBJECT = Causes.cause("JWT subject is blank");
    private static final Cause NON_POSITIVE_LIFETIME = Causes.cause("JWT lifetime must be positive");

    public JwtClaims {
        extra = Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    /// The claims, or the cause naming what is wrong: a blank subject, a non-positive lifetime, or an extra claim
    /// that collides with a registered one the signer owns (`iss`, `aud`, `sub`, `iat`, `exp`, `nbf`).
    public static Result<JwtClaims> jwtClaims(String subject, Duration lifetime, Map<String, Object> extra) {
        return Result.all(Verify.ensure(subject, Verify.Is::notBlank, BLANK_SUBJECT),
                          Verify.ensure(lifetime, JwtClaims::isPositive, NON_POSITIVE_LIFETIME),
                          Verify.ensure(extra, JwtClaims::noReserved, JwtClaims::reservedCause))
                     .map(JwtClaims::new);
    }

    private static boolean isPositive(Duration lifetime) {
        return !lifetime.isNegative() && !lifetime.isZero();
    }

    private static boolean noReserved(Map<String, Object> extra) {
        return Collections.disjoint(extra.keySet(), RESERVED);
    }

    private static Cause reservedCause(Map<String, Object> extra) {
        return Causes.cause("JWT extra claims may not set registered claims " + RESERVED + ", got " + extra.keySet());
    }
}
