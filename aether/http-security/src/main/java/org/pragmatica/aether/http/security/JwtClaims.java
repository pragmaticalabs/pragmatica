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


/// The claims a login token is issued for (#2895): who it is for, how long it lives, and the caller's own claims.
/// `iss`, `aud`, `iat` and `exp` are NOT here: the signer owns them (issuer and audience from the configuration the
/// verifier uses, times from its clock), so a caller can neither forge nor omit them. Build it with [#jwtClaims];
/// the canonical constructor is public only because a record's must be.
public record JwtClaims(String subject, Duration lifetime, Map<String, Object> extra) {
    static final Set<String> RESERVED = Set.of("iss", "aud", "sub", "iat", "exp", "nbf");
    /// Upper bound of a login token's lifetime. A guess at a safe ceiling (ten years): far beyond any login session,
    /// far below the point where `iat + lifetime` could wrap `exp`; raise it if a real caller needs more.
    static final Duration MAX_LIFETIME = Duration.ofDays(3650);
    private static final Cause BLANK_SUBJECT = new SecurityError.InvalidCredentials("JWT subject is blank");

    private static final Cause LIFETIME_OUT_OF_RANGE = new SecurityError.InvalidCredentials("JWT lifetime must be positive and at most " + MAX_LIFETIME);

    public JwtClaims {
        extra = Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    /// The claims, or the [SecurityError] naming what is wrong: a blank subject, a lifetime that is not positive or is
    /// beyond [#MAX_LIFETIME], or an extra claim
    /// that collides with a registered one the signer owns (`iss`, `aud`, `sub`, `iat`, `exp`, `nbf`).
    public static Result<JwtClaims> jwtClaims(String subject, Duration lifetime, Map<String, Object> extra) {
        return Result.all(Verify.ensure(subject, Verify.Is::notBlank, BLANK_SUBJECT),
                          Verify.ensure(lifetime, JwtClaims::inRange, LIFETIME_OUT_OF_RANGE),
                          Verify.ensure(extra, JwtClaims::noReserved, JwtClaims::reservedCause))
                     .map(JwtClaims::new)
                     .mapError(JwtClaims::asSecurityError);
    }

    /// `Result.all` collects every failure into one composite cause; the caller gets it as a single [SecurityError]
    /// carrying all the messages.
    private static Cause asSecurityError(Cause cause) {
        return cause instanceof SecurityError
               ? cause
               : new SecurityError.InvalidCredentials(cause.message());
    }

    private static boolean inRange(Duration lifetime) {
        return ! lifetime.isNegative()
               && !lifetime.isZero()
               && lifetime.compareTo(MAX_LIFETIME) <= 0;
    }

    private static boolean noReserved(Map<String, Object> extra) {
        return Collections.disjoint(extra.keySet(), RESERVED);
    }

    private static Cause reservedCause(Map<String, Object> extra) {
        return new SecurityError.InvalidCredentials("JWT extra claims may not set registered claims " + RESERVED
                                                   + ", got " + extra.keySet());
    }
}
